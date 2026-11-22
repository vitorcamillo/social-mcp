package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Mata os mutantes vivos do PIT em Recipes (prepare/validateReadOnly/expandir/dentroDeStringJson/expand/xpathLiteral/label). */
class RecipesMutantesTest {

    @TempDir
    Path tmp;

    private Store lojaComReceita(String rede, String acoes) throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(rede + ".json"),
                "{\"network\":\"" + rede + "\",\"actions\":{" + acoes + "}}", StandardCharsets.UTF_8);
        return s;
    }

    private static List<String> comoLista(JsonNode no) {
        return Json.M.convertValue(no,
                Json.M.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static String acaoReadOnly(String passos) {
        return "\"list_posts\":{\"readOnly\":true,\"steps\":[" + passos + "]}";
    }

    private static final String GOTO = "{\"kind\":\"goto\",\"url\":\"https://mastodon.social/x\"}";

    // ---------- prepare: fronteira do regex (L170) ----------

    @Test
    void regexDeExatamente200CharsPassaE201Recusado() throws Exception {
        String r200 = "a".repeat(200);
        Store s = lojaComReceita("mastodon", "\"get_post_refs\":{\"params\":[\"commentId\"],"
                + "\"paramPatterns\":{\"commentId\":{\"regex\":\"" + r200 + "\"}},"
                + "\"steps\":[" + GOTO.replace("https://mastodon.social/x",
                        "https://mastodon.social/x/{{commentId}}") + "]}");
        Recipes.Plan p = new Recipes(s).prepare("mastodon", "get_post_refs",
                Json.obj().put("commentId", r200));
        assertTrue(p.steps().get(0).get("url").asText().endsWith(r200));
        String r201 = "a".repeat(201);
        Store s2 = lojaComReceita("mastodon", "\"get_post_refs\":{\"params\":[\"commentId\"],"
                + "\"paramPatterns\":{\"commentId\":{\"regex\":\"" + r201 + "\"}},"
                + "\"steps\":[" + GOTO + "]}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s2).prepare("mastodon", "get_post_refs", Json.obj().put("commentId", "a")));
        assertTrue(e.getMessage().contains("acima de 200"), e.getMessage());
    }

    // ---------- prepare: truncamento do valor em 60 chars (L183) ----------

    @Test
    void valorDe60CharsNaoTruncaE61Trunca() throws Exception {
        String receita = "\"get_post_refs\":{\"params\":[\"commentId\"],"
                + "\"paramPatterns\":{\"commentId\":{\"regex\":\"z+\"}},\"steps\":[" + GOTO + "]}";
        String v60 = "a".repeat(60);
        IllegalArgumentException e60 = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(lojaComReceita("mastodon", receita))
                        .prepare("mastodon", "get_post_refs", Json.obj().put("commentId", v60)));
        assertTrue(e60.getMessage().contains("valor: '" + v60 + "'"), e60.getMessage());
        assertFalse(e60.getMessage().contains("..."), e60.getMessage());
        String v61 = "a".repeat(61);
        IllegalArgumentException e61 = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(lojaComReceita("mastodon", receita))
                        .prepare("mastodon", "get_post_refs", Json.obj().put("commentId", v61)));
        assertTrue(e61.getMessage().contains("valor: '" + "a".repeat(60) + "...'"), e61.getMessage());
    }

    // ---------- prepare readOnly: par capture/verify exigido (L208) ----------

    @Test
    void readOnlyComVerifyNewIdSemCaptureRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ","
                + "{\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"posts\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("sem capture_ids anterior"), e.getMessage());
    }

    // ---------- validateReadOnly: cada kind invalidado (L227-L232) ----------

    @Test
    void readOnlyComVerifyTextSemExpectRecusado() throws Exception {
        Store s = lojaComReceita("mastodon",
                acaoReadOnly(GOTO + ",{\"kind\":\"verify_text\",\"selector\":\"css:a\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("verify_text exige expect"), e.getMessage());
    }

    @Test
    void readOnlyComExtractInvalidoRecusado() throws Exception {
        Store s = lojaComReceita("mastodon",
                acaoReadOnly(GOTO + ",{\"kind\":\"extract\",\"what\":\"bogus\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("extract exige what"), e.getMessage());
    }

    @Test
    void readOnlyComVerifyAttrSemAttrRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ","
                + "{\"kind\":\"verify_attr\",\"selector\":\"css:a\",\"attr\":\"\",\"expect\":\"x\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("verify_attr exige attr"), e.getMessage());
    }

    @Test
    void readOnlyComVerifyAbsentSemSelectorRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ",{\"kind\":\"verify_absent\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("verify_absent exige selector"), e.getMessage());
    }

    @Test
    void readOnlyComCaptureIdsSemAttrRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ","
                + "{\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"\",\"name\":\"posts\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("capture_ids exige attr"), e.getMessage());
    }

    @Test
    void readOnlyComVerifyNewIdSemAttrRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ","
                + "{\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"\",\"name\":\"posts\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("verify_new_id exige attr"), e.getMessage());
    }

    // ---------- validateReadOnly: request so com GET (L235, L238) e indice do passo (L243) ----------

    @Test
    void readOnlyAceitaRequestGetERecusaPost() throws Exception {
        String get = "{\"kind\":\"request\",\"method\":\"GET\",\"url\":\"https://mastodon.social/api/v1/timelines/home\"}";
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + "," + get));
        assertDoesNotThrow(() -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        String post = "{\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://mastodon.social/api/v1/timelines/home\"}";
        Store s2 = lojaComReceita("mastodon", acaoReadOnly(GOTO + "," + post));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s2).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("so com GET"), e.getMessage());
    }

    @Test
    void readOnlyComRequestGetParaHostEstranhoRecusado() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ","
                + "{\"kind\":\"request\",\"method\":\"GET\",\"url\":\"https://evil.example/x\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("nao pertence"), e.getMessage());
    }

    @Test
    void erroDoSegundoPassoCitaOIndiceUm() throws Exception {
        Store s = lojaComReceita("mastodon", acaoReadOnly(GOTO + ",{\"kind\":\"verify_text\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "list_posts", Json.obj()));
        assertTrue(e.getMessage().contains("acao 1"), e.getMessage());
        assertFalse(e.getMessage().contains("-1"), e.getMessage());
    }

    // ---------- expandir paths: array, item nao textual e linha vazia (L278, L280, L282) ----------

    private Store lojaSetFile(String pathsJson) throws Exception {
        return lojaComReceita("mastodon", "\"create_post\":{\"params\":[\"a\",\"b\"],\"steps\":["
                + "{\"kind\":\"set_file\",\"selector\":\"css:input\",\"paths\":" + pathsJson + "}]}");
    }

    @Test
    void pathsEmArrayExpandemCadaItem() throws Exception {
        Store s = lojaSetFile("[\"{{a}}\",\"{{b}}\"]");
        JsonNode passo = new Recipes(s).prepare("mastodon", "create_post",
                Json.obj().put("a", "x.jpg").put("b", "y.jpg")).steps().get(0);
        assertEquals(List.of("x.jpg", "y.jpg"), comoLista(passo.path("paths")));
    }

    @Test
    void pathsEmArrayIgnoramLinhaVazia() throws Exception {
        Store s = lojaSetFile("[\" {{a}} \",\"   \",\"{{b}}\"]");
        JsonNode passo = new Recipes(s).prepare("mastodon", "create_post",
                Json.obj().put("a", "x.jpg").put("b", "y.jpg")).steps().get(0);
        assertEquals(List.of("x.jpg", "y.jpg"), comoLista(passo.path("paths")));
    }

    // ---------- texto do gate: url com placeholder gera text; sem placeholder nao (L304) ----------

    @Test
    void requestComPlaceholderSoNaUrlGeraTextoESemPlaceholderNao() throws Exception {
        Store s = lojaComReceita("mastodon",
                "\"create_post\":{\"params\":[\"text\"],\"textField\":\"text\",\"steps\":["
                        + "{\"kind\":\"request\",\"method\":\"POST\","
                        + "\"url\":\"https://mastodon.social/api/{{text}}\",\"body\":\"{\\\"a\\\":\\\"1\\\"}\"}]},"
                        + "\"reply\":{\"params\":[\"text\"],\"textField\":\"text\",\"steps\":["
                        + "{\"kind\":\"request\",\"method\":\"POST\","
                        + "\"url\":\"https://mastodon.social/api/x\",\"body\":\"{}\"}]}");
        JsonNode comUrl = new Recipes(s).prepare("mastodon", "create_post",
                Json.obj().put("text", "ola")).steps().get(0);
        assertEquals("ola", comUrl.get("text").asText());
        JsonNode semNada = new Recipes(s).prepare("mastodon", "reply",
                Json.obj().put("text", "ola")).steps().get(0);
        assertFalse(semNada.has("text"), semNada.toString());
    }

    // ---------- dentroDeStringJson (L313, L315, L318, L325, L328, L329, L335) ----------

    @Test
    void foraDeJsonNaoEDentroDeStringJson() {
        assertFalse(Recipes.dentroDeStringJson("q={{x}}", 2));
    }

    @Test
    void valorJsonComAspasImparEDentroDeStringJson() {
        // campoIni=0, igual=0 ('=' no indice 0): valor correto exclui o '=' e comeca com '{'.
        assertTrue(Recipes.dentroDeStringJson("={\"a {{x}}", 5));
    }

    @Test
    void aspasEscapadaComBarraNaoConta() {
        // '"' precedido de '\' nao conta; o mutante i+1 olharia o ':' seguinte e contaria.
        assertFalse(Recipes.dentroDeStringJson("{a\\\":{{x}}", 5));
    }

    @Test
    void percentual22NaoEscapadoContaComoAspas() {
        assertTrue(Recipes.dentroDeStringJson("{a%22{{x}}", 5));
    }

    @Test
    void percentual22EscapadoComBarraNaoConta() {
        // "%22" precedido de '\' e escapado; o mutante i+1 olharia o '2' seguinte e contaria.
        assertFalse(Recipes.dentroDeStringJson("{\\%22{{x}}", 5));
    }

    // ---------- dentroDeStringJson reescrita: cada prefixo, fronteira de campo, paridade ----------

    private static int iniDe(String template) {
        int ini = template.indexOf("{{");
        assertTrue(ini >= 0, "sem placeholder em " + template);
        return ini;
    }

    @Test
    void jsonComColcheteImparEDentro() {
        String t = "a=[\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void jsonComChaveParNaoEDentro() {
        String t = "a={\"x\" {{p}}";
        assertFalse(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void jsonComTresAspasImparEDentro() {
        String t = "a={\"\"\"{{p}}";
        assertTrue(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void valorPercent7bMaiusculoEMinusculoSaoJson() {
        String minusc = "a=%7b\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(minusc, iniDe(minusc)));
        String maiusc = "a=%7B\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(maiusc, iniDe(maiusc)));
    }

    @Test
    void valorPercent5bMaiusculoEMinusculoSaoJson() {
        String minusc = "a=%5b\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(minusc, iniDe(minusc)));
        String maiusc = "a=%5B\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(maiusc, iniDe(maiusc)));
    }

    @Test
    void valorSemPrefixoJsonNaoEDentro() {
        String t = "a=xyz\"{{p}}";
        assertFalse(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void igualNoInicioDoCampoComChaveEDentro() {
        // campo comeca com '=' (igual == campoIni): o valor exclui o '=' e comeca com '{'.
        String t = "={\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void variosEcomerciaisUsamSoOUltimoCampo() {
        String t = "a=1&b=2&c={\"x {{p}}";
        assertTrue(Recipes.dentroDeStringJson(t, iniDe(t)));
        // '=' anterior ao '&' pertence ao campo anterior e e ignorado; campo atual vazio.
        String u = "a={\"x&b={{p}}";
        assertFalse(Recipes.dentroDeStringJson(u, iniDe(u)));
    }

    @Test
    void percentual22SeguidoDeOutroPercent22ContaPar() {
        String t = "{%22%22{{p}}";
        assertFalse(Recipes.dentroDeStringJson(t, iniDe(t)));
    }

    @Test
    void percentual22EscapadoPor5cMaiusculoEMinusculoNaoConta() {
        String minusc = "{%5c%22{{p}}";
        assertFalse(Recipes.dentroDeStringJson(minusc, iniDe(minusc)));
        String maiusc = "{%5C%22{{p}}";
        assertFalse(Recipes.dentroDeStringJson(maiusc, iniDe(maiusc)));
    }

    // ---------- expand e fronteiras de fim de template (L350, L352, L354) ----------

    @Test
    void placeholderNoFimDoTemplateExpandeSemEstourar() {
        assertEquals("v", Recipes.expand("{{x}}", Json.obj().put("x", "v"), Recipes.Ctx.FORM));
        assertEquals("a'v", Recipes.expand("a'{{x}}", Json.obj().put("x", "v"), Recipes.Ctx.LITERAL));
    }

    @Test
    void placeholderNoFimComAspaAbertaCodificaForaDeAspas() {
        // fim == length: sem aspa fechando nao lanca e codifica como fora de aspas (sem JSON-escape).
        JsonNode params = Json.obj().put("t", "x\"y");
        assertEquals("a=\"x%22y", Recipes.expand("a=\"{{t}}", params, Recipes.Ctx.FORM));
        // com aspa fechando, o mesmo valor leva JSON-escape antes do URL-encode.
        assertEquals("a=\"x%5C%22y\"", Recipes.expand("a=\"{{t}}\"", params, Recipes.Ctx.FORM));
    }

    @Test
    void xpathComApostrofoNoInicioDoValorViraLiteralSeguro() {
        assertEquals("xpath://p[contains(.,\"'abc\")]",
                Recipes.expand("xpath://p[contains(.,'{{c}}')]", Json.obj().put("c", "'abc"),
                        Recipes.Ctx.XPATH));
    }

    // ---------- xpathLiteral (L377, L378) ----------

    @Test
    void literalXpathCobreOsTresCasosDeAspas() {
        assertEquals("'abc'", Recipes.xpathLiteral("abc"));
        assertEquals("\"d'água\"", Recipes.xpathLiteral("d'água"));
        // apóstrofo no indice 0: sem ele o mutante embrulharia com aspas simples quebradas.
        assertEquals("\"'abc\"", Recipes.xpathLiteral("'abc"));
        // '"' no indice 0 com apóstrofo junto: so o concat escapa os dois.
        assertEquals("concat('\"a', \"'\", 'b')", Recipes.xpathLiteral("\"a'b"));
    }

    // ---------- label: fronteira de 40 chars (L410) ----------

    @Test
    void rotuloCom40CharsNaoTruncaE41Trunca() {
        assertEquals("n a k=" + "x".repeat(40),
                Recipes.label("n", "a", Json.obj().put("k", "x".repeat(40))));
        assertEquals("n a k=" + "x".repeat(40) + "...",
                Recipes.label("n", "a", Json.obj().put("k", "x".repeat(41))));
    }
}
