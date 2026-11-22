package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia Chrome C (rodada 3): mutantes de Tools no lote ({@code social_execute_batch} /
 * {@code executeLocked}) e na leitura ({@code runReadOnly}) que precisam de navegador real.
 * Um unico ChromeFalso + Store + Tools para a classe inteira.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolsChromeLote2Test {

    private static final String BASE = """
            <html><head><title>Base</title></head><body>
            <h1>hello</h1>
            <div id="x" data-k="v"></div>
            <button id="alvo">ok</button>
            </body></html>
            """;

    private static final String FORM = """
            <html><body>
            <input id="vazio">
            <input id="vazio2">
            </body></html>
            """;

    private static final String LISTA = """
            <html><body>
            <ul id="lista"><li data-id="1">um</li></ul>
            <button id="add" onclick="var li=document.createElement('li');li.setAttribute('data-id','2');li.textContent='dois';document.getElementById('lista').appendChild(li);">add</button>
            </body></html>
            """;

    private static final String REQ = """
            <html><body><p>pagina limpa</p></body></html>
            """;

    private static final String PUB8 = """
            <html><body>
            <input id="vazio">
            <ul id="lista"><li data-id="1">um</li></ul>
            <button id="pub" onclick="fetch('/xrpc/com.atproto.repo.createRecord',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({collection:'app.bsky.feed.post',record:{text:'texto prova oito'}})}).then(function(r){return r.json();}).then(function(j){var l=document.getElementById('lista');var a=document.createElement('li');a.setAttribute('data-id','semprova');a.textContent='texto prova oito';l.appendChild(a);var b=document.createElement('li');b.setAttribute('data-id',String(j.uri).split('/').pop());b.textContent='texto prova oito';l.appendChild(b);});">publicar</button>
            </body></html>
            """;

    /** Pagina de publicacao que acrescenta UM item novo (sem o item "semprova" do PUB8). */
    private static final String PUB1 = """
            <html><body>
            <input id="vazio">
            <ul id="lista"><li data-id="1">um</li></ul>
            <button id="pub" onclick="fetch('/xrpc/com.atproto.repo.createRecord',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({collection:'app.bsky.feed.post',record:{text:'texto prova oito'}})}).then(function(r){return r.json();}).then(function(j){var l=document.getElementById('lista');var b=document.createElement('li');b.setAttribute('data-id',String(j.uri).split('/').pop());b.textContent='texto prova oito';l.appendChild(b);});">publicar</button>
            </body></html>
            """;

    private static final String RECEITA_SONDA = """
            {"network":"bluesky","actions":{
              "get_post_sonda":{"params":[],"steps":[
                {"kind":"goto","url":"https://bsky.app/lista"},
                {"kind":"capture_ids","selector":"css:li","attr":"data-id","name":"x","stableMs":1000,"waitMs":2000,"responseProof":true,"expect":"texto prova","description":"capturar"},
                {"kind":"click","selector":"css:#add","description":"controle"}]}
            }}
            """;

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;
    static Tools tools;
    static TypeText.Pausa pausaOriginal;
    static LongSupplier relogioOriginal;

    @BeforeAll
    static void abrirFixture() throws Exception {
        store = new Store(tmp.resolve("home"));
        Path dir = store.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), RECEITA_SONDA, StandardCharsets.UTF_8);
        configurarPadrao();
        cf = ChromeFalso.abrir(tmp, store);
        cf.pagina("/base", BASE);
        cf.pagina("/form", FORM);
        cf.pagina("/lista", LISTA);
        cf.pagina("/req", REQ);
        cf.pagina("/pub8", PUB8);
        cf.pagina("/pub1", PUB1);
        cf.resposta("/xrpc/com.atproto.repo.createRecord", 200, "application/json; charset=utf-8",
                "{\"uri\":\"at://did:plc:teste/app.bsky.feed.post/novopost1\",\"cid\":\"cid1\"}");
        tools = new Tools(store);
        pausaOriginal = Tools.pausa;
        relogioOriginal = Tools.relogio;
    }

    @AfterAll
    static void fecharFixture() {
        if (tools != null) tools.shutdown();
        if (cf != null) cf.close();
    }

    @BeforeEach
    void preparar() throws Exception {
        store.setState("bluesky", "healthy", "teste", "teste");
        cf.limparPedidos();
        limparExecutados();
    }

    @AfterEach
    void restaurarCosturas() {
        Tools.pausa = pausaOriginal;
        Tools.relogio = relogioOriginal;
    }

    /** Config base + tripwire para o fetch rejeitado + requestHosts para o IP sem rota. */
    private static void configurarPadrao() throws Exception {
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ObjectNode defs = (ObjectNode) raiz.path("defaults");
        defs.put("maxPublicActionsPerDay", 50);
        defs.put("minMinutesBetweenPublicActions", 0);
        defs.put("maxBatchesPerDay", 50);
        defs.put("maxPublicStepsPerBatch", 10);
        defs.put("typeMode", "insertText");
        ArrayNode stepDelay = defs.putArray("stepDelayMs");
        stepDelay.add(0);
        stepDelay.add(0);
        ArrayNode chunk = defs.putArray("typeChunkDelayMs");
        chunk.add(0);
        chunk.add(0);
        ObjectNode bsky = (ObjectNode) raiz.path("networks").path("bluesky");
        bsky.put("startUrl", "https://bsky.app/");
        bsky.put("attachExistingChrome", true);
        bsky.put("closeAfterBatch", false);
        bsky.put("browserIdleMinutes", 0);
        ArrayNode trips = bsky.path("tripwires").isArray()
                ? (ArrayNode) bsky.path("tripwires") : bsky.putArray("tripwires");
        if (!trips.toString().contains("failed to fetch")) trips.add("failed to fetch");
        if (!trips.toString().contains("fetch cancelado")) trips.add("fetch cancelado");
        ArrayNode hosts = bsky.path("requestHosts").isArray()
                ? (ArrayNode) bsky.path("requestHosts") : bsky.putArray("requestHosts");
        if (!hosts.toString().contains("10.255.255.1")) hosts.add("10.255.255.1");
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    private void limparExecutados() throws Exception {
        if (!Files.isDirectory(store.batchesDir())) return;
        try (var s = Files.list(store.batchesDir())) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".executed.json")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private String preparar(String acoesJson, boolean ensaio) throws Exception {
        BatchStore bs = new BatchStore(store);
        String id = bs.draft("teste chrome lote2", Json.parse(acoesJson)).path("id").asText();
        bs.approve(id, "humano", "nota de teste", List.of(), true, ensaio);
        return id;
    }

    private JsonNode executar(String id) throws Exception {
        return Json.parse(tools.call("social_execute_batch", Json.obj().put("batchId", id)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PublishTrace> tracesDe() {
        try {
            Field f = Tools.class.getDeclaredField("traces");
            f.setAccessible(true);
            return (Map<String, PublishTrace>) f.get(tools);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("campo traces", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> mapaDe(String campo) {
        try {
            Field f = Tools.class.getDeclaredField(campo);
            f.setAccessible(true);
            return (Map<String, ?>) f.get(tools);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("campo " + campo, e);
        }
    }

    /** Estado por chamada de Tools: o finally de lote/readOnly/sonda precisa esvaziar os tres mapas. */
    private static void assertMapasVazios() {
        assertTrue(mapaDe("capturas").isEmpty(), "capturas deveria estar vazio apos a chamada");
        assertTrue(mapaDe("idsNovosLote").isEmpty(), "idsNovosLote deveria estar vazio apos a chamada");
        assertTrue(mapaDe("traces").isEmpty(), "traces deveria estar vazio apos a chamada");
    }

    /**
     * Hook de Tools.pausa: na primeira pausa depois do capture, troca o trace real por um falso
     * (com corpo fixo ja alimentado) para controlar a correlacao do verify_new_id.
     */
    private static TypeText.Pausa traceFalsoNoLugarDoReal(String texto, String corpo,
            AtomicBoolean armado, TypeText.Pausa original) {
        return ms -> {
            try {
                Map<String, PublishTrace> mapa = tracesDe();
                if (!armado.get() && mapa.get("x") != null) {
                    PublishTrace real = mapa.remove("x");
                    if (real != null) real.close();
                    PublishTrace falso = PublishTrace.start(cdpComCorpoFixo(corpo), "bluesky", texto);
                    mapa.put("x", falso);
                    alimentarTrace(falso, texto);
                    armado.set(true);
                }
                original.dormir(ms);
            } catch (InterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            }
        };
    }

    private static String runReadOnlyRef(Recipes.Plan plano) {
        try {
            Method m = Tools.class.getDeclaredMethod("runReadOnly", Recipes.Plan.class);
            m.setAccessible(true);
            return (String) m.invoke(tools, plano);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) throw re;
            if (causa instanceof Error err) throw err;
            throw new RuntimeException(causa);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("runReadOnly", e);
        }
    }

    private static ObjectNode passo(String kind) {
        ObjectNode o = Json.obj();
        o.put("network", "bluesky");
        o.put("kind", kind);
        return o;
    }

    private boolean journalTem(String tipo, String trecho) {
        return store.tail(500).stream()
                .filter(l -> l.contains("\"type\":\"" + tipo + "\""))
                .anyMatch(l -> l.contains(trecho));
    }

    private static PublishTrace traceQueFalhaAoFechar() {
        PublishTrace.Cdp cdp = new PublishTrace.Cdp() {
            @Override
            public CompletableFuture<Void> addCdpListener(String evento,
                    Consumer<JsonNode> listener) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> removeCdpListener(String evento,
                    Consumer<JsonNode> listener) {
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

    // ---------- item 1: limpeza dos mapas so no finally ----------

    @Test
    @Order(1)
    @Timeout(60)
    void limpezaEntreLotesFalhaVerifySemCapture() throws Exception {
        String texto = "texto prova oito";
        String corpo = "{\"uri\":\"at://did:plc:teste/app.bsky.feed.post/novopost1\",\"cid\":\"cid1\"}";
        TypeText.Pausa original = Tools.pausa;
        AtomicBoolean armado = new AtomicBoolean(false);
        Tools.pausa = traceFalsoNoLugarDoReal(texto, corpo, armado, original);
        try {
            String lote = preparar("""
                    [{"network":"bluesky","kind":"goto","url":"https://bsky.app/pub1","description":"abrir"},
                     {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"x","stableMs":1000,"waitMs":2000,"responseProof":true,"expect":"texto prova oito","description":"capturar"},
                     {"network":"bluesky","kind":"type","selector":"css:#vazio","text":"texto prova oito","description":"digitar"},
                     {"network":"bluesky","kind":"click","selector":"css:#pub","description":"publicar"},
                     {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"x","expect":"texto prova oito","responseProof":true,"timeoutMs":8000,"description":"novo"}]
                    """, true);
            JsonNode r = executar(lote);
            assertTrue(armado.get(), "hook devia ter armado o trace antes do verify");
            assertFalse(r.path("abortado").asBoolean(true), r.toString());
            assertTrue(r.path("passos").get(1).path("ok").asBoolean(false), r.toString());
            JsonNode novo = r.path("passos").get(4);
            assertTrue(novo.path("ok").asBoolean(false), r.toString());
            assertEquals("novopost1", novo.path("idNovo").asText(), r.toString());
            assertMapasVazios();
        } finally {
            Tools.pausa = original;
        }
    }

    @Test
    @Order(2)
    @Timeout(60)
    void limpezaAposReadOnlyFalhaVerifySemCapture() throws Exception {
        ArrayNode passos = Json.M.createArrayNode();
        ObjectNode gotoPasso = passo("goto");
        gotoPasso.put("url", "https://bsky.app/lista");
        passos.add(gotoPasso);
        ObjectNode cap = passo("capture_ids");
        cap.put("selector", "css:li");
        cap.put("attr", "data-id");
        cap.put("name", "x");
        cap.put("stableMs", 1000);
        cap.put("waitMs", 2000);
        cap.put("responseProof", true);
        cap.put("expect", "texto prova");
        passos.add(cap);
        JsonNode leitura = Json.parse(runReadOnlyRef(new Recipes.Plan("bluesky", "acao", true, passos, "rotulo", true)));
        assertTrue(leitura.path("ok").asBoolean(false), leitura.toString());
        assertTrue(leitura.path("passos").get(1).path("ok").asBoolean(false), leitura.toString());
        assertMapasVazios();
    }

    @Test
    @Order(3)
    @Timeout(60)
    void limpezaAposProbeFalhaVerifySemCapture() throws Exception {
        ObjectNode args = Json.obj();
        args.put("network", "bluesky");
        args.put("action", "get_post_sonda");
        args.set("params", Json.obj());
        JsonNode sonda = Json.parse(tools.call("social_probe", args));
        assertTrue(sonda.path("ok").asBoolean(false), sonda.toString());
        assertEquals(2, sonda.path("passos").size(), sonda.toString());
        assertTrue(sonda.path("passos").get(1).path("ok").asBoolean(false), sonda.toString());
        assertMapasVazios();
    }

    // ---------- item 2: traceCleanupFailures += fecharTraces / > 0 ----------

    @Test
    @Order(4)
    @Timeout(60)
    void falhaDeTraceNoLoteAvisaECriaJournal() throws Exception {
        TypeText.Pausa original = Tools.pausa;
        AtomicBoolean injetado = new AtomicBoolean(false);
        Tools.pausa = ms -> {
            if (injetado.compareAndSet(false, true)) {
                tracesDe().put("zumbi", traceQueFalhaAoFechar());
            }
            original.dormir(ms);
        };
        try {
            String id = preparar("""
                    [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                     {"network":"bluesky","kind":"wait","ms":1,"description":"espera"}]
                    """, false);
            JsonNode r = executar(id);
            assertFalse(r.path("abortado").asBoolean(true), r.toString());
            // sem aviso anterior, o de limpeza vem sozinho (sem espaco na frente)
            assertTrue(r.path("AVISO").asText("").startsWith("limpeza de 1 trace(s) falhou"), r.toString());
            assertTrue(journalTem("trace_cleanup_failed", "\"count\":1"), store.tail(500).toString());
        } finally {
            Tools.pausa = original;
        }
    }

    @Test
    @Order(5)
    @Timeout(60)
    void falhaDeTraceNoReadOnlyAvisa() throws Exception {
        ArrayNode passos = Json.M.createArrayNode();
        ObjectNode espera = passo("wait");
        espera.put("ms", 1);
        passos.add(espera);
        ObjectNode gotoPasso = passo("goto");
        gotoPasso.put("url", "https://bsky.app/base");
        passos.add(gotoPasso);
        TypeText.Pausa original = Tools.pausa;
        AtomicBoolean injetado = new AtomicBoolean(false);
        Tools.pausa = ms -> {
            if (injetado.compareAndSet(false, true)) {
                tracesDe().put("zumbi", traceQueFalhaAoFechar());
            }
            original.dormir(ms);
        };
        try {
            JsonNode out = Json.parse(runReadOnlyRef(
                    new Recipes.Plan("bluesky", "acao", true, passos, "rotulo", true)));
            assertTrue(out.path("ok").asBoolean(false), out.toString());
            assertTrue(out.path("AVISO").asText("").contains("limpeza de 1 trace(s) falhou"), out.toString());
            assertTrue(journalTem("trace_cleanup_failed", "\"count\":1"), store.tail(500).toString());
        } finally {
            Tools.pausa = original;
        }
    }

    // ---------- item 3: envio pulado nao conta como envio feito ----------

    @Test
    @Order(6)
    @Timeout(60)
    void envioPuladoAntesDoAbortoConfereAntesDoEnvio() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#alvo","description":"engajou"},
                 {"network":"bluesky","kind":"click","selector":"css:#ausente-xyz","ifPresent":true,"envio":true,"description":"envio condicional"},
                 {"network":"bluesky","kind":"verify_text","expect":"texto-que-nao-existe-xyz","description":"prova"}]
                """, true);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        assertTrue(r.path("passos").get(2).path("pulado").asBoolean(false), r.toString());
        assertEquals("abortado_antes_do_envio", r.path("conferencia").asText(), r.toString());
    }

    // ---------- item 4: tripwire da resposta com status 0 ----------

    @Test
    @Order(7)
    @Timeout(60)
    void requestStatusZeroMarcaIncertoETripwire() throws Exception {
        AtomicLong agora = new AtomicLong(System.currentTimeMillis());
        TypeText.Pausa pausaOrig = Tools.pausa;
        LongSupplier relogioOrig = Tools.relogio;
        Tools.relogio = agora::get;
        Tools.pausa = ms -> { agora.addAndGet(ms); };
        try {
            String id = preparar("""
                    [{"network":"bluesky","kind":"goto","url":"https://bsky.app/req","description":"abrir"},
                     {"network":"bluesky","kind":"request","method":"POST","url":"https://10.255.255.1/api/x","description":"req"}]
                    """, true);
            JsonNode r = executar(id);
            assertTrue(r.path("abortado").asBoolean(false), r.toString());
            assertTrue(r.path("motivo").asText("").contains("resultado incerto"), r.toString());
            JsonNode req = r.path("passos").get(1);
            assertFalse(req.path("ok").asBoolean(true), r.toString());
            assertEquals(0, req.path("status").asInt(-1), r.toString());
            assertTrue(req.path("incerto").asBoolean(false), r.toString());
            assertTrue(req.path("tripwire").asBoolean(false), r.toString());
            assertEquals("cooldown", store.effectiveState("bluesky"), r.toString());
            assertTrue(journalTem("tripwire", "\"batchId\":\"" + id + "\""), store.tail(100).toString());
        } finally {
            Tools.pausa = pausaOrig;
            Tools.relogio = relogioOrig;
        }
    }

    // ---------- item 5: click interrompido no poll de presente ----------

    @Test
    @Order(8)
    @Timeout(60)
    void clickIfPresentInterrompidoAntesDeAgir() throws Exception {
        Thread.interrupted(); // garante que a flag observada foi ligada por este passo
        TypeText.Pausa original = Tools.pausa;
        Tools.pausa = ms -> { throw new InterruptedException("pausa de teste"); };
        try {
            String id = preparar("""
                    [{"network":"bluesky","kind":"click","selector":"css:#ausente-xyz","ifPresent":true,"description":"aviso"}]
                    """, true);
            try {
                executar(id);
            } catch (Exception ignorada) {
                // com a flag ligada o journal pode falhar ao pegar a trava; o que importa e a flag
            }
            assertTrue(Thread.interrupted(), "flag de interrupcao deve sobreviver ao passo");
        } finally {
            Thread.interrupted(); // nao vazar a interrupcao para os proximos testes
            Tools.pausa = original;
        }
    }

    // ---------- item 6: type em modo teclado humanizado ----------

    @Test
    @Order(9)
    @Timeout(60)
    void typeTecladoHumanizado() throws Exception {
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ObjectNode defs = (ObjectNode) raiz.path("defaults");
        String antes = defs.path("typeMode").asText("insertText");
        defs.put("typeMode", "keyboard");
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
        try {
            String id = preparar("""
                    [{"network":"bluesky","kind":"goto","url":"https://bsky.app/form","description":"abrir"},
                     {"network":"bluesky","kind":"click","selector":"css:#vazio","description":"focar"},
                     {"network":"bluesky","kind":"type","text":"oi","description":"digitar"}]
                    """, true);
            JsonNode r = executar(id);
            assertFalse(r.path("abortado").asBoolean(true), r.toString());
            JsonNode digitou = r.path("passos").get(2);
            assertTrue(digitou.path("ok").asBoolean(false), r.toString());
            assertEquals("digitou 2 caracteres (teclado humanizado)", digitou.path("info").asText());
            assertEquals("keyboard", digitou.path("modo").asText());
        } finally {
            ObjectNode volta = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
            ((ObjectNode) volta.path("defaults")).put("typeMode", antes);
            Files.writeString(store.configPath(), Json.writePretty(volta), StandardCharsets.UTF_8);
        }
    }

    // ---------- item 7: segundo capture_ids fecha o anterior ----------

    @Test
    @Order(10)
    @Timeout(60)
    void segundoCaptureFechaAnterior() throws Exception {
        TypeText.Pausa original = Tools.pausa;
        AtomicBoolean trocado = new AtomicBoolean(false);
        Tools.pausa = ms -> {
            Map<String, PublishTrace> mapa = tracesDe();
            if (!trocado.get() && mapa.get("x") != null) {
                PublishTrace real = mapa.remove("x");
                if (real != null) real.close();
                mapa.put("x", traceQueFalhaAoFechar());
                trocado.set(true);
            }
            original.dormir(ms);
        };
        try {
            // Dois capture_ids de mesmo name nao passam pelo lote (validateIdPairs), mas a
            // execucao readOnly usa o mesmo runCaptureIds: o 2o capture fecha o trace do 1o.
            ArrayNode passos = Json.M.createArrayNode();
            ObjectNode gotoPasso = passo("goto");
            gotoPasso.put("url", "https://bsky.app/lista");
            passos.add(gotoPasso);
            ObjectNode c1 = passo("capture_ids");
            c1.put("selector", "css:li");
            c1.put("attr", "data-id");
            c1.put("name", "x");
            c1.put("stableMs", 1000);
            c1.put("waitMs", 2000);
            c1.put("responseProof", true);
            c1.put("expect", "texto prova");
            passos.add(c1);
            ObjectNode espera = passo("wait");
            espera.put("ms", 1);
            passos.add(espera);
            passos.add(c1.deepCopy());
            JsonNode leitura = Json.parse(runReadOnlyRef(
                    new Recipes.Plan("bluesky", "acao", true, passos, "rotulo", true)));
            assertTrue(trocado.get(), "hook devia ter trocado o trace antes do 2o capture");
            assertTrue(leitura.path("passos").get(1).path("ok").asBoolean(false), leitura.toString());
            JsonNode segundo = leitura.path("passos").get(3);
            assertFalse(segundo.path("ok").asBoolean(true), leitura.toString());
            assertTrue(segundo.path("erro").asText("").contains("Falha ao limpar listener CDP"),
                    leitura.toString());
        } finally {
            Tools.pausa = original;
        }
    }

    // ---------- item 8: correlacao do verify_new_id com responseProof ----------

    private static PublishTrace.Cdp cdpComCorpoFixo(String corpoJson) {
        return new PublishTrace.Cdp() {
            @Override
            public CompletableFuture<Void> addCdpListener(String evento,
                    Consumer<JsonNode> listener) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> removeCdpListener(String evento,
                    Consumer<JsonNode> listener) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<JsonNode> executeCdpCmd(String comando,
                    Map<String, Object> params, Float timeout) {
                if ("Network.getResponseBody".equals(comando)) {
                    ObjectNode o = Json.obj();
                    o.put("body", corpoJson);
                    o.put("base64Encoded", false);
                    return CompletableFuture.completedFuture(o);
                }
                return CompletableFuture.completedFuture(Json.obj());
            }
        };
    }

    private static void alimentarTrace(PublishTrace trace, String textoAprovado) {
        try {
            Method onEvent = PublishTrace.class.getDeclaredMethod("onEvent", String.class, JsonNode.class);
            onEvent.setAccessible(true);
            ObjectNode req = Json.obj();
            req.put("requestId", "r1");
            ObjectNode detalhe = Json.obj();
            detalhe.put("method", "POST");
            detalhe.put("url", "https://bsky.social/xrpc/com.atproto.repo.createRecord");
            detalhe.put("postData",
                    "{\"collection\":\"app.bsky.feed.post\",\"record\":{\"text\":\"" + textoAprovado + "\"}}");
            req.set("request", detalhe);
            onEvent.invoke(trace, "Network.requestWillBeSent", req);
            ObjectNode resp = Json.obj();
            resp.put("requestId", "r1");
            ObjectNode resposta = Json.obj();
            resposta.put("status", 200);
            resp.set("response", resposta);
            onEvent.invoke(trace, "Network.responseReceived", resp);
            ObjectNode fim = Json.obj();
            fim.put("requestId", "r1");
            onEvent.invoke(trace, "Network.loadingFinished", fim);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) throw re;
            throw new RuntimeException(causa);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("onEvent", e);
        }
    }

    @Test
    @Order(11)
    @Timeout(60)
    void verifyComResponseProofCasaIdDoTrace() throws Exception {
        String texto = "texto prova oito";
        String corpo = "{\"uri\":\"at://did:plc:teste/app.bsky.feed.post/novopost1\",\"cid\":\"cid1\"}";
        TypeText.Pausa original = Tools.pausa;
        AtomicBoolean armado = new AtomicBoolean(false);
        Tools.pausa = traceFalsoNoLugarDoReal(texto, corpo, armado, original);
        try {
            String id = preparar("""
                    [{"network":"bluesky","kind":"goto","url":"https://bsky.app/pub8","description":"abrir"},
                     {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"x","stableMs":1000,"waitMs":2000,"responseProof":true,"expect":"texto prova oito","description":"capturar"},
                     {"network":"bluesky","kind":"type","selector":"css:#vazio","text":"texto prova oito","description":"digitar"},
                     {"network":"bluesky","kind":"click","selector":"css:#pub","description":"publicar"},
                     {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"x","expect":"texto prova oito","responseProof":true,"timeoutMs":8000,"description":"novo"}]
                    """, true);
            JsonNode r = executar(id);
            assertTrue(armado.get(), "hook devia ter armado o trace antes do verify");
            assertFalse(r.path("abortado").asBoolean(true), r.toString());
            JsonNode novo = r.path("passos").get(4);
            assertTrue(novo.path("ok").asBoolean(false), r.toString());
            assertEquals("novopost1", novo.path("idNovo").asText(), r.toString());
        } finally {
            Tools.pausa = original;
        }
    }
}
