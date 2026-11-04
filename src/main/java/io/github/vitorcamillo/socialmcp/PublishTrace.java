package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.types.Target;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Captura passiva e limitada de IDs gerados por publicações em Instagram, TikTok, YouTube, Facebook, Bluesky, Mastodon, X e Pinterest. */
public final class PublishTrace implements AutoCloseable {
    private static final int MAX_REQUEST_BODY = 64 * 1024;
    private static final int MAX_RESPONSE_BODY = 256 * 1024;
    private static final int MAX_REQUESTS = 32;
    private static final int MAX_IDS = 64;
    private static final long TTL_MILLIS = 1_200_000;
    private static final long OP_TIMEOUT_SECONDS = 5;
    private static final List<String> EVENTS = List.of(
            "Network.requestWillBeSent", "Network.responseReceived", "Network.loadingFinished");

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

    private static final class RequestState {
        private int status;
        private boolean finished;
        private boolean queued;
        private String postData;
        private String path;
    }

    private final Cdp cdp;
    private final String network;
    private final String approvedText;
    private final LongSupplier clockMillis;
    private final long startedAt;
    private final ThreadPoolExecutor bodyExecutor;
    private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
    private final Map<String, RequestState> requests = new HashMap<>();
    private final Set<String> capturedIds = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean invalid;

