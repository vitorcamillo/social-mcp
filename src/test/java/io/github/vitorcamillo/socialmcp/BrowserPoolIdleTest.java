package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fechamento de Chromes ociosos (um Chrome por rede, liberado quando parado).
 * Nada aqui lanca navegador: idleNetworks e puro e o closeIdle roda sem driver.
 */
class BrowserPoolIdleTest {

    @TempDir
    Path tmp;

    @Test
    void idleNetworksEntraESai() {
        Map<String, Long> lastUse = new HashMap<>();
        long now = 1_000_000L;
        long dezMin = 10 * 60_000L;
        lastUse.put("velha", now - 11 * 60_000L);
        lastUse.put("recente", now - 9 * 60_000L);
        lastUse.put("desligada", now - 60 * 60_000L);
        List<String> ociosas = BrowserPool.idleNetworks(lastUse, now,
                n -> "desligada".equals(n) ? 0L : dezMin);
        assertEquals(List.of("velha"), ociosas);
    }

    @Test
    void idleZeroNuncaEntra() {
        Map<String, Long> lastUse = new HashMap<>();
        lastUse.put("a", 0L);
        assertTrue(BrowserPool.idleNetworks(lastUse, 999_999_999L, n -> 0L).isEmpty());
    }

    @Test
    void resultadoOrdenado() {
        Map<String, Long> lastUse = new HashMap<>();
        lastUse.put("zeta", 0L);
        lastUse.put("alfa", 0L);
        lastUse.put("meio", 0L);
        List<String> ociosas = BrowserPool.idleNetworks(lastUse, 60_000L, n -> 60_000L);
        assertEquals(List.of("alfa", "meio", "zeta"), ociosas);
    }

    @Test
    void semDriverAbertoCloseIdleVazio() throws Exception {
        Store store = new Store(tmp);
        BrowserPool pool = new BrowserPool(store, () -> 0L);
        assertEquals(List.of(), pool.closeIdle());
        assertEquals(0, pool.openCount());
    }
}
