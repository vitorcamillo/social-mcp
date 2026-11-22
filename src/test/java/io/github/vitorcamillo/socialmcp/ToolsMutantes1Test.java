package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutantes de {@link Tools} na faixa 1-500: fechamento de traces, thread de idle,
 * shutdown, status, guardas de entrada de browse/read/inspect/query/screenshot,
 * rascunho (preflight, conferencia, origem de receita) e o resultado de uma acao
 * readOnly sem passos. Tudo com Store em @TempDir; nenhum Chrome real e aberto.
 */
class ToolsMutantes1Test {

    @TempDir
    Path tmp;

    private final List<Tools> criados = new ArrayList<>();

    @AfterEach
    void encerrarTools() {
        for (Tools t : criados) {
            try {
                t.shutdown();
            } catch (RuntimeException ignored) {
                // shutdown de teste nao pode mascarar a falha do teste
            }
        }
        criados.clear();
    }

    // ---------- helpers ----------

    private Store store(String nome) {
        return new Store(tmp.resolve(nome + "-" + System.nanoTime()));
    }

    private Tools tools(Store s) {
        Tools t = new Tools(s);
        criados.add(t);
        return t;
    }

    private static Object campo(Object alvo, String nome) {
        try {
            Field f = Tools.class.getDeclaredField(nome);
            f.setAccessible(true);
            return f.get(alvo);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("campo " + nome, e);
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

    @SuppressWarnings("unchecked")
    private static Map<String, PublishTrace> tracesDe(Tools t) {
        return (Map<String, PublishTrace>) campo(t, "traces");
    }

    private static Object invocar(Tools t, String nome, Class<?>[] tipos, Object... args) {
        try {
            Method m = Tools.class.getDeclaredMethod(nome, tipos);
            m.setAccessible(true);
            return m.invoke(t, args);
        } catch (InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) throw re;
            if (causa instanceof Error err) throw err;
            throw new RuntimeException(causa);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("metodo " + nome, e);
        }
    }

    private static int fecharTraces(Tools t) {
        return (Integer) invocar(t, "fecharTraces", new Class<?>[0]);
    }

    private static String status(Tools t) {
        return (String) invocar(t, "status", new Class<?>[0]);
    }

    private static String runReadOnly(Tools t, Recipes.Plan plano) {
        return (String) invocar(t, "runReadOnly", new Class<?>[]{Recipes.Plan.class}, plano);
    }

    private static String capturarErr(Runnable tarefa) {
        PrintStream original = System.err;
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        System.setErr(new PrintStream(saida, true, StandardCharsets.UTF_8));
        try {
            tarefa.run();
        } finally {
            System.setErr(original);
        }
        return saida.toString(StandardCharsets.UTF_8);
    }

    private static PublishTrace traceQueFalhaAoFechar() {
        PublishTrace.Cdp cdp = new PublishTrace.Cdp() {
            @Override
            public CompletableFuture<Void> addCdpListener(String evento,
                    java.util.function.Consumer<JsonNode> listener) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> removeCdpListener(String evento,
                    java.util.function.Consumer<JsonNode> listener) {
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

    private static JsonNode ultimoJournal(Store s, String type) {
        List<String> linhas = s.tail(20);
        String alvo = "\"type\":\"" + type + "\"";
        for (int i = linhas.size() - 1; i >= 0; i--) {
            if (linhas.get(i).contains(alvo)) return Json.parse(linhas.get(i));
        }
        throw new AssertionError("journal sem " + alvo + ": " + linhas);
    }

    private static JsonNode acoesComConferenciaFraca() {
        return Json.parse("""
                [{"network":"bluesky","kind":"click","selector":"css:button"},
                 {"network":"bluesky","kind":"verify_text","expect":"publicado"}]
                """);
    }

    private static Recipes.Plan planoVazio() {
        return new Recipes.Plan("bluesky", "get_profile", true,
                Json.M.createArrayNode(), "rotulo de teste", true);
    }

    private void escreverReceita(Store s, String json) throws Exception {
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), json, StandardCharsets.UTF_8);
    }

    /** Pool de teste: nunca abre Chrome; permite simular idle e shutdown. */
    private static final class PoolFalso extends BrowserPool {
        List<String> ociosas = List.of();
        RuntimeException falhaIdle;
        RuntimeException falhaGet = new IllegalStateException("pool.get inesperado no teste");
        int closeAllChamadas;

        PoolFalso(Store store) {
            super(store);
        }

        @Override
        public List<String> closeIdle() {
            if (falhaIdle != null) throw falhaIdle;
            return ociosas;
        }

        @Override
        public void closeAll() {
            closeAllChamadas++;
        }

        @Override
        public JavaDriverless get(String network) {
            throw falhaGet;
        }
    }

    // ---------- fecharTraces ----------

    @Test
    void fecharTracesFechaContaFalhasGravaJournalELimpaMapa() throws Exception {
        Store s = store("traces");
        Tools t = tools(s);
        tracesDe(t).put("posts", traceQueFalhaAoFechar());

        assertEquals(1, fecharTraces(t), "uma falha de close conta 1 no retorno");
        assertTrue(tracesDe(t).isEmpty(), "o mapa de traces precisa ser limpo");
        String journal = String.join("\n", s.tail(10));
        assertTrue(journal.contains("\"type\":\"trace_cleanup_failed\""), journal);
        assertTrue(journal.contains("\"count\":1"), journal);
    }

    @Test
    void fecharTracesSemFalhasDevolveZeroESemJournal() throws Exception {
        Store s = store("traces-ok");
        Tools t = tools(s);

        assertEquals(0, fecharTraces(t));
        assertFalse(String.join("\n", s.tail(10)).contains("trace_cleanup_failed"),
                "sem falhas, nada de trace_cleanup_failed no journal");
    }

    // ---------- thread/tarefa de idle ----------

    @Test
    void threadDoIdleEhDaemonEChamadaSocialMcpIdle() throws Exception {
        // Chama a fabrica a partir de um chamador nao-daemon conhecido: sem setDaemon a
        // thread herdaria false e o mutante passaria despercebido.
        java.util.concurrent.atomic.AtomicReference<Thread> criada = new java.util.concurrent.atomic.AtomicReference<>();
        Thread chamador = new Thread(() -> criada.set(Tools.threadDoIdle(() -> { })),
                "chamador-nao-daemon");
        chamador.setDaemon(false);
        chamador.start();
        chamador.join(5_000);
        assertFalse(chamador.isAlive(), "chamador travou");

        Thread th = criada.get();
        assertNotNull(th, "a fabrica de thread nao pode devolver null");
        assertTrue(th.isDaemon(), "a thread de idle nao pode segurar a JVM");
        assertEquals("social-mcp-idle", th.getName());
    }

    @Test
    void tarefaIdleImprimeRedesFechadas() throws Exception {
        Store s = store("idle-print");
        Tools t = tools(s);
        PoolFalso pool = new PoolFalso(s);
        pool.ociosas = List.of("bluesky", "mastodon");
        injetarPool(t, pool);

        String err = capturarErr(t::tarefaIdle);
        assertTrue(err.contains("[social-mcp] Chrome ocioso fechado: [bluesky, mastodon]"), err);
    }

    @Test
    void tarefaIdleImprimeFalhaAoFechar() throws Exception {
        Store s = store("idle-falha");
        Tools t = tools(s);
        PoolFalso pool = new PoolFalso(s);
        pool.falhaIdle = new RuntimeException("falha-fake");
        injetarPool(t, pool);

        String err = capturarErr(t::tarefaIdle);
        assertTrue(err.contains("[social-mcp] falha ao fechar Chrome ocioso: falha-fake"), err);
    }

    @Test
    void shutdownFechaTodosOsChromes() throws Exception {
        Store s = store("shutdown");
        Tools t = tools(s);
        PoolFalso pool = new PoolFalso(s);
        injetarPool(t, pool);

        t.shutdown();
        assertEquals(1, pool.closeAllChamadas, "shutdown precisa fechar todos os Chromes do pool");
    }

    // ---------- status ----------

    @Test
    void statusIncluiMotivoDoEstado() throws Exception {
        Store s = store("status-motivo");
        s.setState("bluesky", "cooldown", "anti-bot no perfil", "teste");
        Tools t = tools(s);

        JsonNode out = Json.parse(status(t));
        JsonNode rede = out.path("redes").path("bluesky");
        assertEquals("cooldown", rede.path("estado").asText());
        assertEquals("anti-bot no perfil", rede.path("motivoEstado").asText());
    }

    @Test
    void statusMostraNuncaEDepoisOInstanteDaUltimaAcaoPublica() throws Exception {
        Store s = store("status-ultima");
        Tools t = tools(s);

        JsonNode antes = Json.parse(status(t));
        assertEquals("nunca", antes.path("redes").path("bluesky").path("ultimaAcaoPublica").asText());

        s.appendJournal("action", "bluesky", Json.obj()
                .put("cls", "public")
                .put("batchId", "b1")
                .put("day", s.today()));
        JsonNode depois = Json.parse(status(t));
        String valor = depois.path("redes").path("bluesky").path("ultimaAcaoPublica").asText();
        assertNotEquals("nunca", valor);
        assertDoesNotThrow(() -> Instant.parse(valor));
    }

    @Test
    void statusDevolveJsonCompleto() throws Exception {
        JsonNode out = Json.parse(status(tools(store("status-json"))));

        assertTrue(out.has("killSwitch"));
        assertTrue(out.has("home"));
        assertTrue(out.path("redes").isObject());
    }

    // ---------- browse/read/inspect/query/screenshot: guardas antes do Chrome ----------

    @Test
    void browseExigeRedeConhecidaAntesDeValidarUrl() throws Exception {
        Tools t = tools(store("browse-rede"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.call("social_browse",
                Json.obj().put("network", "nao-existe").put("url", "https://bsky.app/")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void browseValidaNavegacaoAntesDeAbrirChrome() throws Exception {
        Store s = store("browse-nav");
        Tools t = tools(s);
        injetarPool(t, new PoolFalso(s));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.call("social_browse",
                Json.obj().put("network", "bluesky").put("url", "http://bsky.app/")));
        assertTrue(e.getMessage().startsWith("navegacao bloqueada"), e.getMessage());
    }

    @Test
    void readExigeRedeConhecida() throws Exception {
        Tools t = tools(store("read-rede"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_read", Json.obj().put("network", "nao-existe")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void inspectExigeRedeConhecida() throws Exception {
        Tools t = tools(store("inspect-rede"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_inspect", Json.obj().put("network", "nao-existe")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void inspectExigeGuardaDeNavegacao() throws Exception {
        Store s = store("inspect-guarda");
        s.setState("bluesky", "banned", "conta banida", "teste");
        Tools t = tools(s);
        injetarPool(t, new PoolFalso(s));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> t.call("social_inspect", Json.obj().put("network", "bluesky")));
        assertTrue(e.getMessage().contains("BANIDA"), e.getMessage());
    }

    @Test
    void queryExigeNetworkNaoVazio() throws Exception {
        Tools t = tools(store("query-network"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_query", Json.obj().put("network", "").put("selector", "css:a")));
        assertTrue(e.getMessage().contains("network obrigatoria"), e.getMessage());
    }

    @Test
    void queryExigeSelectorNaoVazio() throws Exception {
        Store s = store("query-selector");
        Tools t = tools(s);
        injetarPool(t, new PoolFalso(s));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_query", Json.obj().put("network", "bluesky").put("selector", "")));
        assertTrue(e.getMessage().contains("selector obrigatorio"), e.getMessage());
    }

    @Test
    void queryExigeRedeConhecida() throws Exception {
        Tools t = tools(store("query-rede"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.call("social_query",
                Json.obj().put("network", "nao-existe").put("selector", "css:a")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void queryExigeGuardaDeNavegacao() throws Exception {
        Store s = store("query-guarda");
        s.setState("bluesky", "banned", "conta banida", "teste");
        Tools t = tools(s);
        injetarPool(t, new PoolFalso(s));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.call("social_query",
                Json.obj().put("network", "bluesky").put("selector", "css:a")));
        assertTrue(e.getMessage().contains("BANIDA"), e.getMessage());
    }

    @Test
    void screenshotExigeRedeConhecida() throws Exception {
        Tools t = tools(store("screenshot-rede"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_screenshot", Json.obj().put("network", "nao-existe")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    // ---------- rascunho ----------

    @Test
    void draftBatchDevolveRascunhoEJsonCompleto() throws Exception {
        Tools t = tools(store("draft-json"));
        ObjectNode args = Json.obj().put("label", "teste");
        args.set("actions", acoesComConferenciaFraca());

        JsonNode out = Json.parse(t.call("social_draft_batch", args));
        assertFalse(out.path("batchId").asText("").isBlank(), out.toString());
        assertEquals("draft", out.path("status").asText());
        assertEquals(2, out.path("preflight").size());
    }

    @Test
    void draftBatchNumeraPreflightAPartirDeUm() throws Exception {
        Tools t = tools(store("draft-preflight"));
        ObjectNode args = Json.obj().put("label", "teste");
        args.set("actions", acoesComConferenciaFraca());

        JsonNode pre = Json.parse(t.call("social_draft_batch", args)).path("preflight");
        assertEquals(1, pre.get(0).path("passo").asInt());
        assertEquals(2, pre.get(1).path("passo").asInt());
    }

    @Test
    void draftBatchAvisaConferenciaFraca() throws Exception {
        Tools t = tools(store("draft-aviso"));
        ObjectNode args = Json.obj().put("label", "teste");
        args.set("actions", acoesComConferenciaFraca());

        JsonNode out = Json.parse(t.call("social_draft_batch", args));
        assertEquals("fraca", out.path("conferencia").asText());
        assertTrue(out.has("AVISO_CONFERENCIA"), out.toString());
        assertTrue(out.path("AVISO_CONFERENCIA").asText().contains("conferencia=" + "fraca"), out.toString());
    }

    @Test
    void actionDeReceitaPublicaGravaOrigemNoRascunho() throws Exception {
        Store s = store("action-publica");
        Tools t = tools(s);
        escreverReceita(s, """
                {"network":"bluesky","actions":{"create_post":{"provada":true,
                 "steps":[{"kind":"click","selector":"css:button"}]}}}
                """);

        String resposta = t.call("social_action",
                Json.obj().put("network", "bluesky").put("action", "create_post"));
        assertFalse(resposta.isBlank());

        Path rascunho;
        try (var arquivos = Files.list(s.batchesDir())) {
            rascunho = arquivos.filter(p -> p.getFileName().toString().endsWith(".draft.json"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("receita publica nao criou rascunho"));
        }
        JsonNode lote = Json.parse(Files.readString(rascunho, StandardCharsets.UTF_8));
        assertTrue(lote.path("origem").isObject(), lote.toString());
        assertEquals("create_post", lote.path("origem").path("receita").asText());
        assertTrue(lote.path("origem").path("provada").asBoolean());
    }

    @Test
    void actionExigeRedeConhecidaAntesDaReceita() throws Exception {
        Tools t = tools(store("action-rede"));
        // acao invalida: se requireNetwork nao rodar primeiro, o erro vira "acao invalida"
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.call("social_action",
                Json.obj().put("network", "nao-existe").put("action", "acao-invalida!!")));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    // ---------- runReadOnly ----------

    @Test
    void runReadOnlyBloqueiaQuandoGuardaNega() throws Exception {
        Store s = store("readonly-guarda");
        Tools t = tools(s);
        injetarPool(t, new PoolFalso(s));
        escreverReceita(s, """
                {"network":"bluesky","actions":{"get_post":{"readOnly":true,
                 "steps":[{"kind":"goto","url":"https://bsky.app/"}]}}}
                """);
        s.setStopped(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.call("social_action",
                Json.obj().put("network", "bluesky").put("action", "get_post")));
        assertTrue(e.getMessage().contains("kill-switch ativo"), e.getMessage());
    }

    @Test
    void runReadOnlySemPassosDevolveOkEJournalSemMotivo() throws Exception {
        Store s = store("readonly-vazio");
        Tools t = tools(s);

        JsonNode out = Json.parse(runReadOnly(t, planoVazio()));
        assertEquals("bluesky", out.path("network").asText());
        assertEquals("get_profile", out.path("action").asText());
        assertTrue(out.path("readOnly").asBoolean());
        assertTrue(out.path("ok").asBoolean(), out.toString());
        assertFalse(out.path("tripwire").asBoolean(true));
        assertEquals(0, out.path("passos").size());
        assertFalse(out.has("motivo"), out.toString());
        assertFalse(out.has("AVISO"), out.toString());
        assertFalse(out.has("idsNovos"), out.toString());

        JsonNode journal = ultimoJournal(s, "browse");
        assertTrue(journal.path("ok").asBoolean(), journal.toString());
        assertEquals("get_profile", journal.path("action").asText());
        assertEquals(0, journal.path("urls").size());
    }

    @Test
    void runReadOnlyComFalhaDeTraceAntigaAvisa() throws Exception {
        Store s = store("readonly-trace-falha");
        Tools t = tools(s);
        tracesDe(t).put("posts", traceQueFalhaAoFechar());

        JsonNode out = Json.parse(runReadOnly(t, planoVazio()));
        String aviso = out.path("AVISO").asText("");
        assertTrue(aviso.contains("limpeza de 1 trace(s) falhou"), out.toString());
        assertTrue(tracesDe(t).isEmpty());
    }

    @Test
    void runReadOnlySemFalhaDeTraceNaoAvisa() throws Exception {
        Store s = store("readonly-trace-ok");
        Tools t = tools(s);

        JsonNode out = Json.parse(runReadOnly(t, planoVazio()));
        assertFalse(out.has("AVISO"), "sem falha nao pode haver aviso de limpeza: " + out);
    }
}
