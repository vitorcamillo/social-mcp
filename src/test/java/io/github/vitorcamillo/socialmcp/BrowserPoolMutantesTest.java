package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mata os mutantes vivos (PIT) de BrowserPool que dao para exercitar sem Chrome:
 * fronteiras de compensateShadow/parseWindowSize, storageDir, isOpen falso,
 * close em rede ausente, navegacaoBloqueada (nula e bloqueada), desarme da
 * guarda no close/closeAll e ociosidade (closeIdle/closeAll/close).
 * Guardas e ultimo-uso sao injetados por reflexao com um CDP falso; nenhum
 * teste lanca navegador, usa rede, dorme ou depende de ambiente/relogio real.
 */
class BrowserPoolMutantesTest {

    @TempDir
    Path tmp;

    /** CDP falso minimo: registra comandos e entrega Fetch.requestPaused ao listener. */
    private static final class CdpFalso implements NavGuard.Cdp {
        record Cmd(String comando, Map<String, Object> params) {}

        final List<Cmd> comandos = new CopyOnWriteArrayList<>();
        final Map<String, Consumer<JsonNode>> ouvintes = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> addCdpListener(String evento, Consumer<JsonNode> o) {
            ouvintes.put(evento, o);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> removeCdpListener(String evento, Consumer<JsonNode> o) {
            ouvintes.remove(evento, o);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<JsonNode> executeCdpCmd(String comando, Map<String, Object> params, Float timeout) {
            comandos.add(new Cmd(comando, params));
            return CompletableFuture.completedFuture(Json.obj());
        }

        void pausarDocumento(String requestId, String frame, String url) {
            Consumer<JsonNode> o = ouvintes.get(NavGuard.EVENTO);
            assertNotNull(o, "listener de Fetch.requestPaused deve estar registrado");
            o.accept(Json.parse("{\"requestId\":\"" + requestId + "\",\"resourceType\":\"Document\""
                    + ",\"frameId\":\"" + frame + "\",\"request\":{\"url\":\"" + url + "\"}}"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> ultimoUsoDe(BrowserPool pool) throws Exception {
        var campo = BrowserPool.class.getDeclaredField("lastUse");
        campo.setAccessible(true);
        return (Map<String, Long>) campo.get(pool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NavGuard> guardasDe(BrowserPool pool) throws Exception {
        var campo = BrowserPool.class.getDeclaredField("navGuards");
        campo.setAccessible(true);
        return (Map<String, NavGuard>) campo.get(pool);
    }

    private static String diretorioSessao(BrowserPool pool, String rede) throws Exception {
        var metodo = BrowserPool.class.getDeclaredMethod("storageDir", String.class);
        metodo.setAccessible(true);
        return (String) metodo.invoke(pool, rede);
    }

    private Store novoStore(String nome) {
        return new Store(tmp.resolve(nome));
    }

    // ---------- compensateShadow (L209) ----------

    @Test
    void sombraExatamenteNoLimiteAindaCompensa() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertEquals(new BrowserPool.WindowSize(1480, 1100), BrowserPool.compensateShadow(alvo, 1080));
    }

    @Test
    void sombraUmPixelAcimaDoLimiteNaoMexe() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertEquals(alvo, BrowserPool.compensateShadow(alvo, 1079));
    }

    @Test
    void sombraDeUmPixelJaCompensa() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertEquals(new BrowserPool.WindowSize(1281, 901), BrowserPool.compensateShadow(alvo, 1279));
    }

    @Test
    void semSombraNaoMexe() {
        var alvo = new BrowserPool.WindowSize(1280, 900);
        assertEquals(alvo, BrowserPool.compensateShadow(alvo, 1280));
    }

    // ---------- parseWindowSize (L223) ----------

    @Test
    void larguraMinimaExataVale() {
        assertEquals(new BrowserPool.WindowSize(400, 900), BrowserPool.parseWindowSize("400x900"));
    }

    @Test
    void alturaMinimaExataVale() {
        assertEquals(new BrowserPool.WindowSize(1280, 400), BrowserPool.parseWindowSize("1280x400"));
    }

    @Test
    void larguraMaximaExataVale() {
        assertEquals(new BrowserPool.WindowSize(7680, 900), BrowserPool.parseWindowSize("7680x900"));
    }

    @Test
    void alturaMaximaExataVale() {
        assertEquals(new BrowserPool.WindowSize(1280, 7680), BrowserPool.parseWindowSize("1280x7680"));
    }

    @Test
    void umAbaixoDoMinimoVoltaAoPadrao() {
        assertEquals(BrowserPool.DEFAULT_WINDOW, BrowserPool.parseWindowSize("399x900"));
        assertEquals(BrowserPool.DEFAULT_WINDOW, BrowserPool.parseWindowSize("1280x399"));
    }

    @Test
    void umAcimaDoMaximoVoltaAoPadrao() {
        assertEquals(BrowserPool.DEFAULT_WINDOW, BrowserPool.parseWindowSize("7681x900"));
        assertEquals(BrowserPool.DEFAULT_WINDOW, BrowserPool.parseWindowSize("1280x7681"));
    }

    // ---------- storageDir (L228) ----------

    @Test
    void diretorioDeSessaoEProfileSdkDaRede() throws Exception {
        Store store = novoStore("home-storage");
        BrowserPool pool = new BrowserPool(store);
        String esperado = store.home.resolve(".javadriverless_profiles").resolve("sdk-tiktok").toString();
        assertEquals(esperado, diretorioSessao(pool, "tiktok"));
        assertFalse(diretorioSessao(pool, "tiktok").isEmpty());
    }

    // ---------- isOpen (L343, mutante true) ----------

    @Test
    void redeNuncaAbertaNaoEstaAberta() {
        BrowserPool pool = new BrowserPool(novoStore("home-isopen"));
        assertFalse(pool.isOpen("tiktok"));
    }

    // ---------- close em rede ausente (L355, L407) ----------

    @Test
    void fecharRedeAusenteNaoFalha() {
        BrowserPool pool = new BrowserPool(novoStore("home-close"));
        assertDoesNotThrow(() -> pool.close("rede-ausente"));
        assertFalse(pool.isOpen("rede-ausente"));
    }

    // ---------- navegacaoBloqueada (L173) ----------

    @Test
    void semGuardaNenhumaNavegacaoBloqueada() {
        BrowserPool pool = new BrowserPool(novoStore("home-nulo"));
        assertNull(pool.navegacaoBloqueada("tiktok"));
    }

    @Test
    void guardaComBloqueioDevolveAUrlExata() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-bloq"));
        CdpFalso cdp = new CdpFalso();
        guardasDe(pool).put("tiktok", NavGuard.instalar(cdp, "FRAME-1", url -> false));
        cdp.pausarDocumento("r1", "FRAME-1", "https://evil.example/roubo");
        assertEquals("https://evil.example/roubo", pool.navegacaoBloqueada("tiktok"));
    }

    // ---------- close desarma a guarda (L353, L409) ----------

    @Test
    void fecharRedeRemoveAGuarda() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-desarma"));
        CdpFalso cdp = new CdpFalso();
        guardasDe(pool).put("tiktok", NavGuard.instalar(cdp, "FRAME-1", url -> false));
        cdp.pausarDocumento("r1", "FRAME-1", "https://evil.example/roubo");
        pool.close("tiktok");
        assertNull(pool.navegacaoBloqueada("tiktok"), "close desarma a guarda da rede");
    }

    @Test
    void fecharRedeDesligaOFetchDaGuarda() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-fetch"));
        CdpFalso cdp = new CdpFalso();
        guardasDe(pool).put("tiktok", NavGuard.instalar(cdp, "FRAME-1", url -> false));
        pool.close("tiktok");
        assertTrue(cdp.comandos.stream().anyMatch(c -> c.comando().equals("Fetch.disable")),
                "desarmar a guarda desliga o Fetch antes de tirar o listener");
    }

    // ---------- closeIdle (L385, L388, L390) ----------

    @Test
    void ociosidadeVencidaDevolveARede() throws Exception {
        Store store = novoStore("home-ociosa");
        long agora = 5_000_000_000L;
        BrowserPool pool = new BrowserPool(store, () -> agora);
        ultimoUsoDe(pool).put("tiktok", agora - 11 * 60_000L);
        assertEquals(List.of("tiktok"), pool.closeIdle());
    }

    @Test
    void ociosidadeVencidaFechaARede() throws Exception {
        Store store = novoStore("home-ociosa-fecha");
        long agora = 5_000_000_000L;
        BrowserPool pool = new BrowserPool(store, () -> agora);
        ultimoUsoDe(pool).put("tiktok", agora - 11 * 60_000L);
        pool.closeIdle();
        assertFalse(ultimoUsoDe(pool).containsKey("tiktok"), "closeIdle fecha a rede vencida");
    }

    // ---------- closeAll e close (L401, L402, L417) ----------

    @Test
    void fecharTudoLimpaOUltimoUso() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-tudo-uso"));
        ultimoUsoDe(pool).put("tiktok", 1L);
        pool.closeAll();
        assertTrue(ultimoUsoDe(pool).isEmpty());
    }

