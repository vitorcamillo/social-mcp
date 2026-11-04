package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserPoolOptionsTest {

    /**
     * buildOptions sob limite de memoria do cgroup (ex.: fila heavy): a lib recusa o preset
     * nativo (deviceMemory nao garantido). E restricao do ambiente, nao do codigo: pula o teste.
     */
    private static ChromeOptions opts(ObjectNode cfg) {
        try {
            return BrowserPool.buildOptions("mastodon", cfg, null);
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            Assumptions.assumeFalse(m.contains("cgroup") && m.contains("memory.max"),
                    "ambiente com limite de memoria do cgroup: " + m);
            throw e;
        }
    }

    @Test
    void optionsNativasComPaisPadrao() {
        ObjectNode cfg = Json.obj();
        ChromeOptions o = opts(cfg);
        assertNotNull(o);
    }

    @Test
    void proxySimplesNormalizado() {
        ObjectNode cfg = Json.obj();
        cfg.put("proxy", "10.0.0.1:8080");
        ChromeOptions o = opts(cfg);
        assertEquals("http://10.0.0.1:8080/", o.getSingleProxy());
    }

    @Test
    void proxyComEsquemaMantido() {
        ObjectNode cfg = Json.obj();
        cfg.put("proxy", "socks5://10.0.0.2:1080");
        ChromeOptions o = opts(cfg);
        assertEquals("socks5://10.0.0.2:1080/", o.getSingleProxy());
    }

    @Test
    void proxyAutenticadoUsaBridge() {
        ObjectNode cfg = Json.obj();
        cfg.put("proxy", "http://user:senha@10.0.0.3:8080");
        ChromeOptions o = opts(cfg);
        assertTrue(o.isAuthenticatedProxyBridgeEnabled());
    }

    @Test
    void semProxyNaoConfiguraNada() {
        ObjectNode cfg = Json.obj();
        cfg.put("proxy", "");
        ChromeOptions o = opts(cfg);
        assertFalse(o.isAuthenticatedProxyBridgeEnabled());
    }

    @Test
    void windowSizeVaiComoArgumento() {
        ObjectNode cfg = Json.obj();
        cfg.put("windowSize", "1600x1000");
        ChromeOptions o = opts(cfg);
        assertTrue(o.getArguments().contains("--window-size=1600,1000"), o.getArguments().toString());
    }

    @Test
    void windowSizeInvalidoUsaPadrao() {
        ObjectNode cfg = Json.obj();
        cfg.put("windowSize", "grande");
        ChromeOptions o = opts(cfg);
        assertTrue(o.getArguments().contains("--window-size=1280,900"), o.getArguments().toString());
    }
}
