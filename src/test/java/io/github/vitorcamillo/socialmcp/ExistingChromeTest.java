package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parse/selecao/ambiguidades do attach ao Chrome vivo do mesmo profile.
 *
 * <p>Duas camadas: snapshots sinteticos para o parse puro (rapidos e
 * deterministicos) e UM fixture real headless temporario que prova o caminho
 * live ({@code findForProfile}/{@code scanProc}/{@code reread}) contra o
 * /proc do proprio teste. O fixture usa porta livre via ServerSocket,
 * {@code --user-data-dir} temporario com espaco e {@code about:blank}; guarda
 * somente o {@code Process} criado pelo teste e o encerra no {@code finally}
 * com destroy/wait/forcibly limitado. Nunca toca perfis ou PIDs reais.
 */
class ExistingChromeTest {

    @TempDir
    Path tmp;

    private Path profile(String name) {
        return tmp.resolve(".javadriverless_profiles").resolve(name);
    }

    private static ExistingChrome.Snapshot main(long pid, String exe, String... argv) {
        return new ExistingChrome.Snapshot(pid, exe, List.of(argv));
    }

    @Test
    void attachExatoComFlagIgual() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(1614954L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177")));
        assertEquals(Optional.of("127.0.0.1:46177"), addr);
    }

    @Test
    void argsSeparados() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(10L, "/usr/bin/google-chrome-stable",
                        "/usr/bin/google-chrome-stable",
                        "--user-data-dir", dir.toString(),
                        "--remote-debugging-port", "9222")));
        assertEquals(Optional.of("127.0.0.1:9222"), addr);
    }

    @Test
    void argUnicoReescritoComEspacos() {
        Path dir = tmp.resolve("Perfil Social").resolve("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(11L, "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir + " --remote-debugging-port=46177 --no-first-run")));
        assertEquals(Optional.of("127.0.0.1:46177"), addr);
    }

    @Test
    void substringDeOutroProfileNaoCasa() {
        Path dir = profile("sdk-network");
        // sdk-network-extra e OUTRO profile: nada de anexar a ele.
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(12L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + profile("sdk-network-extra"),
                        "--remote-debugging-port=46177")));
        assertTrue(addr.isEmpty(), "sdk-network nao pode casar sdk-network-extra: " + addr);
    }

    @Test
    void rendererComTypeIgnorado() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(13L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--type=renderer",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177")));
        assertTrue(addr.isEmpty(), "processo com --type= nunca e o principal");
    }

    @Test
    void sandboxIgnorado() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(14L, "/opt/google/chrome/chrome-sandbox",
                        "/opt/google/chrome/chrome-sandbox",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177")));
        assertTrue(addr.isEmpty(), "chrome-sandbox nao e navegador");
    }

    @Test
    void exeNaoChromeIgnorado() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(15L, "/usr/bin/python3",
                        "/usr/bin/python3", "--user-data-dir=" + dir, "--remote-debugging-port=46177")));
        assertTrue(addr.isEmpty());
    }

    @Test
    void portaInvalidaIgnorada() {
        Path dir = profile("sdk-network");
        for (String porta : List.of("0", "99999", "abc", "-1")) {
            Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                    main(16L, "/opt/google/chrome/chrome",
                            "/opt/google/chrome/chrome",
                            "--user-data-dir=" + dir,
                            "--remote-debugging-port=" + porta)));
            assertTrue(addr.isEmpty(), "porta " + porta + " devia ser ignorada");
        }
    }

    @Test
    void profileCertoSemPortaNaoAnexa() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(17L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir)));
        assertTrue(addr.isEmpty(), "sem --remote-debugging-port nao ha onde anexar");
    }

    @Test
    void chromiumAceito() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(18L, "/usr/bin/chromium",
                        "/usr/bin/chromium",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=9333")));
        assertEquals(Optional.of("127.0.0.1:9333"), addr);
    }

    @Test
    void trailingSlashNoUserDataDirAindaCasa() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(19L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir + "/",
                        "--remote-debugging-port=46177")));
        assertEquals(Optional.of("127.0.0.1:46177"), addr);
    }

    @Test
    void outroProfileIgnorado() {
        Path dir = profile("sdk-network");
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(20L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + profile("sdk-outra"),
                        "--remote-debugging-port=5555")));
        assertTrue(addr.isEmpty());
    }

    @Test
    void mesmaPortaEmDoisSnapshotsRetorna() {
        Path dir = profile("sdk-network");
        var principal = main(21L, "/opt/google/chrome/chrome",
                "/opt/google/chrome/chrome",
                "--user-data-dir=" + dir,
                "--remote-debugging-port=46177");
        // Duplicata do mesmo processo (scan repetido): mesma porta, sem ambiguidade.
        Optional<ExistingChrome.Selected> sel = ExistingChrome.select(dir, List.of(principal, principal));
        assertTrue(sel.isPresent());
        assertEquals("127.0.0.1:46177", sel.get().debuggerAddress());
        assertEquals(46177, sel.get().port());
    }

    @Test
    void portasDistintasErroClaroSemLancar() {
        Path dir = profile("sdk-network");
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                ExistingChrome.debuggerAddressFor(dir, List.of(
                        main(22L, "/opt/google/chrome/chrome",
                                "/opt/google/chrome/chrome",
                                "--user-data-dir=" + dir,
                                "--remote-debugging-port=46177"),
                        main(23L, "/opt/google/chrome/chrome",
                                "/opt/google/chrome/chrome",
                                "--user-data-dir=" + dir,
                                "--remote-debugging-port=9222"))));
        assertTrue(e.getMessage().contains("46177"), e.getMessage());
        assertTrue(e.getMessage().contains("9222"), e.getMessage());
        assertTrue(e.getMessage().contains("nenhum Chrome novo foi lancado"), e.getMessage());
    }

    @Test
    void listaVaziaSemNada() {
        assertTrue(ExistingChrome.debuggerAddressFor(profile("sdk-network"), List.of()).isEmpty());
    }

    @Test
    void portaLimite1e65535Validas() {
        Path dir = profile("sdk-network");
        assertEquals(Optional.of("127.0.0.1:1"), ExistingChrome.debuggerAddressFor(dir, List.of(
                main(30L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=1"))));
        assertEquals(Optional.of("127.0.0.1:65535"), ExistingChrome.debuggerAddressFor(dir, List.of(
                main(31L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=65535"))));
    }

    @Test
    void porta0e65536Invalidas() {
        Path dir = profile("sdk-network");
        for (String porta : List.of("0", "65536")) {
            Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                    main(32L, "/opt/google/chrome/chrome",
                            "/opt/google/chrome/chrome",
                            "--user-data-dir=" + dir,
                            "--remote-debugging-port=" + porta)));
            assertTrue(addr.isEmpty(), "porta " + porta + " devia ser ignorada");
        }
    }

    @Test
    void flagSeparadaNoFimSemValorIgnorada() {
        assertNull(ExistingChrome.flagValue(List.of("--user-data-dir"), "--user-data-dir"));
        assertNull(ExistingChrome.flagValue(List.of("x"), "--user-data-dir"));
        Path dir = profile("sdk-network");
        // --remote-debugging-port como ultimo arg sem valor: sem crash, sem anexo.
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(33L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port")));
        assertTrue(addr.isEmpty());
        Optional<String> addr2 = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(34L, "/opt/google/chrome/chrome",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--user-data-dir")));
        assertTrue(addr2.isEmpty());
    }

    @Test
    void flagNoIndiceZeroAindaCasa() {
        Path dir = profile("sdk-network");
        // Flag ja no argv[0]: o loop nao pode pular o indice 0.
        Optional<String> addr = ExistingChrome.debuggerAddressFor(dir, List.of(
                main(35L, "/opt/google/chrome/chrome",
                        "--remote-debugging-port=46177",
                        "--user-data-dir=" + dir)));
        assertEquals(Optional.of("127.0.0.1:46177"), addr);
    }

    @Test
    void exeSemBarraEDeletedAceitos() {
        Path dir = profile("sdk-network");
        assertEquals(Optional.of("127.0.0.1:46177"), ExistingChrome.debuggerAddressFor(dir, List.of(
                main(36L, "chrome",
                        "chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177"))));
        assertEquals(Optional.of("127.0.0.1:46177"), ExistingChrome.debuggerAddressFor(dir, List.of(
                main(37L, "/opt/google/chrome/chrome (deleted)",
                        "/opt/google/chrome/chrome",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177"))));
    }

    @Test
    void sandboxRecusadoChromiumValido() {
        Path dir = profile("sdk-network");
        assertTrue(ExistingChrome.debuggerAddressFor(dir, List.of(
                main(38L, "/usr/lib/chromium/chromium-sandbox",
                        "/usr/lib/chromium/chromium-sandbox",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=46177"))).isEmpty(),
                "basename com sandbox nunca e principal");
        assertEquals(Optional.of("127.0.0.1:9333"), ExistingChrome.debuggerAddressFor(dir, List.of(
                main(39L, "/usr/bin/chromium",
                        "/usr/bin/chromium",
                        "--user-data-dir=" + dir,
                        "--remote-debugging-port=9333"))));
    }

    @Test
    void processoRealHeadlessTemporario() throws Exception {
        Path dir = tmp.resolve("perfil com espaco").resolve("sdk-network");
        Files.createDirectories(dir);
        Path vizinho = tmp.resolve("perfil com espaco").resolve("sdk-network-extra");
        Files.createDirectories(vizinho);
        int porta = portaLivre();
        String esperado = "127.0.0.1:" + porta;
        Path chrome = chromeBinario();

        Process proprio = new ProcessBuilder(
                "/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--remote-debugging-port=" + porta,
                "--user-data-dir=" + dir.toAbsolutePath(),
                "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            long pidReal = proprio.pid();
            // Polling 20s/100ms: Chrome demora a publicar o cmdline no /proc.
            Optional<String> addr = Optional.empty();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                addr = ExistingChrome.findForProfile(dir);
                if (addr.map(esperado::equals).orElse(false)) break;
                Thread.sleep(100);
            }
            assertEquals(Optional.of(esperado), addr, "findForProfile devia achar o Chrome real");

            // scanProc acha o processo principal com o PID proprio.
            Optional<ExistingChrome.Snapshot> noScan = Optional.empty();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                noScan = ExistingChrome.scanProc().stream()
                        .filter(s -> s.pid() == pidReal)
                        .findFirst();
                if (noScan.isPresent()) break;
                Thread.sleep(100);
            }
            assertTrue(noScan.isPresent(), "scanProc devia conter o PID proprio " + pidReal);
            Optional<ExistingChrome.Selected> sel =
                    ExistingChrome.select(dir, List.of(noScan.get()));
            assertTrue(sel.isPresent());
            assertEquals(pidReal, sel.get().pid());
            assertEquals(porta, sel.get().port());

            // Perfil vizinho (sdk-network-extra) nao casa com o processo real.
            assertTrue(ExistingChrome.findForProfile(vizinho).isEmpty(),
                    "perfil vizinho nao pode anexar ao Chrome real");

            // PID inexistente: releitura vazia, sem excecao.
            long inexistente = 987654321L;
            if (inexistente == pidReal) inexistente++;
            assertTrue(ExistingChrome.reread(inexistente).isEmpty());

            // Preservacao ate o finally: o teste nao mata o proprio processo antes.
            assertTrue(proprio.isAlive(), "processo proprio devia estar vivo ate o finally");
        } finally {
            encerrarSomenteProprio(proprio);
        }
    }

    private static int portaLivre() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Path chromeBinario() {
        for (String cand : List.of(
                "/opt/google/chrome/chrome",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser")) {
            Path p = Path.of(cand);
            if (Files.isExecutable(p)) return p;
        }
        throw new IllegalStateException("nenhum binario Chrome/Chromium executavel encontrado");
    }

    private static void encerrarSomenteProprio(Process proprio) {
        if (proprio == null || !proprio.isAlive()) return;
        proprio.destroy();
        try {
            if (!proprio.waitFor(5, TimeUnit.SECONDS)) {
                proprio.destroyForcibly();
                proprio.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proprio.destroyForcibly();
        }
    }
}
