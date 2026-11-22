package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mata mutantes vivos de Tools@850-1230 que morrem sem Chrome.
 *
 * <p>Estrategia: travas fail-fast de {@code executeLocked} (kill-switch, cotas,
 * espacamento, estado da rede, lote ja iniciado, rede divergente) sao exercidas
 * via {@code Tools.call("social_execute_batch", ...)} com lotes aprovados com
 * {@code forcar=true} (sem gate externo, sem rede, sem Chrome); metodos
 * estaticos puros ({@code talvezAgiu}, {@code ehEnvio},
 * {@code envioPodeSerPassoAnterior}, {@code journalAction}) e os tools sem
 * navegador ({@code setNetworkState}, {@code journalTail}) sao chamados
 * direto. Cada teste FALHA com o mutante e PASSA com o codigo atual.
 * Tudo que exige passo real no navegador vai em "precisa de Chrome".
 */
class ToolsMutantes3Test {

    @TempDir
    Path tmp;

    private final List<Tools> ferramentas = new ArrayList<>();
    private int seq = 0;

    @AfterEach
    void fecharFerramentas() {
        for (Tools t : ferramentas) t.shutdown();
        ferramentas.clear();
    }

    private Store novoStore() {
        return new Store(tmp.resolve("mcp-" + (seq++)));
    }

    private Tools novaTools(Store store) {
        Tools t = new Tools(store);
        ferramentas.add(t);
        return t;
    }

    private static String loteClique() {
        return "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\","
                + "\"description\":\"botao Publicar\"}]";
    }

    private static String loteLeitura() {
        return "[{\"network\":\"bluesky\",\"kind\":\"goto\",\"url\":\"https://bsky.app/\","
                + "\"description\":\"abrir\"}]";
    }

    private String aprovar(Store store, String acoesJson, boolean ensaio) throws Exception {
        BatchStore bs = new BatchStore(store);
        String id = bs.draft("teste mutantes3", Json.parse(acoesJson)).path("id").asText();
        bs.approve(id, "humano", "nota de teste", List.of(), true, ensaio);
        return id;
    }

    private String executar(Tools tools, String id) throws Exception {
        return tools.call("social_execute_batch", Json.obj().put("batchId", id));
    }

    private void semearAcaoPublica(Store store, String batchId) {
        Tools.journalAction(store, "bluesky", "click",
                Json.obj().put("description", "semente"), batchId, Json.obj(), true);
    }

    private void zerarCotaLotes(Store store) throws Exception {
        Path cfg = store.configPath();
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(cfg, StandardCharsets.UTF_8));
        ((ObjectNode) raiz.path("defaults")).put("maxBatchesPerDay", 0);
        Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    // ---------- executeLocked: travas antes do loop (L868-L907) ----------

