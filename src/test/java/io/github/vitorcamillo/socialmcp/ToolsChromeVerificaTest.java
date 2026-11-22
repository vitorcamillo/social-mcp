package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mutantes de Tools que so podem ser observados com navegador real:
 * verify_text/verify_attr/verify_absent, capture_ids/verify_new_id, wait,
 * sendCombo/dispatchKey, extract, presente/el/shadowEl e conferirDestino.
 *
 * <p>Um ChromeFalso (Chrome headless + HTTPS local) e um Store/Tools para a classe
 * inteira; cada teste navega para a pagina de que precisa. Os passos sao executados
 * por lotes aprovados (com forcar=true, sem gate externo) e o resultado observado no
 * resumo, no journal e nos textos de retorno.
 */
class ToolsChromeVerificaTest {

    @TempDir
    static Path tmp;

    private static Store store;
    private static ChromeFalso chrome;
    private static Tools tools;
    private static int seq;

    // ---------- fixture ----------

    @BeforeAll
    static void abrirFixture() throws Exception {
        store = new Store(tmp.resolve("home"));
        chrome = ChromeFalso.abrir(tmp, store);
        configurarRapido();
        publicarPaginas();
        tools = new Tools(store);
    }

    @AfterAll
    static void fecharFixture() {
        if (tools != null) tools.shutdown();
        if (chrome != null) chrome.close();
    }

    private TypeText.Pausa pausaOriginal;
    private List<Long> pausas;

    /**
     * Retry de verify_text/verify_attr (1000 ms) nao dorme de verdade: grava os ms pedidos. As demais
     * pausas (poll de wait/verify_absent/shadowEl, wait com ms) dormem de verdade: esses testes medem tempo real.
     */
    @BeforeEach
    void trocarPausaPorGravador() {
        pausaOriginal = Tools.pausa;
        pausas = Collections.synchronizedList(new ArrayList<>());
        Tools.pausa = ms -> {
            if (ms == 1_000L) pausas.add(ms);
            else Thread.sleep(ms);
        };
    }

    @AfterEach
    void restaurarPausa() {
        Tools.pausa = pausaOriginal;
    }

