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

/** Trava de navegacao: browse/goto/find_duplicates so aceitam https em host da rede ou browseHosts. */
class NavegacaoTest {

    @TempDir
    Path tmp;

    private Store store() {
        try {
            return TextGateTest.storeComGate(tmp, "exit 0", 30);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BatchStore bs() {
        return new BatchStore(store());
    }

    private static void gravar(Store s, ObjectNode cfg) throws Exception {
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
    }

    private static String cap(String name) {
        return "{\"network\":\"instagram\",\"kind\":\"capture_ids\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"" + name + "\"}";
    }

    private static String ver(String name) {
        return "{\"network\":\"instagram\",\"kind\":\"verify_new_id\",\"selector\":\"css:article\",\"attr\":\"data-id\",\"name\":\"" + name + "\"}";
    }

    @Test
    void bloqueiaEsquemasHostsEPortasForaDaRede() {
        Store s = store();
        for (String url : List.of(
                "file:///etc/passwd",
                "data:text/html,<p>x</p>",
                "javascript:alert(1)",
                "chrome://settings",
                "http://www.instagram.com/",
                "https://evil.example/",
                "https://user@www.instagram.com/",
                "https://www.instagram.com:8443/",
                "https://www.instagram.com.evil.example/",
                "https://instagram.com@evil.example/",
                "")) {
            assertFalse(BatchStore.urlNavegavel(s, "instagram", url), url);
            assertThrows(IllegalArgumentException.class, () -> BatchStore.validarNavegacao(s, "instagram", url), url);
        }
        assertFalse(BatchStore.urlNavegavel(s, "instagram", null));
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validarNavegacao(s, "instagram", null));
    }

    @Test
    void aceitaHttpsNoDominioDaRede() {
        Store s = store();
        for (String url : List.of(
                "https://www.instagram.com/p/abc/",
                "https://i.instagram.com/x",
                "https://instagram.com/",
                "HTTPS://WWW.INSTAGRAM.COM/")) {
            assertTrue(BatchStore.urlNavegavel(s, "instagram", url), url);
            assertDoesNotThrow(() -> BatchStore.validarNavegacao(s, "instagram", url), url);
        }
    }

    @Test
    void blueskyUsaRequestHostsDefault() {
        Store s = store();
        assertTrue(BatchStore.urlNavegavel(s, "bluesky", "https://auriporia.us-west.host.bsky.network/x"));
        assertFalse(BatchStore.urlNavegavel(s, "bluesky", "https://host.bsky.network/"));
    }

    @Test
    void defaultsBrowseHostsValeEmQualquerRede() throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        cfg.with("defaults").putArray("browseHosts").add("reservaja.uk").add("*.corinthians.com.br");
        gravar(s, cfg);

        assertTrue(BatchStore.urlNavegavel(s, "instagram", "https://reservaja.uk/x"));
        assertTrue(BatchStore.urlNavegavel(s, "bluesky", "https://www.corinthians.com.br/"));
        assertFalse(BatchStore.urlNavegavel(s, "instagram", "https://corinthians.com.br/"));
    }

    @Test
    void browseHostsDaRedeValeSoNaquelaRede() throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.path("networks").path("x")).putArray("browseHosts").add("feverup.com");
        gravar(s, cfg);

        assertTrue(BatchStore.urlNavegavel(s, "x", "https://feverup.com/evento"));
        assertFalse(BatchStore.urlNavegavel(s, "instagram", "https://feverup.com/evento"));
    }

    @Test
    void mensagemNaoEcoaPathNemQuery() {
        Store s = store();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BatchStore.validarNavegacao(s, "instagram", "https://evil.example/segredo?token=abc"));
        assertFalse(e.getMessage().contains("segredo"), e.getMessage());
        assertFalse(e.getMessage().contains("token"), e.getMessage());
        assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
    }

    @Test
    void rascunhoRecusaGotoParaArquivoLocal() {
        BatchStore bs = bs();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> bs.draft("arquivo local",
                Json.parse("[{\"kind\":\"goto\",\"network\":\"instagram\",\"url\":\"file:///etc/passwd\"}]")));
        assertTrue(e.getMessage().contains("acao 0"), e.getMessage());
    }

    @Test
    void rascunhoRecusaGotoComMarcadorParaHostAlheio() {
        BatchStore bs = bs();
        JsonNode lote = Json.parse("[" + cap("p") + "," + ver("p")
                + ",{\"network\":\"instagram\",\"kind\":\"goto\",\"url\":\"https://evil.example/[[novo:p]]\"}]");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bs.draft("marcador em host alheio", lote));
        assertTrue(e.getMessage().contains("bloqueada"), e.getMessage());
    }

    @Test
    void rascunhoAceitaGotoComMarcadorNoHostDaRede() {
        BatchStore bs = bs();
        JsonNode lote = Json.parse("[" + cap("p") + "," + ver("p")
                + ",{\"network\":\"instagram\",\"kind\":\"goto\",\"url\":\"https://www.instagram.com/p/[[novo:p]]/\"}]");
        assertDoesNotThrow(() -> bs.draft("marcador no path", lote));
    }
}
