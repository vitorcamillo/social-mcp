package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ctrl-C real na CLI: o gate roda em grupo proprio (setsid) e so morre pelo gancho da JVM. */
class TextGateSigintTest {

    @TempDir
    Path tmp;

    @Test
    void sigintNaCliMataOGate() throws Exception {
        // sem grupo proprio o gate fica no grupo do terminal e recebe o Ctrl-C direto
        org.junit.jupiter.api.Assumptions.assumeTrue(TextGateMutantesTest.comGrupo(), "sem setsid/kill");
        Path pidFile = tmp.resolve("neto.pid");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process cli = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                GateSigintMain.class.getName(), tmp.toString(), pidFile.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            for (int i = 0; i < 300 && !(Files.exists(pidFile) && Files.readString(pidFile).endsWith("\n")); i++) {
                Thread.sleep(50);
            }
            long neto = Long.parseLong(Files.readString(pidFile).trim());
            new ProcessBuilder("kill", "-INT", String.valueOf(cli.pid())).start().waitFor();
            assertTrue(cli.waitFor(15, TimeUnit.SECONDS), "a CLI deveria encerrar com o SIGINT");
            assertTrue(morre(neto), "gate sobreviveu ao Ctrl-C da CLI");
        } finally {
            cli.destroyForcibly();
        }
    }

    @Test
    void matarVivosMataGateEmAndamento() throws Exception {
        Path pidFile = tmp.resolve("vivo.pid");
        Store s = TextGateMutantesTest.storeComGate(tmp, "sleep 30 & echo $! > '" + pidFile + "'; wait", 60, null);
        Path lote = s.batchesDir().resolve("x.draft.json");
        Files.writeString(lote, "{\"actions\":[]}");
        Thread t = new Thread(() -> TextGate.rodarPre(s, lote, java.util.List.of()));
        t.start();
        for (int i = 0; i < 300 && !(Files.exists(pidFile) && Files.readString(pidFile).endsWith("\n")); i++) {
            Thread.sleep(50);
        }
        long neto = Long.parseLong(Files.readString(pidFile).trim());
        try {
            TextGate.matarVivos(); // o que o gancho de encerramento faz
            t.join(5_000);
            assertFalse(t.isAlive(), "gate deveria ter morrido pelo matarVivos");
            assertTrue(TextGate.VIVOS.isEmpty(), "gate encerrado tem que sair do registro");
            assertTrue(morre(neto), "filho do gate sobreviveu");
            // depois do gancho nenhum gate novo nasce (ficaria orfao com a JVM saindo)
            Path marca = tmp.resolve("nasceu");
            Store s2 = TextGateMutantesTest.storeComGate(tmp, "touch '" + marca + "'", 30, null);
            TextGate.Resultado r = TextGate.rodarPre(s2, lote, java.util.List.of());
            assertEquals("JVM encerrando", r.saida());
            assertEquals("ERRO", r.resultado());
            assertFalse(Files.exists(marca), "gate iniciado durante o encerramento");
        } finally {
            TextGate.encerrando = false;
        }
    }

    static boolean morre(long pid) throws InterruptedException {
        boolean morto = false;
        for (int i = 0; i < 40 && !morto; i++) {
            morto = ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true);
            if (!morto) Thread.sleep(50);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        return morto;
    }
}
