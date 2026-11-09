package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** goto com marcador [[novo:NOME]]: resolucao na execucao e validacao do lote. */
class GotoIdNovoTest {

    @TempDir
    Path tmp;

    /** Store de teste com gate que passa (exit 0), mesma fixture do BatchStoreTest. */
    private BatchStore bs() {
        try {
            return new BatchStore(TextGateTest.storeComGate(tmp, "exit 0", 30));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String cap(String name) {
        return "{\"network\":\"mastodon\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"" + name + "\"}";
    }

    private static String ver(String name) {
        return "{\"network\":\"mastodon\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"" + name + "\"}";
    }

    private static String gotoCom(String url) {
        return "{\"network\":\"mastodon\",\"kind\":\"goto\",\"url\":\"" + url + "\"}";
    }

    // ---------- resolverIdNovo ----------

    @Test
    void trocaOMarcadorPeloIdNovo() {
        Map<String, String> ids = Map.of("posts", "1234567890");
        assertEquals("https://mastodon.social/@usuario/1234567890",
                Tools.resolverIdNovo("https://mastodon.social/@usuario/[[novo:posts]]", ids));
    }

    @Test
    void semMarcadorDevolveUrlIgual() {
        assertEquals("https://mastodon.social/@usuario",
                Tools.resolverIdNovo("https://mastodon.social/@usuario", Map.of("posts", "1")));
    }

    @Test
    void nameSemVerifyOkAnteriorLanca() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                Tools.resolverIdNovo("https://mastodon.social/[[novo:posts]]", Map.of("outro", "1")));
        assertTrue(e.getMessage().contains("[[novo:posts]] sem verify_new_id ok anterior"), e.getMessage());
    }

    @Test
    void idComCaractereDeUrlLanca() {
        for (String id : List.of("12/34", "a?b")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                    Tools.resolverIdNovo("https://mastodon.social/[[novo:posts]]", Map.of("posts", id)));
            assertTrue(e.getMessage().contains("caracteres invalidos"), id + " -> " + e.getMessage());
        }
    }

    // ---------- validacao do lote ----------

    @Test
    void gotoComMarcadorDepoisDeVerifyNovoPassa() throws Exception {
        BatchStore bs = bs();
        JsonNode lote = Json.parse("[" + cap("posts") + "," + ver("posts") + ","
                + gotoCom("https://mastodon.social/@usuario/[[novo:posts]]") + "]");
        assertDoesNotThrow(() -> bs.draft("goto id novo", lote));
        // a execucao revalida por validateBatchRules: marcador continua aceito ali
        assertDoesNotThrow(() -> bs.validateBatchRules(lote));
    }

    @Test
    void gotoComMarcadorSemVerifyAnteriorFalha() {
        BatchStore bs = bs();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                bs.draft("sem verify", Json.parse("[" + gotoCom("https://mastodon.social/[[novo:posts]]") + "]")));
        assertTrue(e.getMessage().contains("sem verify_new_id anterior com esse name"), e.getMessage());
        // verify DEPOIS do goto nao conta: tem de vir antes
        e = assertThrows(IllegalArgumentException.class, () ->
                bs.draft("verify depois", Json.parse("[" + cap("posts") + ","
                        + gotoCom("https://mastodon.social/[[novo:posts]]") + "," + ver("posts") + "]")));
        assertTrue(e.getMessage().contains("sem verify_new_id anterior com esse name"), e.getMessage());
    }

    @Test
    void marcadorForaDoPadraoFalha() {
        BatchStore bs = bs();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                bs.draft("marcador ruim", Json.parse("[" + cap("posts") + "," + ver("posts") + ","
                        + gotoCom("https://mastodon.social/[[outra]]") + "]")));
        assertTrue(e.getMessage().contains("marcador invalido"), e.getMessage());
    }

    @Test
    void marcadorNoHostFalha() {
        BatchStore bs = bs();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                bs.draft("host", Json.parse("[" + cap("posts") + "," + ver("posts") + ","
                        + gotoCom("https://[[novo:posts]].pinterest.com/") + "]")));
        assertTrue(e.getMessage().contains("so no path da url"), e.getMessage());
    }
}
