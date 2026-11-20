package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalIndiceTest {

    @TempDir
    Path tmp;

    private static final String HOJE = "2026-11-20";
    private static final String ONTEM = "2026-11-19";
    private static final String[] REDES = {"instagram", "x"};
    private static final String[] EXCLUDES = {null, "", " ", "b1", "b2", "b3", "b4", "b5", "7", "zz"};
    private static final String[] IDS = {"", " ", "b1", "b2", "b3", "b4", "b5", "7", "zz"};
    private static final int[] TAILS = {0, 1, 2, 5, 500, 100_000};

    // ---------- oraculo: semantica antiga lendo o arquivo inteiro ----------

    private static List<JsonNode> ler(Path journal) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String linha : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            if (linha.isBlank()) continue;
            out.add(Json.parse(linha));
        }
        return out;
    }

    private static Store.Usage usoOraculo(List<JsonNode> linhas, String net, String ex, String hoje) {
        Store.Usage u = new Store.Usage();
        int legados = 0;
        Set<String> lotes = new HashSet<>();
        for (JsonNode e : linhas) {
            if (!Objects.equals(net, e.path("network").asText(null))) continue;
            boolean ehHoje = hoje.equals(e.path("day").asText(""));
            if ("action".equals(e.path("type").asText())
                    && "public".equals(e.path("cls").asText())) {
                String b = e.path("batchId").asText("");
                if (ex == null || !ex.equals(b)) {
                    u.lastPublicActionMs = Math.max(u.lastPublicActionMs, e.path("ts").asLong(0));
                    if (b.isBlank()) {
                        if (ehHoje) legados++;
                    } else if (ehHoje) {
                        lotes.add(b);
                    }
                }
            }
            if ("batch_executed".equals(e.path("type").asText()) && ehHoje) u.batches++;
        }
        u.publicActions = legados + lotes.size();
        return u;
    }

    private static boolean loteIniciadoOraculo(List<JsonNode> linhas, String id) {
        for (JsonNode e : linhas) {
            if ("action".equals(e.path("type").asText())
                    && id.equals(e.path("batchId").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private static int lotesNoDiaOraculo(List<JsonNode> linhas, String hoje) {
        int n = 0;
        for (JsonNode e : linhas) {
            if ("batch_executed".equals(e.path("type").asText())
                    && hoje.equals(e.path("day").asText(""))) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> incertezasOraculo(List<JsonNode> linhas) {
        Set<String> out = new HashSet<>();
        for (JsonNode e : linhas) {
            if ("incerteza_resolvida".equals(e.path("type").asText())) {
                String id = e.path("batchId").asText("");
                if (!id.isBlank()) out.add(id);
            }
        }
        return out;
    }

    private static void assertIgualAoOraculo(JournalIndice idx, Path j, String hoje) throws IOException {
        List<JsonNode> linhas = ler(j);
        idx.atualizar(hoje);
        for (String net : REDES) {
            for (String ex : EXCLUDES) {
                Store.Usage esperado = usoOraculo(linhas, net, ex, hoje);
                Store.Usage obtido = idx.uso(net, ex, hoje);
                String ctx = "rede=" + net + " exclude=" + ex + " hoje=" + hoje;
                assertEquals(esperado.publicActions, obtido.publicActions, "publicActions " + ctx);
                assertEquals(esperado.batches, obtido.batches, "batches " + ctx);
                assertEquals(esperado.lastPublicActionMs, obtido.lastPublicActionMs, "lastMs " + ctx);
            }
        }
        assertEquals(lotesNoDiaOraculo(linhas, hoje), idx.lotesExecutadosNoDia(hoje), "lotes do dia " + hoje);
        assertEquals(incertezasOraculo(linhas), idx.incertezasResolvidas(), "incertezas");
        for (String id : IDS) {
            assertEquals(loteIniciadoOraculo(linhas, id), idx.loteIniciado(id), "loteIniciado " + id);
        }
    }

    // ---------- helpers ----------

    private Path novoJournal(String nome) throws IOException {
        Path j = tmp.resolve(nome);
        Files.writeString(j, "", StandardCharsets.UTF_8);
        return j;
    }

    private static String linha(String net, String batchId, String dia, long ts) {
        ObjectNode e = Json.obj();
        e.put("ts", ts);
        e.put("day", dia);
        e.put("type", "action");
        e.put("network", net);
        e.put("cls", "public");
        e.put("batchId", batchId);
        return Json.write(e);
    }

    // ---------- 1. aleatorio ----------

    @Test
    void aleatorioIgualAoOraculo() throws Exception {
        Path j = novoJournal("aleatorio.jsonl");
        JournalIndice idx = new JournalIndice(j);
        Random rnd = new Random(20261120L);
        String[] tipos = {"action", "batch_executed", "incerteza_resolvida", "browse", "tripwire"};
        String[] ids = {null, "", " ", "b1", "b2", "b3", "b4", "b5"};
        for (int rodada = 0; rodada < 250; rodada++) {
            StringBuilder chunk = new StringBuilder();
            int linhas = 1 + rnd.nextInt(3);
            for (int k = 0; k < linhas; k++) {
                chunk.append(linhaAleatoria(rnd, tipos, ids)).append('\n');
            }
            if (rnd.nextInt(5) == 0) chunk.append('\n');
            Files.writeString(j, chunk, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            List<JsonNode> esperado = ler(j);
            idx.atualizar(HOJE);
            for (String net : REDES) {
                for (String ex : EXCLUDES) {
                    Store.Usage u = idx.uso(net, ex, HOJE);
                    Store.Usage o = usoOraculo(esperado, net, ex, HOJE);
                    String ctx = "rodada=" + rodada + " rede=" + net + " exclude=" + ex;
                    assertEquals(o.publicActions, u.publicActions, "publicActions " + ctx);
                    assertEquals(o.batches, u.batches, "batches " + ctx);
                    assertEquals(o.lastPublicActionMs, u.lastPublicActionMs, "lastMs " + ctx);
                }
            }
            assertEquals(lotesNoDiaOraculo(esperado, HOJE), idx.lotesExecutadosNoDia(HOJE),
                    "lotes rodada=" + rodada);
            assertEquals(incertezasOraculo(esperado), idx.incertezasResolvidas(),
                    "incertezas rodada=" + rodada);
            for (String id : IDS) {
                assertEquals(loteIniciadoOraculo(esperado, id), idx.loteIniciado(id),
                        "loteIniciado rodada=" + rodada + " id=" + id);
            }
        }
    }

    private static String linhaAleatoria(Random rnd, String[] tipos, String[] ids) {
        ObjectNode e = Json.obj();
        e.put("type", tipos[rnd.nextInt(tipos.length)]);
        int rede = rnd.nextInt(3);
        if (rede < 2) e.put("network", REDES[rede]);
        if (rnd.nextBoolean()) e.put("cls", rnd.nextBoolean() ? "public" : "private");
        int id = rnd.nextInt(ids.length + 1);
        if (id < ids.length) {
            if (ids[id] != null) e.put("batchId", ids[id]);
        } else {
            e.put("batchId", 7);
        }
        int dia = rnd.nextInt(3);
        if (dia == 0) e.put("day", HOJE);
        else if (dia == 1) e.put("day", ONTEM);
        if (rnd.nextInt(4) != 0) {
            e.put("ts", (long) rnd.nextInt(2_000_000_000) - 1_000_000_000L);
        }
        return Json.write(e);
    }

    // ---------- 2. append por outra instancia do Store ----------

    @Test
    void appendDeOutroStoreApareceNaConsultaSeguinte() throws Exception {
        Store a = new Store(tmp);
        Store b = new Store(tmp);
        assertEquals(0, a.usageFor("instagram").publicActions);
        assertTrue(!a.batchStarted("b1"));

        b.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "b1"));
        Store.Usage u = a.usageFor("instagram");
        assertEquals(1, u.publicActions);
        assertTrue(u.lastPublicActionMs > 0);
        assertTrue(u.lastPublicActionMs <= System.currentTimeMillis());
        assertTrue(a.batchStarted("b1"));
        assertEquals(0, a.batchesToday());

        b.appendJournal("batch_executed", "instagram", null);
        assertEquals(1, a.batchesToday());
        assertEquals(1, a.usageFor("instagram").batches);
        assertEquals(0, a.usageFor("x").publicActions);
    }

    // ---------- 3. reset quando o arquivo muda ----------

    @Test
    void resetQuandoArquivoEncolheSubstituiOuMudaNoLugar() throws Exception {
        Path j = novoJournal("reset.jsonl");
        Files.writeString(j, linha("x", "b1", HOJE, 10) + "\n" + linha("x", "b2", HOJE, 20) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(Files.size(j), idx.offset());

        // arquivo reescrito menor
        Files.writeString(j, linha("x", "b1", HOJE, 99) + "\n", StandardCharsets.UTF_8);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(Files.size(j), idx.offset());

        // substituido por outro arquivo via move, MESMO tamanho
        long tamanho = Files.size(j);
        Path outro = tmp.resolve("reset-outro.jsonl");
        Files.writeString(outro, linha("x", "b1", HOJE, 77) + "\n", StandardCharsets.UTF_8);
        assertEquals(tamanho, Files.size(outro));
        Files.move(outro, j, StandardCopyOption.REPLACE_EXISTING);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(Files.size(j), idx.offset());
        assertEquals(1, idx.uso("x", null, HOJE).publicActions);
        assertEquals(0, idx.uso("x", "b1", HOJE).publicActions);

        // bytes antes do offset alterados no lugar, mesmo tamanho
        Files.writeString(j, linha("x", "b3", HOJE, 55) + "\n", StandardCharsets.UTF_8);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(Files.size(j), idx.offset());
        assertEquals(1, idx.uso("x", null, HOJE).lastPublicActionMs > 0 ? 1 : 0);
        assertTrue(idx.loteIniciado("b3"));
        assertTrue(!idx.loteIniciado("b1"));
    }

    // ---------- 4. fragmento sem quebra final ----------

    @Test
    void fragmentoSemQuebraFinal() throws Exception {
        String l1 = linha("x", "b1", HOJE, 5);
        String l2 = "{\"ts\":6,\"day\":\"" + HOJE + "\",\"type\":\"batch_executed\",\"network\":\"x\"}";
        String l3 = linha("instagram", "b2", HOJE, 7);

        Path j = novoJournal("frag.jsonl");
        Files.writeString(j, l1 + "\n" + l2, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(1, idx.lotesExecutadosNoDia(HOJE));
        assertTrue(idx.loteIniciado("b1"));

        // append com quebra antes da linha nova
        Files.writeString(j, "\n" + l3 + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertIgualAoOraculo(idx, j, HOJE);

        // append colado no fragmento (sem quebra antes)
        Path j2 = novoJournal("frag-colado.jsonl");
        Files.writeString(j2, l1 + "\n" + l2, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx2 = new JournalIndice(j2);
        assertIgualAoOraculo(idx2, j2, HOJE);
        Files.writeString(j2, l3 + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        boolean oraculoLanca;
        try {
            ler(j2);
            oraculoLanca = false;
        } catch (RuntimeException e) {
            oraculoLanca = true;
        }
        if (oraculoLanca) {
            assertThrows(RuntimeException.class, () -> idx2.atualizar(HOJE));
            assertThrows(RuntimeException.class, () -> idx2.atualizar(HOJE));
        } else {
            assertIgualAoOraculo(idx2, j2, HOJE);
        }
    }

    // ---------- 5. linha corrompida no meio ----------

    @Test
    void linhaCorrompidaLancaERecuperaAoReescrever() throws Exception {
        String boa1 = linha("x", "b1", HOJE, 1);
        String quebrada = "{\"type\":\"action\",\"cls\":\"public\",";
        String boa2 = linha("x", "b2", HOJE, 2);
        Path j = novoJournal("corrompido.jsonl");
        Files.writeString(j, boa1 + "\n" + quebrada + "\n" + boa2 + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        assertThrows(RuntimeException.class, () -> idx.atualizar(HOJE));
        assertThrows(RuntimeException.class, () -> ler(j));

        Files.writeString(j, boa1 + "\n" + boa2 + "\n", StandardCharsets.UTF_8);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(2, idx.uso("x", null, HOJE).publicActions);
    }

    // ---------- 6. virada de dia ----------

    @Test
    void viradaDeDia() throws Exception {
        String d1 = "2026-11-21";
        Path j = novoJournal("virada.jsonl");
        Files.writeString(j,
                linha("x", "b1", HOJE, 10) + "\n"
                        + "{\"ts\":11,\"day\":\"" + HOJE + "\",\"type\":\"batch_executed\",\"network\":\"x\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(1, idx.uso("x", null, HOJE).batches);
        assertEquals(1, idx.lotesExecutadosNoDia(HOJE));

        // consulta com D+1 zera as contagens do dia
        assertIgualAoOraculo(idx, j, d1);
        assertEquals(0, idx.uso("x", null, d1).publicActions);
        assertEquals(0, idx.uso("x", null, d1).batches);
        assertEquals(0, idx.lotesExecutadosNoDia(d1));
        assertTrue(idx.uso("x", null, d1).lastPublicActionMs > 0);

        // linhas de D+1 contam
        Files.writeString(j,
                linha("x", "b2", d1, 12) + "\n"
                        + "{\"ts\":13,\"day\":\"" + d1 + "\",\"type\":\"batch_executed\",\"network\":\"x\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertIgualAoOraculo(idx, j, d1);
        assertEquals(1, idx.uso("x", null, d1).publicActions);
        assertEquals(1, idx.uso("x", null, d1).batches);

        // relogio voltou: reset total e oraculo com D
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(1, idx.uso("x", null, HOJE).publicActions);
        assertEquals(1, idx.uso("x", null, HOJE).batches);
    }

    // ---------- 7. leitura incremental ----------

    @Test
    void offsetSoAvancaComBytesNovos() throws Exception {
        Path j = novoJournal("incremental.jsonl");
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        assertEquals(0, idx.offset());

        Files.writeString(j, linha("x", "b1", HOJE, 1) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        idx.atualizar(HOJE);
        assertEquals(Files.size(j), idx.offset());
        long antes = idx.offset();

        idx.atualizar(HOJE);
        assertEquals(antes, idx.offset());

        Files.writeString(j, linha("x", "b2", HOJE, 2) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        idx.atualizar(HOJE);
        assertEquals(Files.size(j), idx.offset());
        assertEquals(2, idx.uso("x", null, HOJE).publicActions);
    }

    // ---------- 8. ultimasLinhas ----------

    @Test
    void ultimasLinhasIgualReadAllLines() throws Exception {
        Path vazio = novoJournal("tail-vazio.jsonl");
        for (int n : TAILS) assertTail(vazio, n);

        Path simples = tmp.resolve("tail-simples.jsonl");
        Files.writeString(simples, "a\nb\nc\n", StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(simples, n);

        Path semFinal = tmp.resolve("tail-sem-final.jsonl");
        Files.writeString(semFinal, "a\nb\nc", StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(semFinal, n);

        Path crlf = tmp.resolve("tail-crlf.jsonl");
        Files.writeString(crlf, "a\r\nb\r\n\r\nc\r\n", StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(crlf, n);

        Path brancos = tmp.resolve("tail-brancos.jsonl");
        Files.writeString(brancos, "\n\na\n\nb\n\n", StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(brancos, n);

        Path longo = tmp.resolve("tail-longo.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("linha-").append(i).append('-').append("ç🙂ação".repeat(200)).append('\n');
        }
        Files.writeString(longo, sb, StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(longo, n);

        Path borda = tmp.resolve("tail-borda.jsonl");
        Files.writeString(borda, "a".repeat(8190) + "🙂🙂\n" + "fim\n" + "ç".repeat(9000),
                StandardCharsets.UTF_8);
        for (int n : TAILS) assertTail(borda, n);
    }

    private static void assertTail(Path j, int n) throws IOException {
        List<String> todas = Files.readAllLines(j, StandardCharsets.UTF_8);
        List<String> esperado;
        if (n <= 0) {
            esperado = List.of();
        } else {
            esperado = new ArrayList<>(todas.subList(Math.max(0, todas.size() - n), todas.size()));
        }
        assertEquals(esperado, JournalIndice.ultimasLinhas(j, n),
                "n=" + n + " arquivo=" + j.getFileName());
    }

    // ---------- 9. leitura incremental comprovada por contadores ----------

    @Test
    void semMudancaNaoRelerNemResetar() throws Exception {
        Path j = novoJournal("inc-sem-mudanca.jsonl");
        Files.writeString(j, linha("x", "b1", HOJE, 1) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        long lidos = idx.bytesLidos();
        int resets = idx.resets();
        assertTrue(lidos > 0);

        idx.atualizar(HOJE);
        assertEquals(lidos, idx.bytesLidos(), "sem mudanca nao rele bytes");
        assertEquals(resets, idx.resets(), "sem mudanca nao reseta");
    }

    @Test
    void appendNormalLeExatamenteOsBytesNovosSemReset() throws Exception {
        Path j = novoJournal("inc-append.jsonl");
        Files.writeString(j, linha("x", "b1", HOJE, 1) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        long lidos = idx.bytesLidos();
        int resets = idx.resets();

        String novo = linha("x", "b2", HOJE, 2) + "\n";
        int k = novo.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(j, novo, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        idx.atualizar(HOJE);
        assertEquals(lidos + k, idx.bytesLidos(), "append le so os bytes novos");
        assertEquals(resets, idx.resets(), "append nao reseta");
    }

    @Test
    void mesmoDiaNaoResetaEDiaSeguinteTambemNaoMasVoltarReseta() throws Exception {
        Path j = novoJournal("inc-dia.jsonl");
        Files.writeString(j, linha("x", "b1", HOJE, 1) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        idx.atualizar(HOJE);
        int resets = idx.resets();

        idx.atualizar("2026-11-21");
        assertEquals(resets, idx.resets(), "avancar o dia nao reseta");

        idx.atualizar(HOJE);
        assertTrue(idx.resets() > resets, "voltar no tempo reseta");
    }

    @Test
    void substituicaoMesmoTamanhoResetaELeArquivoInteiro() throws Exception {
        Path j = novoJournal("inc-subst.jsonl");
        Files.writeString(j, linha("x", "b1", HOJE, 10) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        long lidos = idx.bytesLidos();
        int resets = idx.resets();
        long tamanho = Files.size(j);

        Path outro = tmp.resolve("inc-subst-outro.jsonl");
        Files.writeString(outro, linha("x", "b2", HOJE, 20) + "\n", StandardCharsets.UTF_8);
        assertEquals(tamanho, Files.size(outro));
        Files.move(outro, j, StandardCopyOption.REPLACE_EXISTING);

        assertIgualAoOraculo(idx, j, HOJE);
        assertTrue(idx.resets() > resets, "substituicao reseta");
        assertEquals(lidos + tamanho, idx.bytesLidos(), "apos reset le o arquivo inteiro");
        assertFalse(idx.loteIniciado("b1"));
        assertTrue(idx.loteIniciado("b2"));
    }

    @Test
    void edicaoNoLugarDosUltimosBytesReseta() throws Exception {
        Path j = novoJournal("inc-edicao.jsonl");
        String l1 = linha("x", "b1", HOJE, 10);
        String l2 = linha("x", "b2", HOJE, 20);
        Files.writeString(j, l1 + "\n" + l2 + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        int resets = idx.resets();

        byte[] conteudo = Files.readAllBytes(j);
        String s = new String(conteudo, StandardCharsets.UTF_8);
        int pos = s.lastIndexOf("b2") + 1; // dentro dos ultimos 64 bytes lidos
        try (FileChannel ch = FileChannel.open(j, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {'3'}), pos);
        }
        assertEquals((long) conteudo.length, Files.size(j), "edicao mantem o tamanho");

        assertIgualAoOraculo(idx, j, HOJE);
        assertTrue(idx.resets() > resets, "edicao na cauda reseta");
        assertFalse(idx.loteIniciado("b2"));
        assertTrue(idx.loteIniciado("b3"));
    }

    @Test
    void arquivoMenorTruncadoNoLugarReseta() throws Exception {
        Path j = novoJournal("inc-trunc.jsonl");
        String l1 = linha("x", "b1", HOJE, 10);
        String l2 = linha("x", "b2", HOJE, 20);
        Files.writeString(j, l1 + "\n" + l2 + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        long lidos = idx.bytesLidos();
        int resets = idx.resets();

        long novo = (l1 + "\n").getBytes(StandardCharsets.UTF_8).length;
        try (FileChannel ch = FileChannel.open(j, StandardOpenOption.WRITE)) {
            ch.truncate(novo);
        }

        assertIgualAoOraculo(idx, j, HOJE);
        assertTrue(idx.resets() > resets, "arquivo menor reseta");
        assertEquals(lidos + novo, idx.bytesLidos(), "apos reset le o arquivo truncado inteiro");
        assertEquals(1, idx.uso("x", null, HOJE).publicActions);
        assertFalse(idx.loteIniciado("b2"));
    }

    @Test
    void fragmentoSemQuebraResetaNoAppendENaoResetaQuandoSoHaEspacos() throws Exception {
        Path j = novoJournal("inc-frag.jsonl");
        String l1 = linha("x", "b1", HOJE, 10);
        Files.writeString(j, l1, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        int resets = idx.resets();

        String l2 = linha("x", "b2", HOJE, 20);
        Files.writeString(j, "\n" + l2 + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertIgualAoOraculo(idx, j, HOJE);
        assertTrue(idx.resets() > resets, "fragmento valido pendente reseta no append");
        assertTrue(idx.loteIniciado("b1"));
        assertTrue(idx.loteIniciado("b2"));

        Path j2 = novoJournal("inc-frag-brancos.jsonl");
        String base = linha("x", "b1", HOJE, 10) + "\n";
        Files.writeString(j2, base, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.writeString(j2, "   ", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx2 = new JournalIndice(j2);
        idx2.atualizar(HOJE);
        int resets2 = idx2.resets();
        long lidos2 = idx2.bytesLidos();

        String l3 = linha("x", "b2", HOJE, 20) + "\n";
        int k = ("\n" + l3).getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(j2, "\n" + l3, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertIgualAoOraculo(idx2, j2, HOJE);
        assertEquals(resets2, idx2.resets(), "linha final so com espacos nao reseta");
        assertEquals(lidos2 + k, idx2.bytesLidos(), "le so os bytes novos");
        assertEquals(2, idx2.uso("x", null, HOJE).publicActions);
    }

    @Test
    void resetNoCatchNaoDuplicaContagem() throws Exception {
        Path j = novoJournal("inc-catch.jsonl");
        String base = linha("x", "b0", HOJE, 10) + "\n"
                + linha("x", "b1", HOJE, 11) + "\n"
                + linha("x", "b2", HOJE, 12) + "\n";
        Files.writeString(j, base, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);

        // acao legada (sem batchId): conta por linha, entao uma releitura sem reset contaria duas vezes
        String v = linha("x", "", HOJE, 100);
        String c = "{\"type\":\"action\",\"cls\":\"public\",";
        Files.writeString(j, v + "\n" + c + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(RuntimeException.class, () -> idx.atualizar(HOJE));

        long limite = base.getBytes(StandardCharsets.UTF_8).length + v.getBytes(StandardCharsets.UTF_8).length + 1L;
        try (FileChannel ch = FileChannel.open(j, StandardOpenOption.WRITE)) {
            ch.truncate(limite);
        }

        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(4, idx.uso("x", null, HOJE).publicActions, "acao legada conta uma vez so");
        assertEquals(3, idx.uso("x", "b0", HOJE).publicActions);
    }

    @Test
    void crlfIgualAoOraculo() throws Exception {
        Path j = novoJournal("inc-crlf.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(linha(REDES[i % 2], "c" + i, i % 2 == 0 ? HOJE : ONTEM, 100 + i)).append("\r\n");
        }
        sb.append("{\"ts\":200,\"day\":\"").append(HOJE)
                .append("\",\"type\":\"batch_executed\",\"network\":\"x\"}\r\n");
        sb.append("{\"ts\":201,\"day\":\"").append(HOJE)
                .append("\",\"type\":\"incerteza_resolvida\",\"network\":\"x\",\"batchId\":\"r1\"}\r\n");
        Files.writeString(j, sb, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(1, idx.uso("x", null, HOJE).batches);
        assertTrue(idx.incertezasResolvidas().contains("r1"));
    }

    @Test
    void typeComEscapeUnicodeIgualAoOraculo() throws Exception {
        Path j = novoJournal("escape-unicode.jsonl");
        String conteudo =
                "{\"ts\":1,\"day\":\"" + HOJE + "\",\"type\":\"\\u0061ction\",\"network\":\"x\",\"cls\":\"public\",\"batchId\":\"b1\"}\n"
                        + "{\"ts\":2,\"day\":\"" + HOJE + "\",\"type\":\"\\u0062atch_executed\",\"network\":\"x\"}\n"
                        + "{\"ts\":3,\"day\":\"" + HOJE + "\",\"type\":\"incerteza_resolvid\\u0061\",\"network\":\"x\",\"batchId\":\"r1\"}\n";
        Files.writeString(j, conteudo, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(1, idx.uso("x", null, HOJE).publicActions);
        assertEquals(1, idx.uso("x", null, HOJE).batches);
        assertEquals(1, idx.lotesExecutadosNoDia(HOJE));
        assertTrue(idx.loteIniciado("b1"));
        assertTrue(idx.incertezasResolvidas().contains("r1"));
    }

    @Test
    void crIsoladoIgualAoOraculo() throws Exception {
        Path j = novoJournal("cr-isolado.jsonl");
        String conteudo = linha("x", "b1", HOJE, 1) + "\r"
                + linha("x", "b2", HOJE, 2) + "\r"
                + "{\"ts\":3,\"day\":\"" + HOJE + "\",\"type\":\"batch_executed\",\"network\":\"x\"}\r"
                + "{\"ts\":4,\"day\":\"" + HOJE + "\",\"type\":\"incerteza_resolvida\",\"network\":\"x\",\"batchId\":\"r1\"}\r";
        Files.writeString(j, conteudo, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        JournalIndice idx = new JournalIndice(j);
        assertIgualAoOraculo(idx, j, HOJE);
        assertEquals(2, idx.uso("x", null, HOJE).publicActions);
        assertEquals(1, idx.uso("x", null, HOJE).batches);
        assertTrue(idx.loteIniciado("b1"));
        assertTrue(idx.loteIniciado("b2"));
        assertTrue(idx.incertezasResolvidas().contains("r1"));
    }

    @Test
    void resetLimpaTodoOEstado() throws Exception {
        Path j = novoJournal("inc-reset-limpa.jsonl");
        StringBuilder antes = new StringBuilder();
        antes.append("{\"ts\":100,\"day\":\"").append(HOJE)
                .append("\",\"type\":\"incerteza_resolvida\",\"network\":\"x\",\"batchId\":\"r1\"}\n");
        antes.append(linha("x", "p1", HOJE, 500)).append('\n');
        antes.append("{\"ts\":600,\"day\":\"").append(HOJE)
                .append("\",\"type\":\"batch_executed\",\"network\":\"x\"}\n");
        Files.writeString(j, antes, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JournalIndice idx = new JournalIndice(j);
        idx.atualizar(HOJE);
        assertTrue(idx.incertezasResolvidas().contains("r1"));
        assertTrue(idx.loteIniciado("p1"));
        assertEquals(500L, idx.uso("x", null, HOJE).lastPublicActionMs);
        assertEquals(1, idx.lotesExecutadosNoDia(HOJE));

        // substitui por arquivo menor SEM as linhas indexadas (so uma acao privada, sem batchId)
        ObjectNode priv = Json.obj();
        priv.put("ts", 700);
        priv.put("day", HOJE);
        priv.put("type", "action");
        priv.put("network", "x");
        priv.put("cls", "private");
        Files.writeString(j, Json.write(priv) + "\n", StandardCharsets.UTF_8);

        assertIgualAoOraculo(idx, j, HOJE);
        assertTrue(idx.incertezasResolvidas().isEmpty());
        assertFalse(idx.loteIniciado("p1"));
        assertEquals(0L, idx.uso("x", null, HOJE).lastPublicActionMs);
        assertEquals(0, idx.lotesExecutadosNoDia(HOJE));
    }

    @Test
    void ultimasLinhasAleatorioIgualReadAllLines() throws Exception {
        Random rnd = new Random(20261121L);
        int arquivos = 40 + rnd.nextInt(10);
        for (int f = 0; f < arquivos; f++) {
            Path j = tmp.resolve("tail-rnd-" + f + ".jsonl");
            StringBuilder sb = new StringBuilder();
            int totalLinhas = rnd.nextInt(80);
            for (int l = 0; l < totalLinhas && sb.length() < 100_000; l++) {
                int len = rnd.nextInt(301);
                for (int c = 0; c < len; c++) {
                    int r = rnd.nextInt(60);
                    if (r == 0) sb.append('ç');
                    else if (r == 1) sb.append("🙂");
                    else sb.append((char) (32 + rnd.nextInt(95)));
                }
                if (l == totalLinhas - 1 && rnd.nextInt(4) == 0) continue; // sem terminador final
                sb.append(rnd.nextBoolean() ? "\n" : "\r\n");
            }
            Files.writeString(j, sb, StandardCharsets.UTF_8);

            List<String> todas = Files.readAllLines(j, StandardCharsets.UTF_8);
            int[] ns = {0, 1, 2, 3,
                    rnd.nextInt(301), rnd.nextInt(301), rnd.nextInt(301),
                    todas.size(), todas.size() + 1};
            for (int n : ns) assertTail(j, n);
        }
    }

    @Test
    void ultimasLinhasLePouco() throws Exception {
        Path j = tmp.resolve("tail-1mb.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < 1_000_000; i++) {
            sb.append(String.format("%099d", i)).append('\n');
        }
        Files.writeString(j, sb, StandardCharsets.UTF_8);
        List<String> todas = Files.readAllLines(j, StandardCharsets.UTF_8);

        long[] lidos = new long[1];
        List<String> cinco = JournalIndice.ultimasLinhas(j, 5, lidos);
        assertEquals(new ArrayList<>(todas.subList(todas.size() - 5, todas.size())), cinco);
        assertEquals(8192, lidos[0], "n=5 le so o primeiro bloco");

        // linhas de 100 bytes: os ultimos 8192 bytes tem 82 quebras (a primeira linha e parcial)
        for (int n : new int[] {81, 82, 200}) {
            lidos[0] = 0;
            List<String> ultimas = JournalIndice.ultimasLinhas(j, n, lidos);
            assertEquals(new ArrayList<>(todas.subList(todas.size() - n, todas.size())), ultimas, "n=" + n);
            assertEquals(n == 81 ? 8192 : 8192 + 16384, lidos[0], "bytes lidos para n=" + n);
        }

        lidos[0] = 0;
        assertEquals(List.of(), JournalIndice.ultimasLinhas(j, 0, lidos));
        assertEquals(0, lidos[0], "n=0 nao le o arquivo");
    }
}
