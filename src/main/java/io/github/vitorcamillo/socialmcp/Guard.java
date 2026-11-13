package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
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
        // trava por rede apos envio incerto/duplicata suspeita (lote em curso nunca esta em executed,
        // entao nao se bloqueia); so social_resolve_incerteza libera
        List<String> pendentes = store.incertezasPendentes(network);
        if (!pendentes.isEmpty()) {
            return new Decision(false, motivoTravaIncerteza(network, pendentes));
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

    /**
     * Mensagem da trava por rede: lote(s) com envio incerto (abortado_apos_publico) ou duplicata
     * suspeita so saem da lista com social_resolve_incerteza, que registra o que a conferencia
     * NA REDE mostrou — a liberacao nunca sai de uma checagem aqui dentro.
     */
    public static String motivoTravaIncerteza(String network, List<String> lotes) {
        return "rede '" + network + "' travada: lote(s) " + String.join(", ", lotes)
                + " terminaram com envio incerto ou duplicata suspeita. Confira na rede e registre com social_resolve_incerteza"
                + " (no_ar|nao_saiu|apagado) antes de qualquer acao publica.";
    }

    /** Devolve o padrao casado (como esta na config) ou null. */
    public static String tripwireMatch(String pageText, ObjectNode netCfg) {
        return tripwireMatch(pageText, netCfg, System.currentTimeMillis());
    }

    /** Marcador de criadoEm/expiraEm ilegivel (ou ausente). */
    static final long DATA_ILEGIVEL = Long.MIN_VALUE;
    private static final long PRAZO_TRIPWIRE_IGNORE_MS = 24 * 3_600_000L;

    /**
     * Frases inteiras de componente da pagina que contem um padrao de tripwire mas nao sao
     * bloqueio da conta (revisadas em codigo, sem prazo; o config do agente nao as altera).
     * So a frase completa sai: "tente novamente mais tarde" solto continua disparando.
     */
    static final List<String> FRASES_INOFENSIVAS = List.of(
            // YouTube Shorts: o botao Compartilhar falha ao carregar os destinos (11/11/2026)
            "ocorreu um erro ao recuperar informações de compartilhamento. tente novamente mais tarde.",
            "an error occurred while retrieving sharing information. please try again later.");

    /** Epoch ms de um numero (epoch ms) ou texto ISO-8601 com offset; ilegivel = DATA_ILEGIVEL. */
    static long epochMsDe(JsonNode n) {
        if (n == null || n.isNull()) return DATA_ILEGIVEL;
        if (n.isNumber()) return n.asLong(DATA_ILEGIVEL);
        if (n.isTextual()) {
            String s = n.asText().trim();
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
            try {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        return DATA_ILEGIVEL;
    }

    /**
     * Frase de tripwireIgnore que vale agora, ou null: item em texto puro (formato antigo), sem
     * criadoEm/expiraEm legiveis, ou com o prazo vencido volta a disparar o tripwire. O item
     * objeto vale enquanto agora &lt; min(expiraEm, criadoEm + 24h).
     */
    private static String fraseIgnoradaValida(JsonNode t, long agoraMs) {
        if (!t.isObject()) return null;
        String frase = t.path("frase").asText("").trim().toLowerCase().replaceAll("\\s+", " ");
        if (frase.isEmpty()) return null;
        long criado = epochMsDe(t.get("criadoEm"));
        long expira = epochMsDe(t.get("expiraEm"));
        if (criado == DATA_ILEGIVEL || expira == DATA_ILEGIVEL) return null;
        if (agoraMs >= Math.min(expira, criado + PRAZO_TRIPWIRE_IGNORE_MS)) return null;
        return frase;
    }

    /** Variante com relogio injetado (teste). */
    public static String tripwireMatch(String pageText, ObjectNode netCfg, long agoraMs) {
        if (pageText == null || pageText.isBlank()) return null;
        if (netCfg == null) return null;
        String lower = pageText.toLowerCase().replaceAll("\\s+", " ");
        // frases conhecidas e inofensivas (ex.: notificacao antiga ja resolvida) saem antes do casamento;
        // cada ignoro tem prazo (criadoEm/expiraEm): vencido, a frase volta a disparar
        for (String frase : FRASES_INOFENSIVAS) lower = lower.replace(frase, " ");
        JsonNode ignorar = netCfg.path("tripwireIgnore");
        if (ignorar.isArray()) {
            for (JsonNode t : ignorar) {
                String frase = fraseIgnoradaValida(t, agoraMs);
                if (frase != null) lower = lower.replace(frase, " ");
            }
        }
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
