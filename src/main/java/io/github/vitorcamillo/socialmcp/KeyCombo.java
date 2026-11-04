package io.github.vitorcamillo.socialmcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tecla ou combinacao ("ctrl+a", "Enter", "ctrl+Enter") pronta para
 * Input.dispatchKeyEvent do CDP. Texto que nao casa o padrao nao e combinacao:
 * o passo key cai na digitacao literal.
 *
 * @param modifierNames modificadores na ordem (Control, Alt, Shift, Meta)
 * @param modifiers     bitmask CDP: Alt=1, Ctrl=2, Meta=4, Shift=8
 * @param key           valor DOM key ("a", "Enter")
 * @param code          valor DOM code ("KeyA", "Enter")
 * @param keyCode       windowsVirtualKeyCode
 * @param text          texto gerado pela tecla ("" quando nao gera)
 */
public record KeyCombo(List<String> modifierNames, int modifiers, String key, String code, int keyCode, String text) {

    public static final Pattern PATTERN = Pattern.compile(
            "^((ctrl|alt|shift|meta|cmd)\\+)*(Enter|Return|Tab|Escape|Esc|Backspace|Delete|Space|Home|End|PageUp|PageDown"
                    + "|Arrow(Up|Down|Left|Right)|F1[0-2]|F[1-9]|[a-zA-Z0-9])$",
            Pattern.CASE_INSENSITIVE);

    /** nome canonico -> {key, code, keyCode, text} */
    private static final Map<String, Object[]> NAMED = Map.ofEntries(
            Map.entry("enter", new Object[]{"Enter", "Enter", 13, "\r"}),
            Map.entry("return", new Object[]{"Enter", "Enter", 13, "\r"}),
            Map.entry("tab", new Object[]{"Tab", "Tab", 9, ""}),
            Map.entry("escape", new Object[]{"Escape", "Escape", 27, ""}),
            Map.entry("esc", new Object[]{"Escape", "Escape", 27, ""}),
            Map.entry("backspace", new Object[]{"Backspace", "Backspace", 8, ""}),
            Map.entry("delete", new Object[]{"Delete", "Delete", 46, ""}),
            Map.entry("space", new Object[]{" ", "Space", 32, " "}),
            Map.entry("home", new Object[]{"Home", "Home", 36, ""}),
            Map.entry("end", new Object[]{"End", "End", 35, ""}),
            Map.entry("pageup", new Object[]{"PageUp", "PageUp", 33, ""}),
            Map.entry("pagedown", new Object[]{"PageDown", "PageDown", 34, ""}),
            Map.entry("arrowup", new Object[]{"ArrowUp", "ArrowUp", 38, ""}),
            Map.entry("arrowdown", new Object[]{"ArrowDown", "ArrowDown", 40, ""}),
            Map.entry("arrowleft", new Object[]{"ArrowLeft", "ArrowLeft", 37, ""}),
            Map.entry("arrowright", new Object[]{"ArrowRight", "ArrowRight", 39, ""}));

    /** Dados CDP de um modificador: {key, code, keyCode, bit}. */
    static Object[] modifierInfo(String name) {
        return switch (name) {
            case "Alt" -> new Object[]{"Alt", "AltLeft", 18, 1};
            case "Control" -> new Object[]{"Control", "ControlLeft", 17, 2};
            case "Meta" -> new Object[]{"Meta", "MetaLeft", 91, 4};
            case "Shift" -> new Object[]{"Shift", "ShiftLeft", 16, 8};
            default -> throw new IllegalArgumentException("modificador invalido: " + name);
        };
    }

    /** Combinacao reconhecida, ou vazio quando o texto deve ser digitado literalmente. */
    public static Optional<KeyCombo> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty() || !PATTERN.matcher(s).matches()) return Optional.empty();
        String[] parts = s.split("\\+");
        List<String> mods = new ArrayList<>();
        int mask = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            String m = switch (parts[i].toLowerCase(Locale.ROOT)) {
                case "ctrl" -> "Control";
                case "alt" -> "Alt";
                case "shift" -> "Shift";
                default -> "Meta"; // meta, cmd
            };
            if (!mods.contains(m)) {
                mods.add(m);
                mask |= (int) modifierInfo(m)[3];
            }
        }
        String last = parts[parts.length - 1];
        boolean shift = (mask & 8) != 0;
        boolean comando = (mask & (1 | 2 | 4)) != 0; // ctrl/alt/meta: tecla nao gera texto
        String key;
        String code;
        int vk;
        String text;
        Object[] named = last.length() > 1 ? NAMED.get(last.toLowerCase(Locale.ROOT)) : null;
        if (named != null) {
            key = (String) named[0];
            code = (String) named[1];
            vk = (int) named[2];
            text = (String) named[3];
        } else if (last.length() > 1) { // F1..F12
            int n = Integer.parseInt(last.substring(1));
            key = "F" + n;
            code = key;
            vk = 111 + n;
            text = "";
        } else {
            char c = last.charAt(0);
            if (Character.isDigit(c)) {
                key = String.valueOf(c);
                code = "Digit" + c;
                vk = c;
            } else {
                char lower = Character.toLowerCase(c);
                char upper = Character.toUpperCase(c);
                key = String.valueOf(shift || Character.isUpperCase(c) ? upper : lower);
                code = "Key" + upper;
                vk = upper;
            }
            text = key;
        }
        if (comando) text = "";
        return Optional.of(new KeyCombo(List.copyOf(mods), mask, key, code, vk, text));
    }

    /** Comando de edicao do Chrome para atalhos que o CDP nao aplica sozinho (ex.: selectAll). */
    public Optional<String> editCommand() {
        if (modifiers == 2 || modifiers == 4) {
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "a" -> Optional.of("selectAll");
                case "c" -> Optional.of("copy");
                case "v" -> Optional.of("paste");
                case "x" -> Optional.of("cut");
                case "z" -> Optional.of("undo");
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    /** Rotulo legivel, ex.: "Control+a". */
    public String label() {
        return modifierNames.isEmpty() ? key : String.join("+", modifierNames) + "+" + key;
    }
}
