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

/**
 * Mata os mutantes vivos (PIT: SURVIVED/NO_COVERAGE) de {@link BatchStore}.
 * Cada teste falha com o mutante e passa com o codigo atual, asserindo o
 * comportamento observavel exato (mensagem, journal, arquivo, digest).
 */
class BatchStoreMutantesTest {

    @TempDir
    Path tmp;

    private Store storeGate(String script) {
        try {
            return TextGateTest.storeComGate(tmp, script, 30);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Store store() {
        return storeGate("exit 0");
    }

    private JsonNode acoes() {
        return Json.parse("""
                [
                  {"network":"bluesky","kind":"goto","url":"https://bsky.app/profile/example_account.bsky.social"},
                  {"network":"bluesky","kind":"verify_text","expect":"ReservaJá"}
                ]
                """);
    }

    private static String req(String extra) {
        return "[{\"network\":\"pinterest\",\"kind\":\"request\"" + extra + "}]";
    }

    private static String reqDe(String net, String url, String extra) {
        return "[{\"network\":\"" + net + "\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"" + url + "\"" + extra + "}]";
    }

    private static String cap(String extra) {
        return "{\"network\":\"mastodon\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"" + extra + "}";
    }

    private static String ver(String extra) {
        return "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"" + extra + "}";
    }

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

    // ---------- L77: draft registra batch_created ----------

