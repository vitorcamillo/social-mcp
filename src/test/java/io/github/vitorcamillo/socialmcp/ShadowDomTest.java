package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Travessia de shadow DOM (ShadowDom.LIB) no node com um DOM falso: light DOM com link,
 * host do X Chat com shadowRoot aberto e um shadow aninhado. Sem node no PATH o teste e pulado.
 */
class ShadowDomTest {

    @TempDir
    Path tmp;

    private static final String PAGINA = """
            function El(tag,o){o=o||{};this.tagName=tag;this.innerText=o.text||'';this.href=o.href;
              this.sels=o.sels||[];this.children=o.children||[];this.shadowRoot=o.shadow||null;}
            function Root(children){this.children=children;}
            function desc(n,acc){(n.children||[]).forEach(function(c){acc.push(c);desc(c,acc);});return acc;}
            function qsa(n,s){var all=desc(n,[]);if(s==='*'){return all;}
              if(s==='a[href]'){return all.filter(function(e){return e.tagName==='A'&&e.href;});}
              return all.filter(function(e){return e.sels.indexOf(s)>=0;});}
            El.prototype.querySelectorAll=Root.prototype.querySelectorAll=function(s){return qsa(this,s);};
            El.prototype.querySelector=Root.prototype.querySelector=function(s){return qsa(this,s)[0]||null;};
            var aninhado=new Root([new El('DIV',{text:'Lopes / You follow them'}),
              new El('A',{href:'https://x.com/i/chat/1:2',sels:['a[href*="/i/chat/"]']})]);
            var chat=new Root([new El('STYLE',{text:'.css{color:red}'}),
              new El('DIV',{text:'Chat\\nYour inbox is empty',children:[
                new El('A',{href:'https://x.com/i/chat/9:9',sels:['a[href*="/i/chat/"]']}),
                new El('X-CARD',{sels:['x-card'],shadow:aninhado})]})]);
            var host=new El('DIV',{sels:['div[data-testid=xchatEmbedRoute]'],shadow:chat});
            var semShadow=new El('DIV',{sels:['div.sem']});
            globalThis.document=new Root([new El('MAIN',{children:[host,semShadow,
              new El('A',{href:'https://x.com/i/chat',sels:[]}),
              new El('A',{href:'https://x.com/i/chat/9:9',sels:[]})]})]);
            document.body={innerText:'To view keyboard shortcuts'};
            """;

