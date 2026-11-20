package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;

/**
 * Indice incremental do journal.jsonl: cada consulta le so os bytes novos desde a ultima
 * leitura, com memoria proporcional a lotes/redes/dias (nunca ao numero de linhas).
 * O resultado equivale a percorrer o arquivo inteiro a cada chamada. O maior ts por rede
 * guarda uma entrada por lote aprovado (como os lotes com acao), nunca uma por linha.
 *
 * <p>Integridade: a cada mudanca de ctime (ou ctime ainda dentro da margem de tique) o prefixo
 * ja lido e conferido por CRC32C, sem parse. Assim uma edicao em qualquer ponto do arquivo
 * provoca releitura completa em vez de passar despercebida.
 *
 * <p>Nao e thread-safe sozinho: o Store so a usa dentro de
 * {@code synchronized (journalLock)} + {@code lock("journal")}.
 */
final class JournalIndice {
    private static final int BLOCO = 64 * 1024;
    /** Escrita a menos disso do ctime visto pode cair no mesmo tique de timestamp: confere de novo. */
    static final long MARGEM_CTIME_MS = 2000;
    /** FS com ctime abaixo de 1 ms (ext4, xfs, btrfs, tmpfs) usa o relogio grosso do kernel: tique <= 10 ms. */
    static final long MARGEM_CTIME_FINO_MS = 20;

    /** Margem do ctime: fina se o FS guarda fracao de milissegundo; senao assume timestamp grosso (ate 2 s). */
    static long margem(FileTime ctime) {
        return ctime.to(TimeUnit.NANOSECONDS) % 1_000_000 != 0 ? MARGEM_CTIME_FINO_MS : MARGEM_CTIME_MS;
    }
    private static final String TOKEN_ACTION = "action";
    private static final String TOKEN_BATCH = "batch_executed";
    private static final String TOKEN_INCERTEZA = "incerteza_resolvida";

    private final Path journal;
    private final LongSupplier relogio;
    /** CRC32C dos bytes [0, offset) ja agregados. */
    private final CRC32C crc = new CRC32C();

    private long offset;
    private Object fileKey;
    private boolean fragmento;
    private String ultimoHoje;
    /** ctime confiavel da ultima leitura; null = conferir o prefixo na proxima consulta. */
    private FileTime ctimeVisto;
    long bytesLidos;
    long bytesVerificados;   // so para teste, NAO zerado pelo reset (como bytesLidos)
    int resets;

    private final Set<String> lotesComAcao = new HashSet<>();
    private final Set<String> resolvidas = new HashSet<>();
    private final Map<String, Map<String, Long>> porRede = new HashMap<>();
    private final Map<String, Dia> porDia = new HashMap<>();

    JournalIndice(Path journal) {
        this(journal, System::currentTimeMillis);
    }

    JournalIndice(Path journal, LongSupplier relogio) {
        this.journal = journal;
        this.relogio = relogio;
    }

    /** Le o que entrou desde a ultima leitura. Toda consulta abaixo chama isto antes. */
    void atualizar(String hoje) throws IOException {
        try {
            long agora = relogio.getAsLong();
            if (ultimoHoje != null && hoje.compareTo(ultimoHoje) < 0) reset();
            ultimoHoje = hoje;

            BasicFileAttributes attrs = Files.readAttributes(journal, BasicFileAttributes.class);
            long tamanho = attrs.size();
            Object fk = attrs.fileKey();
            FileTime ctime = ctime();
            boolean resetar = tamanho < offset
                    || !Objects.equals(fk, fileKey)
                    || (fragmento && tamanho != offset)
                    || ((ctimeVisto == null || !ctimeVisto.equals(ctime)) && !prefixoConfere());
            if (resetar) reset();
            if (tamanho != offset) ler();
            fileKey = fk;
            ctimeVisto = ctime != null && ctime.toMillis() < agora - margem(ctime) ? ctime : null;
            porDia.keySet().removeIf(k -> k.compareTo(hoje) < 0);
        } catch (IOException | RuntimeException e) {
            reset();
            throw e;
        }
    }

