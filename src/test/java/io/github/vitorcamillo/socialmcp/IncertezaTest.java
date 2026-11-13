package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Envio incerto (abortado apos publicar) e duplicata suspeita: trava por rede e liberacao. */
class IncertezaTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("home" + System.nanoTime()));
    }

    /** Grava <id>.executed.json com a conferencia e o executedAt dados (como markExecuted deixa). */
    private void executado(Store s, String id, String network, String conferencia, long executedAt) throws Exception {
        ObjectNode b = Json.obj();
        b.put("id", id);
        b.put("label", "t");
        b.put("status", "executed");
        b.put("createdAt", executedAt);
        b.put("executedAt", executedAt);
        b.put("network", network);
        b.set("result", Json.obj().put("conferencia", conferencia));
        Files.writeString(s.batchesDir().resolve(id + ".executed.json"), Json.writePretty(b), StandardCharsets.UTF_8);
    }

    private static long antesDaTrava() {
        return LocalDate.of(2026, 11, 10).atStartOfDay(Store.TZ).toInstant().toEpochMilli();
    }

    @Test
    void loteIncertoBloqueiaPublicoMasNaoLeitura() throws Exception {
        Store s = store();
        executado(s, "b1", "instagram", "abortado_apos_publico", System.currentTimeMillis());
        assertFalse(s.incertezasPendentes("instagram").isEmpty());
        Guard.Decision d = Guard.check(s, "instagram", "click");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("b1"), d.reason());
        assertTrue(d.reason().contains("social_resolve_incerteza"), d.reason());
        assertTrue(Guard.check(s, "instagram", "goto").allowed());
    }

    @Test
    void duplicataSuspeitaTambemTrava() throws Exception {
        Store s = store();
        executado(s, "b1", "instagram", "duplicata_suspeita", System.currentTimeMillis());
        assertFalse(Guard.check(s, "instagram", "click").allowed());
    }

    @Test
    void loteResolvidoLibera() throws Exception {
        Store s = store();
        executado(s, "b2", "instagram", "abortado_apos_publico", System.currentTimeMillis());
        assertFalse(Guard.check(s, "instagram", "click").allowed());
        s.appendJournal("incerteza_resolvida", "instagram", Json.obj()
                .put("batchId", "b2").put("resultado", "no_ar").put("prova", "https://instagram.com/p/abc12345"));
        assertTrue(s.incertezasPendentes("instagram").isEmpty());
        assertTrue(Guard.check(s, "instagram", "click").allowed());
    }

    @Test
    void loteAnteriorATravaNaoBloqueia() throws Exception {
        Store s = store();
        executado(s, "b3", "instagram", "abortado_apos_publico", antesDaTrava());
        assertTrue(s.incertezasPendentes("instagram").isEmpty());
        assertTrue(Guard.check(s, "instagram", "click").allowed());
    }

    @Test
    void travaNaoVazaParaOutraRede() throws Exception {
        Store s = store();
        executado(s, "b4", "instagram", "abortado_apos_publico", System.currentTimeMillis());
        assertTrue(s.incertezasPendentes("instagram").contains("b4"));
        assertTrue(s.incertezasPendentes("bluesky").isEmpty());
        assertTrue(Guard.check(s, "bluesky", "click").allowed());
    }

    @Test
    void ehEnvioMarcaSoOPassoDePublicar() {
        JsonNode actions = Json.parse("""
                [{"kind":"click","selector":"css:composer"},
                 {"kind":"type","text":"oi"},
                 {"kind":"click","selector":"css:publicar"},
                 {"kind":"verify_new_id","name":"p"}]
                """);
        assertFalse(Tools.ehEnvio(actions, 0), "clique de foco/composer nao e envio");
        assertFalse(Tools.ehEnvio(actions, 1), "type nao e envio");
        assertTrue(Tools.ehEnvio(actions, 2), "ultimo passo publico antes da conferencia e o envio");
        assertFalse(Tools.ehEnvio(actions, 3), "verify_new_id nao e publico");
    }

    @Test
    void ehEnvioComMarcacaoExplicitaSoValeNoMarcado() {
        JsonNode actions = Json.parse("""
                [{"kind":"click","selector":"css:enviar","envio":true},
                 {"kind":"type","text":"oi"},
                 {"kind":"click","selector":"css:publicar"},
                 {"kind":"verify_new_id","name":"p"}]
                """);
        assertTrue(Tools.ehEnvio(actions, 0));
        assertFalse(Tools.ehEnvio(actions, 1));
        assertFalse(Tools.ehEnvio(actions, 2), "com envio declarado, a heuristica nao marca outros");
    }

    @Test
    void conferenciaFinalCom6ArgumentosCobreAs5Regras() {
        JsonNode forte = Json.parse("""
                [{"kind":"click","selector":"css:a"},{"kind":"verify_attr","selector":"css:a","attr":"x","expect":"1"}]""");
        JsonNode semPublico = Json.parse("[{\"kind\":\"goto\",\"url\":\"https://x\"}]");
        // 1. duplicata suspeita vence tudo
        assertEquals(Tools.CONF_DUPLICATA_SUSPEITA, Tools.conferenciaFinal(forte, false, true, true, true, false));
        // 2. sem passo publico feito = igual ao de 3 argumentos
        assertEquals(Tools.conferenciaFinal(forte, true, false), Tools.conferenciaFinal(forte, true, false, false, false, false));
        assertEquals("sem_publico", Tools.conferenciaFinal(semPublico, false, false, false, false, false));
        // 3. abortado com envio feito
        assertEquals("abortado_apos_publico", Tools.conferenciaFinal(forte, true, true, true, false, false));
        // 4. abortado sem envio feito
        assertEquals(Tools.CONF_ABORTADO_ANTES_ENVIO, Tools.conferenciaFinal(forte, true, true, false, false, false));
        // 5. forte com texto divergente cai para fraca; sem divergente segue forte
        assertEquals("fraca", Tools.conferenciaFinal(forte, false, true, true, false, true));
        assertEquals("forte", Tools.conferenciaFinal(forte, false, true, true, false, false));
    }

    @Test
    void avisoConferenciaCobreOsCasosNovos() {
        assertTrue(Tools.avisoConferencia(Tools.CONF_DUPLICATA_SUSPEITA).contains("social_resolve_incerteza"));
        assertTrue(Tools.avisoConferencia(Tools.CONF_ABORTADO_ANTES_ENVIO).contains("rascunho ou composer"));
        assertTrue(Tools.avisoConferencia(Tools.CONF_ABORTADO_APOS_PUBLICO).contains("social_resolve_incerteza"));
    }

    @Test
    void textoDigitadoVemDoUltimoTypeAntesDoVerify() {
        JsonNode a = Json.parse("{\"kind\":\"verify_new_id\",\"name\":\"p\"}");
        JsonNode lote = Json.parse("""
                {"actions":[
                  {"kind":"goto","url":"https://x"},
                  {"kind":"type","text":"primeiro"},
                  {"kind":"click","selector":"css:publicar"},
                  {"kind":"type","text":"segundo"},
                  {"kind":"verify_new_id","name":"p"}]}
                """);
        assertEquals("segundo", Tools.textoDigitadoDoLote(a, lote, 4));
        // campo explicito da acao vence
        JsonNode comCampo = Json.parse("{\"kind\":\"verify_new_id\",\"name\":\"p\",\"textoCompleto\":\"explicito\"}");
        assertEquals("explicito", Tools.textoDigitadoDoLote(comCampo, lote, 4));
        // sem lote (readOnly): vazio
        assertEquals("", Tools.textoDigitadoDoLote(a, null, -1));
        // sem type antes: vazio
        assertEquals("", Tools.textoDigitadoDoLote(a, Json.parse("{\"actions\":[{\"kind\":\"goto\",\"url\":\"https://x\"}]}"), 1));
    }
}
