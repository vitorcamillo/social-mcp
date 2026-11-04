package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in de reaproveitamento do Chrome vivo (attachExistingChrome).
 * Nada aqui lanca navegador: resolveAttach e puro e os builders de opcoes so
 * montam objetos (sem JavaDriverless, sem /proc, sem CDP).
 */
class BrowserPoolAttachTest {

    @TempDir
    Path tmp;

    private static ObjectNode cfg(boolean optIn) {
        ObjectNode c = Json.obj();
        if (optIn) c.put("attachExistingChrome", true);
        return c;
    }

    /**
     * Builders sob limite de memoria do cgroup: a lib recusa o preset nativo.
     * E restricao do ambiente, nao do codigo (mesma guarda de BrowserPoolOptionsTest).
     */
    private static <T> T presetOuPula(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            Assumptions.assumeFalse(m.contains("cgroup") && m.contains("memory.max"),
                    "ambiente com limite de memoria do cgroup: " + m);
            throw e;
        }
    }

    @Test
    void optInDefaultFalse() {
        assertFalse(BrowserPool.attachOptIn(Json.obj()));
        assertFalse(BrowserPool.attachOptIn(cfg(false)));
        assertTrue(BrowserPool.attachOptIn(cfg(true)));
    }

    @Test
    void defaultRecusaChromeVivoSemLancar() {
        ObjectNode c = cfg(false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                BrowserPool.resolveAttach("tiktok", c, tmp.toString(), Optional.of("127.0.0.1:46177")));
        String m = e.getMessage();
        assertTrue(m.contains("attachExistingChrome"), m);
        assertTrue(m.contains("127.0.0.1:46177"), m);
        assertTrue(m.contains("nenhum Chrome novo"), m);
    }

    @Test
    void semChromeDetectadoSegueNormal() {
        assertTrue(BrowserPool.resolveAttach("tiktok", cfg(false), tmp.toString(), Optional.empty()).isEmpty());
        assertTrue(BrowserPool.resolveAttach("tiktok", cfg(true), tmp.toString(), Optional.empty()).isEmpty());
    }

    @Test
    void optInDevolveEnderecoLoopbackExato() {
        assertEquals(Optional.of("127.0.0.1:46177"),
                BrowserPool.resolveAttach("tiktok", cfg(true), tmp.toString(), Optional.of("127.0.0.1:46177")));
    }

    @Test
    void attachProfileSetaLegacySpoof() {
        var p = presetOuPula(() -> BrowserPool.buildAttachProfile("tiktok", cfg(true), tmp.toString()));
        assertEquals("legacy-spoof", p.fingerprint.runtimePolicy);
        assertEquals(tmp.toString(), p.storage.userDataDir);
    }

    @Test
    void attachOptionsSetaDebuggerAddress() {
        ChromeOptions o = presetOuPula(() ->
                BrowserPool.buildAttachOptions("tiktok", cfg(true), tmp.toString(), "127.0.0.1:46177"));
        assertEquals("127.0.0.1:46177", o.getDebuggerAddress());
    }

    @Test
    void attachPreservaProxyEJanela() {
        ObjectNode c = cfg(true);
        c.put("proxy", "10.0.0.1:8080");
        c.put("windowSize", "1600x1000");
        ChromeOptions o = presetOuPula(() ->
                BrowserPool.buildAttachOptions("tiktok", c, tmp.toString(), "127.0.0.1:46177"));
        assertEquals("http://10.0.0.1:8080/", o.getSingleProxy());
        assertTrue(o.getArguments().contains("--window-size=1600,1000"), o.getArguments().toString());
    }

    @Test
    void launchNormalMantemMinimal() {
        var p = presetOuPula(() -> BrowserPool.buildProfile("tiktok", cfg(false), tmp.toString()));
        assertEquals(BrowserPool.LAUNCH_RUNTIME_POLICY, p.fingerprint.runtimePolicy);
        assertEquals("minimal-cdp", p.fingerprint.runtimePolicy);
    }
}
