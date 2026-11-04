package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalCliTest {

    @TempDir
    Path tmp;

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
}