    /** Cotas altas e esperas minimas: o foco destes testes sao os mutantes, nao as travas. */
    private static void configurarRapido() throws Exception {
        Path cfg = store.configPath();
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(cfg, StandardCharsets.UTF_8));
        ObjectNode d = (ObjectNode) raiz.path("defaults");
        d.put("maxBatchesPerDay", 500);
        d.put("maxPublicActionsPerDay", 500);
        d.put("maxPublicStepsPerBatch", 50);
        d.put("minMinutesBetweenPublicActions", 0);
        d.set("stepDelayMs", Json.parse("[1,1]"));
        d.set("typeChunkDelayMs", Json.parse("[1,1]"));
        d.put("closeAfterBatch", false);
        // aborts publicos destes testes nao podem travar a rede para os testes seguintes
        d.put("travaIncertezaDesde", "2999-01-01");
        Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    private static void publicarPaginas() {
        chrome.pagina("/hub", """
                <html><head><title>Hub</title></head><body>
                <p id="msg">Mensagem Principal</p>
                <p id="exact">  Texto   Exato  </p>
                <div id="attrbox" data-state="on" title="inicial">attr</div>
                <div id="shadowHost"></div>
                <div id="linhas">banana<br>abacaxi<br><br>vazia</div>
                <a href="https://bsky.app/um">um</a>
                <a href="https://bsky.app/dois">dois</a>
                <textarea id="ta" autofocus></textarea>
                <div id="log"></div>
                <script>
                const h = document.getElementById('shadowHost');
                const sr = h.attachShadow({mode:'open'});
                sr.innerHTML = '<p id="msg">dentro do shadow</p>';
                const ta = document.getElementById('ta');
                const log = document.getElementById('log');
                function add(s){ log.textContent += s + '|'; }
                document.addEventListener('keydown', e => add('D:' + e.key + ':' + e.ctrlKey));
                document.addEventListener('keyup', e => add('U:' + e.key + ':' + e.ctrlKey));
                ta.addEventListener('input', () => add('I:' + JSON.stringify(ta.value)));
                ta.focus();
                </script>
                </body></html>
                """);

        chrome.pagina("/dinamicos", """
                <html><body>
                <div id="attrbox" data-state="off">x</div>
                <script>
                var n = 0;
                Object.defineProperty(document.body, 'innerText', {configurable:true, get:function(){
                  n++; return n < 4 ? 'carregando' : 'pronto agora';
                }});
                var el = document.getElementById('attrbox');
                var m = 0;
                var orig = el.getAttribute.bind(el);
                el.getAttribute = function(a){
                  if (a === 'data-state') { m++; return m < 3 ? 'off' : 'on'; }
                  return orig(a);
                };
                </script>
                </body></html>
                """);

        chrome.pagina("/some", """
                <html><body>
                <div id="some">presente</div>
                <script>setTimeout(function(){ var e = document.getElementById('some'); if (e) e.remove(); }, 2500);</script>
                </body></html>
                """);

        chrome.pagina("/cresce", """
                <html><body>
                <div id="alvo">carregando</div>
                <script>
                var alvo = document.getElementById('alvo');
                var n = 0;
                Object.defineProperty(alvo, 'innerText', {configurable:true, get:function(){
                  n++; return n < 3 ? 'carregando' : 'pronto agora';
                }});
                </script>
                </body></html>
                """);

        chrome.pagina("/capA", lista("""
                <li data-id="a">antigo</li>
                """));
        chrome.pagina("/capB", lista("""
                <li data-id="a">antigo</li>
                <li data-id="b">novo texto completo</li>
                """));
        chrome.pagina("/capC", lista("""
                <li data-id="a">antigo</li>
                <li data-id="b">novo texto completo</li>
                <li data-id="c">abc curto</li>
                """));

        chrome.pagina("/rp", """
                <html><body>
                <ul id="lista"><li data-id="a">antigo</li></ul>
                <input id="entrada" type="text">
                <script>
                var entrada = document.getElementById('entrada');
                entrada.addEventListener('input', function(){
                  var li = document.querySelector('li[data-id="b"]');
                  if (!li) {
                    li = document.createElement('li');
                    li.setAttribute('data-id','b');
                    document.getElementById('lista').appendChild(li);
                  }
                  li.textContent = entrada.value;
                });
                </script>
                </body></html>
                """);

        chrome.pagina("/botoes", """
                <html><body>
                <button id="btn">Clique</button>
                <div id="log"></div>
                <script>
                var origQS = document.querySelector.bind(document);
                var n = 0;
                document.querySelector = function(s){
                  if (s === '#late') {
                    n++;
                    if (n >= 2 && !document.getElementById('late')) {
                      var b = document.createElement('button');
                      b.id = 'late';
                      b.textContent = 'Tarde';
                      document.body.appendChild(b);
                    }
                  }
                  return origQS(s);
                };
                </script>
                </body></html>
                """);

        chrome.pagina("/shadow", """
                <html><body>
                <div id="host"></div>
                <script>
                var host = document.getElementById('host');
                var root = host.attachShadow({mode:'open'});
                var n = 0;
                var orig = root.querySelector.bind(root);
                root.querySelector = function(s){
                  n++;
                  if (n >= 7) { root.innerHTML = '<p id="msg">dentro do shadow</p>'; }
                  return orig(s);
                };
                </script>
                </body></html>
                """);
    }

    private static String lista(String itens) {
        return "<html><body><ul id=\"lista\">" + itens + "</ul></body></html>";
    }

    // ---------- helpers de lote ----------

    private static ObjectNode acao(String kind) {
        return Json.obj().put("network", "bluesky").put("kind", kind).put("description", "teste");
    }

