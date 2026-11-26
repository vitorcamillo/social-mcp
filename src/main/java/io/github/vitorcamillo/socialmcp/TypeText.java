package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Digitacao do passo {@code type}.
 *
 * <p>Modo padrao {@code teclas}: cada grafema ASCII com mapa vira eventos nativos de
 * tecla via CDP {@code Input.dispatchKeyEvent} com o campo {@code text} (como o
 * Puppeteer), inserindo o caractere pelo caminho normal do navegador (keydown,
 * keypress, beforeinput, input, keyup) e funcionando em contenteditable. Grafemas
 * acentuados, emoji e quebra de linha continuam por {@code Input.insertText}.
 *
 * <p>Modo {@code insertText}: o texto vai por CDP {@code Input.insertText} em
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

    static final String MODE_KEYS = "teclas";
    static final String MODE_INSERT = "insertText";
    static final String MODE_KEYBOARD = "keyboard";
    static final int MAX_CHUNK = 8;
    /** Pausa entre pedacos (ms) quando a config nao define defaults.typeChunkDelayMs. */
    static final long[] DEFAULT_CHUNK_DELAY_MS = {60, 260};
    private static final long CDP_TIMEOUT_S = 5;

    private TypeText() {
    }

    /**
     * Modo de digitacao da rede: {@code keyboard} e {@code insertText} so quando
     * configurados; vazio, nulo, desconhecido e {@code teclas} caem em {@link #MODE_KEYS}.
     */
    static String mode(JsonNode cfg) {
        String m = cfg == null ? "" : cfg.path("typeMode").asText("").trim();
        if (MODE_KEYBOARD.equalsIgnoreCase(m)) return MODE_KEYBOARD;
        if (MODE_INSERT.equalsIgnoreCase(m)) return MODE_INSERT;
        return MODE_KEYS;
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
        String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
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
        io.github.selenium.javaDriverless.types.Target t = d.getChrome().getCurrentTarget()
                .get(CDP_TIMEOUT_S, TimeUnit.SECONDS);
        return insert(p -> t.executeCdpCmd("Input.insertText", Map.of("text", p), (float) CDP_TIMEOUT_S)
                .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS), text, delayMs, rnd);
    }

    interface Envio { void enviar(String pedaco) throws Exception; }
    interface Pausa { void dormir(long ms) throws InterruptedException; }
    static Pausa pausa = Thread::sleep;

    /** Laco do insert sem CDP: envia cada pedaco e dorme entre eles (nao depois do ultimo). */
    static int insert(Envio envio, String text, long[] delayMs, Random rnd) throws Exception {
        List<String> parts = chunks(text, rnd);
        for (int i = 0; i < parts.size(); i++) {
            envio.enviar(parts.get(i));
            if (i < parts.size() - 1) {
                pausa.dormir(delayMs[0] + (long) (rnd.nextDouble() * Math.max(0, delayMs[1] - delayMs[0])));
            }
        }
        return parts.size();
    }

    interface Cdp { void cmd(String method, Map<String, Object> params) throws Exception; }

    /** Digita com teclas nativas do CDP (ver {@code keys(Cdp, String, Random)}). */
    static int keys(JavaDriverless d, String text, Random rnd) throws Exception {
        return keys(d, text, rnd, System::currentTimeMillis, Long.MAX_VALUE);
    }

    /** Como {@link #keys(JavaDriverless, String, Random)}, abortando no prazo. */
    static int keys(JavaDriverless d, String text, Random rnd, LongSupplier relogio, long prazoFimMs) throws Exception {
        io.github.selenium.javaDriverless.types.Target t = d.getChrome().getCurrentTarget()
                .get(CDP_TIMEOUT_S, TimeUnit.SECONDS);
        return keys((method, params) -> t.executeCdpCmd(method, params, (float) CDP_TIMEOUT_S)
                .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS), text, rnd, relogio, prazoFimMs);
    }

    /**
     * Digita por eventos nativos de tecla com {@code text} (como o Puppeteer), que
     * inserem o caractere pelo caminho normal do navegador e funcionam em contenteditable.
     * Cada grafema ASCII com mapa vira keyDown/keyUp (com Shift quando preciso) e conta
     * uma tecla; acentos, emoji, pontuacao sem mapa e {@code \n} (nunca Enter) saem por
     * {@code Input.insertText}. Devolve quantos grafemas foram enviados como tecla.
     */
    static int keys(Cdp cdp, String text, Random rnd) throws Exception {
        return keys(cdp, text, rnd, System::currentTimeMillis, Long.MAX_VALUE);
    }

    /**
     * Como {@link #keys(Cdp, String, Random)}, checando o prazo antes de cada grafema:
     * ao esgotar, lanca falha conservadora (o passo erra e o lote registra) em vez de o
     * cliente cortar a chamada no meio e o resultado do lote se perder.
     */
    static int keys(Cdp cdp, String text, Random rnd, LongSupplier relogio, long prazoFimMs) throws Exception {
        if (text == null || text.isEmpty()) return 0;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(t);
        List<String> grafemas = new ArrayList<>();
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            grafemas.add(t.substring(start, end));
        }
        int teclas = 0;
        for (int i = 0; i < grafemas.size(); i++) {
            if (relogio.getAsLong() >= prazoFimMs) {
                throw new IllegalStateException("type: prazo esgotado na digitacao por teclas (grafema "
                        + (i + 1) + " de " + grafemas.size() + "); confira o campo, o texto pode estar parcial");
            }
            String g = grafemas.get(i);
            if (g.equals("\n")) {
                cdp.cmd("Input.insertText", params("text", "\n"));
            } else if (g.length() == 1 && tecla(g.charAt(0)) != null) {
                char c = g.charAt(0);
                Tecla k = tecla(c);
                int modifiers = k.shift() ? 8 : 0;
                if (k.shift()) {
                    cdp.cmd("Input.dispatchKeyEvent", keyEvent("rawKeyDown", "Shift", "ShiftLeft", 16, null, 8));
                }
                try {
                    cdp.cmd("Input.dispatchKeyEvent", keyEvent("keyDown", g, k.code(), k.vk(), g, modifiers));
                    pausa.dormir(45 + rnd.nextInt(75));
                } finally {
                    try {
                        cdp.cmd("Input.dispatchKeyEvent", keyEvent("keyUp", g, k.code(), k.vk(), null, modifiers));
                    } catch (Exception ignore) {
                        // melhor esforco: nao deixar a tecla presa
                    }
                    if (k.shift()) {
                        try {
                            cdp.cmd("Input.dispatchKeyEvent", keyEvent("keyUp", "Shift", "ShiftLeft", 16, null, 0));
                        } catch (Exception ignore) {
                            // melhor esforco: nao deixar o Shift preso
                        }
                    }
                }
                teclas++;
            } else {
                cdp.cmd("Input.insertText", params("text", g));
            }
            if (i < grafemas.size() - 1) {
                pausa.dormir(60 + rnd.nextInt(90));
                if (rnd.nextDouble() < 0.08) {
                    pausa.dormir(150 + rnd.nextInt(220));
                }
            }
        }
        return teclas;
    }

    /** Grafemas do texto normalizado (quebras viram {@code \n}), como o {@code keys} digita. */
    static int grafemas(String text) {
        if (text == null || text.isEmpty()) return 0;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(t);
        int n = 0;
        while (it.next() != BreakIterator.DONE) n++;
        return n;
    }

    /** Duracao esperada (ms) por teclas: ~210 ms/grafema (segurada media 82, pausa media 105, extra media 21). */
    static long duracaoEsperadaMs(String text) {
        return grafemas(text) * 210L;
    }

    /** Duracao esperada (ms) por insertText: pedacos de 4,5 grafemas em media, pausa media da faixa. */
    static long duracaoEsperadaInsertMs(String text, long[] delayMs) {
        int n = grafemas(text);
        if (n == 0) return 0;
        return (long) Math.ceil(n / 4.5) * (delayMs[0] + delayMs[1]) / 2;
    }

    private record Tecla(String code, int vk, boolean shift) {}

    /** Mapa de um caractere para a tecla nativa; null quando nao ha mapeamento. */
    static Tecla tecla(char c) {
        if (c >= 'a' && c <= 'z') return new Tecla("Key" + Character.toUpperCase(c), Character.toUpperCase(c), false);
        if (c >= 'A' && c <= 'Z') return new Tecla("Key" + c, c, true);
        if (c >= '0' && c <= '9') return new Tecla("Digit" + c, c, false);
        return switch (c) {
            case ' ' -> new Tecla("Space", 32, false);
            case '.' -> new Tecla("Period", 190, false);
            case ',' -> new Tecla("Comma", 188, false);
            case '-' -> new Tecla("Minus", 189, false);
            case '!' -> new Tecla("Digit1", 49, true);
            case '?' -> new Tecla("Slash", 191, true);
            default -> null;
        };
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> keyEvent(String type, String key, String code, int vk, String text, int modifiers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("key", key);
        m.put("code", code);
        m.put("windowsVirtualKeyCode", vk);
        if (text != null) {
            m.put("text", text);
            m.put("unmodifiedText", text);
        }
        m.put("modifiers", modifiers);
        return m;
    }
}
