package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextGateTest {

    @TempDir
    Path tmp;

    /** Store em @TempDir com defaults.gate = bash -c script (script nulo remove o gate). */
    static Store storeComGate(Path base, String script, int timeoutSec) throws Exception {
        Store s = new Store(base.resolve("home" + System.nanoTime()));
        ObjectNode cfg = (ObjectNode) s.config();
        ObjectNode defs = (ObjectNode) cfg.get("defaults");
        if (script == null) {
            defs.remove("gate");
        } else {
            ObjectNode gate = defs.putObject("gate");
            gate.putArray("command").add("bash").add("-c").add(script);
            gate.put("timeoutSec", timeoutSec);
        }
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
        return s;
    }

    private Path lote(Store s) throws Exception {
        Path p = s.batchesDir().resolve("x.draft.json");
        Files.writeString(p, "{\"actions\":[]}", StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void exitZeroEPass() throws Exception {
        Store s = storeComGate(tmp, "exit 0", 30);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(0, r.exit());
        assertEquals("PASS", r.resultado());
    }

    @Test
    void exitDoisComJsonFail() throws Exception {
        Store s = storeComGate(tmp, "echo 'texto ruim'; echo '{\"resultado\":\"FAIL\"}'; exit 2", 30);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(2, r.exit());
        assertEquals("FAIL", r.resultado());
        assertTrue(r.saida().contains("texto ruim"));
    }

    @Test
    void jsonPassNaoAbafaExitDeFalha() throws Exception {
        // resposta contraditoria: JSON diz PASS, processo saiu com falha
        Store s = storeComGate(tmp, "echo '{\"resultado\":\"PASS\"}'; exit 2", 30);
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals(2, r.exit());
        assertEquals("FAIL", r.resultado());
        s = storeComGate(tmp, "echo '{\"resultado\":\"PASS\"}'; exit 7", 30);
        assertEquals("ERRO", TextGate.rodarPre(s, lote(s), List.of()).resultado());
        s = storeComGate(tmp, "echo '{\"resultado\":\"PASS\"}'; exit 3", 30);
        assertEquals("WARN", TextGate.rodarPre(s, lote(s), List.of()).resultado());
        // e o inverso tambem reprova: FAIL no JSON com exit 0
        s = storeComGate(tmp, "echo '{\"resultado\":\"FAIL\"}'; exit 0", 30);
        assertEquals("FAIL", TextGate.rodarPre(s, lote(s), List.of()).resultado());
        assertEquals("PASS", TextGate.resultadoDe("{\"resultado\":\"PASS\"}", 0));
        assertEquals("FAIL", TextGate.resultadoDe("{\"resultado\":\"PASS\"}", 2));
    }

    @Test
    void exitTresEWarn() throws Exception {
        Store s = storeComGate(tmp, "exit 3", 30);
        assertEquals("WARN", TextGate.rodarPre(s, lote(s), List.of()).resultado());
    }

    @Test
    void exitDesconhecidoSemJsonEErro() throws Exception {
        Store s = storeComGate(tmp, "echo nada; exit 7", 30);
        assertEquals("ERRO", TextGate.rodarPre(s, lote(s), List.of()).resultado());
    }

    @Test
    void recebeArgumentosDoLoteEFichas() throws Exception {
        // bash -c script $0 $1...: o primeiro arg extra vira $0
        Store s = storeComGate(tmp, "echo \"$0 $*\"; exit 0", 30);
        Path ficha = tmp.resolve("ficha.md");
        Path l = lote(s);
        TextGate.Resultado r = TextGate.rodarPre(s, l, List.of(ficha));
        assertTrue(r.saida().contains("pre --batch " + l.toAbsolutePath()), r.saida());
        assertTrue(r.saida().contains("--json --fatos " + ficha), r.saida());
    }

    @Test
    void comandoAusenteLanca() throws Exception {
        Store s = storeComGate(tmp, null, 30);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TextGate.rodarPre(s, lote(s), List.of()));
        assertTrue(e.getMessage().contains("gate de texto nao configurado"));
    }

    @Test
    void timeoutEErro() throws Exception {
        Store s = storeComGate(tmp, "sleep 5", 1);
        long t0 = System.currentTimeMillis();
        TextGate.Resultado r = TextGate.rodarPre(s, lote(s), List.of());
        assertEquals("ERRO", r.resultado());
        assertTrue(r.saida().contains("gate excedeu 1s"));
        assertTrue(System.currentTimeMillis() - t0 < 4500, "timeout deve cortar antes do sleep");
    }

    @Test
    void hashEstavelEMudaComUmCaractere() {
        JsonNode a = Json.parse("[{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"ola\"}]");
        JsonNode b = Json.parse("[{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"ola\"}]");
        JsonNode c = Json.parse("[{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"olb\"}]");
        String h = TextGate.hashActions(a);
        assertEquals(64, h.length());
        assertEquals(h, h.toLowerCase());
        assertEquals(h, TextGate.hashActions(b));
        assertNotEquals(h, TextGate.hashActions(c));
    }
}
