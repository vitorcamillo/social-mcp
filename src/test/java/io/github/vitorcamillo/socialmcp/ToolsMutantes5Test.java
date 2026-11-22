package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mutantes de Tools na faixa L1690-L2122: set_file, request, redigir/redigirSubarvores,
 * statusEsperado, extract, presente, el/shadowEl, sleepJitter, conferirDestino e safe.
 *
 * <p>Sem Chrome: os metodos que dependem do navegador sao exercitados so ate a
 * fronteira em que o driver e obrigatorio (validacao ou falha fail-closed), e os
 * seams {@code runRequest}/{@code presente}/{@code statusEsperado}/{@code redigir}
 * rodam direto. Os mutantes cujo comportamento real so aparece com um passo no
 * navegador ficam listados no relatorio da fatia.
 */
class ToolsMutantes5Test {

    @TempDir
    Path tmp;

    private final List<Tools> abertos = new ArrayList<>();
    private int casas;

    @AfterEach
    void fecharFerramentas() {
        for (Tools t : abertos) t.shutdown();
        abertos.clear();
    }

    private Store casa() {
        return new Store(tmp.resolve("casa-" + (casas++) + "-" + System.nanoTime()));
    }

    private Tools tools(Store store) {
        Tools t = new Tools(store);
        abertos.add(t);
        return t;
    }

