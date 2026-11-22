package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * social_inspect: lista os controles interativos VISIVEIS da pagina atual
 * (inputs, textarea, selects, botoes, [role=button], contenteditable).
 *
 * <p>SOMENTE LEITURA: o script fixo abaixo nao recebe URL, JS, seletor nem
 * qualquer outra entrada do chamador (so o inteiro maxControls, limitado no
 * servidor). Nao clica, nao foca, nao dispara eventos e nao altera o DOM.
 *
 * <p>Privacidade: o script omite o no inteiro de controles sensiveis
 * (password, hidden, token/OTP/PIN/cartao/credencial, tambem por
 * labels/atributos) e nunca devolve value, texto de textarea/contenteditable,
 * cookies, HTML bruto, scripts, href, hidden input ou password. O
 * {@link #sanitizeResult} repete o filtro no servidor (allowlist de chaves,
 * teto de tamanho, segundo passe de sensiveis, teto de quantidade).
 */
public final class Controls {
    private Controls() {
    }

    /** Valor quando maxControls nao vem ou nao e inteiro. */
    public static final int PADRAO_MAX = 60;
    /** Limites aceitos para maxControls. */
    public static final int MIN_MAX = 1;
    /** Limites aceitos para maxControls. */
    public static final int MAX_MAX = 100;
    /** Teto de cada string devolvida (rotulos, texto, ids, seletores). */
    static final int TETO_STR = 160;

    /**
     * Nomes que indicam segredo: o no inteiro e omitido. Vale para id, name,
     * rotulo e texto curto do controle. {@code pin} com borda de palavra para
     * nao pegar "spin"/"opiniao".
     */
    static final Pattern SENSIVEL = Pattern.compile(
            "(?i)(passw|passwd|senha|secret|token|jwt|otp|totp|cvv|cvc|credit-?card|cartao|cartão|codigo|código|verification|verifica|recupera|one-time-code|autentica|"
                    + "credencial|credential|authorization|bearer|api[_-]?key|session|sessid|cookie|"
                    + "csrf|xsrf|current-password|cc-num|cc-exp|2fa|mfa|\\bpin\\b)");

    /** maxControls do args, limitado a [1, 100] (padrao 60). */
    public static int clampMaxControls(JsonNode args) {
        int m = PADRAO_MAX;
        if (args != null && args.has("maxControls") && args.get("maxControls").isInt()) {
            m = args.get("maxControls").asInt();
        }
        return Math.min(MAX_MAX, Math.max(MIN_MAX, m));
    }

    /**
     * Script FIXO de inspecao: sem nenhuma entrada do chamador alem do limite
     * (inteiro ja limitado). Atravessa document + shadow roots ABERTOS, pega
     * controles visiveis e devolve JSON com tag/type/role/id/name/label/texto
     * curto/xpath/css, mais count e truncated. Nunca le value, nunca escreve.
     */
    static String inspectScript(int max) {
        int m = Math.min(MAX_MAX, Math.max(MIN_MAX, max));
        return """
(function(){
const MAX=__MAX__, LIMIT=2048;
const sensitive=/(passw|passwd|senha|secret|token|jwt|otp|totp|cvv|cvc|credit.?card|cartao|cartão|codigo|código|verification|verifica|recupera|one-time-code|autentica|credencial|credential|authorization|bearer|api[_-]?key|session|sessid|cookie|csrf|xsrf|cc-num|cc-exp|2fa|mfa|\\bpin\\b)/i;
const attr=(e,k)=>e.getAttribute(k)||'';
const short=s=>String(s||'').replace(/\\s+/g,' ').trim().slice(0,160);
const quote=s=>JSON.stringify(String(s));
const roots=[document], seenRoots=new Set(roots);
for(let r=0;r<roots.length && roots.length<256;r++){
  for(const e of roots[r].querySelectorAll('*')){
    if(e.shadowRoot && !seenRoots.has(e.shadowRoot)){seenRoots.add(e.shadowRoot);roots.push(e.shadowRoot);}
  }
}
function unique(root,css,node){try{const a=root.querySelectorAll(css);return a.length===1&&a[0]===node;}catch{return false;}}
function localSelector(node,root){
  const tag=node.tagName.toLowerCase(), id=attr(node,'id');
  if(id && globalThis.CSS && CSS.escape){const c='#'+CSS.escape(id);if(c.length<=LIMIT&&unique(root,c,node))return c;}
  for(const key of ['name','aria-label','placeholder']){
    const val=attr(node,key);if(!val)continue;
    const c=tag+'['+key+'='+quote(val)+']';if(c.length<=LIMIT&&unique(root,c,node))return c;
  }
  const segments=[];let e=node;
  while(e&&e.nodeType===1){
    let i=1;for(let n=e.previousElementSibling;n;n=n.previousElementSibling)if(n.tagName===e.tagName)i++;
    segments.unshift(e.tagName.toLowerCase()+':nth-of-type('+i+')');
    const c=segments.join(' > ');if(c.length>LIMIT)return '';
    if(unique(root,c,node))return c;
    e=e.parentElement;
  }
  return '';
}
function selector(node){
  let root=node.getRootNode(), css=localSelector(node,root), depth=0;
  if(!css)return '';
  while(root.host&&depth++<16){
    const host=root.host, outer=host.getRootNode(), c=localSelector(host,outer);
    if(!c)return '';css=c+' >>> '+css;if(css.length>LIMIT)return '';root=outer;
  }
  return root===document?css:'';
}
const controls=[];let omitted=0;
for(const root of roots){
  for(const e of root.querySelectorAll('input,textarea,select,button,[role="button"],[contenteditable]:not([contenteditable="false"])')){
    const tag=e.tagName.toUpperCase(), type=attr(e,'type').toLowerCase();
    if(type==='hidden'||type==='password')continue;
    const box=e.getBoundingClientRect(), style=getComputedStyle(e);
    if(!box.width||!box.height||style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse')continue;
    const id=attr(e,'id'), name=attr(e,'name'), role=attr(e,'role');
    // Only associated labels and metadata; never input values or editable contents.
    const labelTexts=e.labels?Array.from(e.labels,x=>x.innerText||'').join(' '):'';
    const label=short([attr(e,'aria-label'),attr(e,'placeholder'),labelTexts].join(' '));
    const text=(tag==='BUTTON'||tag==='SELECT'||tag==='A'||role==='button')?short(e.innerText):'';
    if(sensitive.test([id,name,label,text,attr(e,'autocomplete'),attr(e,'inputmode')].join(' ')))continue;
    const css=selector(e);if(!css)continue;
    if(controls.length>=MAX){omitted++;continue;}
    const out={tag,css};
    for(const lb of (e.labels||[])){
      const labelText=short(lb.innerText), q=labelText.indexOf("'")<0?"'"+labelText+"'":labelText.indexOf('"')<0?'"'+labelText+'"':null;
      if(!q||!labelText)continue;
      const t=tag.toLowerCase(), xp='//'+t+'[@id=//label[normalize-space(.)='+q+']/@for] | //label[normalize-space(.)='+q+']//'+t;
      try{const a=document.evaluate(xp,document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);if(a.snapshotLength===1&&a.snapshotItem(0)===e){out.xpath=xp;break;}}catch{}
    }
    for(const [key,val] of Object.entries({type,role,id,name,label,text}))if(val)out[key]=short(val);
    controls.push(out);
  }
}
return JSON.stringify({controls,count:controls.length,truncated:omitted>0});
})()
""".replace("__MAX__", Integer.toString(m));
    }

