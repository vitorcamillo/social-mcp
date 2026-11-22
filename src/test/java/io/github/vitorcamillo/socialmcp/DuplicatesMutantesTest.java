package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DuplicatesMutantesTest {

    @Test
    void linhaDeExatasCincoPalavrasNuncaViraInterface() {
        String comum = "cinco palavras aqui nesta linha";
        List<String> resto = Duplicates.semInterfaceComum(List.of(
                comum + "\nprimeira legenda exclusiva bem longa mesmo agora",
                comum + "\nsegunda legenda diferente bem longa mesmo agora"));
        assertEquals(2, resto.size());
        assertTrue(resto.get(0).contains(comum), resto.get(0));
        assertTrue(resto.get(1).contains(comum), resto.get(1));
    }

    @Test
    void quorumDeCincoPaginasExigeQuatroPresencas() {
        String[] legendas = {
            "primeira legenda exclusiva bem longa mesmo agora",
            "segunda legenda diferente bem longa mesmo agora",
            "terceira legenda outra bem longa mesmo agora ja",
            "quarta legenda nova bem longa mesmo agora ja",
            "quinta legenda final bem longa mesmo agora fim"};
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < 4; i++) textos.add("Curtir\n" + legendas[i]);
        textos.add(legendas[4]);
        List<String> resto = Duplicates.semInterfaceComum(textos);
        assertEquals(5, resto.size());
        for (int i = 0; i < 4; i++) assertFalse(resto.get(i).contains("Curtir"), resto.get(i));
        assertTrue(resto.get(4).contains(legendas[4]), resto.get(4));
    }

    @Test
    void quorumDeOitoPaginasExigeCincoPresencas() {
        String[] nucleos = {"alfa", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"};
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String legenda = "legenda exclusiva numero " + nucleos[i] + " bem longa mesmo agora";
            textos.add(i < 4 ? "Lida\n" + legenda : legenda);
        }
        List<String> resto = Duplicates.semInterfaceComum(textos);
        assertEquals(8, resto.size());
        for (int i = 0; i < 4; i++) assertTrue(resto.get(i).contains("Lida"), resto.get(i));
    }

    @Test
    void shinglesDeTextoCurtoDevolveOTextoInteiro() {
        assertEquals(Set.of("uma duas"), Duplicates.shingles("uma duas"));
        assertEquals(Set.of("oi"), Duplicates.shingles("oi"));
    }

    @Test
    void jaccardToleraNuloComoVazio() {
        assertEquals(0.0, Duplicates.jaccard(null, Set.of("a b c")));
        assertEquals(0.0, Duplicates.jaccard(Set.of("a b c"), null));
        assertEquals(0.0, Duplicates.jaccard(null, null));
    }

    @Test
    void paginaDeCincoPalavrasEntraNaComparacao() {
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/1", "um dois tres quatro cinco"),
                new Duplicates.Pagina("https://rede/2", "um dois tres quatro cinco"));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/1", "https://rede/2"), grupos.get(0).urls());
        assertEquals(1.0, grupos.get(0).similaridade());
    }

    @Test
    void similaridadeNoLimiarExatoAindaUne() {
        String legenda = "hoje vou plantar tomates organicos no quintal casa";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/1", legenda),
                new Duplicates.Pagina("https://rede/2", legenda));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 1.0);
        assertEquals(1, grupos.size());
        assertEquals(1.0, grupos.get(0).similaridade());
    }

    @Test
    void duasPaginasExigemUnanimidade() {
        List<String> resto = Duplicates.semInterfaceComum(List.of(
                "Curtir\nprimeira legenda exclusiva bem longa mesmo agora",
                "Curtir\nsegunda legenda diferente bem longa mesmo agora"));
        assertEquals(2, resto.size());
        assertFalse(resto.get(0).contains("Curtir"), resto.get(0));
        assertFalse(resto.get(1).contains("Curtir"), resto.get(1));
    }

    @Test
    void quorumDeQuatroPaginasExigeQuatroPresencas() {
        String[] legendas = {
            "primeira legenda exclusiva bem longa mesmo agora",
            "segunda legenda diferente bem longa mesmo agora",
            "terceira legenda outra bem longa mesmo agora ja",
            "quarta legenda nova bem longa mesmo agora ja"};
        List<String> parcial = new ArrayList<>();
        for (int i = 0; i < 3; i++) parcial.add("Curtir\n" + legendas[i]);
        parcial.add(legendas[3]);
        List<String> resto = Duplicates.semInterfaceComum(parcial);
        assertEquals(4, resto.size());
        for (int i = 0; i < 3; i++) assertTrue(resto.get(i).contains("Curtir"), resto.get(i));
        List<String> total = new ArrayList<>();
        for (int i = 0; i < 4; i++) total.add("Curtir\n" + legendas[i]);
        List<String> restoTotal = Duplicates.semInterfaceComum(total);
        assertEquals(4, restoTotal.size());
        for (int i = 0; i < 4; i++) assertFalse(restoTotal.get(i).contains("Curtir"), restoTotal.get(i));
    }

    @Test
    void quorumDeOitoPaginasComCincoPresencasRemove() {
        String[] nucleos = {"alfa", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"};
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String legenda = "legenda exclusiva numero " + nucleos[i] + " bem longa mesmo agora";
            textos.add(i < 5 ? "Lida\n" + legenda : legenda);
        }
        List<String> resto = Duplicates.semInterfaceComum(textos);
        assertEquals(8, resto.size());
        for (int i = 0; i < 5; i++) assertFalse(resto.get(i).contains("Lida"), resto.get(i));
    }

    @Test
    void shinglesCasosLimite() {
        assertEquals(Set.of(), Duplicates.shingles(""));
        assertEquals(Set.of("uma duas tres"), Duplicates.shingles("uma duas tres"));
        assertEquals(Set.of("a b c", "b c d"), Duplicates.shingles("a b c d"));
    }

    @Test
    void similaridadeNoLimiarExatoMeioUne() {
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/1", "alfa bravo charlie delta echo"),
                new Duplicates.Pagina("https://rede/2", "xray bravo charlie delta echo"));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.5);
        assertEquals(1, grupos.size());
        assertEquals(0.5, grupos.get(0).similaridade(), 1e-9);
    }

    @Test
    void gruposSaemOrdenadosPorSimilaridadeDecrescente() {
        String base = "o jardineiro colheu tomates maduros cedo hoje no quintal grande"
                + " atras da casa velha com muros altos e flores";
        String quase = "o jardineiro colheu tomates maduros tarde hoje no quintal grande"
                + " atras da casa velha com muros altos e flores";
        String identica = "a cosmonauta avistou nebulosas distantes ontem a noite pelo"
                + " telescopio novo do observatorio central municipal";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/x1", base),
                new Duplicates.Pagina("https://rede/x2", quase),
                new Duplicates.Pagina("https://rede/y1", identica),
                new Duplicates.Pagina("https://rede/y2", identica));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(2, grupos.size());
        assertEquals(List.of("https://rede/y1", "https://rede/y2"), grupos.get(0).urls());
        assertEquals(1.0, grupos.get(0).similaridade());
        assertEquals(List.of("https://rede/x1", "https://rede/x2"), grupos.get(1).urls());
        assertEquals(0.7, grupos.get(1).similaridade(), 1e-9);
    }
}
