package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextGateMutantesTest {

    @TempDir
    Path tmp;

    static Store storeComGate(Path base, String script, int timeoutSec, String config) throws Exception {
        Store s = new Store(base.resolve("home" + System.nanoTime()));
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode defs = (ObjectNode) cfg.get("defaults");
        ObjectNode gate = defs.putObject("gate");
        gate.putArray("command").add("bash").add("-c").add(script);
        gate.put("timeoutSec", timeoutSec);
        if (config != null) gate.put("config", config);
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        return s;
    }

    private Path lote(Store s) throws Exception {
        Path p = s.batchesDir().resolve("x.draft.json");
        Files.writeString(p, "{\"actions\":[]}", StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void timeoutZeroVoltaPara120() throws Exception {
        Store s = storeComGate(tmp, "sleep 1; exit 0", 0, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(0, r.exit());
        assertEquals("PASS", r.resultado());
    }

    @Test
    void configPassadaVaiParaLinhaDeComando() throws Exception {
        String cfg = tmp.resolve("gate.json").toString();
        Store s = storeComGate(tmp, "echo \"$0 $*\"; exit 0", 30, cfg);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals("PASS", r.resultado());
        assertTrue(r.saida().contains("--config " + cfg), r.saida());
    }

    @Test
    void semConfigNaoPassaFlagDeConfig() throws Exception {
        Store s = storeComGate(tmp, "echo \"$0 $*\"; exit 0", 30, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals("PASS", r.resultado());
        assertFalse(r.saida().contains("--config"), r.saida());
    }

    @Test
    void fechaStdinParaProcessoQueLeEntrada() throws Exception {
        Store s = storeComGate(tmp, "cat > /dev/null; exit 0", 5, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(0, r.exit());
        assertEquals("PASS", r.resultado());
    }

    @Test
    void mataDescendentesNoTimeout() throws Exception {
        Path pidFile = tmp.resolve("filho.pid");
        Store s = storeComGate(tmp,
                "sleep 15 & echo $! > '" + pidFile.toAbsolutePath() + "'; wait", 2, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals("ERRO", r.resultado());
        assertTrue(r.saida().contains("gate excedeu 2s"), r.saida());
        long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        boolean morto = false;
        for (int i = 0; i < 20 && !morto; i++) {
            morto = ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true);
            if (!morto) Thread.sleep(50);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        assertTrue(morto, "processo filho do gate deveria ter sido destruido");
    }

    @Test
    void joinEsperaFilhoQueSeguraStdout() throws Exception {
        Store s = storeComGate(tmp, "(sleep 0.3; printf FIM) & exit 0", 30, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(0, r.exit());
        assertEquals("PASS", r.resultado());
        assertEquals("FIM", r.saida());
    }

    @Test
    void comandoInexistenteDaErroComMensagem() throws Exception {
        Store s = storeComGate(tmp, "exit 0", 30, null);
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode gate = (ObjectNode) cfg.get("defaults").get("gate");
        gate.putArray("command").add("/definitivamente/nao/existe/gate-xyz-123");
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertNotNull(r);
        assertEquals(1, r.exit());
        assertEquals("ERRO", r.resultado());
        assertTrue(r.saida().contains("gate-xyz-123"), r.saida());
    }

    @Test
    void interrupcaoPreservaFlagERetornaErro() throws Exception {
        String marca = "gate-interrompido-" + System.nanoTime();
        Store s = storeComGate(tmp, "sleep 7; : " + marca, 30, null);
        Thread.currentThread().interrupt();
        TextGate.Resultado r = null;
        boolean preservou;
        try {
            r = TextGate.rodarPre(s, lote(s), List.of());
        } finally {
            preservou = Thread.currentThread().isInterrupted();
            Thread.interrupted();
        }
        assertNotNull(r);
        assertEquals(1, r.exit());
        assertEquals("ERRO", r.resultado());
        assertEquals("gate interrompido", r.saida());
        assertTrue(preservou, "flag de interrupcao deveria ser preservada");
        boolean vivo = true;
        for (int i = 0; i < 20 && vivo; i++) {
            vivo = ProcessHandle.allProcesses().anyMatch(h -> h.isAlive()
                    && h.info().commandLine().map(c -> c.contains(marca)).orElse(false));
            if (vivo) Thread.sleep(50);
        }
        ProcessHandle.allProcesses()
                .filter(h -> h.info().commandLine().map(c -> c.contains(marca)).orElse(false))
                .forEach(ProcessHandle::destroyForcibly);
        assertFalse(vivo, "gate interrompido nao pode ficar rodando orfao");
    }
}
