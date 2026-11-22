package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BrowserPoolWindowSizeTest {

    @Test
    void padraoE1280x900() {
        assertEquals(new BrowserPool.WindowSize(1280, 900), BrowserPool.DEFAULT_WINDOW);
    }

    @Test
    void formatoValido() {
        assertEquals(new BrowserPool.WindowSize(1920, 1080), BrowserPool.parseWindowSize("1920x1080"));
        assertEquals(new BrowserPool.WindowSize(1366, 768), BrowserPool.parseWindowSize(" 1366 X 768 "));
        assertEquals(new BrowserPool.WindowSize(400, 7680), BrowserPool.parseWindowSize("400x7680"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "1280", "1280x", "x900", "1280,900", "1280*900", "abcxdef",
            "-1280x900", "1280x900x2", "12.5x900", "399x900", "1280x399", "7681x900", "99999x900"})
    void invalidoCaiNoPadrao(String raw) {
        assertEquals(BrowserPool.DEFAULT_WINDOW, BrowserPool.parseWindowSize(raw));
    }

    @Test
    void configDaRedeSobrepoeDefaults() {
        // networkCfg faz merge defaults + rede; a rede vence.
        var defaults = Json.obj().put("windowSize", "1280x900");
        var merged = Json.obj();
        merged.setAll(defaults);
        merged.setAll(Json.obj().put("windowSize", "1600x1000"));
        assertEquals(new BrowserPool.WindowSize(1600, 1000),
                BrowserPool.parseWindowSize(merged.path("windowSize").asText(null)));
    }

    @Test
    void defaultConfigTrazWindowSize() throws Exception {
        String ws = Json.parse(Store.DEFAULT_CONFIG).path("defaults").path("windowSize").asText();
        assertEquals("1280x900", ws);
    }

    @Test
    void compensaSombraDoFrame() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertEquals(new BrowserPool.WindowSize(1356, 976), BrowserPool.compensateShadow(alvo, 1204));
    }

    @Test
    void semSombraOuDiferencaAbsurdaNaoMexe() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertSame(alvo, BrowserPool.compensateShadow(alvo, 1280), "sem sombra devolve o proprio alvo");
        assertEquals(alvo, BrowserPool.compensateShadow(alvo, 1300));
        assertEquals(alvo, BrowserPool.compensateShadow(alvo, 1000));
    }
}
