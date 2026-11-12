package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProbeTest {

    @TempDir
    Path tmp;

    @Test
    void probeExecutaSoPassosDeLeituraDoInicio() {
        for (String kind : List.of("goto", "wait", "verify_text", "verify_attr", "capture_ids")) {
            assertTrue(Tools.probeExecuta(kind), kind);
        }
        for (String kind : List.of("click", "type", "key", "set_file", "request",
                "verify_new_id", "verify_absent", "extract")) {
            assertFalse(Tools.probeExecuta(kind), kind);
        }
    }

    @Test
    void definitionsExpoeSocialProbeComRequiredNetworkEAction() {
        Store s = new Store(tmp.resolve("home" + System.nanoTime()));
        JsonNode defs = new Tools(s).definitions();
        JsonNode probe = null;
        for (JsonNode d : defs) {
            if ("social_probe".equals(d.path("name").asText(""))) probe = d;
        }
        assertNotNull(probe, "definitions sem social_probe");
        List<String> required = new ArrayList<>();
        for (JsonNode r : probe.path("inputSchema").path("required")) required.add(r.asText());
        assertEquals(List.of("network", "action"), required);
    }
}