    @Test
    void fecharTudoDesarmaAsGuardas() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-tudo-guarda"));
        CdpFalso cdp = new CdpFalso();
        guardasDe(pool).put("tiktok", NavGuard.instalar(cdp, "FRAME-1", url -> false));
        cdp.pausarDocumento("r1", "FRAME-1", "https://evil.example/roubo");
        pool.closeAll();
        assertNull(pool.navegacaoBloqueada("tiktok"), "closeAll desarma as guardas");
    }

    @Test
    void closeDelegaParaFecharTudo() throws Exception {
        BrowserPool pool = new BrowserPool(novoStore("home-close-delega"));
        ultimoUsoDe(pool).put("tiktok", 1L);
        pool.close();
        assertTrue(ultimoUsoDe(pool).isEmpty());
    }

    // ---------- get: espera, prazo, pausa e interrupcao (L77-L96) ----------

    /** Pool com launch/pausa injetados: launch sempre falha, pausa avanca o relogio falso. */
    private static final class PoolDeFalha extends BrowserPool {
        final long[] agora;
        final RuntimeException falha = new RuntimeException("chrome travado");
        int aberturas = 0;
        int dormidas = 0;
        boolean interromperNaPausa = false;

        PoolDeFalha(Store store, long[] agora) {
            super(store, () -> agora[0]);
            this.agora = agora;
        }

