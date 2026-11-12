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
            + "function rjEls(doc,parts,xp){var els=[];if(xp!==null){var r=doc.evaluate(xp,doc,null,7,null);"
            + "for(var i=0;i<r.snapshotLength;i++){els.push(r.snapshotItem(i));}}else{rjAll(doc,parts,0,els);}return els;}"
            + "function rjCount(doc,parts,xp,expect){var els=rjEls(doc,parts,xp);"
            + "if(!expect){return els.length;}var n=0;for(var j=0;j<els.length;j++){"
            + "if(String(els[j].innerText||els[j].textContent||'').indexOf(expect)>=0){n++;}}return n;}"
            + "function rjTx(el){var tn=String(el.tagName||'').toUpperCase();"
            + "if(tn==='INPUT'||tn==='TEXTAREA'||tn==='SELECT'||(el.hasAttribute&&el.hasAttribute('contenteditable'))){return '';}"
            + "var s=String(el.innerText||el.textContent||'');s=s.replace(/\\s+/g,' ').trim();return s.substring(0,160);}"
            + "function rjSens(el){var gt=function(k){return el.getAttribute?el.getAttribute(k)||'':'';};"
            + "if(String(gt('type')).toLowerCase()==='password'){return true;}"
            + "var ac=String(gt('autocomplete')).toLowerCase();"
            + "return ac.indexOf('password')>=0||ac.indexOf('cc-')>=0;}"
            + "function rjRot(no){var t=String(no.tagName||'').toLowerCase();"
            + "var g=function(k){return no.getAttribute?no.getAttribute(k):null;};"
            + "var dt=g('data-testid');if(dt){return t+'[data-testid='+String(dt).substring(0,60)+']';}"
            + "var id=g('id');if(id){return t+'#'+id;}"
            + "var rl=g('role');if(rl){return t+'[role='+rl+']';}return t;}"
            + "function rjCam(el){var c=[];var no=el.parentNode;var v=0;"
            + "while(no&&c.length<5&&v<30){v++;"
            + "if(no.nodeType===11&&no.host){c.push('#shadow('+String(no.host.tagName||'?').toLowerCase()+')');no=no.host;}"
            + "else if(no.nodeType===1&&no.tagName){c.push(rjRot(no));no=no.parentNode;}"
            + "else{no=no.parentNode;}}return c.join(' < ');}"
            + "function rjAt(el){var at={};"
            + "var fix=['id','role','aria-label','aria-pressed','aria-checked','aria-expanded','aria-hidden',"
            + "'aria-selected','data-testid','href','hidden','slot','type','name','title','disabled','class'];"
            + "for(var f=0;f<fix.length;f++){var k=fix[f];"
            + "if(el.hasAttribute&&el.hasAttribute(k)){var w=el.getAttribute(k);if(w===null){w='';}w=String(w);"
            + "if(k==='class'){w=w.substring(0,80);}at[k]=w;}}"
            + "if(el.attributes){var nd=0;for(var a=0;a<el.attributes.length&&nd<12;a++){"
            + "var an=el.attributes[a].name;"
            + "if(an&&an.indexOf('data-')===0&&!Object.prototype.hasOwnProperty.call(at,an)){"
            + "var av=String(el.getAttribute(an)||'');at[an]=av.substring(0,120);nd++;}}}return at;}"
            + "function rjQuery(doc,parts,xp,max){try{var els=rjEls(doc,parts,xp);var total=els.length;var itens=[];"
            + "for(var i=0;i<els.length&&itens.length<max;i++){var el=els[i];try{"
            + "if(rjSens(el)){continue;}"
            + "itens.push({tag:String(el.tagName||'').toLowerCase(),texto:rjTx(el),attrs:rjAt(el),caminho:rjCam(el)});"
            + "}catch(s){}}return JSON.stringify({total:total,itens:itens});}"
            + "catch(e){return JSON.stringify({erro:String((e&&e.message)||e)});}}";

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
        Sel s = traduzir(selector);
        return "(function(){" + LIB + "return rjCount(document," + Json.write(s.parts) + ","
                + (s.xp == null ? "null" : Json.write(TextNode.valueOf(s.xp))) + ","
                + Json.write(TextNode.valueOf(expect == null ? "" : expect)) + ");})()";
    }

    /** Seletor traduzido para o JS: partes css (>>> resolvido) ou xpath; id: vira [id=...]. */
    private static final class Sel {
        final ArrayNode parts;
        final String xp;

        Sel(ArrayNode parts, String xp) {
            this.parts = parts;
            this.xp = xp;
        }
    }

    /** Mesma traducao de seletor do countScript (xpath:/id:/css:/>>>); xpath: vazio e erro. */
    private static Sel traduzir(String selector) {
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
        return new Sel(parts, xp);
    }

    /**
     * Consulta de DOM so leitura: JSON (string) {total, itens} com ate max itens
     * {tag, texto, attrs, caminho}. Mesma traducao de seletor do countScript.
     * Nunca le valor digitado (input/textarea/select/contenteditable tem texto="")
     * e item sensivel (type=password ou autocomplete com password/cc-) sai do
     * detalhe (mas conta no total). Erro de JS vira {erro:...}.
     */
    public static String queryScript(String selector, int max) {
        Sel s = traduzir(selector);
        return "(function(){" + LIB + "return rjQuery(document," + Json.write(s.parts) + ","
                + (s.xp == null ? "null" : Json.write(TextNode.valueOf(s.xp))) + "," + max + ");})()";
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
