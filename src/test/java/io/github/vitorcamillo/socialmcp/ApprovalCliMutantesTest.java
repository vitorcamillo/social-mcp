package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Testes de mutantes de {@link ApprovalCli}: exercitam cada comando do despachante
 * com asserção do texto/estado observável, para que as mutações de PIT sejam mortas.
 * Só comportamento atual (nenhuma trava é afrouxada): aprovação só humana/orquestrador,
 * TTY+CONFIRMO para gate sensível, kill-switch e cotas.
 */
class ApprovalCliMutantesTest {

    @TempDir
    Path tmp;

    private ApprovalCli.Terminal terminalOriginal;
    private PrintStream outOriginal;
    private PrintStream errOriginal;
    private ByteArrayOutputStream saida;
    private ByteArrayOutputStream erro;

    @BeforeEach
    void prepararCaptura() {
        terminalOriginal = ApprovalCli.terminal;
        outOriginal = System.out;
        errOriginal = System.err;
        saida = new ByteArrayOutputStream();
        erro = new ByteArrayOutputStream();
        System.setOut(new PrintStream(saida, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(erro, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restaurarCaptura() {
        ApprovalCli.terminal = terminalOriginal;
        System.setOut(outOriginal);
        System.setErr(errOriginal);
    }

    // ---------- helpers ----------

    private Store storeNovo() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    private int cli(Store s, String... args) {
        return ApprovalCli.run(args, s);
    }

    private String texto(ByteArrayOutputStream b) {
        return b.toString(StandardCharsets.UTF_8);
    }

    private String saidaTxt() {
        return texto(saida);
    }

    private String erroTxt() {
        return texto(erro);
    }

    private void limpar() {
        saida.reset();
        erro.reset();
    }

    /** Terminal falso com interatividade e resposta fixas, sem console real. */
    private static ApprovalCli.Terminal terminalFixo(boolean interativo, String resposta) {
        return new ApprovalCli.Terminal() {
            @Override public boolean interativo() { return interativo; }
            @Override public String lerLinha(String prompt) { return resposta; }
        };
    }

    private String novoDraft(Store s) throws IOException {
        BatchStore bs = new BatchStore(s);
        return bs.draft("cru", Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"goto\",\"url\":\"https://bsky.app\"}]")).get("id").asText();
    }

    private Path draftPath(Store s, String id) {
        return s.batchesDir().resolve(id + ".draft.json");
    }

    private Path aprovadoPath(Store s, String id) {
        return s.batchesDir().resolve(id + ".approved.json");
    }

    /** Gate de texto que sempre passa (sem rede): "true" ignora os argumentos e sai 0. */
    private static String[] comandoGatePassante() {
        for (String candidato : new String[]{"/usr/bin/true", "/bin/true"}) {
            if (Files.isExecutable(Path.of(candidato))) return new String[]{candidato};
        }
        return new String[]{Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version"};
    }

    private void configurarGatePassante(Store s) throws IOException {
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode gate = cfg.with("defaults").with("gate");
        var cmd = gate.putArray("command");
        for (String parte : comandoGatePassante()) cmd.add(parte);
        Files.writeString(s.configPath(), Json.writePretty(cfg));
    }

    private JsonNode entradaJournal(Store s, String tipo) {
        for (String linha : s.tail(50)) {
            if (linha == null || linha.isBlank()) continue;
            JsonNode n = Json.parse(linha);
            if (tipo.equals(n.path("type").asText())) return n;
        }
        return null;
    }

    // ---------- terminal padrao (sem TTY = agente) ----------

    @Test
    void terminalPadraoDeInteratividadeSegueSystemConsole() {
        boolean esperado = System.console() != null;
        assertEquals(esperado, ApprovalCli.terminal.interativo(),
                "o terminal padrao so e interativo quando existe console");
        if (!esperado) {
            assertFalse(ApprovalCli.terminal.interativo(),
                    "sem console (agente) o terminal padrao nao pode se declarar interativo");
        }
    }

    @Test
    void terminalPadraoLerLinhaSemConsoleDevolveNull() {
        assumeTrue(System.console() == null, "teste exige JVM sem console (sem TTY)");
        assertNull(ApprovalCli.terminal.lerLinha("prompt: "),
                "sem console, o terminal padrao devolve null (nao inventa resposta)");
        assertFalse(ApprovalCli.terminal.interativo(),
                "sem console (agente) o terminal padrao nao pode se declarar interativo");
    }

    // ---------- principal (run sem System.exit) ----------

    @Test
    void principalSemArgsMostraUsoEDevolve0SemTocarNaHome() {
        Path home = tmp.resolve("vazia");
        assertEquals(0, ApprovalCli.principal(new String[0], home), saidaTxt());
        assertTrue(saidaTxt().contains("uso: bin/social-mcp --cli <comando>"), saidaTxt());
        assertFalse(Files.exists(home), "sem args nada pode ser criado na home");
    }

    @Test
    void principalValidarReceitasComHomeIncompletaImprimeErroExatoEDevolve1() {
        Path home = tmp.resolve("incompleta");
        assertEquals(1, ApprovalCli.principal(new String[]{"validar-receitas"}, home), saidaTxt());
        assertEquals("ERRO: home do MCP incompleta (" + home + "): falta config.json ou recipes/; nada foi criado",
                saidaTxt().trim());
        assertFalse(Files.exists(home), "home incompleta nao pode ser criada pela validacao");
    }

    @Test
    void principalValidarReceitasComHomeProntaDelegaAoDespachante() throws IOException {
        Store s = storeNovo();
        Files.createDirectories(s.home.resolve("recipes"));
        assertEquals(0, ApprovalCli.principal(new String[]{"validar-receitas"}, s.home), saidaTxt());
        assertTrue(saidaTxt().contains("OK:"), saidaTxt());
    }

    @Test
    void principalComandoNormalNaoExigeHomePronta() {
        Path home = tmp.resolve("nova");
        assertEquals(0, ApprovalCli.principal(new String[]{"batches"}, home), saidaTxt());
        assertEquals("(nenhum lote)", saidaTxt().trim());
    }

    // ---------- status ----------

    @Test
    void statusMostraKillSwitchHomeECabecalho() {
        Store s = storeNovo();
        assertEquals(0, cli(s, "status"), saidaTxt());
        String out = saidaTxt();
        assertTrue(out.contains("kill-switch: inativo"), out);
        assertTrue(out.contains("home: " + s.home), out);
        assertTrue(out.contains("REDE"), out);
    }

    @Test
    void statusDistingueKillSwitchAtivoEDesativado() throws Exception {
        Store s = storeNovo();
        s.setStopped(true);
        assertEquals(0, cli(s, "status"), saidaTxt());
        assertTrue(saidaTxt().contains("kill-switch: ATIVO (STOP)"), saidaTxt());
        limpar();
        s.setStopped(false);
        assertEquals(0, cli(s, "status"), saidaTxt());
        assertTrue(saidaTxt().contains("kill-switch: inativo"), saidaTxt());
    }

    @Test
    void statusMostraNuncaEUltimaAcaoEmIso() {
        Store s = storeNovo();
        long ts = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
        s.appendJournal("action", "bluesky",
                Json.obj().put("cls", "public").put("batchId", "b-lote").put("ts", ts));
        assertEquals(0, cli(s, "status"), saidaTxt());
        String out = saidaTxt();
        assertTrue(out.contains(Instant.ofEpochMilli(ts).toString()), out);
        assertTrue(out.contains("nunca"), out);
    }

    // ---------- batches ----------

    @Test
    void batchesSemLoteMostraNenhumLote() {
        Store s = storeNovo();
        assertEquals(0, cli(s, "batches"), saidaTxt());
        assertEquals("(nenhum lote)", saidaTxt().trim());
    }

    @Test
    void batchesListaRascunhoCriado() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        assertEquals(0, cli(s, "batches"), saidaTxt());
        String out = saidaTxt();
        assertTrue(out.contains(id), out);
        assertTrue(out.contains("draft"), out);
    }

    // ---------- approve ----------

    @Test
    void approveSemBatchIdMostraUsoECodigo1() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "approve"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: approve <batchId>"), saidaTxt());
    }

    @Test
    void approveComApenasBatchIdProssegueParaOGate() throws IOException {
        Store s = storeNovo();
        configurarGatePassante(s);
        String id = novoDraft(s);
        assertEquals(0, cli(s, "approve", id), saidaTxt());
        assertTrue(saidaTxt().contains("APROVADO"), saidaTxt());
    }

    @Test
    void approveForcarSemTtyRecusaSemTocarNoLote() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        ApprovalCli.terminal = terminalFixo(false, "");
        assertEquals(2, cli(s, "approve", id, "--forcar"), saidaTxt());
        assertTrue(erroTxt().contains("RECUSADO: 'approve " + id
                + " --forcar' so pelo dono num terminal interativo (sem TTY = agente)"), erroTxt());
        assertTrue(Files.exists(draftPath(s, id)), "rascunho intocado");
        assertFalse(Files.exists(aprovadoPath(s, id)));
    }

    @Test
    void approveEnsaioSemTtyRecusa() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        ApprovalCli.terminal = terminalFixo(false, "");
        assertEquals(2, cli(s, "approve", id, "--ensaio"), saidaTxt());
        assertTrue(Files.exists(draftPath(s, id)), "rascunho intocado");
        assertFalse(Files.exists(aprovadoPath(s, id)));
    }

