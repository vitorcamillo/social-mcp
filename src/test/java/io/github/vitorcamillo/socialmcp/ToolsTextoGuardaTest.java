package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolsTextoGuardaTest {

    @Test
    void textoCurtoJuntaAlertaETextoInteiro() {
        assertEquals("Titulo\ncorpo curto", Tools.textoParaGuarda("Titulo", "corpo curto"));
    }

    @Test
    void limiteDe1500IncluiTextoInteiro() {
        String texto = "x".repeat(1500);
        assertEquals("Titulo\n" + texto, Tools.textoParaGuarda("Titulo", texto));
    }

    @Test
    void textoLongoComPadraoForaDosAlertasNaoEntraNaGuarda() {
        String longo = "rate limit " + "x".repeat(2000);
        assertEquals("Titulo", Tools.textoParaGuarda("Titulo", longo));
    }

    @Test
    void alertaVazioUsaTextoInteiroMesmoLongo() {
        String longo = "rate limit " + "x".repeat(2000);
        assertEquals(longo, Tools.textoParaGuarda("", longo));
        assertEquals(longo, Tools.textoParaGuarda("   ", longo));
        assertEquals(longo, Tools.textoParaGuarda(null, longo));
    }

    @Test
    void nulosNaoQuebram() {
        assertEquals("", Tools.textoParaGuarda(null, null));
        assertEquals("Titulo", Tools.textoParaGuarda("Titulo", null));
    }
}
