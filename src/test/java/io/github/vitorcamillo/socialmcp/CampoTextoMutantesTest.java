package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CampoTextoMutantesTest {

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
    void mensagemDeLeituraFalhaMostraSoOErro() {
        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(
                        execCom(Json.write(Json.obj().put("erro", "x"))), ""));
        assertEquals("type abortado: leitura do campo falhou (x)", e.getMessage());
    }

    @Test
    void cortaSoAcimaDe80Caracteres() {
        RealClick.NaoAgiu noLimite = assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(
                        execCom(estado("a".repeat(80), "", true, "TEXTAREA")), ""));
        assertEquals("type abortado: campo ja tinha texto: '" + "a".repeat(80) + "'",
                noLimite.getMessage());
    }

    @Test
    void cortaAcimaDe80Caracteres() {
        RealClick.NaoAgiu passou = assertThrows(RealClick.NaoAgiu.class,
                () -> CampoTexto.conferirAntesDeDigitar(
                        execCom(estado("a".repeat(81), "", true, "TEXTAREA")), ""));
        assertEquals("type abortado: campo ja tinha texto: '" + "a".repeat(80) + "...'",
                passou.getMessage());
    }
}
