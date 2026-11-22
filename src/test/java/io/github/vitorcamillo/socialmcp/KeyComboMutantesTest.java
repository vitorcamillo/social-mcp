package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KeyComboMutantesTest {

    @Test
    void rotuloSemModificadorESoATecla() {
        assertEquals("a", KeyCombo.parse("a").orElseThrow().label());
        assertEquals("Enter", KeyCombo.parse("Enter").orElseThrow().label());
    }

    @Test
    void rotuloComModificadoresJuntaComMais() {
        assertEquals("Control+a", KeyCombo.parse("ctrl+a").orElseThrow().label());
        assertEquals("Meta+Shift+Z", KeyCombo.parse("CMD+shift+z").orElseThrow().label());
    }
}
