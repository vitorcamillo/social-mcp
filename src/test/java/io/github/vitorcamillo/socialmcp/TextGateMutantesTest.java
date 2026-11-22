package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** Sistema com setsid e kill: o gate roda em grupo proprio (senao, fallback por descendentes). */
    static boolean comGrupo() {
        return Files.isExecutable(Path.of(TextGate.SETSID)) && Files.isExecutable(Path.of(TextGate.KILL));
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
    void joinEsperaDescendenteQueSeguraStdout() throws Exception {
        // 200 KB (> buffer de 64 KiB do pipe): o pai so termina de escrever com o leitor ja
        // copiando, entao o leitor esta dentro da copia quando o pai sai e o neto escreve depois
        Store s = storeComGate(tmp,
                "head -c 200000 /dev/zero | tr '\\0' x; (sleep 0.3; printf FIM) & exit 0", 30, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(0, r.exit());
        assertEquals("PASS", r.resultado());
        assertEquals("x".repeat(200_000) + "FIM", r.saida());
    }

    @Test
    void comandoInexistenteDaErroComMensagem() throws Exception {
        Store s = storeComGate(tmp, "exit 0", 30, null);
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode gate = (ObjectNode) cfg.get("defaults").get("gate");
        gate.putArray("command").add("/definitivamente/nao/existe/gate-xyz-123");
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        // com setsid na frente, o exec que falha e dele (127); sem setsid, IOException do start
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(comGrupo() ? 127 : 1, r.exit());
        assertEquals("ERRO", r.resultado());
        assertTrue(r.saida().contains("gate-xyz-123"), r.saida());
        String antes = TextGate.KILL;
        TextGate.KILL = "/definitivamente/sem/kill";
        try {
            r = TextGate.rodarPre(s, lote(s), List.of());
        } finally {
            TextGate.KILL = antes;
        }
        assertEquals(1, r.exit());
        assertEquals("ERRO", r.resultado());
        assertTrue(r.saida().contains("gate-xyz-123"), r.saida());
    }

    @Test
    void timeoutMataNetoQueSaiuDaArvore() throws Exception {
        // fork duplo: o neto vira filho do init e some de descendants() (o mesmo que acontece com
        // um filho nascido entre o snapshot e o kill do pai); o SIGKILL no grupo ainda o pega
        assumeTrue(comGrupo(), "sem setsid/kill nao ha grupo proprio");
        String marca = "gate-fork-" + System.nanoTime();
        Store s = storeComGate(tmp, ": " + marca + "; ( (sleep 30; :) & ); sleep 30", 1, null);
        assertEquals("ERRO", TextGate.rodarPre(s, lote(s), List.of()).resultado());
        Thread.sleep(300);
        List<ProcessHandle> vivos = ProcessHandle.allProcesses().filter(h -> h.isAlive()
                && h.info().commandLine().map(c -> c.contains(marca)).orElse(false)).toList();
        vivos.forEach(ProcessHandle::destroyForcibly);
        assertEquals(List.of(), vivos, "filho do gate sobreviveu ao timeout");
    }

    @Test
    void interrupcaoDuranteOKillPreservaFlag() throws Exception {
        assumeTrue(Files.isExecutable(Path.of(TextGate.SETSID)), "sem setsid nao ha kill de grupo");
        Path killLento = tmp.resolve("kill-lento");
        Files.writeString(killLento, "#!/bin/sh\nsleep 4\n");
        killLento.toFile().setExecutable(true);
        Store s = storeComGate(tmp, "sleep 30", 1, null);
        Path l = lote(s);
        Object[] fim = new Object[2];
        String antes = TextGate.KILL;
        TextGate.KILL = killLento.toString();
        try {
            Thread t = new Thread(() -> {
                fim[0] = TextGate.rodarPre(s, l, List.of()).saida();
                fim[1] = Thread.currentThread().isInterrupted();
            });
            t.start();
            Thread.sleep(2_000); // gate ja estourou (1 s); thread esperando o kill lento (ate 5 s)
            t.interrupt();
            t.join(1_500);
            assertFalse(t.isAlive(), "a espera do kill deveria ter sido interrompida");
        } finally {
            TextGate.KILL = antes;
        }
        assertEquals("gate excedeu 1s", fim[0]);
        assertEquals(true, fim[1]);
    }

    @Test
    void gateQueTerminaNaoDeixaFilhoVivo() throws Exception {
        assumeTrue(comGrupo(), "sem setsid/kill nao ha grupo proprio");
        Path pidFile = tmp.resolve("sobra.pid");
        Store s = storeComGate(tmp,
                "sleep 30 >/dev/null 2>&1 & echo $! > '" + pidFile.toAbsolutePath() + "'; echo ok; exit 0", 30, null);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals("PASS", r.resultado());
        assertEquals("ok\n", r.saida());
        long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        boolean morto = TextGateSigintTest.morre(pid);
        assertTrue(morto, "filho em segundo plano sobreviveu ao fim do gate");
    }

    @Test
    void semSetsidCaiNoKillPorDescendentes() throws Exception {
        String antes = TextGate.SETSID;
        TextGate.SETSID = "/definitivamente/sem/setsid";
        try {
            mataDescendentesNoTimeout();
        } finally {
            TextGate.SETSID = antes;
        }
    }

    @Test
    void interrupcaoNoMeioMataDescendentes() throws Exception {
        Path pidFile = tmp.resolve("neto.pid");
        Store s = storeComGate(tmp, "sleep 15 & echo $! > '" + pidFile.toAbsolutePath() + "'; wait", 30, null);
        Path l = lote(s);
        TextGate.Resultado[] r = new TextGate.Resultado[1];
        boolean[] flag = new boolean[1];
        Thread t = new Thread(() -> {
            r[0] = TextGate.rodarPre(s, l, List.of());
            flag[0] = Thread.currentThread().isInterrupted();
        });
        t.start();
        for (int i = 0; i < 100 && !(Files.exists(pidFile) && Files.readString(pidFile).endsWith("\n")); i++) {
            Thread.sleep(50);
        }
        long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        t.interrupt();
        t.join(5_000);
        assertEquals("gate interrompido", r[0].saida());
        assertTrue(flag[0], "flag de interrupcao deveria ser preservada");
        boolean morto = false;
        for (int i = 0; i < 20 && !morto; i++) {
            morto = ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true);
            if (!morto) Thread.sleep(50);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        assertTrue(morto, "descendente do gate interrompido nao pode ficar orfao");
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
