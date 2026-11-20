package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guarda de navegacao: todo DOCUMENTO (goto, redirect HTTP, meta/JS tardio) e
 * decidido no Fetch.requestPaused, ANTES de a requisicao sair — fora da rede =
 * failRequest(BlockedByClient), nada (nem query) chega ao destino.
 */
class NavGuardTest {

    /** Como BatchStore.urlNavegavel, so que para rede.example: so https no host da rede. */
    private static final Predicate<String> REDE = url -> {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equals("rede.example") || host.endsWith(".rede.example"));
        } catch (RuntimeException e) {
            return false;
        }
    };

    private static final String PRINCIPAL = "FRAME-PRINCIPAL";

    private static final class FakeCdp implements NavGuard.Cdp {
        record Cmd(String comando, Map<String, Object> params) {}

        final List<Cmd> comandos = new CopyOnWriteArrayList<>();
        final Map<String, Consumer<JsonNode>> listeners = new ConcurrentHashMap<>();
        boolean falharNoEnable;

        @Override public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> l) {
            listeners.put(event, l);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> l) {
            listeners.remove(event, l);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<JsonNode> executeCdpCmd(String comando, Map<String, Object> params, Float timeout) {
            if (falharNoEnable && "Fetch.enable".equals(comando)) {
                throw new RuntimeException("sem Fetch neste Chrome");
            }
            comandos.add(new Cmd(comando, params));
            return CompletableFuture.completedFuture(Json.obj());
        }

        /** Dispara um Fetch.requestPaused como o Chrome faria (redirect, subrecurso etc.). */
        void pausar(String requestId, String resourceType, String url) {
            pausarNoFrame(requestId, resourceType, url, PRINCIPAL);
        }

        void pausarNoFrame(String requestId, String resourceType, String url, String frameId) {
            Consumer<JsonNode> l = listeners.get(NavGuard.EVENTO);
            assertNotNull(l, "listener de Fetch.requestPaused deve estar registrado");
            l.accept(Json.parse("{\"requestId\":\"" + requestId + "\",\"resourceType\":\"" + resourceType
                    + "\",\"frameId\":\"" + frameId + "\",\"request\":{\"url\":\"" + url + "\"}}"));
        }

        boolean falhou(String requestId) {
            return comandos.stream().anyMatch(c -> "Fetch.failRequest".equals(c.comando())
                    && requestId.equals(c.params().get("requestId"))
                    && "BlockedByClient".equals(c.params().get("errorReason")));
        }

        boolean continuou(String requestId) {
            return comandos.stream().anyMatch(c -> "Fetch.continueRequest".equals(c.comando())
                    && requestId.equals(c.params().get("requestId")));
        }
    }

    @Test
    void habilitaFetchNoEstagioDeRequisicao() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        guard.close();
        FakeCdp.Cmd enable = cdp.comandos.stream().filter(c -> "Fetch.enable".equals(c.comando())).findFirst().orElseThrow();
        assertTrue(enable.params().get("patterns").toString().contains("Request"), enable.params().toString());
        assertTrue(enable.params().get("patterns").toString().contains("*"), enable.params().toString());
        assertTrue(enable.params().get("patterns").toString().contains("Document"), "so pausa documento");
        assertTrue(cdp.comandos.stream().anyMatch(c -> "Fetch.disable".equals(c.comando())));
        assertTrue(cdp.listeners.isEmpty(), "close remove o listener");
    }

    @Test
    void redirectParaForaDaRedeFalhaAntesDaRequisicao() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        // redirect HTTP do host permitido para fora, com dado sensivel na query
        cdp.pausar("r1", "Document", "https://evil.example/?segredo=dados");
        assertTrue(cdp.falhou("r1"), "failRequest(BlockedByClient) para documento fora da rede");
        assertFalse(cdp.comandos.stream().anyMatch(c -> "Fetch.continueRequest".equals(c.comando())
                && "r1".equals(c.params().get("requestId"))), "requisicao nao pode seguir");
        assertEquals("https://evil.example/?segredo=dados", guard.drenarBloqueada());
        assertNull(guard.drenarBloqueada(), "drain consome o registro");
        guard.close();
    }

    @Test
    void downgradeParaHttpTambemEBarredado() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.pausar("r2", "Document", "http://rede.example/x");
        assertTrue(cdp.falhou("r2"), "so https: downgrade http e barrado antes de sair");
        assertEquals("http://rede.example/x", guard.drenarBloqueada());
        guard.close();
    }

    @Test
    void documentoDentroDaRedeSegue() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.pausar("r3", "Document", "https://rede.example/pagina");
        assertTrue(cdp.continuou("r3"));
        assertFalse(cdp.falhou("r3"));
        assertNull(guard.drenarBloqueada());
        guard.close();
    }

    @Test
    void subrecursoESquemaLocalSeguemSemValidar() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.pausar("r4", "XHR", "https://evil.example/api?x=1");
        cdp.pausar("r5", "Image", "http://evil.example/img.png");
        cdp.pausar("r6", "Document", "about:blank");
        assertTrue(cdp.continuou("r4"));
        assertTrue(cdp.continuou("r5"));
        assertTrue(cdp.continuou("r6"), "about:blank (saida de emergencia) segue");
        assertNull(guard.drenarBloqueada());
        guard.close();
    }

    @Test
    void redirectTardioContinuaBarradoAposAConferencia() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.pausar("r7", "Document", "https://rede.example/inicio");
        assertTrue(cdp.continuou("r7"));
        assertNull(guard.drenarBloqueada(), "conferencia do destino logo apos navegar: nada bloqueado");
        // location/meta/JS disparado DEPOIS da checagem: a guarda segue armada
        cdp.pausar("r8", "Document", "https://evil.example/?segredo=tardio");
        assertTrue(cdp.falhou("r8"), "redirect tardio e barrado antes de sair");
        assertEquals("https://evil.example/?segredo=tardio", guard.drenarBloqueada());
        guard.close();
    }

    @Test
    void iframeDeTerceiroSegueMasFramePrincipalNao() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        // youtube/reddit/pinterest embutem accounts.google.com, google.com e recaptcha.net
        cdp.pausarNoFrame("i1", "Document", "https://accounts.google.com/RotateCookiesPage", "IFRAME-1");
        assertTrue(cdp.continuou("i1"), "iframe de terceiro nao derruba a pagina");
        assertNull(guard.drenarBloqueada());
        cdp.pausarNoFrame("i2", "Document", "https://evil.example/?x=1", "");
        assertTrue(cdp.falhou("i2"), "sem frameId = trata como principal");
        assertEquals("https://evil.example/?x=1", guard.drenarBloqueada());
        guard.close();
    }

    @Test
    void instalacaoSemFetchNaoDeixaListenerAtivo() {
        FakeCdp cdp = new FakeCdp();
        cdp.falharNoEnable = true;
        assertThrows(IllegalStateException.class, () -> NavGuard.instalar(cdp, PRINCIPAL, REDE));
        assertTrue(cdp.listeners.isEmpty(), "falha no install remove o listener");
    }
}