    boolean loteIniciado(String batchId) {
        return batchId != null && lotesComAcao.contains(batchId);
    }

    Store.Usage uso(String network, String excludeBatchId, String hoje) {
        Store.Usage u = new Store.Usage();
        Map<String, Long> mapa = porRede.get(network);
        u.lastPublicActionMs = mapa == null ? 0 : Math.max(0, mapa.entrySet().stream()
                .filter(e -> excludeBatchId == null || !excludeBatchId.equals(e.getKey()))
                .mapToLong(Map.Entry::getValue).max().orElse(0));
        int legados = 0;
        int lotes = 0;
        int executados = 0;
        Dia dia = porDia.get(hoje);
        if (dia != null) {
            DiaRede dr = dia.redes.get(network);
            if (dr != null) {
                for (Map.Entry<String, Integer> e : dr.legadosPorId.entrySet()) {
                    if (excludeBatchId == null || !excludeBatchId.equals(e.getKey())) {
                        legados += e.getValue();
                    }
                }
                lotes = dr.lotes.size();
                if (excludeBatchId != null && dr.lotes.contains(excludeBatchId)) lotes--;
                executados = dr.executados;
            }
        }
        u.publicActions = legados + lotes;
        u.batches = executados;
        return u;
    }

    int lotesExecutadosNoDia(String hoje) {
        Dia dia = porDia.get(hoje);
        return dia == null ? 0 : dia.executadosTotal;
    }

    Set<String> incertezasResolvidas() {
        return Set.copyOf(resolvidas);
    }

    long offset() {
        return offset;
    }

    long bytesLidos() {
        return bytesLidos;
    }

    long bytesVerificados() {
        return bytesVerificados;
    }

    int resets() {
        return resets;
    }

    // ---------- leitura incremental ----------