    private static ObjectNode irPara(String caminho) {
        return acao("goto").put("url", "https://bsky.app" + caminho);
    }

    private static JsonNode executar(JsonNode acoes) throws Exception {
        BatchStore bs = new BatchStore(store);
        String id = bs.draft("teste chrome verifica " + (seq++), acoes).path("id").asText();
        bs.approve(id, "humano", "teste local", List.of(), true, true);
        return Json.parse(tools.call("social_execute_batch", Json.obj().put("batchId", id)));
    }

    /** Lote aprovado direto no disco: permite acao que o draft validaria (ex.: exact sem selector). */
    private static JsonNode executarCru(JsonNode acoes) throws Exception {
        String id = "b" + System.currentTimeMillis() + "-" + (seq++);
        ObjectNode b = Json.obj();
        b.put("id", id);
        b.put("label", "teste cru");
        b.put("status", "approved");
        b.put("createdAt", System.currentTimeMillis());
        b.put("approvedAt", System.currentTimeMillis());
        b.put("approvedPor", "humano");
        b.put("network", "bluesky");
        b.put("ensaioAutorizado", true);
        b.set("actions", acoes);
        b.put("actionsHash", TextGate.hashActions(acoes));
        Files.writeString(store.batchesDir().resolve(id + ".approved.json"),
                Json.writePretty(b), StandardCharsets.UTF_8);
        return Json.parse(tools.call("social_execute_batch", Json.obj().put("batchId", id)));
    }

    private static void semAborto(JsonNode resumo, int passos) {
        assertFalse(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertEquals(passos, resumo.path("passos").size(), resumo.toString());
        assertEquals(passos, resumo.path("passosExecutados").asInt(-1), resumo.toString());
    }

    private static String info(JsonNode resumo, int indice) {
        return resumo.path("passos").get(indice).path("info").asText("");
    }

    private static long msDoInfo(String info) {
        return Long.parseLong(info.replaceAll("^.* em (\\d+) ms$", "$1"));
    }

    // ---------- verify_text / verify_attr / wait / extract (caminho feliz) ----------

    @Test
    @Timeout(60)
    void caminhoFelizDeVerificacoesEsperasEExtracoes() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/hub"));
        acoes.add(acao("verify_text").put("expect", "Mensagem Principal"));
        acoes.add(acao("verify_text").put("selector", "#msg").put("expect", "Mensagem"));
        acoes.add(acao("verify_text").put("selector", "#exact").put("expect", "Texto Exato").put("exact", true));
        acoes.add(acao("verify_text").put("selector", "id:msg").put("expect", "Mensagem"));
        acoes.add(acao("verify_text").put("selector", "xpath://p[@id='msg']").put("expect", "Mensagem"));
        acoes.add(acao("verify_text").put("selector", "css:#msg").put("expect", "Mensagem"));
        acoes.add(acao("verify_text").put("selector", "#msg").put("expect", "Mensagem"));
        acoes.add(acao("verify_text").put("selector", "css:#shadowHost >>> #msg").put("expect", "dentro do shadow"));
        acoes.add(acao("verify_attr").put("selector", "#attrbox").put("attr", "data-state").put("expect", "on"));
        acoes.add(acao("verify_absent").put("selector", "#naoexiste").put("timeoutMs", 1));
        acoes.add(acao("extract").put("what", "links").put("match", "bsky.app"));
        acoes.add(acao("extract").put("what", "text").put("match", "banana"));
        acoes.add(acao("wait").put("ms", 10));
        acoes.add(acao("wait").put("expect", "Mensagem Principal").put("timeoutMs", 1000));
        acoes.add(acao("wait").put("selector", "#msg").put("expect", "Mensagem").put("timeoutMs", 1000));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 16);

