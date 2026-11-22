package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia Chrome E: mutantes de {@link BrowserPool#applyWindowSize} que so divergem quando o
 * viewport (innerWidth) sai menor que a largura pedida. O ChromeFalso e anexado e uma subclasse
 * de BrowserPool sobrescreve o seam {@code criarDriver} para, antes de devolver o driver,
 * emular um viewport 50 px menor nos dois eixos (Emulation.setDeviceMetricsOverride). O codigo
 * atual mede o viewport menor e reaplica o tamanho com +50; os mutantes deixam a janela no
 * tamanho pedido.
 */
class BrowserPoolChromeTest {

    private static final String REDE = "bluesky";
    private static final int LARGURA = 1200;
    private static final int ALTURA = 800;
    private static final int SOMBRA_EMULADA = 50;

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;

    @BeforeAll
    static void abrirFixture() throws Exception {
        store = new Store(tmp.resolve("home"));
        cf = ChromeFalso.abrir(tmp, store);
        // windowSize da rede vem de networks.bluesky.windowSize (default 1280x900).
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ((ObjectNode) raiz.path("networks").path(REDE)).put("windowSize", LARGURA + "x" + ALTURA);
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    @AfterAll
    static void fecharFixture() {
        if (cf != null) cf.close();
    }

    /**
     * Pool real que emula um viewport 50 px menor logo apos criar o driver: o Chrome e
     * externo (ChromeFalso), entao o attach e imediato e o target atual existe.
     */
    static final class PoolComSombra extends BrowserPool {
        PoolComSombra(Store store) {
            super(store);
        }

        @Override
        JavaDriverless criarDriver(String n, ChromeOptions options) {
            JavaDriverless d = super.criarDriver(n, options);
            try {
                Target t = d.getChrome().getCurrentTarget().get(5, TimeUnit.SECONDS);
                Map<String, Object> p = new HashMap<>();
                p.put("width", LARGURA - SOMBRA_EMULADA);
                p.put("height", ALTURA - SOMBRA_EMULADA);
                p.put("deviceScaleFactor", 1);
                p.put("mobile", false);
                t.executeCdpCmd("Emulation.setDeviceMetricsOverride", p, 5f)
                        .get(7, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("emulacao do viewport interrompida", e);
            } catch (Exception e) {
                throw new IllegalStateException("nao emulou viewport menor em " + n, e);
            }
            return d;
        }
    }

    @Test
    @Timeout(60)
    void compensaSombraEmuladaEReaplicaJanelaComMais50() throws Exception {
        PoolComSombra pool = new PoolComSombra(store);
        try {
            JavaDriverless d = pool.get(REDE);
            Target t = d.getChrome().getCurrentTarget().get(5, TimeUnit.SECONDS);

            Integer larguraJanela = null;
            Integer alturaJanela = null;
            try {
                JsonNode janela = t.executeCdpCmd("Browser.getWindowForTarget", Map.of(), 5f)
                        .get(7, TimeUnit.SECONDS);
                int w = janela.path("bounds").path("width").asInt(-1);
                int h = janela.path("bounds").path("height").asInt(-1);
                if (w > 0 && h > 0) {
                    larguraJanela = w;
                    alturaJanela = h;
                }
            } catch (Exception semGetWindow) {
                // Headless sem Browser.getWindowForTarget: prova alternativa abaixo.
            }

            if (larguraJanela != null) {
                assertEquals(LARGURA + SOMBRA_EMULADA, larguraJanela.intValue(),
                        "bounds.width da janela compensada");
                assertEquals(ALTURA + SOMBRA_EMULADA, alturaJanela.intValue(),
                        "bounds.height da janela compensada");
            } else {
                t.executeCdpCmd("Emulation.clearDeviceMetricsOverride", Map.of(), 5f)
                        .get(7, TimeUnit.SECONDS);
                assertEquals(LARGURA + SOMBRA_EMULADA, numero(d.executeScript("window.innerWidth")),
                        "innerWidth apos limpar a emulacao");
                assertEquals(ALTURA + SOMBRA_EMULADA, numero(d.executeScript("window.innerHeight")),
                        "innerHeight apos limpar a emulacao");
            }
        } finally {
            pool.closeAll();
        }
    }

    private static int numero(Object o) {
        assertInstanceOf(Number.class, o, "medida do viewport nao numerica: " + o);
        return ((Number) o).intValue();
    }
}
