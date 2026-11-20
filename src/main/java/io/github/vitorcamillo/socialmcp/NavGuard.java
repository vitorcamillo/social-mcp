package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Guarda de navegacao no nivel de requisicao: TODO documento (goto, redirect HTTP,
 * meta refresh, location do JS, inclusive o tardio) e validado no Fetch.requestPaused
 * ANTES de a requisicao sair do Chrome. Documento fora da rede = Fetch.failRequest
 * (BlockedByClient): nem query string chega ao destino. Sub-recursos (XHR, imagem...)
 * e esquemas nao-HTTP (about:, data:) seguem direto. A checagem pos-navegacao do
 * Tools.conferirDestino continua valendo como rede de seguranca.
 */
public final class NavGuard implements AutoCloseable {
    static final String EVENTO = "Fetch.requestPaused";
    private static final float CMD_TIMEOUT_S = 5f;

    public interface Cdp {
        CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> listener);
        CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> listener);
        CompletableFuture<JsonNode> executeCdpCmd(String command, Map<String, Object> params, Float timeout);
    }

    private static final class TargetCdp implements Cdp {
        private final Target target;
        private TargetCdp(Target target) { this.target = target; }
        @Override public CompletableFuture<Void> addCdpListener(String e, Consumer<JsonNode> l) {
            return target.addCdpListener(e, l);
        }
        @Override public CompletableFuture<Void> removeCdpListener(String e, Consumer<JsonNode> l) {
            return target.removeCdpListener(e, l);
        }
        @Override public CompletableFuture<JsonNode> executeCdpCmd(String c, Map<String, Object> p, Float t) {
            return target.executeCdpCmd(c, p, t);
        }
    }

    private final Cdp cdp;
    private final Predicate<String> navegavel;
    private final Consumer<JsonNode> listener = this::aoPausar;
    private String bloqueada;

    public static NavGuard instalar(Target target, Predicate<String> urlNavegavel) {
        return instalar(new TargetCdp(target), urlNavegavel);
    }

    static NavGuard instalar(Cdp cdp, Predicate<String> urlNavegavel) {
        NavGuard guard = new NavGuard(cdp, urlNavegavel);
        try {
            await(cdp.addCdpListener(EVENTO, guard.listener));
            await(cdp.executeCdpCmd("Fetch.enable",
                    Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))), CMD_TIMEOUT_S));
            return guard;
        } catch (Exception e) {
            try { guard.close(); } catch (RuntimeException limpeza) { e.addSuppressed(limpeza); }
            throw new IllegalStateException("Nao foi possivel instalar a guarda de navegacao", e);
        }
    }

    private NavGuard(Cdp cdp, Predicate<String> navegavel) {
        this.cdp = cdp;
        this.navegavel = navegavel;
    }

    /** Ultima navegacao bloqueada (consome o registro); null se nenhuma desde a ultima leitura. */
    synchronized String drenarBloqueada() {
        String url = bloqueada;
        bloqueada = null;
        return url;
    }

    /**
     * Pausa de requisicao: decide e responde SEM bloquear a thread de eventos do CDP
     * (o comando segue async; a requisicao fica presa no Chrome ate continue/fail,
     * entao nada sai antes da decisao).
     */
    private void aoPausar(JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isEmpty()) return; // sem id nao ha como responder; segue o timeout do Chrome
        String url = params.path("request").path("url").asText("");
        boolean documento = "Document".equals(params.path("resourceType").asText(""));
        if (documento && redeHttp(url) && !navegavel.test(url)) {
            synchronized (this) { bloqueada = url; }
            responder("Fetch.failRequest", Map.of("requestId", requestId, "errorReason", "BlockedByClient"));
            return;
        }
        responder("Fetch.continueRequest", Map.of("requestId", requestId));
    }

    /** So http/https leva dados para fora pela rede; about:, data: e afins seguem. */
    private static boolean redeHttp(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private void responder(String comando, Map<String, Object> params) {
        try {
            cdp.executeCdpCmd(comando, params, CMD_TIMEOUT_S).whenComplete((r, e) -> {
                if (e != null) System.err.println("[social-mcp] guarda de navegacao: " + comando + " falhou: " + e);
            });
        } catch (RuntimeException e) {
            System.err.println("[social-mcp] guarda de navegacao: " + comando + " nao saiu: " + e);
        }
    }

    @Override public void close() {
        try {
            await(cdp.removeCdpListener(EVENTO, listener));
            await(cdp.executeCdpCmd("Fetch.disable", Map.of(), CMD_TIMEOUT_S));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desarmar a guarda de navegacao", e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        if (future == null) throw new IllegalStateException("Operacao CDP sem future");
        try {
            return future.get((long) CMD_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timeout na operacao CDP", e);
        }
    }
}
