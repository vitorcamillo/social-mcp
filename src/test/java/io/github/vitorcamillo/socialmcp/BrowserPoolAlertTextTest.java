package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ALERT_TEXT_JS no node com DOM falso: alerta leve, alerta em shadow root aberto
 * e alerta em shadow aninhado. Sem node no PATH o teste e pulado.
 */
class BrowserPoolAlertTextTest {

    @TempDir
    Path tmp;

    private static final String PAGINA = """
            function El(tag,o){o=o||{};this.tagName=tag;this.innerText=o.text||'';this.href=o.href;
              this.sels=o.sels||[];this.children=o.children||[];this.shadowRoot=o.shadow||null;}
            function Root(children){this.children=children;}
            function desc(n,acc){(n.children||[]).forEach(function(c){acc.push(c);desc(c,acc);});return acc;}
            function qsa(n,s){var all=desc(n,[]);if(s==='*'){return all;}
              if(s.indexOf('[role=alert]')>=0){return all.filter(function(e){
                return e.tagName==='H1'||e.tagName==='H2'||e.sels.indexOf('[role=alert]')>=0
                  ||e.sels.indexOf('[role=dialog]')>=0||e.sels.indexOf('[aria-live]')>=0;});}
              return all.filter(function(e){return e.sels.indexOf(s)>=0;});}
            El.prototype.querySelectorAll=Root.prototype.querySelectorAll=function(s){return qsa(this,s);};
            El.prototype.querySelector=Root.prototype.querySelector=function(s){return qsa(this,s)[0]||null;};
            var aninhado=new Root([new El('DIV',{text:'Rate limit aninhado',sels:['[role=alert]']})]);
            var chat=new Root([new El('H1',{text:'Rate limit no shadow'}),
              new El('X-CARD',{shadow:aninhado})]);
            var host=new El('DIV',{shadow:chat});
            globalThis.document=new Root([new El('MAIN',{children:[host,new El('H1',{text:'Alerta leve'})]})]);
            document.title='Titulo da pagina';
            document.body={innerText:'corpo'};
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
    void alertTextAtravessaShadowAbertoEAninhado() throws Exception {
        String t = rodar(BrowserPool.ALERT_TEXT_JS).asText();
        assertEquals("Titulo da pagina\nAlerta leve\nRate limit no shadow\nRate limit aninhado", t);
    }
}