    private static Object invocar(Tools alvo, String nome, Class<?>[] tipos, Object... args) {
        try {
            Method m = Tools.class.getDeclaredMethod(nome, tipos);
            m.setAccessible(true);
            return m.invoke(alvo, args);
        } catch (InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) throw re;
            if (causa instanceof Error err) throw err;
            throw new RuntimeException(causa);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("metodo " + nome, e);
        }
    }

    private static void injetarPool(Tools t, BrowserPool pool) {
        try {
            Field f = Tools.class.getDeclaredField("pool");
            f.setAccessible(true);
            f.set(t, pool);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("nao foi possivel injetar o pool de teste", e);
        }
    }

    /** Pool de teste: nunca abre Chrome; simula a guarda de navegacao ter barrado um destino. */
    private static final class PoolFalso extends BrowserPool {
        String bloqueada;

        PoolFalso(Store store) {
            super(store);
        }

        @Override
        public String navegacaoBloqueada(String network) {
            return bloqueada;
        }

        @Override
        public void closeAll() {
        }
    }

    private static void ajustarDefaults(Store store, java.util.function.Consumer<ObjectNode> ajuste)
            throws Exception {
        Path cfg = store.configPath();
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(cfg, StandardCharsets.UTF_8));
        ajuste.accept((ObjectNode) raiz.get("defaults"));
        Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    private static Path escrever(Path p, String conteudo) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, conteudo, StandardCharsets.UTF_8);
        return p;
    }

    private static ObjectNode midia(int passo, Integer indice, String arquivo, String sha, String copia) {
        ObjectNode m = Json.obj().put("passo", passo).put("arquivo", arquivo).put("sha256", sha);
        if (indice != null) m.put("indice", indice);
        if (copia != null) m.put("copia", copia);
        return m;
    }

    private static ObjectNode lote(JsonNode... midias) {
        ObjectNode b = Json.obj();
        ArrayNode arr = b.putArray("midias");
        for (JsonNode m : midias) arr.add(m);
        return b;
    }

    private static ObjectNode acaoPath(String path) {
        return Json.obj().put("selector", "css:input").put("path", path);
    }

    private static ObjectNode acaoPaths(String... paths) {
        ObjectNode a = Json.obj().put("selector", "css:input");
        ArrayNode arr = a.putArray("paths");
        for (String p : paths) arr.add(p);
        return a;
    }

    private static void setFile(Tools t, JsonNode acao, ObjectNode out, JsonNode lote, int passo) {
        invocar(t, "runSetFile", new Class<?>[]{
                JavaDriverless.class, JsonNode.class, ObjectNode.class, JsonNode.class, int.class
        }, null, acao, out, lote, passo);
    }

    private static void dormirJitter(Tools t, String rede) {
        invocar(t, "sleepJitter", new Class<?>[]{String.class}, rede);
    }

    private static String resposta(int status, String body) {
        return Json.write(Json.obj().put("s", status).put("b", body));
    }

    private static final JsonNode POST = Json.parse("""
            {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x","body":"{}",
             "headers":{"content-type":"application/json"}}
            """);

    // ---------- runSetFile: validacao antes do Chrome (L1690-L1718) ----------

    /** set_file fora de lote (sem copia aprovada com digest) e recusado antes de ler arquivo ou abrir Chrome. */
    @Test
    void setFileSemLoteERecusadoMesmoComArquivoExistente() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        String raiz = tmp.toRealPath().toString();
        ajustarDefaults(store, d -> d.putArray("uploadRoots").add(raiz));
        Path foto = Files.writeString(tmp.resolve("existe.png"), "png");

        for (JsonNode acao : List.of(acaoPath(foto.toString()), acaoPaths(foto.toString(), foto.toString()))) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> setFile(t, acao, Json.obj(), null, 0));
            assertEquals("set_file so roda em lote aprovado", e.getMessage());
        }
    }

    @Test
    void setFileComLoteSemMidiaFalhaNoPassoUm() {
        Store store = casa();
        Tools t = tools(store);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPath("/tmp/foto.png"), Json.obj(), Json.obj(), 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileMultiploComPathsDoisSemMidiaFalhaNoPassoUm() {
        Store store = casa();
        Tools t = tools(store);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPaths("/tmp/a.png", "/tmp/b.png"), Json.obj(), Json.obj(), 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileMultiploComListaVaziaSemMidiaFalhaAntesDoNavegador() {
        Store store = casa();
        Tools t = tools(store);
        // paths vazio nao passa no preflight; runSetFile ainda precisa falhar em vez de ir ao Chrome
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPaths(), Json.obj(), Json.obj(), 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileMultiploComUmaMidiaParaDoisPathsFalhaNoPassoUm() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path copia = escrever(store.batchesDir().resolve("copia-m1.png"), "conteudo");
        String sha = BatchStore.sha256Arquivo(copia);
        ObjectNode b = lote(midia(0, 0, "foto.png", sha, copia.getFileName().toString()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPaths("/tmp/a.png", "/tmp/b.png"), Json.obj(), b, 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileComMidiaSemShaFalhaNoPassoUmAntesDoNavegador() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path copia = escrever(store.batchesDir().resolve("copia-sem-sha.png"), "conteudo");
        ObjectNode b = lote(midia(0, 0, "foto.png", "", copia.getFileName().toString()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPath("/tmp/foto.png"), Json.obj(), b, 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileMultiploComIndiceTrocadoFalhaNoPassoUm() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path copia = escrever(store.batchesDir().resolve("copia-indice.png"), "conteudo");
        String sha = BatchStore.sha256Arquivo(copia);
        ObjectNode b = lote(midia(0, 5, "foto.png", sha, copia.getFileName().toString()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPaths("/tmp/foto.png"), Json.obj(), b, 0));
        assertEquals("set_file sem digest de midia aprovado no passo 1", e.getMessage());
    }

    @Test
    void setFileComCopiaAprovadaAusenteFalhaNoPassoUm() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path original = escrever(tmp.resolve("original.png"), "conteudo");
        String sha = BatchStore.sha256Arquivo(original);
        ObjectNode b = lote(midia(0, null, "foto.png", sha, "copia-que-nao-existe.png"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPath("/tmp/foto.png"), Json.obj(), b, 0));
        assertEquals("copia aprovada da midia do passo 1 ausente ou alterada", e.getMessage());
    }

    @Test
    void setFileComShaDivergenteFalhaNoPassoUm() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path copia = escrever(store.batchesDir().resolve("copia-divergente.png"), "conteudo");
        ObjectNode b = lote(midia(0, null, "foto.png", "0".repeat(64), copia.getFileName().toString()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPath("/tmp/foto.png"), Json.obj(), b, 0));
        assertEquals("copia aprovada da midia do passo 1 ausente ou alterada", e.getMessage());
    }

    @Test
    void setFileMultiploComecaPelaPrimeiraMidiaDoPasso() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        Path copia0 = escrever(store.batchesDir().resolve("copia-0.png"), "primeira");
        Path copia1 = escrever(store.batchesDir().resolve("copia-1.png"), "segunda");
        String sha0 = BatchStore.sha256Arquivo(copia0);
        String sha1 = BatchStore.sha256Arquivo(copia1);
        ObjectNode b = lote(
                midia(0, 0, "foto0.png", sha0, "copia-0-ausente.png"),
                midia(0, 1, "foto1.png", sha1, copia1.getFileName().toString()));

        // mutante que troca a lista de midias por uma so cai em "sem digest" (tamanho 1 != 2)
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> setFile(t, acaoPaths("/tmp/foto0.png", "/tmp/foto1.png"), Json.obj(), b, 0));
        assertEquals("copia aprovada da midia do passo 1 ausente ou alterada", e.getMessage());
    }

    // ---------- runRequest (L1753-L1810) ----------

    @Test
    void requestComExpectBodyPresenteDevolveMetodoUrlEStatus() throws Exception {
        ObjectNode out = Json.obj();
        JsonNode acao = Json.parse("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x",
                 "expectBody":"publicado"}
                """);
        String info = Tools.runRequest(
                js -> js.startsWith("(window[") ? resposta(200, "corpo publicado ok") : "ok", acao, out);
        assertEquals("POST https://bsky.social/xrpc/x -> 200", info);
        assertEquals(200, out.path("status").asInt());
        assertEquals("corpo publicado ok", out.path("bodySnippet").asText());
    }

    @Test
    void requestSemExpectBodyNaRespostaFalhaComTextoExato() throws Exception {
        ObjectNode out = Json.obj();
        JsonNode acao = Json.parse("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x",
                 "expectBody":"ausente"}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(
                        js -> js.startsWith("(window[") ? resposta(200, "corpo ok") : "ok", acao, out));
        assertTrue(e.getMessage().contains("expectBody 'ausente' ausente na resposta"), e.getMessage());
    }

    @Test
    void requestComStatusNegativoNaoEnviadoFalhaSemContarComoIncerto() throws Exception {
        ObjectNode out = Json.obj();
        JsonNode acao = Json.parse("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x"}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(
                        js -> js.startsWith("(window[") ? resposta(-2, "auth: token ausente") : "ok",
                        acao, out));
        assertTrue(e.getMessage().contains("request nao enviado: auth: token ausente"), e.getMessage());
        assertEquals(-2, out.path("status").asInt());
        assertFalse(out.path("incerto").asBoolean(false), out.toString());
    }

    @Test
    void requestInterrompidoRestauraFlagDeInterrupcao() {
        ObjectNode out = Json.obj();
        JsonNode acao = Json.parse("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x"}
                """);
        Thread.currentThread().interrupt();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Tools.runRequest(
                            js -> js.startsWith("(window[") ? "" : "ok", acao, out));
            assertTrue(e.getMessage().contains("interrompido"), e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "Thread.currentThread().interrupt() precisa preservar o cancelamento");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void requestNaoFazPollingApertadoSemDormir() throws Exception {
        ObjectNode out = Json.obj();
        JsonNode acao = Json.parse("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x"}
                """);
        AtomicInteger consultas = new AtomicInteger();
        AtomicReference<Throwable> erro = new AtomicReference<>();
        CountDownLatch primeiraConsulta = new CountDownLatch(1);
        Thread th = new Thread(() -> {
            try {
                Tools.runRequest(js -> {
                    if (js.startsWith("(window[")) {
                        consultas.incrementAndGet();
                        primeiraConsulta.countDown();
                        if (Thread.currentThread().isInterrupted()) {
                            throw new RuntimeException("interrompido-pelo-teste");
                        }
                        return "";
                    }
                    return "ok";
                }, acao, out);
            } catch (Throwable e) {
                erro.set(e);
            }
        });
        th.start();
        assertTrue(primeiraConsulta.await(2, TimeUnit.SECONDS), "thread nao chegou ao polling");
        Thread.sleep(50);
        int vistas = consultas.get();
        th.interrupt();
        th.join(5_000);
        assertFalse(th.isAlive(), "runRequest travou");
        assertTrue(erro.get() == null || erro.get() instanceof IllegalStateException,
                String.valueOf(erro.get()));
        assertTrue(vistas <= 2,
                "sem Thread.sleep(300) o polling dispara consultas demais em 50 ms: " + vistas);
    }

    // ---------- statusEsperado (L1976-L1978) ----------

    @Test
    void statusEsperadoSemExpectAceitaSomenteDoisXX() {
        assertTrue(Tools.statusEsperado(null, 200));
        assertTrue(Tools.statusEsperado(null, 299));
        assertFalse(Tools.statusEsperado(null, 300), "300 nao e 2xx");
        assertFalse(Tools.statusEsperado(null, 199), "199 nao e 2xx");
        assertFalse(Tools.statusEsperado(null, 500));
    }

    @Test
    void statusEsperadoComArrayComparaCadaValorExato() {
        JsonNode expect = Json.parse("[200,202]");
        assertTrue(Tools.statusEsperado(expect, 200));
        assertTrue(Tools.statusEsperado(expect, 202));
        assertFalse(Tools.statusEsperado(expect, 201));
    }

    @Test
    void statusEsperadoComListaVaziaUsaPadraoDoisXX() {
        JsonNode expect = Json.parse("[]");
        assertTrue(Tools.statusEsperado(expect, 200));
        assertFalse(Tools.statusEsperado(expect, 500));
    }

    @Test
    void statusEsperadoComObjetoNaoArrayUsaPadraoDoisXX() {
        JsonNode expect = Json.parse("{\"status\":500}");
        assertTrue(Tools.statusEsperado(expect, 200));
        // objeto nao e lista de status: 500 continua fora do padrao 2xx
        assertFalse(Tools.statusEsperado(expect, 500));
    }

    // ---------- redigir / redigirSubarvores (L1934-L1969) ----------

    @Test
    void redigeSubarvoreAninhadaInteiraDeCampoSensivel() {
        assertEquals("{\"token\":\"[redigido]\",\"x\":2}",
                Tools.redigir("{\"token\":{\"a\":{\"b\":1}},\"x\":2}"));
    }

    @Test
    void redigeSubarvoreEmListaDeCampoSensivel() {
        assertEquals("{\"senha\":\"[redigido]\",\"x\":1}",
                Tools.redigir("{\"senha\":[\"a\",\"b\"],\"x\":1}"));
    }

    @Test
    void redigirChaveNoFimDoTextoNaoEstoura() {
        assertEquals("{\"token\":", Tools.redigir("{\"token\":"));
    }

    @Test
    void redigirSemCampoSensivelMantemTexto() {
        assertEquals("{\"x\":1}", Tools.redigir("{\"x\":1}"));
    }

    // ---------- presente (L2024-L2042) ----------

    @Test
    void presenteComScriptUmDevolveTrue() throws Exception {
        assertTrue(Tools.presente(js -> "1", "css:botao"));
    }

    @Test
    void presenteComRespostaDesconhecidaLancaNaoAgiu() {
        assertThrows(RealClick.NaoAgiu.class,
                () -> Tools.presente(js -> "erro: seletor invalido", "css:["));
    }

    @Test
    void presenteComZeroInterrompidoLancaInterruptedException() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> Tools.presente(js -> "0", "css:nao-existe"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void presenteSemDriverFalhaAntesDeDizerQueNaoExiste() {
        // o overload com JavaDriverless nao pode devolver false/true sem consultar o navegador
        RuntimeException e = assertThrows(RuntimeException.class, () -> invocar(null, "presente",
                new Class<?>[]{JavaDriverless.class, String.class}, null, "css:a"));
        assertTrue(e instanceof RealClick.NaoAgiu || e instanceof NullPointerException, e.toString());
    }

    // ---------- el: falha fechada sem driver ----------

    @Test
    void elSemDriverFalhaEmCadaFormaDeSeletor() {
        Store store = casa();
        Tools t = tools(store);
        for (String seletor : List.of("id:alvo", "xpath://a", "css:#a", "#a", "a >>> b")) {
            assertThrows(NullPointerException.class, () -> invocar(t, "el", new Class<?>[]{
                    JavaDriverless.class, String.class
            }, null, seletor), seletor);
        }
    }

    // ---------- sleepJitter (L2080-L2086) ----------

    @Test
    void sleepJitterComArrayZeroNaoUsaPadraoDeMilissegundos() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        ajustarDefaults(store, d -> d.putArray("stepDelayMs").add(0).add(0));
        // varias rodadas: o padrao 1500/6000 ms sempre fica vivo; [0,0] nunca
        for (int rodada = 0; rodada < 5; rodada++) {
            AtomicReference<Throwable> erro = new AtomicReference<>();
            Thread th = new Thread(() -> {
                try {
                    dormirJitter(t, "bluesky");
                } catch (Throwable e) {
                    erro.set(e);
                }
            });
            th.start();
            th.join(250);
            boolean aindaDorme = th.isAlive();
            th.interrupt();
            th.join(5_000);
            assertNull(erro.get(), String.valueOf(erro.get()));
            assertFalse(aindaDorme,
                    "stepDelayMs [0,0] deve voltar de imediato; o padrao 1500/6000 ms fica dormindo");
        }
    }

    @Test
    void sleepJitterInterrompidoRestauraFlag() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        ajustarDefaults(store, d -> d.putArray("stepDelayMs").add(100).add(100));
        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> dormirJitter(t, "bluesky"));
            assertTrue(Thread.currentThread().isInterrupted(),
                    "InterruptedException nao pode engolir o cancelamento da thread");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sleepJitterNaoGeraEsperaNegativa() throws Exception {
        Store store = casa();
        Tools t = tools(store);
        ajustarDefaults(store, d -> d.putArray("stepDelayMs").add(0).add(100));
        Thread.currentThread().interrupt();
        try {
            for (int i = 0; i < 5; i++) {
                assertDoesNotThrow(() -> dormirJitter(t, "bluesky"));
            }
        } finally {
            Thread.interrupted();
        }
    }

    // ---------- conferirDestino (L2100-L2106) ----------

    @Test
    void conferirDestinoComBloqueioAntesDaRequisicaoFalhaSemLerPagina() {
        Store store = casa();
        Tools t = tools(store);
        PoolFalso pool = new PoolFalso(store);
        pool.bloqueada = "https://evil.example/x";
        injetarPool(t, pool);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> invocar(t, "conferirDestino", new Class<?>[]{JavaDriverless.class, String.class},
                        null, "bluesky"));
        assertEquals("navegacao bloqueada antes da requisicao: redirect para fora da rede bluesky: "
                + "https://evil.example", e.getMessage());
    }

    @Test
    void conferirDestinoComUrlIndisponivelFalhaAposRedirect() {
        Store store = casa();
        Tools t = tools(store);
        PoolFalso pool = new PoolFalso(store);
        pool.bloqueada = null;
        injetarPool(t, pool);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> invocar(t, "conferirDestino", new Class<?>[]{JavaDriverless.class, String.class},
                        null, "bluesky"));
        assertTrue(e.getMessage().contains("navegacao bloqueada apos redirect: destino fora da rede bluesky"),
                e.getMessage());
    }

    // ---------- safe (L2120-L2122) ----------

    @Test
    void safeDevolveOValorDoSupplier() {
        Store store = casa();
        Tools t = tools(store);
        Supplier<String> ok = () -> "https://bsky.app/";
        assertEquals("https://bsky.app/", invocar(t, "safe", new Class<?>[]{Supplier.class}, ok));
    }

    @Test
    void safeEmFalhaDevolveMensagemIndisponivel() {
        Store store = casa();
        Tools t = tools(store);
        Supplier<String> falha = () -> {
            throw new IllegalStateException("cdp caiu");
        };
        assertEquals("(indisponivel: cdp caiu)",
                invocar(t, "safe", new Class<?>[]{Supplier.class}, falha));
    }
}
