package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fatia Chrome A: mutantes de leitura em Tools (browse/read/inspect/query/
 * screenshot/saveScreenshot/draftResponse/action/runReadOnly/probe) que
 * precisam de navegador real. Um ChromeFalso + Store + Tools por classe.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolsChromeLeituraTest {

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;
    static Tools tools;

    static String htmlRica() {
        StringBuilder longo = new StringBuilder();
        for (int i = 0; i < 500; i++) longo.append("texto visivel da sonda. ");
        return "<html><head><title>Pagina Sonda</title></head><body>"
                + "<h1>Sonda da rede</h1>"
                + "<p id=\"boas\">frase-sonda-xyz de boas-vindas a pagina de testes.</p>"
                + "<p class=\"item\">Item um da lista</p>"
                + "<p class=\"item\">Item dois da lista</p>"
                + "<p class=\"item\">Item tres da lista</p>"
                + "<input aria-label=\"Nome completo\" name=\"nome\" type=\"text\">"
                + "<button id=\"alvo-sonda\">Entrar na sonda</button>"
                + "<article data-id=\"n1\">Post um da sonda</article>"
                + "<article data-id=\"n2\">Post dois da sonda</article>"
                + "<p>" + longo + "</p>"
                + "</body></html>";
    }

    @BeforeAll
    static void abrir() throws Exception {
        store = new Store(tmp.resolve("home"));
        Path dir = store.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), """
                {"network":"bluesky","actions":{
                  "get_post":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/sonda"},
                    {"kind":"verify_text","expect":"{{text}}"},
                    {"kind":"click","selector":"css:button#alvo-sonda","description":"abrir alvo"}]},
                  "get_profile":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/sonda"},
                    {"kind":"verify_text","expect":"{{text}}"},
                    {"kind":"click","selector":"css:#botao-que-nao-existe","description":"alvo ausente"}]},
                  "read_inbox":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/bloqueio"},
                    {"kind":"verify_text","expect":"{{text}}"},
                    {"kind":"click","selector":"css:button#alvo-sonda","description":"abrir alvo"}]},
                  "list_posts":{"params":[],"readOnly":true,"steps":[
                    {"kind":"goto","url":"https://bsky.app/"},
                    {"kind":"verify_text","expect":"frase-sonda-xyz"}]},
                  "create_post":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/"},
                    {"kind":"click","selector":"css:button#alvo-sonda","description":"publicar"},
                    {"kind":"verify_text","expect":"{{text}}"}]}
                }}
                """, StandardCharsets.UTF_8);
        cf = ChromeFalso.abrir(tmp, store);
        cf.pagina("/", htmlRica());
        cf.pagina("/sonda", htmlRica());
        cf.pagina("/bloqueio", "<html><head><title>Ops</title></head><body><h1>Ops</h1>"
                + "<p>rate limit exceeded: aguarde um instante. frase-bloqueio-xyz</p></body></html>");
        cf.pagina("/sequestro", "<html><head><meta http-equiv=\"refresh\""
                + " content=\"0;url=about:blank\"></head>"
                + "<body>saindo</body></html>");
        tools = new Tools(store);
    }

    @AfterAll
    static void fechar() {
        try {
            tools.shutdown();
        } finally {
            cf.close();
        }
    }

    // ---------- reflexao ----------

    private static Object campo(Object alvo, String nome) {
        try {
            Field f = Tools.class.getDeclaredField(nome);
            f.setAccessible(true);
            return f.get(alvo);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("campo " + nome, e);
        }
    }

    private static String runReadOnlyRef(Recipes.Plan plano) {
        try {
            Method m = Tools.class.getDeclaredMethod("runReadOnly", Recipes.Plan.class);
            m.setAccessible(true);
            return (String) m.invoke(tools, plano);
        } catch (InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) throw re;
            if (causa instanceof Error err) throw err;
            throw new RuntimeException(causa);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("runReadOnly", e);
        }
    }

    private static Recipes.Plan plano(String acao, ArrayNode passos) {
        return new Recipes.Plan("bluesky", acao, true, passos, "rotulo", true);
    }

    private static ObjectNode passo(String kind) {
        ObjectNode o = Json.obj();
        o.put("network", "bluesky");
        o.put("kind", kind);
        return o;
    }

    private static boolean journalTem(String tipo, String trecho) {
        for (String linha : store.tail(30)) {
            if (linha.contains("\"type\":\"" + tipo + "\"") && linha.contains(trecho)) return true;
        }
        return false;
    }

    private static void saude(String estado) {
        store.setState("bluesky", estado, "teste leitura", "teste");
    }

    private static PublishTrace traceQueFalha() {
        PublishTrace.Cdp cdp = new PublishTrace.Cdp() {
            @Override
            public CompletableFuture<Void> addCdpListener(String evento, Consumer<JsonNode> listener) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> removeCdpListener(String evento, Consumer<JsonNode> listener) {
                return CompletableFuture.failedFuture(new RuntimeException("listener nao removido"));
            }

            @Override
            public CompletableFuture<JsonNode> executeCdpCmd(String comando,
                    Map<String, Object> params, Float timeout) {
                return CompletableFuture.completedFuture(Json.obj());
            }
        };
        return PublishTrace.start(cdp, "bluesky", "texto aprovado");
    }

    private static ObjectNode probeArgs(String acao, ObjectNode params) {
        ObjectNode a = Json.obj();
        a.put("network", "bluesky");
        a.put("action", acao);
        if (params != null) a.set("params", params);
        return a;
    }

    // ---------- browse (L184, L185, L189, L193, L194, L198) ----------

    @Test
    @Order(1)
    @Timeout(60)
    void browseCobreDestinoTituloUrlJournalEMaxChars() throws Exception {
        String r = tools.call("social_browse", Json.obj()
                .put("network", "bluesky").put("url", "https://bsky.app/sonda"));
        JsonNode out = Json.parse(r);
        assertTrue(out.path("url").asText().startsWith("https://bsky.app/sonda"), r);
        assertEquals("Pagina Sonda", out.path("title").asText(), r);
        assertTrue(out.path("textoVisivel").asText().contains("frase-sonda-xyz"), r);
        assertFalse(r.isEmpty(), "retorno vazio (L198)");
        assertTrue(journalTem("browse", "\"url\":\"https://bsky.app/sonda\""), "journal browse sem url (L189)");
        assertTrue(out.path("textoVisivel").asText().length() > 5000,
                "sem maxChars devia trazer o texto longo");

        String curto = tools.call("social_browse", Json.obj()
                .put("network", "bluesky").put("url", "https://bsky.app/sonda").put("maxChars", 50));
        String textoCurto = Json.parse(curto).path("textoVisivel").asText();
        assertTrue(textoCurto.contains("[truncado]"), curto);
        assertTrue(textoCurto.length() < 200, "maxChars=50 ignorado (L185): " + textoCurto.length());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> tools.call("social_browse",
                Json.obj().put("network", "bluesky").put("url", "https://bsky.app/sequestro")),
                "sequestro para about:blank devia ser barrado (L184)");
        assertTrue(e.getMessage().contains("apos redirect"), e.getMessage());
    }

    // ---------- read/inspect/query/screenshot (L215-L337) ----------

    @Test
    @Order(2)
    @Timeout(60)
    void leituraReadInspectQueryScreenshot() throws Exception {
        tools.call("social_browse", Json.obj()
                .put("network", "bluesky").put("url", "https://bsky.app/sonda"));

        String r = tools.call("social_read", Json.obj().put("network", "bluesky"));
        JsonNode read = Json.parse(r);
        assertFalse(r.isEmpty(), "read vazio (L227)");
        assertTrue(read.path("url").asText().startsWith("https://bsky.app/sonda"), r);
        assertTrue(read.path("textoVisivel").asText().contains("frase-sonda-xyz"), r);
        assertTrue(journalTem("read", "\"type\":\"read\""), "journal read ausente (L219)");

        String curto = tools.call("social_read",
                Json.obj().put("network", "bluesky").put("maxChars", 40));
        String textoCurto = Json.parse(curto).path("textoVisivel").asText();
        assertTrue(textoCurto.contains("[truncado]") && textoCurto.length() < 200,
                "maxChars do read ignorado (L215): " + textoCurto.length());

        String inspecao = tools.call("social_inspect", Json.obj().put("network", "bluesky"));
        JsonNode insp = Json.parse(inspecao);
        assertFalse(inspecao.isEmpty(), "inspect vazio (L274)");
        assertTrue(insp.path("controles").size() >= 2, inspecao);
        assertTrue(insp.path("total").asInt(0) >= 2, inspecao);
        assertTrue(journalTem("inspect", "\"type\":\"inspect\""), "journal inspect ausente (L272)");

        String inspecao1 = tools.call("social_inspect",
                Json.obj().put("network", "bluesky").put("maxControls", 1));
        JsonNode insp1 = Json.parse(inspecao1);
        assertEquals(1, insp1.path("controles").size(), inspecao1);
        assertTrue(insp1.path("truncado").asBoolean(false), inspecao1);

        String consulta = tools.call("social_query", Json.obj()
                .put("network", "bluesky").put("selector", "css:.item").put("max", 2));
        JsonNode q = Json.parse(consulta);
        assertFalse(consulta.isEmpty(), "query vazia (L321)");
        assertEquals(3, q.path("total").asInt(-1), consulta);
        assertEquals(2, q.path("itens").size(), "max=2 ignorado (L289): " + consulta);
        assertTrue(q.path("url").asText().startsWith("https://bsky.app/"), consulta);
        assertTrue(journalTem("query", "\"selector\":\"css:.item\""), "journal query ausente (L320)");

        String foto = tools.call("social_screenshot", Json.obj().put("network", "bluesky"));
        assertTrue(foto.startsWith("screenshot salvo em: "), foto);
        String caminho = foto.substring("screenshot salvo em: ".length()).trim();
        assertFalse(caminho.isEmpty(), "saveScreenshot vazio (L337)");
        assertTrue(Files.isRegularFile(Path.of(caminho)), "png ausente: " + caminho);
        assertTrue(journalTem("screenshot", caminho), "journal screenshot ausente (L329)");
    }

    // ---------- draftResponse (L372) ----------

    @Test
    @Order(3)
    @Timeout(60)
    void draftResponseSinalizaConferenciaFracaEAusente() throws Exception {
        ObjectNode fraca = Json.obj().put("label", "t-fraca");
        fraca.set("actions", Json.parse("""
                [{"network":"bluesky","kind":"click","selector":"css:button","description":"botao Publicar"},
                 {"network":"bluesky","kind":"verify_text","expect":"publicado"}]
                """));
        JsonNode fracaOut = Json.parse(tools.call("social_draft_batch", fraca));
        assertEquals("fraca", fracaOut.path("conferencia").asText(), fracaOut.toString());
        assertTrue(fracaOut.hasNonNull("AVISO_CONFERENCIA"), fracaOut.toString());

        ObjectNode ausente = Json.obj().put("label", "t-ausente");
        ausente.set("actions", Json.parse("""
                [{"network":"bluesky","kind":"click","selector":"css:button","description":"botao Publicar"}]
                """));
        JsonNode ausenteOut = Json.parse(tools.call("social_draft_batch", ausente));
        assertEquals("ausente", ausenteOut.path("conferencia").asText(), ausenteOut.toString());
        assertTrue(ausenteOut.hasNonNull("AVISO_CONFERENCIA"), ausenteOut.toString());

        ObjectNode forte = Json.obj().put("label", "t-forte");
        forte.set("actions", Json.parse("""
                [{"network":"bluesky","kind":"click","selector":"css:button","description":"botao Publicar"},
                 {"network":"bluesky","kind":"verify_attr","selector":"css:button","attr":"aria-label","expect":"ok"}]
                """));
        JsonNode forteOut = Json.parse(tools.call("social_draft_batch", forte));
        assertEquals("forte", forteOut.path("conferencia").asText(), forteOut.toString());
        assertFalse(forteOut.has("AVISO_CONFERENCIA"), forteOut.toString());
    }

    // ---------- action (L397) ----------

    @Test
    @Order(4)
    @Timeout(60)
    void actionReadOnlyExecutaEPublicaViraRascunho() throws Exception {
        saude("healthy");
        String r = tools.call("social_action", Json.obj()
                .put("network", "bluesky").put("action", "list_posts").set("params", Json.obj()));
        JsonNode out = Json.parse(r);
        assertTrue(out.path("readOnly").asBoolean(false), r);
        assertTrue(out.path("ok").asBoolean(false), r);
        assertEquals("list_posts", out.path("action").asText(), r);

        String pub = tools.call("social_action", Json.obj()
                .put("network", "bluesky").put("action", "create_post")
                .set("params", Json.obj().put("text", "ola mundo")));
        JsonNode draft = Json.parse(pub);
        assertEquals("draft", draft.path("status").asText(), pub);
        assertFalse(draft.path("batchId").asText().isEmpty(), pub);
    }

    // ---------- runReadOnly passos (L418, L426, L428, L432, L442-frio, L447-frio, L457-frio) ----------

    @Test
    @Order(5)
    @Timeout(60)
    void runReadOnlyNumeracaoUrlsInfoEPrazo() throws Exception {
        saude("healthy");
        ArrayNode passos = Json.M.createArrayNode();
        passos.add(passo("goto").put("url", "https://bsky.app/sonda"));
        passos.add(passo("wait").put("ms", 1200));
        JsonNode out = Json.parse(runReadOnlyRef(plano("list_posts", passos)));
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertFalse(out.path("tripwire").asBoolean(true), out.toString());
        assertFalse(out.has("AVISO"), out.toString());
        assertEquals(1, out.path("passos").get(0).path("passo").asInt(-1), out.toString());
        assertEquals(2, out.path("passos").get(1).path("passo").asInt(-1), out.toString());
        // urls vao so para o journal (browse com action), nao para a resposta
        assertTrue(journalTem("browse", "\"urls\":[\"https://bsky.app/sonda\"]"), store.tail(5).toString());
        assertTrue(out.path("passos").get(0).hasNonNull("info"), out.toString());
        assertTrue(out.path("passos").get(1).path("info").asText("").contains("esperou 1200 ms"),
                out.toString());
    }

    @Test
    @Order(6)
    @Timeout(60)
    void runReadOnlyErroParaNoPassoComMensagemExata() throws Exception {
        saude("healthy");
        ArrayNode passos = Json.M.createArrayNode();
        passos.add(passo("goto").put("url", "https://bsky.app/sonda"));
        passos.add(passo("zzz"));
        JsonNode out = Json.parse(runReadOnlyRef(plano("list_posts", passos)));
        assertFalse(out.path("ok").asBoolean(true), out.toString());
        assertEquals(2, out.path("passos").size(), out.toString());
        assertFalse(out.path("passos").get(1).path("ok").asBoolean(true), out.toString());
        assertTrue(out.path("motivo").asText("").contains("passo 2 falhou"), out.toString());
    }

    @Test
    @Order(7)
    @Timeout(60)
    void runReadOnlyTripwireVaiParaCooldownComAvisoCombinado() throws Exception {
        saude("healthy");
        @SuppressWarnings("unchecked")
        Map<String, PublishTrace> traces = (Map<String, PublishTrace>) campo(tools, "traces");
        traces.put("posts", traceQueFalha());
        try {
            ArrayNode passos = Json.M.createArrayNode();
            passos.add(passo("goto").put("url", "https://bsky.app/bloqueio"));
            JsonNode out = Json.parse(runReadOnlyRef(plano("read_inbox", passos)));
            assertFalse(out.path("ok").asBoolean(true), out.toString());
            assertTrue(out.path("tripwire").asBoolean(false), out.toString());
            String aviso = out.path("AVISO").asText("");
            assertTrue(aviso.contains("bloqueio/anti-bot"), out.toString());
            assertTrue(aviso.contains("trace(s)"), out.toString());
            assertEquals("cooldown", store.effectiveState("bluesky"), out.toString());
            List<String> cauda = store.tail(5);
            assertTrue(cauda.stream().anyMatch(l ->
                    l.contains("\"type\":\"tripwire\"") && l.contains("read_inbox")), cauda.toString());
        } finally {
            traces.clear();
            saude("healthy");
        }
    }

    @Test
    @Order(8)
    @Timeout(60)
    void runReadOnlyIdsNovosEStaleNaoReaproveita() throws Exception {
        saude("healthy");
        ArrayNode passos = Json.M.createArrayNode();
        passos.add(passo("goto").put("url", "https://bsky.app/sonda"));
        passos.add(passo("capture_ids")
                .put("selector", "css:.antigo[data-id]").put("attr", "data-id").put("name", "posts"));
        passos.add(passo("verify_new_id")
                .put("selector", "css:article[data-id]").put("attr", "data-id")
                .put("name", "posts").put("timeoutMs", 8000));
        JsonNode out = Json.parse(runReadOnlyRef(plano("list_posts", passos)));
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        String idNovo = out.path("idsNovos").path("posts").asText("");
        assertTrue(idNovo.equals("n1") || idNovo.equals("n2"), out.toString());

        ArrayNode soGoto = Json.M.createArrayNode();
        soGoto.add(passo("goto").put("url", "https://bsky.app/[[novo:posts]]"));
        JsonNode semReuso = Json.parse(runReadOnlyRef(plano("list_posts", soGoto)));
        assertFalse(semReuso.path("ok").asBoolean(true), semReuso.toString());
        assertTrue(semReuso.path("motivo").asText("").contains("sem verify_new_id ok anterior"),
                "idsNovosLote stale nao podia vazar para a proxima acao (L420): " + semReuso);
    }

    // ---------- probe ----------

    @Test
    @Order(9)
    @Timeout(60)
    void probeReceitaVivaComParamsExplicitos() throws Exception {
        saude("healthy");
        JsonNode out = Json.parse(tools.call("social_probe",
                probeArgs("get_post", Json.obj().put("text", "frase-sonda-xyz"))));
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals("receita viva", out.path("motivo").asText(), out.toString());
        assertFalse(out.path("tripwire").asBoolean(true), out.toString());
        assertEquals(2, out.path("passos").size(), out.toString());
        assertEquals(1, out.path("passos").get(0).path("passo").asInt(-1), out.toString());
        assertEquals(2, out.path("passos").get(1).path("passo").asInt(-1), out.toString());
        assertTrue(out.path("passos").get(0).hasNonNull("info"), out.toString());
        assertTrue(out.path("primeiroControle").hasNonNull("presente"), out.toString());
        assertEquals("click", out.path("primeiroControle").path("kind").asText(), out.toString());
        assertTrue(journalTem("probe", "\"action\":\"get_post\""), "journal probe ausente");
    }

    @Test
    @Order(10)
    @Timeout(60)
    void probeControleAusente() throws Exception {
        saude("healthy");
        JsonNode out = Json.parse(tools.call("social_probe",
                probeArgs("get_profile", Json.obj().put("text", "frase-sonda-xyz"))));
        assertFalse(out.path("ok").asBoolean(true), out.toString());
        assertEquals("controle ausente: css:#botao-que-nao-existe",
                out.path("motivo").asText(), out.toString());
        assertFalse(out.path("primeiroControle").path("presente").asBoolean(true), out.toString());
    }

    @Test
    @Order(11)
    @Timeout(60)
    void probeTripwireVaiParaCooldown() throws Exception {
        saude("healthy");
        try {
            JsonNode out = Json.parse(tools.call("social_probe",
                    probeArgs("read_inbox", Json.obj().put("text", "frase-bloqueio-xyz"))));
            assertFalse(out.path("ok").asBoolean(true), out.toString());
            assertTrue(out.path("tripwire").asBoolean(false), out.toString());
            assertTrue(out.path("motivo").asText("").startsWith("tripwire: "), out.toString());
            assertEquals("cooldown", store.effectiveState("bluesky"), out.toString());
            List<String> cauda = store.tail(5);
            assertTrue(cauda.stream().anyMatch(l ->
                    l.contains("\"type\":\"tripwire\"") && l.contains("read_inbox")), cauda.toString());
        } finally {
            saude("healthy");
        }
    }

    @Test
    @Order(12)
    @Timeout(60)
    void probePassoComErroParaAntesDoControle() throws Exception {
        saude("healthy");
        JsonNode out = Json.parse(tools.call("social_probe",
                probeArgs("get_post", Json.obj().put("text", "frase-que-nao-existe-xyz"))));
        assertFalse(out.path("ok").asBoolean(true), out.toString());
        assertTrue(out.path("motivo").asText("").contains("passo 2 falhou"), out.toString());
        assertEquals(2, out.path("passos").size(), out.toString());
        assertFalse(out.path("passos").get(1).path("ok").asBoolean(true), out.toString());
        assertTrue(out.path("primeiroControle").isNull(), out.toString());
    }

    @Test
    @Order(13)
    @Timeout(60)
    void probeGuardasDeEstado() throws Exception {
        saude("banned");
        try {
            IllegalStateException banida = assertThrows(IllegalStateException.class, () -> tools.call(
                    "social_probe", probeArgs("get_post", Json.obj().put("text", "frase-sonda-xyz"))));
            assertTrue(banida.getMessage().contains("nao permitida"), banida.getMessage());
        } finally {
            saude("healthy");
        }
        saude("manutencao");
        try {
            IllegalStateException estranho = assertThrows(IllegalStateException.class, () -> tools.call(
                    "social_probe", probeArgs("get_post", Json.obj().put("text", "frase-sonda-xyz"))));
            assertTrue(estranho.getMessage().contains("nao permitida"), estranho.getMessage());
        } finally {
            saude("healthy");
        }
        store.setStopped(true);
        try {
            IllegalStateException parado = assertThrows(IllegalStateException.class, () -> tools.call(
                    "social_probe", probeArgs("get_post", Json.obj().put("text", "frase-sonda-xyz"))));
            assertTrue(parado.getMessage().contains("kill-switch"), parado.getMessage());
        } finally {
            store.setStopped(false);
        }
    }

    @Test
    @Order(14)
    @Timeout(60)
    void probeFechaChromeAposSonda() throws Exception {
        saude("healthy");
        JsonNode out = Json.parse(tools.call("social_probe",
                probeArgs("get_post", Json.obj().put("text", "frase-sonda-xyz"))));
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        Object pool = campo(tools, "pool");
        assertEquals(0, ((BrowserPool) pool).openCount(), "probe devia fechar o Chrome (L620/L621)");
    }
}
