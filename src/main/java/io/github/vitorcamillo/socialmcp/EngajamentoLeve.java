package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Classificador e cota de lote de engajamento leve (curtir/seguir sem texto).
 *
 * <p>Um lote e leve quando espelha {@code ReservaJa/scripts/redes/engajamento_sem_texto.py}:
 * receita de gesto simples, sem midia, so passos de navegacao/clique/verificacao e 1 ou 2
 * cliques no total. Com {@code engagement} ativo na config da rede, o lote leve sai da cota de
 * posts/lotes e passa a valer contra a cota propria de engajamento.
 */
public final class EngajamentoLeve {
    /** Receitas que sao gesto simples sem texto (origem.receita do lote). */
    public static final Set<String> RECEITAS =
            Set.of("like_ui", "unlike_ui", "follow_ui", "unfollow_ui", "follow_private_ui");

    /** Passos permitidos no lote leve (comparados em minusculas). */
    private static final Set<String> KINDS =
            Set.of("goto", "click", "wait", "verify_text", "verify_attr", "verify_absent", "screenshot");

    private EngajamentoLeve() {}

    /** Cota propria de engajamento da rede: maximo de lotes leves por dia e espacamento entre eles. */
    public record Cota(int maxPerDay, int minMinutesBetween) {}

    /**
     * Config de engajamento da rede (merge de defaults + rede, como networkCfg): ausente ou com
     * maxPerDay &lt;= 0 devolve null e o comportamento atual fica inalterado.
     */
    public static Cota cota(ObjectNode cfg) {
        JsonNode eng = cfg == null ? null : cfg.get("engagement");
        if (eng == null || !eng.isObject()) return null;
        int max = eng.path("maxPerDay").asInt(0);
        if (max <= 0) return null;
        return new Cota(max, Math.max(0, eng.path("minMinutesBetween").asInt(0)));
    }

    /**
     * True so se TUDO vale: origem.receita de gesto simples; sem midias (ausente, null ou vazio);
     * actions nao vazio com kinds permitidos (minusculos), nenhum passo com campo text, goto sem
     * parametro text na query; 1 ou 2 clicks no total.
     */
    public static boolean eh(JsonNode lote) {
        if (lote == null || !lote.isObject()) return false;
        if (!RECEITAS.contains(lote.path("origem").path("receita").asText(""))) return false;
        JsonNode midias = lote.get("midias");
        if (midias != null && !midias.isNull()) {
            if (midias.isArray() || midias.isObject()) {
                if (!midias.isEmpty()) return false;
            } else if (midias.isTextual()) {
                if (!midias.asText().isBlank()) return false;
            } else {
                return false;
            }
        }
        JsonNode actions = lote.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) return false;
        int clicks = 0;
        for (JsonNode a : actions) {
            if (a == null || !a.isObject()) return false;
            String kind = a.path("kind").asText("").toLowerCase(Locale.ROOT);
            if (!KINDS.contains(kind)) return false;
            if (a.has("text")) return false;
            if ("goto".equals(kind) && !gotoSemTextoNaQuery(a.path("url").asText(""))) return false;
            if ("click".equals(kind)) clicks++;
        }
        return clicks >= 1 && clicks <= 2;
    }

    /** True quando a query da URL nao tem parametro text (fragmento nao e query). */
    static boolean gotoSemTextoNaQuery(String url) {
        if (url == null) return true;
        int q = url.indexOf('?');
        if (q < 0) return true;
        int fim = url.indexOf('#', q + 1);
        String query = fim < 0 ? url.substring(q + 1) : url.substring(q + 1, fim);
        for (String par : query.split("&", -1)) {
            String nome = par;
            int eq = par.indexOf('=');
            if (eq >= 0) nome = par.substring(0, eq);
            try {
                nome = URLDecoder.decode(nome, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // sequencia % invalida: compara o nome cru
            }
            if ("text".equals(nome)) return false;
        }
        return true;
    }
}
