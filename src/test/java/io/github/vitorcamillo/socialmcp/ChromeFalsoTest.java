package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Smoke test do fixture ChromeFalso: Chrome real, servidor HTTPS local e Tools anexando. */
class ChromeFalsoTest {

    @TempDir
    Path tmp;

    @Test
    @Timeout(60)
    void browseLeAPaginaFalsaPeloChromeReal() throws Exception {
        Store store = new Store(tmp.resolve("home"));
        try (ChromeFalso cf = ChromeFalso.abrir(tmp, store)) {
            cf.pagina("/", "<html><head><title>Falso</title></head>"
                    + "<body><p id=x>ola falso</p></body></html>");
            Tools tools = new Tools(store);
            try {
                String r = tools.call("social_browse", Json.obj()
                        .put("network", "bluesky")
                        .put("url", "https://bsky.app/"));
                JsonNode out = Json.parse(r);
                assertEquals("Falso", out.path("title").asText());
                assertTrue(out.path("textoVisivel").asText().contains("ola falso"),
                        out.path("textoVisivel").asText());
                assertTrue(out.path("url").asText().startsWith("https://bsky.app/"),
                        out.path("url").asText());
                assertTrue(cf.pedidos().stream()
                                .anyMatch(p -> "GET".equals(p.metodo()) && "/".equals(p.caminho())),
                        cf.pedidos().toString());
            } finally {
                tools.shutdown();
            }
        }
    }

    @Test
    @Timeout(60)
    void redirectParaForaDaRedeEBloqueado() throws Exception {
        Store store = new Store(tmp.resolve("home"));
        try (ChromeFalso cf = ChromeFalso.abrir(tmp, store)) {
            // Meta refresh com 1 s: a pagina inicial carrega e o redirect tardio cai no NavGuard.
            cf.pagina("/", "<html><head><meta http-equiv=\"refresh\""
                    + " content=\"1;url=https://evil.example/\"></head>"
                    + "<body>redirecionando</body></html>");
            Tools tools = new Tools(store);
            try {
                try {
                    String r = tools.call("social_browse", Json.obj()
                            .put("network", "bluesky")
                            .put("url", "https://bsky.app/"));
                    JsonNode out = Json.parse(r);
                    String url = out.path("url").asText();
                    assertFalse(url.contains("evil.example"),
                            "browse nao pode terminar fora da rede: " + url);
                } catch (IllegalStateException e) {
                    assertTrue(String.valueOf(e.getMessage()).contains("navegacao bloqueada"),
                            e.getMessage());
                } catch (Exception e) {
                    // O driver pode reportar o bloqueio da requisicao antes do conferirDestino.
                    String msg = String.valueOf(e.getMessage());
                    assertTrue(msg.contains("ERR_BLOCKED_BY_CLIENT") || msg.contains("net::ERR_BLOCKED"),
                            "esperava bloqueio de navegacao; veio: " + e);
                }
            } finally {
                tools.shutdown();
            }
        }
    }
}
