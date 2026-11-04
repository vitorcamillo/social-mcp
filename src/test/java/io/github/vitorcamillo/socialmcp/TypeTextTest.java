package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void modoPadraoEInsertText() {
        JsonNode defaults = Json.parse(Store.DEFAULT_CONFIG).path("defaults");
        assertEquals("insertText", defaults.path("typeMode").asText());
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(defaults));
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(Json.obj()));
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(null));
        assertEquals(TypeText.MODE_INSERT, TypeText.mode(Json.obj().put("typeMode", "xpto")));
        assertEquals(TypeText.MODE_KEYBOARD, TypeText.mode(Json.obj().put("typeMode", "keyboard")));
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
