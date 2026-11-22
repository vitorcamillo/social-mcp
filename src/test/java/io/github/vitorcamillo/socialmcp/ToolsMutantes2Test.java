package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Mutantes de Tools.probe (sem navegador), Tools.findDuplicates (rede) e Tools.resolverIncerteza. */
class ToolsMutantes2Test {

    @TempDir
    Path tmp;

    private final List<Tools> abertos = new ArrayList<>();
    private int casas;

    @AfterEach
    void fechaChromes() {
        for (Tools t : abertos) t.shutdown();
        abertos.clear();
    }

    private Store casa() {
        return new Store(tmp.resolve("casa-" + (casas++) + "-" + System.nanoTime()));
    }

    private Tools tools(Store s) {
        Tools t = new Tools(s);
        abertos.add(t);
        return t;
    }

    /** Grava <id>.executed.json como BatchStore.markExecuted deixa. */
    private void executado(Store s, String id, String network, String conferencia) throws Exception {
        long agora = System.currentTimeMillis();
        ObjectNode b = Json.obj();
        b.put("id", id);
        b.put("label", "t");
        b.put("status", "executed");
        b.put("createdAt", agora);
        b.put("executedAt", agora);
        b.put("network", network);
        b.set("result", Json.obj().put("conferencia", conferencia));
        Files.writeString(s.batchesDir().resolve(id + ".executed.json"), Json.writePretty(b), StandardCharsets.UTF_8);
    }

    private ObjectNode argsIncerteza(String batchId, String resultado, String prova) {
        return Json.obj().put("batchId", batchId).put("resultado", resultado).put("prova", prova).put("nota", "visto");
    }

    // ---------- probe sem navegador (preparo falha: sem receita no tmp) ----------

    @Test
    void sondaComRedeDesconhecidaLancaAntesDePreparar() {
        Store s = casa();
        Tools t = tools(s);
        ObjectNode args = Json.obj().put("network", "rede_fantasma_xyz").put("action", "acao_que_nao_existe");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.call("social_probe", args));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    @Test
    void sondaComPreparoFalhadoRetornaOkFalseComMotivo() throws Exception {
        Store s = casa();
        Tools t = tools(s);
        ObjectNode args = Json.obj().put("network", "instagram").put("action", "acao_que_nao_existe");
        String saida = assertDoesNotThrow(() -> t.call("social_probe", args));
        JsonNode out = Json.parse(saida);
        assertEquals("instagram", out.path("network").asText());
        assertFalse(out.path("ok").asBoolean(true), saida);
        assertTrue(out.path("motivo").asText("").contains("nenhuma receita para a rede 'instagram'"), saida);
        assertFalse(out.path("tripwire").asBoolean(true), saida);
        assertTrue(out.path("primeiroControle").isNull(), saida);
        assertTrue(out.has("passos"), saida);
    }

    @Test
    void sondaComPreparoFalhadoRegistraJournalDeProbe() throws Exception {
        Store s = casa();
        Tools t = tools(s);
        t.call("social_probe", Json.obj().put("network", "instagram").put("action", "acao_que_nao_existe"));
        String cauda = String.join("\n", s.tail(10));
        assertTrue(cauda.contains("\"type\":\"probe\""), cauda);
        assertTrue(cauda.contains("\"action\":\"acao_que_nao_existe\""), cauda);
    }

    // ---------- findDuplicates sem navegador ----------

    @Test
    void duplicadasComRedeDesconhecidaLancaRedeDesconhecida() {
        Store s = casa();
        Tools t = tools(s);
        ObjectNode args = Json.obj().put("network", "rede_fantasma_xyz").put("maxPosts", 5);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_find_duplicates", args));
        assertTrue(e.getMessage().contains("rede desconhecida"), e.getMessage());
    }

    // ---------- resolverIncerteza (sem navegador) ----------

    @Test
    void incertezaComBatchIdInvalidoRejeita() {
        Store s = casa();
        Tools t = tools(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("lote invalido!", "no_ar", "https://rede/p/abc12345")));
        assertTrue(e.getMessage().contains("batchId invalido"), e.getMessage());
    }

    @Test
    void incertezaSemArquivoExecutadoRejeita() {
        Store s = casa();
        Tools t = tools(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("x9", "no_ar", "https://rede/p/abc12345")));
        assertTrue(e.getMessage().contains("nao esta executado"), e.getMessage());
    }

    @Test
    void incertezaComConferenciaForteNaoPedeResolucao() throws Exception {
        Store s = casa();
        executado(s, "f1", "instagram", "forte");
        Tools t = tools(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("f1", "no_ar", "https://rede/p/abc12345")));
        assertTrue(e.getMessage().contains("nao tem envio incerto"), e.getMessage());
    }

    @Test
    void incertezaComResultadoInvalidoRejeita() throws Exception {
        Store s = casa();
        executado(s, "r1", "instagram", "abortado_apos_publico");
        Tools t = tools(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("r1", "talvez", "https://rede/p/abc12345")));
        assertTrue(e.getMessage().contains("resultado invalido"), e.getMessage());
    }

    @Test
    void incertezaComProvaDeSeteCharsRejeitaEOitoPassa() throws Exception {
        Store s = casa();
        executado(s, "p7", "instagram", "duplicata_suspeita");
        executado(s, "p8", "instagram", "duplicata_suspeita");
        Tools t = tools(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("p7", "no_ar", "1234567")));
        assertTrue(e.getMessage().contains("prova obrigatoria"), e.getMessage());
        String saida = assertDoesNotThrow(
                () -> t.call("social_resolve_incerteza", argsIncerteza("p8", "apagado", "12345678")));
        JsonNode out = Json.parse(saida);
        assertEquals("p8", out.path("batchId").asText());
        assertEquals("apagado", out.path("resultado").asText());
    }

    @Test
    void incertezaResolveEGravaJournalComJsonDeVolta() throws Exception {
        Store s = casa();
        executado(s, "lote-7", "instagram", "abortado_apos_publico");
        Tools t = tools(s);
        String saida = assertDoesNotThrow(
                () -> t.call("social_resolve_incerteza", argsIncerteza("lote-7", "no_ar", "https://rede/p/abc12345")));
        JsonNode out = Json.parse(saida);
        assertEquals("lote-7", out.path("batchId").asText());
        assertEquals("instagram", out.path("network").asText());
        assertEquals("no_ar", out.path("resultado").asText());
        assertTrue(out.path("pendentesNaRede").isArray(), saida);
        assertTrue(s.incertezasPendentes("instagram").isEmpty());
        String cauda = String.join("\n", s.tail(10));
        assertTrue(cauda.contains("\"type\":\"incerteza_resolvida\""), cauda);
        assertTrue(cauda.contains("\"batchId\":\"lote-7\""), cauda);
    }

    @Test
    void incertezaResolvidaDuasVezesRejeitaNaSegunda() throws Exception {
        Store s = casa();
        executado(s, "dup-1", "instagram", "abortado_apos_publico");
        Tools t = tools(s);
        assertDoesNotThrow(
                () -> t.call("social_resolve_incerteza", argsIncerteza("dup-1", "nao_saiu", "li o perfil e nao saiu")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> t.call("social_resolve_incerteza", argsIncerteza("dup-1", "nao_saiu", "li o perfil e nao saiu")));
        assertTrue(e.getMessage().contains("ja foi resolvido"), e.getMessage());
    }
}
