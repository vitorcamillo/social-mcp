package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mutantes PIT de RecipeValidator: cada teste falha com o mutante e passa no codigo atual. */
class RecipeValidatorMutantesTest {

    @TempDir
    Path tmp;

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

    private static RecipeValidator.Item item(RecipeValidator.Relatorio r, String acao) {
        return r.itens().stream().filter(i -> i.action().equals(acao)).findFirst()
                .orElseThrow(() -> new AssertionError("sem item " + acao + " em " + r.itens()));
    }

    private static final String LIST = "\"list_posts\":{\"readOnly\":true,\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x\"},"
            + "{\"kind\":\"extract\",\"what\":\"links\"}]}";

    private static final String TEXTO_PADRAO = "Teste de validacao da receita (nao publicado)";

    @Test
    void tipoRecusadaRespeitaReadOnly() throws Exception {
        String roRuim = "\"read_mentions\":{\"readOnly\":true,\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/x\"},"
                + "{\"kind\":\"verify_text\",\"selector\":\"css:a >>> \",\"expect\":\"x\"}]}";
        String wrRuim = "\"reply\":{\"params\":[\"text\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:b\"}]}";
        Store s = store(receita(String.join(",", LIST, roRuim, wrRuim)));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        RecipeValidator.Item ro = item(r, "read_mentions");
        RecipeValidator.Item wr = item(r, "reply");
        assertFalse(ro.aceita());
        assertFalse(wr.aceita());
        assertEquals(RecipeValidator.TIPO_LEITURA, ro.tipo());
        assertEquals(RecipeValidator.TIPO_ESCRITA, wr.tipo());
    }

    @Test
    void provaAusenteEhTraco() throws Exception {
        String wrRuim = "\"reply\":{\"params\":[\"text\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:b\"}]}";
        Store s = store(receita(String.join(",", LIST, wrRuim)));
        RecipeValidator.Relatorio r = RecipeValidator.validar(s, "mastodon");
        assertEquals("-", item(r, "list_posts").prova());
        assertEquals("-", item(r, "reply").prova());
    }

