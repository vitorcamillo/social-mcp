package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class NewIdsTest {

    @TempDir
    Path tmp;

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

    /** relogio falso: o tempo so anda no dormir. */
    static final class RelogioFake implements NewIds.Relogio {
        long agora = 0;

        @Override public long agora() { return agora; }

        @Override public void dormir(long ms) { agora += ms; }
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

    @Test
    void capturarEstavelEmDuasLeiturasIguais() throws Exception {
        NewIds.Captura c = NewIds.capturar(new Fila().add(leitura("a", "b")).add(leitura("a", "b")),
                new RelogioFake(), spec("css:article"), 10_000);
        assertEquals(Set.of("a", "b"), c.ids());
        assertEquals(1000, c.ms(), "estabiliza apos a 2a leitura (1 dormir de 1000 ms)");
    }

    @Test
    void capturarListaCrescendoEstabilizaNoConjuntoFinal() throws Exception {
        NewIds.Captura c = NewIds.capturar(new Fila().add(leitura("a", "b")).add(leitura("a", "b", "c")).add(leitura("a", "b", "c")),
                new RelogioFake(), spec("css:article"), 10_000);
        assertEquals(Set.of("a", "b", "c"), c.ids());
    }

    @Test
    void capturarVazioSoDepoisDoPrazoInteiro() throws Exception {
        Fila f = new Fila();
        for (int i = 0; i < 6; i++) f.add(leitura());
        NewIds.Captura c = NewIds.capturar(f, new RelogioFake(), spec("css:article"), 5_000);
        assertEquals(Set.of(), c.ids());
        assertEquals(5000, c.ms(), "vazio so vale depois do waitMs inteiro (nao em 1 s)");
    }

    @Test
    void capturarVazioDepoisApareceItemEEstabiliza() throws Exception {
        NewIds.Captura c = NewIds.capturar(new Fila().add(leitura()).add(leitura("a")).add(leitura("a")),
                new RelogioFake(), spec("css:article"), 10_000);
        assertEquals(Set.of("a"), c.ids());
    }

    @Test
    void capturarNuncaEstabilizaErra() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add(leitura("a")).add(leitura("a", "b")).add(leitura("a")).add(leitura("a", "b")),
                        new RelogioFake(), spec("css:article"), 3_000));
        assertTrue(e.getMessage().contains("nao estabilizou"), e.getMessage());
        assertTrue(e.getMessage().contains("anterior 1 vs atual 2 ids"), e.getMessage());
    }

    @Test
    void capturarParentZeroOTempoTodoErra() {
        Fila f = new Fila();
        for (int i = 0; i < 4; i++) f.add("{\"p\":0,\"it\":[]}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(f, new RelogioFake(), spec("css:article"), 3_000));
        assertTrue(e.getMessage().contains("parentSelector nao apareceu"), e.getMessage());
    }

    @Test
    void capturarParentAmbiguoErra() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add("{\"erro\":\"parentSelector ambiguo (2)\"}"),
                        new RelogioFake(), spec("css:article"), 5_000));
        assertTrue(e.getMessage().contains("parentSelector ambiguo"), e.getMessage());
    }

    @Test
    void capturarErroDeJsErraImediato() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add("{\"erro\":\"seletor invalido\"}"),
                        new RelogioFake(), spec("css:article"), 5_000));
        assertTrue(e.getMessage().contains("seletor invalido"), e.getMessage());
    }

    @Test
    void capturarMaisDeMaxIdsErra() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add(itens(NewIds.MAX_IDS + 1)),
                        new RelogioFake(), spec("css:article"), 5_000));
        assertTrue(e.getMessage().contains("mais de " + NewIds.MAX_IDS), e.getMessage());
    }

    @Test
    void capturarNaoAceitaAntesDoStableMs() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "data-id", "", "", 3_000);
        NewIds.Captura c = NewIds.capturar(
                new Fila().add(leitura("a")).add(leitura("a")).add(leitura("a")).add(leitura("a")),
                new RelogioFake(), s, 10_000);
        assertEquals(Set.of("a"), c.ids());
        assertEquals(3000, c.ms(), "so aceita depois que o conjunto cobre stableMs");
    }

    @Test
    void capturarConjuntoMudouReiniciaContagem() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "data-id", "", "", 3_000);
        NewIds.Captura c = NewIds.capturar(
                new Fila().add(leitura("a")).add(leitura("a")).add(leitura("a"))
                        .add(leitura("b")).add(leitura("b")).add(leitura("b")).add(leitura("b")),
                new RelogioFake(), s, 10_000);
        assertEquals(Set.of("b"), c.ids());
        assertEquals(6000, c.ms(), "conjunto mudou em t=3000; b so estabiliza em t=6000");
    }

    @Test
    void capturarAttrTextPegaPrimeiroNaoVazio() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "@text", "", "");
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"\",\"comentario A\",\"comentario B\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r), new RelogioFake(), s, 10_000);
        assertEquals(Set.of("comentario A"), c.ids());
    }

    @Test
    void capturarAttrTextComIdRegex() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:article", "", "@text", "", "([A-Za-z0-9]+)");
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"comentario 123\",\"outro 456\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r), new RelogioFake(), s, 10_000);
        assertEquals(Set.of("comentario"), c.ids());
    }

    @Test
    void variosCandidatosPegaPrimeiroQueCasaIdRegex() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:div", "", "href", "a[href]", "([0-9]+)");
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"/profile?c=YWJj\",\"/posts/1595606631582237?comment_id=1595606631582237\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r), new RelogioFake(), s, 10_000);
        assertEquals(Set.of("1595606631582237"), c.ids(), "1o candidato (base64, sem digitos) e ignorado");
    }

    @Test
    void variosCandidatosSemIdRegexPegaPrimeiroNaoVazioAte512() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:div", "", "data-id", "span", "");
        String r = "{\"p\":-1,\"it\":[{\"v\":[null,\"" + "x".repeat(600) + "\",\"ok-id\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(r).add(r), new RelogioFake(), s, 10_000);
        assertEquals(Set.of("ok-id"), c.ids(), "null ignorado, >512 ignorado");
    }

    @Test
    void idRegexExtraiGrupo1EDedupeESemMatchIgnora() throws Exception {
        NewIds.Spec s = new NewIds.Spec("css:a", "", "href", "",
                "(?:story_fbid=|/posts/)([A-Za-z0-9]+)");
        String uma = "{\"p\":-1,\"it\":[{\"v\":[\"/posts/123?x=1\"]},{\"v\":[\"/posts/123?x=2\"]},{\"v\":[\"/foo/bar\"]}]}";
        NewIds.Captura c = NewIds.capturar(new Fila().add(uma).add(uma), new RelogioFake(), s, 10_000);
        assertEquals(Set.of("123"), c.ids());
    }

    @Test
    void verificarSoIdsDeAntesErraAposTimeout() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(leitura("a", "b")).add(leitura("a", "b")).add(leitura("a", "b")),
                        new RelogioFake(), spec("css:article"), Set.of("a", "b"), "", 1_000));
        assertTrue(e.getMessage().contains("nenhum item novo"), e.getMessage());
    }

    @Test
    void verificarIdNovoSemExpect() throws Exception {
        NewIds.Novo n = NewIds.verificar(new Fila().add(leitura("a", "b")),
                new RelogioFake(), spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("b", n.id());
        assertEquals(0, n.outros());
        assertEquals(1, n.ancoras());
    }

    @Test
    void verificarIdNovoComExpectQueCasa() throws Exception {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"oi mundo\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 5_000);
        assertEquals("b", n.id());
    }

    @Test
    void verificarExigeAncoraDaListaCapturada() {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"b\"],\"t\":\"oi\"}]}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r).add(r).add(r),
                        new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 1_000));
        assertTrue(e.getMessage().contains("(itens vistos 1, ja conhecidos 0; sem ancora: a lista nao e a mesma da captura)"), e.getMessage());
    }

    @Test
    void verificarAceitaNovoComAncoraEQuandoCapturaEraVazia() throws Exception {
        String comAncora = "{\"p\":-1,\"it\":[{\"v\":[\"a\"]},{\"v\":[\"b\"],\"t\":\"oi\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(comAncora), new RelogioFake(),
                spec("css:article"), Set.of("a"), "oi", 1_000);
        assertEquals("b", n.id());
        String semAncora = "{\"p\":-1,\"it\":[{\"v\":[\"b\"],\"t\":\"oi\"}]}";
        n = NewIds.verificar(new Fila().add(semAncora), new RelogioFake(),
                spec("css:article"), Set.of(), "oi", 1_000);
        assertEquals("b", n.id());
    }

    @Test
    void capturarRespeitaMinIds() throws Exception {
        NewIds.Spec min1 = new NewIds.Spec("css:article", "", "data-id", "", "", 1_000, 1);
        IllegalStateException vazio = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add(leitura()).add(leitura()).add(leitura()),
                        new RelogioFake(), min1, 2_000));
        assertEquals("capture_ids falhou: lista com 0 ids (minIds 1): a lista nao carregou", vazio.getMessage());
        assertEquals(Set.of("a"), NewIds.capturar(new Fila().add(leitura("a")).add(leitura("a")),
                new RelogioFake(), min1, 2_000).ids());
        NewIds.Spec min2 = new NewIds.Spec("css:article", "", "data-id", "", "", 1_000, 2);
        IllegalStateException um = assertThrows(IllegalStateException.class,
                () -> NewIds.capturar(new Fila().add(leitura("a")).add(leitura("a")).add(leitura("a")),
                        new RelogioFake(), min2, 2_000));
        assertTrue(um.getMessage().contains("lista com 1 ids (minIds 2)"), um.getMessage());
    }

    @Test
    void verificarIdNovoCujoTextoNaoCasaErra() {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"nada aqui\"}]}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r).add(r).add(r),
                        new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 1_000));
        assertTrue(e.getMessage().contains("nenhum item novo"), e.getMessage());
        assertTrue(e.getMessage().contains("com 'oi'"), e.getMessage());
    }

    @Test
    void verificarParentApareceDepois() throws Exception {
        NewIds.Novo n = NewIds.verificar(new Fila().add("{\"p\":0,\"it\":[]}").add("{\"p\":1,\"it\":[{\"v\":[\"a\"]},{\"v\":[\"b\"]}]}"),
                new RelogioFake(), spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("b", n.id());
    }

    @Test
    void verificarContaAncorasEOutrosEPeguePrimeiroDoDom() throws Exception {
        NewIds.Novo n = NewIds.verificar(new Fila().add(leitura("a", "d", "b")),
                new RelogioFake(), spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("d", n.id(), "primeiro em ordem do DOM");
        assertEquals(1, n.outros(), "b e o outro candidato");
        assertEquals(1, n.ancoras(), "a reapareceu");
    }

    @Test
    void parseLeituraComTexto() {
        NewIds.Leitura l = NewIds.parse("{\"p\":-1,\"it\":[{\"v\":[\"1\"],\"t\":\"t1\"},{\"v\":[\"2\"]}]}");
        assertEquals(-1, l.parents());
        assertEquals(2, l.itens().size());
        assertEquals(List.of("1"), l.itens().get(0).v());
        assertEquals("t1", l.itens().get(0).texto());
        assertNull(l.itens().get(1).texto());
        assertNull(l.erro());
        NewIds.Leitura e = NewIds.parse("{\"erro\":\"x\"}");
        assertEquals("x", e.erro());
    }

    // ---------- readScript no node (pulado sem node) ----------

    private static final String PAGINA = """
            function colecao(a){var c={length:a.length};for(var i=0;i<a.length;i++){c[i]=a[i];}return c;}
            function El(tag,o){o=o||{};this.tagName=tag;this.innerText=o.text||'';this.attrs=o.attrs||{};
              this.sels=o.sels||[];this.children=colecao(o.children||[]);this.shadowRoot=o.shadow||null;}
            El.prototype.getAttribute=function(n){return Object.prototype.hasOwnProperty.call(this.attrs,n)?this.attrs[n]:null;};
            El.prototype.contains=function(x){return x===this||desc(this,[]).indexOf(x)>=0;}; // Node.contains: inclui o proprio no
            function Root(children){this.children=colecao(children);}
            function desc(n,acc){var cs=n.children||[];for(var i=0;i<cs.length;i++){acc.push(cs[i]);desc(cs[i],acc);}return acc;}
            function qsa(n,s){var all=desc(n,[]);if(s==='*'){return all;}
              return all.filter(function(e){return e.sels.indexOf(s)>=0;});}
            El.prototype.querySelectorAll=Root.prototype.querySelectorAll=function(s){return qsa(this,s);};
            El.prototype.querySelector=Root.prototype.querySelector=function(s){return qsa(this,s)[0]||null;};
            var a1=new El('ARTICLE',{sels:['article'],attrs:{'data-id':'1'},text:'primeiro',
              children:[new El('A',{sels:['a[href]'],attrs:{href:'/status/1',alt:'imagem de capa'}})]});
            var a2=new El('ARTICLE',{sels:['article'],attrs:{'data-id':'2'},text:'segundo',
              children:[new El('A',{sels:['a[href]'],attrs:{href:'/status/2'}})]});
            var main=new El('MAIN',{sels:['main'],children:[a1,a2]});
            globalThis.document=new Root([main]);
            document.body={innerText:''};
            """;

    private JsonNode rodar(String expr) throws Exception {
        Path f = tmp.resolve("n.js");
        Files.writeString(f, PAGINA + "console.log(" + expr + ");\n", StandardCharsets.UTF_8);
        Process pr;
        try {
            pr = new ProcessBuilder("node", f.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("node ausente no PATH");
            return null;
        }
        assertTrue(pr.waitFor(20, TimeUnit.SECONDS), "node travou");
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, pr.exitValue(), out);
        return Json.parse(out);
    }

    @Test
    void readScriptComParent() throws Exception {
        JsonNode r = rodar(NewIds.readScript(new NewIds.Spec("css:article", "css:main", "data-id", "", ""), false));
        assertEquals(1, r.path("p").asInt());
        assertEquals(Json.parse("[{\"v\":[\"1\"]},{\"v\":[\"2\"]}]"), r.path("it"));
    }

    @Test
    void readScriptDescartaItemForaDoParentMesmoQuandoSeletorRetornaAmbos() throws Exception {
        String pagina = PAGINA + "main.querySelectorAll=function(s){return s==='article'?[a1,a2]:qsa(this,s);};"
                + "main.contains=function(el){return el===a1;};";
        String scriptCss = NewIds.readScript(new NewIds.Spec("css:article", "css:main", "data-id", "", ""), false);
        String scriptXpath = NewIds.readScript(new NewIds.Spec("xpath://article", "css:main", "data-id", "", ""), false);
        assertTrue(scriptXpath.contains("contains("), "JS de xpath precisa conter a checagem de parent");
        JsonNode r = rodarPagina(pagina, scriptCss);
        assertEquals(Json.parse("[{\"v\":[\"1\"]}]"), r.path("it"));
    }

    @Test
    void readScriptComAttrSelectorETexto() throws Exception {
        JsonNode r = rodar(NewIds.readScript(new NewIds.Spec("css:article", "css:main", "href", "a[href]", ""), true));
        assertEquals(Json.parse("[{\"v\":[\"/status/1\"],\"t\":\"primeiro\\nimagem de capa\"},{\"v\":[\"/status/2\"],\"t\":\"segundo\"}]"), r.path("it"));
    }

    @Test
    void readScriptComVariosCandidatosEAltAriaLabel() throws Exception {
        String pagina = """
                function El(tag,o){o=o||{};this.tagName=tag;this.innerText=o.text||'';this.attrs=o.attrs||{};
                  this.sels=o.sels||[];this.children=o.children||[];}
                El.prototype.getAttribute=function(n){return Object.prototype.hasOwnProperty.call(this.attrs,n)?this.attrs[n]:null;};
                function Root(children){this.children=children;}
                function desc(n,acc){(n.children||[]).forEach(function(c){acc.push(c);desc(c,acc);});return acc;}
                function qsa(n,s){var all=desc(n,[]);if(s==='*'){return all;}
                  return all.filter(function(e){return e.sels.indexOf(s)>=0;});}
                El.prototype.querySelectorAll=Root.prototype.querySelectorAll=function(s){return qsa(this,s);};
                El.prototype.querySelector=Root.prototype.querySelector=function(s){return qsa(this,s)[0]||null;};
                var art=new El('ARTICLE',{sels:['article'],attrs:{'data-id':'1'},text:'legenda principal',
                  children:[new El('IMG',{sels:['img'],attrs:{'alt':'imagem de capa'}}),
                            new El('A',{sels:['a[href]'],attrs:{href:'/posts/1'}}),
                            new El('A',{sels:['a[href]'],attrs:{href:'/posts/2','aria-label':'titulo do post'}})]});
                var main=new El('MAIN',{sels:['main'],children:[art]});
                globalThis.document=new Root([main]);
                document.body={innerText:''};
                """;
        JsonNode r = rodarPagina(pagina, NewIds.readScript(new NewIds.Spec("css:article", "", "href", "a[href]", ""), true));
        JsonNode it = r.path("it");
        assertEquals(1, it.size());
        assertEquals(Json.parse("[\"/posts/1\",\"/posts/2\"]"), it.get(0).path("v"));
        assertEquals("legenda principal\nimagem de capa\ntitulo do post", it.get(0).path("t").asText());
    }

    @Test
    void readScriptAttrTextNormalizaECorta300() throws Exception {
        String pagina = """
                function El(tag,o){o=o||{};this.tagName=tag;this.innerText=o.text||'';this.attrs=o.attrs||{};
                  this.sels=o.sels||[];this.children=o.children||[];}
                El.prototype.getAttribute=function(n){return Object.prototype.hasOwnProperty.call(this.attrs,n)?this.attrs[n]:null;};
                function Root(children){this.children=children;}
                function desc(n,acc){(n.children||[]).forEach(function(c){acc.push(c);desc(c,acc);});return acc;}
                function qsa(n,s){var all=desc(n,[]);if(s==='*'){return all;}
                  return all.filter(function(e){return e.sels.indexOf(s)>=0;});}
                El.prototype.querySelectorAll=Root.prototype.querySelectorAll=function(s){return qsa(this,s);};
                El.prototype.querySelector=Root.prototype.querySelector=function(s){return qsa(this,s)[0]||null;};
                var longo="a".repeat(500);
                var art=new El('ARTICLE',{sels:['article'],text:"  ola   mundo  "+longo});
                var main=new El('MAIN',{sels:['main'],children:[art]});
                globalThis.document=new Root([main]);
                document.body={innerText:''};
                """;
        JsonNode r = rodarPagina(pagina, NewIds.readScript(new NewIds.Spec("css:article", "", "@text", "", ""), false));
        String v = r.path("it").get(0).path("v").get(0).asText();
        assertTrue(v.startsWith("ola mundo "), v);
        assertEquals(300, v.length());
    }

    @Test
    void verificarIdAntigoNoDomSemCapturaJamaisPassa() {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"velho\"],\"t\":\"oi mundo\"}]}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r).add(r).add(r),
                        new RelogioFake(), spec("css:article"), Set.of("velho"), "oi", 1_000));
        assertTrue(e.getMessage().contains("nenhum item novo"), e.getMessage());
    }

    @Test
    void verificarIdCapturadoExigeMesmoTextoDoCandidato() throws Exception {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"nada aqui\"},{\"v\":[\"c\"],\"t\":\"oi mundo\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 5_000);
        assertEquals("c", n.id(), "b tem texto diferente: so o candidato com o expect vale");
        assertEquals(0, n.outros());
    }

    @Test
    void verificarPredicateCorrelacaoFiltraSemFalsePositive() throws Exception {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"oi\"},{\"v\":[\"c\"],\"t\":\"oi\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 5_000, id -> id.equals("c"));
        assertEquals("c", n.id(), "b e novo com o texto, mas o predicate (trace) nao o viu: nao adianta pegar o primeiro");
        // predicate que rejeita tudo: nenhum item novo
        String r2 = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"oi\"}]}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r2).add(r2).add(r2),
                        new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 1_000, id -> false));
        assertTrue(e.getMessage().contains("nenhum item novo"), e.getMessage());
        // estado invalido do predicate aborta fail-fast
        IllegalStateException falha = assertThrows(IllegalStateException.class,
                () -> NewIds.verificar(new Fila().add(r),
                        new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 5_000,
                        id -> { throw new IllegalStateException("trace invalido"); }));
        assertTrue(falha.getMessage().contains("trace invalido"), falha.getMessage());
    }

    @Test
    void verificarOutrosContaCandidatosComMesmoTextoAntesDaCorrelacao() throws Exception {
        // dois ids novos com o mesmo texto; o trace so capturou "c": "b" continua outro candidato
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"oi\"},{\"v\":[\"c\"],\"t\":\"oi\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "oi", 5_000, id -> id.equals("c"));
        assertEquals("c", n.id(), "correlacao escolhe o id comprovado");
        assertEquals(1, n.outros(), "b tem o mesmo texto e e novo: conta como outro mesmo sem correlacao");
    }

    @Test
    void normEspacosColapsaEspacosERemoveLarguraZero() {
        assertEquals("a b c d", NewIds.normEspacos("a\n\n b c\u200b d "));
        assertEquals("", NewIds.normEspacos(null));
    }

    @Test
    void verificarAchaNovoQuandoDomTemQuebraDeLinha() throws Exception {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"texto um\\ntexto dois\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "texto um texto dois", 5_000);
        assertEquals("b", n.id(), "\\n no DOM nao pode abortar o verify com o mesmo expect");
    }

    @Test
    void verificarDevolveTextoCruDoItemEscolhido() throws Exception {
        String r = "{\"p\":-1,\"it\":[{\"v\":[\"a\"],\"t\":\"x\"},{\"v\":[\"b\"],\"t\":\"texto um\\ntexto dois\"}]}";
        NewIds.Novo n = NewIds.verificar(new Fila().add(r),
                new RelogioFake(), spec("css:article"), Set.of("a"), "texto um texto dois", 5_000);
        assertEquals("texto um\ntexto dois", n.texto(), "texto cru do item, sem normalizacao");
        NewIds.Novo semT = NewIds.verificar(new Fila().add(leitura("a", "z")),
                new RelogioFake(), spec("css:article"), Set.of("a"), "", 5_000);
        assertEquals("", semT.texto(), "item sem t no DOM devolve texto vazio");
    }

    @Test
    void cobreSegmentosExemplosDaSpec() {
        assertTrue(NewIds.cobreSegmentos(
                        "Garanta o seu\ningresso: exemplo.com.br/fiel\u2026 Segunda tem jogo",
                        "Garanta o seu ingresso: https://exemplo.com.br/fiel/?utm_source=x Segunda tem jogo"),
                "link encurtado ou truncado na tela nao impede a prova");
        assertFalse(NewIds.cobreSegmentos(
                        "Garanta o seu ingresso bSegunda tem jogo",
                        "Garanta o seu ingresso\nSegunda tem jogo"),
                "lixo no meio do texto exibido nao passa");
        assertFalse(NewIds.cobreSegmentos("Segunda tem jogo Garanta", "Garanta Segunda tem jogo"),
                "segmentos fora de ordem nao passam");
        assertTrue(NewIds.cobreSegmentos("qualquer", ""), "textoCompleto vazio: nada a conferir");
    }

    /** Roda o readScript no node com uma pagina falsa propria. */
    private JsonNode rodarPagina(String pagina, String expr) throws Exception {
        Path f = tmp.resolve("n-" + System.nanoTime() + ".js");
        Files.writeString(f, pagina + "console.log(" + expr + ");\n", StandardCharsets.UTF_8);
        Process pr;
        try {
            pr = new ProcessBuilder("node", f.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("node ausente no PATH");
            return null;
        }
        assertTrue(pr.waitFor(20, TimeUnit.SECONDS), "node travou");
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, pr.exitValue(), out);
        return Json.parse(out);
    }

    @Test
    void readScriptParentAmbiguoDevolveErro() throws Exception {
        String pagina = PAGINA + "var main2=new El('MAIN',{sels:['main'],children:[]});document=new Root([main,main2]);";
        Path f = tmp.resolve("n2.js");
        Files.writeString(f, pagina + "console.log(" + NewIds.readScript(new NewIds.Spec("css:article", "css:main", "data-id", "", ""), false) + ");\n", StandardCharsets.UTF_8);
        Process pr;
        try {
            pr = new ProcessBuilder("node", f.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("node ausente no PATH");
            return;
        }
        assertTrue(pr.waitFor(20, TimeUnit.SECONDS));
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, pr.exitValue(), out);
        assertTrue(Json.parse(out).has("erro"), out);
    }
}
