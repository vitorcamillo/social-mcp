package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JS do clique real (RealClick.LIB) no node com DOM falso: dialogo com scroll interno (como o
 * ytcp-uploads-dialog), radio Polymer com shadowRoot, botao coberto por overlay (joyride do
 * TikTok) e elemento 0x0. Sem node no PATH os testes de JS sao pulados.
 */
class RealClickTest {

    @TempDir
    Path tmp;

    @Test
    void falhasAntesDeAgirSaoNaoAgiu() {
        TimeoutException timeout = new TimeoutException("CDP timeout");
        RealClick.NaoAgiu falha = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.antesDeAgir("click", () -> { throw timeout; }));
        assertSame(timeout, falha.getCause());
        assertSame(falha, assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.antesDeAgir("click", () -> { throw falha; })));
        assertFalse(Tools.talvezAgiu("click", falha, Json.obj()));

        try {
            InterruptedException interrompido = new InterruptedException("cancelado");
            RealClick.NaoAgiu convertido = assertThrows(RealClick.NaoAgiu.class,
                    () -> RealClick.antesDeAgir("click", () -> { throw interrompido; }));
            assertSame(interrompido, convertido.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static final String PAGINA = """
            var W={innerWidth:1280,innerHeight:900,getComputedStyle:function(n){return {overflowY:n.ov||'visible'};}};
            W.top=W;var doc={defaultView:W};
            function El(tag,o){o=o||{};this.tagName=tag;this.id=o.id||'';this.className=o.cls||'';this.innerText=o.text||'';
              this.r=o.r||{left:0,top:0,width:0,height:0};this.ov=o.ov;this.scrollHeight=o.sh||0;this.clientHeight=o.ch||0;
              this.children=[];this.parentNode=null;this.shadowRoot=null;this.ownerDocument=doc;this.nodeType=1;this.attrs=o.attrs||{};
              this.hidden=!!o.hidden;}
            El.prototype.getClientRects=function(){return this.hidden?[]:[this.r];};
            El.prototype.add=function(c){c.parentNode=this;this.children.push(c);return c;};
            El.prototype.getBoundingClientRect=function(){var r=this.r;return {left:r.left,top:r.top,width:r.width,height:r.height,
              right:r.left+r.width,bottom:r.top+r.height};};
            El.prototype.getAttribute=function(a){return a in this.attrs?this.attrs[a]:null;};
            function desc(n,acc){(n.children||[]).forEach(function(c){acc.push(c);desc(c,acc);});return acc;}
            El.prototype.querySelectorAll=function(){return desc(this,[]);};
            function Shadow(host){this.host=host;this.children=[];this.nodeType=11;this.parentNode=null;}
            Shadow.prototype.add=El.prototype.add;Shadow.prototype.querySelectorAll=El.prototype.querySelectorAll;
            function inR(e,x,y){var r=e.r;return x>=r.left&&x<=r.left+r.width&&y>=r.top&&y<=r.top+r.height;}
            // hit test: o ultimo da lista "camadas" que contem o ponto (retargeting: devolve o host no documento)
            var camadas=[];doc.elementFromPoint=function(x,y){var h=null;camadas.forEach(function(e){if(inR(e,x,y)){h=e;}});return h;};
            var body=new El('BODY',{r:{left:0,top:0,width:1280,height:900}});
            var dialogo=body.add(new El('YTCP-UPLOADS-DIALOG',{id:'dialog',ov:'auto',sh:2000,ch:700,r:{left:100,top:100,width:1000,height:700}}));
            var radio=dialogo.add(new El('TP-YT-PAPER-RADIO-BUTTON',{r:{left:200,top:1300,width:300,height:40},attrs:{'aria-checked':'false'}}));
            var sr=new Shadow(radio);radio.shadowRoot=sr;
            var circulo=sr.add(new El('DIV',{id:'radioContainer',text:'',r:{left:200,top:1300,width:40,height:40}}));
            sr.add(new El('DIV',{id:'radioLabel',text:'Publico',r:{left:240,top:1300,width:260,height:40}}));
            sr.elementFromPoint=function(x,y){return inR(circulo,x,y)?circulo:null;};
            var visivel=dialogo.add(new El('TP-YT-PAPER-RADIO-BUTTON',{r:{left:200,top:400,width:300,height:40}}));
            var vsr=new Shadow(visivel);visivel.shadowRoot=vsr;
            var vc=vsr.add(new El('DIV',{id:'radioContainer',r:{left:200,top:400,width:300,height:40}}));
            vsr.elementFromPoint=function(x,y){return inR(vc,x,y)?vc:null;};
            var botao=body.add(new El('BUTTON',{text:'Publicar',r:{left:600,top:500,width:120,height:40}}));
            var overlay=body.add(new El('DIV',{cls:'react-joyride__overlay x',r:{left:0,top:0,width:1280,height:900}}));
            var zero=body.add(new El('BUTTON',{r:{left:10,top:10,width:0,height:0}}));
            var host=body.add(new El('YTCP-BUTTON',{id:'done-button',r:{left:900,top:850,width:100,height:36}}));
            var hs=new Shadow(host);host.shadowRoot=hs;hs.add(new El('STYLE',{text:'.x{}'}));hs.add(new El('DIV',{text:'Publicar',hidden:true}));hs.add(new El('DIV',{text:'Salvar'}));
            var botaoPorCima=body.add(new El('BUTTON',{attrs:{'aria-label':'Fechar'},r:{left:0,top:0,width:1280,height:900}}));
            var oculto=body.add(new El('BUTTON',{text:'Publicar',hidden:true,r:{left:5,top:5,width:50,height:20}}));
            camadas=[body,dialogo,visivel,botao];
            """;

    private JsonNode rodar(String setup, String expr) throws Exception {
        Path f = tmp.resolve("s.js");
        Files.writeString(f, RealClick.LIB + "\n" + PAGINA + setup + "\nconsole.log(JSON.stringify(" + expr + "));\n",
                StandardCharsets.UTF_8);
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
    void radioDentroDoScrollDoDialogoPedeRodaSobreODialogo() throws Exception {
        JsonNode s = rodar("", "rjScrollInfo(radio)");
        assertFalse(s.get("dentro").asBoolean());
        assertEquals("ytcp-uploads-dialog#dialog", s.get("conteiner").asText());
        assertEquals(600, s.get("wx").asDouble(), 0.01); // centro da area visivel do dialogo
        assertEquals(450, s.get("wy").asDouble(), 0.01);
        assertEquals(1320 - 450, s.get("dy").asDouble(), 0.01); // positivo = rolar para baixo
        assertTrue(rodar("", "rjScrollInfo(visivel)").get("dentro").asBoolean());
    }

    @Test
    void conteinerForaDaJanelaRolaODocumentoPrimeiro() throws Exception {
        JsonNode s = rodar("dialogo.r.top=1500;radio.r.top=1600;", "rjScrollInfo(radio)");
        assertFalse(s.get("dentro").asBoolean());
        assertEquals("documento", s.get("conteiner").asText());
        assertEquals(450, s.get("wy").asDouble(), 0.01); // meio da janela de 900
        assertEquals(1620 - 450, s.get("dy").asDouble(), 0.01);
    }

    @Test
    void hitTestDesceNoShadowRootEReconheceOProprioRadio() throws Exception {
        JsonNode m = rodar("", "rjMeasure(visivel)");
        assertEquals(350, m.get("x").asDouble(), 0.01);
        assertEquals(420, m.get("y").asDouble(), 0.01);
        assertTrue(m.get("acertou").asBoolean(), m.toString());
        assertEquals("div#radioContainer", m.get("alvo").asText());
    }

    @Test
    void overlayPorCimaViraAvisoComODescritorDoOverlay() throws Exception {
        JsonNode m = rodar("camadas.push(overlay);", "rjMeasure(botao)");
        assertFalse(m.get("acertou").asBoolean());
        assertEquals("div.react-joyride__overlay", m.get("alvo").asText());
        assertTrue(rodar("", "rjMeasure(botao)").get("acertou").asBoolean());
    }

    @Test
    void outroControlePorCimaEConflitoMasCamadaDoProprioBotaoNao() throws Exception {
        JsonNode m = rodar("camadas.push(botaoPorCima);", "rjMeasure(botao)");
        assertFalse(m.get("acertou").asBoolean());
        assertEquals("button", m.get("conflito").asText());
        // overlay sem papel de controle: so aviso (sem conflito)
        assertTrue(rodar("camadas.push(overlay);", "rjMeasure(botao)").get("conflito").isNull());
        // alvo e um filho do botao e o hit cai noutra camada do MESMO botao (YouTube "Editar rascunho")
        JsonNode y = rodar("var rotulo=botao.add(new El('DIV',{r:{left:640,top:510,width:40,height:20}}));"
                + "var feedback=botao.add(new El('DIV',{cls:'ytSpecTouchFeedbackShapeFill',r:{left:600,top:500,width:120,height:40}}));"
                + "camadas.push(rotulo);camadas.push(feedback);", "rjMeasure(rotulo)");
        assertFalse(y.get("acertou").asBoolean());
        assertTrue(y.get("conflito").isNull(), y.toString());
    }

    @Test
    void scrollerEAchadoAtravessandoOShadowRoot() throws Exception {
        JsonNode s = rodar("", "rjScrollInfo(circulo)"); // circulo esta no shadow do radio, dentro do dialogo
        assertEquals("ytcp-uploads-dialog#dialog", s.get("conteiner").asText());
        assertFalse(s.get("dentro").asBoolean());
    }

    @Test
    void textoOcultoNaoContaComoEstado() throws Exception {
        assertEquals("", rodar("", "rjElText(oculto)").asText());
    }

    @Test
    void focoDentroDoElementoOuDoShadowDele() throws Exception {
        assertTrue(rodar("doc.activeElement=visivel;visivel.shadowRoot.activeElement=vc;", "rjFocused(visivel)").asBoolean());
        assertFalse(rodar("doc.activeElement=visivel;", "rjFocused(vc)").asBoolean());
        assertFalse(rodar("doc.activeElement=botao;", "rjFocused(visivel)").asBoolean());
        assertFalse(rodar("doc.activeElement=null;", "rjFocused(visivel)").asBoolean());
    }

    @Test
    void focoNoBodyOuEmAncestralComumNaoContaComoFocoNoAlvo() throws Exception {
        assertFalse(rodar("doc.activeElement=body;", "rjFocused(visivel)").asBoolean());
        assertFalse(rodar("doc.activeElement=body;", "rjFocused(botao)").asBoolean());
        assertFalse(rodar("doc.activeElement=dialogo;", "rjFocused(visivel)").asBoolean());
    }

    @Test
    void focoNoEditorAncestralEfetivoContaComoFocoNoAlvo() throws Exception {
        assertTrue(rodar("var ed=new El('DIV',{attrs:{contenteditable:'true'}});body.add(ed);"
                + "var trecho=ed.add(new El('SPAN',{}));doc.activeElement=ed;", "rjFocused(trecho)").asBoolean());
    }

    @Test
    void medidaRepetidaAposMovimentoDetectaControleSurgidoPorHover() throws Exception {
        JsonNode m = rodar("var antes=rjMeasure(botao);camadas.push(botaoPorCima);",
                "{antes:antes.conflito,depois:rjMeasure(botao).conflito}");
        assertTrue(m.get("antes").isNull(), m.toString());
        assertEquals("button", m.get("depois").asText());
    }

    @Test
    void elementoSemCaixaOuForaDaJanelaEErro() throws Exception {
        assertTrue(rodar("", "rjMeasure(zero)").get("erro").asText().contains("sem caixa"));
        assertTrue(rodar("", "rjMeasure(radio)").get("erro").asText().contains("fora da janela"));
    }

    @Test
    void textoDoElementoIncluiShadowRootESemStyle() throws Exception {
        assertEquals("Salvar", rodar("", "rjElText(host)").asText());
        assertEquals("Publicar", rodar("", "rjElText(botao)").asText());
    }

    @Test
    void funcoesDeElementoSaoDeclaracoesValidas() throws Exception {
        for (String fn : List.of(RealClick.scrollInfoFn(), RealClick.measureFn(), RealClick.textFn(),
                RealClick.scrollIntoViewFn(true), RealClick.attrFn("aria-checked"))) {
            assertTrue(fn.startsWith("function(){"), fn);
        }
        JsonNode v = rodar("", "JSON.parse((" + RealClick.attrFn("aria-checked") + ").call(radio))");
        assertEquals("false", v.get("v").asText());
        JsonNode ausente = rodar("", "JSON.parse((" + RealClick.attrFn("data-x") + ").call(radio))");
        assertTrue(ausente.get("v").isNull());
    }

    @Test
    void trilhaTerminaNoAlvoExatoComPontosSuficientes() {
        Random rnd = new Random(7);
        List<double[]> p = RealClick.path(10, 800, 650, 520, rnd);
        assertTrue(p.size() >= 6 && p.size() <= 25, "pontos: " + p.size());
        assertArrayEquals(new double[]{650, 520}, p.get(p.size() - 1));
        for (double[] pt : p) assertTrue(pt[0] >= 0 && pt[1] >= 0);
        assertEquals(6, RealClick.path(100, 100, 102, 101, rnd).size());
    }

    @Test
    void rodaAndaNaDirecaoDoAlvoSemPassarDele() {
        Random rnd = new Random(1);
        for (int i = 0; i < 50; i++) {
            double d = RealClick.wheelDelta(870, rnd);
            assertTrue(d >= 96 && d <= 144, "delta " + d);
            assertTrue(RealClick.wheelDelta(-870, rnd) < 0);
        }
        assertEquals(30, RealClick.wheelDelta(30, rnd), 0.001);
    }

    @Test
    void modoPadraoEMouseEInvalidoFalha() {
        assertEquals("mouse", RealClick.mode(Json.parse("{}")));
        assertEquals("dom", RealClick.mode(Json.parse("{\"clickMode\":\"dom\"}")));
        assertThrows(IllegalArgumentException.class, () -> RealClick.mode(Json.parse("{\"clickMode\":\"js\"}")));
    }

    @Test
    void eventoDeMouseLevaBotaoEButtons() {
        Map<String, Object> p = RealClick.mouseEvent("mousePressed", 1.5, 2.5, "left", 1, 1);
        assertEquals("left", p.get("button"));
        assertEquals(1, p.get("buttons"));
        assertEquals(1, p.get("clickCount"));
        assertEquals("mouse", p.get("pointerType"));
    }
}
