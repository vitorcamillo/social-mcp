package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CampoTextoTest {

    private static Function<String, Object> execCom(String json) {
        return script -> json;
    }

    private static String estado(String texto, String selecionado, boolean editavel, String tag) {
        return Json.write(Json.obj()
                .put("editavel", editavel)
                .put("texto", texto)
                .put("selecionado", selecionado)
                .put("tag", tag));
    }

    @Test
    void campoVazioPassa() {
        assertDoesNotThrow(() -> CampoTexto.conferirAntesDeDigitar(execCom(estado("", "", true, "TEXTAREA")), ""));
        String soSujeira = "  \n" + ((char) 0x200B) + " ";
        assertDoesNotThrow(() -> CampoTexto.conferirAntesDeDigitar(execCom(estado(soSujeira, "", true, "TEXTAREA")), ""));
    }

    @Test
    void casoRealTextoSobrandoLancaNaoAgiu() {
        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(execCom(estado("rindo", "", true, "TEXTAREA")), ""));
        assertTrue(e.getMessage().contains("campo ja tinha texto"));
        assertTrue(e.getMessage().contains("rindo"));
    }

    @Test
    void textoTodoSelecionadoPassaParcialNao() {
        assertDoesNotThrow(() -> CampoTexto.conferirAntesDeDigitar(
                execCom(estado("legenda antiga", "legenda antiga", true, "TEXTAREA")), ""));
        assertThrows(RealClick.NaoAgiu.class, () -> CampoTexto.conferirAntesDeDigitar(
                execCom(estado("legenda antiga", "antiga", true, "TEXTAREA")), ""));
    }

    @Test
    void allowExistingComRegex() {
        String regex = "@[A-Za-z0-9_.@-]+";
        assertDoesNotThrow(() -> CampoTexto.conferirAntesDeDigitar(
                execCom(estado("@fulano@mastodon.social ", "", true, "TEXTAREA")), regex));
        assertThrows(RealClick.NaoAgiu.class, () -> CampoTexto.conferirAntesDeDigitar(
                execCom(estado("rindo", "", true, "TEXTAREA")), regex));
    }

    @Test
    void campoNaoEditavelLancaNaoAgiu() {
        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(execCom(estado("", "", false, "BODY")), ""));
        assertTrue(e.getMessage().contains("campo editavel"));
    }

    @Test
    void erroEJsonInvalidoEExecComFalhaLancaoNaoAgiu() {
        assertThrows(RealClick.NaoAgiu.class, () -> CampoTexto.conferirAntesDeDigitar(
                execCom(Json.write(Json.obj().put("erro", "x"))), ""));
        assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(execCom("nao-json"), ""));
        Function<String, Object> explosiva = script -> {
            throw new RuntimeException("navegador caiu");
        };
        assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(explosiva, ""));
    }

    @Test
    void execRecebeScriptExato() {
        AtomicReference<String> recebido = new AtomicReference<>();
        Function<String, Object> captura = script -> {
            recebido.set(script);
            return estado("", "", true, "TEXTAREA");
        };
        assertDoesNotThrow(() -> CampoTexto.conferirAntesDeDigitar(captura, ""));
        assertEquals(CampoTexto.SCRIPT, recebido.get());
    }

    @Test
    void textoConfereExact() {
        assertFalse(Tools.textoConfere("rindoQuarta tem Timão na Arena", "Quarta tem Timão na Arena", true));
        assertTrue(Tools.textoConfere("Quarta  tem\nTimão na Arena ", "Quarta tem Timão na Arena", true));
        assertTrue(Tools.textoConfere("rindoQuarta tem Timão", "Quarta tem Timão", false));
    }

    @Test
    void validateVerifyText() {
        JsonNode exactTexto = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"verify_text\",\"expect\":\"Oi\",\"exact\":\"sim\"}");
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validateVerifyText(exactTexto, 0));
        JsonNode exactSemSelector = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"verify_text\",\"expect\":\"Oi\",\"exact\":true}");
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validateVerifyText(exactSemSelector, 0));
        JsonNode exactComSelector = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"verify_text\",\"expect\":\"Oi\",\"exact\":true,\"selector\":\"css:span\"}");
        assertDoesNotThrow(() -> BatchStore.validateVerifyText(exactComSelector, 0));
        JsonNode semExactSemSelector = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"verify_text\",\"expect\":\"Oi\"}");
        assertDoesNotThrow(() -> BatchStore.validateVerifyText(semExactSemSelector, 0));
    }

    @Test
    void validateAllowExisting() {
        JsonNode kindChave = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"key\",\"text\":\"a\",\"allowExisting\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validateAllowExisting(kindChave, 0));
        JsonNode regexInvalida = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"[abc\"}");
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validateAllowExisting(regexInvalida, 0));
        JsonNode vazia = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"\"}");
        assertThrows(IllegalArgumentException.class, () -> BatchStore.validateAllowExisting(vazia, 0));
        JsonNode valida = Json.parse(
                "{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"oi\",\"allowExisting\":\"@[A-Za-z0-9_.@-]+\"}");
        assertDoesNotThrow(() -> BatchStore.validateAllowExisting(valida, 0));
        JsonNode semCampo = Json.parse("{\"network\":\"bluesky\",\"kind\":\"type\",\"text\":\"oi\"}");
        assertDoesNotThrow(() -> BatchStore.validateAllowExisting(semCampo, 0));
    }
}