        @Override
        JavaDriverless abrir(String network) {
            aberturas++;
            throw falha;
        }

        @Override
        void pausar(long ms) throws InterruptedException {
            if (interromperNaPausa) throw new InterruptedException("pausa interrompida");
            dormidas++;
            agora[0] += ms;
        }
    }

    private static void gravarConfig(Store s, ObjectNode cfg) throws Exception {
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
    }

    @Test
    void esperaNegativaViraZeroNaMensagem() throws Exception {
        Store s = novoStore("home-espera-neg");
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.path("networks")).putObject("tiktok").put("profileWaitSeconds", -5);
        gravarConfig(s, cfg);
        PoolDeFalha pool = new PoolDeFalha(s, new long[]{7_000_000_000L});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pool.get("tiktok"));
        assertEquals("perfil da rede 'tiktok' ocupado ou Chrome nao iniciou apos 0s"
                + " (outra sessao do MCP pode estar com ele): chrome travado", e.getMessage());
        assertSame(pool.falha, e.getCause());
        assertEquals(1, pool.aberturas, "com espera 0 a primeira falha ja estoura o prazo");
        assertEquals(0, pool.dormidas, "sem pausa quando o prazo ja estourou");
    }

    @Test
    void prazoEstouradoContaTentativasExatas() {
        Store s = novoStore("home-prazo");
        PoolDeFalha pool = new PoolDeFalha(s, new long[]{9_000_000_000L});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pool.get("tiktok"));
        assertEquals("perfil da rede 'tiktok' ocupado ou Chrome nao iniciou apos 90s"
                + " (outra sessao do MCP pode estar com ele): chrome travado", e.getMessage());
        assertSame(pool.falha, e.getCause());
        assertEquals(19, pool.aberturas, "1 tentativa inicial + 18 pausas de 5s ate 90s");
        assertEquals(18, pool.dormidas);
    }

    @Test
    void interrupcaoNaPausaRelancaAFalhaEPreservaAFlag() {
        Store s = novoStore("home-interrompe");
        PoolDeFalha pool = new PoolDeFalha(s, new long[]{1_000L});
        pool.interromperNaPausa = true;
        boolean preservou;
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> pool.get("tiktok"));
            assertSame(pool.falha, e, "interrupcao relanca a ultima falha do launch");
            assertEquals(1, pool.aberturas);
        } finally {
            preservou = Thread.currentThread().isInterrupted();
            Thread.interrupted();
        }
        assertTrue(preservou, "flag de interrupcao preservada");
    }

    @Test
    void pausaPadraoRespeitaInterrupcao() {
        BrowserPool pool = new BrowserPool(novoStore("home-pausa"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> pool.pausar(5_000));
        } finally {
            Thread.interrupted();
        }
    }

    // ---------- instalarGuardaOuFechar e falhaDeGuarda (L123-L146) ----------

    @Test
    void guardaOuFecharSucessoNaoFecha() {
        boolean[] fechou = {false};
        assertDoesNotThrow(() -> BrowserPool.instalarGuardaOuFechar(() -> {}, () -> fechou[0] = true));
        assertFalse(fechou[0], "sucesso nao fecha nada");
    }

    @Test
    void guardaOuFecharFalhaFechaEPropagaOriginal() {
        RuntimeException causa = new RuntimeException("sem Fetch");
        boolean[] fechou = {false};
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> BrowserPool.instalarGuardaOuFechar(() -> { throw causa; }, () -> fechou[0] = true));
        assertSame(causa, e);
        assertTrue(fechou[0], "falha na guarda fecha a sessao");
    }

    @Test
    void guardaOuFecharFalhaNoFecharEntraComoSuprimida() {
        RuntimeException causa = new RuntimeException("sem Fetch");
        RuntimeException noFechar = new RuntimeException("quit recusado");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> BrowserPool.instalarGuardaOuFechar(() -> { throw causa; }, () -> { throw noFechar; }));
        assertSame(causa, e);
        assertEquals(1, e.getSuppressed().length, "a falha ao fechar nao pode se perder");
        assertSame(noFechar, e.getSuppressed()[0]);
    }

    @Test
    void falhaDeGuardaComInterrupcaoPreservaAFlag() {
        InterruptedException causa = new InterruptedException("cdp cortado");
        boolean preservou;
        IllegalStateException e;
        try {
            e = BrowserPool.falhaDeGuarda("tiktok", causa);
        } finally {
            preservou = Thread.currentThread().isInterrupted();
            Thread.interrupted();
        }
        assertTrue(preservou, "InterruptedException preserva a flag");
        assertEquals("guarda de navegacao nao instalada para a rede 'tiktok'; "
                + "navegador nao sera usado sem interceptacao pre-requisicao", e.getMessage());
        assertSame(causa, e.getCause());
    }

    @Test
    void falhaDeGuardaSemInterrupcaoNaoTocaNaFlag() {
        Thread.interrupted();
        RuntimeException causa = new RuntimeException("timeout cdp");
        IllegalStateException e = BrowserPool.falhaDeGuarda("tiktok", causa);
        assertFalse(Thread.currentThread().isInterrupted());
        assertSame(causa, e.getCause());
        assertTrue(e.getMessage().contains("'tiktok'"), e.getMessage());
    }

    // ---------- applyWindowSize: falha vai para stderr (L195) ----------

    @Test
    void tamanhoQueFalhaVaiParaStderr() throws Exception {
        var metodo = BrowserPool.class.getDeclaredMethod("applyWindowSize",
                JavaDriverless.class, String.class, BrowserPool.WindowSize.class);
        metodo.setAccessible(true);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            metodo.invoke(null, null, "tiktok", new BrowserPool.WindowSize(1000, 700));
        } finally {
            System.setErr(original);
        }
        String saida = err.toString(StandardCharsets.UTF_8);
        assertTrue(saida.contains("[social-mcp] tiktok: nao ajustou janela para 1000x700: "), saida);
    }

    // ---------- Chrome real anexado (L69-L85, L117-L131, L144, L188-L190, L343-L449) ----------

    private static final String REDE_REAL = "mutante-real";
    private static Path baseReal;
    private static Store storeReal;
    private static Process chromeReal;
    private static boolean chromeRealOk = false;

    @BeforeAll
    static void subirChromeReal() throws Exception {
        baseReal = Files.createTempDirectory("pool-mutantes-real");
        storeReal = new Store(baseReal.resolve("home"));
        ObjectNode cfg = (ObjectNode) storeReal.config();
        ObjectNode rede = ((ObjectNode) cfg.path("networks")).putObject(REDE_REAL);
        rede.put("attachExistingChrome", true);
        rede.put("windowSize", "1000x700");
        rede.put("profileWaitSeconds", 1);
        rede.putArray("browseHosts").add("livre.invalid");
        Files.writeString(storeReal.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        Path perfil = storeReal.home.resolve(".javadriverless_profiles").resolve("sdk-" + REDE_REAL);
        Files.createDirectories(perfil);
        Path chrome = null;
        for (String cand : List.of("/opt/google/chrome/chrome", "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            if (Files.isExecutable(Path.of(cand))) {
                chrome = Path.of(cand);
                break;
            }
        }
        if (chrome == null) return;
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort();
        }
        chromeReal = new ProcessBuilder("/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=" + porta, "--user-data-dir=" + perfil.toAbsolutePath(), "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean pronto = false;
        while (!pronto && System.nanoTime() < prazo) {
            try (Socket s = new Socket("127.0.0.1", porta)) {
                pronto = true;
            } catch (java.io.IOException ainda) {
                Thread.sleep(100);
            }
        }
        chromeRealOk = pronto;
    }

    @AfterAll
    static void descerChromeReal() throws Exception {
        if (chromeReal != null) {
            chromeReal.descendants().forEach(ProcessHandle::destroyForcibly);
            chromeReal.destroyForcibly();
            chromeReal.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void assumirChromeReal() {
        Assumptions.assumeTrue(chromeRealOk, "sem Chrome headless para attach");
    }

    @Test
    void anexaEDevolveOMesmoObjeto() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d1 = pool.get(REDE_REAL);
            assertNotNull(d1);
            assertSame(d1, pool.get(REDE_REAL), "segundo get devolve o MESMO objeto");
            assertTrue(pool.isOpen(REDE_REAL));
            assertEquals(1, pool.openCount());
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void janelaAplicadaNoAttach() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            Object inner = d.executeScript("window.innerWidth");
            assertInstanceOf(Number.class, inner);
            assertEquals(1000, ((Number) inner).intValue(),
                    "applyWindowSize deve deixar o viewport na largura pedida");
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void guardaRealBloqueiaHttpForaDaRede() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            try {
                d.get("http://127.0.0.1:1/");
            } catch (Exception esperado) {
                // failRequest(BlockedByClient) aborta o goto
            }
            assertEquals("http://127.0.0.1:1/", pool.navegacaoBloqueada(REDE_REAL),
                    "goto http fora da rede deve ser bloqueado e registrado pela guarda real");
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void guardaRealPermiteHttpsLiberado() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            try {
                d.get("https://livre.invalid/");
            } catch (Exception esperado) {
                // sem rede externa: o DNS falha depois de a guarda liberar
            }
            String bloqueada = pool.navegacaoBloqueada(REDE_REAL);
            assertTrue(bloqueada == null || !bloqueada.equals("https://livre.invalid/"),
                    "url liberada nao pode ser registrada como bloqueada; registrado=" + bloqueada);
        } finally {
            pool.closeAll();
        }
    }

    /**
     * Driver real cujo quit() e contado: no attach o quit nao derruba a conexao do
     * Chrome externo, entao o fechamento observavel e uma chamada exata a quit().
     */
    private static JavaDriverless contarQuits(String n, ChromeOptions options, AtomicInteger quits) {
        return new JavaDriverless("sdk-" + n, options, false) {
            @Override
            public void quit() {
                quits.incrementAndGet();
                super.quit();
            }
        };
    }

    /** Pool que conta os quit() das sessoes criadas. */
    private static final class PoolContandoQuits extends BrowserPool {
        final AtomicInteger quits = new AtomicInteger();

        PoolContandoQuits(Store store) {
            super(store);
        }

        @Override
        JavaDriverless criarDriver(String n, ChromeOptions options) {
            return contarQuits(n, options, quits);
        }
    }

    /** Pool que cria sessao real mas simula falha na guarda, para o caminho sem guarda. */
    private static final class PoolGuardaFalsa extends BrowserPool {
        final long[] agora;
        JavaDriverless criado;
        final AtomicInteger quits = new AtomicInteger();
        final AtomicInteger criados = new AtomicInteger();

        PoolGuardaFalsa(Store store, long[] agora) {
            super(store, () -> agora[0]);
            this.agora = agora;
        }

        @Override
        JavaDriverless criarDriver(String n, ChromeOptions options) {
            criados.incrementAndGet();
            JavaDriverless d = contarQuits(n, options, quits);
            criado = d;
            return d;
        }

        @Override
        void instalarGuarda(JavaDriverless d, String n) {
            throw new IllegalStateException("falha de guarda simulada");
        }

        @Override
        void pausar(long ms) throws InterruptedException {
            agora[0] += ms;
        }
    }

    @Test
    void falhaNaGuardaFechaSessaoEPropaga() {
        assumirChromeReal();
        PoolGuardaFalsa pool = new PoolGuardaFalsa(storeReal, new long[]{5_000_000_000L});
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> pool.get(REDE_REAL));
            assertTrue(e.getMessage().startsWith(
                    "perfil da rede 'mutante-real' ocupado ou Chrome nao iniciou apos 1s ("), e.getMessage());
            assertTrue(e.getMessage().endsWith(": falha de guarda simulada"), e.getMessage());
            assertNotNull(pool.criado, "driver criado antes de a guarda falhar");
            assertEquals(2, pool.criados.get(), "tentativa inicial + uma apos a pausa que estoura o prazo");
            assertEquals(2, pool.quits.get(), "cada sessao criada sem guarda recebe exatamente um quit()");
            assertFalse(pool.isOpen(REDE_REAL));
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void alertaRealDevolveTextoExato() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            d.executeScript("document.title = 'Titulo X';"
                    + " document.body.innerHTML = '<div role=\"alert\">alerta Y</div>';");
            assertEquals("Titulo X\nalerta Y", BrowserPool.alertText(d));
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void alertaRealTruncaEm4000() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            d.executeScript("document.title = 'T';"
                    + " document.body.innerHTML = '<div role=\"alert\">' + 'z'.repeat(5000) + '</div>';");
            String cheio = "T\n" + "z".repeat(5000);
            assertEquals(cheio.substring(0, 4000), BrowserPool.alertText(d));
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void visivelRealNaFronteiraExata() {
        assumirChromeReal();
        BrowserPool pool = new BrowserPool(storeReal);
        try {
            JavaDriverless d = pool.get(REDE_REAL);
            d.executeScript("document.body.innerHTML = 'v'.repeat(120);");
            assertEquals("v".repeat(120), BrowserPool.visibleText(d, 120),
                    "texto com exatamente maxChars volta sem sufixo");
            assertEquals("v".repeat(119) + "...[truncado]", BrowserPool.visibleText(d, 119),
                    "um char acima do limite trunca com sufixo");
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void fechaESessaoMorre() {
        assumirChromeReal();
        PoolContandoQuits pool = new PoolContandoQuits(storeReal);
        try {
            assertNotNull(pool.get(REDE_REAL));
            assertTrue(pool.isOpen(REDE_REAL));
            assertEquals(0, pool.quits.get(), "sessao aberta ainda nao sofreu quit()");
            pool.close(REDE_REAL);
            assertFalse(pool.isOpen(REDE_REAL));
            assertEquals(0, pool.openCount());
            assertEquals(1, pool.quits.get(), "close deve encerrar a sessao com exatamente um quit()");
        } finally {
            pool.closeAll();
        }
    }

    @Test
    void fechaTudoLimpaEZera() {
        assumirChromeReal();
        PoolContandoQuits pool = new PoolContandoQuits(storeReal);
        try {
            assertNotNull(pool.get(REDE_REAL));
            assertEquals(1, pool.openCount());
            assertEquals(0, pool.quits.get(), "sessao aberta ainda nao sofreu quit()");
            pool.closeAll();
            assertFalse(pool.isOpen(REDE_REAL));
            assertEquals(0, pool.openCount());
            assertEquals(1, pool.quits.get(), "closeAll deve encerrar a sessao com exatamente um quit()");
        } finally {
            pool.closeAll();
        }
    }
}