    private PublishTrace(Cdp cdp, String network, String approvedText, LongSupplier clockMillis) {
        this.cdp = cdp;
        this.network = normalizeNetwork(network);
        this.approvedText = approvedText;
        this.clockMillis = clockMillis;
        this.startedAt = clockMillis.getAsLong();
        this.bodyExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), r -> {
                    Thread t = new Thread(r, "publish-trace-body");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static PublishTrace start(Target target, String network, String approvedText) {
        return start(new TargetCdp(target), network, approvedText);
    }

    public static PublishTrace start(Cdp cdp, String network, String approvedText) {
        return start(cdp, network, approvedText, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static PublishTrace start(Cdp cdp, String network, String approvedText, LongSupplier clockMillis) {
        if (cdp == null || approvedText == null || approvedText.isEmpty()) {
            throw new IllegalArgumentException("CDP e texto aprovado sao obrigatorios");
        }
        PublishTrace trace = new PublishTrace(cdp, network, approvedText, clockMillis);
        try {
            for (String event : EVENTS) {
                Consumer<JsonNode> listener = params -> trace.onEvent(event, params);
                trace.listeners.put(event, listener);
                trace.await(cdp.addCdpListener(event, listener));
            }
            trace.await(cdp.executeCdpCmd("Network.enable", Map.of("maxResourceBufferSize", 256 * 1024, "maxTotalBufferSize", 1024 * 1024), (float) OP_TIMEOUT_SECONDS));
            return trace;
        } catch (Exception e) {
            trace.markInvalid();
            try { trace.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw new IllegalStateException("Nao foi possivel iniciar captura passiva", e);
        }
    }

    public synchronized Set<String> ids() {
        return Set.copyOf(capturedIds);
    }

    public synchronized void assertMatched(String candidateId) {
        if (closed.get() || invalid || clockMillis.getAsLong() - startedAt >= TTL_MILLIS) {
            invalid = true;
            throw new IllegalStateException("Trace de publicacao invalido ou expirado");
        }
        if (candidateId == null || !capturedIds.contains(candidateId)) {
            throw new IllegalArgumentException("ID candidato nao foi capturado pela publicacao");
        }
    }

    private void onEvent(String event, JsonNode params) {
        if (closed.get()) return;
        try {
            switch (event) {
                case "Network.requestWillBeSent" -> onRequest(params);
                case "Network.responseReceived" -> onResponse(params);
                case "Network.loadingFinished" -> onFinished(params);
                default -> { }
            }
        } catch (RuntimeException e) {
            markInvalid();
        }
    }

    private synchronized void onRequest(JsonNode params) {
        if (expiredOrInvalid()) return;
        // A redirect event carries the prior response and is never considered publication proof.
        String requestId = params.path("requestId").asText("");
        if (params.has("redirectResponse")) {
            requests.remove(requestId);
            return;
        }
        JsonNode request = params.path("request");
        if (requestId.isEmpty() || !"POST".equalsIgnoreCase(request.path("method").asText())
                || !allowedHost(request.path("url").asText())) return;
        String body = request.path("postData").asText("");
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY || !containsApprovedText(body)) return;
        if (!requests.containsKey(requestId) && requests.size() >= MAX_REQUESTS) {
            invalid = true;
            return;
        }
        RequestState state = requests.computeIfAbsent(requestId, ignored -> new RequestState());
        if (network.equals("bsky")) {
            state.postData = body;
            state.path = URI.create(request.path("url").asText()).getPath();
        }
    }

    private synchronized void onResponse(JsonNode params) {
        if (expiredOrInvalid()) return;
        String requestId = params.path("requestId").asText("");
        RequestState state = requests.get(requestId);
        if (state != null) {
            state.status = params.path("response").path("status").asInt();
            if (state.status < 200 || state.status >= 300) requests.remove(requestId);
        }
    }

    private synchronized void onFinished(JsonNode params) {
        if (expiredOrInvalid()) return;
        String requestId = params.path("requestId").asText("");
        RequestState state = requests.get(requestId);
        if (state == null || state.status < 200 || state.status >= 300 || state.queued) return;
        state.finished = true;
        state.queued = true;
        try {
            bodyExecutor.execute(() -> fetchResponseBody(requestId));
        } catch (RuntimeException e) {
            invalid = true;
        }
    }

    private void fetchResponseBody(String requestId) {
        try {
            RequestState state;
            synchronized (this) {
                state = requests.get(requestId);
            }
            if (state == null) return;
            JsonNode result = await(cdp.executeCdpCmd("Network.getResponseBody",
                    Map.of("requestId", requestId), (float) OP_TIMEOUT_SECONDS));
            if (result == null) throw new IllegalStateException("Resposta CDP ausente");
            String body = result.path("body").asText("");
            if (result.path("base64Encoded").asBoolean()) {
                if (body.length() > ((MAX_RESPONSE_BODY + 2) / 3) * 4) {
                    throw new IllegalStateException("Resposta excede limite");
                }
                byte[] decoded = Base64.getDecoder().decode(body);
                if (decoded.length > MAX_RESPONSE_BODY) throw new IllegalStateException("Resposta excede limite");
                body = new String(decoded, StandardCharsets.UTF_8);
            } else if (body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BODY) {
                throw new IllegalStateException("Resposta excede limite");
            }
            body = stripXssi(body);
            JsonNode root = Json.M.readTree(body);
            if (root == null) throw new IllegalStateException("JSON vazio");
            Set<String> found = extractIds(root, state);
            synchronized (this) {
                if (expiredOrInvalid()) return;
                for (String id : found) {
                    if (!capturedIds.contains(id) && capturedIds.size() >= MAX_IDS) {
                        invalid = true;
                        return;
                    }
                    capturedIds.add(id);
                }
                requests.remove(requestId);
            }
        } catch (Exception e) {
            markInvalid();
        }
    }

    private Set<String> extractIds(JsonNode root, RequestState state) {
        if (network.equals("bsky")) {
            return extractBskyIds(root, state);
        }
        if (network.equals("mastodon")) return root.isObject() ? numericId(root.get("id")) : Set.of();
        if (network.equals("x")) {
            JsonNode result = root.path("data").path("create_tweet").path("tweet_results").path("result");
            if ("TweetWithVisibilityResults".equals(result.path("__typename").asText())) result = result.path("tweet");
            return numericId(result.get("rest_id"));
        }
        if (network.equals("pinterest")) return numericId(root.path("resource_response").path("data").get("id"));
        Set<String> allowed = switch (network) {
            case "ig" -> Set.of("media_id", "pk", "code");
            case "tt" -> Set.of("item_id", "aweme_id");
            case "yt" -> Set.of("videoId");
            case "fb" -> Set.of("page_id", "pageID");
            default -> Set.of();
        };
        Set<String> result = new HashSet<>();
        int[] nodes = {0};
        collect(root, 0, nodes, allowed, result);
        return result;
    }

    private static Set<String> numericId(JsonNode value) {
        if (value == null || !(value.isTextual() || value.isNumber())) return Set.of();
        String id = value.asText(); return id.matches("[0-9]{1,32}") ? Set.of(id) : Set.of();
    }

    private Set<String> extractBskyIds(JsonNode response, RequestState state) {
        JsonNode request;
        try {
            request = Json.M.readTree(state.postData == null ? "" : state.postData);
        } catch (Exception e) {
            return Set.of();
        }
        if (request == null || !request.isObject()) return Set.of();
        if ("/xrpc/com.atproto.repo.createRecord".equals(state.path)) {
            if (!"app.bsky.feed.post".equals(request.path("collection").asText())
                    || !request.path("record").path("text").isTextual()
                    || !request.path("record").path("text").asText().contains(approvedText)) return Set.of();
            return bskyPostId(response.path("uri"));
        }
        if (!"/xrpc/com.atproto.repo.applyWrites".equals(state.path)) return Set.of();
        JsonNode writes = request.path("writes");
        JsonNode results = response.path("results");
        if ((writes.isArray() && writes.size() > 200) || (results.isArray() && results.size() > 200)) {
            throw new IllegalStateException("applyWrites excede 200 itens");
        }
        if (!writes.isArray() || !results.isArray()) return Set.of();
        if (writes.size() != results.size()) return Set.of();
        Set<String> ids = new HashSet<>();
        for (int k = 0; k < writes.size(); k++) {
            JsonNode write = writes.get(k);
            JsonNode result = results.get(k);
            if (!"com.atproto.repo.applyWrites#create".equals(write.path("$type").asText())
                    || !"app.bsky.feed.post".equals(write.path("collection").asText())
                    || !write.path("value").path("text").isTextual()
                    || !write.path("value").path("text").asText().contains(approvedText)
                    || !"com.atproto.repo.applyWrites#createResult".equals(result.path("$type").asText())) continue;
            ids.addAll(bskyPostId(result.get("uri")));
        }
        return ids;
    }

    private static Set<String> bskyPostId(JsonNode uri) {
        if (uri == null || !uri.isTextual()) return Set.of();
        var matcher = java.util.regex.Pattern.compile("^at://did:[a-z0-9]+:[A-Za-z0-9._:%-]+/app\\.bsky\\.feed\\.post/([a-z0-9]{1,64})$").matcher(uri.asText());
        return matcher.matches() ? Set.of(matcher.group(1)) : Set.of();
    }

    private void collect(JsonNode node, int depth, int[] nodes, Set<String> allowed, Set<String> out) {
        if (depth > 20 || ++nodes[0] > 4096) throw new IllegalStateException("Resposta JSON excede limites");
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (allowed.contains(field.getKey()) && value.isValueNode()) {
                    String id = value.asText();
                    if (id.matches("[A-Za-z0-9_-]{1,64}")) out.add(id);
                }
                collect(value, depth + 1, nodes, allowed, out);
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) collect(value, depth + 1, nodes, allowed, out);
        }
    }

    private boolean allowedHost(String url) {
        try {
            URI uri = URI.create(url); String host = uri.getHost(); String path = uri.getPath();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String base = switch (network) {
                case "ig" -> "instagram.com";
                case "tt" -> "tiktok.com";
                case "yt" -> "youtube.com";
                case "fb" -> "facebook.com";
                case "bsky" -> "bsky.social";
                case "mastodon" -> "mastodon.social";
                case "x" -> "x.com";
                case "pinterest" -> "pinterest.com";
                default -> "";
            };
            boolean hostOk = !base.isEmpty() && (host.equals(base) || host.endsWith("." + base)
                    || (network.equals("bsky") && host.endsWith(".bsky.network"))
                    || (network.equals("x") && (host.equals("twitter.com") || host.endsWith(".twitter.com"))));
            if (!hostOk) return false;
            return switch (network) {
                case "bsky" -> path.equals("/xrpc/com.atproto.repo.createRecord") || path.equals("/xrpc/com.atproto.repo.applyWrites");
                case "mastodon" -> host.equals("mastodon.social") && path.equals("/api/v1/statuses");
                case "x" -> path.matches("^/i/api/graphql/[A-Za-z0-9_-]+/CreateTweet$");
                case "pinterest" -> path.equals("/resource/PinResource/create/");
                default -> true;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean containsApprovedText(String body) {
        String escaped = Json.write(approvedText);
        if (body.contains(approvedText) || body.contains(escaped.substring(1, escaped.length() - 1))) return true;
        try {
            String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
            return decoded.contains(approvedText) || decoded.contains(escaped.substring(1, escaped.length() - 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripXssi(String body) {
        if (body.startsWith(")]}'")) {
            int newline = body.indexOf('\n');
            return newline < 0 ? "" : body.substring(newline + 1);
        }
        for (String prefix : List.of("for(;;);", "for(;;)", "for (;;);", "for (;;)")) {
            if (body.startsWith(prefix)) return body.substring(prefix.length());
        }
        return body;
    }

    private boolean expiredOrInvalid() {
        if (invalid || closed.get() || clockMillis.getAsLong() - startedAt >= TTL_MILLIS) {
            invalid = true;
            return true;
        }
        return false;
    }

    private synchronized void markInvalid() { invalid = true; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        for (Map.Entry<String, Consumer<JsonNode>> entry : new ArrayList<>(listeners.entrySet())) {
            try {
                await(cdp.removeCdpListener(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                markInvalid();
                if (failure == null) failure = new IllegalStateException("Falha ao limpar listener CDP", e);
                else failure.addSuppressed(e);
            }
        }
        listeners.clear();
        synchronized (this) {
            requests.clear();
            capturedIds.clear();
        }
        bodyExecutor.shutdownNow();
        if (failure != null) throw failure;
    }

    private static String normalizeNetwork(String network) {
        String n = network == null ? "" : network.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "ig", "instagram" -> "ig";
            case "tt", "tiktok" -> "tt";
            case "yt", "youtube" -> "yt";
            case "fb", "facebook" -> "fb";
            case "bluesky", "bsky" -> "bsky";
            case "mastodon" -> "mastodon";
            case "x", "twitter" -> "x";
            case "pinterest" -> "pinterest";
            default -> throw new IllegalArgumentException("Rede sem suporte para captura de publicacao");
        };
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        if (future == null) throw new IllegalStateException("Operacao CDP sem future");
        try {
            return future.get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timeout na operacao CDP", e);
        }
    }
}
