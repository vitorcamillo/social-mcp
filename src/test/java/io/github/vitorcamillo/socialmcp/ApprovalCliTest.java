package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalCliTest {

    @TempDir
    Path tmp;

    private ApprovalCli.Terminal original;

    @BeforeEach
    void guardarTerminal() {
        original = ApprovalCli.terminal;
    }

    @AfterEach
    void restaurarTerminal() {
        ApprovalCli.terminal = original;
    }

    /** Terminal falso com interatividade e resposta fixas, sem console real. */
    private static ApprovalCli.Terminal terminalFixo(boolean interativo, String resposta) {
        return new ApprovalCli.Terminal() {
            @Override public boolean interativo() { return interativo; }
            @Override public String lerLinha(String prompt) { return resposta; }
        };
    }

    private Store storeNovo() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    @Test
    void stopInvalidoNaoAlteraKillSwitch() throws Exception {
        Store s = new Store(tmp.resolve("home" + System.nanoTime()));
        s.setStopped(true);
        for (String arg : new String[]{"ON", "On", "onn", "of", "", "parar"}) {
            assertThrows(IllegalArgumentException.class, () -> ApprovalCli.aplicarStop(s, arg), arg);
            assertTrue(s.isStopped(), "arg '" + arg + "' nao pode desativar o kill-switch");
        }
        assertFalse(ApprovalCli.aplicarStop(s, "off"));
        assertFalse(s.isStopped());
        // invalido tambem nao ativa nem desativa com o switch desligado
        assertThrows(IllegalArgumentException.class, () -> ApprovalCli.aplicarStop(s, "ON"));
        assertFalse(s.isStopped());
        assertTrue(ApprovalCli.aplicarStop(s, "on"));
        assertTrue(s.isStopped());
    }

    // ---------- comandos sensiveis so com terminal humano ----------

    @Test
    void stateHealthySemTerminalRecusadoENaoMudaEstado() throws Exception {
        Store s = storeNovo();
        ApprovalCli.terminal = terminalFixo(false, "CONFIRMO");
        int code = ApprovalCli.run(new String[]{"state", "bluesky", "healthy", "rotina"}, s);
        assertEquals(2, code);
        assertFalse(s.health().has("bluesky"), "estado nao pode mudar sem terminal interativo");
    }

    @Test
    void stateHealthyComConfirmoMudaETrilhaCliChamada() throws Exception {
        Store s = storeNovo();
        ApprovalCli.terminal = terminalFixo(true, "CONFIRMO");
        int code = ApprovalCli.run(new String[]{"state", "bluesky", "healthy", "rotina"}, s);
        assertEquals(0, code);
        assertEquals("healthy", s.health().path("bluesky").path("state").asText());
        assertEquals("dono (CLI tty)", s.health().path("bluesky").path("by").asText());
        String j = String.join("\n", s.tail(5));
        assertTrue(j.contains("cli_chamada"), j);
        assertTrue(j.contains("\"tty\":true"), j);
    }

    @Test
    void stateHealthyComRespostaQueNaoEConfirmoRecusa() throws Exception {
        Store s = storeNovo();
        ApprovalCli.terminal = terminalFixo(true, "sim");
        assertEquals(2, ApprovalCli.run(new String[]{"state", "bluesky", "healthy"}, s));
        assertFalse(s.health().has("bluesky"));
    }

    @Test
    void statePausedSemTerminalPermitido() throws Exception {
        Store s = storeNovo();
        ApprovalCli.terminal = terminalFixo(false, "");
        assertEquals(0, ApprovalCli.run(new String[]{"state", "bluesky", "paused", "manutencao"}, s));
        assertEquals("paused", s.health().path("bluesky").path("state").asText());
        assertEquals("dono (CLI)", s.health().path("bluesky").path("by").asText());
        String j = String.join("\n", s.tail(5));
        assertTrue(j.contains("cli_chamada"), j);
        assertTrue(j.contains("\"tty\":false"), j);
    }

    @Test
    void approveForcarSemTerminalRecusadoNaoTocaNoLote() throws Exception {
        Store s = storeNovo();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("cru", Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"goto\",\"url\":\"https://bsky.app\"}]")).get("id").asText();
        ApprovalCli.terminal = terminalFixo(false, "");
        int code = ApprovalCli.run(new String[]{"approve", id, "humano", "decisao", "--forcar"}, s);
        assertEquals(2, code);
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")), "rascunho intocado");
        assertFalse(String.join("\n", s.tail(5)).contains("batch_approved"));
    }

    @Test
    void approveEnsaioSemTerminalRecusado() throws Exception {
        Store s = storeNovo();
        BatchStore bs = new BatchStore(s);
        String id = bs.draft("cru", Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"goto\",\"url\":\"https://bsky.app\"}]")).get("id").asText();
        ApprovalCli.terminal = terminalFixo(false, "");
        assertEquals(2, ApprovalCli.run(new String[]{"approve", id, "humano", "ensaio", "--ensaio"}, s));
        assertTrue(Files.exists(s.batchesDir().resolve(id + ".draft.json")), "rascunho intocado");
    }
}
