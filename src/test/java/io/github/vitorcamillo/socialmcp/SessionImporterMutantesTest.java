package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mutantes PIT de SessionImporter.
 *
 * <p>A primeira camada (guardas de entrada) roda sem Chrome. O run completo usa um
 * Chrome headless proprio, anexado via {@code attachExistingChrome=true} e o profile exato
 * do Store de teste; a pagina e {@code file://} porque a guarda de navegacao so barra
 * http(s) fora da rede (nao ha host https local com porta 443 sem privilegio). Depois do
 * run, uma segunda sessao CDP le os cookies aplicados ({@code Network.getCookies}) para
 * provar cada campo opcional (secure, httpOnly, sameSite, expires) e o cookie de sessao.
 */
class SessionImporterMutantesTest {

    @TempDir
    Path tmp;

    private static final String REDE = "testenet";
    private static final String DOMINIO = "exemplo.test";

    private Store storeNovo(String nome) {
        return new Store(tmp.resolve(nome + "-" + System.nanoTime()));
    }

    private static String arquivoCookies(Path dir, String nome, String conteudo) throws Exception {
        Path f = dir.resolve(nome);
        Files.writeString(f, conteudo, StandardCharsets.UTF_8);
        return f.toString();
    }

    @Test
    void redeDesconhecidaRejeitaAntesDeLerArquivo() {
        Store s = storeNovo("home");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                SessionImporter.run(s, "rede-que-nao-existe", tmp.resolve("naoexiste.json").toString()));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void arquivoComArrayVazioRejeitaSemAbrirNavegador() throws Exception {
        Store s = storeNovo("home");
        String arq = arquivoCookies(tmp, "vazio.json", "[]");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                SessionImporter.run(s, "instagram", arq));
        assertTrue(e.getMessage().contains("arquivo sem cookies"), e.getMessage());
    }

    @Test
    void arquivoSemArrayRejeitaSemAbrirNavegador() throws Exception {
        Store s = storeNovo("home");
        String arq = arquivoCookies(tmp, "objeto.json", "{}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                SessionImporter.run(s, "instagram", arq));
        assertTrue(e.getMessage().contains("arquivo sem cookies"), e.getMessage());
    }

    // ---------- run completo com Chrome headless anexado ----------

    /**
     * Kills: negacao de {@code !arr.isArray() || arr.isEmpty()} (array valido segue),
     * addShutdownHook removido, cada {@code hasNonNull} de secure/httpOnly/sameSite,
     * {@code exp > 0} (fronteira e negacao), {@code ok++}, {@code falha++},
     * {@code texto.contains(m)}, appendJournal removido, os dois ternarios do journal e
     * do retorno, e o {@code pool.closeAll()} do finally (via fabrica de teste).
     */
    @Test
    void runCompletoAplicaCookiesRegistraJournalFechaPoolERetornaTextoExato() throws Exception {
        Path pagina = tmp.resolve("pagina-login.html");
        Files.writeString(pagina, "<html><body>Fazer login para continuar</body></html>", StandardCharsets.UTF_8);
        Store s = storeComRede("completo", pagina);
        Path perfil = perfilDaRede(s);
        Path chrome = acharChrome();
        Assumptions.assumeTrue(chrome != null, "sem binario Chrome/Chromium");
        exigirPreset(s, perfil);
        int porta = portaLivre();
        Process proc = subirChrome(chrome, perfil, porta);

        Consumer<Thread> ganchoOriginal = SessionImporter.ganchoSaida;
        Function<Store, BrowserPool> fabricaOriginal = SessionImporter.fabricaPool;
        AtomicReference<Thread> gancho = new AtomicReference<>();
        AtomicBoolean fechou = new AtomicBoolean();
        AtomicReference<JavaDriverless> driverDoPool = new AtomicReference<>();
        AtomicReference<JsonNode> cookiesLidos = new AtomicReference<>();
        AtomicReference<Exception> erroCookies = new AtomicReference<>();
        long expiraEm = System.currentTimeMillis() / 1000 + 200L * 86_400; // dentro do teto de 400 dias do Chrome
        try {
            SessionImporter.ganchoSaida = gancho::set; // nao registra hook real no Runtime
            SessionImporter.fabricaPool = st -> new BrowserPool(st) {
                @Override public JavaDriverless get(String n) {
                    JavaDriverless d = super.get(n);
                    driverDoPool.set(d);
                    return d;
                }
                @Override public void closeAll() {
                    fechou.set(true);
                    // le os cookies com a MESMA sessao, antes de o pool fechar o navegador
                    try {
                        Target t = driverDoPool.get().getChrome().getCurrentTarget().get(5, TimeUnit.SECONDS);
                        cookiesLidos.set(t.executeCdpCmd("Network.getCookies",
                                Map.<String, Object>of("urls", List.of("https://" + DOMINIO + "/")),
                                5f).get(10, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        erroCookies.set(e);
                    }
                    super.closeAll();
                }
            };
            String retorno = SessionImporter.run(s, REDE,
                    arquivoCookies(tmp, "cookies.json", cookiesDoRun(expiraEm)));

            assertTrue(retorno.startsWith("rede=" + REDE
                    + " | cookies aplicados=5 falhas=1"
                    + " | marcadorLogin='fazer login' (deslogada?) | screenshot="), retorno);
            assertTrue(retorno.endsWith(".png"), retorno);

            Thread hook = gancho.get();
            assertNotNull(hook, "o gancho de saida (addShutdownHook) precisa ser registrado");
            assertFalse(Runtime.getRuntime().removeShutdownHook(hook),
                    "o teste nao pode deixar hook real pendurado no Runtime");
            assertTrue(fechou.get(), "o finally do run precisa fechar o pool");

            JsonNode j = ultimaLinhaDoJournal(s);
            assertEquals("session_import", j.path("type").asText());
            assertEquals(REDE, j.path("network").asText());
            assertEquals(5, j.path("cookiesOk").asInt(), j.toString());
            assertEquals(1, j.path("cookiesFalha").asInt(), j.toString());
            assertEquals("fazer login", j.path("marcadorLogin").asText(), j.toString());
            assertEquals(7, j.size(), "ts/day/type/network + os 3 campos do import: " + j);

            assertNull(erroCookies.get(), "leitura de cookies na sessao do pool: " + erroCookies.get());
            JsonNode cookies = cookiesLidos.get();
            assertNotNull(cookies, "Network.getCookies precisa ser lido antes de fechar o pool");
            assertTrue(cookie(cookies, "ck_secure").path("secure").asBoolean(), cookies.toString());
            assertTrue(cookie(cookies, "ck_http").path("httpOnly").asBoolean(), cookies.toString());
            assertEquals("Strict", cookie(cookies, "ck_samesite").path("sameSite").asText(), cookies.toString());
            assertEquals(expiraEm, cookie(cookies, "ck_expira").path("expires").asLong(), cookies.toString());
            assertEquals(-1L, cookie(cookies, "ck_sessao").path("expires").asLong(),
                    "sem expires = cookie de sessao: " + cookies);

            assertDoesNotThrow(hook::run, "rodar o gancho com o pool ja fechado nao pode falhar");
        } finally {
            SessionImporter.ganchoSaida = ganchoOriginal;
            SessionImporter.fabricaPool = fabricaOriginal;
            matar(proc);
        }
    }

    /** Mata o caminho de marcador NAO encontrado (journal com "" e texto exato de logada). */
    @Test
    void marcadorAusenteRegistraVazioERetornaTextoDeLogada() throws Exception {
        Path pagina = tmp.resolve("pagina-neutra.html");
        Files.writeString(pagina, "<html><body>Conteudo da pagina de teste</body></html>", StandardCharsets.UTF_8);
        Store s = storeComRede("neutra", pagina);
        Path perfil = perfilDaRede(s);
        Path chrome = acharChrome();
        Assumptions.assumeTrue(chrome != null, "sem binario Chrome/Chromium");
        exigirPreset(s, perfil);
        int porta = portaLivre();
        Process proc = subirChrome(chrome, perfil, porta);

        Consumer<Thread> ganchoOriginal = SessionImporter.ganchoSaida;
        try {
            SessionImporter.ganchoSaida = t -> { }; // nao registra hook real no Runtime
            String arq = arquivoCookies(tmp, "cookies-neutro.json",
                    "[{\"name\":\"ck\",\"value\":\"1\",\"domain\":\"" + DOMINIO + "\"}]");
            String retorno = SessionImporter.run(s, REDE, arq);

            assertTrue(retorno.startsWith("rede=" + REDE
                    + " | cookies aplicados=1 falhas=0"
                    + " | marcadorLogin=NAO encontrado (provavelmente LOGADA — confirme no screenshot)"
                    + " | screenshot="), retorno);
            assertTrue(retorno.endsWith(".png"), retorno);
            assertEquals("", ultimaLinhaDoJournal(s).path("marcadorLogin").asText(),
                    "sem marcador, o journal grava string vazia (nunca null)");
        } finally {
            SessionImporter.ganchoSaida = ganchoOriginal;
            matar(proc);
        }
    }

    // ---------- fixture ----------

    private Store storeComRede(String nome, Path pagina) throws Exception {
        Path home = tmp.resolve(nome + "-" + System.nanoTime());
        Files.createDirectories(home);
        ObjectNode cfg = Json.obj();
        ObjectNode defs = cfg.putObject("defaults");
        defs.put("profileWaitSeconds", 2);
        defs.put("screenshotRetentionDays", 0);
        ObjectNode rede = cfg.putObject("networks").putObject(REDE);
        rede.put("startUrl", pagina.toUri().toString());
        rede.put("attachExistingChrome", true);
        Files.writeString(home.resolve("config.json"), Json.write(cfg), StandardCharsets.UTF_8);
        return new Store(home);
    }

    private static Path perfilDaRede(Store s) throws Exception {
        Path perfil = s.profilesDir().resolve("sdk-" + REDE);
        Files.createDirectories(perfil);
        return perfil;
    }

    /** Mesma guarda de BrowserPoolOptionsTest: cgroup sem folga impede montar o preset. */
    private static void exigirPreset(Store s, Path perfil) {
        try {
            BrowserPool.buildAttachOptions(REDE, s.networkCfg(REDE), perfil.toString(), "127.0.0.1:1");
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            Assumptions.assumeFalse(m.contains("cgroup") && m.contains("memory.max"),
                    "ambiente com limite de memoria do cgroup: " + m);
            throw e;
        }
    }

    private static Path acharChrome() {
        for (String cand : List.of("/opt/google/chrome/chrome", "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            if (Files.isExecutable(Path.of(cand))) return Path.of(cand);
        }
        return null;
    }

    private static int portaLivre() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Copia do fixture headless de NavGuardMutantesTest (attach e o unico caminho sem janela). */
    private static Process subirChrome(Path chrome, Path perfil, int porta) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/setpriv", "--pdeathsig", "TERM", chrome.toString(),
                "--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=" + porta, "--user-data-dir=" + perfil, "about:blank")
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
        if (!pronto) {
            matar(p);
            fail("Chrome headless nao abriu a porta de depuracao");
        }
        return p;
    }

    private static void matar(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonNode cookie(JsonNode resposta, String nome) {
        for (JsonNode c : resposta.path("cookies")) {
            if (nome.equals(c.path("name").asText())) return c;
        }
        return fail("cookie ausente: " + nome + " em " + resposta);
    }

    private static JsonNode ultimaLinhaDoJournal(Store s) throws Exception {
        List<String> linhas = Files.readAllLines(s.journalPath(), StandardCharsets.UTF_8);
        assertFalse(linhas.isEmpty(), "journal vazio: session_import nao foi gravado");
        return Json.parse(linhas.get(linhas.size() - 1));
    }

    /**
     * 5 cookies validos (cada campo opcional uma vez) + 1 invalido (sem name) para falha++.
     * ck_sessao nao tem expires: exp == 0 nao pode virar expires=0 no Chrome.
     */
    private static String cookiesDoRun(long expiraEm) {
        return """
                [
                  {"name":"ck_secure","value":"1","domain":"%1$s","secure":true},
                  {"name":"ck_http","value":"1","domain":"%1$s","httpOnly":true},
                  {"name":"ck_samesite","value":"1","domain":"%1$s","sameSite":"Strict"},
                  {"name":"ck_expira","value":"1","domain":"%1$s","expires":%2$d},
                  {"name":"ck_sessao","value":"1","domain":"%1$s"},
                  {"value":"sem-nome","domain":"%1$s"}
                ]
                """.formatted(DOMINIO, expiraEm);
    }
}