    @Test
    void arquivoExemploPreferePngDaSegundaRaiz() throws Exception {
        Store s = TextGateTest.storeComGate(tmp, "exit 0", 30);
        Path raizJpg = Files.createDirectories(tmp.resolve("up-jpg"));
        Path raizPng = Files.createDirectories(tmp.resolve("up-png"));
        Files.write(raizJpg.resolve("foto.jpg"), new byte[]{1, 2, 3});
        Files.write(raizPng.resolve("foto.png"), new byte[]{1, 2, 3});
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.get("defaults")).putArray("uploadRoots").add(raizJpg.toString()).add(raizPng.toString());
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        assertEquals("foto.png",
                RecipeValidator.arquivoExemplo(s).orElseThrow().getFileName().toString());
    }

    @Test
    void valorPadraoParaUrlLinkUri() {
        assertEquals("https://example.com/p/123", RecipeValidator.valorPadrao("imageUrl"));
        assertEquals("https://example.com/p/123", RecipeValidator.valorPadrao("link"));
        assertEquals("https://example.com/p/123", RecipeValidator.valorPadrao("imageUri"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("comment"));
    }

    @Test
    void valorPadraoParaIdCidStartEnd() {
        assertEquals("123456789", RecipeValidator.valorPadrao("postId"));
        assertEquals("123456789", RecipeValidator.valorPadrao("commentCid"));
        assertEquals("123456789", RecipeValidator.valorPadrao("startTime"));
        assertEquals("123456789", RecipeValidator.valorPadrao("endDate"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
    }

    @Test
    void valorPadraoParaHandleUserProfileNick() {
        assertEquals("example_account", RecipeValidator.valorPadrao("handle"));
        assertEquals("example_account", RecipeValidator.valorPadrao("username"));
        assertEquals("example_account", RecipeValidator.valorPadrao("profileName"));
        assertEquals("example_account", RecipeValidator.valorPadrao("nickname"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
    }

    @Test
    void valorPadraoParaSubreddit() {
        assertEquals("brasil", RecipeValidator.valorPadrao("subreddit"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
    }

    @Test
    void valorPadraoDidNegadoNaoVazaParaTextoLivre() {
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("comment"));
    }

    @Test
    void escritaValidaFracaSemIdNemRequestTemProvaTraco() throws Exception {
        // like so com verify_text: valida, conferencia fraca (nao forte), sem id_novo e sem request
        // conferido -> prova() retorna PROVA_NENHUMA ("-", nao "").
        String like = "\"like\":{\"params\":[\"postId\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:button.star\"},{\"kind\":\"verify_text\",\"expect\":\"Favoritado\"}]}";
        Store s = store(receita(String.join(",", LIST, like)));
        RecipeValidator.Item i = item(RecipeValidator.validar(s, "mastodon"), "like");
        assertTrue(i.aceita(), i.motivo());
        assertEquals("fraca", i.conferencia());
        assertEquals("-", i.prova());
    }

    @Test
    void valorPadraoParaPastaBoard() {
        assertEquals("Shows e eventos", RecipeValidator.valorPadrao("pasta"));
        assertEquals("Shows e eventos", RecipeValidator.valorPadrao("boardName"));
        assertEquals(TEXTO_PADRAO, RecipeValidator.valorPadrao("text"));
    }

    private static String linhaDoTexto(String texto, String acao) {
        return texto.lines().filter(l -> l.contains(acao)).findFirst()
                .orElseThrow(() -> new AssertionError("sem linha " + acao + " em:\n" + texto));
    }

    @Test
    void textoMarcaAceitaERecusadaPorAcao() throws Exception {
        String wrRuim = "\"reply\":{\"params\":[\"text\"],\"steps\":[{\"kind\":\"goto\",\"url\":\"https://mastodon.social/@x/{{postId}}\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:b\"}]}";
        Store s = store(receita(String.join(",", LIST, wrRuim)));
        String t = RecipeValidator.texto(RecipeValidator.validar(s, "mastodon"));
        String linhaOk = linhaDoTexto(t, "list_posts");
        String linhaRuim = linhaDoTexto(t, "reply");
        assertTrue(linhaOk.contains("aceita"), linhaOk);
        assertFalse(linhaOk.contains("RECUSADA"), linhaOk);
        assertTrue(linhaRuim.contains("RECUSADA"), linhaRuim);
        assertFalse(linhaRuim.contains("aceita"), linhaRuim);
    }

    @Test
    void textoObservacaoSegueTipoEAceite() {
        var itens = List.of(
                new RecipeValidator.Item("r", "a_ns", "nao_suportada", true, "motivo-ns", "-", "-", "st-ns"),
                new RecipeValidator.Item("r", "a_leit", "leitura", true, "", "-", "-", "st-leit"),
                new RecipeValidator.Item("r", "a_ruim", "escrita", false, "boom", "-", "-", ""));
        String t = RecipeValidator.texto(new RecipeValidator.Relatorio(itens, Map.of(), List.of()));
        assertTrue(linhaDoTexto(t, "a_ns").contains("nao suportado: motivo-ns"), t);
        assertTrue(linhaDoTexto(t, "a_leit").contains("st-leit"), t);
        assertTrue(linhaDoTexto(t, "a_ruim").contains("boom"), t);
    }

    @Test
    void textoResumoContaCadaCategoria() {
        var itens = List.of(
                new RecipeValidator.Item("r", "a_ns", "nao_suportada", true, "motivo-ns", "-", "-", "st"),
                new RecipeValidator.Item("r", "a_leit", "leitura", true, "", "-", "-", "st"),
                new RecipeValidator.Item("r", "a_forte", "escrita", true, "", "forte", "id_novo", "st"),
                new RecipeValidator.Item("r", "a_fraca", "escrita", true, "", "fraca", "estado", "st"),
                new RecipeValidator.Item("r", "a_aus", "escrita", true, "", "ausente", "-", "st"),
                new RecipeValidator.Item("r", "a_ruim", "escrita", false, "boom", "-", "-", ""));
        String t = RecipeValidator.texto(new RecipeValidator.Relatorio(itens, Map.of(), List.of()));
        assertTrue(t.contains(
                "RESUMO: 6 acoes; 5 aceitas, 1 recusadas; leitura 1; escrita forte 1, fraca 1, ausente 1; nao suportadas 1; id novo 1"),
                t);
    }
}