    @Test
    void draftRegistraJournalComLoteRedeRotuloEAcoes() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("rotulo mutante", acoes()).get("id").asText();
        String j = String.join("\n", s.tail(3));
        assertTrue(j.contains("\"type\":\"batch_created\""), j);
        assertTrue(j.contains(id), j);
        assertTrue(j.contains("\"network\":\"bluesky\""), j);
        assertTrue(j.contains("rotulo mutante"), j);
        assertTrue(j.contains("\"acoes\":2"), j);
    }

    // ---------- L107/L145/L517/L522/L1001: indice exato do passo ----------

    @Test
    void validateRelataIndiceExatoDoPassoComErro() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"mastodon","kind":"goto","url":"https://mastodon.social/a"},
                 {"network":"mastodon","kind":"click"}]
                """)));
        assertTrue(e1.getMessage().startsWith("acao 1:"), e1.getMessage());
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"mastodon","kind":"goto","url":"https://mastodon.social/a"},
                 {"network":"mastodon","kind":"goto","url":"https://mastodon.social/b"},
                 {"network":"mastodon","kind":"click"}]
                """)));
        assertTrue(e2.getMessage().startsWith("acao 2:"), e2.getMessage());
    }

    @Test
    void regrasDeLoteRelatamIndiceExato() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        BatchStore bs = new BatchStore(storeUpload(raiz, null));
        IllegalArgumentException dom = assertThrows(IllegalArgumentException.class, () -> bs.validateBatchRules(Json.parse("""
                [{"network":"tiktok","kind":"click","selector":"css:a","clickMode":"mouse"},
                 {"network":"tiktok","kind":"click","selector":"css:b","clickMode":"dom"}]
                """)));
        assertTrue(dom.getMessage().startsWith("acao 1:"), dom.getMessage());
        IllegalArgumentException ordem = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("sem envio", Json.parse(loteUpload(img.toString(), ""))));
        assertTrue(ordem.getMessage().startsWith("acao 0: set_file sem passo"), ordem.getMessage());
        IllegalArgumentException ordem1 = assertThrows(IllegalArgumentException.class, () -> bs.draft("sem envio",
                Json.parse("[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                        + loteUpload(img.toString(), "").substring(1))));
        assertTrue(ordem1.getMessage().startsWith("acao 1: set_file sem passo"), ordem1.getMessage());
    }

    @Test
    void gotoComMarcadorSemVerifyRelataIndiceExato() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"mastodon","kind":"goto","url":"https://mastodon.social/a"},
                 {"network":"mastodon","kind":"goto","url":"https://mastodon.social/a[[novo:x]]"}]
                """)));
        assertTrue(e.getMessage().contains("acao 1"), e.getMessage());
        assertTrue(e.getMessage().contains("sem verify_new_id"), e.getMessage());
    }

    // ---------- L162/L163: allowExisting e exact ----------

    @Test
    void allowExistingSoValeEmTypeComRegexValida() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"[a-z]+\"}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\"}]")));
        for (String lote : List.of(
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"\"}]",
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"a[\"}]",
                "[{\"network\":\"tiktok\",\"kind\":\"click\",\"selector\":\"css:a\",\"allowExisting\":\"[a-z]+\"}]",
                "[{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"https://mastodon.social/a\",\"allowExisting\":\"[a-z]+\"}]")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(lote)), lote);
            assertTrue(e.getMessage().contains("allowExisting"), e.getMessage());
        }
    }

    @Test
    void exactSoValeEmVerifyText() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[{\"network\":\"x\",\"kind\":\"verify_text\",\"expect\":\"oi\",\"exact\":true,\"selector\":\"css:a\"}]")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"tiktok\",\"kind\":\"click\",\"selector\":\"css:a\",\"exact\":true}]")));
        assertTrue(e.getMessage().contains("exact so vale em verify_text"), e.getMessage());
    }

    // ---------- L181/L186: validacao por kind ----------

    @Test
    void clickComModoInvalidoRecusado() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"youtube\",\"kind\":\"click\",\"selector\":\"css:a\",\"clickMode\":\"js\"}]")));
        assertTrue(e.getMessage().contains("clickMode"), e.getMessage());
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[{\"network\":\"youtube\",\"kind\":\"click\",\"selector\":\"css:a\",\"clickMode\":\"dom\"}]")));
    }

    @Test
    void verifyTextSemExpectRecusado() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"x\",\"kind\":\"verify_text\"}]")));
        assertTrue(e.getMessage().contains("verify_text exige expect"), e.getMessage());
    }

    // ---------- L202: paths 2..10 ----------

    @Test
    void setFilePathsAceitaDeDoisADez() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        java.util.ArrayList<String> dez = new java.util.ArrayList<>();
        for (int k = 0; k < 11; k++) {
            dez.add(Files.write(raiz.resolve("f" + k + ".png"), new byte[]{(byte) k}).toString());
        }
        BatchStore bs = new BatchStore(storeUpload(raiz, null));
        assertDoesNotThrow(() -> bs.draft("dois", Json.parse(loteUploads(dez.subList(0, 2), CLIQUE))));
        assertDoesNotThrow(() -> bs.draft("dez", Json.parse(loteUploads(dez.subList(0, 10), CLIQUE))));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("um", Json.parse(loteUploads(dez.subList(0, 1), CLIQUE))));
        assertThrows(IllegalArgumentException.class, () -> bs.draft("onze", Json.parse(loteUploads(dez, CLIQUE))));
        assertThrows(IllegalArgumentException.class,
                () -> bs.draft("dup", Json.parse(loteUploads(List.of(dez.get(0), dez.get(0)), CLIQUE))));
    }

    // ---------- L224/L227: request GET e mensagem ----------

    @Test
    void requestGetSoComAllowGet() {
        Store s = store();
        JsonNode get = Json.parse("{\"network\":\"mastodon\",\"kind\":\"request\",\"method\":\"GET\",\"url\":\"https://mastodon.social/x\"}");
        assertDoesNotThrow(() -> BatchStore.validateRequest(s, get, 0, true));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BatchStore.validateRequest(s, get, 0, false));
        assertTrue(e.getMessage().contains("GET so em social_action readOnly"), e.getMessage());
        JsonNode trace = Json.parse("{\"network\":\"mastodon\",\"kind\":\"request\",\"method\":\"TRACE\",\"url\":\"https://mastodon.social/x\"}");
        IllegalArgumentException eGet = assertThrows(IllegalArgumentException.class,
                () -> BatchStore.validateRequest(s, trace, 0, true));
        assertTrue(eGet.getMessage().contains("|GET"), eGet.getMessage());
        assertDoesNotThrow(() -> BatchStore.validateRequest(s, Json.parse(
                "{\"network\":\"mastodon\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://mastodon.social/x\"}"), 0, false));
    }

    // ---------- L248: body ate 16 KiB ----------

    @Test
    void requestBodyToleraAte16KiBExatos() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String base = "https://br.pinterest.com/resource/X/";
        assertDoesNotThrow(() -> bs.draft("16k", Json.parse(req(",\"method\":\"POST\",\"url\":\"" + base
                + "\",\"body\":\"" + "b".repeat(16 * 1024) + "\""))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("16k+1", Json.parse(req(
                ",\"method\":\"POST\",\"url\":\"" + base + "\",\"body\":\"" + "b".repeat(16 * 1024 + 1) + "\""))));
        assertTrue(e.getMessage().contains("16 KiB"), e.getMessage());
    }

    // ---------- L268/L271/L277: expectStatus e expectBody ----------

    @Test
    void expectStatusAceitaDe100a599() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String base = "https://br.pinterest.com/resource/X/";
        for (String st : List.of("[100]", "[599]", "[200,201,299]")) {
            assertDoesNotThrow(() -> bs.draft("st", Json.parse(req(",\"method\":\"POST\",\"url\":\"" + base
                    + "\",\"expectStatus\":" + st))), st);
        }
        for (String st : List.of("[]", "[99]", "[600]", "[\"200\"]", "[1.5]")) {
            assertThrows(IllegalArgumentException.class, () -> bs.draft("st", Json.parse(req(",\"method\":\"POST\",\"url\":\""
                    + base + "\",\"expectStatus\":" + st))), st);
        }
    }

    @Test
    void expectBodyPrecisaSerTexto() {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String base = "https://br.pinterest.com/resource/X/";
        assertDoesNotThrow(() -> bs.draft("eb", Json.parse(req(",\"method\":\"POST\",\"url\":\"" + base + "\""))));
        assertDoesNotThrow(() -> bs.draft("eb", Json.parse(req(",\"method\":\"POST\",\"url\":\"" + base
                + "\",\"expectBody\":\"ok\""))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("eb", Json.parse(req(
                ",\"method\":\"POST\",\"url\":\"" + base + "\",\"expectBody\":5"))));
        assertTrue(e.getMessage().contains("expectBody deve ser string"), e.getMessage());
    }

    // ---------- L291: mensagem de credentials ----------

    @Test
    void credentialsInvalidasMostramValorEUso() {
        BatchStore bs = new BatchStore(store());
        String u = "https://bsky.social/xrpc/com.atproto.repo.createRecord";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("cred", Json.parse(reqDe("bluesky", u, ",\"credentials\":\"all\""))));
        assertTrue(e.getMessage().contains("invalido 'all'"), e.getMessage());
        assertTrue(e.getMessage().contains("include|omit|same-origin"), e.getMessage());
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("cred", Json.parse(reqDe("bluesky", u, ",\"credentials\":true"))));
        assertTrue(e2.getMessage().contains("invalido 'true'"), e2.getMessage());
    }

    // ---------- L372: auth valida bodyField ----------

    @Test
    void authComBodyFieldInvalidoRecusado() {
        BatchStore bs = new BatchStore(store());
        String u = "https://www.reddit.com/svc/shreddit/graphql";
        String json = ",\"headers\":{\"Content-Type\":\"application/json\"}";
        assertDoesNotThrow(() -> bs.draft("auth", Json.parse(reqDe("reddit", u, json
                + ",\"auth\":{\"from\":\"cookie\",\"key\":\"token_v2\",\"bodyField\":\"variables.token\"}"))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("auth", Json.parse(reqDe(
                "reddit", u, json + ",\"auth\":{\"from\":\"cookie\",\"key\":\"t\",\"bodyField\":\"a..b\"}"))));
        assertTrue(e.getMessage().contains("auth.bodyField invalido"), e.getMessage());
    }

    // ---------- L423/L426/L427: dominio registravel ----------

    @Test
    void dominioRegistravelCasosLimite() {
        assertEquals("pinterest.com", BatchStore.dominioRegistravel("pinterest.com"));
        assertEquals("pinterest.com", BatchStore.dominioRegistravel("br.pinterest.com"));
        assertEquals("example.co.uk", BatchStore.dominioRegistravel("social.example.co.uk"));
        assertEquals("b.co.uk", BatchStore.dominioRegistravel("a.b.co.uk"));
        assertEquals("x.co.de", BatchStore.dominioRegistravel("x.co.de"));
        assertEquals("co.de", BatchStore.dominioRegistravel("co.de"));
        assertEquals("localhost", BatchStore.dominioRegistravel("localhost"));
    }

    // ---------- L465/L470/L473/L476: resumo de url ----------

    @Test
    void resumoUrlCasosLimite() {
        assertEquals("(url invalida)", BatchStore.resumoUrl(null));
        assertEquals("(url invalida)", BatchStore.resumoUrl("https://[invalido"));
        assertEquals("(url invalida)", BatchStore.resumoUrl("apenas-texto"));
        assertEquals("https://mastodon.social", BatchStore.resumoUrl("https://mastodon.social/a/b?c=d"));
        String longa = "https://" + "a".repeat(130) + ".com/x";
        String r = BatchStore.resumoUrl(longa);
        assertEquals(120, r.length());
        assertEquals(("https://" + "a".repeat(130) + ".com").substring(0, 120), r);
        assertTrue(longa.startsWith(r));
        String exata = "https://" + "b".repeat(108) + ".com/x";
        assertEquals("https://" + "b".repeat(108) + ".com", BatchStore.resumoUrl(exata));
        String estouro = "https://" + "c".repeat(109) + ".com/x";
        assertEquals(120, BatchStore.resumoUrl(estouro).length());
    }

    // ---------- L481/L491/L493: host da rede ----------

    @Test
    void hostDaRedeCasosLimite() throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode nets = (ObjectNode) cfg.get("networks");
        nets.putObject("ruim").put("startUrl", "::::");
        nets.putObject("semhost").put("startUrl", "https:///caminho");
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        assertFalse(BatchStore.hostDaRede(s, "redequenaoexiste", "x.com"));
        assertFalse(BatchStore.hostDaRede(s, "ruim", "x.com"));
        assertFalse(BatchStore.hostDaRede(s, "semhost", "x.com"));
        assertTrue(BatchStore.hostDaRede(s, "mastodon", "mastodon.social"));
        assertFalse(BatchStore.hostDaRede(s, "mastodon", "attacker.com"));
    }

    // ---------- L504/L506: curinga e host exato ----------

    @Test
    void hostCasaCuringaCasosLimite() {
        assertTrue(BatchStore.hostCasa("a.host.bsky.network", "*.host.bsky.network"));
        assertFalse(BatchStore.hostCasa("host.bsky.network", "*.host.bsky.network"));
        assertFalse(BatchStore.hostCasa("a.", "*."));
        assertFalse(BatchStore.hostCasa("q*.x", "**.x"));
        assertTrue(BatchStore.hostCasa("api.x.com", "api.x.com"));
        assertFalse(BatchStore.hostCasa("*abc", "*abc"));
        assertFalse(BatchStore.hostCasa("x", ""));
        assertFalse(BatchStore.hostCasa("bsky.social", "*"));
    }

    // ---------- L570/L581: extensao e tamanho do upload ----------

    @Test
    void uploadAceitaPontoPngERecusaSemExtensao() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path ponto = Files.write(raiz.resolve(".png"), new byte[]{1});
        Path semExt = Files.write(raiz.resolve("png"), new byte[]{1});
        BatchStore bs = new BatchStore(storeUpload(raiz, null));
        assertDoesNotThrow(() -> bs.draft("ponto", Json.parse(loteUpload(ponto.toString(), CLIQUE))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("semext", Json.parse(loteUpload(semExt.toString(), CLIQUE))));
        assertTrue(e.getMessage().contains("extensao"), e.getMessage());
    }

    @Test
    void uploadTamanhoExatoNoLimitePassa() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path exato = Files.write(raiz.resolve("exato.png"), new byte[1024 * 1024]);
        Path estouro = Files.write(raiz.resolve("estouro.png"), new byte[1024 * 1024 + 1]);
        BatchStore bs = new BatchStore(storeUpload(raiz, 1));
        assertDoesNotThrow(() -> bs.draft("exato", Json.parse(loteUpload(exato.toString(), CLIQUE))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("estouro", Json.parse(loteUpload(estouro.toString(), CLIQUE))));
        assertTrue(e.getMessage().contains("uploadMaxMb"), e.getMessage());
    }

    // ---------- L687: allowExisting 1..200 chars ----------

    @Test
    void allowExistingLimiteDe200Chars() {
        BatchStore bs = new BatchStore(store());
        String ok = "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"" + "a".repeat(200) + "\"}]";
        assertDoesNotThrow(() -> bs.validate(Json.parse(ok)));
        for (String lote : List.of(
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"" + "a".repeat(201) + "\"}]",
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":5}]",
                "[{\"network\":\"tiktok\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"(\"}]")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(lote)), lote);
            assertTrue(e.getMessage().contains("allowExisting"), e.getMessage());
        }
    }

    // ---------- L718: verify_absent timeoutMs 1..MAX ----------

    @Test
    void verifyAbsentTimeoutLimites() {
        BatchStore bs = new BatchStore(store());
        String base = "{\"network\":\"mastodon\",\"kind\":\"verify_absent\",\"selector\":\"css:a\"";
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"timeoutMs\":1}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"timeoutMs\":660000}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + "}]")));
        for (String t : List.of("0", "660001")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.validate(Json.parse("[" + base + ",\"timeoutMs\":" + t + "}]")), t);
            assertTrue(e.getMessage().contains("timeoutMs"), e.getMessage());
        }
    }

    // ---------- L734/L740: capture_ids minIds e stableMs ----------

    @Test
    void captureIdsMinIdsEStableMsLimites() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap(",\"minIds\":0") + "]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap(",\"minIds\":1000") + "]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap(",\"stableMs\":1000") + "]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap(",\"stableMs\":30000,\"waitMs\":31000") + "]")));
        for (String extra : List.of(",\"minIds\":-1", ",\"minIds\":1001", ",\"stableMs\":999", ",\"stableMs\":30001")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.validate(Json.parse("[" + cap(extra) + "]")), extra);
            assertTrue(e.getMessage().contains("capture_ids"), e.getMessage());
        }
    }

    // ---------- L756: capture responseProof expect 8..2200 ----------

    @Test
    void captureResponseProofExpectLimites() {
        BatchStore bs = new BatchStore(store());
        String base = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:a\",\"attr\":\"data-id\",\"name\":\"posts\",\"responseProof\":true,\"expect\":\"";
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + "12345678\"}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + "x".repeat(2200) + "\"}]")));
        for (String expect : List.of("1234567", "x".repeat(2201))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.validate(Json.parse("[" + base + expect + "\"}]")), String.valueOf(expect.length()));
            assertTrue(e.getMessage().contains("8..2200"), e.getMessage());
        }
    }

    // ---------- L773/L775/L779/L782/L785: verify_new_id ----------

    @Test
    void verifyNewIdExigeSelectorDoPar() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[" + cap("") + ",{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"attr\":\"data-id\",\"name\":\"posts\"}]")));
        assertTrue(e.getMessage().contains("exige selector"), e.getMessage());
    }

    @Test
    void verifyNewIdExpectETimeoutLimites() {
        BatchStore bs = new BatchStore(store());
        String par = "[" + cap("") + ",{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:b\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"";
        assertDoesNotThrow(() -> bs.validate(Json.parse(par + "}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse(par + ",\"timeoutMs\":1}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse(par + ",\"timeoutMs\":660000}]")));
        IllegalArgumentException vazio = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse(par + ",\"expect\":\"\"}]")));
        assertTrue(vazio.getMessage().contains("expect"), vazio.getMessage());
        for (String t : List.of("0", "660001")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.validate(Json.parse(par + ",\"timeoutMs\":" + t + "}]")), t);
            assertTrue(e.getMessage().contains("timeoutMs"), e.getMessage());
        }
    }

    @Test
    void verifyNewIdResponseProofPrecisaSerBooleano() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[" + cap("") + ",{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\","
                        + "\"attr\":\"data-id\",\"name\":\"posts\",\"responseProof\":\"sim\"}]")));
        assertTrue(e.getMessage().contains("responseProof deve ser boolean"), e.getMessage());
    }

    @Test
    void verifyNewIdResponseProofExpectLimites() {
        BatchStore bs = new BatchStore(store());
        String expect8 = "12345678";
        String captura = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect8 + "\",\"responseProof\":true}";
        String tipo = "{\"network\":\"instagram\",\"kind\":\"type\",\"selector\":\"css:t\",\"text\":\"" + expect8 + "\"}";
        String publica = "{\"network\":\"instagram\",\"kind\":\"click\",\"selector\":\"css:button\"}";
        String verifica = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect8 + "\",\"responseProof\":true}";
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + captura + "," + tipo + "," + publica + "," + verifica + "]")));
        String expect2200 = "y".repeat(2200);
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + captura.replace(expect8, expect2200) + ","
                + tipo.replace(expect8, expect2200) + "," + publica + "," + verifica.replace(expect8, expect2200) + "]")));
        IllegalArgumentException curto = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[" + captura + "," + tipo + "," + publica + "," + verifica.replace(expect8, "1234567") + "]")));
        assertTrue(curto.getMessage().contains("8..2200"), curto.getMessage());
    }

    // ---------- L832/L839: attrSelector e idRegex ----------

    private static JsonNode loteAttrSelector(String attrSelector) {
        ObjectNode n = Json.obj();
        n.put("network", "mastodon");
        n.put("kind", "capture_ids");
        n.put("selector", "css:article");
        n.put("attr", "data-id");
        n.put("name", "posts");
        n.put("attrSelector", attrSelector);
        return Json.M.createArrayNode().add(n);
    }

    @Test
    void attrSelectorSoCssPuro() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(loteAttrSelector("css:a[href]")));
        assertDoesNotThrow(() -> bs.validate(loteAttrSelector("css:a[title=\"x\"]")));
        for (String sel : List.of("xpath://a", "id:x", "css:", "css:a >>>")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> bs.validate(loteAttrSelector(sel)), sel);
            assertTrue(e.getMessage().contains("attrSelector deve ser css puro"), e.getMessage());
        }
    }

    @Test
    void idRegexLimiteDe200Chars() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + cap(",\"idRegex\":\"" + "a".repeat(200) + "\"") + "]")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + cap(",\"idRegex\":\"" + "a".repeat(201) + "\"") + "]")));
        assertTrue(e.getMessage().contains("idRegex acima de 200"), e.getMessage());
        ObjectNode regexInvalido = (ObjectNode) Json.parse(cap(""));
        regexInvalido.put("idRegex", "(");
        IllegalArgumentException sintaxe = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.M.createArrayNode().add(regexInvalido)));
        assertTrue(sintaxe.getMessage().contains("idRegex invalido"), sintaxe.getMessage());
    }

    // ---------- L851: xpath relativo com parent ----------

    @Test
    void xpathComAlternativaVaziaRejeitado() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[" + cap(",\"parentSelector\":\"css:main\"").replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:a|\"") + "]")));
        assertTrue(e.getMessage().contains("precisa ser relativo"), e.getMessage());
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[" + cap(",\"parentSelector\":\"css:main\"").replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:.//a | .//b\"") + "]")));
    }

    // ---------- L916/L932/L934: texto digitado entre capture e verify ----------

    private static String parRp(String expect, String meio) {
        String captura = "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        String verifica = "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\","
                + "\"name\":\"posts\",\"expect\":\"" + expect + "\",\"responseProof\":true}";
        return "[" + captura + "," + meio + "," + verifica + "]";
    }

    @Test
    void responseProofExigeTypeOuRequestComTextoExatoEntreEles() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado de teste 123";
        String publica = "{\"network\":\"instagram\",\"kind\":\"click\",\"selector\":\"css:button\"}";
        String tipo = "{\"network\":\"instagram\",\"kind\":\"type\",\"selector\":\"css:t\",\"text\":\"" + expect + "\"}";
        assertDoesNotThrow(() -> bs.validate(Json.parse(parRp(expect, tipo + "," + publica))));
        IllegalArgumentException semTipo = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse(parRp(expect, publica))));
        assertTrue(semTipo.getMessage().contains("type ou request POST posterior"), semTipo.getMessage());
        IllegalArgumentException antes = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse("[" + tipo + "," + parRp(expect, publica).substring(1))));
        assertTrue(antes.getMessage().contains("type ou request POST posterior"), antes.getMessage());
    }

    @Test
    void responseProofAceitaRequestPostFormEncoded() {
        BatchStore bs = new BatchStore(store());
        String expect = "texto aprovado aqui";
        String request = "{\"network\":\"instagram\",\"kind\":\"request\",\"method\":\"POST\","
                + "\"url\":\"https://instagram.com/publish\",\"body\":\"texto=texto+aprovado+aqui\"}";
        assertDoesNotThrow(() -> bs.validate(Json.parse(parRp(expect, request))));
    }

    @Test
    void responseProofComCorpoQueNaoContemExpectRejeitado() {
        BatchStore bs = new BatchStore(store());
        String expect = "textoaprovado123";
        String request = "{\"network\":\"instagram\",\"kind\":\"request\",\"method\":\"POST\","
                + "\"url\":\"https://instagram.com/publish\",\"body\":\"outro corpo qualquer\"}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse(parRp(expect, request))));
        assertTrue(e.getMessage().contains("type ou request POST posterior"), e.getMessage());
        String cru = "{\"network\":\"instagram\",\"kind\":\"request\",\"method\":\"POST\","
                + "\"url\":\"https://instagram.com/publish\",\"body\":\"prefixo " + expect + " sufixo\"}";
        assertDoesNotThrow(() -> bs.validate(Json.parse(parRp(expect, cru))));
        String ilegivel = "{\"network\":\"instagram\",\"kind\":\"request\",\"method\":\"POST\","
                + "\"url\":\"https://instagram.com/publish\",\"body\":\"%\"}";
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> bs.validate(Json.parse(parRp(expect, ilegivel))));
        assertTrue(e2.getMessage().contains("type ou request POST posterior"), e2.getMessage());
    }

    // ---------- L959/L964/L975: marcador [[novo:NOME]] no goto ----------

    @Test
    void gotoComMarcadorSoNoPath() {
        BatchStore bs = new BatchStore(store());
        ObjectNode captura = Json.obj();
        captura.put("network", "mastodon");
        captura.put("kind", "capture_ids");
        captura.put("selector", "css:article");
        captura.put("attr", "data-id");
        captura.put("name", "x");
        ObjectNode publico = Json.obj();
        publico.put("network", "mastodon");
        publico.put("kind", "click");
        publico.put("selector", "css:b");
        ObjectNode verifica = Json.obj();
        verifica.put("network", "mastodon");
        verifica.put("kind", "verify_new_id");
        verifica.put("selector", "css:article");
        verifica.put("attr", "data-id");
        verifica.put("name", "x");
        ObjectNode vai = Json.obj();
        vai.put("network", "mastodon");
        vai.put("kind", "goto");
        vai.put("url", "https://mastodon.social/a[[novo:x]]");
        JsonNode loteValido = Json.M.createArrayNode().add(captura).add(publico).add(verifica).add(vai);
        assertDoesNotThrow(() -> bs.validate(loteValido));
        IllegalArgumentException host = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"https://[[novo:a]]mastodon.social/[[novo:b]]x\"}]")));
        assertTrue(host.getMessage().contains("so no path"), host.getMessage());
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"[[novo:a]]https://mastodon.social/x[[novo:b]]y\"}]")));
        assertTrue(zero.getMessage().contains("so no path"), zero.getMessage());
    }

    @Test
    void gotoComColcheteSemFechoEInvalido() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"[[https://mastodon.social/a\"}]")));
        assertTrue(e.getMessage().contains("marcador invalido"), e.getMessage());
        IllegalArgumentException semScheme = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"httpsmastodon.social/a[[novo:x]]\"}]")));
        assertTrue(semScheme.getMessage().contains("so no path"), semScheme.getMessage());
    }

    // ---------- L1074: request so confere com corpo e status 2xx ----------

    private static String reqForte(String status) {
        return "{\"network\":\"bluesky\",\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://bsky.social/xrpc/x\","
                + "\"expectBody\":\"ok\",\"expectStatus\":" + status + "}";
    }

    @Test
    void requestSeConfereSoComExpectBodyEStatus2xx() {
        assertEquals("forte", BatchStore.conferencia(Json.parse("[" + reqForte("[200]") + "]")));
        assertEquals("forte", BatchStore.conferencia(Json.parse("[" + reqForte("[299]") + "]")));
        assertEquals("fraca", BatchStore.conferencia(Json.parse("[" + reqForte("[199]") + "]")));
        assertEquals("fraca", BatchStore.conferencia(Json.parse("[" + reqForte("[300]") + "]")));
    }

    // ---------- L1088/L1093: prova por id novo ----------

    @Test
    void provaPorIdNovoCasosLimite() {
        assertFalse(BatchStore.provaPorIdNovo(Json.parse("[" + cap("") + "," + ver("") + "]")));
        assertTrue(BatchStore.provaPorIdNovo(Json.parse("["
                + "{\"network\":\"mastodon\",\"kind\":\"extract\",\"what\":\"links\"},"
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver("") + "]")));
        assertTrue(BatchStore.provaPorIdNovo(Json.parse("["
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + ver("") + "]")));
        assertFalse(BatchStore.provaPorIdNovo(Json.parse("["
                + "{\"network\":\"mastodon\",\"kind\":\"click\",\"selector\":\"css:button\"},"
                + cap("") + ","
                + ver("") + "]")));
    }

    // ---------- L1123: orcamento de espera 720000 ----------

    @Test
    void orcamentoDeEsperaLimiteExatoPassa() {
        BatchStore bs = new BatchStore(store());
        String lote = "["
                + "{\"network\":\"mastodon\",\"kind\":\"wait\",\"ms\":30000},"
                + "{\"network\":\"mastodon\",\"kind\":\"wait\",\"expect\":\"x\",\"timeoutMs\":660000},"
                + cap("") + ","
                + "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"},"
                + "{\"network\":\"mastodon\",\"kind\":\"wait\",\"ms\":5000}]";
        assertDoesNotThrow(() -> bs.validate(Json.parse(lote)));
        String estouro = lote.replace("\"ms\":5000", "\"ms\":5001");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(estouro)));
        assertTrue(e.getMessage().contains("720000"), e.getMessage());
    }

    // ---------- L1150/L1159: wait ms e timeoutMs ----------

    @Test
    void waitMsETimeoutMsLimites() {
        BatchStore bs = new BatchStore(store());
        String base = "{\"network\":\"x\",\"kind\":\"wait\"";
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"ms\":1}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"ms\":30000}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"expect\":\"x\",\"timeoutMs\":1}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[" + base + ",\"expect\":\"x\",\"timeoutMs\":660000}]")));
        for (String w : List.of(",\"ms\":0", ",\"ms\":30001", ",\"expect\":\"x\",\"timeoutMs\":0", ",\"expect\":\"x\",\"timeoutMs\":660001")) {
            assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("[" + base + w + "}]")), w);
        }
    }

    // ---------- L1170: extract match ----------

    @Test
    void extractMatchPrecisaSerTexto() {
        BatchStore bs = new BatchStore(store());
        assertDoesNotThrow(() -> bs.validate(Json.parse("[{\"network\":\"x\",\"kind\":\"extract\",\"what\":\"links\"}]")));
        assertDoesNotThrow(() -> bs.validate(Json.parse("[{\"network\":\"x\",\"kind\":\"extract\",\"what\":\"text\",\"match\":\"/a/\"}]")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[{\"network\":\"x\",\"kind\":\"extract\",\"what\":\"links\",\"match\":5}]")));
        assertTrue(e.getMessage().contains("extract match deve ser string"), e.getMessage());
    }

    // ---------- L1184: sha256 documentado ----------

    @Test
    void sha256DeConteudoConhecidoEVazio() throws Exception {
        Path abc = Files.write(tmp.resolve("abc.bin"), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BatchStore.sha256Arquivo(abc));
        Path vazio = Files.write(tmp.resolve("vazio.bin"), new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", BatchStore.sha256Arquivo(vazio));
    }

    // ---------- L1203: midias do passo em ordem ----------

    @Test
    void midiasDoPassoOrdenamPorIndice() {
        ObjectNode batch = Json.obj();
        var midias = batch.putArray("midias");
        midias.addObject().put("passo", 0).put("indice", 1).put("sha256", "a");
        midias.addObject().put("passo", 0).put("indice", 0).put("sha256", "b");
        midias.addObject().put("passo", 1).put("indice", 0).put("sha256", "c");
        var passo0 = BatchStore.midiasDoPasso(batch, 0);
        assertEquals(2, passo0.size());
        assertEquals(0, passo0.get(0).path("indice").asInt());
        assertEquals(1, passo0.get(1).path("indice").asInt());
        assertEquals(1, BatchStore.midiasDoPasso(batch, 1).size());
        assertTrue(BatchStore.midiasDoPasso(batch, 9).isEmpty());
    }

    // ---------- L1234/L1237/L1255: vinculo da midia ----------

    @Test
    void vincularMidiasApontaPassoCorretoENomeiaCopia() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path f1 = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path f2 = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String lote = "[{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f1 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f2 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]";
        String id = bs.draft("duas midias", Json.parse(lote)).get("id").asText();
        bs.approve(id, "humano", null, List.of(), false, true);
        JsonNode aprovado = bs.requireApproved(id);
        assertEquals(2, aprovado.path("midias").size());
        assertEquals(0, aprovado.path("midias").get(0).path("passo").asInt());
        assertEquals(2, aprovado.path("midias").get(1).path("passo").asInt());
        assertEquals(id + "-media-0-a.png", aprovado.path("midias").get(0).path("copia").asText());
        assertEquals(id + "-media-2-b.png", aprovado.path("midias").get(1).path("copia").asText());
    }

    @Test
    void vincularMidiasApontaPassoDoArquivoInvalido() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path f1 = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path f2 = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String lote = "[{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f1 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f2 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]";
        String id = bs.draft("midia some", Json.parse(lote)).get("id").asText();
        Files.delete(f2);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        assertTrue(e.getMessage().contains("passo 2"), e.getMessage());
        assertTrue(e.getMessage().contains("invalida na aprovacao"), e.getMessage());
    }

    // ---------- L1277/L1279/L1280: midia sem digest ----------

    private String aprovaMidiaUnica(Store s, Path img) throws Exception {
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))).get("id").asText();
        bs.approve(id, "humano", null, List.of(), false, true);
        return id;
    }

    @Test
    void midiaSemDigestOuIndiceTortoRecusadaComJournal() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = aprovaMidiaUnica(s, img);
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        ((ObjectNode) b.path("midias").get(0)).remove("sha256");
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 1"), e.getMessage());
        assertTrue(e.getMessage().contains("sem digest"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_sem_hash"));
    }

    @Test
    void midiaMultiplaComIndiceTortoRecusada() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path a = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path b2 = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUploads(List.of(a.toString(), b2.toString()), CLIQUE))).get("id").asText();
        bs.approve(id, "humano", null);
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode bb = (ObjectNode) Json.parse(Files.readString(ap));
        ((ObjectNode) bb.path("midias").get(1)).remove("indice");
        Files.writeString(ap, Json.writePretty(bb), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("sem digest"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_sem_hash"));
    }

    // ---------- L1290/L1291/L1294/L1295/L1300/L1301/L1307: midia adulterada ----------

    @Test
    void midiaApagadaDepoisDaAprovacaoRecusadaComJournal() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = aprovaMidiaUnica(s, img);
        Files.delete(img);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 1"), e.getMessage());
        assertTrue(e.getMessage().contains("invalida"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_invalida"));
    }

    @Test
    void midiaTrocadaDepoisDaAprovacaoRecusadaComJournal() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = aprovaMidiaUnica(s, img);
        Files.write(img, new byte[]{9, 9, 9});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 1"), e.getMessage());
        assertTrue(e.getMessage().contains("alterado depois da aprovacao"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_divergente"));
    }

    @Test
    void copiaAprovadaAdulteradaRecusadaComJournal() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = aprovaMidiaUnica(s, img);
        Path copia = BatchStore.copiaAprovada(s, bs.requireApproved(id).path("midias").get(0));
        Files.write(copia, new byte[]{7, 7, 7, 7});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 1"), e.getMessage());
        assertTrue(e.getMessage().contains("copia aprovada"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("copia_divergente"));
    }

    @Test
    void segundaMidiaTrocadaApontaPassoDois() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path f1 = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path f2 = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String lote = "[{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f1 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"set_file\",\"selector\":\"css:i\",\"path\":\"" + f2 + "\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]";
        String id = bs.draft("duas", Json.parse(lote)).get("id").asText();
        bs.approve(id, "humano", null);
        Files.write(f2, new byte[]{8, 8});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 2"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_divergente"));
    }

    // ---------- L1332/L1342/L1343: aprovado sem hash ou vencido ----------

    @Test
    void aprovadoSemHashRecusadoComJournal() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        b.remove("actionsHash");
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("sem gate/hash"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("sem_hash"));
    }

    @Test
    void aprovacaoVencidaRecusadaComJournalENaoVencidaPassa() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        b.put("approvedAt", System.currentTimeMillis() - 23L * 3_600_000L);
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> bs.requireApproved(id));
        b.put("approvedAt", System.currentTimeMillis() - 25L * 3_600_000L);
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("venceu (24h)"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("aprovacao_vencida"));
    }

    // ---------- L1435: nota da aprovacao ----------

    @Test
    void approveComNotaGravaNotaESemNotaNaoGrava() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "nota do dono");
        assertEquals("nota do dono", bs.requireApproved(id).path("nota").asText());
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id2, "humano", null);
        assertFalse(bs.requireApproved(id2).has("nota"));
    }

    // ---------- L1466: copias limpas quando o draft some no meio ----------

    @Test
    void approveComDraftRemovidoNoMeioLimpaCopias() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))).get("id").asText();
        bs.antesDeConfirmarAprovacao = () -> {
            try {
                Files.deleteIfExists(s.batchesDir().resolve(id + ".draft.json"));
            } catch (java.io.IOException ignored) {
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        assertTrue(e.getMessage().contains("rejeitado ou removido"), e.getMessage());
        try (var fs = Files.list(s.batchesDir())) {
            assertFalse(fs.anyMatch(p -> p.getFileName().toString().startsWith(id + "-media-")));
        }
    }

    // ---------- L1472/L1475: journal da aprovacao ----------

    @Test
    void journalDeAprovacaoRegistraNotaGateEAprovador() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "orquestrador", "revisado");
        String j = String.join("\n", s.tail(2));
        assertTrue(j.contains("batch_approved"), j);
        assertTrue(j.contains("\"nota\":\"revisado\""), j);
        assertTrue(j.contains("\"gate\":\"PASS\""), j);
        assertTrue(j.contains("\"forcado\":false"), j);
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id2, null, null);
        String j2 = String.join("\n", s.tail(1));
        assertTrue(j2.contains("\"nota\":\"\""), j2);
        assertTrue(j2.contains("\"aprovadoPor\":\"humano\""), j2);
        assertTrue(j2.contains("\"gate\":\"PASS\""), j2);
        assertTrue(j2.contains("\"forcado\":false"), j2);
    }

    // ---------- L1514/L1520: reject em transicao ----------

    @Test
    void rejectComLoteEmExecucaoOuEmAprovacao() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        Files.writeString(s.batchesDir().resolve(id + ".running.json"), "{}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.reject(id, null));
        assertTrue(e.getMessage().contains("em execucao"), e.getMessage());
        Files.deleteIfExists(s.batchesDir().resolve(id + ".running.json"));
        Files.writeString(s.batchesDir().resolve("bmanual1.approving.json"), "{}");
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> bs.reject("bmanual1", null));
        assertTrue(e2.getMessage().contains("sendo aprovado"), e2.getMessage());
    }

    // ---------- L1529/L1534: nota da rejeicao ----------

    @Test
    void rejectComNotaESemNota() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id, "texto improprio");
        JsonNode r = Json.parse(Files.readString(s.batchesDir().resolve(id + ".rejected.json")));
        assertEquals("texto improprio", r.path("nota").asText());
        assertTrue(String.join("\n", s.tail(2)).contains("texto improprio"));
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id2, null);
        assertFalse(Json.parse(Files.readString(s.batchesDir().resolve(id2 + ".rejected.json"))).has("nota"));
        assertTrue(String.join("\n", s.tail(1)).contains("\"nota\":\"\""));
        String id3 = bs.draft("teste", acoes()).get("id").asText();
        bs.reject(id3, "  ");
        assertFalse(Json.parse(Files.readString(s.batchesDir().resolve(id3 + ".rejected.json"))).has("nota"));
    }

    // ---------- L1537: rede da rejeicao ----------

    @Test
    void rejectSemRedeUsaRedeDaAcao() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        ObjectNode cru = Json.obj().put("id", "brede1").put("status", "draft");
        cru.set("actions", Json.parse("[{\"network\":\"mastodon\",\"kind\":\"extract\",\"what\":\"links\"}]"));
        Files.writeString(s.batchesDir().resolve("brede1.draft.json"), Json.writePretty(cru), StandardCharsets.UTF_8);
        bs.reject("brede1", null);
        assertTrue(String.join("\n", s.tail(2)).contains("\"network\":\"mastodon\""));
        ObjectNode vazia = Json.obj().put("id", "brede2").put("status", "draft").put("network", "");
        vazia.set("actions", Json.parse("[{\"network\":\"mastodon\",\"kind\":\"extract\",\"what\":\"links\"}]"));
        Files.writeString(s.batchesDir().resolve("brede2.draft.json"), Json.writePretty(vazia), StandardCharsets.UTF_8);
        bs.reject("brede2", null);
        assertTrue(String.join("\n", s.tail(1)).contains("\"network\":\"mastodon\""));
    }

    // ---------- L1574/L1578: claim ----------

    @Test
    void claimComHashDivergenteRecusaComJournal() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.claim(id, Json.obj()));
        assertTrue(e.getMessage().contains("mudou entre a conferencia"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("hash_divergente"));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
    }

    @Test
    void claimRegistraJournal() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        bs.claim(id, bs.requireApproved(id));
        String j = String.join("\n", s.tail(2));
        assertTrue(j.contains("batch_claimed"), j);
        assertTrue(j.contains(id), j);
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".running.json")));
    }

    // ---------- L1607: rede do markExecuted ----------

    @Test
    void markExecutedSemRedeUsaRedeDaAcao() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(s.batchesDir().resolve(id + ".approved.json")));
        b.remove("network");
        Files.writeString(s.batchesDir().resolve(id + ".approved.json"), Json.writePretty(b), StandardCharsets.UTF_8);
        bs.markExecuted(id, Json.obj());
        assertTrue(String.join("\n", s.tail(2)).contains("\"network\":\"bluesky\""));
    }

    // ---------- L1633/L1649: provas por receita ----------

    @Test
    void promoverProvaMantemReceitasDaRede() throws Exception {
        Store s = store();
        String id1 = new BatchStore(s).draft("p1", Json.parse("[" + reqForte("[200]") + "]"),
                Json.obj().put("receita", "r1").put("provada", true)).get("id").asText();
        new BatchStore(s).approve(id1, "humano", "ok");
        new BatchStore(s).markExecuted(id1, Json.obj().put("conferencia", "forte"));
        String id2 = new BatchStore(s).draft("p2", Json.parse("[" + reqForte("[201]") + "]"),
                Json.obj().put("receita", "r2").put("provada", true)).get("id").asText();
        new BatchStore(s).approve(id2, "humano", "ok");
        new BatchStore(s).markExecuted(id2, Json.obj().put("conferencia", "forte"));
        JsonNode provas = Json.parse(Files.readString(s.batchesDir().getParent().resolve("provas.json")));
        assertEquals(id1, provas.path("bluesky").path("r1").asText());
        assertEquals(id2, provas.path("bluesky").path("r2").asText());
        assertTrue(String.join("\n", s.tail(4)).contains("receita_provada"));
    }

    @Test
    void falhaEmProvasViraEventoNoJournal() throws Exception {
        Store s = store();
        Files.createDirectory(s.batchesDir().getParent().resolve("provas.json"));
        String id = new BatchStore(s).draft("p", Json.parse("[" + reqForte("[200]") + "]"),
                Json.obj().put("receita", "r1").put("provada", true)).get("id").asText();
        new BatchStore(s).approve(id, "humano", "ok");
        new BatchStore(s).markExecuted(id, Json.obj().put("conferencia", "forte"));
        assertTrue(String.join("\n", s.tail(3)).contains("receita_provada_falhou"));
    }

    // ---------- L1670/L1674/L1675: listagem ----------

    @Test
    void listIgnoraArquivosEstranhos() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        Files.writeString(s.batchesDir().resolve("lixo.txt"), "x");
        String id = bs.draft("teste", acoes()).get("id").asText();
        List<String> linhas = bs.list();
        assertEquals(1, linhas.size());
        assertTrue(linhas.get(0).startsWith(id), linhas.toString());
        assertFalse(linhas.toString().contains("ilegivel"), linhas.toString());
    }

    @Test
    void listComBatchesAusenteRetornaVazio() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        Files.delete(s.batchesDir());
        assertTrue(bs.list().isEmpty());
    }

    @Test
    void listMostraRejeitadosEExecutados() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String idOk = bs.draft("ok", acoes()).get("id").asText();
        bs.approve(idOk, "humano", null);
        bs.markExecuted(idOk, Json.obj());
        String idRej = bs.draft("rej", acoes()).get("id").asText();
        bs.reject(idRej, null);
        String todas = String.join("\n", bs.list());
        assertTrue(todas.contains(idOk + "  executed"), todas);
        assertTrue(todas.contains(idRej + "  rejected"), todas);
    }

    // ---------- L162/L181: precedencia das validacoes por acao ----------

    @Test
    void clickModeInvalidoTemPrecedenciaSobreRegraDeRede() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"youtube","kind":"click","selector":"css:a","clickMode":"js"},
                 {"network":"tiktok","kind":"click","selector":"css:b"}]
                """)));
        assertTrue(e.getMessage().contains("clickMode"), e.getMessage());
    }

    // ---------- L504: sufixo puro e curinga curto ----------

    @Test
    void hostCasaNaoAceitaSufixoPuroNemCuringaCurto() {
        assertFalse(BatchStore.hostCasa(".mastodon.social", "*.mastodon.social"));
        assertFalse(BatchStore.hostCasa("a.", "*."));
        assertTrue(BatchStore.hostCasa("x.mastodon.social", "*.mastodon.social"));
    }

    // ---------- L851: xpath relativo so de parenteses ----------

    @Test
    void xpathComParentSoComParentesesRecusadoSemEstourarIndice() {
        BatchStore bs = new BatchStore(store());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(
                "[" + cap(",\"parentSelector\":\"css:main\"")
                        .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:(((\"") + "]")));
        assertTrue(e.getMessage().contains("precisa ser relativo"), e.getMessage());
        assertDoesNotThrow(() -> bs.validate(Json.parse(
                "[" + cap(",\"parentSelector\":\"css:main\"")
                        .replace("\"selector\":\"css:article\"", "\"selector\":\"xpath:(((.//a\"") + "]")));
    }

    // ---------- L1243: copia divergente na aprovacao ----------

    @Test
    void copiaDivergenteNaAprovacaoRelataPassoExato() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String lote = "[{\"network\":\"bluesky\",\"kind\":\"extract\",\"what\":\"links\"},"
                + loteUpload(img.toString(), CLIQUE).substring(1);
        String id = bs.draft("divergente", Json.parse(lote)).get("id").asText();
        bs.aposCopiarMidia = p -> {
            try {
                Files.write(p, new byte[]{9, 9});
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        assertEquals("copia da midia do passo 2 divergiu na aprovacao", e.getMessage());
        try (var fs = Files.list(s.batchesDir())) {
            assertFalse(fs.anyMatch(p -> p.getFileName().toString().startsWith(id + "-media-")));
        }
    }

    // ---------- L1277: indices fora de ordem na midia multipla ----------

    @Test
    void midiaMultiplaComIndicesForaDeOrdemRecusada() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path a = Files.write(raiz.resolve("a.png"), new byte[]{1, 2});
        Path b2 = Files.write(raiz.resolve("b.png"), new byte[]{3, 4});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUploads(List.of(a.toString(), b2.toString()), CLIQUE)))
                .get("id").asText();
        bs.approve(id, "humano", null, List.of(), false, true);
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode bb = (ObjectNode) Json.parse(Files.readString(ap));
        ((ObjectNode) bb.path("midias").get(0)).put("indice", 1);
        ((ObjectNode) bb.path("midias").get(1)).put("indice", 2);
        Files.writeString(ap, Json.writePretty(bb), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("passo 1"), e.getMessage());
        assertTrue(e.getMessage().contains("sem digest"), e.getMessage());
        assertTrue(String.join("\n", s.tail(3)).contains("midia_sem_hash"));
    }

    // ---------- L1342: idade da aprovacao no limite exato ----------

    @Test
    void idadeDaAprovacaoLimiteExatoAceitaEUmMsDepoisRecusa() throws Exception {
        Store s = store();
        long agora = 1_800_000_000_000L;
        BatchStore bs = new BatchStore(s, () -> agora);
        String id = bs.draft("idade", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Path ap = s.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(ap));
        b.put("approvedAt", agora - 24L * 3_600_000L);
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> bs.requireApproved(id));
        b.put("approvedAt", agora - 24L * 3_600_000L - 1);
        Files.writeString(ap, Json.writePretty(b), StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.requireApproved(id));
        assertTrue(e.getMessage().contains("venceu (24h)"), e.getMessage());
    }

    // ---------- L1466: draft alterado no meio da aprovacao ----------

    @Test
    void approveComDraftAlteradoNoMeioLimpaCopias() throws Exception {
        Path raiz = Files.createDirectories(tmp.resolve("marketing"));
        Path img = Files.write(raiz.resolve("card.png"), new byte[]{1, 2, 3});
        Store s = storeUpload(raiz, null);
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("up", Json.parse(loteUpload(img.toString(), CLIQUE))).get("id").asText();
        bs.antesDeConfirmarAprovacao = () -> {
            try {
                Path draft = s.batchesDir().resolve(id + ".draft.json");
                Files.writeString(draft, Files.readString(draft) + "\n", StandardCharsets.UTF_8);
            } catch (java.io.IOException ignored) {
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.approve(id, "humano", null));
        assertTrue(e.getMessage().contains("mudou durante a aprovacao"), e.getMessage());
        try (var fs = Files.list(s.batchesDir())) {
            assertFalse(fs.anyMatch(p -> p.getFileName().toString().startsWith(id + "-media-")));
        }
    }

    // ---------- L1475: aprovador no evento ensaio_autorizado ----------

    @Test
    void ensaioAutorizadoRegistraAprovadorExatoNoJournal() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, null, null, List.of(), false, true);
        String j = String.join("\n", s.tail(2));
        assertTrue(j.contains("ensaio_autorizado"), j);
        assertTrue(j.contains("\"aprovador\":\"\""), j);
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id2, "orquestrador", null, List.of(), false, true);
        String j2 = String.join("\n", s.tail(1));
        assertTrue(j2.contains("ensaio_autorizado"), j2);
        assertTrue(j2.contains("\"aprovador\":\"orquestrador\""), j2);
    }

    // ---------- L1514: reject com lote reservado e aprovado ----------

    @Test
    void rejectComLoteReservadoEAprovadoRecusaAntesDeRevogar() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Files.writeString(s.batchesDir().resolve(id + ".running.json"), "{}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.reject(id, null));
        assertTrue(e.getMessage().contains("em execucao"), e.getMessage());
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".approved.json")));
    }

    // ---------- L1534: nota da revogacao ----------

    @Test
    void rejectDeLoteAprovadoRegistraNotaNoJournal() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        bs.reject(id, null);
        String j = String.join("\n", s.tail(2));
        assertTrue(j.contains("batch_revoked"), j);
        assertTrue(j.contains("\"nota\":\"\""), j);
        assertEquals("ok", Json.parse(Files.readString(s.batchesDir().resolve(id + ".rejected.json"))).path("nota").asText(),
                "revogacao sem nota nova preserva a nota da aprovacao");
        String id2 = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id2, "humano", "ok");
        bs.reject(id2, "revogada pelo dono");
        String j2 = String.join("\n", s.tail(1));
        assertTrue(j2.contains("batch_revoked"), j2);
        assertTrue(j2.contains("revogada pelo dono"), j2);
        assertEquals("revogada pelo dono",
                Json.parse(Files.readString(s.batchesDir().resolve(id2 + ".rejected.json"))).path("nota").asText());
    }

    // ---------- rodada 3: hostCasa, corrida da revogacao ----------

    @Test
    void curingaNaoCasaOProprioSufixoComPonto() {
        assertFalse(BatchStore.hostCasa(".example.com", "*.example.com"), "host igual ao sufixo nao e subdominio");
        assertTrue(BatchStore.hostCasa("a.example.com", "*.example.com"));
    }

    @Test
    void curingaSoComPontoNaoCasaNada() {
        assertFalse(BatchStore.hostCasa("a.", "*."), "\"*.\" sem sufixo real nao pode liberar host nenhum");
        assertFalse(BatchStore.hostCasa(".bsky.app", "*.bsky.app"), "host igual ao sufixo (sem rotulo) nao casa");
        assertTrue(BatchStore.hostCasa("a.bsky.app", "*.bsky.app"));
    }

    @Test
    void revogacaoQuePerdeACorridaParaAExecucaoAvisaQueEstaEmExecucao() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Path aprovado = s.batchesDir().resolve(id + ".approved.json");
        bs.antesDeRevogar = () -> {
            try {
                Files.move(aprovado, s.batchesDir().resolve(id + ".running.json"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.reject(id, null));
        assertEquals("lote " + id + " esta em execucao; para parar use o kill-switch (stop on)", e.getMessage());
    }

    @Test
    void revogacaoDeLoteQueSumiuNoMeioAvisaQueDeixouDeEstarAprovado() throws Exception {
        Store s = store();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("teste", acoes()).get("id").asText();
        bs.approve(id, "humano", "ok");
        Path aprovado = s.batchesDir().resolve(id + ".approved.json");
        bs.antesDeRevogar = () -> {
            try {
                Files.delete(aprovado);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bs.reject(id, null));
        assertEquals("lote " + id + " deixou de estar aprovado (executado, revogado ou reservado por outro processo)",
                e.getMessage());
    }
}
