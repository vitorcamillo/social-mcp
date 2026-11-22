package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mata mutantes vivos de {@link RealClick} (PIT) nas partes puras: texto de
 * {@code Result.info}, repasse de {@code antesDeAgir}, funcoes JS de foco/scroll e a
 * aritmetica exata da trilha do mouse. Nao altera src/main e nao abre navegador.
 */
class RealClickMutantesTest {

    @Test
    void infoDoModoDomEExatamenteAFraseDoCliqueSintetico() {
        RealClick.Result dom = new RealClick.Result(RealClick.MODE_DOM, "js", 0, 0, true, "");

        assertEquals("clicou (dom: scrollIntoView + el.click())", dom.info());
    }

    @Test
    void infoDoModoMouseMostraPontoRolagemEAvisoSoQuandoOCentroEstaCoberto() {
        RealClick.Result limpo = new RealClick.Result(RealClick.MODE_MOUSE, "roda (3)", 10, 20, true, "div.overlay");
        assertEquals("clicou (mouse em 10,20; rolagem roda (3))", limpo.info());

        RealClick.Result coberto = new RealClick.Result(RealClick.MODE_MOUSE, "nenhuma", 10, 20, false, "div.overlay");
        assertEquals("clicou (mouse em 10,20; rolagem nenhuma); AVISO: centro coberto por div.overlay",
                coberto.info());
    }

    @Test
    void antesDeAgirDevolveOMesmoResultadoDoCallable() throws Exception {
        Object valor = new Object();

        assertSame(valor, RealClick.antesDeAgir("click", () -> valor));
    }

    @Test
    void scrollIntoViewSoClicaNoModoDom() {
        String comClique = RealClick.scrollIntoViewFn(true);
        String semClique = RealClick.scrollIntoViewFn(false);

        assertTrue(comClique.contains("rjScrollIntoView(this);"), comClique);
        assertTrue(comClique.contains("this.click();"), comClique);
        assertTrue(semClique.contains("rjScrollIntoView(this);"), semClique);
        assertFalse(semClique.contains("this.click();"), semClique);
        assertTrue(semClique.contains("return 'ok';"), semClique);
    }

    @Test
    void focusFnDevolveOJSCompletoDoRjFocused() {
        assertEquals("function(){" + RealClick.LIB + "return rjFocused(this)?'1':'0';}", RealClick.focusFn());
    }

    @Test
    void trilhaUsaDesvioEControleExatosQuandoODesvioNaoBateNoTeto() {
        double x0 = 10;
        double y0 = 60;
        double x1 = 160;
        double y1 = 260; // dist = 250, logo n = 10 pontos
        List<double[]> pontos = RealClick.path(x0, y0, x1, y1, new RandomFixo(0.0));

        assertEquals(10, pontos.size());
        // desvio = min(80, 250*0.2) * (0.0-0.5) * 2 = -50
        // cx = (10+160)/2 - (260-60)/250 * (-50) = 85 + 40 = 125
        // cy = (60+260)/2 + (160-10)/250 * (-50) = 160 - 30 = 130
        assertPonto(pontos.get(2), 0.3, x0, y0, 125, 130, x1, y1);
        assertPonto(pontos.get(4), 0.5, x0, y0, 125, 130, x1, y1);
        assertPonto(pontos.get(6), 0.7, x0, y0, 125, 130, x1, y1);
    }

    /** Confere o ponto pela formula esperada (independente de RealClick.path). */
    private static void assertPonto(double[] ponto, double u, double x0, double y0,
                                    double cx, double cy, double x1, double y1) {
        double e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
        double px = (1 - e) * (1 - e) * x0 + 2 * (1 - e) * e * cx + e * e * x1;
        double py = (1 - e) * (1 - e) * y0 + 2 * (1 - e) * e * cy + e * e * y1;

        assertEquals(Math.max(0, px), ponto[0], 1e-9, "px em u=" + u);
        assertEquals(Math.max(0, py), ponto[1], 1e-9, "py em u=" + u);
    }

    /** Random determinista: o path consome apenas nextDouble(), uma vez. */
    private static final class RandomFixo extends Random {
        private final double valor;

        RandomFixo(double valor) {
            this.valor = valor;
        }

        @Override
        public double nextDouble() {
            return valor;
        }
    }
}
