package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cota propria de engajamento leve: classificacao {@code EngajamentoLeve.eh()}, Guarda,
 * fail-fast do lote e journal. Tudo sem navegador: recusas acontecem antes do claim e a
 * permissao e exercida via Guarda + pegada de journal identica a da execucao.
 */
class EngajamentoLeveTest {

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

    private void comEngagement(Store store, String rede, int max, int min) throws Exception {
        ObjectNode raiz = (ObjectNode) Json.parse(Files.readString(store.configPath(), StandardCharsets.UTF_8));
        ObjectNode alvo = rede == null
                ? (ObjectNode) raiz.path("defaults")
                : (ObjectNode) raiz.path("networks").path(rede);
        alvo.putObject("engagement").put("maxPerDay", max).put("minMinutesBetween", min);
        Files.writeString(store.configPath(), Json.writePretty(raiz), StandardCharsets.UTF_8);
    }

    private static String acoesLeves() {
        return "[{\"network\":\"bluesky\",\"kind\":\"goto\",\"url\":\"https://bsky.app/\",\"description\":\"abrir\"},"
                + "{\"network\":\"bluesky\",\"kind\":\"click\",\"selector\":\"css:button\",\"description\":\"curtir\"}]";
    }

    private static ObjectNode origemLeve(String receita) {
        return Json.obj().put("receita", receita).put("provada", true);
    }

    private static ObjectNode loteDe(String receita, String acoesJson) {
        ObjectNode lote = Json.obj();
        lote.set("origem", origemLeve(receita));
        lote.set("actions", Json.parse(acoesJson));
        return lote;
    }

    private static ObjectNode loteGoto(String url) {
        return loteDe("like_ui", "[{\"kind\":\"goto\",\"url\":\"" + url + "\"},"
                + "{\"kind\":\"click\",\"selector\":\"css:b\"}]");
    }

    private String aprovarLeve(Store store) throws Exception {
        BatchStore bs = new BatchStore(store);
        String id = bs.draft("curtida", Json.parse(acoesLeves()), origemLeve("like_ui")).path("id").asText();
        bs.approve(id, "humano", "nota de teste", List.of(), true, false);
        return id;
    }

    private String executar(Tools tools, String id) throws Exception {
        return tools.call("social_execute_batch", Json.obj().put("batchId", id));
    }

    private void semearPublica(Store store, String batchId, long ts) {
        store.appendJournal("action", "bluesky", Json.obj().put("kind", "click").put("cls", "public")
                .put("batchId", batchId).put("ts", ts));
    }

    /** Pegada de journal de um lote leve executado: marco + acao de engajamento + lote executado. */
    private void simularExecucaoLeve(Store store, String batchId) {
        Tools.marcarLoteEngajamento(store, "bluesky", batchId);
        Tools.journalAction(store, "bluesky", "click", Json.obj().put("description", "curtir"),
                batchId, Json.obj(), true, true);
        store.appendJournal("batch_executed", "bluesky",
                Json.obj().put("batchId", batchId).put("day", store.today()));
    }

    // ---------- EngajamentoLeve.eh ----------

    @Test
    void ehAceitaLoteLeveValido() {
        assertTrue(EngajamentoLeve.eh(loteDe("like_ui", acoesLeves())));
        assertTrue(EngajamentoLeve.eh(loteDe("follow_private_ui",
                "[{\"kind\":\"goto\",\"url\":\"https://bsky.app/u\"},"
                        + "{\"kind\":\"wait\",\"ms\":500},"
                        + "{\"kind\":\"click\",\"selector\":\"css:a\"},"
                        + "{\"kind\":\"click\",\"selector\":\"css:b\"},"
                        + "{\"kind\":\"verify_text\",\"expect\":\"Seguindo\"}]")));
        for (String receita : List.of("unlike_ui", "follow_ui", "unfollow_ui")) {
            assertTrue(EngajamentoLeve.eh(loteDe(receita, acoesLeves())), receita);
        }
    }

