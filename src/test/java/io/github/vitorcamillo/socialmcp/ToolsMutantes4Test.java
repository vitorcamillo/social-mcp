package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Mata mutantes vivos de {@link Tools} na faixa 1231-1689 que morrem sem Chrome.
 *
 * <p>Estrategia: {@code corta} (L1586) e {@code textoDigitadoDoLote} (L1578) sao
 * metodos puros/static sem navegador; cada teste FALHA com o mutante e PASSA com
 * o codigo atual. Todo o resto da faixa (runStep, runVerifyText, runVerifyAttr,
 * runVerifyAbsent, runCaptureIds, runVerifyNewId, runWait, sendCombo,
 * dispatchKey) exige {@code JavaDriverless}/Chrome real e vai em
 * "precisa de Chrome", pois nao ha fixture headless local nesta faixa nos
 * testes existentes.
 */
class ToolsMutantes4Test {

    private static String corta(String s, int max) {
        try {
            Method m = Tools.class.getDeclaredMethod("corta", String.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(null, s, max);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("metodo corta", e);
        }
    }

    private static JsonNode lote(String acoesJson) {
        return Json.parse("{\"actions\":" + acoesJson + "}");
    }

    // ---------- corta (L1586) ----------

    @Test
    void cortaNoLimiteExatoDevolveTextoIntegro() {
        // Fronteira: length == max nao trunca; mutante >= truncaria para "abc...".
        assertEquals("abc", corta("abc", 3));
    }

    @Test
    void cortaAcimaDoLimiteTruncaComReticencias() {
        // Mutante "return ''" devolveria "" aqui.
        assertEquals("abc...", corta("abcdef", 3));
    }

    @Test
    void cortaTextoCurtoDevolveIntegro() {
        // Mutante "return ''" devolveria "" aqui.
        assertEquals("ab", corta("ab", 5));
    }

    // ---------- textoDigitadoDoLote (L1578) ----------

    @Test
    void textoCompletoTemPrioridadeSobreLote() {
        JsonNode a = Json.parse("{\"textoCompleto\":\"texto integral\"}");
        JsonNode batch = lote("[{\"kind\":\"type\",\"text\":\"outro\"}]");
        assertEquals("texto integral", Tools.textoDigitadoDoLote(a, batch, 1));
    }

    @Test
    void textoDigitadoSemLoteDevolveVazio() {
        JsonNode a = Json.obj();
        assertEquals("", Tools.textoDigitadoDoLote(a, null, 0));
    }

    @Test
    void textoDigitadoEnxergaTypeNoIndiceZero() {
        // Fronteira j >= 0: o unico type esta no indice 0; mutante j > 0 pula e devolve "".
        JsonNode a = Json.obj();
        JsonNode batch = lote("[{\"kind\":\"type\",\"text\":\"unico\"}]");
        assertEquals("unico", Tools.textoDigitadoDoLote(a, batch, 1));
    }

    @Test
    void textoDigitadoRespeitaJanelaDoPassoIdx() {
        // Aritmetica: inicio = min(passoIdx, size) - 1 = 0, so o indice 0 e visivel.
        // Mutante +1 comecaria em 2 e enxergaria o "tardio" do indice 1.
        JsonNode a = Json.obj();
        JsonNode batch = lote("[{\"kind\":\"click\"},{\"kind\":\"type\",\"text\":\"tardio\"},{\"kind\":\"type\",\"text\":\"x\"}]");
        assertEquals("", Tools.textoDigitadoDoLote(a, batch, 1));
    }

    @Test
    void textoDigitadoPegaTypeMaisProximoAntesDoPasso() {
        // Ordem reversa: o type imediatamente anterior (indice 2) vence o do indice 0.
        JsonNode a = Json.obj();
        JsonNode batch = lote("[{\"kind\":\"type\",\"text\":\"primeiro\"},{\"kind\":\"click\"},{\"kind\":\"type\",\"text\":\"segundo\"}]");
        assertEquals("segundo", Tools.textoDigitadoDoLote(a, batch, 3));
    }

    @Test
    void textoDigitadoSemTypeAnteriorDevolveVazio() {
        JsonNode a = Json.obj();
        JsonNode batch = lote("[{\"kind\":\"click\"},{\"kind\":\"click\"}]");
        assertEquals("", Tools.textoDigitadoDoLote(a, batch, 2));
    }
}
