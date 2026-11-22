package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes que fixam o comportamento observavel exato da PublishTrace para matar mutantes de PIT:
 * limites exatos (64 KiB de corpo, 32 requisicoes, 64 IDs, 200 writes, 4096 nos, profundidade 20,
 * TTL exato, 100 caracteres de caminho), contadores do diagnostico, delegacao do adaptador {@link Target}
 * e limpeza no close.
 *
 * <p>Nao abre Chrome nem toca a rede: usa um CDP falso, como PublishTraceTest. Nenhum helper aqui
 * chama o home real, o relogio de parede (quando relevante usa relogio injetado) ou dorme mais de 100 ms.
 */
class PublishTraceMutantesTest {

    private static final String TEXTO = "texto aprovado";
    private static final int MAX_CORPO = 64 * 1024;
    private static final int MAX_RESPOSTA = 256 * 1024;
    private static final int MAX_REQUISICOES = 32;
    private static final int MAX_IDS = 64;
    private static final long TTL = 1_200_000L;

    private static final String URL_IG = "https://www.instagram.com/api/v1/media/1/configure/";
    private static final String URL_REDDIT = "https://www.reddit.com/svc/shreddit/t3_abc123/create-comment";
    private static final String URL_BSKY_CREATE = "https://bsky.social/xrpc/com.atproto.repo.createRecord";
    private static final String URL_BSKY_WRITES = "https://bsky.social/xrpc/com.atproto.repo.applyWrites";
    private static final String URL_MASTODON = "https://mastodon.social/api/v1/statuses";

    @TempDir
    Path tmp;

    // ---------- fixture CDP ----------

    private static final class FakeCdp implements PublishTrace.Cdp {
        final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
        final AtomicInteger corposPedidos = new AtomicInteger();
        volatile JsonNode response = Json.obj().put("body", "{\"media_id\":\"media_123\"}")
                .put("base64Encoded", false);
        volatile JsonNode requestPostData = Json.obj().put("postData", "");
        /** addCdpListener falha no evento Network.responseReceived. */
        volatile boolean failAddResponse;
        volatile boolean failRemove;
        volatile boolean failPostData;
        /** Se nao nulo, getResponseBody bloqueia ate o latch abrir. */
        volatile CountDownLatch travaCorpo;
        volatile Thread threadDoCorpo;

        @Override public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> listener) {
            if (failAddResponse && "Network.responseReceived".equals(event)) {
                return CompletableFuture.failedFuture(new IllegalStateException("fake failure"));
            }
            listeners.put(event, listener);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> listener) {
            if (failRemove) return CompletableFuture.failedFuture(new IllegalStateException("fake failure"));
            listeners.remove(event, listener);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<JsonNode> executeCdpCmd(String comando, Map<String, Object> params, Float timeout) {
            if ("Network.getResponseBody".equals(comando)) {
                corposPedidos.incrementAndGet();
                threadDoCorpo = Thread.currentThread();
                CountDownLatch trava = travaCorpo;
                if (trava != null) {
                    try {
                        trava.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return CompletableFuture.completedFuture(response);
            }
            if ("Network.getRequestPostData".equals(comando)) {
                if (failPostData) {
                    return CompletableFuture.failedFuture(new IllegalStateException("fake failure"));
                }
                return CompletableFuture.completedFuture(requestPostData);
            }
            return CompletableFuture.completedFuture(Json.obj());
        }

        void emitir(String evento, String json) {
            Consumer<JsonNode> listener = listeners.get(evento);
            assertNotNull(listener, "listener ausente: " + evento);
            listener.accept(Json.parse(json));
        }

        void emitirNulo(String evento) {
            Consumer<JsonNode> listener = listeners.get(evento);
            assertNotNull(listener, "listener ausente: " + evento);
            listener.accept(null);
        }

        void pedido(String requestId, String url, String method, String body) {
            ObjectNode params = Json.obj();
            params.put("requestId", requestId);
            ObjectNode request = params.putObject("request");
            request.put("url", url);
            request.put("method", method);
            request.put("postData", body);
            emitir("Network.requestWillBeSent", params.toString());
        }

        void pedidoSemCorpo(String requestId, String url) {
            ObjectNode params = Json.obj();
            params.put("requestId", requestId);
            ObjectNode request = params.putObject("request");
            request.put("url", url);
            request.put("method", "POST");
            request.put("hasPostData", true);
            emitir("Network.requestWillBeSent", params.toString());
        }

        void resposta(String requestId, int status) {
            emitir("Network.responseReceived",
                    "{\"requestId\":\"" + requestId + "\",\"response\":{\"status\":" + status + "}}");
            emitir("Network.loadingFinished", "{\"requestId\":\"" + requestId + "\"}");
        }
    }

    // ---------- helpers ----------

    private static PublishTrace iniciar(FakeCdp cdp, String rede) {
        return PublishTrace.start(cdp, rede, TEXTO);
    }

    private static JsonNode corpo(JsonNode json) {
        return Json.obj().put("body", json.toString()).put("base64Encoded", false);
    }

    private static JsonNode corpoTexto(String body) {
        return Json.obj().put("body", body).put("base64Encoded", false);
    }

    private static Object campo(Object alvo, String nome) {
        Class<?> tipo = alvo.getClass();
        while (tipo != null) {
            try {
                Field field = tipo.getDeclaredField(nome);
                field.setAccessible(true);
                return field.get(alvo);
            } catch (NoSuchFieldException ausente) {
                tipo = tipo.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("campo ausente: " + nome);
    }

    private static int tamanhoCampo(Object alvo, String nome) {
        Object valor = campo(alvo, nome);
        if (valor instanceof Collection<?> colecao) return colecao.size();
        if (valor instanceof Map<?, ?> mapa) return mapa.size();
        throw new IllegalStateException("campo nao e colecao: " + nome);
    }

    private static void esperarAte(BooleanSupplier condicao, String descricao) {
        long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < prazo) {
            if (condicao.getAsBoolean()) return;
            Thread.yield();
        }
        fail("timeout esperando: " + descricao);
    }

    private static void esperarId(PublishTrace trace, String id) {
        esperarAte(() -> trace.ids().contains(id), "captura do id " + id);
    }

    private static void esperarDiag(PublishTrace trace, String fragmento) {
        esperarAte(() -> trace.diagnostico().contains(fragmento), "diagnostico conter " + fragmento);
    }

    private static void esperarCorpoProcessado(PublishTrace trace) {
        esperarAte(() -> tamanhoCampo(trace, "requests") == 0, "processamento assincrono do corpo");
    }

    private static String diagnosticoVazio() {
        return "posts=0 caminhoOk=0 semCorpo=0 comTexto=0 status=[] corpos=0 ids=[]"
                + " invalido=false erro=- caminhosComTexto=[]";
    }

    // ---------- adaptador Target (L48/L102) ----------

    @Test
    void adaptadorTargetEhTocadoEStartNaoDevolveNull() {
        // Target nulo e invalido, mas prova que o adaptador encaminha a chamada em vez de devolver null:
        // sem encaminhar, start devolveria null sem excecao ou falharia com ISE "Operacao CDP sem future".
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PublishTrace.start((Target) null, "instagram", TEXTO));
        assertEquals("Nao foi possivel iniciar captura passiva", e.getMessage());
        assertInstanceOf(NullPointerException.class, e.getCause(),
                "o adaptador deve chamar o Target (NPE do target nulo), nao devolver null");
    }

    // ---------- thread do executor de corpos (L96) ----------

    @Test
    void threadDoCorpoEhDaemonENomeada() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("c", URL_IG, "POST", TEXTO);
            cdp.resposta("c", 201);
            esperarId(trace, "media_123");
            Thread thread = cdp.threadDoCorpo;
            assertNotNull(thread, "o corpo deve ser buscado na thread do executor");
            assertEquals("publish-trace-body", thread.getName());
            assertTrue(thread.isDaemon(), "a thread de corpos nao pode impedir o encerramento da JVM");
        } finally {
            trace.close();
        }
    }

