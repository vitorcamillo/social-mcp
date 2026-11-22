package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mata mutantes de {@link TypeText} (PIT) nas partes puras: limite exato da
 * pausa e o alvo de cada pedaco (1 + nextInt), que o mutante troca por
 * subtracao. O laco do insert roda sem Chrome (Envio/Pausa falsos) e um teste
 * final usa Chrome headless anexado para provar o caminho CDP real.
 */
class TypeTextMutantesTest {

    @TempDir
    Path tmp;

    @Test
    void pausaAceitaExatamente5000ComoMaximo() {
        assertArrayEquals(new long[]{100, 5000},
                TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[100,5000]}")),
                "max == 5000 ainda e valido");
        assertArrayEquals(new long[]{5000, 5000},
                TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[5000,5000]}")),
                "max == 5000 com min igual");
        assertArrayEquals(new long[]{0, 5000},
                TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[-10,5000]}")),
                "min negativo sobe para 0 e max 5000 continua valido");
        assertArrayEquals(TypeText.DEFAULT_CHUNK_DELAY_MS,
                TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[100,5001]}")),
                "max == 5001 cai no padrao");
    }

    @Test
    void primeiroPedacoUsaUmMaisOSorteio() {
        for (int seed = 0; seed < 50; seed++) {
            int esperado = 1 + new Random(seed).nextInt(TypeText.MAX_CHUNK);
            List<String> partes = TypeText.chunks("abcdefghij", new Random(seed));
            assertEquals(esperado, partes.get(0).length(),
                    "seed " + seed + ": primeiro pedaco deve ter 1 + nextInt(8): " + partes);
        }
    }

    @Test
    void alvoDepoisDaQuebraDeLinhaUsaUmMaisOSorteio() {
        for (int seed = 0; seed < 50; seed++) {
            Random r = new Random(seed);
            r.nextInt(TypeText.MAX_CHUNK); // alvo inicial consumido antes do loop
            int esperado = 1 + r.nextInt(TypeText.MAX_CHUNK); // alvo recalculado no "\n"
            List<String> partes = TypeText.chunks("\nabcdefghij", new Random(seed));
            assertEquals("\n", partes.get(0), "seed " + seed);
            assertEquals(esperado, partes.get(1).length(),
                    "seed " + seed + ": alvo apos quebra de linha deve ser 1 + nextInt(8): " + partes);
        }
    }

    @Test
    void alvoDoSegundoPedacoUsaUmMaisOSorteio() {
        String texto = "abcdefghijklmnopqrstuvwxyz";
        for (int seed = 0; seed < 50; seed++) {
            Random r = new Random(seed);
            r.nextInt(TypeText.MAX_CHUNK); // alvo inicial
            int esperado = 1 + r.nextInt(TypeText.MAX_CHUNK); // alvo recalculado apos o 1o pedaco
            List<String> partes = TypeText.chunks(texto, new Random(seed));
            assertTrue(partes.size() > 1, "seed " + seed + ": " + partes);
            assertEquals(esperado, partes.get(1).length(),
                    "seed " + seed + ": segundo alvo deve ser 1 + nextInt(8): " + partes);
        }
    }

    // ---------- laco do insert sem Chrome ----------

    /** Random deterministico: nextInt segue a semente, nextDouble sempre devolve o mesmo d. */
    private static Random randomComDouble(long seed, double d) {
        return new Random(seed) {
            @Override public double nextDouble() {
                return d;
            }
        };
    }

    /**
     * Kills: fronteira/negacao do {@code for (i < parts.size())}, as tres formas de
     * {@code i < parts.size() - 1}, a soma/subtracao da faixa, multiplicacao->divisao,
     * adicao->subtracao do minimo, Thread.sleep removido e o retorno trocado por 0.
     */
    @Test
    void lacoPausaSoEntrePedacosComValorExatoERetornaNumeroDePedacos() throws Exception {
        TypeText.Pausa pausaOriginal = TypeText.pausa;
        List<String> enviados = new ArrayList<>();
        List<Long> pausas = new ArrayList<>();
        TypeText.pausa = pausas::add;
        try {
            String texto = "ab cd ef gh";
            long[] faixa = {100, 901};
            int esperado = TypeText.chunks(texto, new Random(7)).size();
            assertTrue(esperado > 1, "fixture precisa de mais de um pedaco");

            int devolvido = TypeText.insert(enviados::add, texto, faixa, randomComDouble(7, 0.5));

            assertEquals(esperado, devolvido, "retorno = numero de pedacos");
            assertEquals(esperado, enviados.size());
            assertEquals(texto, String.join("", enviados));
            assertEquals(esperado - 1, pausas.size(), "pausa so ENTRE pedacos, nunca depois do ultimo");
            long valor = 100 + (long) (0.5 * (901 - 100));
            for (long p : pausas) assertEquals(valor, p, "pausa = min + (long)(d*(max-min)): " + pausas);
        } finally {
            TypeText.pausa = pausaOriginal;
        }
    }

    /** Faixa com max == min nao depende do sorteio e nunca some (min - 0) nem soma (max + min). */
    @Test
    void faixaComMaxIgualMinPausaFixaEmTodosOsIntervalos() throws Exception {
        TypeText.Pausa pausaOriginal = TypeText.pausa;
        List<Long> pausas = new ArrayList<>();
        TypeText.pausa = pausas::add;
        try {
            String texto = "abcdefghijklmnop";
            int esperado = TypeText.chunks(texto, new Random(11)).size();
            assertTrue(esperado > 1, "fixture precisa de mais de um pedaco");

            int devolvido = TypeText.insert(p -> { }, texto, new long[]{37, 37}, randomComDouble(11, 0.9));

            assertEquals(esperado, devolvido);
            assertEquals(esperado - 1, pausas.size());
            for (long p : pausas) assertEquals(37L, p, "max == min: pausa fixa: " + pausas);
        } finally {
            TypeText.pausa = pausaOriginal;
        }
    }

    // ---------- Chrome headless anexado: caminho CDP real ----------

    /** insert(JavaDriverless, ...) de verdade: Insere em textarea focada e devolve o numero de pedacos. */
    @Test
    void insertRealEmTextareaFocadaConfereValorFinalERetorno() throws Exception {
        Path perfil = tmp.resolve("perfil");
        Files.createDirectories(perfil);
        Path chrome = acharChrome();
        Assumptions.assumeTrue(chrome != null, "sem binario Chrome/Chromium");
        exigirPreset(perfil);
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort();
        }
        Process proprio = subirChrome(chrome, perfil, porta);
        try {
            JavaDriverless d = new JavaDriverless("typetext-teste", BrowserPool.buildAttachOptions(
                    "typetext-teste", Json.obj(), perfil.toString(), "127.0.0.1:" + porta), false);
            try {
                d.setPageLoadTimeout(10_000);
                d.get("data:text/html;charset=utf-8,%3Ctextarea%20id%3Dt%3E%3C%2Ftextarea%3E");
                d.executeScript("document.getElementById('t').focus()");

                int esperado = TypeText.chunks("ab cd", new Random(3)).size();
                int devolvido = TypeText.insert(d, "ab cd", new long[]{0, 0}, new Random(3));

                assertEquals("ab cd", String.valueOf(d.executeScript("document.getElementById('t').value")));
                assertEquals(esperado, devolvido);
            } finally {
                try {
                    d.quit();
                } catch (Exception ignored) {
                }
            }
        } finally {
            matar(proprio);
        }
    }

    // ---------- fixture headless (padrao NavGuardMutantesTest/ExistingChromeTest) ----------

    private static Path acharChrome() {
        for (String cand : List.of("/opt/google/chrome/chrome", "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            if (Files.isExecutable(Path.of(cand))) return Path.of(cand);
        }
        return null;
    }

    private static void exigirPreset(Path perfil) {
        try {
            BrowserPool.buildAttachOptions("typetext-teste", Json.obj(), perfil.toString(), "127.0.0.1:1");
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            Assumptions.assumeFalse(m.contains("cgroup") && m.contains("memory.max"),
                    "ambiente com limite de memoria do cgroup: " + m);
            throw e;
        }
    }

    private static Process subirChrome(Path chrome, Path perfil, int porta) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=" + porta, "--user-data-dir=" + perfil, "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean pronto = false;
        while (!pronto && System.nanoTime() < prazo) {
            try (Socket s = new Socket("127.0.0.1", porta)) {
                pronto = true;
            } catch (java.io.IOException ainda) {
                Thread.sleep(100);
            }
        }
        if (!pronto) {
            matar(p);
            fail("Chrome headless nao abriu a porta de depuracao");
        }
        return p;
    }

    private static void matar(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
