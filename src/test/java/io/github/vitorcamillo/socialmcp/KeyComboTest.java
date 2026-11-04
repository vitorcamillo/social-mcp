package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyComboTest {

    @Test
    void ctrlAViraCombinacaoComModificador() {
        KeyCombo k = KeyCombo.parse("ctrl+a").orElseThrow();
        assertEquals(List.of("Control"), k.modifierNames());
        assertEquals(2, k.modifiers());
        assertEquals("a", k.key());
        assertEquals("KeyA", k.code());
        assertEquals(65, k.keyCode());
        assertEquals("", k.text());
        assertEquals("selectAll", k.editCommand().orElseThrow());
    }

    @Test
    void enterSozinhoGeraRetorno() {
        KeyCombo k = KeyCombo.parse("Enter").orElseThrow();
        assertEquals(0, k.modifiers());
        assertEquals("Enter", k.key());
        assertEquals("Enter", k.code());
        assertEquals(13, k.keyCode());
        assertEquals("\r", k.text());
        assertTrue(k.editCommand().isEmpty());
    }

    @Test
    void ctrlEnterNaoGeraTexto() {
        KeyCombo k = KeyCombo.parse("ctrl+Enter").orElseThrow();
        assertEquals(2, k.modifiers());
        assertEquals("Enter", k.key());
        assertEquals(13, k.keyCode());
        assertEquals("", k.text());
    }

    @Test
    void escapeEAliases() {
        KeyCombo k = KeyCombo.parse("Escape").orElseThrow();
        assertEquals("Escape", k.key());
        assertEquals(27, k.keyCode());
        assertEquals("Escape", KeyCombo.parse("esc").orElseThrow().key());
        assertEquals("Tab", KeyCombo.parse("TAB").orElseThrow().key());
        assertEquals("Enter", KeyCombo.parse("return").orElseThrow().key());
    }

    @Test
    void textoComumEhLiteral() {
        assertTrue(KeyCombo.parse("hello").isEmpty());
        assertTrue(KeyCombo.parse("ctrl+").isEmpty());
        assertTrue(KeyCombo.parse("ctrl+hello").isEmpty());
        assertTrue(KeyCombo.parse("").isEmpty());
        assertTrue(KeyCombo.parse(null).isEmpty());
        assertTrue(KeyCombo.parse("F13").isEmpty());
    }

    @Test
    void cmdEhMetaEModificadoresSomam() {
        KeyCombo k = KeyCombo.parse("CMD+shift+z").orElseThrow();
        assertEquals(List.of("Meta", "Shift"), k.modifierNames());
        assertEquals(4 | 8, k.modifiers());
        assertEquals("Z", k.key());
        assertEquals("KeyZ", k.code());
        assertEquals(90, k.keyCode());
        assertTrue(k.editCommand().isEmpty());
    }

    @Test
    void funcaoDigitoESetas() {
        assertEquals(123, KeyCombo.parse("F12").orElseThrow().keyCode());
        assertEquals(112, KeyCombo.parse("f1").orElseThrow().keyCode());
        KeyCombo d = KeyCombo.parse("alt+1").orElseThrow();
        assertEquals("Digit1", d.code());
        assertEquals(49, d.keyCode());
        assertEquals(1, d.modifiers());
        assertEquals(40, KeyCombo.parse("ArrowDown").orElseThrow().keyCode());
        assertEquals(" ", KeyCombo.parse("Space").orElseThrow().text());
    }

    @Test
    void letraSemModificadorGeraTexto() {
        KeyCombo k = KeyCombo.parse("a").orElseThrow();
        assertEquals("a", k.text());
        assertEquals(0, k.modifiers());
    }
}
