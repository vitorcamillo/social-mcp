package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** validar-receitas: expande e valida sem gravar lote nem journal; classifica conferencia. */
class RecipeValidatorTest {

    @TempDir
    Path tmp;

    private static final String BASE_NS = """
            "get_profile": {"naoSuportado": "x"}, "read_inbox": {"naoSuportado": "x"},
            "read_notifications": {"naoSuportado": "x"}, "read_mentions": {"naoSuportado": "x"},
            "read_comments": {"naoSuportado": "x"}, "edit_post": {"naoSuportado": "x"},
            "delete_post": {"naoSuportado": "x"}, "reply": {"naoSuportado": "x"},
            "unfollow": {"naoSuportado": "x"}, "unlike": {"naoSuportado": "x"}, "get_post": {"naoSuportado": "x"}
            """;

    private Store store(String receita) throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path dir = s.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mastodon.json"), receita, StandardCharsets.UTF_8);
        return s;
    }

    private static String receita(String acoes) {
        return "{\"network\":\"mastodon\",\"exemplos\":{\"postId\":\"42\"},\"actions\":{" + acoes + "}}";
    }

    private static final String LIST = "\"list_posts\":{\"readOnly\":true,\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x\"},"
            + "{\"kind\":\"extract\",\"what\":\"links\"}]}";
    private static final String CREATE_FORTE = "\"create_post\":{\"params\":[\"text\"],\"textField\":\"text\",\"status\":\"mapeada, nao provada\",\"steps\":["
            + "{\"kind\":\"goto\",\"url\":\"https://mastodon.social/home\"},{\"kind\":\"type\",\"selector\":\"css:textarea\",\"text\":\"{{text}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button[type=submit]\"},{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x\"},"
            + "{\"kind\":\"verify_text\",\"selector\":\"css:article:first-of-type\",\"expect\":\"{{text}}\"}]}";
    private static final String CREATE_ID_NOVO = "\"create_post\":{\"params\":[\"text\"],\"textField\":\"text\",\"status\":\"mapeada\",\"steps\":["
            + "{\"kind\":\"goto\",\"url\":\"https://mastodon.social/home\"},"
            + "{\"kind\":\"capture_ids\",\"selector\":\"css:article[data-id]\",\"attr\":\"data-id\",\"name\":\"posts\"},"
            + "{\"kind\":\"type\",\"selector\":\"css:textarea\",\"text\":\"{{text}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button[type=submit]\"},{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x\"},"
            + "{\"kind\":\"verify_new_id\",\"selector\":\"css:article[data-id]\",\"attr\":\"data-id\",\"name\":\"posts\"}]}";
    private static final String CREATE_REQUEST = "\"create_post\":{\"params\":[\"text\"],\"textField\":\"text\",\"status\":\"mapeada\",\"steps\":["
            + "{\"kind\":\"request\",\"method\":\"POST\",\"url\":\"https://mastodon.social/api/v1/statuses\","
            + "\"headers\":{\"content-type\":\"application/json\"},\"body\":\"{\\\"status\\\":\\\"{{text}}\\\"}\","
            + "\"expectBody\":\"id\",\"description\":\"post\"}]}";
    private static final String EDIT_FORTE = "\"edit_post\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button.edit\"},{\"kind\":\"verify_attr\",\"selector\":\"css:button.edit\",\"attr\":\"aria-pressed\",\"expect\":\"true\"}]}";
    private static final String FOLLOW_ATTR = "\"follow\":{\"params\":[\"profile\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@{{profile}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button.logo-button\"},{\"kind\":\"verify_attr\",\"selector\":\"css:button.logo-button\",\"attr\":\"aria-pressed\",\"expect\":\"true\"}]}";
    private static final String LIKE_FRACA = "\"like\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button.star\"},{\"kind\":\"verify_text\",\"expect\":\"Favoritado\"}]}";
    private static final String DELETE_ABSENT = "\"delete_post\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
            + "{\"kind\":\"click\",\"selector\":\"css:button.del\"},{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x\"},"
            + "{\"kind\":\"verify_text\",\"selector\":\"css:h1\",\"expect\":\"x\"},"
            + "{\"kind\":\"verify_absent\",\"selector\":\"css:a[href*='/{{postId}}']\"}]}";

    private long arquivos(Store s) throws Exception {
        try (Stream<Path> l = Files.list(s.batchesDir())) {
            return l.count();
        }
    }

    @Test
    void classificaConferenciaSemGravarLoteNemJournal() throws Exception {
        String bloco = BASE_NS.replace("\"delete_post\": {\"naoSuportado\": \"x\"},", "");
        Store s = store(receita(String.join(",", LIST, CREATE_FORTE, FOLLOW_ATTR, LIKE_FRACA, DELETE_ABSENT, bloco)));
        long journal = Files.size(s.journalPath());
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        assertEquals(0, arquivos(s), "nao pode gravar lote");
        assertEquals(journal, Files.size(s.journalPath()), "nao pode escrever no journal");
        assertEquals("forte", item(r, "create_post").conferencia());
        assertEquals("mapeada, nao provada", item(r, "create_post").status());
        assertEquals("forte", item(r, "follow").conferencia());
        assertEquals("forte", item(r, "delete_post").conferencia());
        assertEquals("fraca", item(r, "like").conferencia());
        assertTrue(item(r, "like").escritaFraca());
        assertEquals(RecipeValidator.TIPO_LEITURA, item(r, "list_posts").tipo());
        assertEquals(RecipeValidator.TIPO_NAO_SUPORTADA, item(r, "edit_post").tipo());
        assertTrue(r.faltam().isEmpty(), r.faltam().toString());
        assertFalse(r.ok(), "like fraca derruba o ok");
        assertTrue(RecipeValidator.texto(r).contains("FALHOU"));
    }

    @Test
    void okQuandoTudoForteEBaseCompleta() throws Exception {
        String bloco = BASE_NS.replace("\"delete_post\": {\"naoSuportado\": \"x\"},", "");
        String like = "\"like\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:button.star\"},{\"kind\":\"verify_attr\",\"selector\":\"css:button.star\",\"attr\":\"aria-pressed\",\"expect\":\"true\"}]}";
        Store s = store(receita(String.join(",", LIST, CREATE_ID_NOVO, FOLLOW_ATTR, like, DELETE_ABSENT, bloco)));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        assertTrue(r.ok(), RecipeValidator.texto(r));
        assertTrue(RecipeValidator.texto(r).contains("OK: 100%"));
    }

    @Test
    void faltaDeAcaoBaseERecusaAparecem() throws Exception {
        // reply sem o param usado no placeholder -> recusada; acoes base ausentes listadas
        String ruim = "\"reply\":{\"params\":[\"text\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:b\"}]}";
        Store s = store(receita(String.join(",", LIST, ruim)));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        RecipeValidator.Item reply = item(r, "reply");
        assertFalse(reply.aceita());
        assertTrue(reply.motivo().contains("postId"), reply.motivo());
        assertTrue(r.faltam().get("mastodon").containsAll(List.of("create_post", "follow", "unlike")), r.faltam().toString());
        assertFalse(r.ok());
    }

    @Test
    void naoSuportadoRecusaNoPrepare() throws Exception {
        Store s = store(receita(String.join(",", LIST, "\"edit_post\":{\"naoSuportado\":\"rede sem edicao\"}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Recipes(s).prepare("mastodon", "edit_post", Json.obj()));
        assertTrue(e.getMessage().contains("rede sem edicao"), e.getMessage());
    }

    @Test
    void redeDesconhecidaEsemReceita() throws Exception {
        Store s = store(receita(LIST));
        assertThrows(IllegalArgumentException.class, () -> RecipeValidator.validar(s, "orkut"));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, null);
        assertTrue(r.semReceita().contains("bluesky"), r.semReceita().toString());
    }

    @Test
    void redeSolicitadaSemReceitaReprova() throws Exception {
        Store s = store(receita(LIST));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "bluesky");
        assertTrue(r.semReceita().contains("bluesky"), r.semReceita().toString());
        assertFalse(r.ok(), RecipeValidator.texto(r));
        assertTrue(RecipeValidator.texto(r).contains("FALHOU"), RecipeValidator.texto(r));
        assertFalse(RecipeValidator.texto(r).contains("OK: 100%"), RecipeValidator.texto(r));
        assertTrue(r.itens().stream().anyMatch(i -> !i.aceita() && i.motivo().contains("sem receita")),
                r.itens().toString());
    }

    @Test
    void setFileSemArquivoDeExemploRecusaComMotivo() throws Exception {
        String foto = "\"create_photo_post\":{\"params\":[\"arquivo\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/home\"},"
                + "{\"kind\":\"set_file\",\"selector\":\"css:input[type=file]\",\"path\":\"{{arquivo}}\"},{\"kind\":\"click\",\"selector\":\"css:b\"},"
                + "{\"kind\":\"verify_attr\",\"selector\":\"css:b\",\"attr\":\"x\",\"expect\":\"y\"}]}";
        Store s = store(receita(String.join(",", LIST, foto)));
        Path midia = Files.createDirectories(tmp.resolve("midia"));
        com.fasterxml.jackson.databind.node.ObjectNode cfg = (com.fasterxml.jackson.databind.node.ObjectNode) s.config();
        ((com.fasterxml.jackson.databind.node.ObjectNode) cfg.get("defaults")).putArray("uploadRoots").add(midia.toString());
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        RecipeValidator.Item i = item(RecipeValidator.validar(s, "mastodon"), "create_photo_post");
        assertFalse(i.aceita());
        assertTrue(i.motivo().contains("sem arquivo de exemplo"), i.motivo());
        Files.write(midia.resolve("card.png"), new byte[]{1, 2, 3});
        i = item(RecipeValidator.validar(s, "mastodon"), "create_photo_post");
        assertTrue(i.aceita(), i.motivo());
        assertEquals("forte", i.conferencia());
    }

    @Test
    void verifyAbsentValidadoEContaNoOrcamento() throws Exception {
        BatchStore bs = new BatchStore(TextGateTest.storeComGate(tmp, "exit 0", 30));
        assertDoesNotThrow(() -> bs.validate(Json.parse("""
                [{"network":"x","kind":"verify_absent","selector":"css:a[href*='/123']"},
                 {"network":"x","kind":"verify_absent","selector":"xpath://article","expect":"oi","timeoutMs":20000}]
                """)));
        for (String w : List.of("{\"kind\":\"verify_absent\"}",
                "{\"kind\":\"verify_absent\",\"selector\":\"css:a\",\"expect\":\"\"}",
                "{\"kind\":\"verify_absent\",\"selector\":\"css:a\",\"expect\":1}",
                "{\"kind\":\"verify_absent\",\"selector\":\"css:a\",\"timeoutMs\":0}",
                "{\"kind\":\"verify_absent\",\"selector\":\"css:a\",\"timeoutMs\":660001}",
                "{\"kind\":\"verify_absent\",\"selector\":\"xpath:\"}",
                "{\"kind\":\"verify_absent\",\"selector\":\"xpath:  \"}",
                "{\"kind\":\"verify_absent\",\"selector\":\"css:a >>> \"}")) {
            String lote = "[" + w.replace("{", "{\"network\":\"x\",") + "]";
            assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse(lote)), lote);
        }
        assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"x","kind":"click","selector":"xpath:"}]""")));
        assertFalse(Guard.isPublic("verify_absent"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.validate(Json.parse("""
                [{"network":"x","kind":"wait","expect":"a","timeoutMs":660000},
                 {"network":"x","kind":"verify_absent","selector":"css:a","timeoutMs":60001}]
                """)));
        assertTrue(e.getMessage().contains("720000"), e.getMessage());
        // verify_absent isolado: pagina de login/vazia tambem nao tem o item -> fraca
        assertEquals("fraca", BatchStore.conferencia(Json.parse("""
                [{"network":"x","kind":"click","selector":"css:a"},{"network":"x","kind":"verify_absent","selector":"css:a"}]
                """)));
        // com confirmacao positiva da pagina (verify com selector) a ausencia volta a ser forte
        assertEquals("forte", BatchStore.conferencia(Json.parse("""
                [{"network":"x","kind":"click","selector":"css:a"},{"network":"x","kind":"verify_text","selector":"css:h1","expect":"x"},{"network":"x","kind":"verify_absent","selector":"css:a"}]
                """)));
        assertEquals("forte", BatchStore.conferencia(Json.parse("""
                [{"network":"x","kind":"click","selector":"css:a"},{"network":"x","kind":"verify_absent","selector":"css:a"},{"network":"x","kind":"verify_attr","selector":"css:b","attr":"aria-pressed","expect":"true"}]
                """)));
        assertEquals("fraca", BatchStore.conferencia(Json.parse("""
                [{"network":"x","kind":"click","selector":"css:a"},{"network":"x","kind":"verify_absent","selector":"css:a"},{"network":"x","kind":"verify_text","expect":"sumiu"}]
                """)));
    }

    private static RecipeValidator.Item item(RecipeValidator.Relatorio r, String acao) {
        return r.itens().stream().filter(i -> i.action().equals(acao)).findFirst()
                .orElseThrow(() -> new AssertionError("sem item " + acao + " em " + r.itens()));
    }

    @Test
    void readOnlyComSeletorInvalidoRecusado() throws Exception {
        String ro = "\"read_mentions\":{\"readOnly\":true,\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/x\"},"
                + "{\"kind\":\"verify_text\",\"selector\":\"css:a >>> \",\"expect\":\"x\"}]}";
        Store s = store(receita(String.join(",", LIST, ro)));
        RecipeValidator.Item i = item(RecipeValidator.validar(s, "mastodon"), "read_mentions");
        assertFalse(i.aceita(), "seletor >>> vazio em readOnly tem que ser recusado");
    }

    @Test
    void arquivoDeExemploPulaCandidatoInvalido() throws Exception {
        Store s = store(receita(LIST));
        Path midia = Files.createDirectories(tmp.resolve("midia2"));
        com.fasterxml.jackson.databind.node.ObjectNode cfg = (com.fasterxml.jackson.databind.node.ObjectNode) s.config();
        com.fasterxml.jackson.databind.node.ObjectNode defs = (com.fasterxml.jackson.databind.node.ObjectNode) cfg.get("defaults");
        defs.putArray("uploadRoots").add(midia.toString());
        defs.put("uploadMaxMb", 1);
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        Files.write(midia.resolve("a-grande.png"), new byte[2 * 1024 * 1024]);
        Files.write(midia.resolve("b-ok.jpg"), new byte[]{1, 2, 3});
        assertEquals("b-ok.jpg", RecipeValidator.arquivoExemplo(s).orElseThrow().getFileName().toString());
    }

    @Test
    void homeIncompletaNaoEhPronta() throws Exception {
        Path h = tmp.resolve("vazia");
        Files.createDirectories(h);
        assertFalse(ApprovalCli.homePronta(h));
        assertEquals(0, Files.list(h).count(), "checar nao pode criar nada");
        Store s = store(receita(LIST));
        assertTrue(ApprovalCli.homePronta(s.home));
    }

    @Test
    void createPostComVerifyNewIdDaProvaIdNovoEok() throws Exception {
        String follow = "\"follow\":{\"params\":[\"profile\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@{{profile}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:button.logo-button\"},{\"kind\":\"verify_attr\",\"selector\":\"css:button.logo-button\",\"attr\":\"aria-pressed\",\"expect\":\"true\"}]}";
        String like = "\"like\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:button.star\"},{\"kind\":\"verify_attr\",\"selector\":\"css:button.star\",\"attr\":\"aria-pressed\",\"expect\":\"true\"}]}";
        Store s = store(receita(String.join(",", LIST, CREATE_ID_NOVO, follow, like, BASE_NS)));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        RecipeValidator.Item create = item(r, "create_post");
        assertEquals("id_novo", create.prova());
        assertFalse(create.criacaoSemIdNovo());
        assertTrue(r.ok(), RecipeValidator.texto(r));
    }

    @Test
    void createPostSoComVerifyTextFalhaCriacaoSemIdNovo() throws Exception {
        Store s = store(receita(String.join(",", LIST, CREATE_FORTE)));
        RecipeValidator.Item create = item(RecipeValidator.validar(s, "mastodon"), "create_post");
        assertTrue(create.aceita());
        assertEquals("estado", create.prova());
        assertTrue(create.criacaoSemIdNovo());
    }

    @Test
    void createPostRequestComExpectBodyDaProvaRequestEok() throws Exception {
        Store s = store(receita(String.join(",", LIST, CREATE_REQUEST)));
        RecipeValidator.Item create = item(RecipeValidator.validar(s, "mastodon"), "create_post");
        assertTrue(create.aceita(), create.motivo());
        assertEquals("request", create.prova());
        assertFalse(create.criacaoSemIdNovo());
    }

    @Test
    void editPostComEstadoNaoEhCriacaoEOk() throws Exception {
        Store s = store(receita(String.join(",", LIST, EDIT_FORTE)));
        RecipeValidator.Item edit = item(RecipeValidator.validar(s, "mastodon"), "edit_post");
        assertTrue(edit.aceita(), edit.motivo());
        assertEquals("estado", edit.prova());
        assertFalse(edit.criacaoSemIdNovo(), "edit_post nao e criacao/resposta");
    }
}
