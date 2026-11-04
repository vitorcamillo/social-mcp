package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PublicDefaultsTest {

    @TempDir
    Path tmp;

    @Test
    void defaultHomeUsaNomeNeutroSemEscreverNoHome() {
        String configured = System.getenv("SOCIAL_MCP_HOME");
        Path resolved = Store.defaultHome();
        boolean existedBefore = Files.exists(resolved.resolve("config.json"));
        if (configured == null || configured.isBlank()) {
            assertEquals(".social-mcp-public", resolved.getFileName().toString());
        } else {
            assertEquals(Path.of(configured).toAbsolutePath().normalize(), resolved);
        }
        assertEquals(existedBefore, Files.exists(resolved.resolve("config.json")),
                "a leitura do defaultHome nao deve criar estado no home real");
    }

    @Test
    void defaultsPublicosNaoApontamParaGatePrivado() throws Exception {
        Store store = new Store(tmp.resolve("runtime"));
        JsonNode config = store.config();
        JsonNode gate = config.path("defaults").path("gate");
        assertTrue(gate.path("command").isArray());
        assertEquals(0, gate.path("command").size());
        assertTrue(gate.path("config").asText().isBlank());
        String serialized = config.toString().toLowerCase();
        assertFalse(serialized.contains("/" + "media/"));
        assertFalse(serialized.contains("/home/"));
        assertFalse(serialized.contains("reser" + "vaja"));
        assertFalse(serialized.contains("ban em"));
    }

    @Test
    void aprovacaoContinuaFalhandoFechadoSemGate() throws Exception {
        Store store = new Store(tmp.resolve("runtime"));
        BatchStore batches = new BatchStore(store);
        String id = batches.draft("synthetic review", Json.parse("""
                [{"network":"bluesky","kind":"goto","url":"https://bsky.app/"}]
                """)).path("id").asText();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> batches.approve(id, "humano", "reviewed"));
        assertTrue(error.getMessage().contains("gate de texto nao configurado"), error.getMessage());
    }
}