    /** Corta, colapsa espacos e limita a {@link #TETO_STR} (nunca null). */
    static String corta(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.substring(0, Math.min(t.length(), TETO_STR));
    }

    /**
     * Filtro server-side (defesa em profundidade): allowlist de chaves
     * (tag/type/role/id/name/rotulo/texto/xpath/css — value, href, html e
     * qualquer outra cai fora), teto de tamanho, segundo passe de sensiveis
     * (descarta o no) e teto de quantidade.
     */
    static ObjectNode sanitizeResult(String network, String url, JsonNode bruto, int max) {
        int m = Math.min(MAX_MAX, Math.max(MIN_MAX, max));
        ObjectNode out = Json.obj();
        out.put("network", network == null ? "" : network);
        out.put("url", safeUrl(url));
        ArrayNode lista = out.putArray("controles");
        int crus = 0;
        boolean marcado = bruto != null && bruto.path("truncated").asBoolean(false);
        if (bruto != null && bruto.path("controls").isArray()) {
            for (JsonNode n : bruto.path("controls")) {
                if (n.isObject()) {
                    crus++;
                }
            }
            for (JsonNode n : bruto.path("controls")) {
                if (!n.isObject() || lista.size() >= m) {
                    continue;
                }
                ObjectNode limpo = sanitizeNode(n);
                if (limpo != null) {
                    lista.add(limpo);
                }
            }
        }
        out.put("total", lista.size());
        out.put("truncado", marcado || crus > m);
        return out;
    }

    static String safeUrl(String value) {
        if (value == null) return "";
        try {
            java.net.URI u = java.net.URI.create(value);
            if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || u.getHost() == null) return "";
            return new java.net.URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString();
        } catch (Exception e) { return ""; }
    }

    private static String selectorValue(String value) {
        return value != null && value.length() <= 2048 ? value : "";
    }

    /** Um no do script para o formato de saida; null = descartar. */
    static ObjectNode sanitizeNode(JsonNode n) {
        String tag = corta(n.path("tag").asText("")).toUpperCase(Locale.ROOT);
        if (tag.isEmpty()) {
            return null;
        }
        String type = corta(n.path("type").asText("")).toLowerCase(Locale.ROOT);
        if ("password".equals(type) || "hidden".equals(type)) {
            return null;
        }
        String id = corta(n.path("id").asText(""));
        String name = corta(n.path("name").asText(""));
        String label = corta(n.path("label").asText(""));
        String roleValue = corta(n.path("role").asText(""));
        String text = ("BUTTON".equals(tag) || "SELECT".equals(tag) || "A".equals(tag) || "button".equals(roleValue))
                ? corta(n.path("text").asText("")) : "";
        if (SENSIVEL.matcher(id + " " + name + " " + label + " " + text + " " + n.path("autocomplete").asText("")).find()) {
            return null;
        }
        String xpath = selectorValue(n.path("xpath").asText(""));
        String css = selectorValue(n.path("css").asText(""));
        if (id.isEmpty() && name.isEmpty() && label.isEmpty() && text.isEmpty()
                && xpath.isEmpty() && css.isEmpty()) {
            return null;
        }
        ObjectNode o = Json.obj();
        o.put("tag", tag);
        if (!type.isEmpty()) {
            o.put("type", type);
        }
        String role = corta(n.path("role").asText(""));
        if (!role.isEmpty()) {
            o.put("role", role);
        }
        if (!id.isEmpty()) {
            o.put("id", id);
        }
        if (!name.isEmpty()) {
            o.put("name", name);
        }
        if (!label.isEmpty()) {
            o.put("rotulo", label);
        }
        if (!text.isEmpty()) {
            o.put("texto", text);
        }
        if (!xpath.isEmpty()) {
            o.put("xpath", xpath);
        }
        if (!css.isEmpty()) {
            o.put("css", css);
        }
        return o;
    }
}
