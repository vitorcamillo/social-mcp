package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GuardTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    @Test
    void redePausadaBloqueiaAcaoPublicaMasPermiteLeitura() {
        Store s = store();
        // threads vem pausada no config default
        assertEquals("paused", s.effectiveState("threads"));
        assertFalse(Guard.check(s, "threads", "click").allowed());
        assertFalse(Guard.check(s, "threads", "type").allowed());
        assertTrue(Guard.check(s, "threads", "goto").allowed());
        assertTrue(Guard.check(s, "threads", "verify_text").allowed());
    }

    @Test
    void redeBannedBloqueiaTudo() {
        Store s = store();
        s.setState("bluesky", "banned", "teste", "teste");
        assertFalse(Guard.check(s, "bluesky", "click").allowed());
        assertFalse(Guard.check(s, "bluesky", "goto").allowed());
    }

    @Test
    void killSwitchBloqueiaTudo() throws Exception {
        Store s = store();
        s.setStopped(true);
        assertTrue(Files.exists(s.stopFile()));
        assertFalse(Guard.check(s, "bluesky", "goto").allowed());
        assertFalse(Guard.check(s, "bluesky", "click").allowed());
    }

    @Test
    void cotaDiariaEsgotadaBloqueiaAcaoPublica() {
        Store s = store();
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 0; i < 4; i++) {
            s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public").put("ts", duasHorasAtras));
        }
        assertTrue(Guard.check(s, "mastodon", "click").allowed());
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public").put("ts", duasHorasAtras));
        Guard.Decision d = Guard.check(s, "mastodon", "click");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("cota"));
        // leitura continua livre
        assertTrue(Guard.check(s, "mastodon", "goto").allowed());
    }

    @Test
    void espacamentoMinimoEntreAcoesPublicas() {
        Store s = store();
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public"));
        Guard.Decision d = Guard.check(s, "mastodon", "click");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("espacamento"));
    }

    @Test
    void agenteNaoPodeReativarRedePausadaViaTool() throws Exception {
        Store s = store();
        s.setState("bluesky", "paused", "teste", "teste");
        Tools t = new Tools(s);
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> t.call("social_set_network_state",
                            Json.parse("{\"network\":\"bluesky\",\"state\":\"healthy\",\"reason\":\"tentativa do agente\"}")));
            assertTrue(e.getMessage().contains("healthy"), e.getMessage());
            assertEquals("paused", s.effectiveState("bluesky"));
        } finally {
            t.shutdown();
        }
    }

    @Test
    void browseReadScreenshotRespeitamStopEBanned() throws Exception {
        Store s = store();
        Tools t = new Tools(s);
        try {
            s.setStopped(true);
            assertThrows(IllegalStateException.class, () -> t.call("social_browse",
                    Json.parse("{\"network\":\"bluesky\",\"url\":\"https://bsky.app\"}")));
            assertThrows(IllegalStateException.class, () -> t.call("social_read",
                    Json.parse("{\"network\":\"bluesky\"}")));
            assertThrows(IllegalStateException.class, () -> t.call("social_screenshot",
                    Json.parse("{\"network\":\"bluesky\"}")));
            s.setStopped(false);
            s.setState("bluesky", "banned", "teste", "teste");
            assertThrows(IllegalStateException.class, () -> t.call("social_browse",
                    Json.parse("{\"network\":\"bluesky\",\"url\":\"https://bsky.app\"}")));
            assertThrows(IllegalStateException.class, () -> t.call("social_read",
                    Json.parse("{\"network\":\"bluesky\"}")));
            assertThrows(IllegalStateException.class, () -> t.call("social_screenshot",
                    Json.parse("{\"network\":\"bluesky\"}")));
        } finally {
            t.shutdown();
        }
    }

    @Test
    void falhaDeLeituraDoJournalPropagaEmVezDeZerarConsumo() throws Exception {
        Store s = store();
        Files.delete(s.journalPath());
        Files.createDirectory(s.journalPath());
        RuntimeException e = assertThrows(RuntimeException.class, () -> s.usageFor("bluesky"));
        assertTrue(e.getMessage().contains("journal"), e.getMessage());
        // a guarda de acao publica tambem fecha (nao libera por consumo zero)
        assertThrows(RuntimeException.class, () -> Guard.check(s, "bluesky", "click"));
        assertThrows(RuntimeException.class, () -> s.batchStarted("b1"));
    }

    @Test
    void tripwireDetectaPadroesDeAntiBot() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        assertTrue(Guard.hitsTripwire("Você está fazendo isso muito rápido. Rate limit exceeded.", cfg));
        assertTrue(Guard.hitsTripwire("Sua conta suspensa por atividade incomum.", cfg));
        assertFalse(Guard.hitsTripwire("Hoje tem jogo do Corinthians às 17h30.", cfg));
        assertFalse(Guard.hitsTripwire("", cfg));
        assertFalse(Guard.hitsTripwire(null, cfg));
    }

    @Test
    void tripwireIgnoraFraseConhecidaMasPegaDesafioNovo() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        long agora = System.currentTimeMillis();
        cfg.putArray("tripwireIgnore").addObject()
                .put("frase", "Para continuar usando o Reddit, verifique se você é humano ou registre-se como um app")
                .put("criadoEm", agora - 3_600_000L).put("expiraEm", agora + 3_600_000L);
        String notificacao = "Verifique sua conta\nPara continuar usando o Reddit, verifique se você\né humano ou registre-se como um app em até 7 dias.";
        assertFalse(Guard.hitsTripwire(notificacao, cfg));
        assertTrue(Guard.hitsTripwire(notificacao + " Verifique se você é humano para continuar.", cfg));
    }

    /** Texto cujo unico padrao de anti-bot esta DENTRO da frase ignorada. */
    private static final String PAGINA_HUMANA = "Para continuar usando o Reddit, verifique se você é humano para continuar.";

    @Test
    void erroDeCompartilharDoShortsNaoEhBloqueio() {
        ObjectNode cfg = store().networkCfg("youtube");
        String pagina = "Assistir mais tarde Compartilhar Ocorreu um erro ao recuperar informações de\n"
                + "compartilhamento. Tente novamente mais tarde. Copiar link";
        assertNull(Guard.tripwireMatch(pagina, cfg, System.currentTimeMillis()));
    }

    @Test
    void tenteNovamenteSoltoContinuaDisparando() {
        ObjectNode cfg = store().networkCfg("youtube");
        String pagina = "Ocorreu um erro ao recuperar informações de compartilhamento. Tente novamente mais tarde. "
                + "Você publicou demais. Tente novamente mais tarde.";
        assertEquals("tente novamente mais tarde", Guard.tripwireMatch(pagina, cfg, System.currentTimeMillis()));
    }

    @Test
    void tripwireIgnoreEmTextoPuroNaoIgnoraMais() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        // formato antigo (texto puro) nao vale mais: a frase volta a disparar
        cfg.putArray("tripwireIgnore").add("Para continuar usando o Reddit, verifique se você é humano");
        assertNotNull(Guard.tripwireMatch(PAGINA_HUMANA, cfg, System.currentTimeMillis()));
    }

    @Test
    void tripwireIgnoreObjetoDentroDoPrazoIgnora() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        long agora = System.currentTimeMillis();
        ObjectNode item = cfg.putArray("tripwireIgnore").addObject();
        item.put("frase", "Para continuar usando o Reddit, verifique se você é humano")
                .put("criadoEm", agora - 3_600_000L)
                .put("expiraEm", agora + 3_600_000L);
        assertNull(Guard.tripwireMatch(PAGINA_HUMANA, cfg, agora));
    }

    @Test
    void tripwireIgnoreObjetoExpiradoNaoIgnora() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        long agora = System.currentTimeMillis();
        ObjectNode item = cfg.putArray("tripwireIgnore").addObject();
        item.put("frase", "Para continuar usando o Reddit, verifique se você é humano")
                .put("criadoEm", agora - 48 * 3_600_000L)
                .put("expiraEm", agora - 3_600_000L);
        assertNotNull(Guard.tripwireMatch(PAGINA_HUMANA, cfg, agora));
    }

    @Test
    void tripwireIgnoreTetoDe24hVencePrazoLongo() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        long agora = System.currentTimeMillis();
        // expiraEm a 3 dias, mas criadoEm ha 25h: o teto de criadoEm + 24h ja venceu
        ObjectNode item = cfg.putArray("tripwireIgnore").addObject();
        item.put("frase", "Para continuar usando o Reddit, verifique se você é humano")
                .put("criadoEm", agora - 25 * 3_600_000L)
                .put("expiraEm", agora + 72 * 3_600_000L);
        assertNotNull(Guard.tripwireMatch(PAGINA_HUMANA, cfg, agora));
    }

    @Test
    void aplicarTripwireColocaCooldownEBloqueiaAcaoPublica() {
        Store s = store();
        Tools tools = new Tools(s);
        try {
            assertEquals("healthy", s.effectiveState("bluesky"));
            assertTrue(Guard.check(s, "bluesky", "click").allowed());
            ObjectNode aviso = Json.obj();
            assertTrue(tools.aplicarTripwire("bluesky", "Rate limit exceeded", "leitura de teste", aviso));
            assertEquals("cooldown", s.effectiveState("bluesky"));
            assertTrue(s.stateReason("bluesky").startsWith("leitura de teste"), s.stateReason("bluesky"));
            assertTrue(s.stateReason("bluesky").contains("rate limit"), s.stateReason("bluesky"));
            assertTrue(aviso.path("tripwire").asBoolean());
            assertTrue(aviso.path("AVISO").asText().contains("rede em cooldown"));
            assertEquals("rate limit", aviso.path("tripwirePadrao").asText());
            assertTrue(aviso.path("tripwireContexto").asText().toLowerCase().contains("rate limit"));
            assertFalse(Guard.check(s, "bluesky", "click").allowed());

            assertEquals("healthy", s.effectiveState("mastodon"));
            ObjectNode limpo = Json.obj();
            assertFalse(tools.aplicarTripwire("mastodon", "postagem normal", "sem match", limpo));
            assertEquals("healthy", s.effectiveState("mastodon"));
            assertFalse(limpo.path("tripwire").asBoolean());
            assertTrue(limpo.path("AVISO").isMissingNode());
        } finally {
            tools.shutdown();
        }
    }

    @Test
    void configDefaultVemComThreadsERedditPausados() {
        Store s = store();
        assertEquals("paused", s.effectiveState("threads"));
        assertEquals("paused", s.effectiveState("reddit"));
        assertEquals("healthy", s.effectiveState("bluesky"));
    }

    @Test
    void passosDoMesmoLoteNaoSeBloqueiamPorEspacamento() {
        Store s = store();
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "type").put("cls", "public").put("batchId", "b1"));
        // passo seguinte do MESMO lote: o lote nao se bloqueia
        assertTrue(Guard.check(s, "mastodon", "type", "b1").allowed());
        // outro lote: sofre o espacamento
        Guard.Decision d = Guard.check(s, "mastodon", "click", "b2");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("espacamento"));
        // sem currentBatchId (chamada antiga): tambem sofre o espacamento
        assertFalse(Guard.check(s, "mastodon", "click").allowed());
    }

    @Test
    void loteContaComoUmaAcaoNaCota() {
        Store s = store();
        long duasHorasAtras = System.currentTimeMillis() - 7_200_000L;
        for (int i = 1; i <= 4; i++) {
            String bid = "b" + i;
            for (int j = 0; j < 3; j++) {
                s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public")
                        .put("batchId", bid).put("ts", duasHorasAtras));
            }
        }
        // 12 passos publicos em 4 lotes = 4 unidades de cota (nao 12)
        assertEquals(4, s.usageFor("mastodon").publicActions);
        assertTrue(Guard.check(s, "mastodon", "click", "b9").allowed());
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public")
                .put("batchId", "b5").put("ts", duasHorasAtras));
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public")
                .put("batchId", "b5").put("ts", duasHorasAtras));
        Guard.Decision d = Guard.check(s, "mastodon", "click", "b6");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("cota"));
    }

    @Test
    void lotesExecutadosContamPorRede() {
        Store s = store();
        for (int i = 0; i < 4; i++) {
            s.appendJournal("batch_executed", "mastodon", Json.obj().put("batchId", "b" + i));
        }
        assertEquals(4, s.usageFor("mastodon").batches);
        assertEquals(0, s.usageFor("bluesky").batches);
    }

    @Test
    void batchStartedDetectaExecucaoParcial() {
        Store s = store();
        assertFalse(s.batchStarted("b7"));
        s.appendJournal("action", "mastodon", Json.obj().put("kind", "click").put("cls", "public").put("batchId", "b7"));
        assertTrue(s.batchStarted("b7"));
        assertFalse(s.batchStarted("b8"));
    }

    @Test
    void kwaiVemPausadoNoConfigDefault() {
        Store s = store();
        assertEquals("paused", s.effectiveState("kwai"));
    }

    @Test
    void requestEPublicoEExtractNao() {
        assertTrue(Guard.isPublic("request"));
        assertFalse(Guard.isPublic("extract"));
        assertFalse(Guard.isPublic("set_file"));
        assertFalse(Guard.isPublic("screenshot"));
        assertFalse(Guard.isPublic("wait"));
        Store s = store();
        // threads pausada: request bloqueado como click, extract liberado como leitura
        assertFalse(Guard.check(s, "threads", "request").allowed());
        assertTrue(Guard.check(s, "threads", "extract").allowed());
        assertTrue(Guard.check(s, "threads", "screenshot").allowed());
        assertTrue(Guard.check(s, "threads", "wait").allowed());
    }

    @Test
    void tripwireMatchDevolvePadraoComoNaConfig() {
        Store s = store();
        ObjectNode cfg = s.networkCfg("reddit");
        assertEquals("rate limit", Guard.tripwireMatch("Voce fez isso muito rapido. Rate Limit exceeded!", cfg));
        assertNull(Guard.tripwireMatch("Hoje tem jogo do Corinthians as 17h30.", cfg));
        assertNull(Guard.tripwireMatch("", cfg));
        assertNull(Guard.tripwireMatch(null, cfg));
    }

    @Test
    void contextoTripwireCortaEAchataQuebrasDeLinha() {
        String texto = "legenda de fã\ncomentario normal\nToo Fast, tente de novo\noutro comentario";
        String ctx = Guard.contextoTripwire(texto, "too fast");
        assertFalse(ctx.contains("\n"), ctx);
        assertFalse(ctx.contains("\r"), ctx);
        assertTrue(ctx.toLowerCase().contains("too fast"), ctx);
        assertTrue(ctx.length() <= 160, String.valueOf(ctx.length()));
        String longo = "x".repeat(200) + "rate limit" + "y".repeat(200);
        String ctx2 = Guard.contextoTripwire(longo, "rate limit");
        assertTrue(ctx2.length() <= 160, String.valueOf(ctx2.length()));
        assertTrue(ctx2.toLowerCase().contains("rate limit"), ctx2);
        assertEquals("", Guard.contextoTripwire(texto, "padrao ausente"));
    }
}
