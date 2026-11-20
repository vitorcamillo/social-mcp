package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/** Manutencao de disco: retencao de screenshots, teto do cache do Chrome e pre-filtro do journal. */
class ManutencaoTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    private static void gravar(Store s, ObjectNode cfg) throws Exception {
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
    }

    private static void retencao(Store s, int dias) throws Exception {
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.get("defaults")).put("screenshotRetentionDays", dias);
        gravar(s, cfg);
    }

    /** Cria o arquivo com o mtime dado (regra de poda por lastModifiedTime). */
    private static Path escrever(Path p, long mtimeMs) throws Exception {
        Files.writeString(p, "png", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMs));
        return p;
    }

    private static long diasAtras(long dias) {
        return System.currentTimeMillis() - dias * 86_400_000L;
    }

    @Test
    void podaScreenshotsApagaSoPngsVencidosDaPasta() throws Exception {
        Store s = store();
        long agora = System.currentTimeMillis();
        Path velho = escrever(s.screenshotsDir().resolve("1-x.png"), agora - 100L * 86_400_000L);
        Path recente = escrever(s.screenshotsDir().resolve("2-x.png"), agora - 10L * 86_400_000L);
        Path nota = escrever(s.screenshotsDir().resolve("nota.txt"), agora - 100L * 86_400_000L);
        Path sub = Files.createDirectory(s.screenshotsDir().resolve("sub"));
        Path subVelho = escrever(sub.resolve("3-x.png"), agora - 100L * 86_400_000L);

        assertEquals(1, s.podarScreenshots(agora));
        assertFalse(Files.exists(velho));
        assertTrue(Files.exists(recente));
        assertTrue(Files.exists(nota));
        assertTrue(Files.exists(subVelho));
    }

    @Test
    void retencaoZeroDesligaPoda() throws Exception {
        Store s = store();
        retencao(s, 0);
        Path velho = escrever(s.screenshotsDir().resolve("1-x.png"), diasAtras(100));

        assertEquals(0, s.podarScreenshots(System.currentTimeMillis()));
        assertTrue(Files.exists(velho));
    }

    @Test
    void retencaoCincoDiasApagaTambemDezDias() throws Exception {
        Store s = store();
        retencao(s, 5);
        long agora = System.currentTimeMillis();
        Path cemDias = escrever(s.screenshotsDir().resolve("1-x.png"), agora - 100L * 86_400_000L);
        Path dezDias = escrever(s.screenshotsDir().resolve("2-x.png"), agora - 10L * 86_400_000L);

        assertEquals(2, s.podarScreenshots(agora));
        assertFalse(Files.exists(cemDias));
        assertFalse(Files.exists(dezDias));
    }

    @Test
    void podaRespeitaFronteiraExataDaRetencao() throws Exception {
        Store s = store();
        long agora = System.currentTimeMillis();
        long limite = 90L * 86_400_000L;
        Path naFronteira = escrever(s.screenshotsDir().resolve("1-x.png"), agora - limite);
        Path umMsAlem = escrever(s.screenshotsDir().resolve("2-x.png"), agora - limite - 1);

        assertEquals(1, s.podarScreenshots(agora));
        assertTrue(Files.exists(naFronteira), "exatamente 90 dias ainda nao venceu");
        assertFalse(Files.exists(umMsAlem));
    }

    @Test
    void novoScreenshotPodaEDevolveCaminho() throws Exception {
        Store s = store();
        Path velho = escrever(s.screenshotsDir().resolve("1-x.png"), diasAtras(100));

        Path novo = s.novoScreenshot("instagram");

        assertEquals(s.screenshotsDir(), novo.getParent());
        assertTrue(novo.getFileName().toString().endsWith("-instagram.png"));
        assertFalse(Files.exists(velho));
    }

    @Test
    void diskCacheBytesPadraoECustomizado() {
        assertEquals(256L * 1024 * 1024, BrowserPool.diskCacheBytes(Json.obj()));
        assertEquals(64L * 1024 * 1024, BrowserPool.diskCacheBytes(Json.obj().put("diskCacheMb", 64)));
        assertEquals(256L * 1024 * 1024, BrowserPool.diskCacheBytes(Json.obj().put("diskCacheMb", 0)));
    }

    /** buildOptions sob limite de memoria do cgroup pode recusar o preset nativo (igual BrowserPoolOptionsTest). */
    private static ChromeOptions opts(ObjectNode cfg) {
        try {
            return BrowserPool.buildOptions("mastodon", cfg, null);
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            Assumptions.assumeFalse(m.contains("cgroup") && m.contains("memory.max"),
                    "ambiente com limite de memoria do cgroup: " + m);
            throw e;
        }
    }

    @Test
    void buildOptionsLimitaCacheDeDisco() {
        ChromeOptions o = opts(Json.obj());
        assertTrue(o.getArguments().contains("--disk-cache-size=268435456"), o.getArguments().toString());
    }

    @Test
    void journalMantemContagensComPreFiltro() throws Exception {
        Store s = store();
        s.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "b1"));
        s.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "b1"));
        s.appendJournal("action", "instagram", Json.obj().put("cls", "public"));
        s.appendJournal("action", "x", Json.obj().put("cls", "public").put("batchId", "bx"));
        s.appendJournal("batch_executed", "instagram", Json.obj());
        s.appendJournal("batch_executed", "x", Json.obj());
        s.appendJournal("incerteza_resolvida", "instagram", Json.obj().put("batchId", "b1"));

        // instagram: 1 lote (b1, duas acoes) + 1 acao legada; 1 batch_executed
        Store.Usage insta = s.usageFor("instagram");
        assertEquals(2, insta.publicActions);
        assertEquals(1, insta.batches);
        // x nao entra nas contagens de instagram (e vice-versa)
        Store.Usage x = s.usageFor("x");
        assertEquals(1, x.publicActions);
        assertEquals(1, x.batches);

        assertTrue(s.batchStarted("b1"));
        assertFalse(s.batchStarted("b2"));
        assertFalse(s.batchStarted(null));
        assertEquals(2, s.batchesToday());
    }

    @Test
    void cacheDoJournalVeAppendDeOutroProcesso() throws Exception {
        Store a = store();
        a.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "c1"));
        assertEquals(1, a.usageFor("instagram").publicActions);
        Store b = new Store(a.home); // outro processo no mesmo home
        b.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "c2"));
        assertEquals(2, a.usageFor("instagram").publicActions, "append alheio aparece na leitura seguinte");
        assertTrue(a.batchStarted("c2"));
    }

    @Test
    void journalGrandeEhLidoSemReterHistorico() throws Exception {
        Store s = store();
        String dia = s.today();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            sb.append("{\"ts\":").append(i).append(",\"day\":\"").append(dia)
                    .append("\",\"type\":\"action\",\"network\":\"instagram\",\"cls\":\"public\",\"batchId\":\"g")
                    .append(i).append("\"}\n");
        }
        sb.append("{\"ts\":5000,\"day\":\"").append(dia)
                .append("\",\"type\":\"batch_executed\",\"network\":\"instagram\"}\n");
        Files.writeString(s.journalPath(), sb.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        assertEquals(5_000, s.usageFor("instagram").publicActions);
        assertEquals(1, s.batchesToday());
        assertTrue(s.batchStarted("g4999"));
        assertEquals(2, s.tail(2).size());
        assertTrue(s.tail(2).get(1).contains("batch_executed"));
        // append apos historico grande: leitura seguinte ve a linha nova sem recarga em cache
        s.appendJournal("action", "instagram", Json.obj().put("cls", "public").put("batchId", "novo"));
        assertTrue(s.batchStarted("novo"));
        assertEquals(5_001, s.usageFor("instagram").publicActions);
    }

    @Test
    void podeConterFiltraPorSubstring() {
        String linha = "{\"network\":\"instagram\"}";
        assertTrue(Store.podeConter(linha, "instagram"));
        assertFalse(Store.podeConter(linha, "insta"));
        assertTrue(Store.podeConter(linha, "a\"b"));
        assertFalse(Store.podeConter("{\"n\":\"q\"}", "a b"));   // espaco (0x20) nao e escapado pelo JSON: filtra
        assertFalse(Store.podeConter("{\"n\":\"q\"}", "a~b"));   // til (0x7e) idem
        assertTrue(Store.podeConter("{\"n\":\"q\"}", "a\u001fb")); // controle: nao filtra
        assertTrue(Store.podeConter("{\"n\":\"q\"}", "a\u007fb")); // DEL/nao-ASCII: nao filtra
    }
}