        assertEquals("texto verificado", info(resumo, 1));
        assertEquals("texto verificado no elemento", info(resumo, 2));
        assertEquals("texto exato verificado no elemento", info(resumo, 3));
        assertEquals("texto verificado no elemento", info(resumo, 4));
        assertEquals("texto verificado no elemento", info(resumo, 5));
        assertEquals("texto verificado no elemento", info(resumo, 6));
        assertEquals("texto verificado no elemento", info(resumo, 7));
        assertEquals("texto verificado no elemento", info(resumo, 8));
        assertEquals("atributo verificado: data-state=on", info(resumo, 9));
        assertTrue(info(resumo, 10).startsWith("ausente em "), info(resumo, 10));

        JsonNode links = resumo.path("passos").get(11).path("extraido");
        assertTrue(links.isArray(), resumo.toString());
        assertEquals(2, links.size(), links.toString());
        assertTrue(links.toString().contains("/um"), links.toString());
        assertTrue(links.toString().contains("/dois"), links.toString());

        JsonNode linhas = resumo.path("passos").get(12).path("extraido");
        assertTrue(linhas.isArray(), resumo.toString());
        assertEquals(1, linhas.size(), linhas.toString());
        assertEquals("banana", linhas.get(0).asText());

        assertEquals("esperou 10 ms", info(resumo, 13));

        String texto = info(resumo, 14);
        assertTrue(texto.startsWith("texto apareceu em "), texto);
        assertTrue(msDoInfo(texto) >= 0 && msDoInfo(texto) < 10_000, texto);

        String doElemento = info(resumo, 15);
        assertTrue(doElemento.startsWith("texto do elemento apareceu em "), doElemento);
        assertTrue(msDoInfo(doElemento) >= 0 && msDoInfo(doElemento) < 10_000, doElemento);