    @Test
    void loteComAcaoNoJournalNaoReexecuta() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        String id = aprovar(store, loteClique(), true);
        semearAcaoPublica(store, id); // journal ja tem action deste lote
        store.setState("bluesky", "cooldown", "contencao do mutante", "teste");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("ja foi iniciado"), e.getMessage());
    }

    @Test
    void redeDasAcoesDivergenteDaRedeDoLoteRejeita() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        String id = aprovar(store, loteClique(), true);
        Path aprovado = store.batchesDir().resolve(id + ".approved.json");
        ObjectNode lote = (ObjectNode) Json.parse(Files.readString(aprovado, StandardCharsets.UTF_8));
        lote.put("network", "x"); // hash cobre so actions: segue valido
        Files.writeString(aprovado, Json.writePretty(lote), StandardCharsets.UTF_8);
        store.setState("bluesky", "cooldown", "contencao do mutante", "teste");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("difere da rede do lote"), e.getMessage());
    }

    @Test
    void killSwitchAtivoParaAntesDaCotaDeLotes() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        zerarCotaLotes(store); // mutante que pula o kill-switch cai na cota
        store.setStopped(true);
        String id = aprovar(store, loteClique(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("kill-switch"), e.getMessage());
    }

    @Test
    void cotaDeLotesEsgotadaNoLimiteExato() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        zerarCotaLotes(store); // feitos=0, max=0: 0 >= 0 lanca
        semearAcaoPublica(store, "semente-espacamento"); // mutante que pula a cota cai aqui
        String id = aprovar(store, loteClique(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("cota diaria de lotes"), e.getMessage());
        assertTrue(e.getMessage().contains("0/0"), e.getMessage());
    }

    @Test
    void cotaDeLotesAbaixoDoLimiteDeixaPassar() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        semearAcaoPublica(store, "semente-espacamento"); // espacamento lanca depois da cota
        String id = aprovar(store, loteClique(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("espacamento"), e.getMessage());
    }

    @Test
    void loteSoLeituraPulaValidacaoDePassoPublico() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        String id = aprovar(store, loteLeitura(), false);
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        // mutante que finge passo publico em leitura lanca "esta 'banned'" na L920
        JsonNode resumo = Json.parse(executar(tools, id));
        assertTrue(resumo.path("abortado").asBoolean(false));
        assertTrue(resumo.path("motivo").asText("").contains("BANIDA"), resumo.toString());
    }

    @Test
    void travaDeIncertezaVaziaNaoBloqueiaLotePublico() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        semearAcaoPublica(store, "semente-espacamento");
        String id = aprovar(store, loteClique(), true);
        // mutante que inverte o isEmpty lanca a trava mesmo sem pendencia
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("espacamento"), e.getMessage());
    }

    @Test
    void cotaPublicaExataBloqueiaProximoLote() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        for (int i = 0; i < 5; i++) semearAcaoPublica(store, "semente-cota-" + i);
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        String id = aprovar(store, loteClique(), true);
        // 5 usadas + 1 do lote > 5 lanca; mutante cai no espacamento/banned
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("excede a cota"), e.getMessage());
    }

    @Test
    void cotaPublicaUmAbaixoDoLimiteDeixaPassar() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        for (int i = 0; i < 4; i++) semearAcaoPublica(store, "semente-cota-" + i);
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        String id = aprovar(store, loteClique(), true);
        // 4 + 1 > 5 e falso; fronteira >= lancaria "excede a cota" aqui
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("espacamento"), e.getMessage());
    }

    // ---------- espacamento, estado da rede, prazo e resumo (L911-L1066) ----------

    @Test
    void espacamentoRecenteBloqueiaComMinutosExatos() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        semearAcaoPublica(store, "semente-espacamento");
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        String id = aprovar(store, loteClique(), true);
        // padrao 30 min: (falta + 59999) / 60000 = 30 com margem de 60 s
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("espacamento de 'bluesky'"), e.getMessage());
        assertTrue(e.getMessage().contains("~30 min"), e.getMessage());
    }

    @Test
    void redeEmCooldownRejeitaPassoPublicoAntesDoLoop() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        store.setState("bluesky", "cooldown", "suspeita de bot", "dono");
        String id = aprovar(store, loteClique(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("esta 'cooldown'"), e.getMessage());
    }

    @Test
    void prazoNaoEstouraEmExecucaoRapida() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        store.setState("bluesky", "cooldown", "suspeita de bot", "dono");
        String id = aprovar(store, loteClique(), true);
        // mutante que troca + por - no prazo abortaria "prazo do lote" em vez de lancar
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("cooldown"), e.getMessage());
    }

    @Test
    void guardaBloqueiaPrimeiroPassoSemAbrirNavegador() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        // passo de leitura: o fail-fast so olha rede/cota/espacamento de passo publico, entao
        // nao lanca antes do loop; quem recusa o passo 1 e o Guard.check (banned vale ate
        // para leitura) e ele roda antes de runStep/pool.get, sem abrir navegador
        String id = aprovar(store, loteLeitura(), false);
        // mutante do loop pulado devolve abortado=false; mutante do prazo devolve outro motivo
        JsonNode resumo = Json.parse(executar(tools, id));
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(resumo.path("passosExecutados").asInt(-1) == 0, resumo.toString());
        assertTrue(resumo.path("motivo").asText("").contains("guarda bloqueou o passo 1"),
                resumo.toString());
    }

    @Test
    void resumoDoAbortoGuardaTemFormaExata() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        // leitura + banned: bloqueio vem do Guard.check no passo 1, antes de qualquer Chrome
        String id = aprovar(store, loteLeitura(), false);
        String resultado = executar(tools, id);
        assertTrue(resultado.contains(id)); // retorno vazio ("") morreria aqui (L850/L1066)
        JsonNode resumo = Json.parse(resultado);
        assertTrue(resumo.path("abortado").asBoolean(false), resumo.toString());
        assertTrue(resumo.path("motivo").asText("").contains("guarda bloqueou o passo 1"),
                resumo.toString());
        assertFalse(resumo.has("AVISO"), resumo.toString()); // sem publico feito e sem traces
        assertTrue(resumo.path("duracaoMs").asLong(Long.MAX_VALUE) < 120_000, resumo.toString());
        assertFalse(resumo.has("idsNovos"), resumo.toString());
        Path executado = store.batchesDir().resolve(id + ".executed.json");
        assertTrue(Files.exists(executado), "markExecuted deve gravar o lote executado");
        assertTrue(Files.readString(executado, StandardCharsets.UTF_8).contains("guarda bloqueou"));
    }

    // ---------- talvezAgiu / ehEnvio / envioPodeSerPassoAnterior (L1075-L1126) ----------

    @Test
    void requestComStatusZeroEIncertoFalsoNaoAgiu() {
        ObjectNode passo = Json.obj().put("status", 0);
        // fronteira: status > 0; mutante >= 0 diria que agiu
        assertFalse(Tools.talvezAgiu("request", new IllegalStateException("timeout"), passo));
    }

    @Test
    void talvezAgiuPincaCasosConhecidos() {
        assertTrue(Tools.talvezAgiu("request", new IllegalStateException("timeout"),
                Json.obj().put("status", 1)));
        assertTrue(Tools.talvezAgiu("request", new IllegalStateException("timeout"),
                Json.obj().put("status", 0).put("incerto", true)));
        assertFalse(Tools.talvezAgiu("goto", new IllegalStateException("timeout"), Json.obj()));
        assertFalse(Tools.talvezAgiu("click", new RealClick.NaoAgiu("falha antes do efeito"),
                Json.obj()));
        assertTrue(Tools.talvezAgiu("click", new IllegalStateException("timeout do CDP"),
                Json.obj()));
    }

    @Test
    void envioEOUltimoPublicoAntesDaConferencia() {
        JsonNode acoes = Json.parse(loteClique());
        // unico passo publico sem marcacao: ele e o envio; return false morreria aqui
        assertTrue(Tools.ehEnvio(acoes, 0));
    }

    @Test
    void ehEnvioPincaCasosConhecidos() {
        assertFalse(Tools.ehEnvio(Json.parse(loteLeitura()), 0)); // goto nao e publico
        JsonNode marcado = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\","
                        + "\"envio\":true}]");
        assertTrue(Tools.ehEnvio(marcado, 0));
        JsonNode antesDaProva = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"},"
                        + "{\"network\":\"bluesky\",\"kind\":\"verify_text\",\"selector\":\"css:p\","
                        + "\"expect\":\"oi\"}]");
        assertTrue(Tools.ehEnvio(antesDaProva, 0));
        JsonNode doisPublicos = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:a\"},"
                        + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]");
        assertFalse(Tools.ehEnvio(doisPublicos, 0));
        assertTrue(Tools.ehEnvio(doisPublicos, 1));
        JsonNode comMarcacao = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:a\",\"envio\":true},"
                        + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]");
        assertFalse(Tools.ehEnvio(comMarcacao, 1)); // marcacao explicita: so o marcado e envio
    }

    @Test
    void envioAnteriorExigeDoisPassosPublicosExatos() {
        JsonNode dois = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:a\"},"
                        + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]");
        // fronteira: publicos >= 2; mutante > 2 diria falso
        assertTrue(Tools.envioPodeSerPassoAnterior(dois));
    }

    @Test
    void envioAnteriorPincaCasosConhecidos() {
        JsonNode um = Json.parse(loteClique());
        assertFalse(Tools.envioPodeSerPassoAnterior(um));
        JsonNode comMarcacao = Json.parse(
                "[{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:a\",\"envio\":true},"
                        + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:b\"}]");
        assertFalse(Tools.envioPodeSerPassoAnterior(comMarcacao));
    }

    // ---------- journalAction por kind (L1175-L1209) ----------

    private String ultimaLinhaJournal(Store store) {
        List<String> cauda = store.tail(1);
        assertEquals(1, cauda.size());
        return cauda.get(0);
    }

    @Test
    void passoKeyGravaModoLiteral() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "key", Json.obj(), "lote-1", Json.obj(), true);
        assertTrue(ultimaLinhaJournal(store).contains("\"modo\":\"literal\""));
    }

    @Test
    void passoTypeGravaModoInsercao() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "type", Json.obj(), "lote-1", Json.obj(), true);
        assertTrue(ultimaLinhaJournal(store).contains("\"modo\":\"insertText\""));
    }

    @Test
    void passoClickComCoberturaGravaModoMouse() {
        Store store = novoStore();
        ObjectNode passo = Json.obj().put("modo", "mouse").put("coberto", "css:menu");
        Tools.journalAction(store, "bluesky", "click", Json.obj(), "lote-1", passo, true);
        String linha = ultimaLinhaJournal(store);
        assertTrue(linha.contains("\"modo\":\"mouse\""), linha);
        assertTrue(linha.contains("\"coberto\":\"css:menu\""), linha);
    }

    @Test
    void passoClickLimpoNaoVazaCamposDeOutrosKinds() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "click", Json.obj(), "lote-1", Json.obj(), true);
        String linha = ultimaLinhaJournal(store);
        for (String ausente : new String[] {"coberto", "arquivo", "bytes", "idsVistos", "idNovo",
                "\"url\"", "\"status\"", "incerto", "\"ok\""}) {
            assertFalse(linha.contains(ausente), ausente + " vazou em: " + linha);
        }
    }

    @Test
    void passoScreenshotGravaArquivo() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "screenshot", Json.obj(), "lote-1",
                Json.obj().put("arquivo", "/tmp/tela.png"), true);
        assertTrue(ultimaLinhaJournal(store).contains("\"arquivo\":\"/tmp/tela.png\""));
    }

    @Test
    void passoSetFileGravaArquivoEBytes() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "set_file", Json.obj(), "lote-1",
                Json.obj().put("arquivo", "/tmp/foto.jpg").put("bytes", 10), true);
        String linha = ultimaLinhaJournal(store);
        assertTrue(linha.contains("\"arquivo\":\"/tmp/foto.jpg\""), linha);
        assertTrue(linha.contains("\"bytes\":10"), linha);
    }

    @Test
    void passoCaptureIdsGravaContagem() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "capture_ids", Json.obj(), "lote-1",
                Json.obj().put("idsVistos", 3), true);
        assertTrue(ultimaLinhaJournal(store).contains("\"idsVistos\":3"));
    }

    @Test
    void passoVerifyNewIdCurtoGravaInteiro() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "verify_new_id", Json.obj(), "lote-1",
                Json.obj().put("idNovo", "abc123"), true);
        // mutante que inverte o > 200 estouraria substring neste id curto
        assertTrue(ultimaLinhaJournal(store).contains("\"idNovo\":\"abc123\""));
    }

    @Test
    void passoVerifyNewIdLongoTruncaEm200() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "verify_new_id", Json.obj(), "lote-1",
                Json.obj().put("idNovo", "z".repeat(250)), true);
        String idNovo = Json.parse(ultimaLinhaJournal(store)).path("idNovo").asText("");
        assertEquals(200, idNovo.length());
    }

    @Test
    void passoRequestCompletoGravaTudoSemVazarSegredo() {
        Store store = novoStore();
        ObjectNode acao = Json.obj().put("url", "https://api.x").put("method", "post")
                .put("description", "r");
        acao.set("auth", Json.obj().put("from", "sessao"));
        ObjectNode passo = Json.obj().put("status", 200).put("bodySnippet", "corpo")
                .put("incerto", true);
        Tools.journalAction(store, "bluesky", "request", acao, "lote-1", passo, true);
        String linha = ultimaLinhaJournal(store);
        assertTrue(linha.contains("\"url\":\"https://api.x\""), linha);
        assertTrue(linha.contains("\"method\":\"POST\""), linha);
        assertTrue(linha.contains("\"status\":200"), linha);
        assertTrue(linha.contains("\"from\":\"sessao\""), linha);
        assertTrue(linha.contains("\"bodySnippet\":\"corpo\""), linha);
        assertTrue(linha.contains("\"incerto\":true"), linha);
        assertTrue(linha.contains("\"ok\":true"), linha);
    }

    @Test
    void passoRequestSemAuthSemIncertezaNaoVazaCampos() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "request", Json.obj().put("url", "https://api.x"),
                "lote-1", Json.obj().put("status", 0), false);
        String linha = ultimaLinhaJournal(store);
        assertTrue(linha.contains("\"status\":0"), linha);
        assertTrue(linha.contains("\"ok\":false"), linha);
        assertFalse(linha.contains("\"auth\""), linha);
        assertFalse(linha.contains("incerto"), linha);
    }

    @Test
    void corpoLongoDoRequestTruncaEm300() {
        Store store = novoStore();
        Tools.journalAction(store, "bluesky", "request", Json.obj(), "lote-1",
                Json.obj().put("bodySnippet", "w".repeat(350)), true);
        String corpo = Json.parse(ultimaLinhaJournal(store)).path("bodySnippet").asText("");
        assertEquals(300, corpo.length());
    }

    // ---------- setNetworkState / journalTail (L1216-L1227) ----------

    @Test
    void redeDesconhecidaNaoMudaEstado() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        ObjectNode args = Json.obj().put("network", "fantasma").put("state", "cooldown");
        // mutante sem requireNetwork aplicaria o estado e devolveria texto
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tools.call("social_set_network_state", args));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void agentePoeCooldownComMensagemDoDono() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        ObjectNode args = Json.obj().put("network", "bluesky").put("state", "cooldown")
                .put("reason", "suspeita de bot");
        String resposta = tools.call("social_set_network_state", args);
        assertEquals("rede 'bluesky' agora esta 'cooldown'. So o dono reativa: "
                + "bin/social-mcp --cli state bluesky healthy", resposta);
        assertEquals("cooldown", store.effectiveState("bluesky"));
    }

    @Test
    void agenteNaoLiberaHealthyNemBanned() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        for (String estado : new String[] {"healthy", "banned", "ativo"}) {
            ObjectNode args = Json.obj().put("network", "bluesky").put("state", estado);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> tools.call("social_set_network_state", args));
            assertTrue(e.getMessage().contains("cooldown|paused"), e.getMessage());
        }
        assertEquals("healthy", store.effectiveState("bluesky"));
    }

    @Test
    void caudaDoJournalRespeitaN() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        for (int i = 0; i < 10; i++) {
            store.appendJournal("nota_teste", "bluesky", Json.obj().put("i", i));
        }
        // mutante que ignora n devolveria as 10 linhas; mutante "" devolveria vazio
        String cinco = tools.call("social_journal_tail", Json.obj().put("n", 5));
        assertFalse(cinco.isBlank());
        assertEquals(5, cinco.split("\n").length);
        assertTrue(cinco.contains("\"i\":9"), cinco);
    }

    @Test
    void caudaDoJournalPincaLimites() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        for (int i = 0; i < 10; i++) {
            store.appendJournal("nota_teste", "bluesky", Json.obj().put("i", i));
        }
        assertEquals(1, tools.call("social_journal_tail", Json.obj().put("n", 0)).split("\n").length);
        assertEquals(10,
                tools.call("social_journal_tail", Json.obj().put("n", 1000)).split("\n").length);
        assertEquals(10, tools.call("social_journal_tail", Json.obj()).split("\n").length);
        assertEquals(10, tools.call("social_journal_tail", Json.obj().put("n", "5")).split("\n").length);
    }
}




