package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mata os mutantes vivos (PIT: SURVIVED/NO_COVERAGE) de {@link NewIds}.
 * Cada teste falha com o mutante e passa com o codigo atual, asserindo o
 * comportamento observavel exato (retorno, mensagem, estado, relogio).
 */
class NewIdsMutantesTest {

    /** exec falso: devolve os retornos JSON na ordem (ignora o script). */
    static final class Fila implements Function<String, Object> {
        private final ArrayDeque<String> fila = new ArrayDeque<>();

        Fila add(String json) {
            fila.add(json);
            return this;
        }

        @Override public Object apply(String js) {
            return fila.pollFirst();
        }
    }

    /** relogio falso: o tempo so anda no dormir; conta as dormidas. */
    static final class RelogioContado implements NewIds.Relogio {
        long agora;
        int dormidas;

        RelogioContado() {
            this(0);
        }

        RelogioContado(long inicio) {
            this.agora = inicio;
        }

        @Override public long agora() { return agora; }

        @Override public void dormir(long ms) {
            dormidas++;
            agora += ms;
        }
    }

    private static NewIds.Spec spec(String selector) {
        return new NewIds.Spec(selector, "", "data-id", "", "");
    }

    private static String leitura(String... ids) {
        StringBuilder sb = new StringBuilder("{\"p\":-1,\"it\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"v\":[\"").append(ids[i]).append("\"]}");
        }
        return sb.append("]}").toString();
    }

    private static String itens(int n) {
        StringBuilder sb = new StringBuilder("{\"p\":-1,\"it\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"v\":[\"x").append(i).append("\"]}");
        }
        return sb.append("]}").toString();
    }

    private static Fila repetir(String json, int vezes) {
        Fila f = new Fila();
        for (int i = 0; i < vezes; i++) f.add(json);
        return f;
    }

    // ---------- Spec.of / relogio real ----------

    @Test
    void specOfLeTodosOsCamposDoJson() {
        NewIds.Spec s = NewIds.Spec.of(Json.parse("{\"selector\":\"css:a\",\"parentSelector\":\"css:b\","
                + "\"attr\":\"href\",\"attrSelector\":\"a[href]\",\"idRegex\":\"(\\\\d+)\","
                + "\"stableMs\":250,\"minIds\":3}"));
        assertEquals("css:a", s.selector());
        assertEquals("css:b", s.parentSelector());
        assertEquals("href", s.attr());
        assertEquals("a[href]", s.attrSelector());
        assertEquals("(\\d+)", s.idRegex());
        assertEquals(250L, s.stableMs());
        assertEquals(3, s.minIds());
    }

    @Test
    void specOfUsaPadroesQuandoJsonVazio() {
        NewIds.Spec s = NewIds.Spec.of(Json.parse("{}"));
        assertNotNull(s, "Spec.of nunca pode devolver null");
        assertEquals("", s.selector());
        assertEquals("", s.parentSelector());
        assertEquals("", s.attr());
        assertEquals("", s.attrSelector());
        assertEquals("", s.idRegex());
        assertEquals(NewIds.STABLE_PADRAO_MS, s.stableMs());
        assertEquals(0, s.minIds());
    }

    @Test
    void relogioRealNuncaEhNulo() {
        assertNotNull(NewIds.real());
    }

    @Test
    void relogioRealAgoraSegueRelogioDoSistema() {
        NewIds.Relogio r = NewIds.real();
        long antes = System.currentTimeMillis();
        long agora = r.agora();
        long depois = System.currentTimeMillis();
        assertTrue(agora >= antes, "agora() devolveu " + agora + " antes de " + antes);
        assertTrue(agora <= depois + 1000, "agora() devolveu " + agora + " depois de " + depois);
    }

    @Test
    void relogioRealDormirBloqueiaPeloMenos20ms() throws Exception {
        long t0 = System.nanoTime();
        NewIds.real().dormir(40);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 20, "dormir(40) nao bloqueou: " + ms + " ms");
    }

    // ---------- readScript/plano ----------

    @Test
    void readScriptComShadowDomIncluiTodasAsPartesNoPlano() {
        String js = NewIds.readScript(new NewIds.Spec("css:div >>> span", "", "data-id", "", ""), false);
        assertTrue(js.contains("\"p\":[\"div\",\"span\"]"),
                "plano css com >>> deve listar os dois trechos: " + js);
    }

    // ---------- parse ----------

    @Test
    void parseDeRespostaVaziaDevolveLeituraComErro() {
        NewIds.Leitura l = NewIds.parse("");
        assertNotNull(l, "resposta vazia nunca pode virar null");
        assertEquals(0, l.parents());
        assertEquals(List.of(), l.itens());
        assertEquals("resposta vazia do navegador", l.erro());
        assertEquals("resposta vazia do navegador", NewIds.parse("   ").erro());
        assertEquals("resposta vazia do navegador", NewIds.parse(null).erro());
    }

    @Test
    void parseAceitaVComoValorUnicoNaoNulo() {
        NewIds.Leitura l = NewIds.parse("{\"p\":-1,\"it\":[{\"v\":\"abc\"}]}");
        assertEquals(1, l.itens().size());
        assertEquals(List.of("abc"), l.itens().get(0).v());
    }

    @Test
    void parseIgnoraVQueEhNull() {
        NewIds.Leitura l = NewIds.parse("{\"p\":-1,\"it\":[{\"v\":null}]}");
        assertEquals(1, l.itens().size());
        assertEquals(List.of(), l.itens().get(0).v(), "null nao pode virar candidato de id");
    }

    // ---------- ler / idDe / idDoItem ----------

    @Test
    void capturarConverteExcecaoDoExecEmErroDeLeitura() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(js -> {
                    throw new IllegalStateException("boom");
                }, new RelogioContado(), spec("css:article"), 5_000));
        assertEquals("capture_ids falhou: boom", e.getMessage());
    }

    @Test
    void idDeSemRegexDevolveORawInteiro() {
        assertEquals("raw-1", NewIds.idDe("raw-1", null));
    }

    @Test
    void idDeSemGrupoDevolveOMatchInteiro() {
        assertEquals("123", NewIds.idDe("abc123def", Pattern.compile("[0-9]+")));
    }

    @Test
    void idDoItemAceitaIdDeExatamente512Chars() {
        String exato = "x".repeat(512);
        assertEquals(exato, NewIds.idDoItem(spec("css:a"),
                new NewIds.Item(List.of(exato), null), null));
    }

    @Test
    void idDoItemIgnoraIdCom513Chars() {
        assertNull(NewIds.idDoItem(spec("css:a"),
                new NewIds.Item(List.of("x".repeat(513)), null), null));
    }

    // ---------- capturar: parents / waitMs / estabilidade ----------

    @Test
    void capturarAceitaExatamenteUmParentCanonico() throws Exception {
        String r = "{\"p\":1,\"it\":[{\"v\":[\"a\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r),
                new RelogioContado(), spec("css:article"), 10_000);
        assertEquals(Set.of("a"), c.ids(), "parents=1 e um parent valido, nao ambiguo");
        assertEquals(2, c.leituras());
    }

    @Test
    void capturarComParentZeroDepoisAceitaLeituraValida() throws Exception {
        // inicio != 0: a soma (mutante) estouraria o prazo na primeira leitura p=0
        String p0 = "{\"p\":0,\"it\":[]}";
        String a = leitura("a");
        NewIds.Captura c = NewIds.capturar(new Fila().add(p0).add(a).add(a).add(a),
                new RelogioContado(10_000), spec("css:article"), 5_000);
        assertEquals(Set.of("a"), c.ids());
        assertEquals(2_000, c.ms());
        assertEquals(2, c.leituras());
    }

    @Test
    void capturarAceitaExatamenteMaxIdsItens() throws Exception {
        String r = itens(NewIds.MAX_IDS);
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r),
                new RelogioContado(), spec("css:article"), 10_000);
        assertEquals(NewIds.MAX_IDS, c.ids().size(), "exatamente MAX_IDS ainda e aceito");
        assertEquals(2, c.leituras());
    }

    @Test
    void capturarContaLeiturasValidasCrescendo() throws Exception {
        NewIds.Captura c = NewIds.capturar(new Fila().add(leitura("a", "b")).add(leitura("a", "b")),
                new RelogioContado(), spec("css:article"), 10_000);
        assertEquals(2, c.leituras(), "duas leituras validas: contador cresce de 1 em 1");
    }

    @Test
    void capturarVazioComInicioNaoZeroSoRetornaNoPrazoInteiro() throws Exception {
        NewIds.Captura c = NewIds.capturar(repetir(leitura(), 7),
                new RelogioContado(10_000), spec("css:article"), 5_000);
        assertEquals(Set.of(), c.ids());
        assertEquals(5_000, c.ms(), "ms = agora - inicio (nao soma)");
        assertEquals(6, c.leituras());
    }

    @Test
    void capturarNaoVazioDevolveTempoRelativoAoInicio() throws Exception {
        NewIds.Captura c = NewIds.capturar(new Fila().add(leitura("a")).add(leitura("a")),
                new RelogioContado(10_000), spec("css:article"), 5_000);
        assertEquals(Set.of("a"), c.ids());
        assertEquals(1_000, c.ms(), "ms = agora - inicio, nao agora + inicio");
        assertEquals(2, c.leituras());
    }

    @Test
    void capturarAbaixoDeMinIdsContinuaAteCarregar() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "data-id", "", "", 1_000, 2);
        NewIds.Captura c = NewIds.capturar(
                new Fila().add(leitura("a")).add(leitura("a", "b")).add(leitura("a", "b")),
                new RelogioContado(10_000), s, 5_000);
        assertEquals(Set.of("a", "b"), c.ids());
        assertEquals(2_000, c.ms());
    }

    @Test
    void capturarNoPrazoComExatamenteMinIdsErraPorNaoEstabilizar() {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "data-id", "", "", 1_000, 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add(leitura("a")).add(leitura("b"))
                                .add(leitura("c")).add(leitura("d")),
                        new RelogioContado(), s, 3_000));
        assertTrue(e.getMessage().contains("nao estabilizou"), e.getMessage());
        assertFalse(e.getMessage().contains("minIds"),
                "com size == minIds o erro e de estabilizacao, nao de minimo: " + e.getMessage());
    }

    // ---------- verificar: erro / parents / deadline ----------

    @Test
    void verificarErroDeJsSoEstouraNoPrazo() {
        RelogioContado r = new RelogioContado(10_000);
        // 11 erros: o 11o cai exatamente no prazo (10 polls de 500 ms); a leitura valida
        // extra garante que o teste termina mesmo se o mutante nao estourar.
        Fila f = repetir("{\"erro\":\"falha\"}", 11).add(leitura("a", "b"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(f, r, spec("css:article"), Set.of("a"), "", 5_000));
        assertEquals("verify_new_id falhou: falha", e.getMessage());
        assertEquals(10, r.dormidas, "10 polls de 500 ms ate o prazo de 5000 ms");
    }

    @Test
    void verificarParentZeroSoEstouraNoPrazo() {
        RelogioContado r = new RelogioContado(10_000);
        Fila f = repetir("{\"p\":0,\"it\":[]}", 11).add(leitura("a", "b"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(f, r, spec("css:article"), Set.of("a"), "", 5_000));
        assertEquals("verify_new_id falhou: nenhum item novo em 'css:article' apos 5000 ms"
                + " (itens vistos 0, ja conhecidos 0)", e.getMessage());
        assertEquals(10, r.dormidas);
    }

    @Test
    void verificarParentZeroDepoisAceitaItemNovo() throws Exception {
        RelogioContado r = new RelogioContado();
        NewIds.Novo n = NewIds.verificar(
                new Fila().add("{\"p\":0,\"it\":[]}").add(leitura("a", "b")),
                r, spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("b", n.id());
        assertEquals(1, r.dormidas, "entre a leitura p=0 e a proxima deve dormir VERIFY_POLL_MS");
    }

    @Test
    void verificarCandidatoSemAncoraSoEstouraNoPrazo() {
        RelogioContado r = new RelogioContado(10_000);
        String semAncora = "{\"p\":-1,\"it\":[{\"v\":[\"b\"],\"t\":\"oi\"}]}";
        // 11 leituras sem ancora; a 12a tem ancora e encerra se o mutante nao estourar.
        Fila f = repetir(semAncora, 11).add(leitura("a", "b"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(f, r, spec("css:article"), Set.of("a"), "", 5_000));
        assertTrue(e.getMessage().contains("sem ancora"), e.getMessage());
        assertEquals(10, r.dormidas);
    }

    @Test
    void verificarSemCandidatoDormeAntesDaProximaLeitura() throws Exception {
        RelogioContado r = new RelogioContado();
        NewIds.Novo n = NewIds.verificar(
                new Fila().add(leitura("a")).add(leitura("a", "b")),
                r, spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("b", n.id());
        assertEquals(1, r.dormidas, "nenhum candidato deve dormir VERIFY_POLL_MS, nao estourar");
    }

    // ---------- cobreSegmentos / avancaSegmento / corta ----------

    @Test
    void cobreSegmentosFalsoQuandoSegmentoInicialNaoAparece() {
        assertFalse(NewIds.cobreSegmentos(
                "Segunda tem jogo", "Garanta https://exemplo.com/x Segunda tem jogo"),
                "segmento antes da URL ausente no item: nao cobre");
    }

    @Test
    void cobreSegmentosContinuaQuandoSegmentoCurtoNaoAvanca() {
        assertTrue(NewIds.cobreSegmentos("fim", "a https://exemplo.com/x fim"),
                "segmento curto devolve busca==0 e nao pode ser tratado como falha");
    }

    @Test
    void cobreSegmentoSeguinteCurtoDevolveBuscaAtualNaoZero() {
        assertFalse(NewIds.cobreSegmentos(
                "def abcQQ", "abc https://a.com/x QQ https://b.com/y def"),
                "segmento curto entre URLs preserva a posicao; 'def' antes de 'abc' nao vale");
    }

    @Test
    void cobreSegmentoDeTresCharsAvancaPosicao() {
        assertFalse(NewIds.cobreSegmentos("def abc", "abc https://x.com/y def"),
                "segmento de exatamente 3 chars sem espacos avanca a busca");
    }

    @Test
    void cobreSegmentosSegmentoFinalCurtoSemAcharDevolveTrue() {
        assertTrue(NewIds.cobreSegmentos("qualquer", "ab"),
                "segmento final curto devolve busca (0), que conta como >= 0");
    }

    @Test
    void timeoutMsgNaoCortaTextoNoTamanhoExatoDoLimite() {
        String selector = "s".repeat(120);
        String expect = "e".repeat(60);
        String r = leitura("a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r).add(r),
                        new RelogioContado(), spec(selector), Set.of("a"), expect, 500));
        String msg = e.getMessage();
        assertTrue(msg.contains("com '" + expect + "' em '" + selector + "' apos"),
                "texto no limite exato (60/120) nao pode ganhar '...': " + msg);
    }
}
