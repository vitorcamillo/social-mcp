package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DuplicatesTest {

    private static final String INTERFACE = "\nCurtir\nComentar\nminhaconta";

    @Test
    void normalizarTiraAcentoPontuacaoEEmoji() {
        assertEquals("sao so tres passos", Duplicates.normalizar("São só três passos! 👉"));
    }

    @Test
    void semInterfaceComumRemoveLinhaDas4PaginasEMantemUnicas() {
        List<String> textos = List.of(
                "legenda um so minha\nCurtir",
                "legenda dois so minha\nCurtir",
                "legenda tres so minha\nCurtir",
                "legenda quatro so minha\nCurtir");
        List<String> resto = Duplicates.semInterfaceComum(textos);
        assertEquals(4, resto.size());
        for (String r : resto) {
            assertFalse(r.contains("Curtir"), "interface deveria sair: " + r);
        }
        assertTrue(resto.get(0).contains("legenda um so minha"));
        assertTrue(resto.get(1).contains("legenda dois so minha"));
        assertTrue(resto.get(2).contains("legenda tres so minha"));
        assertTrue(resto.get(3).contains("legenda quatro so minha"));
    }

    @Test
    void jaccardIguaisDisjuntosEVazios() {
        assertEquals(1.0, Duplicates.jaccard(Set.of("a b c", "b c d"), Set.of("a b c", "b c d")));
        assertEquals(0.0, Duplicates.jaccard(Set.of("a b c"), Set.of("x y z")));
        assertEquals(0.0, Duplicates.jaccard(Set.of(), Set.of()));
    }

    @Test
    void agruparQuatroPaginasAchaSoAeB() {
        String legendaA = "Hoje vou mostrar como plantar tomates orgânicos no quintal de casa, passo a passo completo para iniciantes! 🍅";
        String legendaB = "Hoje vou mostrar como plantar tomates organicos no quintal de casa passo a passo completo para iniciantes";
        String legendaC = "Receita de bolo de cenoura com cobertura de chocolate para o aniversario da minha avo querida";
        String legendaD = "Acabei de voltar de uma viagem incrivel pelas praias do nordeste com aguas cristalinas e por do sol inesquecivel";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/a", legendaA + INTERFACE),
                new Duplicates.Pagina("https://rede/b", legendaB + INTERFACE),
                new Duplicates.Pagina("https://rede/c", legendaC + INTERFACE),
                new Duplicates.Pagina("https://rede/d", legendaD + INTERFACE));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/a", "https://rede/b"), grupos.get(0).urls());
        assertTrue(grupos.get(0).similaridade() >= 0.8, "similaridade: " + grupos.get(0).similaridade());
    }

    @Test
    void agruparNaoJuntaPostsQueSoCompartilhamInterface() {
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/a",
                        "O ceu azul anuncia uma manha perfeita para caminhar no parque central" + INTERFACE),
                new Duplicates.Pagina("https://rede/b",
                        "Meu gato dormiu a tarde inteira em cima do sofa da sala escura" + INTERFACE));
        assertTrue(Duplicates.agrupar(paginas, 0.6).isEmpty());
    }

    @Test
    void agruparIgnoraPaginaDeTextoCurto() {
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/a",
                        "Hoje vou mostrar como plantar tomates organicos no quintal de casa passo a passo" + INTERFACE),
                new Duplicates.Pagina("https://rede/b", "Oi, tudo bem?" + INTERFACE));
        assertTrue(Duplicates.agrupar(paginas, 0.6).isEmpty());
    }

    @Test
    void tresPaginasIguaisFormamUmGrupoComTresUrls() {
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/1",
                        "Amo viajar pelo mundo conhecendo culturas novas e pessoas incriveis! 🌍" + INTERFACE),
                new Duplicates.Pagina("https://rede/2",
                        "Amo viajar pelo mundo conhecendo culturas novas e pessoas incriveis? 🌎" + INTERFACE),
                new Duplicates.Pagina("https://rede/3",
                        "Amo viajar pelo mundo conhecendo culturas novas e pessoas incriveis." + INTERFACE));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/1", "https://rede/2", "https://rede/3"), grupos.get(0).urls());
        assertEquals(3, grupos.get(0).inicios().size());
    }

    @Test
    void legendaIgualEmDoisDeTresPostsNaoViraInterface() {
        String legenda = "Arrasta e ve: sao so tres passos para garantir seu ingresso sem fila";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/a", legenda + INTERFACE),
                new Duplicates.Pagina("https://rede/b", "Outro post falando do jogo de domingo no estadio cheio" + INTERFACE),
                new Duplicates.Pagina("https://rede/c", legenda + INTERFACE));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/a", "https://rede/c"), grupos.get(0).urls());
        assertEquals(1.0, grupos.get(0).similaridade());
    }

    @Test
    void legendasExatamenteIguaisEmDuasPaginasAchamDuplicata() {
        // legenda IDÊNTICA (byte a byte) em 2 páginas: a linha comum era conteúdo,
        // não interface — remover tudo deixava a busca com zero duplicatas
        String legenda = "Promocao de lancamento so ate sexta com frete gratis para todo o Brasil";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/a", legenda + INTERFACE),
                new Duplicates.Pagina("https://rede/b", legenda + INTERFACE));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/a", "https://rede/b"), grupos.get(0).urls());
        assertEquals(1.0, grupos.get(0).similaridade());
    }

    @Test
    void legendasExatamenteIguaisNasTresPaginasAchamDuplicata() {
        String legenda = "Arrasta e ve: sao so tres passos para garantir seu ingresso sem fila";
        List<Duplicates.Pagina> paginas = List.of(
                new Duplicates.Pagina("https://rede/1", legenda + INTERFACE),
                new Duplicates.Pagina("https://rede/2", legenda + INTERFACE),
                new Duplicates.Pagina("https://rede/3", legenda + INTERFACE));
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, 0.6);
        assertEquals(1, grupos.size());
        assertEquals(List.of("https://rede/1", "https://rede/2", "https://rede/3"), grupos.get(0).urls());
        assertEquals(1.0, grupos.get(0).similaridade());
    }

    @Test
    void legendaIgualComContadoresDiferentesAchaDuplicata() {
        List<Duplicates.Grupo> g = Duplicates.agrupar(List.of(
                new Duplicates.Pagina("https://a", "Promocao de lancamento com frete gratis\nCurtir\n1"),
                new Duplicates.Pagina("https://b", "Promocao de lancamento com frete gratis\nCurtir\n2")), 0.6);
        assertEquals(1, g.size());
        assertEquals(List.of("https://a", "https://b"), g.get(0).urls());
    }

    @Test
    void linhaLongaComumNuncaViraInterface() {
        List<String> r = Duplicates.semInterfaceComum(List.of(
                "Legenda longa repetida em todos os posts\nCurtir",
                "Legenda longa repetida em todos os posts\nCurtir"));
        assertEquals(List.of("Legenda longa repetida em todos os posts",
                "Legenda longa repetida em todos os posts"), r);
    }
}
