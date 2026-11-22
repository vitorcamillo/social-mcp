package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ApprovalCliTerminalTest {
    /** Imita o Console do JDK 22+: existe mesmo sem TTY e responde isTerminal(). */
    public static final class ConsoleJdk22 {
        private final Object resposta;
        ConsoleJdk22(Object resposta) { this.resposta = resposta; }
        public boolean isTerminal() {
            if (resposta instanceof RuntimeException e) throw e;
            return (Boolean) resposta;
        }
    }

    /** Imita o Console do JDK 21: sem isTerminal(); so existe com TTY. */
    public static final class ConsoleJdk21 {}

    @Test
    void ttyVemDoIsTerminalQuandoExisteEFalhaFechado() {
        assertFalse(ApprovalCli.terminalReal(null, ConsoleJdk22.class));
        assertTrue(ApprovalCli.terminalReal(new ConsoleJdk22(true), ConsoleJdk22.class));
        assertFalse(ApprovalCli.terminalReal(new ConsoleJdk22(false), ConsoleJdk22.class), "JDK 22 redirecionado = agente");
        assertTrue(ApprovalCli.terminalReal(new ConsoleJdk21(), ConsoleJdk21.class));
        assertFalse(ApprovalCli.terminalReal(new ConsoleJdk22(new IllegalStateException("x")), ConsoleJdk22.class));
        assertFalse(ApprovalCli.terminalReal(new ConsoleJdk21(), ConsoleJdk22.class), "tipo errado = nao e terminal");
    }

    @Test
    void terminalPadraoPerguntaAoConsoleDoSistema() {
        java.util.function.Supplier<Object> antes = ApprovalCli.consoleDoSistema;
        try {
            ApprovalCli.consoleDoSistema = () -> null;
            assertFalse(ApprovalCli.terminal.interativo(), "sem console = agente");
            // ate o JDK 21 nao ha isTerminal: console presente = TTY; no 22+ o objeto falso falha fechado
            ApprovalCli.consoleDoSistema = Object::new;
            assertEquals(Runtime.version().feature() < 22, ApprovalCli.terminal.interativo());
        } finally {
            ApprovalCli.consoleDoSistema = antes;
        }
    }
}
