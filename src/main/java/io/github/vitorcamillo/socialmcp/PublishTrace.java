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

/** Captura passiva e limitada de IDs gerados por publicações em Instagram, TikTok, YouTube, Facebook, Bluesky, Mastodon, X, Pinterest e Reddit. */
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

    private static final String REDDIT_COMMENT_PATH = "^/svc/shreddit/t[13]_[a-z0-9]{1,16}/create-comment$";

    /** Envio de post novo do Reddit: GraphQL do shreddit, rota de submit do shreddit ou /api/submit (nunca /events). */
    static boolean redditPostPath(String path) {
        return path.equals("/svc/shreddit/graphql") || path.equals("/api/submit")
                || path.matches("^/svc/shreddit/(?:[a-z0-9_-]+/){0,3}(?:submit|create-post)[a-z0-9_-]*/?$");
    }

    private static final class RequestState {
        private int status;
        private boolean finished;
        private boolean queued;
        private boolean needsPostData;
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
    private int posts;
    private int caminhoOk;
    private int semCorpo;
    private int comTexto;
    private final List<Integer> statuses = new ArrayList<>();
    private int corpos;
    private String lastError;
    private final List<String> pathsWithText = new ArrayList<>();

    private PublishTrace(Cdp cdp, String network, String approvedText, LongSupplier clockMillis) {
        this.cdp = cdp;
        this.network = normalizeNetwork(network);
        this.approvedText = approvedText;
        this.clockMillis = clockMillis;
        this.startedAt = clockMillis.getAsLong();
        this.bodyExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("publish-trace-body").daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
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
            markInvalid(e);
        }
    }

    public synchronized String diagnostico() {
        String ids = capturedIds.stream().sorted().limit(5).collect(
                java.util.stream.Collectors.joining(", ", "[", "]"));
        String status = statuses.stream().limit(5).map(String::valueOf).collect(
                java.util.stream.Collectors.joining(", ", "[", "]"));
        String caminhos = pathsWithText.stream().limit(5).collect(
                java.util.stream.Collectors.joining(", ", "[", "]"));
        return "posts=" + posts + " caminhoOk=" + caminhoOk + " semCorpo=" + semCorpo
                + " comTexto=" + comTexto + " status=" + status + " corpos=" + corpos
                + " ids=" + ids + " invalido=" + invalid + " erro=" + (lastError == null ? "-" : lastError)
                + " caminhosComTexto=" + caminhos;
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
        String url = request.path("url").asText("");
        if (requestId.isEmpty() || !"POST".equalsIgnoreCase(request.path("method").asText())
                || !hostBase(url)) return;
        posts++;
        String body = request.path("postData").asText("");
        boolean empty = body.isEmpty();
        if (!empty && containsApprovedText(body)) {
            comTexto++;
            recordPathWithText(url);
        }
        if (!allowedHost(url)) return;
        caminhoOk++;
        if (empty) {
            if (!request.path("hasPostData").asBoolean(false)) return;
            semCorpo++;
            if (!requests.containsKey(requestId) && requests.size() >= MAX_REQUESTS) {
                invalid = true;
                return;
            }
            RequestState state = requests.computeIfAbsent(requestId, ignored -> new RequestState());
            state.needsPostData = true;
            if (network.equals("bsky") || network.equals("reddit") || network.equals("ig")) {
                state.path = URI.create(url).getPath();
            }
            return;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY || !containsApprovedText(body)) return;
        if (!requests.containsKey(requestId) && requests.size() >= MAX_REQUESTS) {
            invalid = true;
            return;
        }
        RequestState state = requests.computeIfAbsent(requestId, ignored -> new RequestState());
        if (network.equals("bsky")) {
            state.postData = body;
        }
        if (network.equals("bsky") || network.equals("reddit") || network.equals("ig")) {
            state.path = URI.create(url).getPath();
        }
    }

    private synchronized void recordPathWithText(String url) {
        if (pathsWithText.size() >= 5) return;
        try {
            String path = URI.create(url).getPath();
            if (path == null) return;
            pathsWithText.add(path.substring(0, Math.min(path.length(), 100)));
        } catch (RuntimeException ignored) {
            // URL invalida: ignora registro de caminho
        }
    }

    private synchronized void onResponse(JsonNode params) {
        if (expiredOrInvalid()) return;
        String requestId = params.path("requestId").asText("");
        RequestState state = requests.get(requestId);
        if (state != null) {
            int status = params.path("response").path("status").asInt();
            if (statuses.size() < 5) statuses.add(status);
            state.status = status;
            if (state.status < 200 || state.status >= 300) requests.remove(requestId);
        }
    }

    private synchronized void onFinished(JsonNode params) {
        if (expiredOrInvalid()) return;
        String requestId = params.path("requestId").asText("");
        RequestState state = requests.get(requestId);
        // status >= 300 nunca chega aqui: onResponse ja tirou o pedido do mapa
        if (state == null || state.status < 200 || state.queued) return;
        state.finished = true;
        state.queued = true;
        try {
            bodyExecutor.execute(() -> fetchResponseBody(requestId));
        } catch (RuntimeException e) {
            markInvalid(e);
        }
    }

    private void fetchResponseBody(String requestId) {
        try {
            RequestState state;
            synchronized (this) {
                state = requests.get(requestId);
            }
            if (state == null) return;
            if (state.needsPostData) {
                JsonNode postResult;
                try {
                    postResult = await(cdp.executeCdpCmd("Network.getRequestPostData",
                            Map.of("requestId", requestId), (float) OP_TIMEOUT_SECONDS));
                } catch (Exception e) {
                    synchronized (this) {
                        requests.remove(requestId);
                        lastError = e.getClass().getSimpleName();
                    }
                    return;
                }
                if (postResult == null) {
                    synchronized (this) {
                        requests.remove(requestId);
                        lastError = "IllegalStateException";
                    }
                    return;
                }
                String postData = postResult.path("postData").asText("");
                if (postData.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY
                        || !containsApprovedText(postData)) {
                    synchronized (this) {
                        requests.remove(requestId);
                    }
                    return;
                }
                synchronized (this) {
                    RequestState current = requests.get(requestId);
                    if (current == null) return;
                    state = current;
                    comTexto++;
                    if (state.path != null && pathsWithText.size() < 5) {
                        pathsWithText.add(state.path.substring(0, Math.min(state.path.length(), 100)));
                    }
                    if (network.equals("bsky")) {
                        state.postData = postData;
                    }
                    state.needsPostData = false;
                }
            }
            JsonNode result = await(cdp.executeCdpCmd("Network.getResponseBody",
                    Map.of("requestId", requestId), (float) OP_TIMEOUT_SECONDS));
            if (result == null) throw new IllegalStateException("Resposta CDP ausente");
            synchronized (this) {
                corpos++;
            }
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
            if (network.equals("reddit")) {
                // Resposta HTML do create-comment: o fragmento do comentario novo traz o id como t1_<id>
                // (atributo thingid, permalink ou JSON embutido; aspas podem vir escapadas). So ids deste
                // endpoint entram; o verify_new_id ainda exige autor da conta e id ausente da captura.
                // Criacao de post (graphql/submit, so com o titulo/texto aprovado no corpo): ids t3_<id>.
                java.util.Set<String> occurrences = new java.util.LinkedHashSet<>();
                boolean comentario = state.path == null || state.path.matches(REDDIT_COMMENT_PATH);
                var shreddit = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + (comentario ? "t1_" : "t3_")
                        + "([a-z0-9]{1,16})(?![a-z0-9])").matcher(body);
                while (shreddit.find()) occurrences.add(shreddit.group(1));
                synchronized (this) {
                    if (expiredOrInvalid()) return;
                    if (capturedIds.size() + occurrences.size() > MAX_IDS) {
                        invalid = true;
                        return;
                    }
                    capturedIds.addAll(occurrences);
                    requests.remove(requestId);
                }
                return;
            }
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
            markInvalid(e);
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
        if (network.equals("ig") && state.path != null
                && state.path.matches("^/api/v1/web/comments/[0-9]{1,32}/add/?$")) {
            Set<String> commentIds = new HashSet<>();
            int[] commentNodes = {0};
            collect(root, 0, commentNodes, Set.of("id", "pk"), commentIds);
            commentIds.removeIf(id -> !id.matches("[0-9]{1,32}"));
            return commentIds;
        }
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

    private boolean hostBase(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
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
                case "reddit" -> "reddit.com";
                default -> "";
            };
            return !base.isEmpty() && (host.equals(base) || host.endsWith("." + base)
                    || (network.equals("bsky") && host.endsWith(".bsky.network"))
                    || (network.equals("x") && (host.equals("twitter.com") || host.endsWith(".twitter.com"))));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean allowedHost(String url) {
        // hostBase ja parseou a URL e exigiu host: aqui a URI e hierarquica (path nunca null)
        if (!hostBase(url)) return false;
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        return switch (network) {
            case "bsky" -> path.equals("/xrpc/com.atproto.repo.createRecord") || path.equals("/xrpc/com.atproto.repo.applyWrites");
            case "mastodon" -> host.equals("mastodon.social") && path.equals("/api/v1/statuses");
            case "x" -> path.matches("^/i/api/graphql/[A-Za-z0-9_-]+/CreateTweet$");
            case "pinterest" -> path.equals("/resource/PinResource/create/");
            case "reddit" -> path.matches(REDDIT_COMMENT_PATH) || redditPostPath(path);
            default -> true;
        };
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
            return newline == -1 ? "" : body.substring(newline + 1);
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

    private synchronized void markInvalid(Throwable e) {
        invalid = true;
        if (e != null) lastError = e.getClass().getSimpleName();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // limpeza completa mesmo com a thread interrompida (o get do future engoliria a flag e
        // pularia a espera); a flag volta no fim para quem chamou
        boolean interrompida = Thread.interrupted();
        try {
            RuntimeException failure = null;
            for (Map.Entry<String, Consumer<JsonNode>> entry : new ArrayList<>(listeners.entrySet())) {
                try {
                    await(cdp.removeCdpListener(entry.getKey(), entry.getValue()));
                } catch (Exception e) {
                    if (e instanceof InterruptedException) interrompida = true;
                    markInvalid(e);
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
        } finally {
            if (interrompida) Thread.currentThread().interrupt();
        }
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
            case "reddit" -> "reddit";
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
