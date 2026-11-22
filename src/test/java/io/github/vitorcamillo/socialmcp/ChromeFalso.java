package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Assumptions;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chrome REAL headless servindo uma rede falsa local: https://bsky.app/ aponta para um
 * HttpsServer com certificado autoassinado. O BrowserPool/Tools anexam nesse Chrome pelo
 * profile exato, entao os testes exercitam a navegacao sem internet.
 */
final class ChromeFalso implements AutoCloseable {
    static final String REDE = "bluesky";
    static final String HOST = "bsky.app";
    private static final Path CHROME = Path.of("/usr/bin/google-chrome");
    private static final Path SETPRIV = Path.of("/usr/bin/setpriv");
    private static final long ESPERA_CHROME_MS = 20_000;

    /** Pedido recebido pelo servidor HTTPS local. */
    record Pedido(String metodo, String caminho, String corpo) {
    }

    /** Resposta cadastrada para um caminho (sem query). */
    record Resposta(int status, String contentType, String corpo) {
    }

    private final HttpsServer servidor;
    private final ExecutorService executor;
    private final Process processo;
    private final int portaHttps;
    private final int portaDebug;
    private final Map<String, Resposta> paginas;
    private final List<Pedido> pedidos;
    private boolean fechado;

    private ChromeFalso(HttpsServer servidor, ExecutorService executor, Process processo,
                        int portaHttps, int portaDebug,
                        Map<String, Resposta> paginas, List<Pedido> pedidos) {
        this.servidor = servidor;
        this.executor = executor;
        this.processo = processo;
        this.portaHttps = portaHttps;
        this.portaDebug = portaDebug;
        this.paginas = paginas;
        this.pedidos = pedidos;
    }

