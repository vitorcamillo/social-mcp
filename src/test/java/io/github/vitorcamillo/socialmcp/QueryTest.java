package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {

    @TempDir
    Path tmp;

    @Test
    void queryXpathVazioRejeitado() {
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.queryScript("xpath:", 5));
    }

    @Test
    void queryCssShadowEmbutePartesEMaxSemValue() {
        String s = ShadowDom.queryScript("css:a >>> b", 3);
        assertTrue(s.contains("\"a\""), s);
        assertTrue(s.contains("\"b\""), s);
        assertTrue(s.contains("3"), s);
        assertFalse(s.contains(".value"), s);
    }

    @Test
    void queryXpathEscapadoEmJson() {
        String s = ShadowDom.queryScript("xpath://button[@x='1']", 5);
        assertTrue(s.contains("//button[@x='1']"), s);
    }

    @Test
    void countContinuaUsandoRjCount() {
        assertTrue(ShadowDom.countScript("css:a", "").contains("rjCount("));
    }

    @Test
    void definitionsExpoeSocialQueryComRequiredNetworkESelector() {
        Store s = new Store(tmp.resolve("home" + System.nanoTime()));
        JsonNode defs = new Tools(s).definitions();
        JsonNode query = null;
        for (JsonNode d : defs) {
            if ("social_query".equals(d.path("name").asText(""))) query = d;
        }
        assertNotNull(query, "definitions sem social_query");
        List<String> required = new ArrayList<>();
        for (JsonNode r : query.path("inputSchema").path("required")) required.add(r.asText());
        assertEquals(List.of("network", "selector"), required);
    }
}
