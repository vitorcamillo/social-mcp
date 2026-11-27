package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SessionCheckTest {

    @TempDir
    Path tmp;

    private Store store;
    private SessionCheck.LeitorCookies leitorOriginal;
    private Clock relogioOriginal;
    private ApprovalCli.Navegador navegadorOriginal;

    private static final Instant AGORA = Instant.parse("2026-06-01T00:00:00Z");

    @BeforeEach
    void montar() {
        store = new Store(tmp.resolve("home-" + System.nanoTime()));
        leitorOriginal = SessionCheck.leitor;
        relogioOriginal = SessionCheck.relogio;
        navegadorOriginal = ApprovalCli.navegador;
        SessionCheck.relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
    }

    @AfterEach
    void restaurar() {
        SessionCheck.leitor = leitorOriginal;
        SessionCheck.relogio = relogioOriginal;
        ApprovalCli.navegador = navegadorOriginal;
    }

    /** Microssegundos desde 1601-01-01T00:00Z ate a data (meia-noite UTC). */
    private static long micros(LocalDate data) {
        return ChronoUnit.MICROS.between(
                Instant.parse("1601-01-01T00:00:00Z"), data.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** Cria perfil + Default/Cookies vazio (o leitor injetado ignora o conteudo). */
    private Path perfilComCookies(String rede) throws Exception {
        Path perfil = store.profilesDir().resolve("sdk-" + rede);
        Files.createDirectories(perfil.resolve("Default"));
        Path cookies = perfil.resolve("Default").resolve("Cookies");
        if (!Files.exists(cookies)) {
            Files.createFile(cookies);
        }
        return perfil;
    }

    private static SessionCheck.Cookie cookie(String host, String nome, LocalDate validade) {
        return new SessionCheck.Cookie(host, nome, validade == null ? 0L : micros(validade));
    }

    @Test
    void facebookLogadoMostraMenorValidade() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".facebook.com", "c_user", LocalDate.of(2027, 11, 26)),
                cookie(".facebook.com", "xs", LocalDate.of(2028, 5, 1)));
        assertEquals("facebook    logado (valido ate 2027-11-26)",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void cifraDoChaveiroPedeRelogin() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> List.of(
                new SessionCheck.Cookie(".facebook.com", "c_user", micros(LocalDate.of(2027, 11, 26)), "v11"),
                new SessionCheck.Cookie(".facebook.com", "xs", micros(LocalDate.of(2028, 5, 1)), "v10"));
        assertEquals("facebook    deslogado para o MCP (cookies cifrados pelo chaveiro: relogue com --cli login facebook)",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void estadoSemPrefixo() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".facebook.com", "c_user", LocalDate.of(2027, 11, 26)),
                cookie(".facebook.com", "xs", LocalDate.of(2028, 5, 1)));
        String comPrefixo = SessionCheck.verificar(store, "facebook");
        assertEquals("facebook    logado (valido ate 2027-11-26)", comPrefixo);
        assertEquals(comPrefixo.substring("facebook    ".length()), SessionCheck.estado(store, "facebook"));
    }

    @Test
    void cookiesDeSessaoLogadoSemData() throws Exception {
        perfilComCookies("instagram");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".instagram.com", "sessionid", null),
                cookie(".instagram.com", "ds_user_id", null));
        assertEquals("instagram   logado (cookie de sessao)",
                SessionCheck.verificar(store, "instagram"));
    }

    @Test
    void facebookSoComDatrDeslogadoFaltandoOsDois() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".facebook.com", "datr", LocalDate.of(2028, 1, 1)));
        assertEquals("facebook    deslogado (falta c_user, xs)",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void xsVencidoContaComoFalta() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".facebook.com", "c_user", LocalDate.of(2027, 11, 26)),
                cookie(".facebook.com", "xs", LocalDate.of(2020, 1, 1)));
        assertEquals("facebook    deslogado (falta xs)",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void xSoCasaSufixoExato() throws Exception {
        perfilComCookies("x");
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".a-mx.com", "auth_token", LocalDate.of(2027, 1, 1)),
                cookie(".a-mx.com", "twid", LocalDate.of(2027, 1, 1)));
        assertEquals(String.format("%-11s ", "x") + "deslogado (falta auth_token, twid)",
                SessionCheck.verificar(store, "x"));
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".x.com", "auth_token", LocalDate.of(2027, 3, 4)),
                cookie("x.com", "twid", LocalDate.of(2027, 3, 4)));
        assertEquals(String.format("%-11s ", "x") + "logado (valido ate 2027-03-04)",
                SessionCheck.verificar(store, "x"));
    }

    @Test
    void pinterestNaoVerificavelSemChamarLeitor() {
        SessionCheck.leitor = arquivo -> {
            throw new AssertionError("leitor nao deve ser chamado para rede fora do mapa");
        };
        assertEquals("pinterest   nao verificavel por cookie",
                SessionCheck.verificar(store, "pinterest"));
    }

    @Test
    void perfilInexistenteESemCookies() throws Exception {
        assertEquals("facebook    sem perfil", SessionCheck.verificar(store, "facebook"));
        Files.createDirectories(store.profilesDir().resolve("sdk-facebook"));
        assertEquals("facebook    deslogado (sem cookies)", SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void singletonLockAcrescentaAviso() throws Exception {
        Path perfil = perfilComCookies("facebook");
        Files.createFile(perfil.resolve("SingletonLock"));
        SessionCheck.leitor = arquivo -> List.of(
                cookie(".facebook.com", "c_user", LocalDate.of(2027, 11, 26)),
                cookie(".facebook.com", "xs", LocalDate.of(2027, 11, 26)));
        assertEquals("facebook    logado (valido ate 2027-11-26) [perfil aberto: feche o Chrome antes da rodada]",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void lockDePidMortoNaoContaComoAberto() throws Exception {
        Path perfil = Files.createDirectories(store.profilesDir().resolve("sdk-x"));
        Path lock = perfil.resolve("SingletonLock");
        long morto = Long.MAX_VALUE - 1;
        Files.createSymbolicLink(lock, Path.of(SessionCheck.hostname() + "-" + morto));
        assertFalse(SessionCheck.perfilAberto(perfil));
        Files.delete(lock);
        Files.createSymbolicLink(lock, Path.of(SessionCheck.hostname() + "-" + ProcessHandle.current().pid()));
        assertTrue(SessionCheck.perfilAberto(perfil));
        Files.delete(lock);
        Files.createSymbolicLink(lock, Path.of("outro-host-" + morto));
        assertTrue(SessionCheck.perfilAberto(perfil), "outro host: conservador");
        Files.delete(lock);
        assertFalse(SessionCheck.perfilAberto(perfil));
    }

    @Test
    void erroDoLeitorViraLinhaSemStack() throws Exception {
        perfilComCookies("facebook");
        SessionCheck.leitor = arquivo -> {
            throw new IllegalStateException("x");
        };
        assertEquals("facebook    erro ao ler cookies: x",
                SessionCheck.verificar(store, "facebook"));
    }

    @Test
    void loginAbreChromeDoPerfilComStartUrl() throws Exception {
        List<Path> perfis = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<String> proxies = new ArrayList<>();
        ApprovalCli.navegador = (perfil, url, proxy) -> {
            perfis.add(perfil);
            urls.add(url);
            proxies.add(proxy);
        };
        SessionCheck.leitor = arquivo -> List.of();
        assertEquals(0, ApprovalCli.run(new String[]{"login", "facebook"}, store));
        assertEquals(List.of(store.profilesDir().resolve("sdk-facebook")), perfis);
        assertEquals(List.of("https://www.facebook.com/"), urls);
        assertEquals(List.of(""), proxies);
    }

    @Test
    void loginPassaProxyDaRede() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode raiz = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Files.readString(store.configPath(), java.nio.charset.StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raiz.path("networks").path("facebook")).put("proxy", "10.0.0.1:8080");
        Files.writeString(store.configPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raiz),
                java.nio.charset.StandardCharsets.UTF_8);
        List<String> proxies = new ArrayList<>();
        ApprovalCli.navegador = (perfil, url, proxy) -> {
            proxies.add(proxy);
        };
        SessionCheck.leitor = arquivo -> List.of();
        assertEquals(0, ApprovalCli.run(new String[]{"login", "facebook"}, store));
        assertEquals(List.of("10.0.0.1:8080"), proxies);
    }

    @Test
    void comandoChromeComESemProxy() {
        assertEquals(
                List.of("google-chrome", "--user-data-dir=/p", "--password-store=basic",
                        "--proxy-server=http://10.0.0.1:8080/", "https://a/"),
                ApprovalCli.comandoChrome(Path.of("/p"), "https://a/", "http://10.0.0.1:8080/"));
        assertEquals(
                List.of("google-chrome", "--user-data-dir=/p", "--password-store=basic"),
                ApprovalCli.comandoChrome(Path.of("/p"), "", null));
    }

    @Test
    void loginComPerfilAbertoNaoChamaNavegador() throws Exception {
        Path perfil = perfilComCookies("facebook");
        Files.createFile(perfil.resolve("SingletonLock"));
        ApprovalCli.navegador = (p, u, x) -> {
            throw new AssertionError("navegador nao deve ser chamado com perfil aberto");
        };
        assertEquals(1, ApprovalCli.run(new String[]{"login", "facebook"}, store));
    }

    @Test
    void loginSemRedeERedeDesconhecida() {
        ApprovalCli.navegador = (p, u, x) -> {
            throw new AssertionError("navegador nao deve ser chamado sem rede valida");
        };
        assertEquals(1, ApprovalCli.run(new String[]{"login"}, store));
        assertEquals(1, ApprovalCli.run(new String[]{"login", "nada"}, store));
    }

    @Test
    void lerViaPythonLeCopiaSemValorELimpaTemp() throws Exception {
        Path dir = tmp.resolve("perfil");
        Files.createDirectories(dir);
        Path banco = dir.resolve("Cookies");
        String criacao = "import sqlite3, sys\n"
                + "con = sqlite3.connect(sys.argv[1])\n"
                + "con.execute(\"create table cookies(host_key text, name text, expires_utc integer, value text, encrypted_value blob)\")\n"
                + "con.execute(\"insert into cookies values(?,?,?,?,?)\","
                + " ('.facebook.com', 'c_user', 13400000000000000, 'SEGREDO123', b\"v11\" + b\"SEGREDO123\"))\n"
                + "con.commit()\n"
                + "con.close()\n";
        Process criador = new ProcessBuilder("python3", "-I", "-c", criacao, banco.toString()).start();
        assertTrue(criador.waitFor(30, TimeUnit.SECONDS), "python3 deve criar o sqlite de teste");
        assertEquals(0, criador.exitValue());

        Set<String> antes = temporariosSessao();
        List<SessionCheck.Cookie> lidos = SessionCheck.lerViaPython(banco);
        Set<String> depois = temporariosSessao();

        assertEquals(1, lidos.size());
        assertEquals(".facebook.com", lidos.get(0).host());
        assertEquals("c_user", lidos.get(0).name());
        assertEquals(13400000000000000L, lidos.get(0).expiresUtc());
        assertEquals("v11", lidos.get(0).cifra());
        for (SessionCheck.Cookie c : lidos) {
            assertFalse(c.host().contains("SEGREDO123"));
            assertFalse(c.name().contains("SEGREDO123"));
            assertFalse(Long.toString(c.expiresUtc()).contains("SEGREDO123"));
            assertFalse(c.cifra().contains("SEGREDO123"));
        }
        assertEquals(antes, depois, "diretorio temporario de copia deve ser apagado");
    }

    private static Set<String> temporariosSessao() throws Exception {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> todos = Files.list(base)) {
            Set<String> nomes = new HashSet<>();
            todos.filter(p -> p.getFileName().toString().startsWith("social-sessao-"))
                    .forEach(p -> nomes.add(p.getFileName().toString()));
            return nomes;
        }
    }

    @Test
    void relatorioCobreTodasAsRedesNaOrdem() {
        SessionCheck.leitor = arquivo -> {
            throw new IllegalStateException("nao deve ler sem perfil");
        };
        String relatorio = SessionCheck.relatorio(store);
        List<String> linhas = relatorio.lines().toList();
        assertEquals(new ArrayList<>(store.knownNetworks()), linhas.stream()
                .map(l -> l.replaceFirst(" +.*", "")).toList());
        assertTrue(relatorio.endsWith("\n"));
        assertTrue(linhas.stream().anyMatch(l -> l.contains("nao verificavel por cookie")));
    }
}
