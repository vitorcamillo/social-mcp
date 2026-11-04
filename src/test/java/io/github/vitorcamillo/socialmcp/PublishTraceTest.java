package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PublishTraceTest {
    private static final String TEXT = "texto aprovado";

    private static final class FakeCdp implements PublishTrace.Cdp {
        final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final CountDownLatch bodyRequested = new CountDownLatch(1);
        volatile JsonNode response = Json.obj().put("body", "{\"media_id\":\"media_123\"}")
                .put("base64Encoded", false);
        volatile boolean failAdd;
        volatile boolean failRemove;
        volatile boolean failBody;

        @Override public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> listener) {
            if (failAdd && event.equals("Network.responseReceived")) return failed();
            listeners.put(event, listener);
            added.add(event);
            return completed();
        }

        @Override public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> listener) {
            removed.add(event);
            listeners.remove(event, listener);
            return failRemove ? failed() : completed();
        }

        @Override public CompletableFuture<JsonNode> executeCdpCmd(String command, Map<String, Object> params, Float timeout) {
            if (command.equals("Network.getResponseBody")) {
                bodyRequested.countDown();
                if (failBody) return CompletableFuture.failedFuture(new IllegalStateException("fake failure"));
                return CompletableFuture.completedFuture(response);
            }
            return CompletableFuture.completedFuture(Json.obj());
        }

        void emit(String event, String json) { listeners.get(event).accept(Json.parse(json)); }
        void publicationRequest(String id, String url, String method, String body) {
            ObjectNode p = Json.obj();
            p.put("requestId", id);
            ObjectNode request = p.putObject("request");
            request.put("url", url);
            request.put("method", method);
            request.put("postData", body);
            emit("Network.requestWillBeSent", p.toString());
        }
        void response(String id, int status) {
            emit("Network.responseReceived", "{\"requestId\":\"" + id + "\",\"response\":{\"status\":" + status + "}}");
            emit("Network.loadingFinished", "{\"requestId\":\"" + id + "\"}");
        }
        boolean awaitBody() throws InterruptedException { return bodyRequested.await(1, TimeUnit.SECONDS); }
    }

    private static <T> CompletableFuture<T> completed() { return CompletableFuture.completedFuture(null); }
    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("fake failure"));
    }

    private static void waitForId(PublishTrace trace, String id) throws InterruptedException {
        CountDownLatch captured = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (trace.ids().contains(id)) {
                captured.countDown();
                break;
            }
            Thread.yield();
        }
        assertTrue(captured.await(1, TimeUnit.SECONDS), "ID nao processado dentro do prazo");
    }

    private PublishTrace start(FakeCdp cdp) { return PublishTrace.start(cdp, "instagram", TEXT); }

    @Test
    void capturaSomentePostHostTextoAprovadoEResposta2xxEProvaIdForaDoDom() throws Exception {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = start(cdp);
        cdp.publicationRequest("bad-host", "https://instagram.com.evil.test/api", "POST", TEXT);
        cdp.publicationRequest("bad-text", "https://www.instagram.com/api", "POST", "outro texto");
        cdp.publicationRequest("bad-method", "https://instagram.com/api", "PUT", TEXT);
        cdp.publicationRequest("redirect", "https://instagram.com/api", "POST", TEXT);
        cdp.emit("Network.requestWillBeSent", "{\"requestId\":\"redirect\",\"redirectResponse\":{},\"request\":{\"url\":\"https://instagram.com/api\",\"method\":\"POST\",\"postData\":\"texto aprovado\"}}");
        cdp.publicationRequest("http-fail", "https://www.instagram.com/api", "POST", TEXT);
        cdp.response("http-fail", 503);
        cdp.publicationRequest("valid", "https://www.instagram.com/api", "POST", "text=texto%20aprovado");
        cdp.response("valid", 201);

        assertTrue(cdp.awaitBody());
        // A completion latch is signalled from the executor request; the returned body is already available.
        waitForId(trace, "media_123");
        assertEquals(Set.of("media_123"), trace.ids());
        assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("id-antigo-do-dom"));
        assertDoesNotThrow(() -> trace.assertMatched("media_123"));
        trace.close();
    }

    @Test
    void capturaIdDeBodyBase64EAplicaXssiConhecido() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", java.util.Base64.getEncoder().encodeToString(
                ")]}'\n{\"videoId\":\"vid_42\",\"id\":\"ignored\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .put("base64Encoded", true);
        PublishTrace trace = PublishTrace.start(cdp, "youtube", TEXT);
        cdp.publicationRequest("yt", "https://youtube.com/upload", "POST", "caption=" + TEXT);
        cdp.response("yt", 200);
        assertTrue(cdp.awaitBody());
        waitForId(trace, "vid_42");
        assertEquals(Set.of("vid_42"), trace.ids());
        trace.close();
    }

    @Test
    void aplicaXssiForDoFacebook() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", "for(;;);{\"id\":\"ignored\",\"page_id\":\"fb_7\"}").put("base64Encoded", false);
        PublishTrace trace = PublishTrace.start(cdp, "facebook", TEXT);
        cdp.publicationRequest("fb", "https://www.facebook.com/api", "POST", TEXT);
        cdp.response("fb", 200);
        assertTrue(cdp.awaitBody());
        waitForId(trace, "fb_7");
        assertEquals(Set.of("fb_7"), trace.ids());
        trace.close();
    }

    @Test
    void aplicaPrefixoFacebookComEspacoSemPontoEVirgula() throws Exception {
        for (String prefix : List.of("for (;;);", "for (;;)", "for(;;)")) {
            FakeCdp cdp = new FakeCdp();
            cdp.response = Json.obj().put("body", prefix + "{\"page_id\":\"fb_space\"}").put("base64Encoded", false);
            PublishTrace trace = PublishTrace.start(cdp, "fb", TEXT);
            cdp.publicationRequest("fb", "https://facebook.com/api", "POST", TEXT);
            cdp.response("fb", 200);
            assertTrue(cdp.awaitBody());
            waitForId(trace, "fb_space");
            assertEquals(Set.of("fb_space"), trace.ids());
            trace.close();
        }
    }

    @Test
    void jsonMalformadoSaturacaoErroDeCleanupEExpiracaoInvalidamTrace() throws Exception {
        FakeCdp malformed = new FakeCdp();
        malformed.response = Json.obj().put("body", "not-json").put("base64Encoded", false);
        PublishTrace bad = start(malformed);
        malformed.publicationRequest("malformed", "https://instagram.com/api", "POST", TEXT);
        malformed.response("malformed", 200);
        assertTrue(malformed.awaitBody());
        // The malformed response is processed asynchronously and invalidates rather than partially matching.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            try {
                bad.assertMatched("media_123");
            } catch (IllegalStateException expected) {
                break;
            } catch (IllegalArgumentException notProcessedYet) {
                Thread.yield();
            }
        }
        assertThrows(IllegalStateException.class, () -> bad.assertMatched("media_123"));
        bad.close();

        FakeCdp flooded = new FakeCdp();
        PublishTrace full = start(flooded);
        for (int i = 0; i < 33; i++) flooded.publicationRequest("r" + i,
                "https://instagram.com/api", "POST", TEXT);
        assertThrows(IllegalStateException.class, () -> full.assertMatched("anything"));
        full.close();

        FakeCdp cleanup = new FakeCdp();
        cleanup.failAdd = true;
        assertThrows(IllegalStateException.class, () -> start(cleanup));
        assertEquals(2, cleanup.removed.size(), "remove listeners registered antes da falha");

        FakeCdp expiring = new FakeCdp();
        AtomicLong now = new AtomicLong(10);
        PublishTrace ttl = PublishTrace.start(expiring, "ig", TEXT, now::get);
        now.set(1_200_009);
        assertThrows(IllegalArgumentException.class, () -> ttl.assertMatched("x"));
        now.set(1_200_010);
        assertThrows(IllegalStateException.class, () -> ttl.assertMatched("x"));
        ttl.close();
    }

    @Test
    void cleanupFalhaAindaTentaTodosListenersECloseConcorrenteNaoReabreCaptura() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = start(cdp);
        Consumer<JsonNode> callback = cdp.listeners.get("Network.requestWillBeSent");
        CountDownLatch invoke = new CountDownLatch(1);
        Thread callbackThread = new Thread(() -> {
            try {
                invoke.await();
                callback.accept(Json.parse("{\"requestId\":\"late\",\"request\":{\"url\":\"https://instagram.com/api\",\"method\":\"POST\",\"postData\":\"texto aprovado\"}}"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        callbackThread.start();
        cdp.failRemove = true;
        assertThrows(IllegalStateException.class, trace::close);
        invoke.countDown();
        try { callbackThread.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(e); }
        assertFalse(callbackThread.isAlive());
        assertEquals(3, cdp.removed.size());
        assertTrue(cdp.listeners.isEmpty());
        assertThrows(IllegalStateException.class, () -> trace.assertMatched("id"));
    }

    private static void assertNadaCapturadoComEventosCompletos(FakeCdp cdp, PublishTrace trace)
            throws InterruptedException {
        assertFalse(cdp.bodyRequested.await(400, TimeUnit.MILLISECONDS),
                "nenhum corpo deveria ser buscado para requisicao rejeitada");
        assertTrue(trace.ids().isEmpty(), "IDs nao deveriam ser capturados");
        assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("media_123"));
    }

    @Test
    void rejeitaPostEmHostnameDeTerceiroSemSubstringDaRede() throws Exception {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = start(cdp);
        cdp.publicationRequest("evil", "https://evil.example.org/api/publish", "POST", TEXT);
        cdp.response("evil", 200);
        cdp.publicationRequest("evil2", "https://attacker.test/publish", "POST", TEXT);
        cdp.response("evil2", 200);
        assertNadaCapturadoComEventosCompletos(cdp, trace);
        trace.close();
    }

    @Test
    void rejeitaTextoSemAprovadoEmPlainJsonEUrlencoded() throws Exception {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = start(cdp);
        cdp.publicationRequest("plain-ruim", "https://www.instagram.com/api", "POST", "outro texto qualquer");
        cdp.response("plain-ruim", 200);
        cdp.publicationRequest("json-ruim", "https://www.instagram.com/api", "POST",
                "{\"caption\":\"outro texto qualquer\"}");
        cdp.response("json-ruim", 200);
        cdp.publicationRequest("form-ruim", "https://www.instagram.com/api", "POST", "text=outro%20texto%20qualquer");
        cdp.response("form-ruim", 200);
        assertNadaCapturadoComEventosCompletos(cdp, trace);
        trace.close();
    }

    @Test
    void aceitaHostExatoESubdominioERejeitaPrefixoMalicioso() throws Exception {
        FakeCdp exact = new FakeCdp();
        PublishTrace exactTrace = start(exact);
        exact.publicationRequest("exact", "https://instagram.com/api", "POST", TEXT);
        exact.response("exact", 200);
        assertTrue(exact.awaitBody());
        waitForId(exactTrace, "media_123");
        assertEquals(Set.of("media_123"), exactTrace.ids());
        assertDoesNotThrow(() -> exactTrace.assertMatched("media_123"));
        exactTrace.close();

        FakeCdp sub = new FakeCdp();
        PublishTrace subTrace = start(sub);
        sub.publicationRequest("sub", "https://www.instagram.com/api", "POST", TEXT);
        sub.response("sub", 200);
        assertTrue(sub.awaitBody());
        waitForId(subTrace, "media_123");
        assertEquals(Set.of("media_123"), subTrace.ids());
        subTrace.close();

        FakeCdp malicious = new FakeCdp();
        PublishTrace maliciousTrace = start(malicious);
        malicious.publicationRequest("prefix", "https://instagram.com.evil.test/api", "POST", TEXT);
        malicious.response("prefix", 200);
        malicious.publicationRequest("sufixo", "https://evilinstagram.com/api", "POST", TEXT);
        malicious.response("sufixo", 200);
        assertNadaCapturadoComEventosCompletos(malicious, maliciousTrace);
        maliciousTrace.close();
    }

    @Test
    void capturaTextoAprovadoEmPlainJsonEUrlencodedComUnicode() throws Exception {
        FakeCdp plain = new FakeCdp();
        PublishTrace plainTrace = start(plain);
        plain.publicationRequest("plain", "https://www.instagram.com/api", "POST", TEXT);
        plain.response("plain", 200);
        assertTrue(plain.awaitBody());
        waitForId(plainTrace, "media_123");
        assertEquals(Set.of("media_123"), plainTrace.ids());
        plainTrace.close();

        FakeCdp json = new FakeCdp();
        PublishTrace jsonTrace = start(json);
        json.publicationRequest("json", "https://www.instagram.com/api", "POST",
                "{\"caption\":" + Json.write(TEXT) + "}");
        json.response("json", 200);
        assertTrue(json.awaitBody());
        waitForId(jsonTrace, "media_123");
        assertEquals(Set.of("media_123"), jsonTrace.ids());
        jsonTrace.close();

        FakeCdp form = new FakeCdp();
        PublishTrace formTrace = start(form);
        form.publicationRequest("form", "https://www.instagram.com/api", "POST", "text=texto%20aprovado");
        form.response("form", 201);
        assertTrue(form.awaitBody());
        waitForId(formTrace, "media_123");
        assertEquals(Set.of("media_123"), formTrace.ids());
        formTrace.close();

        String quoted = "diz \"oi\" aprovado";
        FakeCdp escaped = new FakeCdp();
        PublishTrace escapedTrace = PublishTrace.start(escaped, "instagram", quoted);
        String jsonBody = "{\"caption\":" + Json.write(quoted) + "}";
        assertFalse(jsonBody.contains(quoted), "pre-condicao: corpo JSON usa forma escapada");
        escaped.publicationRequest("esc", "https://www.instagram.com/api", "POST", jsonBody);
        escaped.response("esc", 200);
        assertTrue(escaped.awaitBody());
        waitForId(escapedTrace, "media_123");
        assertEquals(Set.of("media_123"), escapedTrace.ids());
        escapedTrace.close();

        String unicode = "texto aprovado \u00e9 \u2603";
        FakeCdp uni = new FakeCdp();
        PublishTrace uniTrace = PublishTrace.start(uni, "instagram", unicode);
        uni.publicationRequest("uni", "https://www.instagram.com/api", "POST",
                "{\"caption\":" + Json.write(unicode) + "}");
        uni.response("uni", 200);
        assertTrue(uni.awaitBody());
        waitForId(uniTrace, "media_123");
        assertEquals(Set.of("media_123"), uniTrace.ids());
        uniTrace.close();
    }

    @Test
    void capturaPostBlueskyEFiltraUrisQueNaoSaoPosts() throws Exception {
        FakeCdp create = new FakeCdp();
        create.response = Json.obj().put("body", "{\"uri\":\"at://did:plc:abc123/app.bsky.feed.post/3kabc234xyz22\",\"cid\":\"x\"}")
                .put("base64Encoded", false);
        PublishTrace createTrace = PublishTrace.start(create, "bluesky", TEXT);
        create.publicationRequest("bsky-create", "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.createRecord", "POST",
                "{\"collection\":\"app.bsky.feed.post\",\"record\":{\"text\":\"" + TEXT + "\"}}");
        create.response("bsky-create", 200);
        assertTrue(create.awaitBody());
        waitForId(createTrace, "3kabc234xyz22");
        assertDoesNotThrow(() -> createTrace.assertMatched("3kabc234xyz22"));
        createTrace.close();

        FakeCdp writes = new FakeCdp();
        writes.response = Json.obj().put("body", "{\"results\":[{\"$type\":\"com.atproto.repo.applyWrites#createResult\",\"uri\":\"at://did:plc:abc/app.bsky.feed.post/3kpost222aaaa\"}]}")
                .put("base64Encoded", false);
        PublishTrace writesTrace = PublishTrace.start(writes, "bluesky", TEXT);
        writes.publicationRequest("bsky-writes", "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.applyWrites", "POST",
                "{\"writes\":[{\"$type\":\"com.atproto.repo.applyWrites#create\",\"collection\":\"app.bsky.feed.post\",\"value\":{\"text\":\"" + TEXT + "\"}}]}");
        writes.response("bsky-writes", 200);
        assertTrue(writes.awaitBody());
        waitForId(writesTrace, "3kpost222aaaa");
        assertDoesNotThrow(() -> writesTrace.assertMatched("3kpost222aaaa"));
        writesTrace.close();
    }

    @Test
    void correlacionaApplyWritesPorIndiceEExigeCreateCorrespondente() throws Exception {
        String endpoint = "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.applyWrites";
        String create = "{\"$type\":\"com.atproto.repo.applyWrites#create\",\"collection\":\"app.bsky.feed.post\",\"value\":{\"text\":\"" + TEXT + "\"}}";
        String update = "{\"$type\":\"com.atproto.repo.applyWrites#update\",\"collection\":\"app.bsky.feed.post\",\"value\":{\"text\":\"" + TEXT + "\"}}";
        String createResult = "{\"$type\":\"com.atproto.repo.applyWrites#createResult\",\"uri\":\"at://did:plc:abc/app.bsky.feed.post/3kcreate222aaa\"}";
        String updateResult = "{\"$type\":\"com.atproto.repo.applyWrites#updateResult\",\"uri\":\"at://did:plc:abc/app.bsky.feed.post/3kold222aaa\"}";

        FakeCdp updateCdp = new FakeCdp();
        updateCdp.response = Json.obj().put("body", "{\"results\":[" + updateResult + "]}").put("base64Encoded", false);
        PublishTrace updateTrace = PublishTrace.start(updateCdp, "bluesky", TEXT);
        updateCdp.publicationRequest("update", endpoint, "POST", "{\"writes\":[" + update + "]}");
        updateCdp.response("update", 200);
        assertTrue(updateCdp.awaitBody());
        Thread.sleep(50);
        assertTrue(updateTrace.ids().isEmpty());
        updateTrace.close();

        FakeCdp twoCdp = new FakeCdp();
        String otherText = "texto que nao foi aprovado";
        String createOther = create.replace(TEXT, otherText);
        twoCdp.response = Json.obj().put("body", "{\"results\":[{\"$type\":\"com.atproto.repo.applyWrites#createResult\",\"uri\":\"at://did:plc:abc/app.bsky.feed.post/3kother222aaa\"}," + createResult + "]}")
                .put("base64Encoded", false);
        PublishTrace twoTrace = PublishTrace.start(twoCdp, "bluesky", TEXT);
        twoCdp.publicationRequest("two", endpoint, "POST", "{\"writes\":[" + createOther + "," + create + "]}");
        twoCdp.response("two", 200);
        assertTrue(twoCdp.awaitBody());
        waitForId(twoTrace, "3kcreate222aaa");
        assertEquals(Set.of("3kcreate222aaa"), twoTrace.ids());
        twoTrace.close();

        FakeCdp mismatchCdp = new FakeCdp();
        mismatchCdp.response = Json.obj().put("body", "{\"results\":[]}").put("base64Encoded", false);
        PublishTrace mismatchTrace = PublishTrace.start(mismatchCdp, "bluesky", TEXT);
        mismatchCdp.publicationRequest("mismatch", endpoint, "POST", "{\"writes\":[" + create + "]}");
        mismatchCdp.response("mismatch", 200);
        assertTrue(mismatchCdp.awaitBody());
        Thread.sleep(50);
        assertTrue(mismatchTrace.ids().isEmpty());
        mismatchTrace.close();
    }

    @Test
    void applyWritesRespeitaLimiteDeDuzentosItens() throws Exception {
        String endpoint = "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.applyWrites";
        String otherText = "texto diferente";
        List<String> writes = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            writes.add("{\"$type\":\"com.atproto.repo.applyWrites#create\",\"collection\":\"app.bsky.feed.post\",\"value\":{\"text\":"
                    + Json.write(i >= 199 ? TEXT : otherText) + "}}");
            results.add("{\"$type\":\"com.atproto.repo.applyWrites#createResult\",\"uri\":\"at://did:plc:abc/app.bsky.feed.post/rkey"
                    + i + "\"}");
        }

        FakeCdp oversized = new FakeCdp();
        oversized.response = Json.obj().put("body", "{\"results\":[" + String.join(",", results) + "]}")
                .put("base64Encoded", false);
        PublishTrace oversizedTrace = PublishTrace.start(oversized, "bluesky", TEXT);
        oversized.publicationRequest("bsky-201", endpoint, "POST", "{\"writes\":[" + String.join(",", writes) + "]}");
        oversized.response("bsky-201", 200);
        assertTrue(oversized.awaitBody());
        Thread.sleep(50);
        assertTrue(oversizedTrace.ids().isEmpty());
        assertThrows(IllegalStateException.class, () -> oversizedTrace.assertMatched("rkey200"));
        oversizedTrace.close();

        FakeCdp atLimit = new FakeCdp();
        atLimit.response = Json.obj().put("body", "{\"results\":[" + String.join(",", results.subList(0, 200)) + "]}")
                .put("base64Encoded", false);
        PublishTrace atLimitTrace = PublishTrace.start(atLimit, "bluesky", TEXT);
        atLimit.publicationRequest("bsky-200", endpoint, "POST", "{\"writes\":[" + String.join(",", writes.subList(0, 200)) + "]}");
        atLimit.response("bsky-200", 200);
        assertTrue(atLimit.awaitBody());
        waitForId(atLimitTrace, "rkey199");
        assertDoesNotThrow(() -> atLimitTrace.assertMatched("rkey199"));
        atLimitTrace.close();
    }

    @Test
    void createRecordExigeCollectionDePost() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", "{\"uri\":\"at://did:plc:abc/app.bsky.feed.post/3krecord222aaa\"}").put("base64Encoded", false);
        PublishTrace trace = PublishTrace.start(cdp, "bluesky", TEXT);
        cdp.publicationRequest("like", "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.createRecord", "POST",
                "{\"collection\":\"app.bsky.feed.like\",\"record\":{\"text\":\"" + TEXT + "\"}}");
        cdp.response("like", 200);
        assertTrue(cdp.awaitBody());
        Thread.sleep(50);
        assertTrue(trace.ids().isEmpty());
        trace.close();
    }

    @Test
    void rejeitaHostEEndpointBlueskyForaDaLista() throws Exception {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = PublishTrace.start(cdp, "bluesky", TEXT);
        cdp.publicationRequest("bsky-evil", "https://evil-bsky.network/xrpc/com.atproto.repo.createRecord", "POST", TEXT);
        cdp.response("bsky-evil", 200);
        cdp.publicationRequest("bsky-get", "https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.getRecord", "POST", TEXT);
        cdp.response("bsky-get", 200);
        assertNadaCapturadoComEventosCompletos(cdp, trace);
        trace.close();
    }

    @Test
    void capturaSomenteIdDoStatusMastodonERejeitaOutrosEndpoints() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", "{\"id\":\"113000000000000001\",\"account\":{\"id\":\"999\"},\"media_attachments\":[{\"id\":\"777\"}]}")
                .put("base64Encoded", false);
        PublishTrace trace = PublishTrace.start(cdp, "mastodon", TEXT);
        cdp.publicationRequest("mastodon", "https://mastodon.social/api/v1/statuses", "POST", TEXT);
        cdp.response("mastodon", 200);
        assertTrue(cdp.awaitBody());
        waitForId(trace, "113000000000000001");
        assertDoesNotThrow(() -> trace.assertMatched("113000000000000001"));
        assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("999"));
        assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("777"));
        trace.close();

        FakeCdp favourite = new FakeCdp();
        PublishTrace favouriteTrace = PublishTrace.start(favourite, "mastodon", TEXT);
        favourite.publicationRequest("favourite", "https://mastodon.social/api/v1/statuses/1/favourite", "POST", TEXT);
        favourite.response("favourite", 200);
        assertNadaCapturadoComEventosCompletos(favourite, favouriteTrace);
        favouriteTrace.close();
    }

    @Test
    void capturaTweetXIncluindoVarianteDeVisibilidadeERejeitaOutrosDadosEEndpoints() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", "{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{\"rest_id\":\"1850000000000000001\",\"core\":{\"user_results\":{\"result\":{\"rest_id\":\"42\"}}}}}}}}")
                .put("base64Encoded", false);
        PublishTrace trace = PublishTrace.start(cdp, "x", TEXT);
        cdp.publicationRequest("x-create", "https://x.com/i/api/graphql/AbC_1-x/CreateTweet", "POST", TEXT);
        cdp.response("x-create", 200);
        assertTrue(cdp.awaitBody());
        waitForId(trace, "1850000000000000001");
        assertDoesNotThrow(() -> trace.assertMatched("1850000000000000001"));
        assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("42"));
        trace.close();

        FakeCdp visibility = new FakeCdp();
        visibility.response = Json.obj().put("body", "{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{\"__typename\":\"TweetWithVisibilityResults\",\"tweet\":{\"rest_id\":\"1850000000000000002\"}}}}}}")
                .put("base64Encoded", false);
        PublishTrace visibilityTrace = PublishTrace.start(visibility, "x", TEXT);
        visibility.publicationRequest("x-visibility", "https://x.com/i/api/graphql/AbC_1-x/CreateTweet", "POST", TEXT);
        visibility.response("x-visibility", 200);
        assertTrue(visibility.awaitBody());
        waitForId(visibilityTrace, "1850000000000000002");
        assertDoesNotThrow(() -> visibilityTrace.assertMatched("1850000000000000002"));
        visibilityTrace.close();

        FakeCdp delete = new FakeCdp();
        PublishTrace deleteTrace = PublishTrace.start(delete, "x", TEXT);
        delete.publicationRequest("x-delete", "https://x.com/i/api/graphql/AbC/DeleteTweet", "POST", TEXT);
        delete.response("x-delete", 200);
        assertNadaCapturadoComEventosCompletos(delete, deleteTrace);
        deleteTrace.close();
    }

    @Test
    void capturaTweetXNosHostsTwitterERejeitaHostComPrefixo() throws Exception {
        String response = "{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{\"rest_id\":\"1850000000000000003\"}}}}}";
        for (String host : List.of("twitter.com", "mobile.twitter.com")) {
            FakeCdp cdp = new FakeCdp();
            cdp.response = Json.obj().put("body", response).put("base64Encoded", false);
            PublishTrace trace = PublishTrace.start(cdp, "x", TEXT);
            cdp.publicationRequest(host, "https://" + host + "/i/api/graphql/abc/CreateTweet", "POST", TEXT);
            cdp.response(host, 200);
            assertTrue(cdp.awaitBody());
            waitForId(trace, "1850000000000000003");
            assertDoesNotThrow(() -> trace.assertMatched("1850000000000000003"));
            trace.close();
        }

        FakeCdp evil = new FakeCdp();
        PublishTrace evilTrace = PublishTrace.start(evil, "x", TEXT);
        evil.publicationRequest("evil", "https://eviltwitter.com/i/api/graphql/abc/CreateTweet", "POST", TEXT);
        evil.response("evil", 200);
        assertNadaCapturadoComEventosCompletos(evil, evilTrace);
        evilTrace.close();
    }

    @Test
    void capturaIdDeCriacaoPinterest() throws Exception {
        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", "{\"resource_response\":{\"data\":{\"id\":\"1234567890123\"}}}")
                .put("base64Encoded", false);
        PublishTrace trace = PublishTrace.start(cdp, "pinterest", TEXT);
        String body = "source_url=%2Fpin-creation-tool%2F&data=" +
                java.net.URLEncoder.encode("{\"options\":{\"title\":\"" + TEXT + "\"}}", java.nio.charset.StandardCharsets.UTF_8);
        cdp.publicationRequest("pin-create", "https://br.pinterest.com/resource/PinResource/create/", "POST", body);
        cdp.response("pin-create", 200);
        assertTrue(cdp.awaitBody());
        waitForId(trace, "1234567890123");
        assertDoesNotThrow(() -> trace.assertMatched("1234567890123"));
        trace.close();
    }

    @Test
    void rejeitaRedeSemSuporte() {
        assertThrows(IllegalArgumentException.class, () -> PublishTrace.start(new FakeCdp(), "reddit", TEXT));
    }
}
