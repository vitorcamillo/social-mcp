package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Varios processos do MCP (um por cliente stdio) sobre o mesmo SOCIAL_MCP_HOME. */
class ExecucaoConcorrenteTest {

    @TempDir
    Path tmp;

    @org.junit.jupiter.api.AfterEach
    void nenhumaFilaFicaRetidaDepoisDoTeste() throws Exception {
        var campo = Store.class.getDeclaredField("SEMAFOROS");
        campo.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) campo.get(null)).isEmpty(), "fila retida sem usuario");
    }

    private Store store() throws Exception {
        return TextGateTest.storeComGate(tmp, "exit 0", 30);
    }

    private static JsonNode acoes() {
        return Json.parse("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/"},
                 {"network":"bluesky","kind":"click","selector":"css:button"},
                 {"network":"bluesky","kind":"verify_attr","selector":"css:button","attr":"aria-pressed","expect":"true"}]
                """);
    }

    private static String aprovado(BatchStore bs) throws Exception {
        String id = bs.draft("t", acoes()).get("id").asText();
        bs.approve(id, "humano", null);
        return id;
    }

    @Test
    void soUmProcessoReservaOLote() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = aprovado(bs);
        JsonNode previa = bs.requireApproved(id);
        JsonNode reservado = bs.claim(id, previa);
        assertEquals(id, reservado.path("id").asText());
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".running.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
        // segundo processo: nem a conferencia nem a reserva passam
        IllegalStateException e1 = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e1.getMessage().contains("em execucao"), e1.getMessage());
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> bs.claim(id, previa));
        assertTrue(e2.getMessage().contains("em execucao"), e2.getMessage());
        // em execucao nao se revoga (o kill-switch para)
        IllegalStateException e3 = assertThrows(IllegalStateException.class, () -> bs.reject(id, "x"));
        assertTrue(e3.getMessage().contains("em execucao"), e3.getMessage());
        assertTrue(bs.list().stream().anyMatch(l -> l.contains(id) && l.contains("approved")), bs.list().toString());
        bs.markExecuted(id, Json.obj().put("passosExecutados", 3).put("conferencia", "forte"));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".running.json")));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".executed.json")));
        assertTrue(String.join("\n", s.tail(5)).contains("\"conferencia\":\"forte\""));
    }

    @Test
    void claimAposRevogacaoFalhaSemCriarRunning() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = aprovado(bs);
        JsonNode previa = bs.requireApproved(id);
        bs.reject(id, "revogado");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.claim(id, previa));
        assertTrue(e.getMessage().contains("aprovado"), e.getMessage());
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".running.json")));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".rejected.json")));
    }

    // ---------- aprovacao x rejeicao concorrentes ----------

    @Test
    void transicaoDoLoteRecusaSemATravaDoLote() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("t", acoes()).get("id").asText();
        try (Store.Trava t = s.tryLock(BatchStore.loteLock(id))) {
            assertNotNull(t);
            IllegalStateException ea = assertThrows(IllegalStateException.class,
                    () -> bs.approve(id, "orquestrador", "nota"));
            assertTrue(ea.getMessage().contains("em transicao"), ea.getMessage());
            IllegalStateException er = assertThrows(IllegalStateException.class, () -> bs.reject(id, "x"));
            assertTrue(er.getMessage().contains("em transicao"), er.getMessage());
            assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")), "sem a trava, nada muda");
        }
        assertEquals("PASS", bs.approve(id, "orquestrador", "nota"), "solta a trava: aprova");
    }

    @Test
    void aprovacaoConcorrenteComRejeicaoNuncaDeixaLoteRejeitadoAprovado() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            Store s = store();
            BatchStore bs = new BatchStore(s);
            BatchStore outro = new BatchStore(s);
            String id = bs.draft("corrida", acoes()).get("id").asText();
            CountDownLatch vai = new CountDownLatch(1);
            AtomicBoolean approveOk = new AtomicBoolean();
            AtomicBoolean rejectOk = new AtomicBoolean();
            AtomicReference<Throwable> erroApprove = new AtomicReference<>();
            AtomicReference<Throwable> erroReject = new AtomicReference<>();
            Thread tA = new Thread(() -> {
                aguardar(vai);
                try {
                    outro.approve(id, "orquestrador", "nota");
                    approveOk.set(true);
                } catch (Throwable t) {
                    erroApprove.set(t);
                }
            });
            Thread tR = new Thread(() -> {
                aguardar(vai);
                try {
                    bs.reject(id, "rejeitado na corrida");
                    rejectOk.set(true);
                } catch (Throwable t) {
                    erroReject.set(t);
                }
            });
            tA.start();
            tR.start();
            vai.countDown();
            tA.join(TimeUnit.SECONDS.toMillis(15));
            tR.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(tA.isAlive(), "approve travou na rodada " + rodada);
            assertFalse(tR.isAlive(), "reject travou na rodada " + rodada);
            assertTrue(approveOk.get() || rejectOk.get(),
                    "rodada " + rodada + ": ao menos um vence (approve=" + approveOk.get()
                            + ", reject=" + rejectOk.get() + ", erros=" + erroApprove.get() + "/" + erroReject.get() + ")");
            boolean approved = Files.exists(s.batchesDir().resolve(id + ".approved.json"));
            boolean rejected = Files.exists(s.batchesDir().resolve(id + ".rejected.json"));
            assertFalse(approved && rejected, "rodada " + rodada + ": lote aprovado E rejeitado ao mesmo tempo");
            if (rejectOk.get()) {
                // approve pode ter concluido antes do reject pegar a trava: revogar lote
                // aprovado e legitimo, entao ambos vencem e o estado final e rejeitado
                assertTrue(rejected, "rodada " + rodada);
                if (!approveOk.get()) {
                    assertTrue(erroApprove.get() instanceof IllegalStateException, String.valueOf(erroApprove.get()));
                }
                assertThrows(IllegalStateException.class, () -> bs.requireApproved(id),
                        "lote rejeitado nao executa");
            } else {
                assertTrue(approved, "rodada " + rodada);
                assertTrue(erroReject.get() instanceof IllegalStateException, String.valueOf(erroReject.get()));
            }
        }
    }

    @Test
    void rejeicaoNoPontoDeCommitImpedeAprovacao() throws Exception {
        Store s = store();
        Path media = Files.write(tmp.resolve("aprovacao.png"), new byte[]{1, 2, 3});
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.get("defaults")).putArray("uploadRoots").add(tmp.toString());
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        BatchStore bs = new BatchStore(s);
        JsonNode actions = Json.parse("[{\"network\":\"bluesky\",\"kind\":\"set_file\","
                + "\"selector\":\"css:input[type=file]\",\"path\":"
                + Json.write(com.fasterxml.jackson.databind.node.TextNode.valueOf(media.toString())) + "},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\"}]");
        String id = bs.draft("t", actions).get("id").asText();
        bs.antesDeConfirmarAprovacao = () -> {
            try {
                // rejeicao que nao passa pela trava do lote (ex.: processo de versao anterior)
                Files.move(s.batchesDir().resolve(id + ".draft.json"), s.batchesDir().resolve(id + ".rejected.json"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> bs.approve(id, "humano", null, List.of(), true));
        assertTrue(e.getMessage().contains("foi rejeitado"), e.getMessage());
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".rejected.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approving.json")));
        try (var files = Files.list(s.batchesDir())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(id + "-media-")));
        }
    }

    @Test
    void rejeicaoDepoisDaAprovacaoRevoga() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("t", acoes()).get("id").asText();
        bs.approve(id, "humano", null, List.of(), true);
        bs.reject(id, "x");

        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".rejected.json")));
        assertTrue(String.join("\n", s.tail(10)).contains("batch_revoked"));
    }

    @Test
    void approveERejectConcorrentesNuncaCoexistem() throws Exception {
        for (int rodada = 0; rodada < 30; rodada++) {
            Path home = tmp.resolve("approve-reject-" + rodada);
            Store s = TextGateTest.storeComGate(home, "exit 0", 30);
            BatchStore aprovar = new BatchStore(s);
            BatchStore rejeitar = new BatchStore(s);
            String id = aprovar.draft("t", acoes()).get("id").asText();
            CountDownLatch vai = new CountDownLatch(1);
            AtomicReference<Throwable> erroApprove = new AtomicReference<>();
            AtomicReference<Throwable> erroReject = new AtomicReference<>();
            Thread tApprove = new Thread(() -> {
                aguardar(vai);
                try {
                    aprovar.approve(id, "humano", null, List.of(), true);
                } catch (Throwable t) {
                    erroApprove.set(t);
                }
            });
            Thread tReject = new Thread(() -> {
                aguardar(vai);
                try {
                    rejeitar.reject(id, "corrida");
                } catch (Throwable t) {
                    erroReject.set(t);
                }
            });
            tApprove.start();
            tReject.start();
            vai.countDown();
            tApprove.join(TimeUnit.SECONDS.toMillis(10));
            tReject.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(tApprove.isAlive(), "approve travou na rodada " + rodada);
            assertFalse(tReject.isAlive(), "reject travou na rodada " + rodada);
            Throwable ea = erroApprove.get();
            Throwable er = erroReject.get();
            assertTrue(ea == null || ea instanceof IllegalStateException, String.valueOf(ea));
            assertTrue(er == null || er instanceof IllegalStateException, String.valueOf(er));
            boolean approved = Files.exists(s.batchesDir().resolve(id + ".approved.json"));
            boolean rejected = Files.exists(s.batchesDir().resolve(id + ".rejected.json"));
            assertTrue(approved ^ rejected, "rodada " + rodada + ": approved=" + approved + ", rejected=" + rejected);
            assertFalse(Files.exists(s.batchesDir().resolve(id + ".approving.json")), "rodada " + rodada);
        }
    }

    @Test
    void revogacaoEReservaNaoCoexistem() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            Store s = store();
            BatchStore bs = new BatchStore(s);
            BatchStore outro = new BatchStore(s);
            String id = aprovado(bs);
            JsonNode previa = bs.requireApproved(id);
            CountDownLatch vai = new CountDownLatch(1);
            AtomicBoolean claimOk = new AtomicBoolean();
            AtomicBoolean rejectOk = new AtomicBoolean();
            AtomicReference<Throwable> erroClaim = new AtomicReference<>();
            AtomicReference<Throwable> erroReject = new AtomicReference<>();
            Thread tClaim = new Thread(() -> {
                aguardar(vai);
                try {
                    outro.claim(id, previa);
                    claimOk.set(true);
                } catch (Throwable t) {
                    erroClaim.set(t);
                }
            });
            Thread tReject = new Thread(() -> {
                aguardar(vai);
                try {
                    bs.reject(id, "corrida");
                    rejectOk.set(true);
                } catch (Throwable t) {
                    erroReject.set(t);
                }
            });
            tClaim.start();
            tReject.start();
            vai.countDown();
            tClaim.join(TimeUnit.SECONDS.toMillis(10));
            tReject.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(tClaim.isAlive(), "claim travou na rodada " + rodada);
            assertFalse(tReject.isAlive(), "reject travou na rodada " + rodada);
            assertTrue(claimOk.get() ^ rejectOk.get(),
                    "rodada " + rodada + ": exatamente um vence (claim=" + claimOk.get()
                            + ", reject=" + rejectOk.get() + ", erros=" + erroClaim.get() + "/" + erroReject.get() + ")");
            boolean running = Files.exists(s.batchesDir().resolve(id + ".running.json"));
            boolean rejected = Files.exists(s.batchesDir().resolve(id + ".rejected.json"));
            assertFalse(running && rejected, "rodada " + rodada + ": running e rejected coexistem");
            if (claimOk.get()) {
                assertTrue(running, "rodada " + rodada);
                assertFalse(rejected, "rodada " + rodada);
                assertTrue(erroReject.get() instanceof IllegalStateException, String.valueOf(erroReject.get()));
            } else {
                assertTrue(rejected, "rodada " + rodada);
                assertFalse(running, "rodada " + rodada);
                assertTrue(erroClaim.get() instanceof IllegalStateException, String.valueOf(erroClaim.get()));
            }
        }
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reservaRecusaArquivoTrocadoEntreConferenciaEReserva() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = aprovado(bs);
        JsonNode previa = bs.requireApproved(id);
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        Files.writeString(ap, Files.readString(ap).replace("css:button", "css:a.outro"), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.claim(id, previa));
        assertTrue(e.getMessage().contains("mudou"), e.getMessage());
        assertTrue(Files.exists(ap), "reserva desfeita: o lote volta a approved (e o hash o barra)");
    }

    @Test
    void batchIdComCaminhoRecusado() throws Exception {
        BatchStore bs = new BatchStore(store());
        assertThrows(IllegalArgumentException.class, () -> bs.requireApproved("../../x"));
        assertThrows(IllegalArgumentException.class, () -> bs.requireApproved("a/b"));
    }

    @Test
    void travaNaoEntraDuasVezesNaMesmaJvm() throws Exception {
        Store s = store();
        try (Store.Trava t = s.tryLock("exec-bluesky")) {
            assertNotNull(t);
            assertNull(s.tryLock("exec-bluesky"));
            try (Store.Trava outra = s.tryLock("exec-x")) {
                assertNotNull(outra, "rede diferente nao bloqueia");
            }
        }
        try (Store.Trava t = s.tryLock("exec-bluesky")) {
            assertNotNull(t, "solta ao fechar");
        }
        assertThrows(IllegalArgumentException.class, () -> s.tryLock("../x"));
    }

    /** Segundo processo segura a trava; esta JVM nao consegue pegar. */
    @Test
    void travaValeEntreProcessos() throws Exception {
        Store s = store();
        String java = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        Process p = new ProcessBuilder(java, "-cp", cp, TravaHolder.class.getName(), s.home.toString(), "exec-bluesky")
                .redirectErrorStream(true).start();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("travado", r.readLine());
            assertNull(s.tryLock("exec-bluesky"), "outro processo segura a trava");
        } finally {
            p.getOutputStream().close();
            assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        }
        try (Store.Trava t = s.tryLock("exec-bluesky")) {
            assertNotNull(t, "processo saiu: trava livre");
        }
    }

    /** Main do processo filho: pega a trava, avisa e segura ate o stdin fechar. */
    public static final class TravaHolder {
        public static void main(String[] args) throws Exception {
            Store s = new Store(Path.of(args[0]));
            try (Store.Trava t = s.tryLock(args[1])) {
                System.out.println(t == null ? "ocupado" : "travado");
                System.out.flush();
                while (System.in.read() >= 0) {
                    // espera o pai fechar o stdin
                }
            }
        }
    }

    @Test
    void executeBatchRecusaSeOutroProcessoExecutaARede() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = aprovado(bs);
        Tools tools = new Tools(s);
        try (Store.Trava t = s.tryLock("exec-bluesky")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> tools.call("social_execute_batch", Json.obj().put("batchId", id)));
            assertTrue(e.getMessage().contains("outro processo"), e.getMessage());
        } finally {
            tools.shutdown();
        }
        assertDoesNotThrow(() -> bs.requireApproved(id), "lote segue aprovado, nada rodou");
        assertFalse(s.batchStarted(id));
    }

    @Test
    void setStateDeVariosProcessosNaoPerdeRede() throws Exception {
        Store a = store();
        Store b = new Store(a.home);
        a.setState("bluesky", "cooldown", "x", "t");
        b.setState("x", "paused", "y", "t");
        assertEquals("cooldown", a.effectiveState("bluesky"));
        assertEquals("paused", a.effectiveState("x"));
        assertTrue(Files.exists(a.locksDir().resolve("health.lock")));
    }

    @Test
    void inicializacaoComHealthAusenteNaoApagaEstadoGravado() throws Exception {
        for (int rodada = 0; rodada < 25; rodada++) {
            Store s = store();
            Files.delete(s.healthPath()); // dois processos veem o mesmo home "novo"
            AtomicReference<Throwable> erro = new AtomicReference<>();
            Thread b = new Thread(() -> {
                try {
                    new Store(s.home);
                } catch (Throwable t) {
                    erro.set(t);
                }
            });
            b.start();
            Store c = new Store(s.home); // segundo inicializando em paralelo...
            c.setState("bluesky", "cooldown", "anti-bot", "auto"); // ...e gravando estado
            b.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(b.isAlive(), "construtor travou na rodada " + rodada);
            assertNull(erro.get(), String.valueOf(erro.get()));
            assertEquals("cooldown", s.effectiveState("bluesky"), "rodada " + rodada + ": init apagou o estado");
        }
        // reabrir um home com estado tambem nao pode voltar para {}
        Store s = store();
        s.setState("x", "paused", "motivo", "t");
        new Store(s.home);
        assertEquals("paused", s.effectiveState("x"));
    }

    @Test
    void journalComRegistrosGrandesNaoIntercalaEntreProcessos() throws Exception {
        Store s = store();
        String java = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        Process p = new ProcessBuilder(java, "-cp", cp, JournalWriter.class.getName(), s.home.toString(), "40")
                .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("pronto", r.readLine(), "escritor filho nao sinalizou");
        ObjectNode detalhe = Json.obj().put("desc", "x".repeat(32 * 1024)); // acima dos 8192 bytes por bloco
        for (int i = 0; i < 40; i++) s.appendJournal("action", "bluesky", detalhe);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "escritor filho travou");
        assertEquals(0, p.exitValue());
        int acoes = 0;
        for (String linha : Files.readAllLines(s.journalPath(), StandardCharsets.UTF_8)) {
            if (linha.isBlank()) continue;
            JsonNode e = Json.parse(linha); // lanca se blocos de dois processos intercalaram
            if ("action".equals(e.path("type").asText())) acoes++;
        }
        assertEquals(80, acoes, "cada gravacao chega inteira (40 desta JVM + 40 do processo filho)");
    }

    /** Main do processo filho: grava N registros grandes no journal do home informado. */
    public static final class JournalWriter {
        public static void main(String[] args) throws Exception {
            Store s = new Store(Path.of(args[0]));
            System.out.println("pronto");
            System.out.flush();
            ObjectNode detalhe = Json.obj().put("desc", "y".repeat(32 * 1024));
            for (int i = 0; i < Integer.parseInt(args[1]); i++) s.appendJournal("action", "bluesky", detalhe);
        }
    }

    @Test
    void passoPublicoFalhadoDepoisDeDespacharVaiAoJournal() throws Exception {
        Store s = store();
        JsonNode a = Json.parse("{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\",\"description\":\"enviar resposta\"}");
        ObjectNode step = Json.obj();
        step.put("ok", false);
        step.put("erro", "timeout do CDP depois do mouseReleased");
        step.put("modo", "mouse");
        Tools.journalAction(s, "bluesky", "click", a, "b1", step, false);
        String j = String.join("\n", s.tail(1));
        assertTrue(j.contains("\"type\":\"action\""), j);
        assertTrue(j.contains("\"cls\":\"public\""), j);
        assertTrue(j.contains("\"batchId\":\"b1\""), j);
        // a tentativa conta para cota e espacamento
        Store.Usage u = s.usageFor("bluesky");
        assertEquals(1, u.publicActions);
        assertTrue(u.lastPublicActionMs > 0);
    }

    @Test
    void tripwireEmLeituraColocaRedeEmCooldownERegistra() throws Exception {
        Store s = store();
        ObjectNode out = Json.obj();
        Tools.reagirTripwire(s, "bluesky", out, "padrao de anti-bot detectado ao ler a pagina");
        assertEquals("cooldown", s.effectiveState("bluesky"));
        assertEquals("padrao de anti-bot detectado ao ler a pagina", s.stateReason("bluesky"));
        assertTrue(out.path("AVISO").asText().contains("cooldown"), out.toString());
        assertTrue(String.join("\n", s.tail(3)).contains("state_change"), "evento registrado no journal");
    }

    // ---------- conferencia do resultado ----------

    private static String conf(String json) {
        return BatchStore.conferencia(Json.parse(json));
    }

    @Test
    void conferenciaPorEstadoDeElementoEForte() {
        assertEquals("forte", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"verify_attr","selector":"css:a","attr":"aria-pressed","expect":"true"}]"""));
        assertEquals("forte", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"goto","url":"https://x"},{"kind":"verify_text","selector":"css:h1","expect":"oi"}]"""));
        assertEquals("forte", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"wait","selector":"css:dialog","expect":"publicado","timeoutMs":1000}]"""));
        assertEquals("forte", conf("""
                [{"kind":"goto","url":"https://x"},{"kind":"request","method":"POST","url":"https://x","expectStatus":[200],"expectBody":"\\"uri\\""}]"""));
    }

    @Test
    void conferenciaVerifyAbsentIsoladoEFracaEComProvaPositivaEForte() {
        // sem prova de que a pagina carregou, a ausencia pode ser login/pagina vazia
        assertEquals("fraca", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"verify_absent","selector":"css:a"}]"""));
        assertEquals("forte", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"goto","url":"https://x"},{"kind":"verify_text","selector":"css:h1","expect":"x"},{"kind":"verify_absent","selector":"css:a"}]"""));
        assertEquals("fraca", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"verify_absent","selector":"css:a"},{"kind":"verify_text","expect":"sumiu"}]"""));
    }

    @Test
    void conferenciaPorTextoSoltoEFracaESemConferirEAusente() {
        // licao de 22/10: o texto digitado continua no editor e o verify_text solto passa
        assertEquals("fraca", conf("""
                [{"kind":"type","text":"oi"},{"kind":"click","selector":"css:a"},{"kind":"verify_text","expect":"oi"}]"""));
        assertEquals("fraca", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"wait","expect":"publicad","timeoutMs":1000}]"""));
        assertEquals("ausente", conf("""
                [{"kind":"click","selector":"css:a"},{"kind":"goto","url":"https://x"}]"""));
        assertEquals("ausente", conf("""
                [{"kind":"verify_attr","selector":"css:a","attr":"x","expect":"1"},{"kind":"click","selector":"css:a"}]"""));
        assertEquals("sem_publico", conf("""
                [{"kind":"goto","url":"https://x"},{"kind":"verify_text","expect":"oi"}]"""));
    }

    @Test
    void requestSemCorpoConferidoOuComStatusDeErroNaoEForte() {
        // codex 22/10: expectStatus:[400] recebendo 400 passava como prova forte
        assertEquals("fraca", conf("""
                [{"kind":"request","method":"POST","url":"https://x","expectStatus":[400],"expectBody":"ok"}]"""));
        assertEquals("fraca", conf("""
                [{"kind":"request","method":"POST","url":"https://x","expectStatus":[200]}]"""));
        assertEquals("forte", conf("""
                [{"kind":"request","method":"POST","url":"https://x"},{"kind":"verify_attr","selector":"css:a","attr":"x","expect":"1"}]"""));
    }

    @Test
    void ifPresentSoPulaComAusenciaComprovada() throws Exception {
        String java = Tools.presencaScript("css:div[data-e2e=local_draft_container] button");
        assertTrue(java.contains("document.querySelector(\"div[data-e2e=local_draft_container] button\")"), java);
        assertTrue(Tools.presencaScript("xpath://button[normalize-space()='Descartar']").contains("document.evaluate("));
        assertTrue(Tools.presencaScript("id:x").contains("getElementById(\"x\")"));
        assertTrue(Tools.presencaScript("css:a >>> b").startsWith("(function(){try{return (!!(function(){"));
        // seletor invalido devolve erro (o passo falha), nao "0" (que pularia o clique)
        String js = "var document={querySelector:function(s){if(s.indexOf('[[')>=0){throw new Error('invalid selector');}"
                + "return s==='a'?{}:null;}};"
                + "console.log([" + Tools.presencaScript("css:a") + "," + Tools.presencaScript("css:b") + ","
                + Tools.presencaScript("css:a[[") + "].join('|'));";
        Path f = Files.writeString(tmp.resolve("p.js"), js);
        Process pr;
        try {
            pr = new ProcessBuilder("node", f.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("node ausente no PATH");
            return;
        }
        String saida = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(pr.waitFor(20, TimeUnit.SECONDS));
        assertEquals("1|0|erro:invalid selector", saida);
    }

    @Test
    void fetchRejeitadoViraIncerto() {
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.obj();
        String resp = "{\"s\":0,\"b\":\"erro fetch: TypeError: Failed to fetch\"}";
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Tools.runRequest(
                js -> js.startsWith("(window[") ? resp : "ok",
                Json.parse("{\"network\":\"x\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://x.com/a\"}"), out));
        assertTrue(e.getMessage().contains("incerto"), e.getMessage());
        assertTrue(out.path("incerto").asBoolean(false));
        assertTrue(Tools.talvezAgiu("request", e, out), "conta na cota");
    }

    @Test
    void conferenciaFinalConsideraAborto() {
        JsonNode forte = Json.parse("""
                [{"kind":"click","selector":"css:a"},{"kind":"verify_attr","selector":"css:a","attr":"x","expect":"1"}]""");
        assertEquals("forte", Tools.conferenciaFinal(forte, false, true));
        assertEquals("abortado_apos_publico", Tools.conferenciaFinal(forte, true, true));
        assertEquals("nenhum_passo_publico_feito", Tools.conferenciaFinal(forte, true, false));
        assertEquals("sem_publico", Tools.conferenciaFinal(Json.parse("[{\"kind\":\"goto\",\"url\":\"https://x\"}]"), false, false));
        for (String c : List.of("fraca", "ausente", "abortado_apos_publico")) {
            assertTrue(Tools.avisoConferencia(c).contains("onfir") || Tools.avisoConferencia(c).contains("onferir"), c);
        }
    }

    @Test
    void falhaAntesDoEfeitoNaoContaComoPublicoFeito() {
        JsonNode vazio = Json.obj();
        // clique abortado antes de apertar / elemento ausente / foco errado no type: nada publicado
        assertFalse(Tools.talvezAgiu("click", new RealClick.NaoAgiu("abortado antes de apertar"), vazio));
        assertFalse(Tools.talvezAgiu("type", new RealClick.NaoAgiu("nada foi digitado"), vazio));
        // falha depois de despachar o evento (ex.: timeout do CDP) pode ter publicado
        assertTrue(Tools.talvezAgiu("click", new IllegalStateException("timeout"), vazio));
        assertTrue(Tools.talvezAgiu("key", new IllegalStateException("x"), vazio));
        // request: so conta se saiu (status > 0) ou ficou incerto
        assertFalse(Tools.talvezAgiu("request", new IllegalStateException("csrf ausente"), Json.obj().put("status", -1)));
        assertTrue(Tools.talvezAgiu("request", new IllegalStateException("500"), Json.obj().put("status", 500)));
        assertTrue(Tools.talvezAgiu("request", new IllegalStateException("t"), Json.obj().put("status", 0).put("incerto", true)));
        assertFalse(Tools.talvezAgiu("verify_text", new IllegalStateException("x"), vazio));
    }

    @Test
    void falhaDoDriverNaConsultaIfPresentNaoContaComoAcaoPublica() {
        // navegador cai na consulta de presenca, antes de qualquer clique despachado
        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class, () -> Tools.presente(
                js -> {
                    throw new RuntimeException("CDP caiu");
                }, "css:a"));
        assertFalse(Tools.talvezAgiu("click", e, Json.obj()));
    }

    @Test
    void consultaIfPresentInterrompidaPropagaInterrupcao() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> Tools.presente(js -> "0", "css:a"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void waitComSelectorAceitoNoDraft() throws Exception {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("w", Json.parse("""
                [{"network":"youtube","kind":"click","selector":"css:#done-button"},
                 {"network":"youtube","kind":"wait","selector":"css:ytcp-video-share-dialog","expect":"publicado","timeoutMs":30000}]""")));
    }

    // ---------- resposta de request sem token ----------

    @Test
    void redigirTiraTokenDaResposta() {
        String jwtFalso = String.join(".", "eyJ" + "x".repeat(12), "eyJ" + "y".repeat(12), "z".repeat(10));
        String r = Tools.redigir("{\"accessJwt\":\"" + jwtFalso + "\","
                + "\"refreshJwt\":\"abc123def456\",\"csrf_token\":\"zz\",\"uri\":\"at://did:plc:1/app.bsky.feed.post/3k\","
                + "\"author\":{\"handle\":\"example_account\"},\"session_id\":12345}");
        assertFalse(r.contains("eyJ"), r);
        assertFalse(r.contains("abc123def456"), r);
        assertFalse(r.contains("\"zz\""), r);
        assertFalse(r.contains("12345"), r);
        assertTrue(r.contains("at://did:plc:1/app.bsky.feed.post/3k"), r);
        assertTrue(r.contains("\"author\":{\"handle\":\"example_account\"}"), r);
        assertFalse(Tools.redigir("Authorization: Bearer abcdefghijklmnop").contains("abcdefghijklmnop"));
        // corpo cortado em 2000 chars no meio do token
        assertFalse(Tools.redigir("{\"ok\":1,\"token\":\"segredo-cortado").contains("segredo"));
        String ck = Tools.redigir("{\"cookies\":[{\"name\":\"sid\",\"value\":\"valor-ficticio\"}],\"ok\":1}");
        assertFalse(ck.contains("valor-ficticio"), ck);
        assertTrue(ck.contains("\"ok\":1"), ck);
        assertEquals("", Tools.redigir(""));
        assertNull(Tools.redigir(null));
    }

    @Test
    void redigirRedigeObjetoEListaDeCampoSensivel() {
        String lista = Tools.redigir("{\"tokens\":[\"credencial-ficticia\"],\"ok\":1}");
        assertFalse(lista.contains("credencial-ficticia"), lista);
        assertTrue(lista.contains("\"tokens\":\"[redigido]\""), lista);
        assertTrue(lista.contains("\"ok\":1"), lista);

        String objeto = Tools.redigir("{\"token\":{\"valor\":\"segredo-interno\",\"tentativas\":2},\"ok\":true}");
        assertFalse(objeto.contains("segredo-interno"), objeto);
        assertFalse(objeto.contains("tentativas"), objeto);
        assertTrue(objeto.contains("\"ok\":true"), objeto);

        String aninhado = Tools.redigir("{\"dados\":{\"api_key\":[\"a1\",\"b2\"]},"
                + "\"lista\":[{\"password\":{\"hash\":\"h123\"}}],\"tags\":[\"x\",\"y\"]}");
        assertFalse(aninhado.contains("a1"), aninhado);
        assertFalse(aninhado.contains("b2"), aninhado);
        assertFalse(aninhado.contains("h123"), aninhado);
        assertTrue(aninhado.contains("\"tags\":[\"x\",\"y\"]"), aninhado);

        // colchetes dentro de string nao encerram a subarvore antes da hora
        String comString = Tools.redigir("{\"token\":{\"nota\":\"a]b}c\"},\"ok\":2}");
        assertFalse(comString.contains("a]b"), comString);
        assertTrue(comString.contains("\"ok\":2"), comString);

        // corpo truncado no meio da lista: redige ate o fim
        assertFalse(Tools.redigir("{\"tokens\":[\"segredo-cortado").contains("segredo-cortado"));
    }
    /**
     * A inicializacao espera a trava de health.json: um estado gravado por outro processo
     * enquanto esta JVM inicializa nao e sobrescrito por {}.
     */
    @Test
    void inicializacaoDeStoreEsperaATravaDaSaude() throws Exception {
        Store s = store();
        Files.delete(s.healthPath());
        CountDownLatch travado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicReference<Throwable> erro = new AtomicReference<>();
        Thread escritor = new Thread(() -> {
            try (Store.Trava t = s.lock("health")) {
                travado.countDown();
                liberar.await(10, TimeUnit.SECONDS);
                // estado gravado por outro processo enquanto este inicializa
                Files.writeString(s.healthPath(), "{\"bluesky\":{\"state\":\"cooldown\",\"reason\":\"anti-bot\"}}\n",
                        StandardCharsets.UTF_8);
            } catch (Throwable e) {
                travado.countDown();
                erro.set(e);
            }
        });
        escritor.start();
        assertTrue(travado.await(5, TimeUnit.SECONDS));
        AtomicReference<Store> nova = new AtomicReference<>();
        Thread init = new Thread(() -> nova.set(new Store(s.home)));
        init.start();
        init.join(1000);
        assertTrue(init.isAlive(), "a inicializacao precisa esperar a trava de health.json");
        liberar.countDown();
        init.join(10_000);
        assertFalse(init.isAlive());
        escritor.join(10_000);
        assertNull(erro.get());
        assertNotNull(nova.get());
        assertEquals("cooldown", s.effectiveState("bluesky"), "init sobrescreveu health.json da rede bloqueada");
    }

    /** Varias instancias inicializando juntas no mesmo home nao falham nem brigam pelos arquivos. */
    @Test
    void inicializacaoConcorrenteDeVariasInstancias() throws Exception {
        Path home = tmp.resolve("home-varios");
        Thread[] ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> new Store(home));
            ts[i].start();
        }
        for (Thread t : ts) t.join(10_000);
        for (Thread t : ts) assertFalse(t.isAlive(), "inicializacao travou");
        assertTrue(Files.exists(home.resolve("config.json")));
        assertTrue(Files.exists(home.resolve("health.json")));
        assertTrue(Files.exists(home.resolve("journal.jsonl")));
        assertEquals("{}", Json.write(new Store(home).health()));
    }

    @Test
    void focoErradoDepoisDoCliqueContaTentativa() {
        RuntimeException e = Tools.falhaFocoAposClique("css:#campo");
        assertFalse(e instanceof RealClick.NaoAgiu);
        assertTrue(Tools.talvezAgiu("type", e, Json.obj()));
        assertTrue(e.getMessage().contains("tentativa registrada"));
    }

    @Test
    void segredoTruncadoMultilineNaoVaza() {
        String r = Tools.redigir("{\"session\": {\n  \"value\": \"opaque-segunda-linha\"\n \"outro\": \"dado\"");
        assertFalse(r.contains("opaque-segunda-linha"), r);
        assertFalse(r.contains("dado"), r);
        assertTrue(r.contains("[redigido]"), r);
    }

    @Test
    void claimRespeitaTransicaoDoLote() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = aprovado(bs);
        JsonNode previa = bs.requireApproved(id);
        try (Store.Trava t = s.lock(BatchStore.loteLock(id))) {
            assertThrows(IllegalStateException.class, () -> bs.claim(id, previa));
            assertTrue(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
            assertFalse(Files.exists(s.batchesDir().resolve(id + ".running.json")));
        }
        assertEquals(id, bs.claim(id, previa).path("id").asText());
    }

    @Test
    void lockEsperaOutraInstanciaNaMesmaJvmESoltaAoInterromper() throws Exception {
        Store s = store();
        Store outra = new Store(s.home);
        CountDownLatch iniciada = new CountDownLatch(1);
        AtomicReference<Throwable> falha = new AtomicReference<>();
        AtomicBoolean interrompida = new AtomicBoolean();
        Thread espera = new Thread(() -> {
            iniciada.countDown();
            try (Store.Trava t = outra.lock("teste")) {
                fail("nao deveria adquirir antes de ser interrompida");
            } catch (java.io.IOException e) {
                interrompida.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                falha.set(e);
            }
        });
        try (Store.Trava t = s.lock("teste")) {
            espera.start();
            assertTrue(iniciada.await(5, TimeUnit.SECONDS));
            espera.join(200);
            assertTrue(espera.isAlive(), "deve esperar, sem OverlappingFileLockException");
            assertNull(outra.tryLock("teste"));
            espera.interrupt();
            espera.join(5000);
            assertFalse(espera.isAlive());
            assertNull(falha.get());
            assertTrue(interrompida.get());
        } finally {
            espera.interrupt();
            espera.join(5000);
        }
        try (Store.Trava t = outra.tryLock("teste")) { assertNotNull(t); }
    }

    @Test
    void registroDeSemaforosNaoAcumulaTravasConcluidas() throws Exception {
        Store s = store();
        var campo = Store.class.getDeclaredField("SEMAFOROS");
        campo.setAccessible(true);
        int antes = ((java.util.Map<?, ?>) campo.get(null)).size();
        for (int i = 0; i < 100; i++) {
            try (Store.Trava t = s.lock("temporaria-" + i)) { assertNotNull(t); }
        }
        assertEquals(antes, ((java.util.Map<?, ?>) campo.get(null)).size());
    }

    @Test
    void falhaAoAbrirCanalLiberaSemaforo() throws Exception {
        Store s = store();
        Files.createDirectory(s.locksDir().resolve("invalida.lock"));
        assertThrows(java.io.IOException.class, () -> s.lock("invalida"));
        assertThrows(java.io.IOException.class, () -> s.tryLock("invalida"));
        Files.delete(s.locksDir().resolve("invalida.lock"));
        try (Store.Trava t = s.tryLock("invalida")) { assertNotNull(t); }
        assertThrows(IllegalArgumentException.class, () -> s.lock("../escape"));
        assertThrows(IllegalArgumentException.class, () -> s.tryLock("../escape"));
    }
    @Test
    void conflitoDeCanalDiretoLiberaRecursosDaFila() throws Exception {
        Store s = store();
        try (var canal = java.nio.channels.FileChannel.open(s.locksDir().resolve("direto.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var trava = canal.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> s.lock("direto"));
            assertNull(s.tryLock("direto"));
        }
        try (Store.Trava t = s.tryLock("direto")) { assertNotNull(t); }
    }

    @Test
    void closeFechaCanalESoltaTrava() throws Exception {
        Store s = store();
        Store.Trava t = s.lock("fechar");
        var campo = Store.Trava.class.getDeclaredField("ch");
        campo.setAccessible(true);
        var canal = (java.nio.channels.FileChannel) campo.get(t);
        t.close();
        assertFalse(canal.isOpen());
        try (Store.Trava outra = s.tryLock("fechar")) { assertNotNull(outra); }
    }
}