    @Test
    void approveFatosSemArquivoMostraErroEspecifico() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        assertEquals(1, cli(s, "approve", id, "--fatos"), saidaTxt());
        assertEquals("ERRO: --fatos exige um arquivo", saidaTxt().trim());
    }

    @Test
    void approveFatosRegistraFichaAprovada() throws IOException {
        Store s = storeNovo();
        configurarGatePassante(s);
        String id = novoDraft(s);
        Path ficha = tmp.resolve("fatos.json");
        Files.writeString(ficha, "{}");
        assertEquals(0, cli(s, "approve", id, "humano", "decisao", "--fatos", ficha.toString()), saidaTxt());
        JsonNode b = Json.parse(Files.readString(aprovadoPath(s, id)));
        assertEquals(ficha.toString(), b.path("gate").path("fichas").path(0).asText());
        assertEquals("decisao", b.path("nota").asText());
    }

    @Test
    void approveComoOrquestradorRegistraAprovador() throws IOException {
        Store s = storeNovo();
        configurarGatePassante(s);
        String id = novoDraft(s);
        assertEquals(0, cli(s, "approve", id, "orquestrador"), saidaTxt());
        assertTrue(saidaTxt().contains("APROVADO por orquestrador"), saidaTxt());
        assertEquals("orquestrador", Json.parse(Files.readString(aprovadoPath(s, id)))
                .path("aprovadoPor").asText());
    }

    @Test
    void approveHumanoComNotaGravaNota() throws IOException {
        Store s = storeNovo();
        configurarGatePassante(s);
        String id = novoDraft(s);
        assertEquals(0, cli(s, "approve", id, "humano", "minha nota"), saidaTxt());
        assertEquals("minha nota", Json.parse(Files.readString(aprovadoPath(s, id)))
                .path("nota").asText());
    }

    @Test
    void approveHumanoSemNotaNaoInventaNota() throws IOException {
        Store s = storeNovo();
        configurarGatePassante(s);
        String id = novoDraft(s);
        assertEquals(0, cli(s, "approve", id, "humano"), saidaTxt());
        assertFalse(Json.parse(Files.readString(aprovadoPath(s, id))).has("nota"));
    }

    @Test
    void approveForcadoNoTerminalRegistraComandoGateForcadoETrilha() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        ApprovalCli.terminal = terminalFixo(true, "CONFIRMO");
        assertEquals(0, cli(s, "approve", id, "humano", "--forcar"), saidaTxt());
        assertEquals("Lote " + id + " APROVADO por humano (gate FORCADO por humano). "
                + "A operadora ja pode executar com social_execute_batch.", saidaTxt().trim());

        JsonNode chamada = entradaJournal(s, "cli_chamada");
        assertNotNull(chamada, "toda chamada da CLI precisa deixar rastro");
        assertEquals("approve " + id + " --forcar", chamada.path("comando").asText());

        var pai = ProcessHandle.current().parent();
        assertEquals(pai.isPresent() ? pai.get().pid() : -1L, chamada.path("ppid").asLong());
        assertEquals(pai.isPresent() ? pai.get().info().command().orElse("") : "",
                chamada.path("paiCmd").asText());
    }

    @Test
    void approveComEnsaioRegistraEnsaioNoComandoENoLote() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        ApprovalCli.terminal = terminalFixo(true, "CONFIRMO");
        assertEquals(0, cli(s, "approve", id, "humano", "--forcar", "--ensaio"), saidaTxt());
        JsonNode chamada = entradaJournal(s, "cli_chamada");
        assertNotNull(chamada);
        assertEquals("approve " + id + " --forcar --ensaio", chamada.path("comando").asText());
        assertTrue(Json.parse(Files.readString(aprovadoPath(s, id)))
                .path("ensaioAutorizado").asBoolean());
    }

    @Test
    void terminalInterativoSemConfirmoRecusa() {
        Store s = storeNovo();
        ApprovalCli.terminal = terminalFixo(true, "nao");
        assertEquals(2, cli(s, "state", "bluesky", "healthy"), saidaTxt());
        assertTrue(erroTxt().contains("RECUSADO: 'state bluesky healthy' sem CONFIRMO"), erroTxt());
        assertFalse(s.health().has("bluesky"));
    }

    @Test
    void exigirConfirmoFalseDispensaConfirmacaoEFicaNoJournal() throws IOException {
        Store s = storeNovo();
        ObjectNode cfg = (ObjectNode) s.config();
        cfg.with("defaults").put("exigirConfirmo", false);
        Files.writeString(s.configPath(), Json.writePretty(cfg));
        assertFalse(ApprovalCli.confirmacaoExigida(s), "booleano false no config dispensa o CONFIRMO");
        ApprovalCli.terminal = terminalFixo(false, "");
        assertEquals(0, cli(s, "state", "bluesky", "healthy", "rotina"), saidaTxt());
        assertTrue(String.join("\n", s.tail(10)).contains("confirmacao_dispensada"));
    }

    // ---------- reject ----------

    @Test
    void rejectSemBatchIdMostraUso() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "reject"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: reject <batchId> [nota]"), saidaTxt());
    }

    @Test
    void rejectComNotaGravaEstadoRejeitado() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        assertEquals(0, cli(s, "reject", id, "motivo"), saidaTxt());
        assertEquals("Lote " + id + " rejeitado/revogado.", saidaTxt().trim());
        assertFalse(Files.exists(draftPath(s, id)));
        Path rejeitado = s.batchesDir().resolve(id + ".rejected.json");
        assertTrue(Files.exists(rejeitado));
        JsonNode b = Json.parse(Files.readString(rejeitado));
        assertEquals("rejected", b.path("status").asText());
        assertEquals("motivo", b.path("nota").asText());
    }

    @Test
    void rejectSemNotaNaoQuebra() throws IOException {
        Store s = storeNovo();
        String id = novoDraft(s);
        assertEquals(0, cli(s, "reject", id), saidaTxt());
        Path rejeitado = s.batchesDir().resolve(id + ".rejected.json");
        assertTrue(Files.exists(rejeitado));
        assertFalse(Json.parse(Files.readString(rejeitado)).has("nota"));
    }

    // ---------- state ----------

    @Test
    void stateSemRedeMostraUso() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "state", "bluesky"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: state <rede> <healthy|cooldown|paused|banned> [motivo]"),
                saidaTxt());
    }

    @Test
    void stateComEstadoInvalidoRecusa() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "state", "bluesky", "invalido"), saidaTxt());
        assertEquals("estado invalido: invalido", saidaTxt().trim());
        assertFalse(s.health().has("bluesky"));
    }

    @Test
    void statePausedComMotivoGravaMotivoEMensagem() throws IOException {
        Store s = storeNovo();
        assertEquals(0, cli(s, "state", "bluesky", "paused", "manutencao"), saidaTxt());
        assertEquals("manutencao", s.health().path("bluesky").path("reason").asText());
        assertEquals("Rede 'bluesky' agora esta 'paused'.", saidaTxt().trim());
    }

    // ---------- stop ----------

    @Test
    void stopSemArgumentoMostraUso() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "stop"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: stop on|off"), saidaTxt());
    }

    @Test
    void stopLigaEDesligaKillSwitchComMensagem() throws Exception {
        Store s = storeNovo();
        assertEquals(0, cli(s, "stop", "on"), saidaTxt());
        assertTrue(saidaTxt().contains("Kill-switch ATIVO: nenhuma acao ate 'stop off'."), saidaTxt());
        assertTrue(s.isStopped());
        limpar();
        assertEquals(0, cli(s, "stop", "off"), saidaTxt());
        assertTrue(saidaTxt().contains("Kill-switch desativado."), saidaTxt());
        assertFalse(s.isStopped());
    }

    @Test
    void stopInvalidoViraErroComCodigo1() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "stop", "xyz"), saidaTxt());
        assertEquals("ERRO: stop invalido: 'xyz' (use on|off)", saidaTxt().trim());
        assertFalse(s.isStopped());
    }

    // ---------- import-cookies ----------

    @Test
    void importCookiesSemArquivoMostraUso() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "import-cookies", "bluesky"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: import-cookies <rede> <arquivo.json>"), saidaTxt());
    }

    @Test
    void importCookiesRedeDesconhecidaImprimeErro() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "import-cookies", "nao_existe", "cookies.json"), saidaTxt());
        assertEquals("ERRO: rede desconhecida: nao_existe", saidaTxt().trim());
    }

    @Test
    void importCookiesArquivoSemCookiesImprimeErroExato() throws IOException {
        Store s = storeNovo();
        Path cookies = tmp.resolve("cookies-vazios.json");
        Files.writeString(cookies, "{}");
        assertEquals(1, cli(s, "import-cookies", "bluesky", cookies.toString()), saidaTxt());
        assertEquals("ERRO: arquivo sem cookies: " + cookies, saidaTxt().trim());
    }

    @Test
    void importCookiesArquivoInexistenteImprimeErro() {
        Store s = storeNovo();
        Path faltante = tmp.resolve("nao-existe.json");
        assertEquals(1, cli(s, "import-cookies", "bluesky", faltante.toString()), saidaTxt());
        assertTrue(saidaTxt().startsWith("ERRO:"), saidaTxt());
    }

    @Test
    void confirmacaoExigidaComConfigInvalidaFalhaFechada() throws IOException {
        Store s = storeNovo();
        Files.writeString(s.configPath(), "isto nao e json {{{");
        assertTrue(ApprovalCli.confirmacaoExigida(s),
                "config ilegivel: CONFIRMO continua exigido (fail-closed)");
    }

    @Test
    void confirmacaoExigidaComDefaultsNaoObjetoExigeConfirmo() throws IOException {
        Store s = storeNovo();
        ObjectNode raiz = Json.obj();
        raiz.put("defaults", "nao-objeto");
        raiz.putObject("networks");
        Files.writeString(s.configPath(), Json.writePretty(raiz));
        assertTrue(ApprovalCli.confirmacaoExigida(s),
                "defaults nao-objeto: CONFIRMO continua exigido");
    }

    // ---------- validar-receitas ----------

    @Test
    void validarReceitasSemRedeAprovaRelatorioVazio() {
        Store s = storeNovo();
        assertEquals(0, cli(s, "validar-receitas"), saidaTxt());
        String out = saidaTxt();
        assertTrue(out.startsWith("REDE"), out);
        assertTrue(out.contains("OK:"), out);
    }

    @Test
    void validarReceitasRedeSemReceitaFalhaComCodigo1() {
        Store s = storeNovo();
        assertEquals(1, cli(s, "validar-receitas", "instagram"), saidaTxt());
        String out = saidaTxt();
        assertTrue(out.contains("sem receita para a rede solicitada"), out);
        assertTrue(out.contains("FALHOU"), out);
    }

    // ---------- journal ----------

    @Test
    void journalVazioMostraMensagem() {
        Store s = storeNovo();
        assertEquals(0, cli(s, "journal"), saidaTxt());
        assertEquals("(journal vazio)", saidaTxt().trim());
    }

    @Test
    void journalLimitaPelaQuantidadePedida() {
        Store s = storeNovo();
        s.appendJournal("teste", null, Json.obj().put("marcador", "primeiro"));
        s.appendJournal("teste", null, Json.obj().put("marcador", "segundo"));

        assertEquals(0, cli(s, "journal", "1"), saidaTxt());
        assertTrue(saidaTxt().contains("segundo"), saidaTxt());
        assertFalse(saidaTxt().contains("primeiro"), saidaTxt());

        limpar();
        assertEquals(0, cli(s, "journal"), saidaTxt());
        assertTrue(saidaTxt().contains("primeiro"), saidaTxt());
        assertTrue(saidaTxt().contains("segundo"), saidaTxt());
    }

    // ---------- comando desconhecido ----------

    @Test
    void comandoDesconhecidoMostraUso() {
        Store s = storeNovo();
        assertEquals(0, cli(s, "comando-que-nao-existe"), saidaTxt());
        assertTrue(saidaTxt().contains("uso: bin/social-mcp --cli <comando>"), saidaTxt());
    }

    // ---------- rodada 3: run(String[]), principal e import-cookies ----------

    @Test
    void runSoChamaSaidaComArgumentosEComOCodigoDoPrincipal() {
        java.util.function.Supplier<Path> homeOriginal = ApprovalCli.homePadrao;
        java.util.function.IntConsumer saidaOriginal = ApprovalCli.saida;
        java.util.List<Integer> codigos = new java.util.ArrayList<>();
        Path home = tmp.resolve("home-run");
        try {
            ApprovalCli.homePadrao = () -> home;
            ApprovalCli.saida = codigos::add;
            ApprovalCli.run(new String[0]);
            assertTrue(codigos.isEmpty(), "sem argumentos so mostra o uso, sem System.exit");
            ApprovalCli.run(new String[]{"validar-receitas"});
            assertEquals(java.util.List.of(1), codigos, "home incompleta sai com 1");
            assertFalse(Files.exists(home), "validar-receitas nao cria a home");
        } finally {
            ApprovalCli.homePadrao = homeOriginal;
            ApprovalCli.saida = saidaOriginal;
        }
    }

    @Test
    void principalDevolveOCodigoDoDespachante() {
        assertEquals(1, ApprovalCli.principal(new String[]{"stop"}, tmp.resolve("home-stop")));
        assertTrue(saidaTxt().contains("uso: stop on|off"), saidaTxt());
    }

    @Test
    void importCookiesImprimeOResultadoDoImportador() {
        ApprovalCli.Importador original = ApprovalCli.importador;
        try {
            ApprovalCli.importador = (st, rede, arquivo) -> "importados de " + rede + " via " + arquivo;
            assertEquals(0, cli(storeNovo(), "import-cookies", "x", "cookies.json"));
            assertTrue(saidaTxt().contains("importados de x via cookies.json"), saidaTxt());
        } finally {
            ApprovalCli.importador = original;
        }
    }
}
