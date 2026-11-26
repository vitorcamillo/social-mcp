package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TypeTextTest {

    private static final String FRASE = "Segunda-feira tem Flamengo x Atlético-MG, 20h.";

    private static int graphemes(String s) {
        java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        it.setText(s);
        int n = 0;
        while (it.next() != java.text.BreakIterator.DONE) n++;
        return n;
    }

    @RepeatedTest(50)
    void fatiamentoPreservaTextoComAcentosEspacosEEmoji() {
        String t = "Ação  é já!  🇧🇷 família 👨‍👩‍👧 e ç  " + FRASE;
        List<String> parts = TypeText.chunks(t, new Random());
        assertEquals(t, String.join("", parts));
        for (String p : parts) {
            int g = graphemes(p);
            assertTrue(g >= 1 && g <= TypeText.MAX_CHUNK, "pedaco fora de 1..8 grafemas: '" + p + "'");
            assertFalse(Character.isLowSurrogate(p.charAt(0)), "pedaco comeca no meio de emoji: '" + p + "'");
            assertFalse(Character.isHighSurrogate(p.charAt(p.length() - 1)), "pedaco termina no meio de emoji: '" + p + "'");
        }
    }

    @Test
    void emojiComposto_naoECortado() {
        String familia = "👨‍👩‍👧";
        String bandeira = "🇧🇷";
        for (int seed = 0; seed < 200; seed++) {
            List<String> parts = TypeText.chunks("a" + familia + "b" + bandeira + "c", new Random(seed));
            assertTrue(parts.stream().anyMatch(p -> p.contains(familia)), "familia cortada, seed " + seed + ": " + parts);
            assertTrue(parts.stream().anyMatch(p -> p.contains(bandeira)), "bandeira cortada, seed " + seed + ": " + parts);
        }
    }

    @Test
    void quebraDeLinhaSaiSozinhaENormalizaCrLf() {
        for (int seed = 0; seed < 100; seed++) {
            List<String> parts = TypeText.chunks("linha um\r\nlinha  dois\n\nfim\r", new Random(seed));
            assertEquals("linha um\nlinha  dois\n\nfim\n", String.join("", parts));
            for (String p : parts) {
                if (p.contains("\n")) assertEquals("\n", p, "\\n precisa ser pedaco proprio: " + parts);
                assertFalse(p.contains("\r"));
            }
            assertEquals(4, parts.stream().filter("\n"::equals).count());
        }
    }

    @Test
    void espacosDuplosEFinaisPreservados() {
        String t = "  dois  espacos  ";
        for (int seed = 0; seed < 100; seed++) {
            assertEquals(t, String.join("", TypeText.chunks(t, new Random(seed))));
        }
    }

    @Test
    void vazioOuNuloNaoGeraPedaco() {
        assertTrue(TypeText.chunks("", new Random()).isEmpty());
        assertTrue(TypeText.chunks(null, new Random()).isEmpty());
    }

    @Test
    void modoPadraoETeclas() {
        JsonNode defaults = Json.parse(Store.DEFAULT_CONFIG).path("defaults");
        assertEquals("teclas", defaults.path("typeMode").asText());
        assertEquals(TypeText.MODE_KEYS, TypeText.mode(defaults));
        assertEquals(TypeText.MODE_KEYS, TypeText.mode(Json.obj()));
        assertEquals(TypeText.MODE_KEYS, TypeText.mode(null));
        assertEquals(TypeText.MODE_KEYS, TypeText.mode(Json.obj().put("typeMode", "xpto")));
        assertEquals(TypeText.MODE_KEYS, TypeText.mode(Json.obj().put("typeMode", "teclas")));
        assertEquals(TypeText.MODE_KEYBOARD, TypeText.mode(Json.obj().put("typeMode", "keyboard")));
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(Json.obj().put("typeMode", "insertText")));
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(Json.obj().put("typeMode", "INSERTTEXT")));
    }

    private record Cmd(String method, Map<String, Object> params, int pausasAntes) {}

    private static Cmd achar(List<Cmd> cmds, String type, Object key) {
        for (Cmd c : cmds) {
            if ("Input.dispatchKeyEvent".equals(c.method())
                    && type.equals(c.params().get("type")) && key.equals(c.params().get("key"))) {
                return c;
            }
        }
        fail("comando nao encontrado: " + type + " " + key);
        return null;
    }

    @Test
    void teclasNativasMandamTextoSeguramTeclaENuncaEnter() throws Exception {
        List<Long> pausas = new ArrayList<>();
        List<Cmd> cmds = new ArrayList<>();
        TypeText.Cdp cdp = (method, params) -> cmds.add(new Cmd(method, params, pausas.size()));
        TypeText.Pausa original = TypeText.pausa;
        try {
            TypeText.pausa = pausas::add;
            int teclas = TypeText.keys(cdp, "Oi a!\né😀", new Random(7));
            assertEquals(5, teclas);

            Cmd primeiro = cmds.get(0);
            assertEquals("Input.dispatchKeyEvent", primeiro.method());
            assertEquals("rawKeyDown", primeiro.params().get("type"));
            assertEquals("Shift", primeiro.params().get("key"));
            assertEquals("ShiftLeft", primeiro.params().get("code"));
            assertEquals(16, primeiro.params().get("windowsVirtualKeyCode"));
            assertEquals(8, primeiro.params().get("modifiers"));

            Cmd o = cmds.get(1);
            assertEquals("keyDown", o.params().get("type"));
            assertEquals("O", o.params().get("key"));
            assertEquals("O", o.params().get("text"));
            assertEquals("KeyO", o.params().get("code"));
            assertEquals(79, o.params().get("windowsVirtualKeyCode"));
            assertEquals(8, o.params().get("modifiers"));

            Cmd espaco = achar(cmds, "keyDown", " ");
            assertEquals("Space", espaco.params().get("code"));
            assertEquals(32, espaco.params().get("windowsVirtualKeyCode"));
            assertEquals(" ", espaco.params().get("text"));

            Cmd excl = achar(cmds, "keyDown", "!");
            assertEquals("Digit1", excl.params().get("code"));
            assertEquals(8, excl.params().get("modifiers"));

            for (String inserido : List.of("\n", "é", "😀")) {
                assertTrue(cmds.stream().anyMatch(c -> "Input.insertText".equals(c.method())
                        && inserido.equals(c.params().get("text"))), "insertText de '" + inserido + "'");
            }

            for (Cmd c : cmds) {
                assertNotEquals("Enter", c.params().get("key"));
            }

            int pendente = -1;
            for (int i = 0; i < cmds.size(); i++) {
                Cmd c = cmds.get(i);
                Object type = c.params().get("type");
                if ("keyDown".equals(type)) {
                    pendente = i;
                } else if ("keyUp".equals(type) && pendente >= 0) {
                    assertEquals(cmds.get(pendente).params().get("key"), c.params().get("key"));
                    List<Long> segurada = pausas.subList(cmds.get(pendente).pausasAntes(), c.pausasAntes());
                    assertTrue(segurada.stream().anyMatch(p -> p >= 45 && p <= 119),
                            "sem pausa de tecla segurada entre " + cmds.get(pendente).params().get("key") + ": " + segurada);
                    pendente = -1;
                }
            }
            assertEquals(-1, pendente, "keyDown sem keyUp");
        } finally {
            TypeText.pausa = original;
        }
    }

    @Test
    void teclasVazioOuNuloNaoMandaComando() throws Exception {
        List<Cmd> cmds = new ArrayList<>();
        TypeText.Cdp cdp = (method, params) -> cmds.add(new Cmd(method, params, 0));
        assertEquals(0, TypeText.keys(cdp, "", new Random(7)));
        assertEquals(0, TypeText.keys(cdp, null, new Random(7)));
        assertTrue(cmds.isEmpty());
    }

    @Test
    void faixaDePausaValidaOuPadrao() {
        assertArrayEquals(new long[]{60, 260}, TypeText.chunkDelay(Json.obj()));
        JsonNode cfg = Json.parse("{\"typeChunkDelayMs\":[10,20]}");
        assertArrayEquals(new long[]{10, 20}, TypeText.chunkDelay(cfg));
        assertArrayEquals(new long[]{60, 260}, TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[10,99999]}")));
        assertArrayEquals(new long[]{30, 30}, TypeText.chunkDelay(Json.parse("{\"typeChunkDelayMs\":[30,5]}")));
    }
}
