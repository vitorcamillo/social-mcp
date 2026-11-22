package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes que fixam o comportamento observavel exato da NavGuard (falha de instalacao,
 * limpeza suprimida, log em stderr e propagacao de future) para matar mutantes de PIT.
 * O caminho TargetCdp usa um Chrome headless real no padrao de fixture local; o resto
 * usa um CDP falso, como NavGuardTest.
 */
class NavGuardMutantesTest {

    @TempDir
    Path tmp;

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
        /** Comando que lanca RuntimeException sincrona em executeCdpCmd. */
        String comandoComExcecaoSincrona;
        /** Comando cujo future completa com excecao (whenComplete recebe erro). */
        String comandoComFutureFalho;
        /** Fetch.enable lanca RuntimeException sincrona. */
        boolean falharNoEnable;
        /** removeCdpListener lanca RuntimeException (falha de limpeza no close). */
        boolean falharAoRemoverListener;
        /** add/removeCdpListener devolvem future que completa com excecao. */
        boolean addListenerComFutureFalho;
        boolean removeListenerComFutureFalho;
        /** Fetch.disable devolve future que nunca completa (o get() do close fica esperando). */
        boolean disableNuncaCompleta;

        @Override public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> l) {
            listeners.put(event, l);
            if (addListenerComFutureFalho) return CompletableFuture.failedFuture(new RuntimeException("add falhou"));
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> l) {
            if (falharAoRemoverListener) throw new RuntimeException("remove recusado");
            listeners.remove(event, l);
            if (removeListenerComFutureFalho) return CompletableFuture.failedFuture(new RuntimeException("remove falhou"));
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<JsonNode> executeCdpCmd(String comando, Map<String, Object> params, Float timeout) {
            if (falharNoEnable && "Fetch.enable".equals(comando)) {
                throw new RuntimeException("sem Fetch neste Chrome");
            }
            if (comando.equals(comandoComExcecaoSincrona)) {
                throw new RuntimeException("socket fechado");
            }
            if (comando.equals(comandoComFutureFalho)) {
                return CompletableFuture.failedFuture(new RuntimeException("falha no comando " + comando));
            }
            if (disableNuncaCompleta && "Fetch.disable".equals(comando)) {
                return new CompletableFuture<>();
            }
            comandos.add(new Cmd(comando, params));
            return CompletableFuture.completedFuture(Json.obj());
        }

        /** Dispara um Fetch.requestPaused como o Chrome faria. */
        void pausar(String requestId, String resourceType, String url) {
            Consumer<JsonNode> l = listeners.get(NavGuard.EVENTO);
            assertNotNull(l, "listener de Fetch.requestPaused deve estar registrado");
            l.accept(Json.parse("{\"requestId\":\"" + requestId + "\",\"resourceType\":\"" + resourceType
                    + "\",\"frameId\":\"" + PRINCIPAL + "\",\"request\":{\"url\":\"" + url + "\"}}"));
        }
    }

    private static String capturarStderr(Runnable acao) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            acao.run();
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    // ---------- falha de instalacao ----------

    @Test
    void falhaNaLimpezaEntraComoSuprimidaNaFalhaDeInstalacao() {
        FakeCdp cdp = new FakeCdp();
        cdp.falharNoEnable = true;
        cdp.falharAoRemoverListener = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NavGuard.instalar(cdp, PRINCIPAL, REDE));
        assertEquals("Nao foi possivel instalar a guarda de navegacao", e.getMessage());
        assertEquals("sem Fetch neste Chrome", e.getCause().getMessage());
        Throwable[] sup = e.getCause().getSuppressed();
        assertEquals(1, sup.length, "a falha ao desarmar nao pode se perder");
        assertInstanceOf(IllegalStateException.class, sup[0]);
        assertEquals("Falha ao desarmar a guarda de navegacao", sup[0].getMessage());
    }

    @Test
    void disableQueFalhaMantemListenerRespondendo() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.comandoComExcecaoSincrona = "Fetch.disable";
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::close);
        assertEquals("Falha ao desarmar a guarda de navegacao", e.getMessage());
        cdp.comandoComExcecaoSincrona = null;
        cdp.pausar("r1", "Document", "https://fora.example/");
        assertEquals("Fetch.failRequest", cdp.comandos.get(cdp.comandos.size() - 1).comando(),
                "com o Fetch possivelmente ligado, a pausa ainda precisa de resposta");
    }

    @Test
    void futureFalhoNaInstalacaoPropagaComoFalha() {
        FakeCdp cdp = new FakeCdp();
        cdp.comandoComFutureFalho = "Fetch.enable";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NavGuard.instalar(cdp, PRINCIPAL, REDE));
        assertEquals("Nao foi possivel instalar a guarda de navegacao", e.getMessage());
        assertInstanceOf(ExecutionException.class, e.getCause(), "await propaga a falha do future");
    }

    @Test
    void futureFalhoAoRegistrarListenerImpedeAInstalacao() {
        FakeCdp cdp = new FakeCdp();
        cdp.addListenerComFutureFalho = true;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NavGuard.instalar(cdp, PRINCIPAL, REDE));
        assertEquals("Nao foi possivel instalar a guarda de navegacao", e.getMessage());
        assertInstanceOf(ExecutionException.class, e.getCause());
        assertEquals("add falhou", e.getCause().getCause().getMessage());
    }

    @Test
    void futureFalhoNoFetchDisableFazOCloseFalhar() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.comandoComFutureFalho = "Fetch.disable";
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::close);
        assertEquals("Falha ao desarmar a guarda de navegacao", e.getMessage());
        assertEquals("falha no comando Fetch.disable", e.getCause().getCause().getMessage());
    }

    @Test
    void futureFalhoAoRemoverListenerFazOCloseFalhar() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.removeListenerComFutureFalho = true;
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::close);
        assertEquals("Falha ao desarmar a guarda de navegacao", e.getMessage());
        assertEquals("remove falhou", e.getCause().getCause().getMessage());
    }

    // ---------- log em stderr da resposta ao Chrome ----------

    @Test
    void falhaAssincronaAoResponderEhEscritaNoStderr() {
        FakeCdp cdp = new FakeCdp();
        cdp.comandoComFutureFalho = "Fetch.failRequest";
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        try {
            String saida = capturarStderr(() -> cdp.pausar("r1", "Document", "https://evil.example/?x=1"));
            assertTrue(saida.contains("guarda de navegacao: Fetch.failRequest falhou:"), saida);
        } finally {
            guard.close();
        }
    }

    @Test
    void respostaBemSucedidaNaoEscreveNoStderr() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        try {
            String saida = capturarStderr(() -> cdp.pausar("r2", "Document", "https://rede.example/pagina"));
            assertFalse(saida.contains("guarda de navegacao"), saida);
        } finally {
            guard.close();
        }
    }

    @Test
    void excecaoSincronaAoResponderEhEscritaNoStderr() {
        FakeCdp cdp = new FakeCdp();
        cdp.comandoComExcecaoSincrona = "Fetch.continueRequest";
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        try {
            String saida = capturarStderr(() -> cdp.pausar("r3", "Document", "https://rede.example/outra"));
            assertTrue(saida.contains("guarda de navegacao: Fetch.continueRequest nao saiu:"), saida);
        } finally {
            guard.close();
        }
    }

    /** close() com a thread interrompida: falha com motivo proprio e devolve a interrupcao ao chamador. */
    @Test
    void closeInterrompidoPreservaAFlagDeInterrupcao() {
        FakeCdp cdp = new FakeCdp();
        NavGuard guard = NavGuard.instalar(cdp, PRINCIPAL, REDE);
        cdp.disableNuncaCompleta = true;
        Thread.currentThread().interrupt();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, guard::close);
            assertEquals("Desarme da guarda de navegacao interrompido", e.getMessage());
            assertTrue(e.getCause() instanceof InterruptedException, String.valueOf(e.getCause()));
            assertTrue(Thread.currentThread().isInterrupted(), "close() engoliu a interrupcao");
        } finally {
            Thread.interrupted();
        }
    }

    // ---------- Chrome headless real: TargetCdp e instalar(Target, ...) ----------

    /**
     * Exercita o caminho real TargetCdp (add/remove listener e executeCdpCmd) e o
     * instalar(Target, ...) publico: um goto para porta fechada em loopback nao sai
     * (drenarBloqueada devolve a url) e close() desarma a guarda.
     */
    @Test
    void guardaRealBloqueiaGotoEDesarmaNoClose() throws Exception {
        Path perfil = tmp.resolve("perfil");
        Files.createDirectories(perfil);
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort();
        }
        Path chrome = null;
        for (String cand : List.of("/opt/google/chrome/chrome", "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            if (Files.isExecutable(Path.of(cand))) {
                chrome = Path.of(cand);
                break;
            }
        }
        Assumptions.assumeTrue(chrome != null, "sem binario Chrome/Chromium");
        // Chrome headless proprio (como ExistingChromeTest) e attach pela lib: o launch
        // estruturado exige Chrome visivel, o attach nao.
        Process proprio = new ProcessBuilder("/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=" + porta, "--user-data-dir=" + perfil.toAbsolutePath(), "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean pronto = false;
            while (!pronto && System.nanoTime() < prazo) {
                try (Socket s = new Socket("127.0.0.1", porta)) {
                    pronto = true;
                } catch (java.io.IOException ainda) {
                    Thread.sleep(100);
                }
            }
            assertTrue(pronto, "Chrome headless nao abriu a porta de depuracao");
            ChromeOptions options = BrowserPool.buildAttachOptions("navguard-teste", Json.obj(),
                    perfil.toString(), "127.0.0.1:" + porta);
            JavaDriverless d = new JavaDriverless("navguard-teste", options, false);
            try {
                d.setPageLoadTimeout(10_000);
                Target target = d.getChrome().getCurrentTarget().get(5, TimeUnit.SECONDS);
                NavGuard guard = NavGuard.instalar(target, url -> false);
                try {
                    try {
                        d.get("http://127.0.0.1:1/");
                    } catch (Exception esperado) {
                        // failRequest(BlockedByClient) aborta o goto
                    }
                    assertEquals("http://127.0.0.1:1/", guard.drenarBloqueada(),
                            "goto fora da rede deve ser bloqueado e registrado pela guarda real");
                } finally {
                    guard.close();
                }
                try {
                    d.get("http://127.0.0.1:1/");
                } catch (Exception esperado) {
                    // apos o close a conexao e recusada sem registro da guarda
                }
                assertNull(guard.drenarBloqueada(), "close() desarma: a mesma navegacao nao fica registrada");
            } finally {
                d.quit();
            }
        } finally {
            proprio.descendants().forEach(ProcessHandle::destroyForcibly);
            proprio.destroyForcibly();
            proprio.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