    static ChromeFalso abrir(Path tmp, Store store) throws Exception {
        if (!Files.isExecutable(CHROME)) {
            Assumptions.abort("google-chrome ausente");
        }

        Path keystore = tmp.resolve("falso.p12");
        String senha = senhaAleatoria();
        gerarCertificado(keystore, senha);

        Map<String, Resposta> paginas = new ConcurrentHashMap<>();
        List<Pedido> pedidos = Collections.synchronizedList(new ArrayList<>());
        HttpsServer servidor = null;
        ExecutorService executor = null;
        Process processo = null;
        try {
            SSLContext ssl = contexto(keystore, senha);
            servidor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setHttpsConfigurator(new HttpsConfigurator(ssl));
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "chrome-falso-https");
                t.setDaemon(true);
                return t;
            });
            servidor.setExecutor(executor);
            HttpsServer srv = servidor;
            srv.createContext("/", exchange -> atender(exchange, paginas, pedidos));
            srv.start();
            int portaHttps = srv.getAddress().getPort();

            Path dir = store.home.resolve(".javadriverless_profiles").resolve("sdk-" + REDE);
            Files.createDirectories(dir);
            int portaDebug = portaLivre();

            List<String> cmd = new ArrayList<>();
            if (Files.isExecutable(SETPRIV)) {
                cmd.add(SETPRIV.toString());
                cmd.add("--pdeathsig");
                cmd.add("TERM");
            }
            cmd.add(CHROME.toString());
            cmd.add("--headless=new");
            cmd.add("--no-sandbox");
            cmd.add("--disable-gpu");
            cmd.add("--no-first-run");
            cmd.add("--no-default-browser-check");
            cmd.add("--remote-debugging-port=" + portaDebug);
            cmd.add("--user-data-dir=" + dir);
            cmd.add("--host-resolver-rules=MAP " + HOST + " 127.0.0.1:" + portaHttps);
            cmd.add("--ignore-certificate-errors");
            cmd.add("about:blank");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            processo = pb.start();

            if (!esperarPorta(portaDebug, ESPERA_CHROME_MS)) {
                throw new IllegalStateException("Chrome falso nao abriu a porta de debug "
                        + portaDebug + " em " + (ESPERA_CHROME_MS / 1000) + " s");
            }

            // Tools/BrowserPool so anexam a um Chrome externo com opt-in explicito.
            ObjectNode cfg = (ObjectNode) store.config();
            ((ObjectNode) cfg.path("networks").path(REDE)).put("attachExistingChrome", true);
            Files.writeString(store.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);

            return new ChromeFalso(srv, executor, processo, portaHttps, portaDebug, paginas, pedidos);
        } catch (Exception | Error e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            encerrar(servidor, executor, processo);
            throw e;
        }
    }

    void pagina(String caminho, String html) {
        resposta(caminho, 200, "text/html; charset=utf-8", html);
    }

    void resposta(String caminho, int status, String contentType, String corpo) {
        paginas.put(chave(caminho), new Resposta(status, contentType, corpo));
    }

    List<Pedido> pedidos() {
        synchronized (pedidos) {
            return new ArrayList<>(pedidos);
        }
    }

    void limparPedidos() {
        synchronized (pedidos) {
            pedidos.clear();
        }
    }

    int portaHttps() {
        return portaHttps;
    }

    int portaDebug() {
        return portaDebug;
    }

    @Override
    public synchronized void close() {
        if (fechado) return;
        fechado = true;
        encerrar(servidor, executor, processo);
    }

    private static void atender(com.sun.net.httpserver.HttpExchange exchange,
                                Map<String, Resposta> paginas, List<Pedido> pedidos) throws IOException {
        try {
            String caminhoBase = exchange.getRequestURI().getRawPath();
            if (caminhoBase == null || caminhoBase.isEmpty()) caminhoBase = "/";
            String query = exchange.getRequestURI().getRawQuery();
            String caminho = query == null || query.isEmpty() ? caminhoBase : caminhoBase + "?" + query;
            byte[] corpo = exchange.getRequestBody().readAllBytes();
            pedidos.add(new Pedido(exchange.getRequestMethod(), caminho,
                    new String(corpo, StandardCharsets.UTF_8)));

            Resposta resposta = paginas.get(caminhoBase);
            if (resposta == null) {
                resposta = new Resposta(404, "text/plain; charset=utf-8", "nao achei");
            }
            byte[] bytes = resposta.corpo().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", resposta.contentType());
            exchange.sendResponseHeaders(resposta.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static void gerarCertificado(Path keystore, String senha) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Files.deleteIfExists(keystore);
        ProcessBuilder pb = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", senha,
                "-keypass", senha,
                "-alias", "s",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-dname", "CN=" + HOST,
                "-ext", "SAN=dns:" + HOST,
                "-validity", "2");
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("keytool nao terminou em 60 s");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("keytool falhou (exit " + p.exitValue()
                    + ") ao gerar o certificado de " + HOST);
        }
    }

    private static SSLContext contexto(Path keystore, String senha) throws Exception {
        char[] pw = senha.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    private static String senhaAleatoria() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static int portaLivre() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean esperarPorta(int porta, long timeoutMs) throws InterruptedException {
        long fim = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < fim) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", porta), 200);
                return true;
            } catch (IOException ignored) {
                // Chrome ainda subindo
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String chave(String caminho) {
        int q = caminho.indexOf('?');
        String p = q >= 0 ? caminho.substring(0, q) : caminho;
        return p.isEmpty() ? "/" : p;
    }

    private static void encerrar(HttpsServer servidor, ExecutorService executor, Process processo) {
        if (processo != null) {
            try {
                processo.descendants().forEach(h -> h.destroyForcibly());
            } catch (RuntimeException ignored) {
                // processo ja morreu
            }
            try {
                processo.destroyForcibly();
            } catch (RuntimeException ignored) {
                // processo ja morreu
            }
            try {
                processo.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // processo ja morreu
            }
        }
        if (servidor != null) {
            try {
                servidor.stop(0);
            } catch (RuntimeException ignored) {
                // servidor ja parado
            }
        }
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (RuntimeException ignored) {
                // executor ja encerrado
            }
        }
    }
}
