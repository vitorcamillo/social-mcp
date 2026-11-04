package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Travessia de shadow DOM ABERTO (shadow roots fechados continuam invisiveis).
 * O JS e uma biblioteca pura (rjRoots/rjText/rjLinks/rjFind) testada no node com DOM falso;
 * os scripts sao IIFE sem "return" no topo (Runtime.evaluate).
 */
public final class ShadowDom {
    /** Separador de seletor que entra no shadowRoot do trecho anterior. */
    public static final String PIERCE = ">>>";

    private ShadowDom() {}

    /** Funcoes JS: raizes (document + shadow roots abertos), texto, links e busca por trechos. */
    static final String LIB = "function rjRoots(r,acc){acc.push(r);var es=r.querySelectorAll('*');"
            + "for(var i=0;i<es.length;i++){if(es[i].shadowRoot){rjRoots(es[i].shadowRoot,acc);}}return acc;}"
            + "var RJ_SKIP={STYLE:1,SCRIPT:1,TEMPLATE:1,LINK:1,NOSCRIPT:1};"
            + "function rjText(doc){var out=[];rjRoots(doc,[]).forEach(function(r){"
            + "if(r===doc){out.push(doc.body?(doc.body.innerText||''):'');}"
            + "else{var cs=r.children||[];for(var i=0;i<cs.length;i++){"
            + "if(!RJ_SKIP[String(cs[i].tagName||'').toUpperCase()]){out.push(cs[i].innerText||'');}}}});"
            + "return out.filter(function(s){return s;}).join('\\n');}"
            + "function rjLinks(doc,match){var seen=Object.create(null);var out=[];rjRoots(doc,[]).forEach(function(r){"
            + "var as=r.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var h=as[i].href;"
            + "if(h&&String(h).indexOf(match)>=0&&!seen[h]){seen[h]=1;out.push(String(h));}}});return out.slice(0,500);}"
            + "function rjFind(doc,parts){var sc=doc;for(var i=0;i<parts.length;i++){var e=sc.querySelector(parts[i]);"
            + "if(!e){return null;}if(i===parts.length-1){return e;}if(!e.shadowRoot){return null;}sc=e.shadowRoot;}return null;}"
            + "function rjAll(sc,parts,i,acc){var es=sc.querySelectorAll(parts[i]);for(var k=0;k<es.length;k++){"
            + "if(i===parts.length-1){acc.push(es[k]);}else if(es[k].shadowRoot){rjAll(es[k].shadowRoot,parts,i+1,acc);}}return acc;}"
            + "function rjCount(doc,parts,xp,expect){var els=[];if(xp!==null){var r=doc.evaluate(xp,doc,null,7,null);"
            + "for(var i=0;i<r.snapshotLength;i++){els.push(r.snapshotItem(i));}}else{rjAll(doc,parts,0,els);}"
            + "if(!expect){return els.length;}var n=0;for(var j=0;j<els.length;j++){"
            + "if(String(els[j].innerText||els[j].textContent||'').indexOf(expect)>=0){n++;}}return n;}";

    /** Texto visivel: body.innerText + innerText dos filhos de cada shadow root aberto. */
    public static String textScript() {
        return "(function(){" + LIB + "return rjText(document);})()";
    }

    /** JSON (string) com ate 500 hrefs unicos contendo match, inclusive dentro de shadow roots. */
    public static String linksScript(String match) {
        return "(function(){" + LIB + "return JSON.stringify(rjLinks(document,"
                + Json.write(TextNode.valueOf(match == null ? "" : match)) + "));})()";
    }

    /** Elemento (ou null) resolvendo cada trecho dentro do shadowRoot do anterior. */
    public static String findScript(List<String> parts) {
        ArrayNode arr = Json.M.createArrayNode();
        parts.forEach(arr::add);
        return "(function(){" + LIB + "return rjFind(document," + Json.write(arr) + ");})()";
    }

    /**
     * Quantos elementos casam o seletor (css:, id:, xpath: ou css com {@code >>>}, que conta os
     * casamentos dentro de cada shadowRoot do trecho anterior); com expect, so os que contem o
     * texto. Base do verify_absent: 0 = ausente. {@code xpath:} sem expressao e rejeitado:
     * vazio nao pode virar consulta CSS de {@code undefined} e "provar" uma ausencia.
     */
    public static String countScript(String selector, String expect) {
        String xp = null;
        ArrayNode parts = Json.M.createArrayNode();
        if (selector.startsWith("xpath:")) {
            xp = selector.substring(6);
            if (xp.isBlank()) {
                throw new IllegalArgumentException("seletor 'xpath:' sem expressao: '" + selector + "'");
            }
        } else if (selector.startsWith("id:")) {
            parts.add("[id=" + Json.write(TextNode.valueOf(selector.substring(3))) + "]");
        } else {
            List<String> ps = parts(selector);
            if (ps == null) ps = List.of(selector.startsWith("css:") ? selector.substring(4) : selector);
            ps.forEach(parts::add);
        }
        return "(function(){" + LIB + "return rjCount(document," + Json.write(parts) + ","
                + (xp == null ? "null" : Json.write(TextNode.valueOf(xp))) + ","
                + Json.write(TextNode.valueOf(expect == null ? "" : expect)) + ");})()";
    }

    /**
     * Trechos de um seletor com {@code >>>} (prefixo css: opcional). Sem {@code >>>}: null
     * (comportamento atual). {@code >>>} com id:/xpath: ou trecho vazio: erro.
     */
    public static List<String> parts(String selector) {
        if (selector == null || !selector.contains(PIERCE)) return null;
        if (selector.startsWith("id:") || selector.startsWith("xpath:")) {
            throw new IllegalArgumentException("seletor com >>> so vale para css: '" + selector + "'");
        }
        String css = selector.startsWith("css:") ? selector.substring(4) : selector;
        List<String> out = new ArrayList<>();
        for (String p : css.split(PIERCE, -1)) {
            String t = p.trim();
            if (t.isEmpty()) throw new IllegalArgumentException("seletor com >>> tem trecho vazio: '" + selector + "'");
            out.add(t);
        }
        return out;
    }
}