    // ---------- relogio real do start publico (L106) ----------

    @Test
    void startComCdpUsaRelogioRealParaStartedAt() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = PublishTrace.start(cdp, "instagram", TEXTO);
        try {
            long startedAt = ((Number) campo(trace, "startedAt")).longValue();
            long agora = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertTrue(startedAt > 0, "startedAt nao pode ser zero (relogio mutado)");
            assertTrue(Math.abs(agora - startedAt) < 60_000,
                    "startedAt deve vir do nanoTime corrente: " + startedAt + " vs " + agora);
        } finally {
            trace.close();
        }
    }

    // ---------- falha de instalacao (L124) ----------

    @Test
    void falhaNaInstalacaoAnexaFalhaDeLimpezaComoSuprimida() {
        FakeCdp cdp = new FakeCdp();
        cdp.failAddResponse = true;
        cdp.failRemove = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PublishTrace.start(cdp, "instagram", TEXTO));
        assertEquals("Nao foi possivel iniciar captura passiva", e.getMessage());
        Throwable[] suprimidas = e.getCause().getSuppressed();
        assertEquals(1, suprimidas.length, "a falha de limpeza nao pode se perder");
        assertInstanceOf(IllegalStateException.class, suprimidas[0]);
        assertEquals("Falha ao limpar listener CDP", suprimidas[0].getMessage());
    }

    // ---------- excecao em evento (L153) ----------

    @Test
    void eventoQueLancaExcecaoInvalidaTrace() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.emitirNulo("Network.requestWillBeSent");
            String diag = trace.diagnostico();
            assertTrue(diag.contains("invalido=true"), diag);
            assertTrue(diag.contains("erro=NullPointerException"), diag);
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("x"));
        } finally {
            trace.close();
        }
    }

    // ---------- diagnostico (L166) ----------

    @Test
    void diagnosticoUsaHifenParaErroAusenteEClasseDoErroQuandoPresente() {
        FakeCdp limpo = new FakeCdp();
        PublishTrace trace = iniciar(limpo, "instagram");
        try {
            assertEquals(diagnosticoVazio(), trace.diagnostico());
        } finally {
            trace.close();
        }

        FakeCdp quebrado = new FakeCdp();
        quebrado.response = corpoTexto("not-json");
        PublishTrace invalido = iniciar(quebrado, "instagram");
        try {
            quebrado.pedido("bad", URL_IG, "POST", TEXTO);
            quebrado.resposta("bad", 200);
            esperarDiag(invalido, "invalido=true");
            String diag = invalido.diagnostico();
            assertFalse(diag.contains("erro=-"), diag);
            assertTrue(diag.matches("(?s).*invalido=true erro=[A-Za-z][A-Za-z0-9]* caminhosComTexto=.*"), diag);
        } finally {
            invalido.close();
        }
    }

    // ---------- contadores de onRequest (L182/L190/L193) ----------

    @Test
    void contadoresDeRequisicaoAparecemExatosNoDiagnostico() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("a", URL_IG, "POST", TEXTO);
            assertEquals("posts=1 caminhoOk=1 semCorpo=0 comTexto=1 status=[] corpos=0 ids=[]"
                    + " invalido=false erro=- caminhosComTexto=[/api/v1/media/1/configure/]", trace.diagnostico());

            cdp.pedidoSemCorpo("b", "https://www.instagram.com/api/v1/media/2/configure/");
            assertEquals("posts=2 caminhoOk=2 semCorpo=1 comTexto=1 status=[] corpos=0 ids=[]"
                    + " invalido=false erro=- caminhosComTexto=[/api/v1/media/1/configure/]", trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- contadores do corpo pendente (L295/L310) ----------

    @Test
    void corpoPendenteSomaTextoECorposExatamente() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData", "comment=texto%20aprovado");
        cdp.response = corpoTexto("<html><body>ok</body></html>");
        PublishTrace trace = iniciar(cdp, "reddit");
        try {
            cdp.pedidoSemCorpo("r", URL_REDDIT);
            cdp.resposta("r", 200);
            esperarAte(() -> trace.diagnostico().contains("corpos=1"), "corpo processado");
            String diag = trace.diagnostico();
            assertTrue(diag.contains("comTexto=1"), diag);
            assertTrue(diag.contains("corpos=1"), diag);
            assertTrue(diag.contains("caminhosComTexto=[/svc/shreddit/t3_abc123/create-comment]"), diag);
        } finally {
            trace.close();
        }
    }

    // ---------- limite de requisicoes sem corpo (L194) ----------

    @Test
    void limiteDeRequisicoesSemCorpoEhExatamente32() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            for (int i = 0; i < MAX_REQUISICOES; i++) {
                cdp.pedidoSemCorpo("r" + i, "https://www.instagram.com/api/v1/media/" + i + "/configure/");
            }
            assertTrue(trace.diagnostico().contains("semCorpo=32"), trace.diagnostico());
            assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("x"),
                    "com 32 requisicoes o trace ainda e valido");

            cdp.pedidoSemCorpo("r32", "https://www.instagram.com/api/v1/media/32/configure/");
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("x"),
                    "a 33a requisicao deve invalidar o trace");
        } finally {
            trace.close();
        }
    }

    // ---------- caminho do estado por rede (L200) ----------

    @Test
    void caminhoEhGravadoSomenteNasRedesQuePrecisamDele() {
        // reddit
        FakeCdp reddit = new FakeCdp();
        reddit.requestPostData = Json.obj().put("postData", "comment=texto%20aprovado");
        reddit.response = corpoTexto("<html></html>");
        PublishTrace redditTrace = iniciar(reddit, "reddit");
        try {
            reddit.pedidoSemCorpo("r", URL_REDDIT);
            reddit.resposta("r", 200);
            esperarDiag(redditTrace, "caminhosComTexto=[/svc/shreddit/t3_abc123/create-comment]");
        } finally {
            redditTrace.close();
        }

        // instagram
        FakeCdp ig = new FakeCdp();
        ig.requestPostData = Json.obj().put("postData", "comment_text=texto%20aprovado");
        ig.response = corpo(Json.obj());
        PublishTrace igTrace = iniciar(ig, "ig");
        try {
            ig.pedidoSemCorpo("i", "https://www.instagram.com/api/v1/web/comments/3123456789/add/");
            ig.resposta("i", 200);
            esperarDiag(igTrace, "caminhosComTexto=[/api/v1/web/comments/3123456789/add/]");
        } finally {
            igTrace.close();
        }

        // bluesky
        FakeCdp bsky = new FakeCdp();
        bsky.requestPostData = Json.obj().put("postData",
                "{\"collection\":\"app.bsky.feed.post\",\"record\":{\"text\":\"" + TEXTO + "\"}}");
        bsky.response = corpo(Json.obj());
        PublishTrace bskyTrace = iniciar(bsky, "bsky");
        try {
            bsky.pedidoSemCorpo("b", URL_BSKY_CREATE);
            bsky.resposta("b", 200);
            esperarDiag(bskyTrace, "caminhosComTexto=[/xrpc/com.atproto.repo.createRecord]");
        } finally {
            bskyTrace.close();
        }

        // mastodon: nao precisa do caminho
        FakeCdp mastodon = new FakeCdp();
        mastodon.requestPostData = Json.obj().put("postData", "status=texto%20aprovado");
        mastodon.response = corpo(Json.obj());
        PublishTrace mastodonTrace = iniciar(mastodon, "mastodon");
        try {
            mastodon.pedidoSemCorpo("m", URL_MASTODON);
            mastodon.resposta("m", 200);
            esperarDiag(mastodonTrace, "comTexto=1");
            String diag = mastodonTrace.diagnostico();
            assertTrue(diag.contains("caminhosComTexto=[]"), diag);
        } finally {
            mastodonTrace.close();
        }
    }

    // ---------- limite do corpo do POST (L205) ----------

    @Test
    void corpoDePostComExatamente64kEhAceito() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            String noLimite = TEXTO + "a".repeat(MAX_CORPO - TEXTO.length());
            assertEquals(MAX_CORPO, noLimite.getBytes(StandardCharsets.UTF_8).length);
            cdp.pedido("limite", URL_IG, "POST", noLimite);
            cdp.resposta("limite", 200);
            esperarId(trace, "media_123");
            assertEquals(Set.of("media_123"), trace.ids());
        } finally {
            trace.close();
        }
    }

    // ---------- limite do corpo da resposta (L314/L318/L320) ----------

    private static String jsonComTamanhoExato(int bytes) {
        String base = "{\"media_id\":\"media_123\"}";
        return base + " ".repeat(bytes - base.length());
    }

    @Test
    void respostaBase64NoLimiteExatoDe256kEhAceita() {
        byte[] bytes = jsonComTamanhoExato(MAX_RESPOSTA).getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        assertEquals(349_528, base64.length(), "pre-condicao: base64 de 256 KiB tem 349528 caracteres");

        FakeCdp cdp = new FakeCdp();
        cdp.response = Json.obj().put("body", base64).put("base64Encoded", true);
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("b64", URL_IG, "POST", TEXTO);
            cdp.resposta("b64", 200);
            esperarId(trace, "media_123");
            assertEquals(Set.of("media_123"), trace.ids());
        } finally {
            trace.close();
        }
    }

    @Test
    void respostaTextoNoLimiteExatoDe256kEhAceita() {
        String corpo = jsonComTamanhoExato(MAX_RESPOSTA);
        assertEquals(MAX_RESPOSTA, corpo.getBytes(StandardCharsets.UTF_8).length);

        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(corpo);
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("texto256k", URL_IG, "POST", TEXTO);
            cdp.resposta("texto256k", 200);
            esperarId(trace, "media_123");
            assertEquals(Set.of("media_123"), trace.ids());
        } finally {
            trace.close();
        }
    }

    // ---------- limite de caminhos com texto (L220) ----------

    @Test
    void caminhosComTextoLimitadosAExatamente5() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            for (int i = 0; i < 6; i++) {
                cdp.pedido("p" + i, "https://www.instagram.com/api/v1/p" + i + "/configure/", "POST", TEXTO);
            }
            assertEquals(5, tamanhoCampo(trace, "pathsWithText"));
        } finally {
            trace.close();
        }
    }

    // ---------- status no diagnostico (L237) ----------

    @Test
    void statusDaRespostaApareceNoDiagnostico() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("s", URL_IG, "POST", TEXTO);
            cdp.resposta("s", 201);
            esperarId(trace, "media_123");
            assertTrue(trace.diagnostico().contains("status=[201]"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- 2xx mantem, 300 descarta (L239/L247) ----------

    @Test
    void status200MantemRequisicaoEPermiteCaptura() {
        FakeCdp cdp = new FakeCdp();
        CountDownLatch trava = new CountDownLatch(1);
        cdp.travaCorpo = trava;
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("ok", URL_IG, "POST", TEXTO);
            cdp.resposta("ok", 200);
            assertEquals(1, tamanhoCampo(trace, "requests"), "status 200 nao pode remover a requisicao");
            trava.countDown();
            esperarId(trace, "media_123");
            assertEquals(Set.of("media_123"), trace.ids());
        } finally {
            trava.countDown();
            trace.close();
        }
    }

    @Test
    void status300RemoveRequisicaoSemBuscarCorpo() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("t300", URL_IG, "POST", TEXTO);
            cdp.resposta("t300", 300);
            assertEquals(0, tamanhoCampo(trace, "requests"), "status 300 deve remover a requisicao");
            assertEquals(0, cdp.corposPedidos.get(), "status 300 nao pode pedir corpo");
            assertTrue(trace.diagnostico().contains("status=[300]"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- rejeicao da fila de corpos (L253) ----------

    @Test
    void filaDeCorposRejeitaExcedenteEInvalidaTrace() {
        FakeCdp cdp = new FakeCdp();
        CountDownLatch trava = new CountDownLatch(1);
        cdp.travaCorpo = trava;
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            // 1 thread ativa + 16 na fila; o 18o corpo e rejeitado pelo AbortPolicy.
            for (int i = 0; i < 18; i++) {
                String id = "q" + i;
                cdp.pedido(id, "https://www.instagram.com/api/v1/media/" + i + "/configure/", "POST", TEXTO);
                cdp.resposta(id, 200);
            }
            String diag = trace.diagnostico();
            assertTrue(diag.contains("invalido=true"), diag);
            assertTrue(diag.contains("erro=RejectedExecutionException"), diag);
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("x"));
        } finally {
            trava.countDown();
            trace.close();
        }
    }

    // ---------- limite do postData pendente (L284) ----------

    @Test
    void postDataComExatamente64kEhAceito() {
        FakeCdp cdp = new FakeCdp();
        String noLimite = TEXTO + "c".repeat(MAX_CORPO - TEXTO.length());
        assertEquals(MAX_CORPO, noLimite.getBytes(StandardCharsets.UTF_8).length);
        cdp.requestPostData = Json.obj().put("postData", noLimite);
        cdp.response = corpoTexto("<shreddit-comment thingid=\"t1_abc123\">x</shreddit-comment>");
        PublishTrace trace = iniciar(cdp, "reddit");
        try {
            cdp.pedidoSemCorpo("r", URL_REDDIT);
            cdp.resposta("r", 200);
            esperarId(trace, "abc123");
            assertTrue(trace.diagnostico().contains("comTexto=1"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- corpo pendente sem caminho (L296, negacao de state.path != null) ----------

    @Test
    void corpoPendenteSemCaminhoNaoQuebraExtracao() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData", "status=texto%20aprovado");
        cdp.response = corpo(Json.parse("{\"id\":\"111\"}"));
        PublishTrace trace = iniciar(cdp, "mastodon");
        try {
            cdp.pedidoSemCorpo("m", URL_MASTODON);
            cdp.resposta("m", 200);
            esperarId(trace, "111");
            assertTrue(trace.diagnostico().contains("invalido=false"), trace.diagnostico());
            assertDoesNotThrow(() -> trace.assertMatched("111"));
        } finally {
            trace.close();
        }
    }

    // ---------- postData guardado no bsky (L300) ----------

    @Test
    void corpoPendenteDoBskyUsaPostDataCapturado() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData",
                "{\"collection\":\"app.bsky.feed.post\",\"record\":{\"text\":\"" + TEXTO + "\"}}");
        cdp.response = corpo(Json.parse(
                "{\"uri\":\"at://did:plc:abc123/app.bsky.feed.post/3kabc234xyz22\"}"));
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedidoSemCorpo("b", URL_BSKY_CREATE);
            cdp.resposta("b", 200);
            esperarId(trace, "3kabc234xyz22");
            assertEquals(Set.of("3kabc234xyz22"), trace.ids());
        } finally {
            trace.close();
        }
    }

    // ---------- limite de IDs do reddit (L333) ----------

    private static String htmlReddit(String prefixo, int quantidade) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < quantidade; i++) {
            html.append("<shreddit-comment thingid=\"t1_").append(prefixo).append(i).append("\">x</shreddit-comment>");
        }
        return html.toString();
    }

    @Test
    void limiteDeIdsDoRedditAceitaExatamente64() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "reddit");
        try {
            cdp.response = corpoTexto(htmlReddit("a", 10));
            cdp.pedido("r1", URL_REDDIT, "POST", "comment=texto%20aprovado");
            cdp.resposta("r1", 200);
            esperarId(trace, "a0");

            cdp.response = corpoTexto(htmlReddit("b", 54));
            cdp.pedido("r2", "https://www.reddit.com/svc/shreddit/t3_def456/create-comment", "POST",
                    "comment=texto%20aprovado");
            cdp.resposta("r2", 200);

            esperarAte(() -> trace.ids().size() == 64, "64 ids capturados");
            assertEquals(64, trace.ids().size());
            assertDoesNotThrow(() -> trace.assertMatched("a0"), "64 ids ainda mantem o trace valido");
        } finally {
            trace.close();
        }
    }

    @Test
    void limiteDeIdsDoRedditSomaCapturadosComOcorrencias() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "reddit");
        try {
            cdp.response = corpoTexto(htmlReddit("a", 10));
            cdp.pedido("r1", URL_REDDIT, "POST", "comment=texto%20aprovado");
            cdp.resposta("r1", 200);
            esperarId(trace, "a0");

            cdp.response = corpoTexto(htmlReddit("b", 55));
            cdp.pedido("r2", "https://www.reddit.com/svc/shreddit/t3_def456/create-comment", "POST",
                    "comment=texto%20aprovado");
            cdp.resposta("r2", 200);

            esperarDiag(trace, "invalido=true");
            assertEquals(10, trace.ids().size(), "10 + 55 passa de 64: nada do segundo corpo entra");
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("a0"));
        } finally {
            trace.close();
        }
    }

    // ---------- limite de IDs do JSON (L348) ----------

    @Test
    void limiteDeIdsDoJsonInvalidaAcimaDe64() {
        ObjectNode root = Json.obj();
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < MAX_IDS + 1; i++) {
            data.addObject().put("media_id", String.format("id%03d", i));
        }

        FakeCdp cdp = new FakeCdp();
        cdp.response = corpo(root);
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("muitos", URL_IG, "POST", TEXTO);
            cdp.resposta("muitos", 200);
            esperarDiag(trace, "invalido=true");
            assertEquals(64, trace.ids().size(), "64 ids entram e o 65o invalida");
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("id000"));
        } finally {
            trace.close();
        }
    }

    // ---------- numericId (L394) ----------

    @Test
    void numericIdAceitaTextoENumeroERejeitaOutrosTipos() {
        FakeCdp texto = new FakeCdp();
        texto.response = corpo(Json.parse("{\"id\":\"113000000000000001\"}"));
        PublishTrace textoTrace = iniciar(texto, "mastodon");
        try {
            texto.pedido("t", URL_MASTODON, "POST", TEXTO);
            texto.resposta("t", 200);
            esperarId(textoTrace, "113000000000000001");
        } finally {
            textoTrace.close();
        }

        FakeCdp numero = new FakeCdp();
        numero.response = corpo(Json.parse("{\"id\":113000000000000002}"));
        PublishTrace numeroTrace = iniciar(numero, "mastodon");
        try {
            numero.pedido("n", URL_MASTODON, "POST", TEXTO);
            numero.resposta("n", 200);
            esperarId(numeroTrace, "113000000000000002");
        } finally {
            numeroTrace.close();
        }

        FakeCdp objeto = new FakeCdp();
        objeto.response = corpo(Json.parse("{\"id\":{\"x\":1}}"));
        PublishTrace objetoTrace = iniciar(objeto, "mastodon");
        try {
            objeto.pedido("o", URL_MASTODON, "POST", TEXTO);
            objeto.resposta("o", 200);
            esperarCorpoProcessado(objetoTrace);
            assertTrue(objetoTrace.ids().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> objetoTrace.assertMatched("x"));
        } finally {
            objetoTrace.close();
        }

        FakeCdp semId = new FakeCdp();
        semId.response = corpo(Json.parse("{}"));
        PublishTrace semIdTrace = iniciar(semId, "mastodon");
        try {
            semId.pedido("s", URL_MASTODON, "POST", TEXTO);
            semId.resposta("s", 200);
            esperarCorpoProcessado(semIdTrace);
            assertTrue(semIdTrace.ids().isEmpty());
            assertTrue(semIdTrace.diagnostico().contains("invalido=false"), semIdTrace.diagnostico());
        } finally {
            semIdTrace.close();
        }
    }

    // ---------- applyWrites acima de 200 (L415) ----------

    private record CorposApplyWrites(String requisicao, String resposta) {}

    private static CorposApplyWrites applyWrites(int itens) {
        List<String> writes = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < itens; i++) {
            if (i == 0) {
                writes.add("{\"$type\":\"com.atproto.repo.applyWrites#create\",\"collection\":\"app.bsky.feed.post\","
                        + "\"value\":{\"text\":\"" + TEXTO + "\"}}");
                results.add("{\"$type\":\"com.atproto.repo.applyWrites#createResult\","
                        + "\"uri\":\"at://did:plc:abc/app.bsky.feed.post/rkey0\"}");
            } else {
                writes.add("{\"$type\":\"com.atproto.repo.applyWrites#update\",\"collection\":\"app.bsky.feed.post\","
                        + "\"value\":{\"text\":\"texto diferente\"}}");
                results.add("{\"$type\":\"com.atproto.repo.applyWrites#updateResult\"}");
            }
        }
        return new CorposApplyWrites("{\"writes\":[" + String.join(",", writes) + "]}",
                "{\"results\":[" + String.join(",", results) + "]}");
    }

    @Test
    void applyWritesCom201ItensInvalidaTrace() {
        CorposApplyWrites corpos = applyWrites(201);
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(corpos.resposta());
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w201", URL_BSKY_WRITES, "POST", corpos.requisicao());
            cdp.resposta("w201", 200);
            esperarDiag(trace, "invalido=true");
            assertTrue(trace.ids().isEmpty());
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("rkey0"));
        } finally {
            trace.close();
        }
    }

    @Test
    void applyWritesCom200ItensCapturaIdDoCreate() {
        CorposApplyWrites corpos = applyWrites(200);
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(corpos.resposta());
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w200", URL_BSKY_WRITES, "POST", corpos.requisicao());
            cdp.resposta("w200", 200);
            esperarId(trace, "rkey0");
            assertEquals(Set.of("rkey0"), trace.ids());
        } finally {
            trace.close();
        }
    }

    // ---------- limites do collect (L441/L451/L453/L454) ----------

    private static JsonNode objetoAninhado(int niveis, String chave, String valor) {
        ObjectNode atual = Json.obj().put(chave, valor);
        for (int i = 0; i < niveis; i++) {
            ObjectNode externo = Json.obj();
            externo.set("n", atual);
            atual = externo;
        }
        return atual;
    }

    private static JsonNode arrayAninhado(int niveis, JsonNode interno) {
        JsonNode atual = interno;
        for (int i = 0; i < niveis; i++) {
            ArrayNode array = Json.M.createArrayNode();
            array.add(atual);
            atual = array;
        }
        return atual;
    }

    private static JsonNode nosSemIdValido(int elementos) {
        ObjectNode root = Json.obj();
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < elementos; i++) {
            data.addObject().put("media_id", "!!!");
        }
        return root;
    }

    @Test
    void profundidadeJsonAceita20ERejeita21() {
        FakeCdp aceito = new FakeCdp();
        aceito.response = corpo(objetoAninhado(19, "media_id", "prof_1"));
        PublishTrace ok = iniciar(aceito, "instagram");
        try {
            aceito.pedido("p20", URL_IG, "POST", TEXTO);
            aceito.resposta("p20", 200);
            esperarId(ok, "prof_1");
            assertEquals(Set.of("prof_1"), ok.ids());
        } finally {
            ok.close();
        }

        FakeCdp negado = new FakeCdp();
        negado.response = corpo(objetoAninhado(20, "media_id", "prof_2"));
        PublishTrace invalido = iniciar(negado, "instagram");
        try {
            negado.pedido("p21", URL_IG, "POST", TEXTO);
            negado.resposta("p21", 200);
            esperarDiag(invalido, "invalido=true");
            assertThrows(IllegalStateException.class, () -> invalido.assertMatched("prof_2"));
        } finally {
            invalido.close();
        }
    }

    @Test
    void quantidadeDeNosJsonAceita4096ERejeitaAcima() {
        // 2 + 2*2047 = 4096 nos visitados: exatamente no limite aceito.
        FakeCdp aceito = new FakeCdp();
        aceito.response = corpo(nosSemIdValido(2047));
        PublishTrace ok = iniciar(aceito, "instagram");
        try {
            aceito.pedido("n4096", URL_IG, "POST", TEXTO);
            aceito.resposta("n4096", 200);
            esperarCorpoProcessado(ok);
            String diag = ok.diagnostico();
            assertTrue(diag.contains("invalido=false"), diag);
            assertTrue(ok.ids().isEmpty());
        } finally {
            ok.close();
        }

        FakeCdp negado = new FakeCdp();
        negado.response = corpo(nosSemIdValido(2048));
        PublishTrace invalido = iniciar(negado, "instagram");
        try {
            negado.pedido("n4097", URL_IG, "POST", TEXTO);
            negado.resposta("n4097", 200);
            esperarDiag(invalido, "invalido=true");
            assertThrows(IllegalStateException.class, () -> invalido.assertMatched("x"));
        } finally {
            invalido.close();
        }
    }

    @Test
    void collectPercorreObjetosEArraysAninhados() {
        FakeCdp objeto = new FakeCdp();
        objeto.response = corpo(Json.parse("{\"a\":{\"b\":{\"media_id\":\"nested_1\"}}}"));
        PublishTrace objetoTrace = iniciar(objeto, "instagram");
        try {
            objeto.pedido("obj", URL_IG, "POST", TEXTO);
            objeto.resposta("obj", 200);
            esperarId(objetoTrace, "nested_1");
            assertEquals(Set.of("nested_1"), objetoTrace.ids());
        } finally {
            objetoTrace.close();
        }

        FakeCdp array = new FakeCdp();
        array.response = corpo(Json.parse("[{\"media_id\":\"arr_1\"}]"));
        PublishTrace arrayTrace = iniciar(array, "instagram");
        try {
            array.pedido("arr", URL_IG, "POST", TEXTO);
            array.resposta("arr", 200);
            esperarId(arrayTrace, "arr_1");
            assertEquals(Set.of("arr_1"), arrayTrace.ids());
        } finally {
            arrayTrace.close();
        }
    }

    @Test
    void profundidadeDeArraysAcimaDe20EhRejeitada() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpo(arrayAninhado(21, Json.obj().put("media_id", "fundo_array")));
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("arr-deep", URL_IG, "POST", TEXTO);
            cdp.resposta("arr-deep", 200);
            esperarDiag(trace, "invalido=true");
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("fundo_array"));
        } finally {
            trace.close();
        }
    }

    // ---------- hostBase (L462/L480) ----------

    @Test
    void hostBaseRejeitaUrlSemHost() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("rel", "/api/local", "POST", TEXTO);
            assertEquals(diagnosticoVazio(), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    @Test
    void hostBaseRejeitaUrlInvalida() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("invalida", "https://exa mple.com/api", "POST", TEXTO);
            assertEquals(diagnosticoVazio(), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- containsApprovedText (L512) ----------

    @Test
    void corpoComEscapamentoPercentualInvalidoNaoAprovaTexto() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("pct", URL_IG, "POST", "texto=%ZZ");
            assertEquals("posts=1 caminhoOk=1 semCorpo=0 comTexto=0 status=[] corpos=0 ids=[]"
                    + " invalido=false erro=- caminhosComTexto=[]", trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- TTL exato (L528) ----------

    @Test
    void traceExpiraNoInstanteExatoDoTtl() {
        FakeCdp cdp = new FakeCdp();
        AtomicLong relogio = new AtomicLong(1_000L);
        PublishTrace trace = PublishTrace.start(cdp, "instagram", TEXTO, relogio::get);
        try {
            relogio.set(1_000L + TTL - 1);
            assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("x"),
                    "um milissegundo antes do TTL o trace ainda e valido");
            relogio.set(1_000L + TTL);
            assertThrows(IllegalStateException.class, () -> trace.assertMatched("x"),
                    "no TTL exato o trace expira");
            assertTrue(trace.diagnostico().contains("invalido=true"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- trace invalido ignora eventos (L530) ----------

    @Test
    void traceInvalidoIgnoraEventosSeguintes() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto("not-json");
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("bad", URL_IG, "POST", TEXTO);
            cdp.resposta("bad", 200);
            esperarDiag(trace, "invalido=true");
            String antes = trace.diagnostico();

            cdp.pedido("depois1", "https://www.instagram.com/api/v1/media/2/configure/", "POST", TEXTO);
            cdp.pedido("depois2", "https://www.instagram.com/api/v1/media/3/configure/", "POST", TEXTO);

            assertEquals(antes, trace.diagnostico(), "trace invalido nao pode processar novos eventos");
            assertTrue(antes.contains("posts=1 caminhoOk=1"), antes);
        } finally {
            trace.close();
        }
    }

    // ---------- markInvalid registra a classe do erro (L539) ----------

    @Test
    void markInvalidRegistraClasseDoErroRecebido() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto("not-json");
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("bad", URL_IG, "POST", TEXTO);
            cdp.resposta("bad", 200);
            esperarDiag(trace, "invalido=true");
            String diag = trace.diagnostico();
            assertFalse(diag.contains("erro=-"), diag);
            assertTrue(diag.matches("(?s).*invalido=true erro=[A-Za-z][A-Za-z0-9]* caminhosComTexto=.*"), diag);
        } finally {
            trace.close();
        }
    }

    // ---------- close: falha de remocao (L549/L551) ----------

    @Test
    void closeComFalhaDeRemocaoInvalidaERegistraErro() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        cdp.failRemove = true;
        assertThrows(IllegalStateException.class, trace::close);
        String diag = trace.diagnostico();
        assertTrue(diag.contains("invalido=true"), diag);
        assertFalse(diag.contains("erro=-"), diag);
        assertTrue(diag.matches("(?s).*invalido=true erro=[A-Za-z][A-Za-z0-9]* caminhosComTexto=.*"), diag);
    }

    @Test
    void closeAcumulaFalhasDeRemocaoComoSuprimidas() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        cdp.failRemove = true;
        IllegalStateException falha = assertThrows(IllegalStateException.class, trace::close);
        assertEquals("Falha ao limpar listener CDP", falha.getMessage());
        assertEquals(2, falha.getSuppressed().length, "as falhas seguintes precisam ficar suprimidas");
    }

    // ---------- close: limpeza de estado (L554/L556/L557) ----------

    @Test
    void closeLimpaListenersRequestsEIds() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        cdp.pedido("c", URL_IG, "POST", TEXTO);
        cdp.resposta("c", 201);
        esperarId(trace, "media_123");
        cdp.pedido("pendente", "https://www.instagram.com/api/v1/media/2/configure/", "POST", TEXTO);
        assertEquals(1, tamanhoCampo(trace, "requests"));
        assertEquals(3, tamanhoCampo(trace, "listeners"));

        trace.close();

        assertEquals(0, tamanhoCampo(trace, "listeners"), "close precisa limpar os listeners");
        assertEquals(0, tamanhoCampo(trace, "requests"), "close precisa limpar as requisicoes");
        assertTrue(trace.ids().isEmpty(), "close precisa limpar os ids capturados");
    }

    // ---------- Chrome real: TargetCdp + start(Target) (L48/L51/L54/L102) ----------

    @Test
    void startRealNoChromeDevolveTraceECloseLimpaListener() throws Exception {
        Path perfil = tmp.resolve("perfil");
        Files.createDirectories(perfil);
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort();
        }
        Path chrome = null;
        for (String cand : List.of("/opt/google/chrome/chrome", "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            if (Files.isExecutable(Path.of(cand))) {
                chrome = Path.of(cand);
                break;
            }
        }
        Assumptions.assumeTrue(chrome != null, "sem binario Chrome/Chromium");
        Process proprio = new ProcessBuilder("/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=" + porta, "--user-data-dir=" + perfil.toAbsolutePath(), "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean pronto = false;
            while (!pronto && System.nanoTime() < prazo) {
                try (Socket s = new Socket("127.0.0.1", porta)) {
                    pronto = true;
                } catch (java.io.IOException ainda) {
                    Thread.sleep(100);
                }
            }
            assertTrue(pronto, "Chrome headless nao abriu a porta de depuracao");
            ChromeOptions options = BrowserPool.buildAttachOptions("publishtrace-teste", Json.obj(),
                    perfil.toString(), "127.0.0.1:" + porta);
            JavaDriverless d = new JavaDriverless("publishtrace-teste", options, false);
            try {
                d.setPageLoadTimeout(10_000);
                Target target = d.getChrome().getCurrentTarget().get(5, TimeUnit.SECONDS);
                PublishTrace trace = PublishTrace.start(target, "x", TEXTO);
                try {
                    assertNotNull(trace, "start(Target) nao pode devolver null: o adaptador precisa encaminhar");
                } finally {
                    assertDoesNotThrow(trace::close, "close() precisa remover o listener real sem excecao");
                }
            } finally {
                d.quit();
            }
        } finally {
            proprio.descendants().forEach(ProcessHandle::destroyForcibly);
            proprio.destroyForcibly();
            proprio.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ---------- path do estado por rede, corpo presente (L214) ----------

    private static String caminhoDoEstado(PublishTrace trace, String requestId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requests = (Map<String, Object>) campo(trace, "requests");
        Object estado;
        synchronized (trace) {
            estado = requests.get(requestId);
        }
        assertNotNull(estado, "estado da requisicao ausente: " + requestId);
        return (String) campo(estado, "path");
    }

    @Test
    void pathDeEstadoComCorpoSoNasRedesListadas() {
        record Caso(String rede, String url, String esperado) {}
        for (Caso c : List.of(
                new Caso("bsky", URL_BSKY_CREATE, "/xrpc/com.atproto.repo.createRecord"),
                new Caso("reddit", URL_REDDIT, "/svc/shreddit/t3_abc123/create-comment"),
                new Caso("ig", "https://www.instagram.com/api/v1/web/comments/3123456789/add/",
                        "/api/v1/web/comments/3123456789/add/"),
                new Caso("x", "https://x.com/i/api/graphql/AbC123/CreateTweet", null),
                new Caso("mastodon", URL_MASTODON, null))) {
            FakeCdp cdp = new FakeCdp();
            PublishTrace trace = iniciar(cdp, c.rede());
            try {
                cdp.pedido("req", c.url(), "POST", TEXTO);
                assertEquals(c.esperado(), caminhoDoEstado(trace, "req"), "rede " + c.rede());
            } finally {
                trace.close();
            }
        }
    }

    @Test
    void redeForaDaListaNaoRegistraCaminhoPendente() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData", "text=texto%20aprovado");
        cdp.response = corpo(Json.parse("{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{}}}}}}"));
        PublishTrace trace = iniciar(cdp, "x");
        try {
            cdp.pedidoSemCorpo("x1", "https://x.com/i/api/graphql/AbC123/CreateTweet");
            cdp.resposta("x1", 200);
            esperarCorpoProcessado(trace);
            assertTrue(trace.diagnostico().contains("comTexto=1"), trace.diagnostico());
            assertTrue(trace.diagnostico().contains("caminhosComTexto=[]"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- truncamento de caminho em 100 (L224/L297, padrao 1) ----------

    private static String urlInstagramComPath(int tamanho) {
        return "https://www.instagram.com/" + "p".repeat(tamanho - 1);
    }

    @Test
    void caminhoComTextoGuarda100ETrunca101() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("e100", urlInstagramComPath(100), "POST", TEXTO);
            cdp.pedido("e101", urlInstagramComPath(101), "POST", TEXTO);
            List<String> copia;
            synchronized (trace) {
                @SuppressWarnings("unchecked")
                List<String> caminhos = (List<String>) campo(trace, "pathsWithText");
                copia = List.copyOf(caminhos);
            }
            assertEquals(2, copia.size());
            assertEquals("/" + "p".repeat(99), copia.get(0));
            assertEquals(100, copia.get(0).length());
            assertEquals("/" + "p".repeat(99), copia.get(1));
            assertEquals(100, copia.get(1).length());
        } finally {
            trace.close();
        }
    }

    @Test
    void caminhoPendenteLongoTruncadoEm100() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData", "comment_text=texto%20aprovado");
        cdp.response = corpo(Json.obj());
        PublishTrace trace = iniciar(cdp, "ig");
        try {
            cdp.pedidoSemCorpo("longo", urlInstagramComPath(101));
            cdp.resposta("longo", 200);
            esperarDiag(trace, "comTexto=1");
            List<String> copia;
            synchronized (trace) {
                @SuppressWarnings("unchecked")
                List<String> caminhos = (List<String>) campo(trace, "pathsWithText");
                copia = List.copyOf(caminhos);
            }
            assertEquals(1, copia.size());
            assertEquals(("/" + "p".repeat(100)).substring(0, 100), copia.get(0));
            assertEquals(100, copia.get(0).length());
        } finally {
            trace.close();
        }
    }

    // ---------- status limitado aos 5 primeiros (L237) ----------

    @Test
    void diagnosticoGuardaExatamente5Status() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            for (int i = 0; i < 6; i++) {
                String id = "st" + i;
                cdp.pedido(id, "https://www.instagram.com/api/v1/media/" + i + "/configure/", "POST", TEXTO);
                cdp.resposta(id, 200 + i);
            }
            esperarId(trace, "media_123");
            List<Integer> copia;
            synchronized (trace) {
                @SuppressWarnings("unchecked")
                List<Integer> status = (List<Integer>) campo(trace, "statuses");
                copia = List.copyOf(status);
            }
            assertEquals(List.of(200, 201, 202, 203, 204), copia);
            assertTrue(trace.diagnostico().contains("status=[200, 201, 202, 203, 204]"), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- fronteiras de status do finished (L247) ----------

    @Test
    void status199IgnoraE200BuscaCorpo() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("s199", URL_IG, "POST", TEXTO);
            cdp.resposta("s199", 199);
            assertEquals(0, cdp.corposPedidos.get(), "status 199 nao pode pedir corpo");
            assertEquals(0, tamanhoCampo(trace, "requests"));

            cdp.pedido("s200", URL_IG, "POST", TEXTO);
            cdp.resposta("s200", 200);
            esperarId(trace, "media_123");
            assertEquals(1, cdp.corposPedidos.get(), "status 200 precisa buscar o corpo");
        } finally {
            trace.close();
        }
    }

    @Test
    void status299BuscaCorpoE300Ignora() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("s299", URL_IG, "POST", TEXTO);
            cdp.resposta("s299", 299);
            esperarId(trace, "media_123");
            assertEquals(1, cdp.corposPedidos.get(), "status 299 precisa buscar o corpo");

            cdp.pedido("s300", URL_IG, "POST", TEXTO);
            cdp.resposta("s300", 300);
            assertEquals(1, cdp.corposPedidos.get(), "status 300 nao pode pedir corpo");
            assertEquals(0, tamanhoCampo(trace, "requests"));
        } finally {
            trace.close();
        }
    }

    // ---------- caminhos pendentes limitados a 5 (L296) ----------

    @Test
    void caminhosPendentesLimitadosAExatamente5() {
        FakeCdp cdp = new FakeCdp();
        cdp.requestPostData = Json.obj().put("postData", "comment=texto%20aprovado");
        cdp.response = corpoTexto("<html></html>");
        PublishTrace trace = iniciar(cdp, "reddit");
        try {
            for (int i = 0; i < 6; i++) {
                String id = "pp" + i;
                cdp.pedidoSemCorpo(id, "https://www.reddit.com/svc/shreddit/t3_aa" + i + "bb/create-comment");
                cdp.resposta(id, 200);
            }
            esperarDiag(trace, "comTexto=6");
            assertEquals(5, tamanhoCampo(trace, "pathsWithText"));
        } finally {
            trace.close();
        }
    }

    // ---------- applyWrites: cada lado do || em 200/201 (L415) ----------

    private static String writesJson(int itens) {
        List<String> writes = new ArrayList<>();
        for (int i = 0; i < itens; i++) {
            if (i == 0) {
                writes.add("{\"$type\":\"com.atproto.repo.applyWrites#create\",\"collection\":\"app.bsky.feed.post\","
                        + "\"value\":{\"text\":\"" + TEXTO + "\"}}");
            } else {
                writes.add("{\"$type\":\"com.atproto.repo.applyWrites#update\",\"collection\":\"app.bsky.feed.post\","
                        + "\"value\":{\"text\":\"texto diferente\"}}");
            }
        }
        return "{\"writes\":[" + String.join(",", writes) + "]}";
    }

    private static String resultsJson(int itens) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < itens; i++) {
            if (i == 0) {
                results.add("{\"$type\":\"com.atproto.repo.applyWrites#createResult\","
                        + "\"uri\":\"at://did:plc:abc/app.bsky.feed.post/rkey0\"}");
            } else {
                results.add("{\"$type\":\"com.atproto.repo.applyWrites#updateResult\"}");
            }
        }
        return "{\"results\":[" + String.join(",", results) + "]}";
    }

    @Test
    void applyWritesCom201WritesInvalidaMesmoComPoucosResults() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto("{\"results\":[]}");
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w201r0", URL_BSKY_WRITES, "POST", writesJson(201));
            cdp.resposta("w201r0", 200);
            esperarDiag(trace, "invalido=true");
            assertTrue(trace.ids().isEmpty());
        } finally {
            trace.close();
        }
    }

    @Test
    void applyWritesCom201ResultsInvalidaMesmoComPoucosWrites() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(resultsJson(201));
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w1r201", URL_BSKY_WRITES, "POST", writesJson(1));
            cdp.resposta("w1r201", 200);
            esperarDiag(trace, "invalido=true");
            assertTrue(trace.ids().isEmpty());
        } finally {
            trace.close();
        }
    }

    @Test
    void applyWritesCom200WritesEPoucosResultsNaoInvalida() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto("{\"results\":[]}");
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w200r0", URL_BSKY_WRITES, "POST", writesJson(200));
            cdp.resposta("w200r0", 200);
            esperarCorpoProcessado(trace);
            assertTrue(trace.diagnostico().contains("invalido=false"), trace.diagnostico());
            assertTrue(trace.ids().isEmpty(), "tamanhos diferentes: nenhum id entra, mas o trace segue valido");
        } finally {
            trace.close();
        }
    }

    @Test
    void applyWritesCom200ResultsEPoucosWritesNaoInvalida() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(resultsJson(200));
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("w1r200", URL_BSKY_WRITES, "POST", writesJson(1));
            cdp.resposta("w1r200", 200);
            esperarCorpoProcessado(trace);
            assertTrue(trace.diagnostico().contains("invalido=false"), trace.diagnostico());
            assertTrue(trace.ids().isEmpty(), "tamanhos diferentes: nenhum id entra, mas o trace segue valido");
        } finally {
            trace.close();
        }
    }

    // ---------- allowedHost (L486/L490/L501) ----------

    private static boolean allowedHostViaReflexao(PublishTrace trace, String url) {
        try {
            Method m = PublishTrace.class.getDeclaredMethod("allowedHost", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(trace, url);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void allowedHostRejeitaUrlForaDoHostBaseESemHost() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            assertFalse(allowedHostViaReflexao(trace,
                    "https://evil.example.com/api/v1/media/1/configure/"));
            assertFalse(allowedHostViaReflexao(trace, "/api/local"));
        } finally {
            trace.close();
        }
    }

    @Test
    void redeComPathNaoPermitidoNaoCapturaNada() {
        FakeCdp cdp = new FakeCdp();
        PublishTrace trace = iniciar(cdp, "bsky");
        try {
            cdp.pedido("errada", "https://bsky.social/xrpc/com.atproto.repo.listRecords", "POST", TEXTO);
            assertTrue(trace.diagnostico().contains("posts=1"), trace.diagnostico());
            assertTrue(trace.diagnostico().contains("caminhoOk=0"), trace.diagnostico());
            cdp.resposta("errada", 200);
            esperarCorpoProcessado(trace);
            assertTrue(trace.ids().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> trace.assertMatched("x"));
        } finally {
            trace.close();
        }
    }

    // ---------- stripXssi (L519) ----------

    private static String stripXssiViaReflexao(String body) {
        try {
            Method m = PublishTrace.class.getDeclaredMethod("stripXssi", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, body);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void xssiSemQuebraDevolveVazioEComQuebraDevolveResto() {
        assertEquals("", stripXssiViaReflexao(")]}'"));
        assertEquals("{\"a\":1}", stripXssiViaReflexao(")]}'\n{\"a\":1}"));
    }

    @Test
    void corpoComPrefixoXssiECapturado() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(")]}'\n{\"media_id\":\"xssi_1\"}");
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("xssi", URL_IG, "POST", TEXTO);
            cdp.resposta("xssi", 200);
            esperarId(trace, "xssi_1");
            assertEquals(Set.of("xssi_1"), trace.ids());
        } finally {
            trace.close();
        }
    }

    @Test
    void corpoSoComPrefixoXssiNaoCapturaNada() {
        FakeCdp cdp = new FakeCdp();
        cdp.response = corpoTexto(")]}'");
        PublishTrace trace = iniciar(cdp, "instagram");
        try {
            cdp.pedido("xssi-vazio", URL_IG, "POST", TEXTO);
            cdp.resposta("xssi-vazio", 200);
            esperarDiag(trace, "corpos=1");
            assertTrue(trace.ids().isEmpty(), trace.diagnostico());
        } finally {
            trace.close();
        }
    }

    // ---------- TTL exato ignora eventos (L528) ----------

    @Test
    void eventoNoTtlExatoEIgnoradoEUmMsAntesVale() {
        FakeCdp cdp = new FakeCdp();
        AtomicLong relogio = new AtomicLong(9_000L);
        PublishTrace trace = PublishTrace.start(cdp, "instagram", TEXTO, relogio::get);
        try {
            relogio.set(9_000L + TTL - 1);
            cdp.pedido("antes", URL_IG, "POST", TEXTO);
            assertTrue(trace.diagnostico().contains("posts=1"), trace.diagnostico());

            relogio.set(9_000L + TTL);
            cdp.pedido("depois", "https://www.instagram.com/api/v1/media/2/configure/", "POST", TEXTO);
            String diag = trace.diagnostico();
            assertTrue(diag.contains("posts=1"), diag);
            assertTrue(diag.contains("invalido=true"), diag);
        } finally {
            trace.close();
        }
    }
}