    @Test
    void ehAceitaKindEmMaiusculasComoMinusculo() {
        assertTrue(EngajamentoLeve.eh(loteDe("like_ui",
                "[{\"kind\":\"goto\",\"url\":\"https://bsky.app/\"},{\"kind\":\"Click\",\"selector\":\"css:b\"}]")));
    }

    @Test
    void ehRecusaQuandoPassoTemText() {
        assertFalse(EngajamentoLeve.eh(loteDe("like_ui",
                "[{\"kind\":\"goto\",\"url\":\"https://bsky.app/\"},"
                        + "{\"kind\":\"verify_text\",\"expect\":\"x\",\"text\":\"y\"}]")));
    }

    @Test
    void ehRecusaComMidias() {
        ObjectNode lote = loteDe("like_ui", acoesLeves());
        lote.set("midias", Json.parse("[{\"arquivo\":\"a.png\"}]"));
        assertFalse(EngajamentoLeve.eh(lote));
        ObjectNode vazio = loteDe("like_ui", acoesLeves());
        vazio.set("midias", Json.parse("[]"));
        assertTrue(EngajamentoLeve.eh(vazio));
        ObjectNode nulo = loteDe("like_ui", acoesLeves());
        nulo.putNull("midias");
        assertTrue(EngajamentoLeve.eh(nulo));
    }

    @Test
    void ehRecusaReceitaQueNaoEDeGesto() {
        assertFalse(EngajamentoLeve.eh(loteDe("reply_ui", acoesLeves())));
        ObjectNode semOrigem = Json.obj();
        semOrigem.set("actions", Json.parse(acoesLeves()));
        assertFalse(EngajamentoLeve.eh(semOrigem));
        assertFalse(EngajamentoLeve.eh(null));
    }

    @Test
    void ehRecusaTresClicksOuNenhum() {
        assertFalse(EngajamentoLeve.eh(loteDe("like_ui",
                "[{\"kind\":\"click\",\"selector\":\"css:a\"},"
                        + "{\"kind\":\"click\",\"selector\":\"css:b\"},"
                        + "{\"kind\":\"click\",\"selector\":\"css:c\"}]")));
        assertFalse(EngajamentoLeve.eh(loteDe("like_ui",
                "[{\"kind\":\"goto\",\"url\":\"https://bsky.app/\"},{\"kind\":\"wait\",\"ms\":100}]")));
    }

    @Test
    void ehRecusaKindNaoPermitido() {
        assertFalse(EngajamentoLeve.eh(loteDe("like_ui",
                "[{\"kind\":\"goto\",\"url\":\"https://bsky.app/\"},{\"kind\":\"type\",\"selector\":\"css:b\"}]")));
    }

    @Test
    void ehRecusaGotoComTextNaQuery() {
        assertFalse(EngajamentoLeve.eh(loteGoto("https://bsky.app/?text=oi")));
        assertFalse(EngajamentoLeve.eh(loteGoto("https://bsky.app/x?a=1&text=oi")));
        assertFalse(EngajamentoLeve.eh(loteGoto("https://bsky.app/x?%74%65%78%74=oi")));
        assertTrue(EngajamentoLeve.eh(loteGoto("https://bsky.app/x?a=1&b=2")));
        assertTrue(EngajamentoLeve.eh(loteGoto("https://bsky.app/x#text=oi")));
    }

    // ---------- cota propria ----------