    private JsonNode rodar(String expr) throws Exception {
        Path f = tmp.resolve("s.js");
        Files.writeString(f, PAGINA + "console.log(JSON.stringify(" + expr + "));\n", StandardCharsets.UTF_8);
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
    void textoAtravessaShadowAbertoEAninhadoSemCss() throws Exception {
        String t = rodar(ShadowDom.textScript()).asText();
        assertTrue(t.startsWith("To view keyboard shortcuts"), t);
        assertTrue(t.contains("Your inbox is empty"), t);
        assertTrue(t.contains("Lopes / You follow them"), t);
        assertFalse(t.contains("color:red"), t);
    }

    @Test
    void linksDeTodasAsRaizesSemRepeticaoEComFiltro() throws Exception {
        JsonNode l = Json.parse(rodar(ShadowDom.linksScript("/i/chat/")).asText());
        assertEquals(List.of("https://x.com/i/chat/9:9", "https://x.com/i/chat/1:2"),
                Json.M.convertValue(l, List.class), l.toString());
        JsonNode todos = Json.parse(rodar(ShadowDom.linksScript("")).asText());
        assertEquals(3, todos.size(), todos.toString());
    }

    @Test
    void findResolveCadaTrechoNoShadowRootDoAnterior() throws Exception {
        List<String> parts = ShadowDom.parts("css:div[data-testid=xchatEmbedRoute] >>> a[href*=\"/i/chat/\"]");
        JsonNode r = rodar("(function(){var e=" + ShadowDom.findScript(parts) + ";return e?e.href:null;})()");
        assertEquals("https://x.com/i/chat/9:9", r.asText());
        // aninhado: host > x-card > link
        r = rodar("(function(){var e=" + ShadowDom.findScript(ShadowDom.parts("div[data-testid=xchatEmbedRoute] >>> x-card >>> a[href*=\"/i/chat/\"]"))
                + ";return e?e.href:null;})()");
        assertEquals("https://x.com/i/chat/1:2", r.asText());
        // trecho sem shadowRoot no meio e elemento ausente devolvem null
        assertTrue(rodar(ShadowDom.findScript(List.of("div.sem", "a[href]"))).isNull());
        assertTrue(rodar(ShadowDom.findScript(List.of("div.nao-existe"))).isNull());
    }

    @Test
    void countContaCasamentosEmCadaShadowEFiltraPorTexto() throws Exception {
        // dois links /i/chat/ no shadow do host (um no nivel 1, um no aninhado nao conta: so o trecho exato)
        assertEquals(1, rodar(ShadowDom.countScript("css:div[data-testid=xchatEmbedRoute] >>> a[href*=\"/i/chat/\"]", "")).asInt());
        assertEquals(0, rodar(ShadowDom.countScript("css:div.nao-existe", "")).asInt());
        assertEquals(1, rodar(ShadowDom.countScript("div.sem", "")).asInt());
        // filtro por texto: o DIV do shadow aninhado tem o texto, o light DOM nao
        assertEquals(0, rodar(ShadowDom.countScript("div.sem", "Lopes")).asInt());
        assertEquals(1, rodar(ShadowDom.countScript("css:div[data-testid=xchatEmbedRoute] >>> x-card", "")).asInt());
    }

    @Test
    void xpathVazioRejeitadoENaoDesviaParaCss() {
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.countScript("xpath:", ""));
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.countScript("xpath:   ", ""));
        // XPath se distingue de CSS por xp !== null: "" nao pode cair em querySelectorAll(undefined)
        assertTrue(ShadowDom.LIB.contains("xp!==null"), ShadowDom.LIB);
        assertFalse(ShadowDom.LIB.contains("if(xp){"), ShadowDom.LIB);
        assertTrue(ShadowDom.countScript("xpath://a[@id='z']", "").contains("\"//a[@id='z']\""));
    }

    @Test
    void countScriptEscapaSeletorETexto() {
        String js = ShadowDom.countScript("css:a[title=\"x\"]", "o'i\"+alert(1)+\"");
        assertTrue(js.startsWith("(function(){") && js.endsWith("})()"), js);
        assertTrue(js.contains("\\\"+alert"), js);
        assertTrue(ShadowDom.countScript("xpath://a[@id='z']", "").contains("\"//a[@id='z']\""));
        assertTrue(ShadowDom.countScript("id:meu\"id", "").contains("[id=\\\"meu\\\\\\\"id\\\"]"));
    }

    @Test
    void parseDoSeparador() {
        assertNull(ShadowDom.parts("css:div > a"));
        assertNull(ShadowDom.parts("xpath://a"));
        assertNull(ShadowDom.parts(""));
        assertEquals(List.of("div[data-testid=xchatEmbedRoute]", "a[href*=\"/i/chat/\"]"),
                ShadowDom.parts("css:div[data-testid=xchatEmbedRoute] >>> a[href*=\"/i/chat/\"]"));
        assertEquals(List.of("a", "b", "c > d"), ShadowDom.parts("a>>>b >>> c > d"));
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.parts("css:a >>> "));
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.parts(">>> a"));
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.parts("xpath://a >>> b"));
        assertThrows(IllegalArgumentException.class, () -> ShadowDom.parts("id:x >>> b"));
    }

    @Test
    void scriptsSemReturnNoTopo() {
        for (String js : List.of(ShadowDom.textScript(), ShadowDom.linksScript("x'+alert(1)+'"), ShadowDom.findScript(List.of("a")))) {
            assertTrue(js.startsWith("(function(){") && js.endsWith("})()"), js);
        }
        assertTrue(ShadowDom.linksScript("x\"+alert(1)+\"").contains("\\\"+alert"));
    }
}
