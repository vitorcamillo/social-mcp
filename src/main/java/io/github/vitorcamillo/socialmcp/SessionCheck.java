package io.github.vitorcamillo.socialmcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Confere se ha sessao logada por rede lendo so host/nome/validade dos cookies
 * do profile do Chrome. Nunca le nem imprime o VALOR de cookie.
 */
final class SessionCheck {
    private SessionCheck() {}

    /** expiresUtc em microssegundos desde 1601-01-01 (0 = cookie de sessao). cifra: so etiqueta v10/v11 ou vazio. */
    record Cookie(String host, String name, long expiresUtc, String cifra) {
        Cookie(String host, String name, long expiresUtc) {
            this(host, name, expiresUtc, "");
        }
    }

    /** Recebe o Cookies ORIGINAL; a impl padrao copia e le a copia. */
    interface LeitorCookies {
        List<Cookie> ler(Path arquivoCookies) throws Exception;
    }

    static LeitorCookies leitor = SessionCheck::lerViaPython;
    static Clock relogio = Clock.systemUTC();

    private record Regra(String sufixo, List<String> nomes) {}

    private static final Map<String, Regra> REGRAS = Map.of(
            "facebook", new Regra("facebook.com", List.of("c_user", "xs")),
            "instagram", new Regra("instagram.com", List.of("sessionid", "ds_user_id")),
            "threads", new Regra("threads.com", List.of("sessionid", "ds_user_id")),
            "x", new Regra("x.com", List.of("auth_token", "twid")),
            "youtube", new Regra("youtube.com", List.of("SID", "LOGIN_INFO")),
            "reddit", new Regra("reddit.com", List.of("reddit_session")),
            "tiktok", new Regra("tiktok.com", List.of("sessionid")));

    private static final Instant BASE_CHROME = Instant.parse("1601-01-01T00:00:00Z");
    private static final DateTimeFormatter FORMATO_DATA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final String AVISO_ABERTO = " [perfil aberto: feche o Chrome antes da rodada]";

    /**
     * Le so host_key/name/expires_utc e a etiqueta de versao da cifra; o corpo nunca menciona
     * value/encrypted_value. Do valor cifrado sai SOMENTE a etiqueta de versao (v10, v11 ou vazio),
     * nunca o valor.
     */
    static final String SCRIPT_PYTHON = """
            import sqlite3
            import sys
            con = sqlite3.connect(sys.argv[1])
            try:
                cur = con.execute("select host_key, name, expires_utc, substr(encrypted_value, 1, 3) from cookies")
                for host, name, exp, ev in cur:
                    etq = bytes(ev or b"")[:3]
                    cifra = etq.decode("ascii") if etq in (b"v10", b"v11") else ""
                    sys.stdout.write(str(host) + "\\t" + str(name) + "\\t" + str(int(exp)) + "\\t" + cifra + "\\n")
            finally:
                con.close()
            """;

    /** Uma linha de relatorio da rede, com prefixo de 12 colunas ("rede       "). */
    static String verificar(Store store, String rede) {
        return String.format("%-11s ", rede) + estado(store, rede);
    }

    /** O texto da linha de relatorio sem o prefixo da rede. */
    static String estado(Store store, String rede) {
        String prefixo = "";
        Path perfil = store.profilesDir().resolve("sdk-" + rede);
        boolean aberto = perfilAberto(perfil);
        String fim = aberto ? AVISO_ABERTO : "";
        Regra regra = REGRAS.get(rede);
        if (regra == null) {
            return prefixo + "nao verificavel por cookie" + fim;
        }
        if (!Files.isDirectory(perfil, LinkOption.NOFOLLOW_LINKS)) {
            return prefixo + "sem perfil" + fim;
        }
        Path cookies = perfil.resolve("Default").resolve("Cookies");
        if (!Files.isRegularFile(cookies, LinkOption.NOFOLLOW_LINKS)) {
            return prefixo + "deslogado (sem cookies)" + fim;
        }
        List<Cookie> lidos;
        try {
            lidos = leitor.ler(cookies);
        } catch (Exception e) {
            return prefixo + "erro ao ler cookies: " + mensagemCurta(e) + fim;
        }
        Instant agora = Instant.now(relogio);
        List<String> faltando = new ArrayList<>();
        List<Long> validades = new ArrayList<>();
        List<Cookie> achados = new ArrayList<>();
        for (String nome : regra.nomes()) {
            Cookie achado = null;
            for (Cookie c : lidos) {
                if (c.name().equals(nome) && casaHost(c.host(), regra.sufixo()) && !vencido(c, agora)) {
                    achado = c;
                    break;
                }
            }
            if (achado == null) {
                faltando.add(nome);
            } else {
                validades.add(achado.expiresUtc());
                achados.add(achado);
            }
        }
        if (!faltando.isEmpty()) {
            return prefixo + "deslogado (falta " + String.join(", ", faltando) + ")" + fim;
        }
        for (Cookie achado : achados) {
            if ("v11".equals(achado.cifra())) {
                return prefixo + "deslogado para o MCP (cookies cifrados pelo chaveiro: relogue com --cli login "
                        + rede + ")" + fim;
            }
        }
        List<Long> comPrazo = validades.stream().filter(e -> e != 0L).toList();
        if (comPrazo.isEmpty()) {
            return prefixo + "logado (cookie de sessao)" + fim;
        }
        long menor = comPrazo.stream().mapToLong(Long::longValue).min().orElse(0L);
        return prefixo + "logado (valido ate " + FORMATO_DATA.format(microsParaInstant(menor)) + ")" + fim;
    }

