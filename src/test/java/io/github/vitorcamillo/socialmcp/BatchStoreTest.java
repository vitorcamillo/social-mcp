package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchStoreTest {

    @TempDir
    Path tmp;

    /** Store de teste com gate que passa (exit 0): approve exige gate configurado. */
    private Store store() {
        return storeGate("exit 0");
    }

    private Store storeGate(String script) {
        try {
            return TextGateTest.storeComGate(tmp, script, 30);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode acoes() {
        return Json.parse("""
                [
                  {"network":"bluesky","kind":"goto","url":"https://bsky.app/profile/example_account.bsky.social"},
                  {"network":"bluesky","kind":"verify_text","expect":"ReservaJá"}
                ]
                """);
    }

    @Test
    void loteNaoAprovadoNaoExecuta() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode b = bs.draft("teste", acoes());
        String id = b.get("id").asText();
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("RASCUNHO"));
    }

    @Test
    void cicloCompletoDraftApproveExecuted() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();

        bs.approve(id, "orquestrador", "lote revisado");
        JsonNode aprovado = bs.requireApproved(id);
        assertEquals("approved", aprovado.get("status").asText());
        assertEquals("orquestrador", aprovado.get("aprovadoPor").asText());
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".approved.json")));

        bs.markExecuted(id, Json.obj().put("passosExecutados", 2));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".executed.json")));
        JsonNode executado = Json.parse(Files.readString(s.batchesDir().resolve(id + ".executed.json")));
        assertEquals("executed", executado.get("status").asText());
    }

    @Test
    void approvePorHumanoRegistraHumano() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", null);
        assertEquals("humano", bs.requireApproved(id).get("aprovadoPor").asText());
    }

    @Test
    void rejeicaoRemoveRascunho() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id, "texto impróprio");
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".rejected.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".draft.json")));
    }

    @Test
    void acaoInvalidaRejeitadaNoDraft() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode ruim = Json.parse("""
                [{"network":"bluesky","kind":"click"}]
                """);
        assertThrows(IllegalArgumentException.class, () -> bs.draft("click sem selector", ruim));
        JsonNode redeRuim = Json.parse("""
                [{"network":"","kind":"goto","url":"https://x.com"}]
                """);
        assertThrows(IllegalArgumentException.class, () -> bs.draft("sem rede", redeRuim));
    }

    @Test
    void loteNaoPodeSerAprovadoDuasVezes() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();

        bs.approve(id, "orquestrador", null);
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".draft.json")),
                "draft deve ser apagado no approve; sem ele o lote nao reaprova");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "orquestrador", null));
        assertTrue(e.getMessage().contains("ja aprovado ou executado"));
    }

    @Test
    void loteExecutadoNaoReaprovaNemReexecuta() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();

        bs.approve(id, "humano", null);
        bs.markExecuted(id, Json.obj().put("abortado", false));

        IllegalStateException e1 = assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        assertTrue(e1.getMessage().contains("ja aprovado ou executado"));
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e2.getMessage().contains("ja executado"));
    }

    @Test
    void validateBatchRulesRejeitaLegadoMisto() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode misto = Json.parse("""
                [
                  {"network":"bluesky","kind":"goto","url":"https://bsky.app"},
                  {"network":"mastodon","kind":"goto","url":"https://mastodon.social"}
                ]
                """);
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> bs.validateBatchRules(misto));
        assertTrue(e1.getMessage().contains("uma rede"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> bs.validateBatchRules(acoesTipo("bluesky", 6)));
        assertTrue(e2.getMessage().contains("passos publicos"));
        String net = bs.validateBatchRules(Json.parse("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app"}]
                """));
        assertEquals("bluesky", net);
    }

    private static JsonNode acoesTipo(String rede, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"network\":\"").append(rede).append("\",\"kind\":\"type\",\"text\":\"passo ").append(i).append("\"}");
        }
        return Json.parse(sb.append("]").toString());
    }

    @Test
    void loteMisturandoRedesRejeitado() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode misto = Json.parse("""
                [
                  {"network":"bluesky","kind":"goto","url":"https://bsky.app"},
                  {"network":"mastodon","kind":"goto","url":"https://mastodon.social"}
                ]
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("redes mistas", misto));
        assertTrue(e.getMessage().contains("uma rede"));
    }

    @Test
    void lotePassaDoLimiteDePassosPublicosRejeitado() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("acima do limite", acoesTipo("bluesky", 6)));
        assertTrue(e.getMessage().contains("passos publicos"));
        // no limite (5, default) o draft e aceito
        assertDoesNotThrow(() -> bs.draft("no limite", acoesTipo("bluesky", 5)));
    }

    @Test
    void draftGravaRedeNoLote() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode b = bs.draft("so bluesky", Json.parse("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app"}]
                """));
        assertEquals("bluesky", b.path("network").asText());
    }

    // ---------- gate de texto na aprovacao ----------

    @Test
    void approveComGateFailLancaEMantemRascunho() throws Exception {
        Store s = storeGate("echo 'claim proibido'; echo '{\"resultado\":\"FAIL\"}'; exit 2");
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "orquestrador", "nota"));
        assertTrue(e.getMessage().contains("gate de texto reprovou"));
        assertTrue(e.getMessage().contains("claim proibido"));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
    }

    @Test
    void approveRejeitaGateComJsonPassMasExitDeFalha() throws Exception {
        Store s = storeGate("echo '{\"resultado\":\"PASS\"}'; exit 2");
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        assertThrows(IllegalStateException.class, () -> bs.approve(id, "orquestrador", "nota"));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")));
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
    }

    @Test
    void approveSemGateConfiguradoLanca() throws Exception {
        Store s = storeGate(null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", "nota"));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")));
    }

    @Test
    void approveComGatePassGravaHashEResultado() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        JsonNode draft = bs.draft("teste", acoes());
        String id = draft.get("id").asText();
        assertEquals("PASS", bs.approve(id, "orquestrador", "ok", List.of(tmp.resolve("ficha.md")), false));
        JsonNode a = bs.requireApproved(id);
        assertEquals(TextGate.hashActions(draft.get("actions")), a.get("actionsHash").asText());
        assertEquals("PASS", a.path("gate").path("resultado").asText());
        assertFalse(a.path("gate").path("forcado").asBoolean(true));
        assertEquals(1, a.path("gate").path("fichas").size());
        assertTrue(String.join("\n", s.tail(5)).contains("\"gate\":\"PASS\""));
    }

    @Test
    void gateWarnExigeNota() throws Exception {
        Store s = storeGate("exit 3");
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "orquestrador", ""));
        assertTrue(e.getMessage().contains("gate com avisos"));
        assertEquals("WARN", bs.approve(id, "orquestrador", "aviso revisado: dado conferido"));
        assertEquals("WARN", bs.requireApproved(id).path("gate").path("resultado").asText());
    }

    @Test
    void forcarSoComHumano() throws Exception {
        Store s = storeGate("exit 2");
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.approve(id, "orquestrador", "nota", List.of(), true));
        assertTrue(e.getMessage().contains("--forcar so vale para aprovacao humana"));
        bs.approve(id, "humano", "decisao do dono", List.of(), true);
        JsonNode a = bs.requireApproved(id);
        assertTrue(a.path("gate").path("forcado").asBoolean());
        assertEquals("FORCADO", a.path("gate").path("resultado").asText());
    }

    @Test
    void requireApprovedRecusaLoteAlteradoDepoisDaAprovacao() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "orquestrador", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        ((ObjectNode) b.get("actions").get(1)).put("expect", "example_account!");
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("alterado depois da aprovacao"));
        assertTrue(String.join("\n", s.tail(3)).contains("batch_execute_refused"));
    }

    @Test
    void requireApprovedRecusaAprovacaoVencida() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "orquestrador", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        b.put("approvedAt", System.currentTimeMillis() - 25L * 3_600_000L);
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("venceu (24h)"));
    }

    @Test
    void requireApprovedRecusaAprovacaoSemHash() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "orquestrador", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        b.remove("actionsHash");
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("sem gate/hash"));
    }

    @Test
    void rejectRevogaLoteAprovado() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "orquestrador", "ok");
        bs.reject(id, "mudou o contexto");
        assertFalse(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
        Path rej = s.batchesDir().resolve(id + ".rejected.json");
        assertTrue(Files.exists(rej));
        JsonNode r = Json.parse(Files.readString(rej));
        assertEquals("rejected", r.get("status").asText());
        assertTrue(r.has("revogadoEm"));
        assertTrue(String.join("\n", s.tail(2)).contains("batch_revoked"));
        assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
    }

    private static String req(String extra) {
        return "[{\"network\":\"pinterest\",\"kind\":\"request\"" + extra + "}]";
    }

    @Test
    void requestValidoAceitoNoDraft() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        assertDoesNotThrow(() -> bs.draft("delete pin", Json.parse(req(
                ",\"method\":\"POST\",\"url\":\"https://br.pinterest.com/resource/PinResource/delete/\","
                + "\"body\":\"a=1\",\"expectStatus\":[200],\"csrf\":{\"header\":\"x-csrftoken\",\"cookie\":\"csrftoken\"}"))));
        // subdominio do mesmo dominio da rede
        assertDoesNotThrow(() -> bs.draft("api", Json.parse(req(
                ",\"method\":\"DELETE\",\"url\":\"https://api.pinterest.com/v5/pins/1\""))));
    }

    @Test
    void requestInvalidoRecusadoNoDraft() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String url = ",\"url\":\"https://br.pinterest.com/resource/x/\"";
        // host de outra rede
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("host",
                Json.parse(req(",\"method\":\"POST\",\"url\":\"https://x.com/i/api/graphql\""))));
        assertTrue(e.getMessage().contains("nao pertence"));
        // dominio que so termina igual (evilpinterest.com)
        assertThrows(IllegalArgumentException.class, () -> bs.draft("host2",
                Json.parse(req(",\"method\":\"POST\",\"url\":\"https://evilpinterest.com/a\""))));
        // method fora da lista e GET em lote
        e = assertThrows(IllegalArgumentException.class, () -> bs.draft("m", Json.parse(req(",\"method\":\"TRACE\"" + url))));
        assertTrue(e.getMessage().contains("method"));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("get", Json.parse(req(",\"method\":\"GET\"" + url))));
        // http sem tls
        assertThrows(IllegalArgumentException.class, () -> bs.draft("http",
                Json.parse(req(",\"method\":\"POST\",\"url\":\"http://br.pinterest.com/a\""))));
        // body acima de 16 KiB
        String grande = "a".repeat(16 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> bs.draft("body",
                Json.parse(req(",\"method\":\"POST\"" + url + ",\"body\":\"" + grande + "\""))));
        // header cookie proibido
        assertThrows(IllegalArgumentException.class, () -> bs.draft("cookie",
                Json.parse(req(",\"method\":\"POST\"" + url + ",\"headers\":{\"Cookie\":\"a=b\"}"))));
        // csrf incompleto
        assertThrows(IllegalArgumentException.class, () -> bs.draft("csrf",
                Json.parse(req(",\"method\":\"POST\"" + url + ",\"csrf\":{\"header\":\"x\"}"))));
        // extract com what invalido
        assertThrows(IllegalArgumentException.class, () -> bs.draft("extract",
                Json.parse("[{\"network\":\"pinterest\",\"kind\":\"extract\",\"what\":\"html\"}]")));
    }

    @Test
    void requestContaComoPassoPublico() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"network\":\"pinterest\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://br.pinterest.com/r/\"}");
            sb.append(",{\"network\":\"pinterest\",\"kind\":\"extract\",\"what\":\"links\",\"match\":\"/pin/\"}");
        }
        sb.append(']');
        JsonNode seis = Json.parse(sb.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validateBatchRules(seis));
        assertTrue(e.getMessage().contains("passos publicos: 6"));
    }

    private static String reqDe(String net, String url, String extra) {
        return "[{\"network\":\"" + net + "\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"" + url + "\"" + extra + "}]";
    }

    @Test
    void authValidadoPorFromNoDraft() {
        BatchStore bs = new BatchStore(store());
        String u = "https://mastodon.social/api/v1/statuses";
        // validos: um por from
        for (String au : List.of(
                "{\"header\":\"authorization\",\"prefix\":\"Bearer \",\"from\":\"element-json\",\"selector\":\"#initial-state\",\"path\":\"meta.access_token\"}",
                "{\"header\":\"authorization\",\"prefix\":\"Bearer \",\"from\":\"localStorage\",\"key\":\"BSKY_STORAGE\",\"path\":\"session.currentAccount.accessJwt\"}",
                "{\"header\":\"authorization\",\"from\":\"sessionStorage\",\"key\":\"tk\"}",
                "{\"header\":\"authorization\",\"prefix\":\"Bearer \",\"from\":\"cookie\",\"key\":\"token\"}")) {
            assertDoesNotThrow(() -> bs.draft("auth", Json.parse(reqDe("mastodon", u, ",\"auth\":" + au))), au);
        }
        // invalidos
        for (String au : List.of(
                "\"Bearer x\"",
                "{\"from\":\"cookie\",\"key\":\"token\"}",
                "{\"header\":\"authorization\",\"from\":\"window\",\"key\":\"x\"}",
                "{\"header\":\"authorization\",\"from\":\"cookie\"}",
                "{\"header\":\"authorization\",\"from\":\"localStorage\"}",
                "{\"header\":\"authorization\",\"from\":\"sessionStorage\",\"path\":\"a.b\"}",
                "{\"header\":\"authorization\",\"from\":\"element-json\",\"path\":\"meta.access_token\"}",
                "{\"header\":\"authorization\",\"from\":\"element-json\",\"selector\":\"#initial-state\"}",
                "{\"header\":\"authorization\",\"from\":\"localStorage\",\"key\":\"k\",\"path\":\"a..b\"}",
                "{\"header\":\"authorization\",\"from\":\"cookie\",\"key\":\"t\",\"prefix\":\"Bearer\\n\"}",
                "{\"header\":\"cookie\",\"from\":\"cookie\",\"key\":\"t\"}",
                "{\"header\":\"x y\",\"from\":\"cookie\",\"key\":\"t\"}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> bs.draft("auth", Json.parse(reqDe("mastodon", u, ",\"auth\":" + au))), au);
        }
        // header do auth nao pode repetir header literal nem o do csrf
        String au = ",\"auth\":{\"header\":\"Authorization\",\"from\":\"cookie\",\"key\":\"t\"}";
        assertThrows(IllegalArgumentException.class, () -> bs.draft("dup",
                Json.parse(reqDe("mastodon", u, au + ",\"headers\":{\"authorization\":\"Bearer fixo\"}"))));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("dup2",
                Json.parse(reqDe("mastodon", u, au + ",\"csrf\":{\"header\":\"authorization\",\"cookie\":\"c\"}"))));
        // Bearer fixo literal (X) continua aceito em headers
        assertDoesNotThrow(() -> bs.draft("x", Json.parse(reqDe("x", "https://x.com/i/api/graphql/abc/CreateTweet",
                ",\"headers\":{\"authorization\":\"Bearer AAAA\"},\"csrf\":{\"header\":\"x-csrf-token\",\"cookie\":\"ct0\"}"))));
    }

    @Test
    void requestHostsComCuringaDeSufixo() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        // padroes do DEFAULT_CONFIG
        assertDoesNotThrow(() -> bs.draft("pds", Json.parse(reqDe("bluesky",
                "https://morel.us-east.host.bsky.network/xrpc/com.atproto.repo.createRecord", ""))));
        assertDoesNotThrow(() -> bs.draft("ent", Json.parse(reqDe("bluesky", "https://bsky.social/xrpc/x", ""))));
        assertDoesNotThrow(() -> bs.draft("tw", Json.parse(reqDe("x", "https://twitter.com/i/api/1.1/x", ""))));
        assertDoesNotThrow(() -> bs.draft("ig", Json.parse(reqDe("instagram", "https://i.instagram.com/api/v1/x", ""))));
        // curinga nao casa o proprio sufixo nem dominio que so termina igual
        assertThrows(IllegalArgumentException.class, () -> bs.draft("raiz",
                Json.parse(reqDe("bluesky", "https://host.bsky.network/xrpc/x", ""))));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("evil",
                Json.parse(reqDe("bluesky", "https://evilhost.bsky.network/xrpc/x", ""))));
        // host extra de uma rede nao vale para outra
        assertThrows(IllegalArgumentException.class, () -> bs.draft("cruzado",
                Json.parse(reqDe("mastodon", "https://bsky.social/xrpc/x", ""))));
        assertTrue(BatchStore.hostCasa("a.b.host.bsky.network", "*.host.bsky.network"));
        assertFalse(BatchStore.hostCasa("host.bsky.network", "*.host.bsky.network"));
        assertFalse(BatchStore.hostCasa("bsky.social", "*"));
        assertFalse(BatchStore.hostCasa("bsky.social", "*."));
        assertTrue(BatchStore.hostCasa("API.X.com", "api.x.com"));
    }

    @Test
    void hostDeSufixoPublicoNaoLiberaDominioAlheio() throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.get("networks")).putObject("exampleuk")
                .put("startUrl", "https://social.example.co.uk/");
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        assertEquals("example.co.uk", BatchStore.dominioRegistravel("social.example.co.uk"));
        assertEquals("pinterest.com", BatchStore.dominioRegistravel("br.pinterest.com"));
        assertTrue(BatchStore.hostDaRede(s, "exampleuk", "social.example.co.uk"));
        assertTrue(BatchStore.hostDaRede(s, "exampleuk", "api.example.co.uk"));
        // o achado: derivar por "co.uk" liberava qualquer dominio .co.uk
        assertFalse(BatchStore.hostDaRede(s, "exampleuk", "attacker.co.uk"));
        assertFalse(BatchStore.hostDaRede(s, "exampleuk", "example.co.uk.evil.com"));
        BatchStore bs = new BatchStore(s);
        assertThrows(IllegalArgumentException.class, () -> bs.draft("ataque", Json.parse(
                reqDe("exampleuk", "https://attacker.co.uk/x", ""))));
        assertDoesNotThrow(() -> bs.draft("ok", Json.parse(
                reqDe("exampleuk", "https://social.example.co.uk/x", ""))));
    }

    @Test
    void bodyFieldValidadoNoDraft() {
        BatchStore bs = new BatchStore(store());
        String u = "https://www.reddit.com/svc/shreddit/graphql";
        String json = ",\"headers\":{\"Content-Type\":\"application/json\"}";
        String form = ",\"headers\":{\"content-type\":\"application/x-www-form-urlencoded\"}";
        // csrf so no corpo (sem header), aninhado, com corpo JSON objeto
        assertDoesNotThrow(() -> bs.draft("csrf-json", Json.parse(reqDe("reddit", u, json
                + ",\"body\":\"{\\\"variables\\\":{\\\"input\\\":{}}}\""
                + ",\"csrf\":{\"cookie\":\"csrf_token\",\"bodyField\":\"variables.input.csrf_token\"}"))));
        // header e bodyField juntos; corpo ausente com JSON
        assertDoesNotThrow(() -> bs.draft("csrf-ambos", Json.parse(reqDe("reddit", u, json
                + ",\"csrf\":{\"header\":\"x-csrf\",\"cookie\":\"csrf_token\",\"bodyField\":\"csrf_token\"}"))));
        // form com campo simples
        assertDoesNotThrow(() -> bs.draft("csrf-form", Json.parse(reqDe("reddit", u, form
                + ",\"body\":\"a=1\",\"csrf\":{\"cookie\":\"csrf_token\",\"bodyField\":\"csrf_token\"}"))));
        // auth so no corpo
        assertDoesNotThrow(() -> bs.draft("auth-json", Json.parse(reqDe("reddit", u, json
                + ",\"auth\":{\"from\":\"cookie\",\"key\":\"token_v2\",\"bodyField\":\"variables.token\"}"))));
        // invalidos
        for (String extra : List.of(
                // csrf sem header nem bodyField
                json + ",\"csrf\":{\"cookie\":\"csrf_token\"}",
                // auth sem header nem bodyField
                json + ",\"auth\":{\"from\":\"cookie\",\"key\":\"t\"}",
                // bodyField sem content-type JSON/form
                ",\"body\":\"x\",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"csrf_token\"}",
                ",\"headers\":{\"content-type\":\"text/plain\"},\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"csrf_token\"}",
                // body nao JSON ou JSON nao objeto
                json + ",\"body\":\"a=1\",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"t\"}",
                json + ",\"body\":\"[1]\",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"t\"}",
                // caminho invalido, segmento reservado, pontos em form
                json + ",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"a..b\"}",
                json + ",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"a.__proto__.b\"}",
                form + ",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"a.b\"}",
                json + ",\"csrf\":{\"cookie\":\"c\",\"bodyField\":7}",
                // mesmo bodyField no csrf e no auth
                json + ",\"csrf\":{\"cookie\":\"c\",\"bodyField\":\"t\"},\"auth\":{\"from\":\"cookie\",\"key\":\"k\",\"bodyField\":\"t\"}")) {
            assertThrows(IllegalArgumentException.class, () -> bs.draft("bf", Json.parse(reqDe("reddit", u, extra))), extra);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("csrf",
                Json.parse(reqDe("reddit", u, json + ",\"csrf\":{\"cookie\":\"csrf_token\"}"))));
        assertTrue(e.getMessage().contains("bodyField"), e.getMessage());
    }

    @Test
    void credentialsValidadoNoDraft() {
        BatchStore bs = new BatchStore(store());
        String u = "https://bsky.social/xrpc/com.atproto.repo.createRecord";
        for (String c : List.of("include", "omit", "same-origin")) {
            assertDoesNotThrow(() -> bs.draft("cred", Json.parse(reqDe("bluesky", u, ",\"credentials\":\"" + c + "\""))), c);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("cred", Json.parse(reqDe("bluesky", u, ",\"credentials\":\"all\""))));
        assertTrue(e.getMessage().contains("credentials"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> bs.draft("cred", Json.parse(reqDe("bluesky", u, ",\"credentials\":true"))));
    }

    /** Store com uploadRoots = raiz e teto em MB. */
    private Store storeUpload(Path raiz, Integer maxMb) throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode defs = (ObjectNode) cfg.get("defaults");
        defs.putArray("uploadRoots").add(raiz.toString());
        if (maxMb != null) defs.put("uploadMaxMb", maxMb);
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        return s;
    }

    private static String loteUpload(String path, String depois) {
        return "[{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:input[type=file]\",\"path\":"
                + Json.write(com.fasterxml.jackson.databind.node.TextNode.valueOf(path)) + "}" + depois + "]";
    }

    private static String loteUploads(List<String> paths, String depois) {
        return "[{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:input[type=file]\",\"paths\":"
                + Json.write(Json.M.valueToTree(paths)) + "}" + depois + "]";
    }

    private static final String CLIQUE = ",{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\"}";

    @Test
    void setFileValidoAceitoNoDraft() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.PNG"), new byte[]{1, 2, 3});
        BatchStore bs = new BatchStore(storeUpload(raiz, null));
        assertDoesNotThrow(() -> bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))));
        // key e request tambem contam como envio
        assertDoesNotThrow(() -> bs.draft("up", Json.parse(loteUpload(img.toString(),
                ",{\"network\":\"bluesky\",\"kind\":\"key\",\"text\":\"ctrl+Enter\"}"))));
        assertFalse(Guard.isPublic("set_file"));
    }

    @Test
    void midiaSubstituidaDepoisDaAprovacaoRecusada() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))).get("id").asText();
        bs.approve(id, "humano", null);
        JsonNode aprovado = bs.requireApproved(id);
        assertEquals(1, aprovado.path("midias").size());
        assertEquals(BatchStore.sha256Arquivo(img), aprovado.path("midias").get(0).path("sha256").asText());
        Path copia = BatchStore.copiaAprovada(s, aprovado.path("midias").get(0));
        assertTrue(Files.isRegularFile(copia));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(copia));
        // mesmo caminho, mesmo tamanho, conteudo diferente: o actionsHash nao muda,
        // mas o digest da midia tem que derrubar a aprovacao
        Files.write(img, new byte[]{9, 9, 9});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("alterado depois da aprovacao"), e.getMessage());
        // a copia aprovada continua com o conteudo que foi aprovado
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(copia));
    }

    @Test
    void pathsMultiplosAprovadosIndexadosEAlteracaoRecusada() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path a = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path b = Files.write(raiz.resolve("b.jpg"), new byte[]{3, 4, 5});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUploads(List.of(a.toString(), b.toString()), CLIQUE))).get("id").asText();
        bs.approve(id, "humano", null);
        JsonNode aprovado = bs.requireApproved(id);
        assertEquals(2, aprovado.path("midias").size());
        assertEquals(0, aprovado.path("midias").get(0).path("indice").asInt());
        assertEquals(1, aprovado.path("midias").get(1).path("indice").asInt());
        assertEquals(2, BatchStore.midiasDoPasso(aprovado, 0).size());
        Files.write(b, new byte[]{8, 9, 10});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("alterado depois da aprovacao"), e.getMessage());
    }

    @Test
    void pathsQuantidadeDuplicidadeOuPathJuntoRecusados() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path a = Files.write(raiz.resolve("a.png"), new byte[]{1});
        Path b = Files.write(raiz.resolve("b.png"), new byte[]{2});
        BatchStore bs = new BatchStore(storeUpload(raiz, null));
        for (List<String> paths : List.of(List.of(a.toString()), java.util.Collections.nCopies(11, a.toString()))) {
            assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(loteUploads(paths, CLIQUE))));
        }
        assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(loteUploads(List.of(a.toString(), a.toString()), CLIQUE))));
        String both = loteUploads(List.of(a.toString(), b.toString()), CLIQUE).replace("\"paths\":", "\"path\":\"" + a + "\",\"paths\":");
        assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(both)));
    }

    @Test
    void rollbackRemoveCopiasFeitasQuandoMidiaPosteriorFalhaNaAprovacao() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path a = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path b = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUploads(List.of(a.toString(), b.toString()), CLIQUE))).get("id").asText();
        Files.delete(b);

        assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        try (var files = Files.list(s.batchesDir())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(id + "-media-")));
        }
    }

    @Test
    void copiaDaMidiaApagadaQuandoLoteExecutaOuERevogado() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        for (boolean executa : new boolean[]{true, false}) {
            String id = bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))).get("id").asText();
            bs.approve(id, "humano", null);
            Path copia = BatchStore.copiaAprovada(s, bs.requireApproved(id).path("midias").get(0));
            assertTrue(Files.isRegularFile(copia));
            if (executa) {
                bs.markExecuted(id, Json.obj());
            } else {
                bs.reject(id, "revogado");
            }
            assertFalse(Files.exists(copia), "copia de " + (executa ? "executado" : "revogado") + " ficou em batches/");
            assertTrue(Files.isRegularFile(img), "o arquivo original nunca e tocado");
        }
    }

    @Test
    void setFileInvalidoRecusadoNoDraft() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1});
        Path fora = Files.write(Files.createDirectories(tmp.resolve("outro")).resolve("x.png"), new byte[]{1});
        Path script = Files.write(raiz.resolve("x.sh"), new byte[]{1});
        Path grande = Files.write(raiz.resolve("g.mp4"), new byte[1024 * 1024 + 1]);
        Path link = raiz.resolve("link.png");
        Files.createSymbolicLink(link, fora);
        BatchStore bs = new BatchStore(storeUpload(raiz, 1));
        String[][] casos = {
                {fora.toString(), "fora das raizes"},
                {raiz.resolve("../outro/x.png").toString(), "fora das raizes"},
                {link.toString(), "fora das raizes"},
                {raiz.resolve("nao-existe.png").toString(), "inexistente"},
                {script.toString(), "extensao"},
                {grande.toString(), "uploadMaxMb"},
                {"marketing/card.png", "absoluto"},
                {raiz.toString(), "arquivo regular"}};
        for (String[] c : casos) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.draft("up", Json.parse(loteUpload(c[0], CLIQUE))), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + " -> " + e.getMessage());
        }
        // sem passo de envio depois (type nao envia; envio antes nao vale)
        for (String depois : List.of("", ",{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"oi\"}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.draft("up", Json.parse(loteUpload(img.toString(), depois))));
            assertTrue(e.getMessage().contains("set_file sem passo de envio"), e.getMessage());
        }
        String antes = "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + loteUpload(img.toString(), "").substring(1);
        assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(antes)));
        // sem selector
        assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(
                loteUpload(img.toString(), CLIQUE).replace("\"selector\":\"css:input[type=file]\",", ""))));
    }

    @Test
    void uploadRootsPadraoVemDoGateConfig() throws Exception {
        Path proj = Files.createDirectories(tmp.resolve("proj"));
        Path mkt = Files.createDirectories(proj.resolve("marketing"));
        Path img = Files.write(mkt.resolve("a.jpg"), new byte[]{1});
        Path naRaiz = Files.write(proj.resolve("b.jpg"), new byte[]{1});
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.path("defaults").path("gate")).put("config", mkt.resolve("gate-texto.json").toString());
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        assertEquals(List.of(mkt.toRealPath()), BatchStore.uploadRoots(s));
        BatchStore bs = new BatchStore(s);
        assertDoesNotThrow(() -> bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("up", Json.parse(loteUpload(naRaiz.toString(), CLIQUE))));
    }

    @Test
    void screenshotEWaitAceitosNoDraftENaoPublicos() throws Exception {
        BatchStore bs = new BatchStore(store());
        JsonNode lote = Json.parse("""
                [{"network":"x","kind":"goto","url":"https://x.com/i/chat"},
                 {"network":"x","kind":"wait","ms":3000},
                 {"network":"x","kind":"wait","expect":"Chat","timeoutMs":15000},
                 {"network":"x","kind":"wait","expect":"Chat"},
                 {"network":"x","kind":"screenshot","description":"inbox"}]
                """);
        assertDoesNotThrow(() -> bs.draft("leitura", lote));
        assertEquals("x", bs.validateBatchRules(lote));
    }

    @Test
    void waitInvalidoRecusadoNoDraft() {
        BatchStore bs = new BatchStore(store());
        for (String w : List.of(
                "{\"kind\":\"wait\"}",
                "{\"kind\":\"wait\",\"ms\":30001}",
                "{\"kind\":\"wait\",\"ms\":0}",
                "{\"kind\":\"wait\",\"ms\":\"100\"}",
                "{\"kind\":\"wait\",\"ms\":1.5}",
                "{\"kind\":\"wait\",\"expect\":\"  \"}",
                "{\"kind\":\"wait\",\"expect\":\"a\",\"timeoutMs\":660001}",
                "{\"kind\":\"wait\",\"ms\":100,\"expect\":\"a\"}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"x\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("w", Json.parse(lote)), lote);
        }
        assertDoesNotThrow(() -> bs.draft("w", Json.parse("[{\"network\":\"x\",\"kind\":\"wait\",\"ms\":30000}]")));
    }

    @Test
    void seletorShadowValidadoNoDraft() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("s", Json.parse("""
                [{"network":"x","kind":"click","selector":"css:div[data-testid=xchatEmbedRoute] >>> a[href*='/i/chat/']"}]
                """)));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("s", Json.parse("""
                [{"network":"x","kind":"click","selector":"css:div >>> "}]
                """)));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("s", Json.parse("""
                [{"network":"x","kind":"click","selector":"xpath://div >>> a"}]
                """)));
    }

    @Test
    void waitExpectLongoAceitoAte11MinEComOrcamentoPorLote() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("w", Json.parse("""
                [{"network":"tiktok","kind":"wait","ms":30000},
                 {"network":"tiktok","kind":"wait","expect":"ainda pode ser removido","timeoutMs":660000}]
                """)));
        // soma 30000 + 660000 + 15000 (padrao) + 30000 = 735000 > 720000
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("w", Json.parse("""
                [{"network":"tiktok","kind":"wait","ms":30000},
                 {"network":"tiktok","kind":"wait","expect":"a","timeoutMs":660000},
                 {"network":"tiktok","kind":"wait","expect":"b"},
                 {"network":"tiktok","kind":"wait","ms":30000}]
                """)));
        assertTrue(e.getMessage().contains("720000"), e.getMessage());
    }

    @Test
    void clickModeValidado() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("c", Json.parse("""
                [{"network":"youtube","kind":"click","selector":"css:a","clickMode":"dom"},
                 {"network":"youtube","kind":"click","selector":"css:b","clickMode":"mouse"},
                 {"network":"youtube","kind":"click","selector":"css:c"}]
                """)));
        for (String m : List.of("\"js\"", "\"\"", "1", "\"DOM\"")) {
            String lote = "[{\"network\":\"youtube\",\"kind\":\"click\",\"selector\":\"css:a\",\"clickMode\":" + m + "}]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("c", Json.parse(lote)), lote);
        }
    }

    @Test
    void clickDomProibidoNoTiktok() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("c", Json.parse("""
                [{"network":"tiktok","kind":"click","selector":"css:button[data-e2e=post_video_button]","clickMode":"dom"}]
                """)));
        assertTrue(e.getMessage().contains("telemetria"), e.getMessage());
        // lote aprovado antes da regra: a execucao revalida por validateBatchRules
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> bs.validateBatchRules(Json.parse("""
                [{"network":"tiktok","kind":"click","selector":"css:a","clickMode":"dom"}]
                """)));
        assertTrue(ex.getMessage().contains("telemetria"), ex.getMessage());
        assertDoesNotThrow(() -> bs.draft("c", Json.parse("""
                [{"network":"tiktok","kind":"click","selector":"css:button[data-e2e=post_video_button]","clickMode":"mouse"}]
                """)));
    }

    @Test
    void verifyAttrENaoPublicoEValidado() {
        BatchStore bs = new BatchStore(store());
        assertFalse(Guard.isPublic("verify_attr"));
        assertDoesNotThrow(() -> bs.draft("v", Json.parse("""
                [{"network":"youtube","kind":"verify_attr","selector":"css:tp-yt-paper-radio-button[name=PUBLIC]","attr":"aria-checked","expect":"true"},
                 {"network":"youtube","kind":"verify_attr","selector":"css:x","attr":"checked","expect":""},
                 {"network":"youtube","kind":"verify_text","selector":"css:ytcp-uploads-dialog #done-button","expect":"Publicar"}]
                """)));
        for (String w : List.of(
                "{\"kind\":\"verify_attr\",\"attr\":\"aria-checked\",\"expect\":\"true\"}",
                "{\"kind\":\"verify_attr\",\"selector\":\"css:a\",\"expect\":\"true\"}",
                "{\"kind\":\"verify_attr\",\"selector\":\"css:a\",\"attr\":\"a b\",\"expect\":\"true\"}",
                "{\"kind\":\"verify_attr\",\"selector\":\"css:a\",\"attr\":\"aria-checked\"}",
                "{\"kind\":\"verify_attr\",\"selector\":\"css:a\",\"attr\":\"aria-checked\",\"expect\":true}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"youtube\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("v", Json.parse(lote)), lote);
        }
    }

    @Test
    void ifPresentSoEmClickEBooleano() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("i", Json.parse("""
                [{"network":"tiktok","kind":"click","ifPresent":true,"selector":"xpath://div[@data-e2e='local_draft_container']//button"},
                 {"network":"tiktok","kind":"click","ifPresent":false,"selector":"css:button"}]
                """)));
        for (String w : List.of(
                "{\"kind\":\"click\",\"selector\":\"css:a\",\"ifPresent\":\"sim\"}",
                "{\"kind\":\"type\",\"text\":\"oi\",\"ifPresent\":true}",
                "{\"kind\":\"verify_text\",\"expect\":\"oi\",\"ifPresent\":true}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"tiktok\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("i", Json.parse(lote)), lote);
        }
    }

    // ---------- capture_ids / verify_new_id ----------

    private static String cap(String extra) {
        return "{\"network\":\"mastodon\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"" + extra + "}";
    }

    private static String ver(String extra) {
        return "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"" + extra + "}";
    }

    @Test
    void captureIdsEVerifyNewIdValidados() {
        BatchStore bs = new BatchStore(store());
        assertFalse(Guard.isPublic("capture_ids"));
        assertFalse(Guard.isPublic("verify_new_id"));
        assertDoesNotThrow(() -> bs.draft("ok", Json.parse("["
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver("") + "]")));
        // campos opcionais validos
        assertDoesNotThrow(() -> bs.draft("ok", Json.parse("["
                + cap(",\"parentSelector\":\"css:main\",\"attrSelector\":\"a[href]\",\"idRegex\":\"/posts/([0-9]+)\",\"waitMs\":2000") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver(",\"parentSelector\":\"css:main\",\"attrSelector\":\"a[href]\",\"idRegex\":\"/posts/([0-9]+)\",\"expect\":\"oi\",\"timeoutMs\":3000") + "]")));
        assertDoesNotThrow(() -> bs.draft("min ids", Json.parse("[" + cap(",\"minIds\":1") + "]")));
        // invalidos
        for (String w : List.of(
                "{\"kind\":\"capture_ids\",\"attr\":\"data-id\",\"name\":\"posts\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"name\":\"posts\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"a b\",\"name\":\"posts\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"Posts\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"waitMs\":0}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"waitMs\":660001}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"minIds\":-1}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"minIds\":1001}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"minIds\":1.5}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"attrSelector\":\"xpath://a\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"idRegex\":\"(\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"idRegex\":\"" + "a".repeat(201) + "\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"parentSelector\":\"xpath:\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"id:x\",\"attr\":\"data-id\",\"name\":\"posts\",\"parentSelector\":\"css:main\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"xpath://article\",\"attr\":\"data-id\",\"name\":\"posts\",\"parentSelector\":\"css:main\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"xpath:(//article)\",\"attr\":\"data-id\",\"name\":\"posts\",\"parentSelector\":\"css:main\"}",
                "{\"kind\":\"verify_new_id\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"expect\":\"\"}",
                "{\"kind\":\"verify_new_id\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"timeoutMs\":0}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"mastodon\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("v", Json.parse(lote)), lote);
        }
    }

    @Test
    void xpathComParentSelectorExigeAlternativasRelativas() {
        BatchStore bs = new BatchStore(store());
        for (String expr : List.of(
                "//article", "( //article)[1]", "((//article))[1]", ".//a | //b")) {
            String capture = cap(",\"parentSelector\":\"css:main\"")
                    .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:" + expr + "\"");
            assertThrows(IllegalArgumentException.class,
                    () -> bs.draft("invalid capture xpath", Json.parse("[" + capture + "]")), expr);

            String captureBase = cap(",\"parentSelector\":\"css:main\"");
            String verify = ver(",\"parentSelector\":\"css:main\"")
                    .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:" + expr + "\"");
            assertThrows(IllegalArgumentException.class,
                    () -> bs.draft("invalid verify xpath", Json.parse("[" + captureBase + "," + verify + "]")), expr);
        }

        for (String expr : List.of(".//article", "(.//article)[1]", ".//a | .//b", "./article")) {
            String capture = cap(",\"parentSelector\":\"css:main\"")
                    .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:" + expr + "\"");
            assertDoesNotThrow(() -> bs.draft("valid capture xpath", Json.parse("[" + capture + "]")), expr);

            String captureBase = cap(",\"parentSelector\":\"css:main\"");
            String verify = ver(",\"parentSelector\":\"css:main\"")
                    .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:" + expr + "\"");
            assertDoesNotThrow(() -> bs.draft("valid verify xpath", Json.parse("[" + capture + "," + verify + "]")), expr); // par com o mesmo selector
        }

        assertDoesNotThrow(() -> bs.draft("absolute xpath without parent", Json.parse("["
                + cap("").replace("\"selector\":\"css:article\"", "\"selector\":\"xpath://article\"")
                + "]")));
    }

    @Test
    void stableMsEAttrTextValidados() {
        BatchStore bs = new BatchStore(store());
        String capText = "{\"network\":\"mastodon\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"@text\",\"name\":\"posts\"}";
        String verText = "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"@text\",\"name\":\"posts\"}";
        // @text aceito no par capture/verify
        assertDoesNotThrow(() -> bs.draft("text", Json.parse("["
                + capText + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + verText + "]")));
        // stableMs valido: waitMs explicito >= stableMs + 1000, e padrao (10000) tambem serve
        assertDoesNotThrow(() -> bs.draft("stable", Json.parse("["
                + cap(",\"stableMs\":3000,\"waitMs\":4000") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver("") + "]")));
        assertDoesNotThrow(() -> bs.draft("stable", Json.parse("[" + cap(",\"stableMs\":3000") + "]")));
        // @foo recusado
        assertThrows(IllegalArgumentException.class, () -> bs.draft("foo", Json.parse("["
                + "{\"network\":\"mastodon\",\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"@foo\",\"name\":\"posts\"}]")));
        // stableMs fora do intervalo / waitMs < stableMs + 1000
        for (String w : List.of(
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"stableMs\":999}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"stableMs\":30001}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"stableMs\":\"1000\"}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"stableMs\":3000,\"waitMs\":3999}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"mastodon\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.draft("stable", Json.parse(lote)), lote);
        }
    }

    @Test
    void validateIdPairsExigeCaptureAnteriorEMesmoAttrs() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + ver("") + "]")));
        assertTrue(e.getMessage().contains("sem capture_ids anterior"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + ver("") + "," + cap("") + "]")));
        assertTrue(e.getMessage().contains("sem capture_ids anterior"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + cap("") + "," + cap("") + "]")));
        assertTrue(e.getMessage().contains("name repetido"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("["
                        + cap("") + ","
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                        + ver(",\"attr\":\"href\"") + "]")));
        assertTrue(e.getMessage().contains("diferentes do capture_ids"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + cap("") + "," + ver(",\"selector\":\"css:section\"") + "]")));
        assertTrue(e.getMessage().contains("selector/parentSelector/attr/attrSelector/idRegex"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + cap(",\"parentSelector\":\"css:main\"")
                        + "," + ver(",\"parentSelector\":\"css:section\"") + "]")));
        assertTrue(e.getMessage().contains("selector/parentSelector/attr/attrSelector/idRegex"), e.getMessage());
    }

    @Test
    void validateWaitBudgetSomaCaptureEVerify() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("["
                        + cap(",\"waitMs\":660000") + ","
                        + ver(",\"timeoutMs\":660000") + "]")));
        assertTrue(e.getMessage().contains("720000"), e.getMessage());
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap("") + "," + ver("") + "]")));
    }

    @Test
    void conferenciaForteComParIdNovo() {
        assertEquals("forte", BatchStore.conferencia(Json.parse("["
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver("") + "]")));
        assertEquals("ausente", BatchStore.conferencia(Json.parse("["
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + cap("") + "," + ver("") + "]")));
        assertEquals("sem_publico", BatchStore.conferencia(Json.parse("[" + cap("") + "," + ver("") + "]")));
    }

    @Test
    void responseProofAceitaTrechoDoExpectCapturado() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado de teste 123";
        String capturaOk = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        String digitado = "{\"network\":\"instagram\",\"kind\":\"type\",\"selector\":\"css:textarea\",\"text\":\"" + expect + "\"}";
        String publica = "{\"network\":\"instagram\",\"kind\":\"click\",\"selector\":\"css:button\"}";
        String verificaOk = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        // trecho com pelo menos 8 caracteres passa, type continua contendo o capture completo
        assertDoesNotThrow(() -> bs.draft("prova", Json.parse("[" + capturaOk + "," + digitado + "," + publica + "," + verificaOk + "]")));
        String trecho = verificaOk.replace(expect, "aprovado de teste");
        assertDoesNotThrow(() -> bs.draft("prova trecho", Json.parse("[" + capturaOk + "," + digitado + "," + publica + "," + trecho + "]")));
        // sem opt-in nada muda: sem expect e sem type continua valido
        assertDoesNotThrow(() -> bs.draft("legado", Json.parse("[" + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"}," + ver("") + "]")));
        // expect obrigatorio 8..2200 no capture e no verify
        for (String ruim : List.of(
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"responseProof\":true}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"expect\":\"curto\",\"responseProof\":true}",
                "{\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"expect\":\"" + "a".repeat(2201) + "\",\"responseProof\":true}")) {
            String lote = "[" + ruim.replace("{", "{\"network\":\"instagram\",") + "]";
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("rp", Json.parse(lote)), lote);
            assertTrue(e.getMessage().contains("expect"), e.getMessage());
        }
        // rede fora da allowlist recusada no opt-in
        IllegalArgumentException rede = assertThrows(IllegalArgumentException.class, () -> bs.draft("rp", Json.parse("["
                + cap(",\"network\":\"threads\",\"expect\":\"" + expect + "\",\"responseProof\":true") + "]")));
        assertTrue(rede.getMessage().contains("bluesky"), rede.getMessage());
        // flag nao boolean recusada
        assertThrows(IllegalArgumentException.class, () -> bs.draft("rp", Json.parse("["
                + "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\","
                + "\"expect\":\"" + expect + "\",\"responseProof\":\"sim\"}]")));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("rp", Json.parse("["
                + "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\","
                + "\"expect\":\"" + expect + "\",\"responseProof\":1}]")));
        // verify sem par, com flag/expect diferente, ou sem type exato posterior: rejeitado
        IllegalArgumentException semPar = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + verificaOk + "]")));
        assertTrue(semPar.getMessage().contains("sem capture_ids anterior"), semPar.getMessage());
        IllegalArgumentException flag = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + "," + digitado + "," + publica + ","
                        + verificaOk.replace("\"responseProof\":true", "\"responseProof\":false") + "]")));
        assertTrue(flag.getMessage().contains("responseProof"), flag.getMessage());
        IllegalArgumentException outroExpect = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + "," + digitado + "," + publica + ","
                        + verificaOk.replace(expect, "curto") + "]")));
        assertTrue(outroExpect.getMessage().contains("8..2200"), outroExpect.getMessage());
        outroExpect = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + "," + digitado + "," + publica + ","
                        + verificaOk.replace(expect, "texto externo invalido") + "]")));
        assertTrue(outroExpect.getMessage().contains("nem trecho dele"), outroExpect.getMessage());
        IllegalArgumentException semType = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + "," + publica + "," + verificaOk + "]")));
        assertTrue(semType.getMessage().contains("type ou request POST posterior"), semType.getMessage());
        IllegalArgumentException typeDepois = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + "," + publica + "," + verificaOk + "," + digitado + "]")));
        assertTrue(typeDepois.getMessage().contains("type ou request POST posterior"), typeDepois.getMessage());
        IllegalArgumentException outroTexto = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capturaOk + ","
                        + digitado.replace(expect, "outro texto qualquer") + "," + publica + "," + verificaOk + "]")));
        assertTrue(outroTexto.getMessage().contains("EXATO"), outroTexto.getMessage());
    }

    @Test
    void responseProofAceitaTrechoDeOitoCaracteres() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado de teste 123";
        String capture = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        String type = "{\"network\":\"instagram\",\"kind\":\"type\",\"selector\":\"css:textarea\",\"text\":\"" + expect + "\"}";
        String publish = "{\"network\":\"instagram\",\"kind\":\"click\",\"selector\":\"css:button\"}";
        String verify = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"aprovado\",\"responseProof\":true}";
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + capture + "," + type + "," + publish + "," + verify + "]")));
    }

    @Test
    void responseProofRecusaTrechoDeSeteCaracteresComMensagemDeTrechoInvalido() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado de teste 123";
        String capture = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        String type = "{\"network\":\"instagram\",\"kind\":\"type\",\"selector\":\"css:textarea\",\"text\":\"" + expect + "\"}";
        String publish = "{\"network\":\"instagram\",\"kind\":\"click\",\"selector\":\"css:button\"}";
        String verify = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"aprovad\",\"responseProof\":true}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + capture + "," + type + "," + publish + "," + verify + "]")));
        assertTrue(e.getMessage().contains("8..2200") || e.getMessage().contains("nem trecho dele"), e.getMessage());
    }

    @Test
    void captureIdsEVerifyNewIdNaoContamNoTetoDePassosPublicos() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.draft("no limite", Json.parse("["
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:a\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:b\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:c\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:d\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:e\"},"
                + ver("") + "]")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("acima", Json.parse("["
                        + cap("") + ","
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:a\"},"
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:b\"},"
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:c\"},"
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:d\"},"
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:e\"},"
                        + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:f\"},"
                        + ver("") + "]")));
        assertTrue(e.getMessage().contains("passos publicos"), e.getMessage());
    }

    @Test
    void responseProofAceitaTextoEmRequestPostFormEncoded() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado aqui";
        String capture = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        String request = "{\"network\":\"instagram\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://instagram.com/publish\",\"body\":\"texto=texto+aprovado+aqui\"}";
        String verify = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        assertDoesNotThrow(() -> bs.draft("rp form", Json.parse("[" + capture + "," + request + "," + verify + "]")));
    }

    @Test
    void rejectGravaJournalComNetworkENota() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id, "texto improprio");
        String j = String.join("\n", s.tail(3));
        assertTrue(j.contains("batch_rejected"), j);
        assertTrue(j.contains("bluesky"), j);
        assertTrue(j.contains("texto improprio"), j);
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id2, null);
        String j2 = String.join("\n", s.tail(2));
        assertTrue(j2.contains("batch_rejected"), j2);
        assertTrue(j2.contains("\"nota\":\"\""), j2);
    }

    @Test
    void listMostraSufixoAbortadoSoEmExecutedAbortado() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String idOk = bs.draft("ok", acoes()).get("id").asText();
        bs.approve(idOk, "humano", null);
        bs.markExecuted(idOk, Json.obj().put("abortado", false).put("conferencia", "forte"));
        String idAb = bs.draft("abortado", acoes()).get("id").asText();
        bs.approve(idAb, "humano", null);
        bs.markExecuted(idAb, Json.obj().put("abortado", true).put("conferencia", "fraca"));
        String idSemConf = bs.draft("sem conf", acoes()).get("id").asText();
        bs.approve(idSemConf, "humano", null);
        bs.markExecuted(idSemConf, Json.obj().put("abortado", true));
        String idDraft = bs.draft("rascunho", acoes()).get("id").asText();
        List<String> linhas = bs.list();
        String linhaOk = linhas.stream().filter(l -> l.startsWith(idOk)).findFirst().orElseThrow();
        String linhaAb = linhas.stream().filter(l -> l.startsWith(idAb)).findFirst().orElseThrow();
        String linhaSemConf = linhas.stream().filter(l -> l.startsWith(idSemConf)).findFirst().orElseThrow();
        String linhaDraft = linhas.stream().filter(l -> l.startsWith(idDraft)).findFirst().orElseThrow();
        assertFalse(linhaOk.contains("[abortado:"), linhaOk);
        assertTrue(linhaOk.contains("(2 acoes)"), linhaOk);
        assertTrue(linhaAb.endsWith("[abortado: fraca]"), linhaAb);
        assertTrue(linhaAb.contains("(2 acoes)"), linhaAb);
        assertTrue(linhaSemConf.endsWith("[abortado: sem_conferencia]"), linhaSemConf);
        assertFalse(linhaDraft.contains("[abortado:"), linhaDraft);
    }
}