    /** ctime do kernel: muda a cada escrita e nao e ajustavel por usuario. null se o FS nao expoe. */
    private FileTime ctime() throws IOException {
        try {
            return (FileTime) Files.getAttribute(journal, "unix:ctime");
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /** Os bytes [0, offset) ainda sao os que o indice agregou? (CRC32C, sem parse) */
    private boolean prefixoConfere() throws IOException {
        CRC32C atual = new CRC32C();
        ByteBuffer buf = ByteBuffer.allocate(BLOCO);
        try (SeekableByteChannel ch = Files.newByteChannel(journal, StandardOpenOption.READ)) {
            for (long falta = offset; falta > 0; ) {
                buf.clear().limit((int) Math.min(BLOCO, falta));
                int n = ch.read(buf);
                if (n == -1) throw new EOFException("journal encolheu durante a conferencia");
                atual.update(buf.flip());
                falta -= n;
            }
        }
        bytesVerificados += offset;
        return atual.getValue() == crc.getValue();
    }

    private void ler() throws IOException {
        long lidos = 0;
        try (SeekableByteChannel ch = Files.newByteChannel(journal, StandardOpenOption.READ)) {
            ch.position(offset);
            InputStream in = new BufferedInputStream(
                    new CheckedInputStream(Channels.newInputStream(ch), crc), BLOCO);
            ByteArrayOutputStream pendente = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                lidos++;
                if (b == '\n' || b == '\r') {
                    agregarLinha(pendente.toByteArray());
                    pendente.reset();
                } else {
                    pendente.write(b);
                }
            }
            String resto = texto(pendente.toByteArray());
            if (!resto.isBlank()) {
                agregarTexto(resto);
                fragmento = true;
            }
        }
        bytesLidos += lidos;
        offset += lidos;
    }

    private static String texto(byte[] bytes) {
        // CR e LF ja separam linhas em ler(): o trecho nunca contem '\r'
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void agregarLinha(byte[] bytes) {
        agregarTexto(texto(bytes));
    }

    private void agregarTexto(String linha) {
        if (linha.isBlank()) return;
        // Escape unicode pode ocultar o literal do type (ex.: action com 'a' escapado); nesse caso nao filtrar.
        if (!linha.contains("\\u")
                && !Store.podeConter(linha, TOKEN_ACTION)
                && !Store.podeConter(linha, TOKEN_BATCH)
                && !Store.podeConter(linha, TOKEN_INCERTEZA)) {
            return;
        }
        agregar(Json.parse(linha));
    }

    private void agregar(JsonNode e) {
        String type = e.path("type").asText();
        String network = e.path("network").asText(null);
        if (TOKEN_ACTION.equals(type)) {
            String batchId = e.path("batchId").asText(null);
            if (batchId != null) lotesComAcao.add(batchId);
            if ("public".equals(e.path("cls").asText())) {
                String b = e.path("batchId").asText("");
                String dia = e.path("day").asText("");
                if (network != null) {
                    porRede.computeIfAbsent(network, k -> new HashMap<>())
                            .merge(b, e.path("ts").asLong(0), Math::max);
                    DiaRede dr = porDia.computeIfAbsent(dia, k -> new Dia())
                            .redes.computeIfAbsent(network, k -> new DiaRede());
                    if (b.isBlank()) dr.legadosPorId.merge(b, 1, Integer::sum);
                    else dr.lotes.add(b);
                }
            }
        } else if (TOKEN_BATCH.equals(type)) {
            Dia d = porDia.computeIfAbsent(e.path("day").asText(""), k -> new Dia());
            d.executadosTotal++;
            if (network != null) {
                d.redes.computeIfAbsent(network, k -> new DiaRede()).executados++;
            }
        } else if (TOKEN_INCERTEZA.equals(type)) {
            String id = e.path("batchId").asText("");
            if (!id.isBlank()) resolvidas.add(id);
        }
    }

    private void reset() {
        resets++;
        offset = 0;
        fragmento = false;
        fileKey = null;
        crc.reset();
        ctimeVisto = null;
        lotesComAcao.clear();
        resolvidas.clear();
        porRede.clear();
        porDia.clear();
    }

    // ---------- estrutura agregada ----------

    private static final class Dia {
        final Map<String, DiaRede> redes = new HashMap<>();
        int executadosTotal;
    }

    private static final class DiaRede {
        final Map<String, Integer> legadosPorId = new HashMap<>();
        final Set<String> lotes = new HashSet<>();
        int executados;
    }

    // ---------- ultimas linhas ----------

    /** Ultimas n linhas do journal; semantica igual a ultimas n de Files.readAllLines. */
    static List<String> ultimasLinhas(Path journal, int n) throws IOException {
        return ultimasLinhas(journal, n, new long[1]);
    }

    /** Idem, somando em {@code lidos[0]} os bytes lidos do arquivo. */
    static List<String> ultimasLinhas(Path journal, int n, long[] lidos) throws IOException {
        if (n <= 0) return List.of();
        byte[] dados = new byte[0];
        try (SeekableByteChannel ch = Files.newByteChannel(journal, StandardOpenOption.READ)) {
            long inicio = ch.size();
            long bloco = 8192;
            do {
                long novo = Math.max(0, inicio - bloco);
                byte[] lido = Channels.newInputStream(ch.position(novo)).readNBytes((int) (inicio - novo));
                lidos[0] += lido.length;
                byte[] junto = Arrays.copyOf(lido, lido.length + dados.length);
                System.arraycopy(dados, 0, junto, lido.length, dados.length);
                dados = junto;
                inicio = novo;
                bloco *= 2;
            } while (inicio > 0 && contarQuebras(dados) <= n);
        }
        // A linha parcial do inicio do trecho nunca entra: com n+1 quebras ha n linhas inteiras depois dela.
        List<String> linhas = new BufferedReader(new StringReader(new String(dados, StandardCharsets.UTF_8)))
                .lines().toList();
        return new ArrayList<>(linhas.subList(Math.max(0, linhas.size() - n), linhas.size()));
    }

    private static int contarQuebras(byte[] bytes) {
        int n = 0;
        for (byte b : bytes) {
            if (b == '\n') n++;
        }
        return n;
    }
}
