package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia Chrome C de Tools: executeBatch/executeLocked/journalAction/runStep/runSetFile/runRequest.
 *
 * <p>Um unico ChromeFalso (Chrome real headless servindo https://bsky.app/ por HTTPS local) e um
 * unico Store/Tools para a classe inteira. Cada teste usa um lote aprovado (forcar=true) e le o
 * resumo/arquivo executado. Config base deixa passo publico rapido (stepDelayMs 0, sem espacamento
 * e cota alta) para caber no prazo da classe. O teste de closeAfterBatch roda por ultimo porque
 * fecha o Chrome compartilhado.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolsChromeLoteTest {

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
            <input id="cheio" value="abc">
            <div id="edit" contenteditable="true">abc</div>
            </body></html>
            """;

    private static final String LISTA = """
            <html><body>
            <ul id="lista"><li data-id="1">um</li></ul>
            <button id="add" onclick="var li=document.createElement('li');li.setAttribute('data-id','2');li.textContent='dois';document.getElementById('lista').appendChild(li);">add</button>
            <button id="add2" onclick="var l=document.getElementById('lista');[['2','dois'],['3','tres']].forEach(function(p){var li=document.createElement('li');li.setAttribute('data-id',p[0]);li.textContent=p[1];l.appendChild(li);});">add2</button>
            </body></html>
            """;

    private static final String REQ = """
            <html><body><p>pagina limpa</p></body></html>
            """;

    private static final String REDIR = """
            <html><head><meta http-equiv="refresh" content="1;url=https://evil.example/"></head>
            <body>redirecionando</body></html>
            """;

    private static final String OVERLAY = """
            <html><body>
            <div id="x" data-k="v"></div>
            <button id="alvo" style="position:absolute;left:100px;top:100px;width:120px;height:40px">ok</button>
            <div id="overlay" style="position:fixed;left:0;top:0;width:100%;height:100%;background:rgba(0,0,0,0.01)"></div>
            </body></html>
            """;

    private static final String UPLOAD = """
            <html><body><input type="file" id="file"><button id="enviar">enviar</button></body></html>
            """;

    private static final String SHADOW_UPLOAD = """
            <html><body><div id="host"></div><button id="enviar">enviar</button>
            <script>
            const root = document.querySelector('#host').attachShadow({mode: 'open'});
            root.innerHTML = '<input type="file" id="file">';
            </script></body></html>
            """;

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;
    static Tools tools;

    @BeforeAll
    static void abrirFixture() throws Exception {
        store = new Store(tmp.resolve("home"));
        Files.createDirectories(tmp.resolve("upload"));
        Files.write(tmp.resolve("upload/a.png"), new byte[10]);
        Files.write(tmp.resolve("upload/b.png"), new byte[20]);
        configurarPadrao();
        cf = ChromeFalso.abrir(tmp, store);
        cf.pagina("/base", BASE);
        cf.pagina("/form", FORM);
        cf.pagina("/lista", LISTA);
        cf.pagina("/req", REQ);
        cf.pagina("/redir", REDIR);
        cf.pagina("/overlay", OVERLAY);
        cf.pagina("/upload", UPLOAD);
        cf.pagina("/shadow-upload", SHADOW_UPLOAD);
        cf.pagina("/item/2", "<html><body><h1>post</h1></body></html>");
        cf.resposta("/api/ok", 200, "application/json; charset=utf-8", "ok");
        cf.resposta("/api/limit", 200, "application/json; charset=utf-8", "rate limit");
        cf.resposta("/api/erro", 500, "application/json; charset=utf-8", "rate limit");
        tools = new Tools(store);
    }

    @AfterAll
    static void fecharFixture() {
        if (tools != null) tools.shutdown();
        if (cf != null) cf.close();
    }

    @BeforeEach
    void preparar() throws Exception {
        configurarPadrao();
        store.setState("bluesky", "healthy", "teste", "teste");
        cf.limparPedidos();
        limparExecutados();
    }

    /** Config base: sem atraso entre passos, sem espacamento/cota curta, uploadRoots temporario. */
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
        ArrayNode roots = defs.putArray("uploadRoots");
        roots.add(tmp.resolve("upload").toString());
        ObjectNode bsky = (ObjectNode) raiz.path("networks").path("bluesky");
        bsky.put("startUrl", "https://bsky.app/");
        bsky.put("attachExistingChrome", true);
        bsky.put("closeAfterBatch", false);
        bsky.put("browserIdleMinutes", 0);
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
        String id = bs.draft("teste chrome lote", Json.parse(acoesJson)).path("id").asText();
        bs.approve(id, "humano", "nota de teste", List.of(), true, ensaio);
        return id;
    }

    private JsonNode executar(String id) throws Exception {
        return Json.parse(tools.call("social_execute_batch", Json.obj().put("batchId", id)));
    }

    private static String txt(String s) {
        return Json.write(TextNode.valueOf(s));
    }

    private long contarAcoes(String batchId) {
        return store.tail(500).stream()
                .filter(l -> l.contains("\"type\":\"action\"") && l.contains("\"batchId\":\"" + batchId + "\""))
                .count();
    }

    private boolean journalTem(String type, String batchId) {
        return store.tail(500).stream()
                .filter(l -> l.contains("\"type\":\"" + type + "\""))
                .anyMatch(l -> l.contains("\"batchId\":\"" + batchId + "\""));
    }

    // ---------- executeLocked: passos, numeracao, info e journal ----------

    @Test
    @Order(1)
    @Timeout(60)
    void loteLeituraExecutaTodosOsPassosComInfoNaoVazio() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#naoexiste","ifPresent":true,"description":"aviso"},
                 {"network":"bluesky","kind":"verify_text","expect":"hello","description":"texto"},
                 {"network":"bluesky","kind":"verify_attr","selector":"css:#x","attr":"data-k","expect":"v","description":"attr"},
                 {"network":"bluesky","kind":"verify_absent","selector":"css:.ausente","timeoutMs":1,"description":"ausente"},
                 {"network":"bluesky","kind":"wait","ms":1,"description":"espera"},
                 {"network":"bluesky","kind":"screenshot","description":"tela"},
                 {"network":"bluesky","kind":"extract","what":"text","match":"hello","description":"extrai"},
                 {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.app/api/ok","description":"req"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        assertEquals(9, r.path("passosExecutados").asInt(-1), r.toString());
        JsonNode passos = r.path("passos");
        assertEquals(9, passos.size(), r.toString());
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 1, passos.get(i).path("passo").asInt(-1), "passo " + i + ": " + r);
            assertFalse(passos.get(i).path("info").asText("").isBlank(), "info vazio no passo " + i + ": " + r);
        }
        assertTrue(passos.get(0).path("info").asText().startsWith("navegou para https://bsky.app/base"),
                passos.get(0).path("info").asText());
        assertEquals("ausente (ifPresent): nada clicado", passos.get(1).path("info").asText());
        assertTrue(passos.get(1).path("pulado").asBoolean(false), r.toString());
        assertEquals("texto verificado", passos.get(2).path("info").asText());
        assertEquals("atributo verificado: data-k=v", passos.get(3).path("info").asText());
        assertTrue(passos.get(4).path("info").asText().startsWith("ausente"),
                passos.get(4).path("info").asText());
        assertEquals("esperou 1 ms", passos.get(5).path("info").asText());
        String tela = passos.get(6).path("info").asText();
        assertTrue(tela.startsWith("screenshot salvo em: "), tela);
        assertTrue(Files.exists(Path.of(tela.substring("screenshot salvo em: ".length()))), tela);
        assertTrue(passos.get(7).path("info").asText().startsWith("extraiu "),
                passos.get(7).path("info").asText());
        assertEquals("POST https://bsky.app/api/ok -> 200", passos.get(8).path("info").asText());
        assertEquals("fraca", r.path("conferencia").asText(), r.toString());
        assertTrue(r.path("AVISO").asText("").contains("conferencia e fraca"), r.toString());
        assertEquals(9, contarAcoes(id), "journal deve ter uma action por passo");
    }

    @Test
    @Order(2)
    @Timeout(60)
    void clickIfPresentAusentePulaSemContarComoPublico() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#naoexiste","ifPresent":true,"description":"aviso"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode passo = r.path("passos").get(1);
        assertTrue(passo.path("ok").asBoolean(false), r.toString());
        assertTrue(passo.path("pulado").asBoolean(false), r.toString());
        assertEquals("ausente (ifPresent): nada clicado", passo.path("info").asText());
        assertEquals("nenhum_passo_publico_feito", r.path("conferencia").asText(), r.toString());
        assertFalse(r.has("AVISO"), r.toString());
    }

    @Test
    @Order(3)
    @Timeout(60)
    void clickCobertoRegistraAvisoEConferenciaForteSemAviso() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/overlay","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#alvo","description":"clique coberto"},
                 {"network":"bluesky","kind":"verify_attr","selector":"css:#x","attr":"data-k","expect":"v","description":"prova"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode clique = r.path("passos").get(1);
        assertTrue(clique.path("info").asText().startsWith("clicou (mouse em"), clique.toString());
        assertFalse(clique.path("coberto").asText("").isBlank(), clique.toString());
        assertEquals("forte", r.path("conferencia").asText(), r.toString());
        assertFalse(r.has("AVISO"), r.toString());
    }

    // ---------- runStep: click/type/key ----------

    @Test
    @Order(4)
    @Timeout(60)
    void typeComESemSelectorUsaInsertText() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/form","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#vazio","description":"focar"},
                 {"network":"bluesky","kind":"type","text":"oi","description":"sem selector"},
                 {"network":"bluesky","kind":"type","selector":"css:#vazio2","text":"x","description":"com selector"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode sem = r.path("passos").get(2);
        assertTrue(sem.path("ok").asBoolean(false), r.toString());
        assertTrue(sem.path("info").asText().startsWith("digitou 2 caracteres (insertText"),
                sem.path("info").asText());
        assertEquals("insertText", sem.path("modo").asText());
        JsonNode com = r.path("passos").get(3);
        assertTrue(com.path("ok").asBoolean(false), r.toString());
        assertTrue(com.path("info").asText().startsWith("digitou 1 caracteres (insertText"),
                com.path("info").asText());
    }

    @Test
    @Order(5)
    @Timeout(60)
    void typeEmCampoPreenchidoAbortaAntesDeDigitar() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/form","description":"abrir"},
                 {"network":"bluesky","kind":"type","selector":"css:#cheio","text":"novo","description":"campo cheio"}]
                """, true);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        JsonNode passo = r.path("passos").get(1);
        assertFalse(passo.path("ok").asBoolean(true), r.toString());
        assertTrue(passo.path("erro").asText("").contains("campo ja tinha texto"),
                passo.path("erro").asText());
    }

    @Test
    @Order(6)
    @Timeout(60)
    void teclaComboSelecionaTudoEDepoisDigitaLiteral() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/form","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#edit","description":"focar"},
                 {"network":"bluesky","kind":"key","text":"ctrl+a","description":"selecionar"},
                 {"network":"bluesky","kind":"type","text":"xy","description":"substituir"},
                 {"network":"bluesky","kind":"verify_text","selector":"css:#edit","expect":"xy","exact":true,"description":"valor"},
                 {"network":"bluesky","kind":"key","text":"ab","description":"literal"},
                 {"network":"bluesky","kind":"verify_text","selector":"css:#edit","expect":"xyab","exact":true,"description":"valor final"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode combo = r.path("passos").get(2);
        assertEquals("tecla enviada: Control+a", combo.path("info").asText());
        assertEquals("tecla", combo.path("modo").asText());
        JsonNode digitou = r.path("passos").get(3);
        assertTrue(digitou.path("info").asText().startsWith("digitou 2 caracteres (insertText"),
                digitou.path("info").asText());
        assertTrue(r.path("passos").get(4).path("ok").asBoolean(false), r.toString());
        JsonNode literal = r.path("passos").get(5);
        assertEquals("teclas enviadas (literal, 2 caracteres)", literal.path("info").asText());
        assertEquals("literal", literal.path("modo").asText());
        assertTrue(r.path("passos").get(6).path("ok").asBoolean(false), r.toString());
    }

    // ---------- executeLocked: verify_new_id, duplicata e texto divergente ----------

    @Test
    @Order(7)
    @Timeout(60)
    void verifyNovoIdAlimentaGotoMarcadorEIdsNovosDoResumo() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"abrir"},
                 {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"ids","stableMs":1000,"waitMs":2000,"description":"capturar"},
                 {"network":"bluesky","kind":"click","selector":"css:#add","description":"adicionar"},
                 {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"ids","timeoutMs":3000,"description":"novo"},
                 {"network":"bluesky","kind":"goto","url":"https://bsky.app/item/[[novo:ids]]","description":"ir ao novo"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode passos = r.path("passos");
        assertTrue(passos.get(1).path("info").asText().startsWith("capturou 1 ids em 'ids'"),
                passos.get(1).path("info").asText());
        assertEquals("2", passos.get(3).path("idNovo").asText(), r.toString());
        assertTrue(passos.get(3).path("info").asText().startsWith("id novo em 'ids': 2"),
                passos.get(3).path("info").asText());
        assertTrue(passos.get(4).path("ok").asBoolean(false), r.toString());
        assertTrue(passos.get(4).path("info").asText().startsWith("navegou para https://bsky.app/item/2"),
                passos.get(4).path("info").asText());
        assertEquals("2", r.path("idsNovos").path("ids").asText(""), r.toString());
        assertEquals("forte", r.path("conferencia").asText(), r.toString());
    }

    @Test
    @Order(8)
    @Timeout(60)
    void textoExibidoDivergenteRebaixaConferenciaParaFraca() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"abrir"},
                 {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"ids","stableMs":1000,"waitMs":2000,"description":"capturar"},
                 {"network":"bluesky","kind":"click","selector":"css:#add","description":"adicionar"},
                 {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"ids","timeoutMs":3000,"textoCompleto":"texto completo aprovado","description":"novo"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode novo = r.path("passos").get(3);
        assertTrue(novo.path("textoDivergente").asBoolean(false), r.toString());
        assertTrue(novo.path("info").asText().contains("texto exibido nao cobre"),
                novo.path("info").asText());
        assertEquals("fraca", r.path("conferencia").asText(), r.toString());
    }

    @Test
    @Order(9)
    @Timeout(60)
    void doisItensNovosMarcamDuplicataSuspeita() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/lista","description":"abrir"},
                 {"network":"bluesky","kind":"capture_ids","selector":"css:li","attr":"data-id","name":"ids","stableMs":1000,"waitMs":2000,"description":"capturar"},
                 {"network":"bluesky","kind":"click","selector":"css:#add2","description":"adicionar dois"},
                 {"network":"bluesky","kind":"verify_new_id","selector":"css:li","attr":"data-id","name":"ids","timeoutMs":3000,"description":"novo"}]
                """, true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        assertEquals(1, r.path("passos").get(3).path("outrosNovos").asInt(-1), r.toString());
        assertEquals("duplicata_suspeita", r.path("conferencia").asText(), r.toString());
        assertTrue(r.path("AVISO").asText("").contains("em dobro"), r.toString());
    }

    // ---------- executeLocked: tripwire da pagina e da resposta ----------

    @Test
    @Order(10)
    @Timeout(60)
    void tripwireNoBodySnippetDoRequestColocaRedeEmCooldown() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/req","description":"abrir"},
                 {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.app/api/limit","description":"req"}]
                """, true);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        assertTrue(r.path("motivo").asText("").contains("padrao de anti-bot detectado apos o passo 2"),
                r.path("motivo").asText());
        assertTrue(r.path("passos").get(1).path("tripwire").asBoolean(false), r.toString());
        assertEquals("cooldown", store.effectiveState("bluesky"));
        assertTrue(store.stateReason("bluesky").contains("apos o passo 2 do lote"), store.stateReason("bluesky"));
        assertTrue(journalTem("tripwire", id), store.tail(50).toString());
    }

    @Test
    @Order(11)
    @Timeout(60)
    void requestFalhoContaComoTentativaPublicaETripwireDaResposta() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/req","description":"abrir"},
                 {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.app/api/erro","description":"req"}]
                """, true);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        assertTrue(r.path("motivo").asText("").contains("passo 2 falhou"), r.path("motivo").asText());
        JsonNode req = r.path("passos").get(1);
        assertFalse(req.path("ok").asBoolean(true), r.toString());
        assertEquals(500, req.path("status").asInt(-1));
        assertTrue(req.path("tripwire").asBoolean(false), r.toString());
        assertEquals("cooldown", store.effectiveState("bluesky"));
        assertTrue(store.stateReason("bluesky").contains("na resposta do passo 2"),
                store.stateReason("bluesky"));
        assertTrue(journalTem("tripwire", id), store.tail(50).toString());
        assertTrue(store.tail(500).stream()
                .filter(l -> l.contains("\"type\":\"action\"") && l.contains("\"batchId\":\"" + id + "\""))
                .anyMatch(l -> l.contains("\"ok\":false")), store.tail(50).toString());
        assertEquals("abortado_apos_publico", r.path("conferencia").asText(), r.toString());
    }

    @Test
    @Order(12)
    @Timeout(60)
    void abortoDepoisDeClickClassificaComoAbortadoAposPublico() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"},
                 {"network":"bluesky","kind":"click","selector":"css:#alvo","description":"engajou"},
                 {"network":"bluesky","kind":"verify_text","selector":"css:#x","expect":"nao","description":"prova"}]
                """, true);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        assertEquals(2, r.path("passosExecutados").asInt(-1), r.toString());
        assertEquals("abortado_apos_publico", r.path("conferencia").asText(), r.toString());
    }

    // ---------- runSetFile ----------

    @Test
    @Order(13)
    @Timeout(60)
    void setFileMultiploSomaBytesENomesDoLoteAprovado() throws Exception {
        String a = tmp.resolve("upload/a.png").toString();
        String b = tmp.resolve("upload/b.png").toString();
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/upload","description":"abrir"},
                 {"network":"bluesky","kind":"set_file","selector":"css:#file","paths":[%s,%s],"description":"anexar"},
                 {"network":"bluesky","kind":"click","selector":"css:#enviar","description":"enviar"}]
                """.formatted(txt(a), txt(b)), true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode passo = r.path("passos").get(1);
        assertEquals("arquivo definido: a.png, b.png (30)", passo.path("info").asText());
        assertEquals(30, passo.path("bytes").asLong(-1));
        assertEquals(2, passo.path("arquivos").asInt(-1));
        assertEquals("a.png, b.png", passo.path("arquivo").asText());
    }

    @Test
    @Order(22)
    @Timeout(60)
    void setFileEmShadowDomAbertoUsaBackendNodeId() throws Exception {
        String path = tmp.resolve("upload/a.png").toString();
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/shadow-upload","description":"abrir"},
                 {"network":"bluesky","kind":"set_file","selector":"css:#host >>> input[type=file]","path":%s,"description":"anexar"},
                 {"network":"bluesky","kind":"click","selector":"css:#enviar","description":"enviar"}]
                """.formatted(txt(path)), true);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        assertEquals("arquivo definido: a.png (10)", r.path("passos").get(1).path("info").asText());
    }

    // ---------- runStep: navegacao ----------

    @Test
    @Order(14)
    @Timeout(60)
    void gotoRevalidaDestinoPermitidoNaExecucao() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"}]
                """, false);
        try {
            ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
            ((ObjectNode) raiz.path("networks").path("bluesky")).put("startUrl", "https://exemplo.invalid/");
            Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
            JsonNode r = executar(id);
            assertTrue(r.path("abortado").asBoolean(false), r.toString());
            assertTrue(r.path("passos").get(0).path("erro").asText("")
                            .contains("navegacao bloqueada: so https para host da rede"),
                    r.toString());
        } finally {
            configurarPadrao();
        }
    }

    @Test
    @Order(15)
    @Timeout(60)
    void redirectParaForaDaRedeFalhaNoConferirDestino() throws Exception {
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/redir","description":"abrir"}]
                """, false);
        JsonNode r = executar(id);
        assertTrue(r.path("abortado").asBoolean(false), r.toString());
        assertTrue(r.path("passos").get(0).path("erro").asText("")
                        .contains("navegacao bloqueada antes da requisicao"),
                r.toString());
    }

    // ---------- runStep: overload de acao readOnly ----------

    @Test
    @Order(16)
    @Timeout(60)
    void acaoReadOnlyDevolveInfoDoPasso() throws Exception {
        Path receitas = store.home.resolve("recipes");
        Files.createDirectories(receitas);
        Files.writeString(receitas.resolve("bluesky.json"), """
                {"network":"bluesky","actions":{"get_profile":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/base"},
                  {"kind":"verify_text","expect":"hello"}
                ]}}}
                """, StandardCharsets.UTF_8);
        JsonNode out = Json.parse(tools.call("social_action",
                Json.obj().put("network", "bluesky").put("action", "get_profile")));
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertTrue(out.path("passos").get(0).path("info").asText().startsWith("navegou para https://bsky.app/base"),
                out.toString());
        assertEquals("texto verificado", out.path("passos").get(1).path("info").asText());
    }

    // ---------- sleepJitter entre passos ----------

    @Test
    @Order(17)
    @Timeout(60)
    void sleepJitterAconteceSoEntrePassos() throws Exception {
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ArrayNode atraso = ((ObjectNode) raiz.path("defaults")).putArray("stepDelayMs");
        atraso.add(300);
        atraso.add(300);
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
        String id = preparar("""
                [{"network":"bluesky","kind":"wait","ms":1,"description":"um"},
                 {"network":"bluesky","kind":"wait","ms":1,"description":"dois"},
                 {"network":"bluesky","kind":"wait","ms":1,"description":"tres"}]
                """, false);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        long duracao = r.path("duracaoMs").asLong(-1);
        // atual: 2 intervalos (600 ms); removido/negado: 0 ou 1 intervalo; <=: 3 intervalos
        assertTrue(duracao >= 500, "sleepJitter entre os 3 passos ausente: " + r);
        assertTrue(duracao <= 850, "sleepJitter tambem apos o ultimo passo: " + r);
    }

    // ---------- executeLocked: erro interno e numeracao ----------

    /**
     * Store de teste que falha na 3a consulta de uso: a 1a e a 2a sao o fail-fast do lote
     * (cota antes e depois do claim) e a 3a e a Guard.check dentro do laco, ja com o lote
     * reservado. Sem navegador: o erro acontece antes de runStep.
     */
    static final class StoreFalhaUso extends Store {
        private int usos;

        StoreFalhaUso(Path home) {
            super(home);
        }

        @Override
        public Store.Usage usageFor(String network, String excludeBatchId) {
            if (++usos >= 3) throw new IllegalStateException("falha injetada em usageFor");
            return super.usageFor(network, excludeBatchId);
        }
    }

    @Test
    @Order(18)
    @Timeout(60)
    void erroInternoUsaNumeroDoPassoCorrente() throws Exception {
        StoreFalhaUso falha = new StoreFalhaUso(store.home);
        Tools isolado = new Tools(falha);
        try {
            BatchStore bs = new BatchStore(falha);
            String id = bs.draft("teste chrome lote", Json.parse("""
                    [{"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.app/api/ok","description":"req"}]
                    """)).path("id").asText();
            bs.approve(id, "humano", "nota de teste", List.of(), true, true);
            JsonNode r = Json.parse(isolado.call("social_execute_batch", Json.obj().put("batchId", id)));
            assertTrue(r.path("abortado").asBoolean(false), r.toString());
            assertTrue(r.path("motivo").asText("").contains("erro interno no passo 1"), r.toString());
            Path executado = store.batchesDir().resolve(id + ".executed.json");
            assertTrue(Files.exists(executado), r.toString());
        } finally {
            isolado.shutdown();
        }
    }

    // ---------- executeBatch: closeAfterBatch (por ultimo: fecha o Chrome compartilhado) ----------

    @Test
    @Order(19)
    @Timeout(60)
    void closeAfterBatchFechaOChromeDaRede() throws Exception {
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ((ObjectNode) raiz.path("networks").path("bluesky")).put("closeAfterBatch", true);
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
        String id = preparar("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/base","description":"abrir"}]
                """, false);
        JsonNode r = executar(id);
        assertFalse(r.path("abortado").asBoolean(true), r.toString());
        JsonNode status = Json.parse(tools.call("social_status", Json.obj()));
        assertEquals(0, status.path("chromesAbertos").asInt(-1), status.toString());
    }

    // ---------- runRequest direto: status negativo ----------

    @Test
    @Order(20)
    @Timeout(60)
    void requestComStatusNegativoFalhaComoNaoEnviado() {
        ObjectNode out = Json.obj();
        java.util.function.Function<String, Object> exec = js -> "{\"s\":-1,\"b\":\"falhou\"}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(exec, Json.obj().put("url", "https://bsky.app/api")
                        .put("method", "POST"), out));
        assertTrue(e.getMessage().contains("nao enviado"), e.getMessage());
        assertEquals(-1, out.path("status").asInt(-999));
        assertFalse(out.path("incerto").asBoolean(false), out.toString());
    }
}
