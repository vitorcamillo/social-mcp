package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Estado em disco do Store: home padrao, health/estado, kill-switch, trava de incerteza e travas. */
class StoreEstadoTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    // ---------- helpers ----------

    private static void gravarHealth(Store s, String json) throws Exception {
        Files.writeString(s.healthPath(), json, StandardCharsets.UTF_8);
    }

    private static ObjectNode configDe(Store s) throws Exception {
        return (ObjectNode) Json.parse(Files.readString(s.configPath(), StandardCharsets.UTF_8));
    }

    private static void gravarConfig(Store s, ObjectNode cfg) throws Exception {
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
    }

    /** Objeto networks.&lt;rede&gt; da config (cria o caminho se faltar). */
    private static ObjectNode redeConfig(ObjectNode cfg, String rede) {
        JsonNode nets = cfg.path("networks");
        ObjectNode redes = nets.isObject() ? (ObjectNode) nets : cfg.putObject("networks");
        JsonNode n = redes.path(rede);
        return n.isObject() ? (ObjectNode) n : redes.putObject(rede);
    }

    private static long inicioDoDia(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia).atStartOfDay(Store.TZ).toInstant().toEpochMilli();
    }

    /** Conteudo de um &lt;id&gt;.executed.json (como markExecuted deixa). */
    private static ObjectNode lote(String id, String network, String conferencia, long executedAt) {
        ObjectNode b = Json.obj();
        b.put("id", id);
        b.put("label", "t");
        b.put("status", "executed");
        b.put("createdAt", executedAt);
        b.put("executedAt", executedAt);
        b.put("network", network);
        b.set("result", Json.obj().put("conferencia", conferencia));
        return b;
    }

    /** Grava &lt;id&gt;.executed.json com a conferencia e o executedAt dados (como markExecuted deixa). */
    private void executado(Store s, String id, String network, String conferencia, long executedAt) throws Exception {
        Files.writeString(s.batchesDir().resolve(id + ".executed.json"),
                Json.writePretty(lote(id, network, conferencia, executedAt)), StandardCharsets.UTF_8);
    }

    private static String ultimaLinha(Store s) throws Exception {
        List<String> linhas = Files.readAllLines(s.journalPath(), StandardCharsets.UTF_8);
        assertFalse(linhas.isEmpty(), "journal sem linhas");
        return linhas.get(linhas.size() - 1);
    }

    /** Store de teste cujo lockChannel("ro") devolve um canal somente-leitura (trava falha). */
    private static final class StoreCanalSomenteLeitura extends Store {
        FileChannel guardado;

        StoreCanalSomenteLeitura(Path home) {
            super(home);
        }

        @Override
        FileChannel lockChannel(String nome) throws IOException {
            if (!nome.equals("ro")) return super.lockChannel(nome);
            Files.createDirectories(locksDir());
            Path arquivo = locksDir().resolve("ro.lock");
            try (FileChannel escrita = FileChannel.open(arquivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                // so garante que o arquivo exista
            }
            guardado = FileChannel.open(arquivo, StandardOpenOption.READ);
            return guardado;
        }
    }

    // ---------- home e config ----------

    @Test
    void defaultHomeUsaEnvNaoVazioOuHomeDoUsuario() {
        assertEquals(Path.of("/u/.social-mcp-public"), Store.defaultHome(null, "/u"));
        assertEquals(Path.of("/u/.social-mcp-public"), Store.defaultHome("  ", "/u"));
        assertEquals(Path.of("/x"), Store.defaultHome("/x", "/u"));
    }

    @Test
    void profilesDirFicaSobOHome() {
        Store s = store();
        assertEquals(s.home.resolve(".javadriverless_profiles"), s.profilesDir());
    }

    // ---------- health / estado ----------

    @Test
    void healthEmBrancoViraObjetoVazio() throws Exception {
        Store s = store();
        gravarHealth(s, "  \n");

        JsonNode h = s.health();
        assertNotNull(h);
        assertTrue(h.isObject());
        assertEquals(0, h.size());
        // config padrao nao define state para instagram
        assertEquals("healthy", s.effectiveState("instagram"));
    }

    @Test
    void stateReasonVemDoHealth() throws Exception {
        Store s = store();
        s.setState("instagram", "paused", "motivo", "t");
        assertEquals("motivo", s.stateReason("instagram"));
    }

    @Test
    void stateReasonCaiParaNoteDaConfig() throws Exception {
        Store s = store();
        gravarHealth(s, "{\"instagram\":{\"state\":\"paused\"}}");
        ObjectNode cfg = configDe(s);
        redeConfig(cfg, "instagram").put("note", "nota");
        gravarConfig(s, cfg);

        assertEquals("nota", s.stateReason("instagram"));
    }

    @Test
    void stateReasonIgnoraHealthQueNaoEhObjeto() throws Exception {
        Store s = store();
        gravarHealth(s, "{\"instagram\":\"x\"}");
        ObjectNode cfg = configDe(s);
        redeConfig(cfg, "instagram").put("note", "nota");
        gravarConfig(s, cfg);

        assertEquals("nota", s.stateReason("instagram"));
    }

    @Test
    void stateReasonIgnoraReasonNuloDoHealth() throws Exception {
        Store s = store();
        gravarHealth(s, "{\"instagram\":{\"reason\":null}}");
        ObjectNode cfg = configDe(s);
        redeConfig(cfg, "instagram").put("note", "nota");
        gravarConfig(s, cfg);

        assertEquals("nota", s.stateReason("instagram"));
    }

    @Test
    void stateReasonSemReasonNemNoteEhNulo() throws Exception {
        Store s = store();
        gravarHealth(s, "{\"instagram\":{\"state\":\"paused\"}}");
        ObjectNode cfg = configDe(s);
        redeConfig(cfg, "instagram").remove("note");
        gravarConfig(s, cfg);
        assertFalse(s.config().path("networks").path("instagram").has("note"), "a chave note precisa estar ausente");

        assertNull(s.stateReason("instagram"));
    }

    @Test
    void stateReasonComNoteNulaEhNulo() throws Exception {
        Store s = store();
        gravarHealth(s, "{\"instagram\":{\"reason\":null}}");
        ObjectNode cfg = configDe(s);
        redeConfig(cfg, "instagram").putNull("note");
        gravarConfig(s, cfg);

        assertNull(s.stateReason("instagram"));
    }

    // ---------- kill-switch ----------

    @Test
    void setStoppedGravaKillSwitchNoJournal() throws Exception {
        Store s = store();
        s.setStopped(true);
        assertTrue(s.isStopped());
        JsonNode ligado = Json.parse(ultimaLinha(s));
        assertEquals("kill_switch", ligado.path("type").asText());
        assertTrue(ligado.path("ativo").asBoolean());

        s.setStopped(false);
        assertFalse(s.isStopped());
        JsonNode desligado = Json.parse(ultimaLinha(s));
        assertEquals("kill_switch", desligado.path("type").asText());
        assertFalse(desligado.path("ativo").asBoolean());
    }

    // ---------- trava de incerteza ----------

    /** Ajusta defaults.travaIncertezaDesde (dia null remove a chave). */
    private static void definirTrava(Store s, String dia) throws Exception {
        ObjectNode cfg = configDe(s);
        JsonNode d = cfg.path("defaults");
        ObjectNode defaults = d.isObject() ? (ObjectNode) d : cfg.putObject("defaults");
        if (dia == null) {
            defaults.remove("travaIncertezaDesde");
        } else {
            defaults.put("travaIncertezaDesde", dia);
        }
        gravarConfig(s, cfg);
    }

    @Test
    void travaIncertezaUsaDiaDaConfig() throws Exception {
        Store s = store();
        definirTrava(s, "2026-11-15");
        assertEquals(inicioDoDia(2026, 11, 15), s.travaIncertezaDesdeMs());
    }

    @Test
    void travaIncertezaComDiaInvalidoUsaOPadrao() throws Exception {
        Store s = store();
        definirTrava(s, "lixo");
        assertEquals(inicioDoDia(2026, 11, 11), s.travaIncertezaDesdeMs());
    }

    @Test
    void travaIncertezaVaziaUsaOPadrao() throws Exception {
        Store s = store();
        definirTrava(s, "");
        assertEquals(inicioDoDia(2026, 11, 11), s.travaIncertezaDesdeMs());
    }

    @Test
    void travaIncertezaSemChaveUsaOPadrao() throws Exception {
        Store s = store();
        definirTrava(s, null);
        assertEquals(inicioDoDia(2026, 11, 11), s.travaIncertezaDesdeMs());
    }

    // ---------- incertezas pendentes ----------

    @Test
    void incertezasPendentesSemDiretorioDeLotesEhVazia() throws Exception {
        Store s = store();
        Files.delete(s.batchesDir());

        assertEquals(List.of(), s.incertezasPendentes("instagram"));
    }

    @Test
    void incertezasPendentesRespeitaAFronteiraDaTrava() throws Exception {
        Store s = store();
        long desde = s.travaIncertezaDesdeMs();
        executado(s, "naFronteira", "instagram", "abortado_apos_publico", desde);
        executado(s, "umMsAntes", "instagram", "abortado_apos_publico", desde - 1);

        assertEquals(List.of("naFronteira"), s.incertezasPendentes("instagram"));
    }

    @Test
    void incertezasPendentesSoLeArquivosExecutados() throws Exception {
        Store s = store();
        long agora = System.currentTimeMillis();
        // mesmo conteudo de um lote incerto, mas sem o sufixo .executed.json
        Files.writeString(s.batchesDir().resolve("b9.json"),
                Json.writePretty(lote("b9", "instagram", "abortado_apos_publico", agora)), StandardCharsets.UTF_8);
        executado(s, "b8", "instagram", "abortado_apos_publico", agora);

        assertEquals(List.of("b8"), s.incertezasPendentes("instagram"));
    }

    @Test
    void incertezasPendentesOrdenaPorIdNaoPelaListagemDoDiretorio() throws Exception {
        Store s = store();
        for (int i = 0; i < 12; i++) {
            Files.writeString(s.batchesDir().resolve(String.format("f%02d.executed.json", i)),
                    "{\"id\":\"provisorio\"}", StandardCharsets.UTF_8);
        }
        List<Path> ordemDoDiretorio;
        try (var files = Files.list(s.batchesDir())) {
            ordemDoDiretorio = files.toList();
        }
        assertEquals(12, ordemDoDiretorio.size());

        long agora = System.currentTimeMillis();
        List<String> idsNaOrdemDaListagem = new ArrayList<>();
        for (int i = 0; i < ordemDoDiretorio.size(); i++) {
            String id = String.format("z%02d", 99 - i);
            idsNaOrdemDaListagem.add(id);
            Files.writeString(ordemDoDiretorio.get(i),
                    Json.writePretty(lote(id, "instagram", "abortado_apos_publico", agora)), StandardCharsets.UTF_8);
        }

        List<String> esperado = new ArrayList<>(idsNaOrdemDaListagem);
        esperado.sort(String::compareTo);
        List<String> resultado = s.incertezasPendentes("instagram");

        assertEquals(esperado, resultado);
        assertNotEquals(idsNaOrdemDaListagem, resultado, "a ordem da listagem do diretorio nao pode ditar o resultado");
    }

    // ---------- travas: falha do canal ----------

    @Test
    void tryLockComFalhaDoCanalNaoVazaFilaNemTrava() throws Exception {
        Store normal = store();
        StoreCanalSomenteLeitura s = new StoreCanalSomenteLeitura(normal.home);

        assertThrows(NonWritableChannelException.class, () -> s.tryLock("ro"));
        assertFalse(s.guardado.isOpen(), "o canal que falhou precisa ser fechado");
        assertFalse(s.filaRegistrada("ro"));
        try (Store.Trava t = new Store(normal.home).tryLock("ro")) {
            assertNotNull(t, "a trava precisa voltar a funcionar depois da falha");
        }
    }

    @Test
    void lockComFalhaDoCanalNaoVazaFilaNemTrava() throws Exception {
        Store normal = store();
        StoreCanalSomenteLeitura s = new StoreCanalSomenteLeitura(normal.home);

        assertThrows(NonWritableChannelException.class, () -> s.lock("ro"));
        assertFalse(s.guardado.isOpen(), "o canal que falhou precisa ser fechado");
        assertFalse(s.filaRegistrada("ro"));
        try (Store.Trava t = new Store(normal.home).lock("ro")) {
            assertNotNull(t, "a trava precisa voltar a funcionar depois da falha");
        }
    }

    // ---------- travas: close libera tudo ----------

    @Test
    void travaCloseLiberaFilaELock() throws Exception {
        Store s = store();
        Store.Trava t = s.lock("x");
        assertTrue(s.filaRegistrada("x"));
        t.close();
        assertFalse(s.filaRegistrada("x"));
        try (Store.Trava deNovo = s.tryLock("x")) {
            assertNotNull(deNovo, "depois do close a trava pode ser retomada");
        }
    }

    @Test
    void travaDeTryLockTambemLiberaNoClose() throws Exception {
        Store s = store();
        Store.Trava t = s.tryLock("y");
        assertNotNull(t);
        assertTrue(s.filaRegistrada("y"));
        t.close();
        assertFalse(s.filaRegistrada("y"));
        try (Store.Trava deNovo = s.tryLock("y")) {
            assertNotNull(deNovo, "depois do close a trava pode ser retomada");
        }
    }

    @Test
    void duasTravasEmSequenciaNaMesmaThreadNaoTravam() throws Exception {
        Store s = store();
        for (int i = 0; i < 2; i++) {
            try (Store.Trava t = s.lock("z")) {
                assertNotNull(t);
                assertTrue(s.filaRegistrada("z"));
            }
        }
        assertFalse(s.filaRegistrada("z"), "a fila some quando a ultima trava fecha");
    }
}
