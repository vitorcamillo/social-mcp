package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Digitacao do passo {@code type}.
 *
 * <p>Modo padrao {@code insertText}: o texto vai por CDP {@code Input.insertText} em
 * pedacos de 1-8 grafemas, com pausa aleatoria entre pedacos. Motivo: o teclado da
 * javadriveless manda o espaco como keyDown/keyUp sem o campo {@code text} do CDP e,
 * em contenteditable (Bluesky, Facebook Lexical, X Draft.js, YouTube), o espaco nao
 * entra (post do Bluesky de 28/09 e comentario do Reddit de 17/10 sairam sem espacos).
 *
 * <p>Quebra de linha: {@code \n} vai sozinho, tambem por {@code Input.insertText}, e
 * NUNCA como tecla Enter: em varios compositores Enter envia (comentario do Facebook,
 * DM do X). insertText("\n") insere a quebra sem disparar o envio na maioria dos
 * editores; se um editor ignorar, a linha sai colada e o verify_text do lote acusa.
 *
 * <p>Modo {@code keyboard} (config {@code defaults.typeMode} ou por rede) volta ao
 * comportamento antigo ({@code typeHumanized} da lib).
 */
final class TypeText {

    static final String MODE_INSERT = "insertText";
    static final String MODE_KEYBOARD = "keyboard";
    static final int MAX_CHUNK = 8;
    /** Pausa entre pedacos (ms) quando a config nao define defaults.typeChunkDelayMs. */
    static final long[] DEFAULT_CHUNK_DELAY_MS = {60, 260};
    private static final long CDP_TIMEOUT_S = 5;

    private TypeText() {
    }

    /** Modo de digitacao da rede: "keyboard" so se configurado; qualquer outro valor = insertText. */
    static String mode(JsonNode cfg) {
        String m = cfg == null ? "" : cfg.path("typeMode").asText("");
        return MODE_KEYBOARD.equalsIgnoreCase(m.trim()) ? MODE_KEYBOARD : MODE_INSERT;
    }

    /** Faixa [min, max] de pausa entre pedacos; invalida cai no padrao. */
    static long[] chunkDelay(JsonNode cfg) {
        JsonNode r = cfg == null ? null : cfg.path("typeChunkDelayMs");
        if (r != null && r.isArray() && r.size() == 2 && r.get(0).canConvertToLong() && r.get(1).canConvertToLong()) {
            long min = Math.max(0, r.get(0).asLong());
            long max = Math.max(min, r.get(1).asLong());
            if (max <= 5_000) return new long[]{min, max};
        }
        return DEFAULT_CHUNK_DELAY_MS.clone();
    }

    /**
     * Fatia o texto em pedacos de 1..8 grafemas (nunca corta emoji/acento combinado).
     * "\r\n" e "\r" viram "\n", e cada "\n" sai como pedaco proprio. Espacos (inclusive
     * duplos) sao preservados. A concatenacao dos pedacos e o texto normalizado.
     */
    static List<String> chunks(String text, Random rnd) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(t);
        StringBuilder cur = new StringBuilder();
        int graphemes = 0;
        int alvo = 1 + rnd.nextInt(MAX_CHUNK);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String g = t.substring(start, end);
            if (g.equals("\n")) {
                if (!cur.isEmpty()) out.add(cur.toString());
                out.add("\n");
                cur.setLength(0);
                graphemes = 0;
                alvo = 1 + rnd.nextInt(MAX_CHUNK);
                continue;
            }
            cur.append(g);
            graphemes++;
            if (graphemes >= alvo) {
                out.add(cur.toString());
                cur.setLength(0);
                graphemes = 0;
                alvo = 1 + rnd.nextInt(MAX_CHUNK);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Insere o texto no elemento focado via CDP Input.insertText, pedaco a pedaco. Devolve o numero de pedacos. */
    static int insert(JavaDriverless d, String text, long[] delayMs, Random rnd) throws Exception {
        List<String> parts = chunks(text, rnd);
        io.github.selenium.javaDriverless.types.Target t = d.getChrome().getCurrentTarget()
                .get(CDP_TIMEOUT_S, TimeUnit.SECONDS);
        for (int i = 0; i < parts.size(); i++) {
            t.executeCdpCmd("Input.insertText", Map.of("text", parts.get(i)), (float) CDP_TIMEOUT_S)
                    .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS);
            if (i < parts.size() - 1) {
                long span = delayMs[1] - delayMs[0];
                Thread.sleep(delayMs[0] + (span > 0 ? (long) (rnd.nextDouble() * span) : 0));
            }
        }
        return parts.size();
    }
}
