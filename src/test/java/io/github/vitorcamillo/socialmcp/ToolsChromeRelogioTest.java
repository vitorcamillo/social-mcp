package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fronteiras dependentes de hora em Tools: espacamento publico, prazo do lote, prazo restante
 * nos passos com espera ({@code Math.max(1, prazoFimMs - relogio)}), laco do request, timeout
 * exato de verify_absent/wait e o fim do prazo de presente/shadowEl.
 *
 * <p>Um ChromeFalso unico serve as paginas; cada teste troca {@link Tools#relogio} por um relogio
 * falso e {@link Tools#pausa} por um gravador que avanca/salta o mesmo relogio. Nenhum teste
 * depende da duracao real de uma pausa: os valores sao exatos e restaurados no {@code @AfterEach}.
 */
class ToolsChromeRelogioTest {

    private static final String BASE = """
            <html><head><title>Base</title></head><body>
            <h1>hello</h1>
            <div id="x" data-k="v"></div>
            <button id="alvo">ok</button>
            </body></html>
            """;

    private static final String LISTA = """
            <html><body>
            <ul id="lista"><li data-id="1">um</li></ul>
            </body></html>
            """;

    @TempDir
    static Path tmp;

    private static Store store;
    private static ChromeFalso chrome;
    private static Tools tools;
    private static LongSupplier relogioOriginal;
    private static TypeText.Pausa pausaOriginal;
    private static int seq;

    private RelogioFalso relogio;

    // ---------- fixture ----------

    @BeforeAll
    static void abrirFixture() throws Exception {
        relogioOriginal = Tools.relogio;
        pausaOriginal = Tools.pausa;
        store = new Store(tmp.resolve("home"));
        configurarPadrao();
        chrome = ChromeFalso.abrir(tmp, store);
        chrome.pagina("/base", BASE);
        chrome.pagina("/lista", LISTA);
        tools = new Tools(store);
    }

    @AfterAll
    static void fecharFixture() {
        if (tools != null) tools.shutdown();
        if (chrome != null) chrome.close();
        Tools.relogio = relogioOriginal;
        Tools.pausa = pausaOriginal;
    }

    @BeforeEach
    void prepararCenario() throws Exception {
        relogio = new RelogioFalso();
        Tools.relogio = () -> relogio.agora;
        Tools.pausa = relogio;
        configurarPadrao();
        limparJournal();
        store.setState("bluesky", "healthy", "teste", "teste");
    }

    @AfterEach
    void restaurarRelogioEPausa() {
        Tools.relogio = relogioOriginal;
        Tools.pausa = pausaOriginal;
    }

    /** Cotas altas, sem espacamento e passos sem jitter: o foco e a fronteira de tempo. */
    private static void configurarPadrao() throws Exception {
        Path cfg = store.configPath();
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(cfg, StandardCharsets.UTF_8));
        ObjectNode defs = (ObjectNode) raiz.path("defaults");
        defs.put("maxPublicActionsPerDay", 500);
        defs.put("minMinutesBetweenPublicActions", 0);
        defs.put("maxBatchesPerDay", 500);
        defs.put("maxPublicStepsPerBatch", 50);
        defs.put("travaIncertezaDesde", "2999-01-01");
        defs.set("stepDelayMs", Json.parse("[0,0]"));
        defs.set("typeChunkDelayMs", Json.parse("[1,1]"));
        ObjectNode bsky = (ObjectNode) raiz.path("networks").path("bluesky");
        bsky.put("startUrl", "https://bsky.app/");
        bsky.put("attachExistingChrome", true);
        bsky.put("closeAfterBatch", false);
        bsky.put("browserIdleMinutes", 0);
        Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    /** Zera o journal e forca o indice incremental a ver o arquivo vazio antes da proxima leitura. */
    private static void limparJournal() throws Exception {
        Files.writeString(store.journalPath(), "", StandardCharsets.UTF_8);
        store.usageFor("bluesky");
    }

    private static void minMinutosEntrePublicas(int minutos) throws Exception {
        Path cfg = store.configPath();
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(cfg, StandardCharsets.UTF_8));
        ((ObjectNode) raiz.path("defaults")).put("minMinutesBetweenPublicActions", minutos);
        Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    private static String preparar(String acoesJson, boolean ensaio) throws Exception {
        BatchStore bs = new BatchStore(store);
        String id = bs.draft("teste relogio " + (seq++), Json.parse(acoesJson)).path("id").asText();
        bs.approve(id, "humano", "nota de teste", List.of(), true, ensaio);
        return id;
    }

    private static JsonNode executar(String id) throws Exception {
        return Json.parse(tools.call("social_execute_batch", Json.obj().put("batchId", id)));
    }

    private static String motivo(JsonNode resumo) {
        return resumo.path("motivo").asText("");
    }

    // ---------- espacamento entre acoes publicas ----------

    @Test
    @Timeout(60)
    void semAcaoPublicaAnteriorEspacamentoEnormeNaoBarraOLote() throws Exception {
        minMinutosEntrePublicas(2_000_000_000);
        relogio.agora = 1_000_000_000_000L;

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#alvo","clickMode":"dom","description":"clique"}]
                """, true);
        JsonNode resumo = executar(id);

        // lastPublicActionMs == 0: o lote pula o bloco de espacamento (mutante ">= 0" o recusaria)
        assertFalse(resumo.path("abortado").asBoolean(true), resumo.toString());
        assertEquals(2, resumo.path("passosExecutados").asInt(-1), resumo.toString());
        assertFalse(motivo(resumo).contains("espacamento"), resumo.toString());
    }

    @Test
    @Timeout(60)
    void acaoPublicaAnteriorComFaltaZeroLiberaOLoteNoLimite() throws Exception {
        minMinutosEntrePublicas(0);
        store.appendJournal("action", "bluesky", Json.obj()
                .put("kind", "click")
                .put("cls", "public")
                .put("batchId", "anterior")
                .put("day", store.today())
                .put("desc", "acao publica anterior"));
        long ultima = store.usageFor("bluesky").lastPublicActionMs;
        assertTrue(ultima > 0, "journal precisa ter a acao publica anterior");
        relogio.agora = ultima; // falta = need(0) - (ultima - ultima) = 0

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#alvo","clickMode":"dom","description":"clique"}]
                """, true);
        JsonNode resumo = executar(id);

        assertFalse(resumo.path("abortado").asBoolean(true), resumo.toString());
        assertEquals(2, resumo.path("passosExecutados").asInt(-1), resumo.toString());
    }

    // ---------- prazo do lote ----------

    @Test
    @Timeout(60)
    void prazoDoLoteNoLimiteExatoAbortaAntesDoPassoDois() throws Exception {
        long inicio = 5_000_000L;
        relogio.agora = inicio;
        relogio.saltarNa(0, 1, inicio + Tools.PRAZO_LOTE_MS);

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"um"},
                 {"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"dois"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertEquals(1, resumo.path("passosExecutados").asInt(-1), resumo.toString());
        assertEquals("prazo do lote (840 s) esgotado antes do passo 2; a chamada MCP seria cortada pelo cliente",
                motivo(resumo));
    }

    // ---------- prazo restante curto nos passos com espera ----------

    @Test
    @Timeout(60)
    void waitUsaPrazoRestanteCurtoENaoOMsPedido() throws Exception {
        long inicio = 10_000L;
        relogio.agora = inicio;
        relogio.saltarNa(0, 1, inicio + Tools.PRAZO_LOTE_MS - 40);

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"wait","ms":5000,"description":"espera"}]
                """, false);
        JsonNode resumo = executar(id);

        assertFalse(resumo.path("abortado").asBoolean(true), resumo.toString());
        assertEquals("esperou 40 ms", resumo.path("passos").get(1).path("info").asText());
        assertEquals(List.of(40L), relogio.pausasNaoZero());
    }

    @Test
    @Timeout(60)
    void verifyAbsentUsaPrazoRestanteCurtoNoTimeout() throws Exception {
        long inicio = 20_000L;
        relogio.agora = inicio;
        relogio.saltarNa(0, 1, inicio + Tools.PRAZO_LOTE_MS - 40);

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"verify_absent","selector":"css:#x","timeoutMs":5000,"description":"ausente"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("apos 40 ms"), resumo.toString());
        assertEquals(List.of(500L), relogio.pausasNaoZero());
    }

    @Test
    @Timeout(60)
    void captureIdsUsaPrazoRestanteCurtoNoWaitMs() throws Exception {
        long inicio = 30_000L;
        relogio.agora = inicio;
        relogio.saltarNa(0, 1, inicio + Tools.PRAZO_LOTE_MS - 40);

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"abrir"},
                 {"network":"bluesky","kind":"capture_ids","selector":"css:li","parentSelector":"css:#naoexiste",
                  "attr":"data-id","name":"ids","stableMs":1000,"waitMs":2000,"description":"capturar"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("parentSelector nao apareceu em 40 ms"), resumo.toString());
    }

    @Test
    @Timeout(90)
    void verifyNewIdUsaPrazoRestanteCurtoNoTimeout() throws Exception {
        long inicio = 40_000L;
        relogio.agora = inicio;
        relogio.saltarNa(0, 3, inicio + Tools.PRAZO_LOTE_MS - 40);

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"abrir"},
                 {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"ids",
                  "stableMs":1000,"waitMs":2000,"description":"capturar"},
                 {"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"reabrir"},
                 {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"ids",
                  "timeoutMs":5000,"description":"novo"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("verify_new_id falhou"), resumo.toString());
        assertTrue(motivo(resumo).contains("apos 40 ms"), resumo.toString());
    }

    // ---------- timeout exato dos lacos (verify_absent e wait) ----------

    @Test
    @Timeout(60)
    void verifyAbsentComRelogioNoTimeoutExatoSaiDoLacoNaSegundaPausa() throws Exception {
        relogio.agora = 0;

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"verify_absent","selector":"css:#x","timeoutMs":1000,"description":"ausente"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("apos 1000 ms"), resumo.toString());
        // original: 0 e 500 < 1000; na 3a leitura 1000 >= 1000 sai. Com ">" seriam 3 pausas.
        assertEquals(2, relogio.contar(500));
    }

    @Test
    @Timeout(60)
    void waitComRelogioNoTimeoutExatoSaiDoLacoNaSegundaPausa() throws Exception {
        relogio.agora = 0;

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"wait","expect":"NAO_EXISTE_EM_LUGAR_NENHUM","timeoutMs":1000,
                  "description":"espera"}]
                """, false);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("wait: 'NAO_EXISTE_EM_LUGAR_NENHUM' nao apareceu em 1000 ms"),
                resumo.toString());
        // original: 2 pausas; com ">" a terceira leitura ainda dormiria (3 pausas).
        assertEquals(2, relogio.contar(500));
    }

    // ---------- runRequest: fronteira do laco de espera ----------

    @Test
    @Timeout(60)
    void runRequestComRelogioPulandoAoFimExatoFazUmaPausaESai() throws Exception {
        relogio.agora = 0;
        relogio.saltarNa(300, 1, 30_000L); // REQUEST_TIMEOUT_MS
        ObjectNode out = Json.obj();
        JsonNode acao = Json.obj().put("method", "POST").put("url", "https://bsky.app/api");

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(js -> js.startsWith("(window[") ? "" : "ok", acao, out));

        assertEquals(1, relogio.contar(300), "relogio == fim: original sai; com <= faria outra pausa");
        assertTrue(erro.getMessage().contains("request sem resposta em 30 s"), erro.getMessage());
        assertEquals(0, out.path("status").asInt(-1));
        assertTrue(out.path("incerto").asBoolean(false), out.toString());
    }

    // ---------- presente e shadowEl: fronteira do prazo ----------

    @Test
    @Timeout(60)
    void presenteComRelogioNoFimExatoFazUmaPausaEDeclaraAusencia() throws Exception {
        relogio.agora = 0;
        relogio.saltarNa(300, 1, 4_000L); // PRESENCA_TIMEOUT_MS

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#naoexiste","ifPresent":true,
                  "description":"ausente"}]
                """, true);
        JsonNode resumo = executar(id);

        assertFalse(resumo.path("abortado").asBoolean(true), resumo.toString());
        assertEquals("ausente (ifPresent): nada clicado",
                resumo.path("passos").get(1).path("info").asText());
        assertEquals(1, relogio.contar(300), "relogio == fim: original sai; com > faria outra pausa");
    }

    @Test
    @Timeout(60)
    void shadowElComRelogioNoFimExatoFazUmaPausaEFalha() throws Exception {
        relogio.agora = 0;
        relogio.saltarNa(300, 1, 4_000L); // SHADOW_FIND_TIMEOUT_MS

        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#host >>> #naoexiste","description":"shadow"}]
                """, true);
        JsonNode resumo = executar(id);

        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(motivo(resumo).contains("elemento nao encontrado no shadow DOM"), resumo.toString());
        assertEquals(1, relogio.contar(300), "relogio == fim: original sai; com > faria outra pausa");
    }

    // ---------- relogio/pausa falsos ----------

    /** Pausa que grava o pedido e move um relogio controlado; saltos permitem acertar a fronteira exata. */
    private static final class RelogioFalso implements TypeText.Pausa {
        long agora;
        final List<Long> pausas = Collections.synchronizedList(new ArrayList<>());

        private long saltoEmMs = Long.MIN_VALUE;
        private int saltoNaOcorrencia = -1;
        private long saltoPara;
        private int ocorrencias;
        private final boolean avancarPeloPedido = true;

        /** Na n-esima pausa com esse ms, coloca o relogio exatamente no destino (sem avancar mais). */
        void saltarNa(long ms, int ocorrencia, long destino) {
            this.saltoEmMs = ms;
            this.saltoNaOcorrencia = ocorrencia;
            this.saltoPara = destino;
            this.ocorrencias = 0;
        }

        @Override
        public void dormir(long ms) {
            pausas.add(ms);
            if (ms == saltoEmMs) {
                ocorrencias++;
                if (ocorrencias == saltoNaOcorrencia) {
                    agora = saltoPara;
                    return;
                }
            }
            if (avancarPeloPedido) agora += ms;
        }

        long contar(long ms) {
            return pausas.stream().filter(p -> p.longValue() == ms).count();
        }

        List<Long> pausasNaoZero() {
            return pausas.stream().filter(p -> p.longValue() > 0).toList();
        }
    }
}
