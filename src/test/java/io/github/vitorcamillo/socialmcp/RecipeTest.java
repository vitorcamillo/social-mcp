package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RecipeTest {

    @TempDir
    Path tmp;

    /** Store com gate que passa e a receita de exemplo do pinterest instalada. */
    private Store storeComReceita() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        try (InputStream in = RecipeTest.class.getResourceAsStream("/recipes/pinterest.json")) {
            assertNotNull(in, "receita de exemplo ausente em src/test/resources");
            Files.copy(in, dir.resolve("pinterest.json"));
        }
        return s;
    }

    private long lotes(Store s) throws Exception {
        try (Stream<Path> l = Files.list(s.batchesDir())) {
            return l.count();
        }
    }

    @Test
    void formEscapaPlaceholdersDentroDeStringJsonComPrefixo() {
        String raw = "a \"b\" & c\\d";
        JsonNode params = Json.obj().put("text", raw).put("num", "42");
        for (String template : List.of(
                "data={\"text\":\"prefixo {{text}} sufixo\"}",
                "data=%7B%22text%22%3A%22prefixo%20{{text}}%22%7D")) {
            String expanded = Recipes.expand(template, params, Recipes.Ctx.FORM);
            String form = URLDecoder.decode(expanded.substring(expanded.indexOf('=') + 1), StandardCharsets.UTF_8);
            JsonNode json = Json.parse(form);
            assertEquals(template.contains("sufixo") ? "prefixo " + raw + " sufixo" : "prefixo " + raw,
                    json.path("text").asText());
        }
        assertEquals("\"" + Recipes.urlEncode(Recipes.jsonEscape(raw)) + "\"",
                Recipes.expand("\"{{text}}\"", params, Recipes.Ctx.FORM));
        assertEquals("q=" + Recipes.urlEncode(raw),
                Recipes.expand("q={{text}}", params, Recipes.Ctx.FORM));
        assertEquals("data={\"n\":" + Recipes.urlEncode("42") + "}",
                Recipes.expand("data={\"n\":{{num}}}", params, Recipes.Ctx.FORM));
    }

    @Test
    void expandeComEscapePorContexto() throws Exception {
        Store s = storeComReceita();
        Recipes r = new Recipes(s);
        String id = "12 3&x=\"y\"";
        Recipes.Plan p = r.prepare("pinterest", "delete_post", Json.obj().put("postId", id));
        assertFalse(p.readOnly());
        JsonNode st = p.steps();
        assertEquals(5, st.size());
        // url: URL-encode
        assertEquals("https://br.pinterest.com/pin/12%203%26x%3D%22y%22/", st.get(0).get("url").asText());
        // expect: literal
        assertEquals(id, st.get(1).get("expect").asText());
        // body de formulario: URL-encode; dentro das aspas JSON: JSON-escape + URL-encode
        String body = st.get(2).get("body").asText();
        assertTrue(body.startsWith("source_url=/pin/12%203%26x%3D%22y%22/&data="), body);
        assertTrue(body.contains("\"id\":\"12%203%26x%3D%5C%22y%5C%22\""), body);
        // toda etapa recebe a rede; headers preservados
        for (JsonNode a : st) assertEquals("pinterest", a.get("network").asText());
        assertEquals("XMLHttpRequest", st.get(2).get("headers").get("x-requested-with").asText());
        assertTrue(p.label().startsWith("pinterest delete_post postId="));
    }

    @Test
    void pathsTextualExpandeLinhasEmArray() throws Exception {
        Store s = storeComReceita();
        Path dir = s.home.resolve("recipes");
        Files.writeString(dir.resolve("bluesky.json"), """
                {"network":"bluesky","actions":{"create_post":{"params":["arquivos"],"steps":[
                  {"kind":"set_file","selector":"css:input[type=file]","paths":"{{arquivos}}"},
                  {"kind":"click","selector":"css:button"}
                ]}}}
                """);
        JsonNode steps = new Recipes(s).prepare("bluesky", "create_post", Json.obj()
                .put("arquivos", "a.jpg\n b.jpg \n\n")).steps();
        assertEquals(List.of("a.jpg", "b.jpg"), Json.M.convertValue(steps.get(0).path("paths"),
                Json.M.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void bodyJsonUsaJsonEscapeETextoLiteralParaOGate() throws Exception {
        Store s = storeComReceita();
        String texto = "Bora \"Fiel\"\nlinha 2";
        Recipes.Plan p = new Recipes(s).prepare("pinterest", "reply",
                Json.obj().put("postId", "9").put("text", texto));
        JsonNode req = p.steps().get(1);
        assertEquals("{\"pin\":\"9\",\"text\":\"Bora \\\"Fiel\\\"\\nlinha 2\"}", req.get("body").asText());
        JsonNode corpo = Json.parse(req.get("body").asText());
        assertEquals(texto, corpo.get("text").asText());
        // o passo request leva o texto humano (campo text) para o gate
        assertEquals(texto, req.get("text").asText());
        assertEquals(texto, p.steps().get(2).get("expect").asText());
    }

    @Test
    void receitaOuAcaoAusenteDaErroClaro() throws Exception {
        Store s = storeComReceita();
        Recipes r = new Recipes(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.prepare("bluesky", "delete_post", Json.obj().put("postId", "1")));
        assertTrue(e.getMessage().contains("mapear-rede.sh"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> r.prepare("pinterest", "follow", Json.obj()));
        assertTrue(e.getMessage().contains("nao mapeada para a rede 'pinterest'"), e.getMessage());
        assertTrue(e.getMessage().contains("mapear-rede.sh"));
        // acao fora da lista e rede desconhecida (nem chega ao disco)
        assertThrows(IllegalArgumentException.class, () -> r.prepare("pinterest", "../x", Json.obj()));
        assertThrows(IllegalArgumentException.class, () -> r.prepare("../etc", "list_posts", Json.obj()));
    }

    @Test
    void paramFaltandoExtraOuTextoVazioRecusados() throws Exception {
        Recipes r = new Recipes(storeComReceita());
        assertThrows(IllegalArgumentException.class, () -> r.prepare("pinterest", "delete_post", Json.obj()));
        assertThrows(IllegalArgumentException.class, () -> r.prepare("pinterest", "delete_post",
                Json.obj().put("postId", "1").put("outro", "2")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.prepare("pinterest", "reply",
                Json.obj().put("postId", "1").put("text", "  ")));
        assertTrue(e.getMessage().contains("texto vazio"));
    }

    @Test
    void readOnlyNaoGeraLote() throws Exception {
        Store s = storeComReceita();
        Recipes.Plan p = new Recipes(s).prepare("pinterest", "list_posts", Json.obj());
        assertTrue(p.readOnly());
        assertEquals("extract", p.steps().get(1).get("kind").asText());
        assertEquals(0, lotes(s));
        // receita readOnly com passo publico e recusada
        assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("pinterest", "get_post_ruim", Json.obj().put("postId", "1")));
    }

    @Test
    void findDuplicatesRecusaReceitaListPostsSemReadOnly() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        // list_posts SEM readOnly (com passo publico): prepare aceita, a tool tem que recusar
        Files.writeString(dir.resolve("pinterest.json"), """
                {"network":"pinterest","actions":{
                  "list_posts":{"params":[],"steps":[
                    {"kind":"goto","url":"https://br.pinterest.com/"},
                    {"kind":"click","selector":"css:button[data-test-id=ver-mais]"}]}}}
                """);
        assertFalse(new Recipes(s).prepare("pinterest", "list_posts", Json.obj()).readOnly());
        Tools t = new Tools(s);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.call(
                    "social_find_duplicates", Json.parse("{\"network\":\"pinterest\"}")));
            assertTrue(e.getMessage().contains("readOnly"), e.getMessage());
        } finally {
            t.shutdown();
        }
    }

    @Test
    void acaoPublicaViraRascunhoViaTool() throws Exception {
        Store s = storeComReceita();
        Tools t = new Tools(s);
        try {
            JsonNode out = Json.parse(t.call("social_action", Json.parse(
                    "{\"network\":\"pinterest\",\"action\":\"delete_post\",\"params\":{\"postId\":\"42\"}}")));
            String id = out.get("batchId").asText();
            assertEquals("draft", out.get("status").asText());
            assertTrue(out.get("PROXIMO_PASSO").asText().contains("bin/social-mcp --cli approve " + id));
            assertEquals(5, out.get("preflight").size());
            Path draft = s.batchesDir().resolve(id + ".draft.json");
            assertTrue(Files.exists(draft));
            JsonNode b = Json.parse(Files.readString(draft));
            assertEquals("pinterest delete_post postId=42", b.get("label").asText());
            assertEquals("request", b.get("actions").get(2).get("kind").asText());
            // rede fora de healthy nao aceita social_action
            s.setState("pinterest", "cooldown", "teste", "teste");
            assertThrows(IllegalStateException.class, () -> t.call("social_action", Json.parse(
                    "{\"network\":\"pinterest\",\"action\":\"delete_post\",\"params\":{\"postId\":\"42\"}}")));
        } finally {
            t.shutdown();
        }
    }

    @Test
    void toolSocialActionDeclarada() throws Exception {
        Tools t = new Tools(TextGateTest.storeComGate(tmp, "exit 0", 30));
        boolean achou = false;
        for (JsonNode d : t.definitions()) achou |= "social_action".equals(d.path("name").asText());
        assertTrue(achou);
    }

    private static final java.util.regex.Pattern ISO_MS =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final java.util.regex.Pattern UUID4 =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private Store storeBluesky() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), """
                {"network":"bluesky","actions":{
                  "create_post":{"params":["text"],"textField":"text","steps":[
                    {"kind":"request","method":"POST",
                     "url":"https://morel.us-east.host.bsky.network/xrpc/com.atproto.repo.createRecord",
                     "headers":{"content-type":"application/json","idempotency-key":"{{uuid}}"},
                     "auth":{"header":"authorization","prefix":"Bearer ","from":"localStorage","key":"BSKY_STORAGE","path":"session.currentAccount.accessJwt"},
                     "body":"{\\"rkey\\":\\"{{uuid}}\\",\\"text\\":\\"{{text}}\\",\\"createdAt\\":\\"{{now}}\\",\\"dia\\":\\"{{today}}\\"}",
                     "description":"post {{today}}"},
                    {"kind":"verify_text","expect":"{{uuid}}"}
                  ]},
                  "reply":{"params":["now"],"steps":[{"kind":"goto","url":"https://bsky.app/"}]}
                }}
                """);
        return s;
    }

    @Test
    void placeholdersGeradosNowUuidToday() throws Exception {
        Store s = storeBluesky();
        Recipes.Plan p = new Recipes(s).prepare("bluesky", "create_post", Json.obj().put("text", "Oi \"Fiel\""));
        JsonNode req = p.steps().get(0);
        JsonNode corpo = Json.parse(req.get("body").asText());
        String uuid = corpo.get("rkey").asText();
        assertTrue(UUID4.matcher(uuid).matches(), uuid);
        // mesmo uuid em todas as ocorrencias do passo (body e header)
        assertEquals(uuid, req.get("headers").get("idempotency-key").asText());
        assertTrue(ISO_MS.matcher(corpo.get("createdAt").asText()).matches(), corpo.get("createdAt").asText());
        assertEquals(s.today(), corpo.get("dia").asText());
        assertEquals("post " + s.today(), req.get("description").asText());
        assertEquals("Oi \"Fiel\"", corpo.get("text").asText());
        // auth copiado intacto; o passo seguinte gera outro uuid
        assertEquals("localStorage", req.get("auth").get("from").asText());
        String outro = p.steps().get(1).get("expect").asText();
        assertTrue(UUID4.matcher(outro).matches());
        assertNotEquals(uuid, outro);
        // o draft aceita o passo com auth + host *.host.bsky.network
        assertDoesNotThrow(() -> new BatchStore(s).draft(p.label(), p.steps()));
    }

    @Test
    void nowComMilissegundosMesmoEmSegundoCheio() {
        JsonNode g = Recipes.comGerados(Json.obj(), java.time.Instant.parse("2026-10-21T12:00:00Z"), "2026-10-21");
        assertEquals("2026-10-21T12:00:00.000Z", g.get("now").asText());
        assertEquals("2026-10-21", g.get("today").asText());
        assertEquals("2026-10-21T12:00:00.000Z", Recipes.expand("{{now}}", g, Recipes.Ctx.LITERAL));
    }

    @Test
    void paramComNomeReservadoRecusado() throws Exception {
        Recipes r = new Recipes(storeBluesky());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.prepare("bluesky", "reply", Json.obj().put("now", "x")));
        assertTrue(e.getMessage().contains("reservado"), e.getMessage());
    }

    @Test
    void acoesNovasAceitasEReadOnlySoPelaReceita() throws Exception {
        Store s = storeComReceita();
        Files.writeString(s.home.resolve("recipes").resolve("x.json"), """
                {"network":"x","actions":{
                  "read_inbox":{"params":[],"readOnly":true,"steps":[
                    {"kind":"goto","url":"https://x.com/i/chat"},
                    {"kind":"wait","expect":"Chat","timeoutMs":15000},
                    {"kind":"extract","what":"links","match":"/i/chat/"},
                    {"kind":"screenshot","description":"inbox"}]},
                  "get_profile_refs":{"params":[],"steps":[{"kind":"goto","url":"https://x.com/example_account"}]},
                  "list_videos":{"params":[],"readOnly":true,"steps":[{"kind":"wait","ms":30001}]},
                  "read_mentions":{"params":[],"readOnly":true,"steps":[
                    {"kind":"wait","expect":"a","timeoutMs":660000},{"kind":"wait","expect":"b","timeoutMs":660000}]}
                }}
                """);
        Recipes r = new Recipes(s);
        Recipes.Plan p = r.prepare("x", "read_inbox", Json.obj());
        assertTrue(p.readOnly());
        assertEquals("screenshot", p.steps().get(3).get("kind").asText());
        // nome get_* sem readOnly na receita nao vira readOnly
        assertFalse(r.prepare("x", "get_profile_refs", Json.obj()).readOnly());
        // wait invalido em receita readOnly recusado
        assertThrows(IllegalArgumentException.class, () -> r.prepare("x", "list_videos", Json.obj()));
        // readOnly tambem respeita a soma de esperas por chamada (2 x 11 min > 12 min)
        IllegalArgumentException orc = assertThrows(IllegalArgumentException.class, () -> r.prepare("x", "read_mentions", Json.obj()));
        assertTrue(orc.getMessage().contains("720000"), orc.getMessage());
        for (String a : List.of("list_posts", "list_videos", "list_community", "get_post", "get_profile", "read_inbox",
                "read_notifications", "read_mentions", "read_comments", "create_post", "community_post", "edit_post",
                "delete_post", "reply", "follow", "like", "unfollow", "unlike", "get_post_refs", "read_inbox_v2",
                // nomes das receitas em uso (22/10) que o padrao de 1 sufixo recusava
                "reply_comment_ui", "follow_private_ui", "delete_post_modal_ui", "get_post_comments_api", "list_reels",
                "read_requests", "create_photo_post_ui", "delete_community_post_ui", "edit_title_ui")) {
            assertTrue(Recipes.ACTION.matcher(a).matches(), a);
        }
        for (String a : List.of("read", "get_", "list_posts_", "delete_all", "READ_INBOX", "unlike-x", "reply_a_b_c_d", "reply__ui")) {
            assertFalse(Recipes.ACTION.matcher(a).matches(), a);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.prepare("x", "apagar", Json.obj()));
        assertTrue(e.getMessage().contains("read_inbox") && e.getMessage().contains("unlike"), e.getMessage());
    }

    @Test
    void xpathAceitaApostrofoNoParam() {
        String sel = "xpath://p[contains(.,'{{confere}}')]";
        assertEquals("xpath://p[contains(.,'sem aspa')]",
                Recipes.expand(sel, Json.obj().put("confere", "sem aspa"), Recipes.Ctx.XPATH));
        assertEquals("xpath://p[contains(.,\"d'água\")]",
                Recipes.expand(sel, Json.obj().put("confere", "d'água"), Recipes.Ctx.XPATH));
        assertEquals("xpath://p[contains(.,concat('ele disse \"oi\" e ', \"'\", 'tchau'))]",
                Recipes.expand(sel, Json.obj().put("confere", "ele disse \"oi\" e 'tchau"), Recipes.Ctx.XPATH));
        // fora de apostrofos o valor continua literal; seletor css nao muda
        assertEquals("xpath://a[@x=a'b]",
                Recipes.expand("xpath://a[@x={{y}}]", Json.obj().put("y", "a'b"), Recipes.Ctx.XPATH));
    }

    @Test
    void literalXpath() {
        assertEquals("'abc'", Recipes.xpathLiteral("abc"));
        assertEquals("\"d'água\"", Recipes.xpathLiteral("d'água"));
        assertEquals("concat('a\"', \"'\", 'b')", Recipes.xpathLiteral("a\"'b"));
    }

    @Test
    void readOnlyAceitaCaptureIdsEVerifyNewId() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mastodon.json"), """
                {"network":"mastodon","actions":{
                  "list_posts":{"readOnly":true,"params":[],"steps":[
                    {"kind":"goto","url":"https://mastodon.social/home"},
                    {"kind":"capture_ids","selector":"css:article[data-id]","attr":"data-id","name":"posts"},
                    {"kind":"verify_new_id","selector":"css:article[data-id]","attr":"data-id","name":"posts","timeoutMs":10000}]}
                }}
                """);
        Recipes.Plan p = new Recipes(s).prepare("mastodon", "list_posts", Json.obj());
        assertTrue(p.readOnly());
        assertEquals("capture_ids", p.steps().get(1).path("kind").asText());
        assertEquals("verify_new_id", p.steps().get(2).path("kind").asText());
    }

    @Test
    void parentSelectorXpathComApostrofoViraLiteralSeguro() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mastodon.json"), """
                {"network":"mastodon","actions":{
                  "list_posts":{"readOnly":true,"params":["tag"],"steps":[
                    {"kind":"goto","url":"https://mastodon.social/home"},
                    {"kind":"capture_ids","selector":"css:article","attr":"data-id","name":"posts",
                     "parentSelector":"xpath://div[contains(.,'{{tag}}')]"}]}
                }}
                """);
        Recipes.Plan p = new Recipes(s).prepare("mastodon", "list_posts", Json.obj().put("tag", "d'água"));
        assertEquals("xpath://div[contains(.,\"d'água\")]", p.steps().get(1).path("parentSelector").asText());
    }

    private Store storeYoutube(String patterns) throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("youtube.json"),
                "{\"network\":\"youtube\",\"actions\":{\"reply_comment_ui\":{\"params\":[\"commentId\"],"
                + "\"paramPatterns\":" + patterns + ","
                + "\"steps\":[{\"kind\":\"goto\",\"url\":\"https://www.youtube.com/comment/{{commentId}}\"}]}}}");
        return s;
    }

    @Test
    void paramPatternsCasaENaoCasa() throws Exception {
        Recipes r = new Recipes(storeYoutube("{\"commentId\":{\"regex\":\"[A-Za-z0-9_-]+\",\"erro\":\"commentId de RESPOSTA tem ponto; use o do pai\"}}"));
        Recipes.Plan p = r.prepare("youtube", "reply_comment_ui", Json.obj().put("commentId", "abc-123"));
        assertEquals("https://www.youtube.com/comment/abc-123", p.steps().get(0).get("url").asText());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.prepare("youtube", "reply_comment_ui", Json.obj().put("commentId", "pai.resposta")));
        assertTrue(e.getMessage().contains("param 'commentId' invalido"), e.getMessage());
        assertTrue(e.getMessage().contains("commentId de RESPOSTA"), e.getMessage());
        assertTrue(e.getMessage().contains("valor: 'pai.resposta'"), e.getMessage());
        // erro ausente vira "nao casa <regex>"
        Recipes r2 = new Recipes(storeYoutube("{\"commentId\":{\"regex\":\"[A-Za-z0-9_-]+\"}}"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> r2.prepare("youtube", "reply_comment_ui", Json.obj().put("commentId", "a.b")));
        assertTrue(e2.getMessage().contains("nao casa [A-Za-z0-9_-]+"), e2.getMessage());
    }

    @Test
    void paramPatternsReceitaInvalida() throws Exception {
        assertTrue(paramPatternsErro("{\"commentId\":{\"regex\":\"(\"}}").contains("receita invalida"));
        assertTrue(paramPatternsErro("{\"naoExiste\":{\"regex\":\"x\"}}").contains("nao declarado"));
        assertTrue(paramPatternsErro("{\"commentId\":{\"regex\":\"" + "a".repeat(201) + "\"}}").contains("acima de 200"));
        assertTrue(paramPatternsErro("\"x\"").contains("receita invalida"));
    }

    private String paramPatternsErro(String patterns) throws Exception {
        Recipes r = new Recipes(storeYoutube(patterns));
        try {
            r.prepare("youtube", "reply_comment_ui", Json.obj().put("commentId", "abc"));
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ---------- receita provada ----------

    @Test
    void statusProvadaENaoProvada() {
        assertTrue(Recipes.receitaProvada(Json.parse(
                "{\"status\":\"provada 12/11/2026 (lote b1)\"}"), "ig", "create_post", null));
        assertFalse(Recipes.receitaProvada(Json.parse(
                "{\"status\":\"mapeada, nao provada\"}"), "ig", "create_post", null));
        assertFalse(Recipes.receitaProvada(Json.parse(
                "{\"status\":\"mapeada; prova de resposta obrigatoria; execucao real pendente\"}"), "ig", "create_post", null));
    }

    @Test
    void provadaExplicitoVenceOStatus() {
        assertTrue(Recipes.receitaProvada(Json.parse(
                "{\"provada\":true,\"status\":\"mapeada, nao provada\"}"), "ig", "create_post", null));
        assertFalse(Recipes.receitaProvada(Json.parse(
                "{\"provada\":false,\"status\":\"Provada 12/11\"}"), "ig", "create_post", null));
    }

    @Test
    void entradaEmProvasJsonTornaProvada() throws Exception {
        Path provas = tmp.resolve("provas.json");
        Files.writeString(provas, "{\"ig\":{\"create_post\":\"b123\"}}", StandardCharsets.UTF_8);
        assertTrue(Recipes.receitaProvada(Json.obj(), "ig", "create_post", provas));
        assertFalse(Recipes.receitaProvada(Json.obj(), "ig", "reply", provas));
        // arquivo ausente ou ilegivel = sem entrada
        assertFalse(Recipes.receitaProvada(Json.obj(), "ig", "create_post", tmp.resolve("ausente.json")));
        Files.writeString(tmp.resolve("quebrado.json"), "{nao json", StandardCharsets.UTF_8);
        assertFalse(Recipes.receitaProvada(Json.obj(), "ig", "create_post", tmp.resolve("quebrado.json")));
    }

    @Test
    void readOnlyESempreProvada() {
        assertTrue(Recipes.receitaProvada(Json.parse(
                "{\"readOnly\":true,\"status\":\"mapeada, nao provada\"}"), "ig", "list_posts", null));
    }

    @Test
    void prepareCarregaProvadaDoStatusEProvas() throws Exception {
        Store s = storeComReceita();
        Files.writeString(s.home.resolve("recipes").resolve("instagram.json"), """
                {"network":"instagram","actions":{
                  "create_post":{"params":["text"],"textField":"text","status":"provada 12/11/2026 (lote b1)","steps":[
                    {"kind":"goto","url":"https://www.instagram.com/"}]}}}
                """, StandardCharsets.UTF_8);
        Recipes r = new Recipes(s);
        assertTrue(r.prepare("instagram", "create_post", Json.obj().put("text", "oi")).provada());
        // pinterest delete_post sem status/provada e sem provas.json = nao provada
        assertFalse(r.prepare("pinterest", "delete_post", Json.obj().put("postId", "1")).provada());
    }
}
