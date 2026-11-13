package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Diretorio de dados do MCP (SOCIAL_MCP_HOME), config, saude por rede e journal.
 * Layout: config.json, health.json, journal.jsonl, batches/, screenshots/,
 * STOP (kill-switch) e .javadriverless_profiles/ (profiles do Chrome por rede).
 */
public class Store {
    public static final ZoneId TZ = ZoneId.of("America/Sao_Paulo");

    public final Path home;
    /** Monitor do journal vale para TODAS as instancias da JVM: so uma thread por vez
     *  tenta a trava de arquivo (evita OverlappingFileLockException entre canais). */
    private static final Object journalLock = new Object();

    public Store() {
        this(defaultHome());
    }

    public Store(Path home) {
        this.home = home.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.home);
            Files.createDirectories(this.home.resolve("batches"));
            Files.createDirectories(this.home.resolve("screenshots"));
            try (Trava ignored = lock("health")) {
                if (!Files.exists(configPath())) {
                    Files.writeString(configPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
                if (!Files.exists(healthPath())) {
                    Files.writeString(healthPath(), "{}\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
                if (!Files.exists(journalPath())) {
                    Files.createFile(journalPath());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao preparar diretorio de dados " + this.home + ": " + e.getMessage(), e);
        }
    }

    public static Path defaultHome() {
        String env = System.getenv("SOCIAL_MCP_HOME");
        return Paths.get(env != null && !env.isBlank() ? env : System.getProperty("user.home") + "/.social-mcp-public");
    }

    public Path configPath() { return home.resolve("config.json"); }
    public Path healthPath() { return home.resolve("health.json"); }
    public Path journalPath() { return home.resolve("journal.jsonl"); }
    public Path batchesDir() { return home.resolve("batches"); }
    public Path screenshotsDir() { return home.resolve("screenshots"); }
    public Path stopFile() { return home.resolve("STOP"); }
    public Path profilesDir() { return home.resolve(".javadriverless_profiles"); }
    public Path locksDir() { return home.resolve("locks"); }

    public String today() {
        return LocalDate.now(TZ).toString();
    }

    // ---------- config ----------

    public JsonNode config() {
        try {
            return Json.parse(Files.readString(configPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Falha ao ler config.json: " + e.getMessage(), e);
        }
    }

    public ObjectNode defaultsCfg() {
        JsonNode d = config().path("defaults");
        return d.isObject() ? (ObjectNode) d : Json.obj();
    }

    public Set<String> knownNetworks() {
        Set<String> nets = new LinkedHashSet<>();
        config().path("networks").fieldNames().forEachRemaining(nets::add);
        return nets;
    }

    /** Config efetiva da rede: defaults + overrides da rede. */
    public ObjectNode networkCfg(String network) {
        ObjectNode merged = Json.obj();
        JsonNode defs = config().path("defaults");
        if (defs.isObject()) merged.setAll((ObjectNode) defs);
        JsonNode over = config().path("networks").path(network);
        if (over.isObject()) merged.setAll((ObjectNode) over);
        return merged;
    }

    // ---------- saude por rede ----------

    public JsonNode health() {
        try {
            String s = Files.readString(healthPath(), StandardCharsets.UTF_8);
            if (s.isBlank()) return Json.obj();
            JsonNode n = Json.parse(s);
            return n.isObject() ? n : Json.obj();
        } catch (IOException e) {
            throw new RuntimeException("Falha ao ler health.json: " + e.getMessage(), e);
        }
    }

    public String effectiveState(String network) {
        JsonNode h = health().path(network);
        if (h.isObject() && h.hasNonNull("state")) return h.get("state").asText();
        JsonNode s = config().path("networks").path(network).path("state");
        return s.isMissingNode() || s.isNull() ? "healthy" : s.asText();
    }

    public String stateReason(String network) {
        JsonNode h = health().path(network);
        if (h.isObject() && h.hasNonNull("reason")) return h.get("reason").asText();
        JsonNode r = config().path("networks").path(network).path("note");
        return r.isMissingNode() || r.isNull() ? null : r.asText();
    }

    public synchronized void setState(String network, String state, String reason, String by) {
        // varios processos do MCP (um por cliente stdio) gravam health.json: le-modifica-grava sob trava de arquivo
        try (Trava ignored = lock("health")) {
            JsonNode base = health();
            ObjectNode h = base.isObject() ? (ObjectNode) base : Json.obj();
            ObjectNode e = h.putObject(network);
            e.put("state", state);
            e.put("reason", reason == null ? "" : reason);
            e.put("since", System.currentTimeMillis());
            e.put("by", by);
            atomicWrite(healthPath(), Json.writePretty(h));
            appendJournal("state_change", network, Json.obj()
                    .put("state", state)
                    .put("reason", reason == null ? "" : reason)
                    .put("by", by));
        } catch (IOException ex) {
            throw new RuntimeException("Falha ao gravar health.json: " + ex.getMessage(), ex);
        }
    }

    // ---------- kill-switch ----------

    public boolean isStopped() {
        return Files.exists(stopFile());
    }

    public void setStopped(boolean stopped) throws IOException {
        if (stopped) {
            Files.writeString(stopFile(), "kill-switch ativado em " + java.time.Instant.now() + "\n", StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(stopFile());
        }
        appendJournal("kill_switch", null, Json.obj().put("ativo", stopped));
    }

    // ---------- journal ----------

    public void appendJournal(String type, String network, ObjectNode detail) {
        ObjectNode e = Json.obj();
        e.put("ts", System.currentTimeMillis());
        e.put("day", today());
        e.put("type", type);
        if (network != null) e.put("network", network);
        if (detail != null) e.setAll(detail);
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                // trava de arquivo: registros acima de 8192 bytes saem em varios blocos e
                // processos diferentes nao podem intercalar gravacoes no journal
                Files.writeString(journalPath(), Json.write(e) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new RuntimeException("Falha ao gravar journal: " + ex.getMessage(), ex);
            }
        }
    }

    public List<String> tail(int n) {
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                List<String> all = Files.readAllLines(journalPath(), StandardCharsets.UTF_8);
                List<String> out = new ArrayList<>();
                for (int i = Math.max(0, all.size() - n); i < all.size(); i++) out.add(all.get(i));
                return out;
            } catch (IOException e) {
                throw new RuntimeException("Falha ao ler journal: " + e.getMessage(), e);
            }
        }
    }

    /** True se o journal tem qualquer entrada type=action com esse batchId (lote comecou a rodar). */
    public boolean batchStarted(String batchId) {
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                for (String line : Files.readAllLines(journalPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode e = Json.parse(line);
                    if ("action".equals(e.path("type").asText())
                            && batchId != null && batchId.equals(e.path("batchId").asText(null))) {
                        return true;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Falha ao ler journal: " + e.getMessage(), e);
            }
            return false;
        }
    }

    // ---------- consumo de cotas (derivado do journal) ----------

    public static class Usage {
        public int publicActions;
        public int batches;
        public long lastPublicActionMs;
    }

    public Usage usageFor(String network) {
        return usageFor(network, null);
    }

    /**
     * Uso da rede com cota por LOTE: acoes publicas de um mesmo batchId contam
     * como 1 (o lote e a unidade da cota). Acoes sem batchId sao legadas e contam
     * individualmente. excludeBatchId (lote em execucao) e ignorado por completo,
     * para os passos do proprio lote nao se bloquearem.
     */
    public Usage usageFor(String network, String excludeBatchId) {
        Usage u = new Usage();
        String day = today();
        Set<String> lotesHoje = new HashSet<>();
        int legadosHoje = 0;
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                for (String line : Files.readAllLines(journalPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode e = Json.parse(line);
                    if (!network.equals(e.path("network").asText(null))) continue;
                    boolean hoje = day.equals(e.path("day").asText(""));
                    if ("action".equals(e.path("type").asText())
                            && "public".equals(e.path("cls").asText())) {
                        String batchId = e.path("batchId").asText("");
                        if (excludeBatchId != null && excludeBatchId.equals(batchId)) continue;
                        u.lastPublicActionMs = Math.max(u.lastPublicActionMs, e.path("ts").asLong(0));
                        if (batchId.isBlank()) {
                            if (hoje) legadosHoje++;
                        } else if (hoje) {
                            lotesHoje.add(batchId);
                        }
                    }
                    if ("batch_executed".equals(e.path("type").asText()) && hoje) u.batches++;
                }
            } catch (IOException e) {
                // sem historico nao ha como conferir cota/espacamento: falha fecha, nunca libera com zero
                throw new RuntimeException("Falha ao ler journal: " + e.getMessage(), e);
            }
        }
        u.publicActions = legadosHoje + lotesHoje.size();
        return u;
    }

    public int batchesToday() {
        int n = 0;
        String day = today();
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                for (String line : Files.readAllLines(journalPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode e = Json.parse(line);
                    if ("batch_executed".equals(e.path("type").asText()) && day.equals(e.path("day").asText(""))) n++;
                }
            } catch (IOException e) {
                throw new RuntimeException("Falha ao ler journal: " + e.getMessage(), e);
            }
        }
        return n;
    }

    // ---------- incerteza de envio ----------

    /** Conferencias que travam a rede ate o dono/agente registrar social_resolve_incerteza. */
    public static final Set<String> CONFERENCIAS_INCERTAS = Set.of("abortado_apos_publico", "duplicata_suspeita");
    /** Dia padrao da trava (incidente de 11/11: carrossel publicado e abortado depois). */
    private static final LocalDate TRAVA_INCERTEZA_PADRAO = LocalDate.of(2026, 11, 11);

    /** Inicio do dia (epoch ms, fuso TZ) de defaults.travaIncertezaDesde (yyyy-MM-dd; padrao 2026-11-11). */
    public long travaIncertezaDesdeMs() {
        String dia = defaultsCfg().path("travaIncertezaDesde").asText("").trim();
        try {
            return (dia.isEmpty() ? TRAVA_INCERTEZA_PADRAO : LocalDate.parse(dia)).atStartOfDay(TZ).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return TRAVA_INCERTEZA_PADRAO.atStartOfDay(TZ).toInstant().toEpochMilli();
        }
    }

    /**
     * Lotes da rede que terminaram com envio incerto ou duplicata suspeita e ainda nao foram
     * resolvidos: .executed.json com result.conferencia em CONFERENCIAS_INCERTAS, executedAt
     * (ou createdAt, se faltar) desde o inicio do dia travaIncertezaDesde, sem evento
     * incerteza_resolvida no journal para o batchId. Ordenado por id. Arquivo ilegivel: ignora.
     */
    public List<String> incertezasPendentes(String network) {
        List<String> out = new ArrayList<>();
        long desde = travaIncertezaDesdeMs();
        Set<String> resolvidos = resolvidosIncerteza();
        if (!Files.isDirectory(batchesDir())) return out;
        try (var files = Files.list(batchesDir())) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".executed.json")).toList()) {
                try {
                    JsonNode b = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                    if (!network.equals(b.path("network").asText(null))) continue;
                    if (!CONFERENCIAS_INCERTAS.contains(b.path("result").path("conferencia").asText(""))) continue;
                    long quando = b.path("executedAt").asLong(b.path("createdAt").asLong(0));
                    if (quando < desde) continue;
                    String id = b.path("id").asText("");
                    if (!id.isBlank() && !resolvidos.contains(id)) out.add(id);
                } catch (IOException | RuntimeException ignored) {
                    // arquivo ilegivel: ignora e segue
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao listar lotes executados: " + e.getMessage(), e);
        }
        out.sort(String::compareTo);
        return out;
    }

    /** batchIds com evento incerteza_resolvida no journal (linha a linha, como batchStarted). */
    private Set<String> resolvidosIncerteza() {
        Set<String> out = new HashSet<>();
        synchronized (journalLock) {
            try (Trava ignored = lock("journal")) {
                for (String line : Files.readAllLines(journalPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode e = Json.parse(line);
                    if ("incerteza_resolvida".equals(e.path("type").asText())) {
                        String id = e.path("batchId").asText("");
                        if (!id.isBlank()) out.add(id);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Falha ao ler journal: " + e.getMessage(), e);
            }
            return out;
        }
    }

    // ---------- travas entre processos ----------

    /**
     * FileLock vale pela JVM inteira (outra thread da mesma JVM recebe
     * OverlappingFileLockException em vez de esperar): o semaforo por trava completa isso.
     */
    private static final java.util.Map<String, Fila> SEMAFOROS = new java.util.HashMap<>();

    private static final class Fila extends java.util.concurrent.Semaphore {
        int usuarios;
        Fila() { super(1); }
    }

    private static synchronized void soltarReferencia(String chave) {
        Fila fila = SEMAFOROS.get(chave);
        if (--fila.usuarios == 0) SEMAFOROS.remove(chave);
    }

    /** Trava exclusiva de arquivo (locks/&lt;nome&gt;.lock); vale entre processos do MCP, a CLI e threads da JVM. */
    public static final class Trava implements AutoCloseable {
        private final FileChannel ch;
        private final FileLock lock;
        private final java.util.concurrent.Semaphore fila;
        private final String chave;

        private Trava(FileChannel ch, FileLock lock, java.util.concurrent.Semaphore fila, String chave) {
            this.ch = ch;
            this.lock = lock;
            this.fila = fila;
            this.chave = chave;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                try {
                    ch.close();
                } finally {
                    fila.release();
                    soltarReferencia(chave);
                }
            }
        }
    }

    private FileChannel lockChannel(String nome) throws IOException {
        if (!nome.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("nome de trava invalido: " + nome);
        Files.createDirectories(locksDir());
        return FileChannel.open(locksDir().resolve(nome + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private java.util.concurrent.Semaphore semaforoDe(String nome) throws IOException {
        if (!nome.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("nome de trava invalido: " + nome);
        synchronized (Store.class) {
            Fila fila = SEMAFOROS.computeIfAbsent(chaveDaTrava(nome), k -> new Fila());
            fila.usuarios++;
            return fila;
        }
    }

    private String chaveDaTrava(String nome) throws IOException {
        // Caminho real evita duas filas da JVM para o mesmo home acessado por symlink.
        return home.toRealPath().resolve("locks").resolve(nome + ".lock").toString();
    }

    /** Trava bloqueante (espera o outro processo — ou outra thread desta JVM — soltar). */
    public Trava lock(String nome) throws IOException {
        String chave = chaveDaTrava(nome);
        java.util.concurrent.Semaphore fila = semaforoDe(nome);
        try {
            fila.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            soltarReferencia(chave);
            throw new IOException("interrompido esperando a trava " + nome, e);
        }
        FileChannel ch;
        try {
            ch = lockChannel(nome);
        } catch (IOException | RuntimeException e) {
            fila.release();
            soltarReferencia(chave);
            throw e;
        }
        try {
            return new Trava(ch, ch.lock(), fila, chave);
        } catch (IOException | RuntimeException e) {
            fechaCanal(ch);
            fila.release();
            soltarReferencia(chave);
            throw e;
        }
    }

    /** Trava sem espera: null se outro processo (ou esta JVM) ja a segura. */
    public Trava tryLock(String nome) throws IOException {
        String chave = chaveDaTrava(nome);
        java.util.concurrent.Semaphore fila = semaforoDe(nome);
        if (!fila.tryAcquire()) {
            soltarReferencia(chave);
            return null;
        }
        FileChannel ch;
        try {
            ch = lockChannel(nome);
        } catch (IOException | RuntimeException e) {
            fila.release();
            soltarReferencia(chave);
            throw e;
        }
        try {
            FileLock l = ch.tryLock();
            if (l == null) {
                fechaCanal(ch);
                fila.release();
                soltarReferencia(chave);
                return null;
            }
            return new Trava(ch, l, fila, chave);
        } catch (OverlappingFileLockException e) {
            fechaCanal(ch);
            fila.release();
            soltarReferencia(chave);
            return null;
        } catch (IOException | RuntimeException e) {
            fechaCanal(ch);
            fila.release();
            soltarReferencia(chave);
            throw e;
        }
    }

    private static void fechaCanal(FileChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // a trava sera recriada no proximo uso
        }
    }

    // ---------- util ----------

    private void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final String DEFAULT_CONFIG = """
            {
              "defaults": {
                "maxPublicActionsPerDay": 5,
                "minMinutesBetweenPublicActions": 30,
                "maxBatchesPerDay": 4,
                "maxPublicStepsPerBatch": 5,
                "stepDelayMs": [1500, 6000],
                "typeMode": "insertText",
                "typeChunkDelayMs": [60, 260],
                "pageTextMaxChars": 12000,
                "uploadMaxMb": 200,
                "windowSize": "1280x900",
                "tripwires": ["rate limit", "rate limited", "action blocked", "blocked for",
                  "temporariamente bloque", "sua conta foi suspend", "conta suspensa",
                  "unusual activity", "atividade incomum", "confirme que você é humano",
                  "verifique se você é humano", "verify you are human", "too fast",
                  "tente novamente mais tarde", "try again later", "something went wrong"],
                "gate": {
                  "command": [],
                  "config": "",
                  "timeoutSec": 120,
                  "maxApprovalAgeHours": 24
                }
              },
              "networks": {
                "instagram": { "startUrl": "https://www.instagram.com/",
                               "requestHosts": ["www.instagram.com", "i.instagram.com"] },
                "facebook":  { "startUrl": "https://www.facebook.com/" },
                "tiktok":    { "startUrl": "https://www.tiktok.com/" },
                "youtube":   { "startUrl": "https://www.youtube.com/" },
                "x":         { "startUrl": "https://x.com/",
                               "requestHosts": ["api.x.com", "x.com", "twitter.com"] },
                "pinterest": { "startUrl": "https://br.pinterest.com/" },
                "bluesky":   { "startUrl": "https://bsky.app/",
                               "requestHosts": ["bsky.social", "*.host.bsky.network", "public.api.bsky.app"] },
                "mastodon":  { "startUrl": "https://mastodon.social/" },
                "kwai":      { "startUrl": "https://www.kwai.com/", "state": "paused",
                               "note": "sem sessao configurada. So liberar apos login pelo dono: bin/social-mcp --cli state kwai healthy" },
                "threads":   { "startUrl": "https://www.threads.com/", "state": "paused",
                               "note": "pausada por padrao. So liberar apos revisao humana: bin/social-mcp --cli state threads healthy" },
                "reddit":    { "startUrl": "https://www.reddit.com/", "state": "paused",
                               "note": "pausada por padrao. Confirme a conta e as regras da comunidade antes de reativar." }
              }
            }
            """;
}
