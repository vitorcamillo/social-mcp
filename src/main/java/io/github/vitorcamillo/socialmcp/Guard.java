package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Guarda de seguranca: decide se uma acao pode acontecer.
 * Regras: kill-switch para tudo; rede "banned" para tudo; cooldown/paused
 * bloqueiam acao publica; acao publica respeita cota diaria e espacamento.
 * Tripwires detectam padroes de anti-bot no texto da pagina.
 */
public final class Guard {
    public static final Set<String> PUBLIC_KINDS = Set.of("click", "type", "key", "request");

    private Guard() {}

    public static boolean isPublic(String kind) {
        return PUBLIC_KINDS.contains(kind);
    }

    public record Decision(boolean allowed, String reason) {
        @Override public String toString() { return (allowed ? "PERMITIDO: " : "BLOQUEADO: ") + reason; }
    }

    public static Decision check(Store store, String network, String kind) {
        return check(store, network, kind, null);
    }

    /**
     * Variante para execucao em lote: currentBatchId e o lote em curso, que nao
     * se bloqueia a si mesmo (cota e espacamento contam LOTES, nao passos).
     */
    public static Decision check(Store store, String network, String kind, String currentBatchId) {
        if (!store.knownNetworks().contains(network)) {
            return new Decision(false, "rede desconhecida: '" + network + "'. Redes validas: " + store.knownNetworks());
        }
        if (store.isStopped()) {
            return new Decision(false, "kill-switch ativo (arquivo STOP em " + store.stopFile()
                    + "). Somente o dono reativa: bin/social-mcp --cli stop off");
        }
        String state = store.effectiveState(network);
        if (state.equals("banned")) {
            return new Decision(false, "rede '" + network + "' esta BANIDA. Nenhuma acao, nem de leitura na sessao.");
        }
        if (isPublic(kind) && !"healthy".equals(state)) {
            return new Decision(false, "rede '" + network + "' esta '" + state + "' e nao aceita acao publica. Motivo: "
                    + store.stateReason(network) + ". Somente o dono reativa: bin/social-mcp --cli state " + network + " healthy");
        }
        if (!isPublic(kind)) {
            return new Decision(true, "acao de leitura");
        }
        ObjectNode cfg = store.networkCfg(network);
        int maxPub = cfg.path("maxPublicActionsPerDay").asInt(5);
        int minMin = cfg.path("minMinutesBetweenPublicActions").asInt(30);
        Store.Usage u = store.usageFor(network, currentBatchId);
        if (u.publicActions >= maxPub) {
            return new Decision(false, "cota diaria de acoes publicas de '" + network + "' esgotada ("
                    + u.publicActions + "/" + maxPub + "). Volte amanha.");
        }
        if (u.lastPublicActionMs > 0) {
            long need = minMin * 60_000L;
            long elapsed = System.currentTimeMillis() - u.lastPublicActionMs;
            if (elapsed < need) {
                long faltaMin = (need - elapsed + 59_999) / 60_000;
                return new Decision(false, "espacamento minimo de " + minMin + " min entre acoes publicas em '"
                        + network + "'; faltam ~" + faltaMin + " min");
            }
        }
        return new Decision(true, "dentro de cota e espacamento");
    }

    /** Devolve o padrao casado (como esta na config) ou null. */
    public static String tripwireMatch(String pageText, ObjectNode netCfg) {
        if (pageText == null || pageText.isBlank()) return null;
        if (netCfg == null) return null;
        String lower = pageText.toLowerCase();
        JsonNode arr = netCfg.path("tripwires");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String pat = t.asText("").trim();
                if (!pat.isEmpty() && lower.contains(pat.toLowerCase())) return pat;
            }
        }
        return null;
    }

    public static boolean hitsTripwire(String pageText, ObjectNode netCfg) {
        return tripwireMatch(pageText, netCfg) != null;
    }

    /** Ate 60 caracteres antes e depois da 1a ocorrencia (case-insensitive); quebras viram espaco; max 160 chars. */
    public static String contextoTripwire(String pageText, String padrao) {
        if (pageText == null || pageText.isEmpty() || padrao == null || padrao.isBlank()) return "";
        String pat = padrao.trim();
        if (pat.isEmpty()) return "";
        int idx = pageText.toLowerCase().indexOf(pat.toLowerCase());
        if (idx < 0) return "";
        int start = Math.max(0, idx - 60);
        int end = Math.min(pageText.length(), idx + pat.length() + 60);
        String ctx = pageText.substring(start, end).replace('\r', ' ').replace('\n', ' ');
        if (ctx.length() > 160) ctx = ctx.substring(0, 160);
        return ctx;
    }
}
