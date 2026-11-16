package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Conferencia do campo antes do type: texto que ja estava no campo nunca vira sobra do texto
 * digitado (16/11: "rindo" + comentario saiu no Instagram). Fail-closed, sem limpar em silencio.
 */
final class CampoTexto {
    private CampoTexto() {
    }

    /** JSON {editavel, texto, selecionado} do editor focado (desce em shadow roots e iframes da mesma origem). */
    static final String SCRIPT = "(function(){try{var a=document.activeElement;"
            + "for(var i=0;i<20&&a;i++){if(a.shadowRoot&&a.shadowRoot.activeElement){a=a.shadowRoot.activeElement;continue;}"
            + "if(String(a.tagName||'').toUpperCase()==='IFRAME'){var cd=null;try{cd=a.contentDocument;}catch(e){}"
            + "if(cd&&cd.activeElement){a=cd.activeElement;continue;}}break;}"
            + "if(!a){return JSON.stringify({editavel:false,tag:''});}var t=String(a.tagName||'').toUpperCase();"
            + "if(t==='INPUT'||t==='TEXTAREA'){var v=String(a.value||''),sv='';"
            + "try{var s=a.selectionStart,e=a.selectionEnd;if(s!=null&&e!=null&&e>s){sv=v.substring(s,e);}}catch(x){}"
            + "return JSON.stringify({editavel:true,texto:v,selecionado:sv});}"
            + "if(!a.isContentEditable){return JSON.stringify({editavel:false,tag:t});}"
            + "while(a.parentElement&&a.parentElement.isContentEditable){a=a.parentElement;}"
            + "var r=a.getRootNode(),sel=(r&&r.getSelection?r.getSelection():null)||a.ownerDocument.getSelection(),ss='';"
            + "if(sel&&sel.rangeCount&&!sel.isCollapsed&&a.contains(sel.anchorNode)&&a.contains(sel.focusNode)){ss=sel.toString();}"
            + "return JSON.stringify({editavel:true,texto:String(a.innerText||a.textContent||''),selecionado:ss});}"
            + "catch(e){return JSON.stringify({erro:String(e&&e.message||e)});}})()";

    private static final Pattern INVISIVEL = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern ESPACOS = Pattern.compile("[\\s\\u00A0\\u202F]+");

    /** Normaliza so espacos (e caracteres invisiveis de editor vazio); o resto do texto fica intacto. */
    static String normaliza(String s) {
        return ESPACOS.matcher(INVISIVEL.matcher(s == null ? "" : s).replaceAll("")).replaceAll(" ").trim();
    }

    /**
     * Antes de digitar: o editor focado tem que estar vazio, ou com TODO o texto selecionado (o
     * insertText substitui, ex. edicao com ctrl+a), ou com texto que casa inteiro com
     * allowExisting (regex, ex. a mencao que o Mastodon pre-preenche). Senao NaoAgiu.
     */
    static void conferirAntesDeDigitar(Function<String, Object> exec, String allowExisting) {
        String r;
        try {
            r = String.valueOf(exec.apply(SCRIPT));
        } catch (RuntimeException e) {
            throw new RealClick.NaoAgiu("type abortado: nao deu para ler o campo antes de digitar (" + e.getMessage() + ")", e);
        }
        JsonNode j;
        try {
            j = Json.parse(r);
        } catch (RuntimeException e) {
            throw new RealClick.NaoAgiu("type abortado: leitura do campo invalida (" + corta(r) + ")", e);
        }
        if (j == null || !j.isObject() || j.hasNonNull("erro")) {
            throw new RealClick.NaoAgiu("type abortado: leitura do campo falhou ("
                    + corta(j == null ? r : j.path("erro").asText(r)) + ")");
        }
        if (!j.path("editavel").asBoolean(false)) {
            throw new RealClick.NaoAgiu("type abortado: o foco nao esta num campo editavel (" + j.path("tag").asText("?") + ")");
        }
        String texto = normaliza(j.path("texto").asText(""));
        if (texto.isEmpty() || texto.equals(normaliza(j.path("selecionado").asText("")))) return;
        if (allowExisting != null && !allowExisting.isEmpty() && Pattern.compile(allowExisting).matcher(texto).matches()) return;
        throw new RealClick.NaoAgiu("type abortado: campo ja tinha texto: '" + corta(texto) + "'");
    }

    private static String corta(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