        // sucesso na 1a leitura: nenhum retry de 1 s (ignora o jitter de 1 ms entre passos)
        assertEquals(List.of(), pausas.stream().filter(ms -> ms == 1_000L).toList(),
                "verifies de primeira nao devem pausar entre tentativas: " + pausas);
    }

    // ---------- verify_text: tentativas, sleep e mensagens de falha ----------

    @Test
    @Timeout(60)
    void verifyTextToleraSpaQueSoCarregaNaTerceiraLeitura() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/dinamicos"));
        acoes.add(acao("verify_text").put("expect", "pronto agora"));
        JsonNode resumo = executar(acoes);
        semAborto(resumo, 2);
        assertEquals("texto verificado", info(resumo, 1));
    }

    @Test
    @Timeout(60)
    void verifyTextFalhaComMensagemDaPaginaNoTempoDasTresTentativas() throws Exception {
        tools.call("social_browse", Json.obj().put("network", "bluesky")
                .put("url", "https://bsky.app/hub"));

        var semSelector = Json.M.createArrayNode();
        semSelector.add(acao("verify_text").put("expect", "NAO_EXISTE_EM_LUGAR_NENHUM"));
        pausas.clear();
        JsonNode resumo = executar(semSelector);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        String motivo = resumo.path("motivo").asText("");
        assertTrue(motivo.contains("nao encontrado na pagina"), motivo);
        assertEquals(List.of(1_000L, 1_000L), pausas,
                "3 tentativas = 2 pausas de 1 s (com <= seriam 3): " + pausas);

        var comSelector = Json.M.createArrayNode();
        comSelector.add(acao("verify_text").put("selector", "#msg")
                .put("expect", "ZZZ").put("exact", true));
        pausas.clear();
        JsonNode falhou = executar(comSelector);
        assertTrue(falhou.path("abortado").asBoolean(false), falhou.toString());
        assertTrue(falhou.path("motivo").asText("").contains("nao e exatamente 'ZZZ'"),
                falhou.path("motivo").asText(""));
        assertEquals(List.of(1_000L, 1_000L), pausas,
                "3 tentativas = 2 pausas de 1 s (com <= seriam 3): " + pausas);
    }

    // ---------- verify_attr ----------

    @Test
    @Timeout(60)
    void verifyAttrLeAteTresVezesAteBaterOAtributo() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/dinamicos"));
        acoes.add(acao("verify_attr").put("selector", "#attrbox")
                .put("attr", "data-state").put("expect", "on"));
        JsonNode resumo = executar(acoes);
        semAborto(resumo, 2);
        assertEquals("atributo verificado: data-state=on", info(resumo, 1));
    }

    @Test
    @Timeout(60)
    void verifyAttrFalhaComAtributoAusenteETresEsperas() throws Exception {
        tools.call("social_browse", Json.obj().put("network", "bluesky")
                .put("url", "https://bsky.app/hub"));
        var acoes = Json.M.createArrayNode();
        acoes.add(acao("verify_attr").put("selector", "#attrbox")
                .put("attr", "data-x").put("expect", "y"));
        pausas.clear();
        JsonNode resumo = executar(acoes);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        String motivo = resumo.path("motivo").asText("");
        assertTrue(motivo.contains("(ausente)"), motivo);
        assertFalse(motivo.contains("elemento indisponivel"), motivo);
        assertEquals(List.of(1_000L, 1_000L), pausas,
                "3 tentativas = 2 pausas de 1 s (com <= seriam 3): " + pausas);
    }

    // ---------- verify_absent ----------

    @Test
    @Timeout(60)
    void verifyAbsentEsperaElementoSumirEDevolveTempo() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/some"));
        acoes.add(acao("verify_absent").put("selector", "#some")
                .put("expect", "presente").put("timeoutMs", 5000));
        JsonNode resumo = executar(acoes);
        semAborto(resumo, 2);
        String texto = info(resumo, 1);
        assertTrue(texto.startsWith("ausente (nenhum com 'presente') em "), texto);
        long ms = Long.parseLong(texto.replaceAll("^.* em (\\d+) ms$", "$1"));
        assertTrue(ms >= 0 && ms < 5000, texto);
    }

    @Test
    @Timeout(60)
    void verifyAbsentDistingueErroDeAusenciaEAvisaTimeoutComExpect() throws Exception {
        tools.call("social_browse", Json.obj().put("network", "bluesky")
                .put("url", "https://bsky.app/hub"));

        var invalido = Json.M.createArrayNode();
        invalido.add(acao("verify_absent").put("selector", "css:[[").put("timeoutMs", 1000));
        JsonNode erro = executar(invalido);
        assertTrue(erro.path("abortado").asBoolean(false), erro.toString());
        assertTrue(erro.path("motivo").asText("").contains("nao conseguiu avaliar"),
                erro.path("motivo").asText(""));

        var presente = Json.M.createArrayNode();
        presente.add(acao("verify_absent").put("selector", "#msg")
                .put("expect", "Mensagem").put("timeoutMs", 200));
        long inicio = System.currentTimeMillis();
        JsonNode falhou = executar(presente);
        long gasto = System.currentTimeMillis() - inicio;
        assertTrue(falhou.path("abortado").asBoolean(false), falhou.toString());
        String motivo = falhou.path("motivo").asText("");
        assertTrue(motivo.contains("ainda presente"), motivo);
        assertTrue(motivo.contains("com 'Mensagem'"), motivo);
        assertTrue(gasto >= 350, "removeu o poll de 500 ms: " + gasto + " ms");
    }

    // ---------- capture_ids / verify_new_id ----------

    @Test
    @Timeout(60)
    void captureIdsEVerifyNewIdSemOutrosNovosNemTextoDivergente() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/capA"));
        acoes.add(acao("capture_ids").put("name", "lista").put("selector", "#lista li")
                .put("attr", "data-id").put("stableMs", 1000).put("waitMs", 2000));
        acoes.add(irPara("/capB"));
        acoes.add(acao("verify_new_id").put("name", "lista").put("selector", "#lista li")
                .put("attr", "data-id").put("expect", "texto completo")
                .put("textoCompleto", "texto completo").put("timeoutMs", 2000));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 4);
        assertTrue(info(resumo, 1).startsWith("capturou 1 ids em 'lista'"), info(resumo, 1));
        assertEquals("id novo em 'lista': b", info(resumo, 3));
        JsonNode passo = resumo.path("passos").get(3);
        assertFalse(passo.has("outrosNovos"), passo.toString());
        assertFalse(passo.has("textoDivergente"), passo.toString());
        assertFalse(info(resumo, 3).contains("ATENCAO"), info(resumo, 3));
    }

    @Test
    @Timeout(60)
    void verifyNewIdMarcaTextoDivergenteQuandoItemNaoCobreOTextoDigitado() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/capB"));
        acoes.add(acao("capture_ids").put("name", "lista").put("selector", "#lista li")
                .put("attr", "data-id").put("stableMs", 1000).put("waitMs", 2000));
        acoes.add(irPara("/capC"));
        acoes.add(acao("verify_new_id").put("name", "lista").put("selector", "#lista li")
                .put("attr", "data-id").put("expect", "abc curto")
                .put("textoCompleto", "texto completamente diferente").put("timeoutMs", 2000));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 4);
        JsonNode passo = resumo.path("passos").get(3);
        assertEquals("id novo em 'lista': c (ATENCAO: texto exibido nao cobre o texto digitado inteiro)",
                passo.path("info").asText(""));
        assertTrue(passo.path("textoDivergente").asBoolean(false), passo.toString());
    }

    @Test
    @Timeout(60)
    void verifyNewIdSemCaptureIdsNesteLoteFalhaAntesDoNavegador() throws Exception {
        Method m = Tools.class.getDeclaredMethod("runVerifyNewId",
                io.github.selenium.javaDriverless.JavaDriverless.class,
                JsonNode.class, ObjectNode.class, JsonNode.class, int.class);
        m.setAccessible(true);
        ObjectNode out = Json.obj();
        JsonNode a = Json.obj().put("name", "sem_par");
        InvocationTargetException erro = assertThrows(InvocationTargetException.class,
                () -> m.invoke(tools, null, a, out, null, -1));
        assertTrue(String.valueOf(erro.getCause().getMessage())
                        .contains("capture_ids 'sem_par' nao rodou neste lote"),
                String.valueOf(erro.getCause().getMessage()));
    }

    @Test
    @Timeout(60)
    void captureIdsComResponseProofArmaTraceEVerifyCorrelaciona() throws Exception {
        String texto = "texto aprovado longo";
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/rp"));
        acoes.add(acao("capture_ids").put("name", "post").put("selector", "#lista li")
                .put("attr", "data-id").put("stableMs", 1000).put("waitMs", 2000)
                .put("responseProof", true).put("expect", texto));
        acoes.add(acao("type").put("selector", "#entrada").put("text", texto));
        acoes.add(acao("verify_new_id").put("name", "post").put("selector", "#lista li")
                .put("attr", "data-id").put("expect", texto)
                .put("responseProof", true).put("timeoutMs", 300));

        JsonNode resumo = executar(acoes);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        JsonNode passos = resumo.path("passos");
        assertEquals(4, passos.size(), resumo.toString());
        JsonNode verify = passos.get(3);
        assertEquals("verify_new_id", verify.path("kind").asText());
        String erro = verify.path("erro").asText("");
        assertTrue(erro.contains("verify_new_id falhou"), erro);
        assertTrue(erro.contains("| trace:"), erro);
    }

    // ---------- wait ----------

    @Test
    @Timeout(60)
    void waitComMsEsperaDeVerdadeEDevolveOMesmoValor() throws Exception {
        tools.call("social_browse", Json.obj().put("network", "bluesky")
                .put("url", "https://bsky.app/hub"));
        var acoes = Json.M.createArrayNode();
        acoes.add(acao("wait").put("ms", 300));
        long inicio = System.currentTimeMillis();
        JsonNode resumo = executar(acoes);
        long gasto = System.currentTimeMillis() - inicio;
        semAborto(resumo, 1);
        assertEquals("esperou 300 ms", info(resumo, 0));
        assertTrue(gasto >= 250, "wait nao dormiu: " + gasto + " ms");
    }

    @Test
    @Timeout(60)
    void waitComSelectorSoSaiQuandoOTextoDoElementoAparece() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/cresce"));
        acoes.add(acao("wait").put("selector", "#alvo").put("expect", "pronto agora")
                .put("timeoutMs", 3000));
        JsonNode resumo = executar(acoes);
        semAborto(resumo, 2);
        String texto = info(resumo, 1);
        assertTrue(texto.startsWith("texto do elemento apareceu em "), texto);
        assertTrue(msDoInfo(texto) >= 700, texto);
    }

    @Test
    @Timeout(60)
    void waitFalhaDizONomeDoElementoQuandoHaSelector() throws Exception {
        tools.call("social_browse", Json.obj().put("network", "bluesky")
                .put("url", "https://bsky.app/hub"));
        var acoes = Json.M.createArrayNode();
        acoes.add(acao("wait").put("selector", "#msg").put("expect", "x").put("timeoutMs", 300));
        JsonNode resumo = executar(acoes);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(resumo.path("motivo").asText("").contains(" em '#msg'"),
                resumo.path("motivo").asText(""));
    }

    // ---------- sendCombo / dispatchKey ----------

    @Test
    @Timeout(60)
    void sendComboMantemModificadoresENaoDeixaTeclaPresa() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/hub"));
        acoes.add(acao("key").put("text", "ctrl+a"));
        acoes.add(acao("key").put("text", "Enter"));
        acoes.add(acao("extract").put("what", "text").put("match", "D:Control:true"));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 4);
        JsonNode extraido = resumo.path("passos").get(3).path("extraido");
        assertTrue(extraido.isArray() && extraido.size() == 1, extraido.toString());
        String log = extraido.get(0).asText();
        assertTrue(log.contains("D:Control:true"), log);
        assertTrue(log.contains("D:a:true"), log);
        assertTrue(log.contains("U:a:true"), log);
        assertTrue(log.contains("U:Control:false"), log);
        assertTrue(log.contains("I:\"\\n\""), log);
    }

    // ---------- presente / el / shadowEl ----------

    @Test
    @Timeout(60)
    void clickIfPresentEsperaControleAparecerESoPulaAusenciaComprovada() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/botoes"));
        acoes.add(acao("click").put("selector", "#late").put("ifPresent", true).put("clickMode", "dom"));
        acoes.add(acao("click").put("selector", "#nope").put("ifPresent", true).put("clickMode", "dom"));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 3);
        assertTrue(info(resumo, 1).startsWith("clicou"), info(resumo, 1));
        assertEquals("ausente (ifPresent): nada clicado", info(resumo, 2));
    }

    @Test
    @Timeout(60)
    void shadowElRepeteAteAcharSemDeixarDeDormirEntreTentativas() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/shadow"));
        acoes.add(acao("click").put("selector", "css:#host >>> #msg").put("clickMode", "dom"));

        JsonNode resumo = executar(acoes);
        semAborto(resumo, 2);
        assertTrue(info(resumo, 1).startsWith("clicou"), info(resumo, 1));
        assertTrue(resumo.path("duracaoMs").asLong(0) >= 3300,
                "shadowEl sem sleep nao espera as tentativas: " + resumo.path("duracaoMs").asLong(0));
    }

    // ---------- verify_text exact sem selector (draft nao permite; lote aprovado direto) ----------

    @Test
    @Timeout(60)
    void verifyTextExactSemSelectorRecusaAntesDeLerAPagina() throws Exception {
        var acoes = Json.M.createArrayNode();
        acoes.add(irPara("/hub"));
        acoes.add(acao("verify_text").put("selector", "").put("expect", "Mensagem Principal")
                .put("exact", true));
        JsonNode resumo = executarCru(acoes);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(resumo.path("motivo").asText("").contains("verify_text exact exige selector"),
                resumo.path("motivo").asText(""));
    }
}
