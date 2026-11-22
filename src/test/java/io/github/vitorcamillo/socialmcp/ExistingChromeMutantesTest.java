package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mutantes PIT de ExistingChrome mortos via funcoes puras (sem Chrome, sem rede, sem /proc).
 *
 * <p>Os ramos de {@code reread} (L151/L152) nao sao exercitaveis aqui: leem bytes reais de
 * /proc e o trecho final so diverge com cmdline sem NUL no fim, que o kernel nunca produz
 * para processos execve normais (ver lista "precisa de refatoracao" no relatorio).
 */
class ExistingChromeMutantesTest {

    @TempDir
    Path tmp;

    @Test
    void exeNuloOuVazioNaoEMainChrome() {
        assertFalse(ExistingChrome.isMainChromeExe(null));
        assertFalse(ExistingChrome.isMainChromeExe(""));
        assertFalse(ExistingChrome.isMainChromeExe("   "));
    }

    @Test
    void deletedNoInicioCorteExatoNaoViraChrome() {
        // cut == 0 (fronteira de cut >= 0): o original corta e sobra "" (false);
        // sem o corte sobraria " (deleted) chrome", que contem "chrome" (true).
        assertFalse(ExistingChrome.isMainChromeExe("/x/ (deleted) chrome"));
        // Controle positivo: "(deleted)" no meio continua cortado e aceito.
        assertTrue(ExistingChrome.isMainChromeExe("/opt/google/chrome/chrome (deleted)"));
    }

    @Test
    void mesmoCaminhoNuloOuVazioNaoCasa() {
        Path esperado = tmp.resolve("sdk-rede");
        assertFalse(ExistingChrome.samePath(null, esperado));
        assertFalse(ExistingChrome.samePath("", esperado));
        assertFalse(ExistingChrome.samePath("   ", esperado));
    }

    @Test
    void mesmoCaminhoInvalidoNaoCasa() {
        Path esperado = tmp.resolve("sdk-rede");
        // NUL torna o caminho invalido: Path.of lanca, o catch devolve false.
        assertFalse(ExistingChrome.samePath("a\0b", esperado));
        // Controle positivo: caminho igual casa.
        assertTrue(ExistingChrome.samePath(esperado.toString(), esperado));
    }

    private static byte[] b(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void argvDeSeparaPorNulComOuSemNulFinalESemVazios() {
        assertEquals(java.util.List.of("a", "bc"), ExistingChrome.argvDe(b("a\0bc\0")));
        assertEquals(java.util.List.of("a", "b c"), ExistingChrome.argvDe(b("a\0b c")), "resto sem NUL final entra");
        assertEquals(java.util.List.of("x"), ExistingChrome.argvDe(b("\0\0x")));
        assertTrue(ExistingChrome.argvDe(new byte[0]).isEmpty());
    }

    @Test
    void scanProcEnxergaAPropriaJvm() {
        long eu = ProcessHandle.current().pid();
        assertTrue(ExistingChrome.scanProc().stream().anyMatch(s -> s.pid() == eu), "scan de /proc tem de achar a JVM");
    }
}
