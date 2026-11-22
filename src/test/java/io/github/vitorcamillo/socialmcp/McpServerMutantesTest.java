package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes de mutantes de {@link McpServer}: exercitam o servidor stdio (JSON-RPC
 * newline-delimited) asserindo a resposta exata e o encerramento do processo.
 *
 * <p>A entrada em processo entrega os bytes e depois lança {@link IOException} em vez de
 * devolver EOF: assim o teste cobre o laço de leitura e a escrita/flush da resposta sem
 * chegar ao {@code System.exit(0)} do fim de {@code run()} (que mataria a JVM de teste).
 * O encerramento real (stdin fechado) é verificado em processo filho, que sobrevive ao
 * {@code System.exit}.</p>
 */
class McpServerMutantesTest {

    private static final String FIM_SIMULADO = "fim simulado da entrada";

    @TempDir
    Path tmp;

    private InputStream inOriginal;
    private PrintStream outOriginal;
    private String homeOriginal;
    private ByteArrayOutputStream saida;

    @BeforeEach
    void prepararEntradaESaida() {
        inOriginal = System.in;
        outOriginal = System.out;
        homeOriginal = System.getProperty("user.home");
        // new Store() sem argumento usa user.home: aponta para @TempDir, nunca para o home real.
        System.setProperty("user.home", tmp.toString());
        saida = new ByteArrayOutputStream();
        System.setOut(new PrintStream(saida, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restaurarEntradaESaida() {
        System.setIn(inOriginal);
        System.setOut(outOriginal);
        if (homeOriginal == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", homeOriginal);
        }
    }

    // ---------- helpers ----------

    /**
     * Roda {@link McpServer#run()} em processo com a entrada informada. O stream lança
     * {@link IOException} ao esgotar os bytes (nunca EOF), então {@code run()} propaga a
     * falha antes de alcançar o {@code System.exit(0)} final.
     */
    private String executarEmProcesso(String entrada) {
        System.setIn(new EntradaQueFalhaNoFim(entrada, FIM_SIMULADO));
        IOException erro = assertThrows(IOException.class, McpServer::run);
        assertTrue(erro.getMessage().contains(FIM_SIMULADO), erro.getMessage());
        return saida.toString(StandardCharsets.UTF_8);
    }

    private static List<String> linhas(String texto) {
        return texto.lines().filter(l -> !l.isBlank()).toList();
    }

    /** Roda o servidor em uma JVM filha (sobrevive ao System.exit final de run()). */
    private Resultado rodarEmProcessoFilho(Class<?> principal, Path home, String entrada) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> comando = new ArrayList<>(List.of(
                java, "-cp", System.getProperty("java.class.path"), principal.getName()));
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.environment().put("SOCIAL_MCP_HOME", home.toAbsolutePath().normalize().toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try (OutputStream entradaProcesso = p.getOutputStream()) {
            entradaProcesso.write(entrada.getBytes(StandardCharsets.UTF_8));
            entradaProcesso.flush();
        }
        boolean terminou = p.waitFor(15, TimeUnit.SECONDS);
        if (!terminou) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        String texto = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(terminou, "o processo do MCP nao terminou: " + texto);
        return new Resultado(p.exitValue(), texto);
    }

    private record Resultado(int exitCode, String saida) {
    }

    /** InputStream finito que, ao esgotar, lança IOException em vez de sinalizar EOF. */
    private static final class EntradaQueFalhaNoFim extends InputStream {
        private final ByteArrayInputStream dados;
        private final String mensagem;

        EntradaQueFalhaNoFim(String conteudo, String mensagem) {
            this.dados = new ByteArrayInputStream(conteudo.getBytes(StandardCharsets.UTF_8));
            this.mensagem = mensagem;
        }

        @Override
        public int read() throws IOException {
            int b = dados.read();
            if (b < 0) throw new IOException(mensagem);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = dados.read(b, off, len);
            if (n < 0) throw new IOException(mensagem);
            return n;
        }
    }

    // ---------- resposta stdio ----------

    @Test
    void pingRespondeJsonRpcComIdEResultadoVazio() {
        String texto = executarEmProcesso("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n");

        List<String> l = linhas(texto);
        assertEquals(1, l.size(), texto);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}", l.get(0));
    }

    @Test
    void notificacaoSemIdOuComIdNuloNaoRecebeResposta() {
        String texto = executarEmProcesso("""
                {"jsonrpc":"2.0","method":"ping"}
                {"jsonrpc":"2.0","id":null,"method":"ping"}
                {"jsonrpc":"2.0","id":9,"method":"ping"}
                """);

        List<String> l = linhas(texto);
        assertEquals(1, l.size(), "so a requisicao com id deve ser respondida: " + texto);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}", l.get(0));
    }

    @Test
    void linhaVaziaEIgnoradaEInitializeRespondeProtocoloEIdentidade() {
        String texto = executarEmProcesso("\n   \n"
                + "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}\n");

        List<String> l = linhas(texto);
        assertEquals(1, l.size(), texto);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{"
                + "\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"serverInfo\":{\"name\":\"social-mcp\",\"version\":\"0.1.0\"}}}", l.get(0));
    }

    // ---------- encerramento real (stdin fechado) ----------

    @Test
    void processoFilhoRespondePingEEncerraComZero() throws Exception {
        Resultado r = rodarEmProcessoFilho(Servidor.class, tmp.resolve("home-servidor"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n");

        assertEquals(0, r.exitCode(), r.saida());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", r.saida().trim());
    }

    /**
     * Fim do stdin mata os Chromes e chama System.exit(0): mesmo com uma thread nao-daemon
     * viva (como as threads do CDP), o processo precisa encerrar. Sem o System.exit, o
     * processo ficaria preso e o teste falharia por timeout.
     */
    @Test
    void stdinFechadoEncerraProcessoMesmoComThreadNaoDaemon() throws Exception {
        Resultado r = rodarEmProcessoFilho(ServidorComThreadNaoDaemon.class, tmp.resolve("home-thread"), "");

        assertEquals(0, r.exitCode(), r.saida());
    }

    // ---------- mains dos processos filhos ----------

    /** Main do filho: chama o servidor MCP real. */
    public static final class Servidor {
        public static void main(String[] args) throws Exception {
            McpServer.run();
        }
    }

    /** Main do filho: simula uma thread nao-daemon (ex.: CDP) viva antes de rodar o servidor. */
    public static final class ServidorComThreadNaoDaemon {
        public static void main(String[] args) throws Exception {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(600_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "thread-nao-daemon-de-teste");
            thread.setDaemon(false);
            thread.start();
            McpServer.run();
        }
    }

    @Test
    void fimDaEntradaFechaAsToolsESaiComZeroEOGanchoTambemFecha() throws Exception {
        var toolsOriginal = McpServer.novasTools;
        var ganchoOriginal = McpServer.ganchoSaida;
        var saidaOriginal = McpServer.saida;
        int[] fechou = {0};
        List<Thread> ganchos = new ArrayList<>();
        List<Integer> codigos = new ArrayList<>();
        try {
            McpServer.novasTools = s -> new Tools(s) { @Override public void shutdown() { fechou[0]++; super.shutdown(); } };
            McpServer.ganchoSaida = ganchos::add;
            McpServer.saida = codigos::add;
            System.setIn(new ByteArrayInputStream(new byte[0]));
            McpServer.run();
            assertEquals(List.of(List.of(0), 1, 1), List.of(codigos, fechou[0], ganchos.size()), "sai 0, fecha tools, 1 gancho");
            ganchos.get(0).run();
            assertEquals(2, fechou[0], "o gancho tambem fecha as tools");
        } finally {
            McpServer.novasTools = toolsOriginal;
            McpServer.ganchoSaida = ganchoOriginal;
            McpServer.saida = saidaOriginal;
        }
    }
}
