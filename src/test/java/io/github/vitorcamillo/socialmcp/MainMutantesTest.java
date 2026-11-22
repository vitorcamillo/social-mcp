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
 * Testes de mutantes de {@link Main}: o despacho {@code --cli} (CLI do aprovador) e o
 * caminho padrao (servidor MCP stdio), com asserção do texto/estado observável.
 *
 * <p>O caminho MCP e o {@code --cli} com argumentos terminam em {@link System#exit(int)},
 * que mataria a JVM de teste. Por isso o caminho MCP sem argumentos é exercitado em
 * processo (a entrada lança {@link IOException} antes do fim) e o caminho {@code --cli}
 * em processo filho, que sobrevive ao {@code System.exit}.</p>
 */
class MainMutantesTest {

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

    private static List<String> linhas(String texto) {
        return texto.lines().filter(l -> !l.isBlank()).toList();
    }

    /** Roda {@link Main#main} em processo filho (sobrevive ao System.exit do caminho --cli/MCP). */
    private Resultado rodarMain(Path home, String entrada, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> comando = new ArrayList<>(List.of(
                java, "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        comando.addAll(List.of(args));
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
        assertTrue(terminou, "o processo do Main nao terminou: " + texto);
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

    // ---------- caminho padrao: servidor MCP ----------

    /**
     * Sem argumentos (args.length == 0) o Main delega ao McpServer: a requisicao vinda do
     * stdin vira resposta JSON-RPC. Também mata a fronteira {@code args.length > 0} (que
     * passaria a acessar args[0] com zero argumentos) e a negação do if inteiro.
     */
    @Test
    void semArgsDelegaAoMcpServerELeDoStdin() {
        System.setIn(new EntradaQueFalhaNoFim(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n", FIM_SIMULADO));

        IOException erro = assertThrows(IOException.class, () -> Main.main(new String[0]));

        assertTrue(erro.getMessage().contains(FIM_SIMULADO), erro.getMessage());
        List<String> l = linhas(saida.toString(StandardCharsets.UTF_8));
        assertEquals(1, l.size(), saida.toString(StandardCharsets.UTF_8));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", l.get(0));
    }

    @Test
    void semArgsEmProcessoFilhoRespondePingEEncerraComZero() throws Exception {
        Resultado r = rodarMain(tmp.resolve("home-mcp"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n");

        assertEquals(0, r.exitCode(), r.saida());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", r.saida().trim());
    }

    /**
     * Argumento que nao e "--cli" continua indo para o McpServer (com o if negado, cairia
     * na CLI de aprovacao e imprimiria o uso em vez da resposta JSON-RPC).
     */
    @Test
    void argumentoDesconhecidoTambemIniciaMcpServer() throws Exception {
        Resultado r = rodarMain(tmp.resolve("home-arg"),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n", "foo");

        assertEquals(0, r.exitCode(), r.saida());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}", r.saida().trim());
    }

    // ---------- caminho --cli: ApprovalCli ----------

    /**
     * {@code --cli status} precisa montar {@code rest} com arraycopy e chamar ApprovalCli:
     * a saida e o status do home do processo. Cobre subtracao do tamanho, arraycopy,
     * chamada de ApprovalCli.run e a comparacao com "--cli".
     */
    @Test
    void cliStatusExecutaApprovalCliEImprimeStatus() throws Exception {
        Path home = tmp.resolve("home-cli-status");

        Resultado r = rodarMain(home, "", "--cli", "status");

        assertEquals(0, r.exitCode(), r.saida());
        assertTrue(r.saida().contains("kill-switch: inativo"), r.saida());
        assertTrue(r.saida().contains("home: " + home.toAbsolutePath().normalize()), r.saida());
        assertTrue(r.saida().contains("REDE"), r.saida());
    }

    @Test
    void cliRepassaOsArgumentosSemOPrefixoParaOAprovador() throws Exception {
        java.util.function.Supplier<Path> homeOriginal = ApprovalCli.homePadrao;
        java.util.function.IntConsumer saidaOriginal = ApprovalCli.saida;
        List<Integer> codigos = new ArrayList<>();
        try {
            ApprovalCli.homePadrao = () -> tmp.resolve("home-cli");
            ApprovalCli.saida = codigos::add;
            Main.main(new String[]{"--cli", "stop"});
            assertEquals(List.of(1), codigos, "stop sem on|off sai com 1");
            assertTrue(saida.toString(StandardCharsets.UTF_8).contains("uso: stop on|off"), saida.toString(StandardCharsets.UTF_8));
            Main.main(new String[]{"--cli"});
            assertEquals(List.of(1), codigos, "--cli sozinho so mostra o uso, sem exit");
        } finally {
            ApprovalCli.homePadrao = homeOriginal;
            ApprovalCli.saida = saidaOriginal;
        }
    }
}