    /**
     * Perfil em uso por um Chrome vivo. O SingletonLock do Chrome e symlink "host-pid"; lock de
     * PID morto neste host e sobra de Chrome encerrado (o proprio Chrome o reaproveita), entao
     * nao conta. Lock ilegivel ou de outro host: conservador, conta como aberto.
     */
    static boolean perfilAberto(Path perfil) {
        Path lock = perfil.resolve("SingletonLock");
        if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            String alvo = Files.readSymbolicLink(lock).toString();
            int i = alvo.lastIndexOf('-');
            long pid = Long.parseLong(alvo.substring(i + 1));
            if (!alvo.substring(0, i).equals(hostname())) {
                return true;
            }
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return true;
        }
    }

    static String hostname() throws IOException {
        return Files.readString(Path.of("/proc/sys/kernel/hostname"), StandardCharsets.UTF_8).trim();
    }

    /** Uma linha por rede de store.knownNetworks(), na mesma ordem, cada uma com \n. */
    static String relatorio(Store store) {
        StringBuilder sb = new StringBuilder();
        for (String rede : store.knownNetworks()) {
            sb.append(verificar(store, rede)).append("\n");
        }
        return sb.toString();
    }

    /** Host sem ponto inicial e igual ao sufixo ou termina com "." + sufixo. */
    static boolean casaHost(String host, String sufixo) {
        if (host == null || sufixo == null) {
            return false;
        }
        String h = host.startsWith(".") ? host.substring(1) : host;
        return h.equals(sufixo) || h.endsWith("." + sufixo);
    }

    static boolean vencido(Cookie cookie, Instant agora) {
        return cookie.expiresUtc() != 0L && microsParaInstant(cookie.expiresUtc()).isBefore(agora);
    }

    static Instant microsParaInstant(long micros) {
        return BASE_CHROME.plusSeconds(micros / 1_000_000L).plusNanos((micros % 1_000_000L) * 1_000L);
    }

    /**
     * Le o Cookies ORIGINAL via copia: copia Cookies e os irmaos existentes
     * (Cookies-journal, Cookies-wal) para diretorio temporario, le a copia com
     * python3 e apaga o temporario no finally. Nunca toca no valor dos cookies.
     */
    static List<Cookie> lerViaPython(Path arquivoCookies) throws Exception {
        Path tmp = Files.createTempDirectory("social-sessao-");
        try {
            for (String nome : new String[]{"Cookies", "Cookies-journal", "Cookies-wal"}) {
                Path origem = arquivoCookies.resolveSibling(nome);
                if (Files.exists(origem, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(origem, tmp.resolve(nome), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // saida em arquivo: perfil com milhares de cookies enche o pipe (64 KB) e travaria ate o timeout
            Path out = tmp.resolve("saida.txt");
            Path err = tmp.resolve("erro.txt");
            Process p = new ProcessBuilder(
                    "python3", "-I", "-c", SCRIPT_PYTHON, tmp.resolve("Cookies").toString())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            boolean terminou = p.waitFor(15, TimeUnit.SECONDS);
            if (!terminou) {
                p.destroyForcibly();
                throw new IllegalStateException("timeout ao ler cookies");
            }
            String saida = Files.readString(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                String erro = Files.readString(err, StandardCharsets.UTF_8).trim();
                throw new IllegalStateException(erro.isEmpty()
                        ? "python3 saiu com codigo " + p.exitValue() : primeiraLinha(erro));
            }
            List<Cookie> cookies = new ArrayList<>();
            for (String linha : saida.split("\n")) {
                if (linha.isBlank()) {
                    continue;
                }
                String[] partes = linha.split("\t", -1);
                if (partes.length != 4) {
                    throw new IllegalStateException("linha invalida do leitor de cookies");
                }
                long expira;
                try {
                    expira = Long.parseLong(partes[2].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("expiracao invalida do leitor de cookies");
                }
                cookies.add(new Cookie(partes[0], partes[1], expira, partes[3]));
            }
            cookies.sort(Comparator.comparing(Cookie::host).thenComparing(Cookie::name));
            return List.copyOf(cookies);
        } finally {
            apagarRecursivo(tmp);
        }
    }

    private static String mensagemCurta(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        return primeiraLinha(msg);
    }

    private static String primeiraLinha(String texto) {
        String linha = texto.split("\n", -1)[0].trim();
        return linha.length() > 200 ? linha.substring(0, 200) : linha;
    }

    private static void apagarRecursivo(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var todos = Files.walk(dir)) {
            List<Path> ordem = todos.sorted(Comparator.reverseOrder()).toList();
            for (Path p : ordem) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // melhor esforco: nao derruba o comando por falha de limpeza
                }
            }
        } catch (IOException ignored) {
            // melhor esforco: nao derruba o comando por falha de limpeza
        }
    }
}
