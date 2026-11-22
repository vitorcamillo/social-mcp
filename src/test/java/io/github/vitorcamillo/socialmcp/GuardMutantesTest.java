package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes que fixam o comportamento observavel exato do Guard (mensagens, limites,
 * leitura de datas e contexto de tripwire) para matar mutantes de PIT.
 */
class GuardMutantesTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    private static void gravar(Store s, ObjectNode cfg) throws Exception {
        Files.writeString(s.configPath(), Json.writePretty(cfg), StandardCharsets.UTF_8);
    }

    /** Config minima com um tripwire e um item de tripwireIgnore (o chamador preenche o item). */
    private static ObjectNode cfgComIgnore(ObjectNode item) {
        ObjectNode cfg = Json.obj();
        cfg.putArray("tripwires").add("rate limit");
        cfg.putArray("tripwireIgnore").add(item);
        return cfg;
    }

    // ---------- Decision.toString ----------

    @Test
    void toStringDeDecisaoPermitidaUsaPrefixoPermitido() {
        assertEquals("PERMITIDO: acao de leitura", new Guard.Decision(true, "acao de leitura").toString());
    }

    @Test
    void toStringDeDecisaoBloqueadaUsaPrefixoBloqueado() {
        assertEquals("BLOQUEADO: cota esgotada", new Guard.Decision(false, "cota esgotada").toString());
    }

    // ---------- rede desconhecida ----------

    @Test
    void redeDesconhecidaDevolveDecisaoBloqueadaComAsRedesValidas() {
        Store s = store();
        Guard.Decision d = Guard.check(s, "orkut", "goto");
        assertNotNull(d, "rede desconhecida nunca pode devolver decisao nula");
        assertFalse(d.allowed());
        assertEquals("rede desconhecida: 'orkut'. Redes validas: " + s.knownNetworks(), d.reason());
    }

    // ---------- espacamento entre acoes publicas ----------

    /**
     * lastPublicActionMs == 0 e o sentinela de "sem acao publica": a linha do journal com
     * ts 0 nao entra na checagem de espacamento mesmo com um intervalo minimo gigante.
     */
    @Test
    void ultimaAcaoPublicaComTsZeroNaoEntraNoEspacamento() throws Exception {
        Store s = store();
        ObjectNode cfg = (ObjectNode) s.config();
        ((ObjectNode) cfg.get("defaults")).put("minMinutesBetweenPublicActions", Integer.MAX_VALUE);
        gravar(s, cfg);
        s.appendJournal("action", "mastodon", Json.obj()
                .put("kind", "click").put("cls", "public").put("ts", 0L));

        assertEquals(0L, s.usageFor("mastodon").lastPublicActionMs);
        Guard.Decision d = Guard.check(s, "mastodon", "click");
        assertTrue(d.allowed(), d.reason());
    }

    /** 24 min decorridos de 30: faltam exatamente 6 min arredondados para cima (359.999 ms). */
    @Test
    void espacamentoInformaMinutosQueFaltamArredondandoParaCima() {
        Store s = store();
        long agora = System.currentTimeMillis();
        s.appendJournal("action", "mastodon", Json.obj()
                .put("kind", "click").put("cls", "public").put("ts", agora - 1_440_000L));

        Guard.Decision d = Guard.check(s, "mastodon", "click");
        assertFalse(d.allowed());
        assertEquals("espacamento minimo de 30 min entre acoes publicas em 'mastodon'; faltam ~6 min",
                d.reason());
    }

    /** Limite exato: elapsed == need ja libera (nao pode bloquear por 1 ms). */
    @Test
    void espacamentoLiberaNoInstanteExatoDoIntervalo() {
        Store s = store();
        int minMin = s.networkCfg("mastodon").path("minMinutesBetweenPublicActions").asInt(30);
        long agora = 1_900_000_000_000L;
        s.appendJournal("action", "mastodon", Json.obj()
                .put("kind", "click").put("cls", "public").put("ts", agora - minMin * 60_000L));

        Guard.Decision d = Guard.check(s, "mastodon", "click", null, agora);
        assertTrue(d.allowed(), d.reason());
    }

    /** Um milissegundo antes do intervalo ainda bloqueia e informa ~1 min que falta. */
    @Test
    void espacamentoBloqueiaUmMilissegundoAntesDoIntervalo() {
        Store s = store();
        int minMin = s.networkCfg("mastodon").path("minMinutesBetweenPublicActions").asInt(30);
        long agora = 1_900_000_000_000L;
        s.appendJournal("action", "mastodon", Json.obj()
                .put("kind", "click").put("cls", "public").put("ts", agora - minMin * 60_000L + 1));

        Guard.Decision d = Guard.check(s, "mastodon", "click", null, agora);
        assertFalse(d.allowed());
        assertEquals("espacamento minimo de " + minMin + " min entre acoes publicas em 'mastodon'; faltam ~1 min",
                d.reason());
    }

    // ---------- epochMsDe ----------

    @Test
    void epochMsDeNumeroTextoEhOProprioNumero() {
        assertEquals(1_000L, Guard.epochMsDe(Json.parse("\"1000\"")));
    }

    @Test
    void epochMsDeTextoIsoComOffsetUsaOInstante() {
        assertEquals(500_000L, Guard.epochMsDe(Json.parse("\"1970-01-01T00:08:20+00:00\"")));
    }

    /** ISO_INSTANT aceita segundo bissexto (OffsetDateTime.parse nao): nao pode cair no fallback ilegivel. */
    @Test
    void epochMsDeTextoIsoComSegundoBissextoUsaInstant() {
        String texto = "2016-12-31T23:59:60Z";
        assertEquals(Instant.parse(texto).toEpochMilli(), Guard.epochMsDe(Json.parse("\"" + texto + "\"")));
    }

    @Test
    void epochMsDeNuloEhIlegivel() {
        assertEquals(Guard.DATA_ILEGIVEL, Guard.epochMsDe(Json.parse("null")));
    }

    @Test
    void epochMsDeTextoQueNaoEDataEhIlegivel() {
        assertEquals(Guard.DATA_ILEGIVEL, Guard.epochMsDe(Json.parse("\"nao e data\"")));
    }

    @Test
    void epochMsDeObjetoEhIlegivel() {
        assertEquals(Guard.DATA_ILEGIVEL, Guard.epochMsDe(Json.obj()));
    }

    // ---------- frase ignorada (tripwireIgnore) ----------

    @Test
    void fraseEmBrancoNaoIgnoraOTripwire() {
        ObjectNode item = Json.obj().put("frase", "   ").put("criadoEm", 1_000L).put("expiraEm", 200_000L);
        assertEquals("rate limit", Guard.tripwireMatch("rate limit na pagina", cfgComIgnore(item), 5_000L));
    }

    @Test
    void itemSemCriadoEmLegivelNaoIgnoraOTripwire() {
        ObjectNode item = Json.obj().put("frase", "rate limit").put("expiraEm", 200_000L);
        assertEquals("rate limit", Guard.tripwireMatch("rate limit na pagina", cfgComIgnore(item), 5_000L));
    }

    /** Limite exato: agora == min(expiraEm, criadoEm + 24h) ja venceu o ignore. */
    @Test
    void ignoreVenceNoInstanteExatoDoPrazo() {
        ObjectNode item = Json.obj().put("frase", "rate limit").put("criadoEm", 1_000L).put("expiraEm", 200_000L);
        ObjectNode cfg = cfgComIgnore(item);
        assertNull(Guard.tripwireMatch("rate limit na pagina", cfg, 199_999L), "1 ms antes ainda vale");
        assertEquals("rate limit", Guard.tripwireMatch("rate limit na pagina", cfg, 200_000L));
    }

    @Test
    void configNulaNaoCasaTripwire() {
        assertNull(Guard.tripwireMatch("rate limit na pagina", null));
        assertNull(Guard.tripwireMatch("rate limit na pagina", null, 5_000L));
    }

    // ---------- contextoTripwire ----------

    @Test
    void contextoTripwireIncluiSessentaCaracteresDepoisDoPadrao() {
        String texto = "rate limit" + "y".repeat(100);
        String ctx = Guard.contextoTripwire(texto, "rate limit");
        assertEquals(70, ctx.length());
        assertEquals(texto.substring(0, 70), ctx);
    }

    @Test
    void contextoTripwireIncluiSessentaCaracteresAntesEDepoisDoPadrao() {
        String texto = "a".repeat(100) + "rate limit" + "b".repeat(100);
        String ctx = Guard.contextoTripwire(texto, "rate limit");
        assertEquals(130, ctx.length());
        assertEquals(texto.substring(40, 170), ctx);
    }
}