    @Test
    void tresLevesPassamComCotasEsgotadasEQuartoRecusa() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        comEngagement(store, "bluesky", 3, 0);
        // cota de posts e de lotes esgotadas
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 0; i < 5; i++) semearPublica(store, "post-" + i, duasHorasAtras);
        for (int i = 0; i < 4; i++) {
            store.appendJournal("batch_executed", "bluesky", Json.obj().put("batchId", "lote-" + i));
        }
        // os 3 leves passam na guarda mesmo assim
        long agora = System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            assertTrue(Guard.check(store, "bluesky", "click", "leve-" + i, agora, true).allowed(),
                    "leve-" + i + " deveria passar");
            simularExecucaoLeve(store, "leve-" + i);
        }
        // nenhum consumo nas cotas de posts/lotes; lastPublicActionMs intacto
        Store.Usage u = store.usageFor("bluesky");
        assertEquals(5, u.publicActions);
        assertEquals(duasHorasAtras, u.lastPublicActionMs);
        assertEquals(3, u.engagementActions);
        assertEquals(4, u.batches);
        // o 4o recusa na cota de engajamento, antes de qualquer navegador
        Guard.Decision d = Guard.check(store, "bluesky", "click", "leve-4", agora, true);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("cota diaria de engajamento de 'bluesky' esgotada (3/3)"), d.reason());
        String id = aprovarLeve(store);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("cota diaria de engajamento de 'bluesky' esgotada (3/3)"), e.getMessage());
        assertTrue(Files.exists(store.batchesDir().resolve(id + ".approved.json")), "recusa antes do claim");
    }

    @Test
    void espacamentoDeEngajamentoRecusaELiberaComRelogio() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        comEngagement(store, "bluesky", 5, 30);
        long base = System.currentTimeMillis() - 60_000L;
        store.appendJournal("action", "bluesky", Json.obj().put("kind", "click").put("cls", "engagement")
                .put("batchId", "leve-1").put("ts", base));
        Guard.Decision recusada = Guard.check(store, "bluesky", "click", "leve-2", base + 60_000L, true);
        assertFalse(recusada.allowed());
        assertTrue(recusada.reason().contains("engajamento"), recusada.reason());
        assertTrue(recusada.reason().contains("~29 min"), recusada.reason());
        assertTrue(Guard.check(store, "bluesky", "click", "leve-2", base + 30 * 60_000L, true).allowed());
        // o fail-fast do executeLocked usa o relogio injetavel de Tools
        LongSupplier anterior = Tools.relogio;
        Tools.relogio = () -> base + 60_000L;
        try {
            String id = aprovarLeve(store);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
            assertTrue(e.getMessage().contains("engajamento"), e.getMessage());
            assertTrue(e.getMessage().contains("~29 min"), e.getMessage());
        } finally {
            Tools.relogio = anterior;
        }
    }

    @Test
    void semEngagementLeveContaNaCotaDePosts() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        // sem chave engagement: comportamento atual inalterado
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 0; i < 5; i++) semearPublica(store, "post-" + i, duasHorasAtras);
        Guard.Decision d = Guard.check(store, "bluesky", "click", "leve-1", System.currentTimeMillis(), true);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("cota diaria de acoes publicas"), d.reason());
        String id = aprovarLeve(store);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("excede a cota diaria"), e.getMessage());
        assertFalse(e.getMessage().contains("engajamento"), e.getMessage());
    }

    @Test
    void engagementZeradoMantemComportamentoAtual() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        comEngagement(store, "bluesky", 0, 0); // maxPerDay <= 0 = inativo
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 0; i < 5; i++) semearPublica(store, "post-" + i, duasHorasAtras);
        String id = aprovarLeve(store);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("excede a cota diaria"), e.getMessage());
    }

    @Test
    void engagementNosDefaultsValeParaARede() throws Exception {
        Store store = novoStore();
        comEngagement(store, null, 2, 0); // engagement em defaults
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 0; i < 5; i++) semearPublica(store, "post-" + i, duasHorasAtras);
        // posts esgotados, mas o leve passa pela cota propria herdada de defaults
        assertTrue(Guard.check(store, "bluesky", "click", "leve-1", System.currentTimeMillis(), true).allowed());
    }

    @Test
    void leveNaoConsomeCotaDePostsDepoisTresLevesPostPassa() throws Exception {
        Store store = novoStore();
        comEngagement(store, "bluesky", 3, 0);
        long agora = System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            assertTrue(Guard.check(store, "bluesky", "click", "leve-" + i, agora, true).allowed());
            simularExecucaoLeve(store, "leve-" + i);
        }
        Store.Usage u = store.usageFor("bluesky");
        assertEquals(0, u.publicActions);
        assertEquals(0, u.lastPublicActionMs);
        assertEquals(0, u.batches);
        assertTrue(u.lastEngagementMs > 0);
        assertTrue(Guard.check(store, "bluesky", "click", "post-1", agora, false).allowed());
    }

    @Test
    void loteLeveNaoSeBloqueiaENaoContaDuasVezes() throws Exception {
        Store store = novoStore();
        comEngagement(store, "bluesky", 3, 0);
        long agora = System.currentTimeMillis();
        simularExecucaoLeve(store, "leve-1");
        simularExecucaoLeve(store, "leve-1"); // reexecucao: journal repetido nao conta duas vezes
        Store.Usage u = store.usageFor("bluesky");
        assertEquals(1, u.engagementActions);
        assertEquals(0, store.usageFor("bluesky", "leve-1").engagementActions);
        assertTrue(Guard.check(store, "bluesky", "click", "leve-1", agora, true).allowed());
        Guard.Decision d = Guard.check(store, "bluesky", "click", "leve-2", agora, true);
        assertTrue(d.allowed());
    }

    @Test
    void cooldownRecusaLeveMesmoComEngagement() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        comEngagement(store, "bluesky", 3, 0);
        store.setState("bluesky", "cooldown", "teste", "teste");
        Guard.Decision d = Guard.check(store, "bluesky", "click", "leve-1", System.currentTimeMillis(), true);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("cooldown"), d.reason());
        String id = aprovarLeve(store);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> executar(tools, id));
        assertTrue(e.getMessage().contains("cooldown"), e.getMessage());
    }

    @Test
    void statusMostraEngajamentoQuandoAtivo() throws Exception {
        Store store = novoStore();
        Tools tools = novaTools(store);
        comEngagement(store, "bluesky", 3, 0);
        JsonNode com = Json.parse(tools.call("social_status", Json.obj()));
        assertEquals(0, com.path("redes").path("bluesky").path("engajamentoHoje").asInt(-1));
        assertEquals(3, com.path("redes").path("bluesky").path("cotaEngajamento").asInt(-1));
        Store outro = novoStore();
        Tools ferramentas2 = novaTools(outro);
        JsonNode sem = Json.parse(ferramentas2.call("social_status", Json.obj()));
        assertTrue(sem.path("redes").path("bluesky").path("engajamentoHoje").isMissingNode());
        assertTrue(sem.path("redes").path("bluesky").path("cotaEngajamento").isMissingNode());
    }

    // ---------- virada do dia ----------

    @Test
    void espacamentoDeEngajamentoAtravessaMeiaNoite() throws Exception {
        Store store = novoStore();
        comEngagement(store, "bluesky", 30, 30);
        String ontem = java.time.LocalDate.parse(store.today()).minusDays(1).toString();
        long agora = System.currentTimeMillis();
        // acao as 23h59 de ontem (1 min atras): a de 00h01 tem que esperar o espacamento
        store.appendJournal("action", "bluesky", Json.obj().put("kind", "click").put("cls", "engagement")
                .put("batchId", "ontem-1").put("day", ontem).put("ts", agora - 60_000L));
        Guard.Decision d = Guard.check(store, "bluesky", "click", "leve-novo", agora, true);
        assertFalse(d.allowed(), d.reason());
        assertTrue(d.reason().contains("espacamento minimo de 30 min entre acoes de engajamento"), d.reason());
        // nao conta na cota de hoje: a acao foi ontem
        assertEquals(0, store.usageFor("bluesky").engagementActions);
        // passado o espacamento, libera
        assertTrue(Guard.check(store, "bluesky", "click", "leve-novo", agora + 30 * 60_000L, true).allowed());
    }

    @Test
    void loteLeveQueAtravessaMeiaNoiteNaoGastaCotaDeLotes() throws Exception {
        Store store = novoStore();
        comEngagement(store, "bluesky", 30, 0);
        String ontem = java.time.LocalDate.parse(store.today()).minusDays(1).toString();
        // marcado como leve antes da meia-noite, executado depois
        store.appendJournal(Store.EVENTO_LOTE_ENGAJAMENTO, "bluesky",
                Json.obj().put("batchId", "noite-1").put("day", ontem));
        store.appendJournal("batch_executed", "bluesky",
                Json.obj().put("batchId", "noite-1").put("day", store.today()));
        assertEquals(0, store.usageFor("bluesky").batches);
    }
}
