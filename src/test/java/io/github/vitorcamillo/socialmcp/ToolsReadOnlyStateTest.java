package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolsReadOnlyStateTest {
    @Test
    void aplicaRegraDeEstadoPorTipoDeAcao() {
        String[] states = {"healthy", "cooldown", "paused", "banned", "unknown"};
        boolean[] readOnlyValues = {true, false};

        for (String state : states) {
            for (boolean readOnly : readOnlyValues) {
                boolean allowed = "healthy".equals(state)
                        || (readOnly && ("cooldown".equals(state) || "paused".equals(state)));
                if (allowed) {
                    assertDoesNotThrow(() -> Tools.validateActionState(state, readOnly),
                            () -> "deveria permitir state=" + state + ", readOnly=" + readOnly);
                } else {
                    IllegalStateException error = assertThrows(IllegalStateException.class,
                            () -> Tools.validateActionState(state, readOnly),
                            () -> "deveria bloquear state=" + state + ", readOnly=" + readOnly);
                    org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().startsWith("social_action " + (readOnly ? "readOnly" : "publica")));
                }
            }
        }
    }
}
