package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prova de "item NOVO criado por esta acao" comparando ids antes/depois: capture_ids
 * guarda o conjunto de ids de uma lista antes do passo publico; verify_new_id espera um
 * item cujo id nao esta nesse conjunto. Logica pura, testavel sem navegador: o JS de
 * leitura e uma IIFE que devolve JSON ({p, it} ou {erro}); exec e um seam (executeScript)
 * e Relogio permite relogio falso nos testes.
 */
final class NewIds {
    static final long CAPTURE_POLL_MS = 1_000;
    static final long VERIFY_POLL_MS = 500;
    static final int MAX_IDS = 5_000;
    /** stableMs padrao do capture_ids: 2 leituras seguidas iguais com poll de 1000 ms. */
    static final long STABLE_PADRAO_MS = 1_000;

    private NewIds() {}

    record Spec(String selector, String parentSelector, String attr, String attrSelector, String idRegex, long stableMs, int minIds) {
        Spec(String selector, String parentSelector, String attr, String attrSelector, String idRegex, long stableMs) {
            this(selector, parentSelector, attr, attrSelector, idRegex, stableMs, 0);
        }

        Spec(String selector, String parentSelector, String attr, String attrSelector, String idRegex) {
            this(selector, parentSelector, attr, attrSelector, idRegex, STABLE_PADRAO_MS, 0);
        }

        static Spec of(JsonNode a) {
            return new Spec(
                    a.path("selector").asText(""),
                    a.path("parentSelector").asText(""),
                    a.path("attr").asText(""),
                    a.path("attrSelector").asText(""),
                    a.path("idRegex").asText(""),
                    a.path("stableMs").asLong(STABLE_PADRAO_MS),
                    a.path("minIds").asInt(0));
        }
    }

    /** Item de uma varredura: candidatos de id (1+; com attrSelector, ate 10) e texto opcional para expect. */
    record Item(List<String> v, String texto) {}

    /** Leitura de uma varredura: parents = -1 (sem parent), 0 (nao apareceu), 1 (um); itens = {v:[...], texto?}. */
    record Leitura(int parents, List<Item> itens, String erro) {}

    record Captura(Spec spec, Set<String> ids, long ms, int leituras) {}

    record Novo(String id, int outros, int ancoras) {}

    interface Relogio {
        long agora();

        void dormir(long ms) throws InterruptedException;
    }

    /** Relogio real: System.currentTimeMillis + Thread.sleep. */
    static Relogio real() {
        return new Relogio() {
            @Override public long agora() { return System.currentTimeMillis(); }
            @Override public void dormir(long ms) throws InterruptedException { Thread.sleep(ms); }
        };
    }

    /** JS IIFE que devolve JSON string {p, it:[{v:[...], t?}]} ou {erro}. Texto (t) so com comTexto. */
    static String readScript(Spec s, boolean comTexto) {
        ObjectNode parent = s.parentSelector() == null || s.parentSelector().isBlank()
                ? null : plano(s.parentSelector());
        ObjectNode sel = plano(s.selector());
        String attrSel = s.attrSelector() == null || s.attrSelector().isBlank()
                ? null : (s.attrSelector().startsWith("css:") ? s.attrSelector().substring(4) : s.attrSelector());
        return "(function(){"
                + ShadowDom.LIB
                + "function rjSel(sc,pl){var out=[];"
                + "if(pl.t==='xpath'){var r=document.evaluate(pl.v,sc||document,null,7,null);"
                + "for(var i=0;i<r.snapshotLength;i++){out.push(r.snapshotItem(i));}}"
                + "else if(pl.t==='id'){var e=document.getElementById(pl.v);if(e){out.push(e);}}"
                + "else{var ps=pl.p;if(ps.length===1){var es=(sc||document).querySelectorAll(ps[0]);"
                + "for(var i2=0;i2<es.length;i2++){out.push(es[i2]);}}"
                + "else{rjAll(sc||document,ps,0,out);}}return out;}"
                + "var P=" + (parent == null ? "null" : Json.write(parent)) + ";"
                + "var S=" + Json.write(sel) + ";"
                + "var ATTR=" + jsStr(s.attr()) + ";"
                + "var AS=" + (attrSel == null ? "null" : jsStr(attrSel)) + ";"
                + "var TXT=" + comTexto + ";"
                + "var IS_TEXT=" + "@text".equals(s.attr()) + ";"
                + "var MAX=" + MAX_IDS + ";"
                + "var LIM=10;"
                + "function norm(x){x=String(x==null?'':x).replace(/\\s+/g,' ').trim();return x.substring(0,300);}"
                + "function val(alvo){if(IS_TEXT){return norm((alvo&&alvo.innerText)||(alvo&&alvo.textContent)||'');}"
                + "return (alvo&&alvo.getAttribute)?alvo.getAttribute(ATTR):null;}"
                + "function itemTxt(el){var parts=[String((el&&el.innerText)||(el&&el.textContent)||'')];var q=[el];"
                + "for(var i=0;i<q.length;i++){var n=q[i];if(n.getAttribute){var a=n.getAttribute('alt');if(a){parts.push(a);}"
                + "var ar=n.getAttribute('aria-label');if(ar){parts.push(ar);}}"
                + "var cs=n.children||[];for(var k=0;k<cs.length;k++){q.push(cs[k]);}}" // HTMLCollection: sem forEach
                + "return parts.join('\\n').substring(0,4000);}"
                + "function dentro(parent,el){if(parent.contains(el)){return true;}var n=el;"
                + "while(n){var root=n.getRootNode?n.getRootNode():null;var host=root&&root.host;"
                + "if(!host){return false;}if(parent.contains(host)){return true;}n=host;}return false;}"
                + "function col(els,pe){var it=[];for(var i=0;i<els.length;i++){var el=els[i];var cands=[];"
                + "if(pe&&!dentro(pe,el)){continue;}"
                + "if(AS){var ds=el.querySelectorAll(AS);for(var j=0;j<ds.length&&cands.length<LIM;j++){cands.push(val(ds[j]));}}"
                + "else{cands.push(val(el));}"
                + "if(it.length>=MAX+1){break;}"
                + "if(TXT){it.push({v:cands,t:itemTxt(el)});}else{it.push({v:cands});}}return it;}"
                + "try{"
                + "if(P){var ps=rjSel(document,P);"
                + "if(ps.length===0){return JSON.stringify({p:0,it:[]});}"
                + "if(ps.length>1){return JSON.stringify({erro:'parentSelector ambiguo ('+ps.length+')'});}"
                + "return JSON.stringify({p:1,it:col(rjSel(ps[0],S),ps[0])});}"
                + "return JSON.stringify({p:-1,it:col(rjSel(document,S),null)});"
                + "}catch(e){return JSON.stringify({erro:String(e&&e.message||e)});}"
                + "})()";
    }

    /** Plano de resolucao de um seletor (xpath, id ou css com >>>) para o JS. */
    private static ObjectNode plano(String selector) {
        ObjectNode o = Json.obj();
        if (selector.startsWith("xpath:")) {
            o.put("t", "xpath");
            o.put("v", selector.substring(6));
        } else if (selector.startsWith("id:")) {
            o.put("t", "id");
            o.put("v", selector.substring(3));
        } else {
            o.put("t", "css");
            List<String> parts = ShadowDom.parts(selector);
            ArrayNode arr = o.putArray("p");
            if (parts == null) {
                arr.add(selector.startsWith("css:") ? selector.substring(4) : selector);
            } else {
                parts.forEach(arr::add);
            }
        }
        return o;
    }

    private static String jsStr(String s) {
        return Json.write(TextNode.valueOf(s));
    }

    /** Converte o retorno do script (string JSON) numa Leitura. */
    static Leitura parse(Object retorno) {
        String raw = retorno == null ? "" : String.valueOf(retorno);
        if (raw.isBlank()) return new Leitura(0, List.of(), "resposta vazia do navegador");
        JsonNode r = Json.parse(raw);
        if (r.has("erro")) return new Leitura(0, List.of(), r.get("erro").asText());
        int p = r.path("p").asInt(-1);
        List<Item> itens = new ArrayList<>();
        for (JsonNode it : r.path("it")) {
            List<String> v = new ArrayList<>();
            JsonNode vn = it.path("v");
            if (vn.isArray()) {
                for (JsonNode c : vn) v.add(c.isNull() ? null : c.asText());
            } else if (vn.isValueNode() && !vn.isNull()) {
                v.add(vn.asText());
            }
            String texto = it.hasNonNull("t") ? it.get("t").asText() : null;
            itens.add(new Item(v, texto));
        }
        return new Leitura(p, itens, null);
    }

    /** Le uma varredura; erro do navegador/JS vira Leitura com erro (nunca estoura aqui). */
    private static Leitura ler(Function<String, Object> exec, String js) {
        try {
            return parse(exec.apply(js));
        } catch (RuntimeException e) {
            return new Leitura(0, List.of(), String.valueOf(e.getMessage()));
        }
    }

    /** id do valor cru: idRegex grupo 1 (se existir) ou match inteiro; sem match = null (ignorado). */
    static String idDe(String raw, Pattern re) {
        if (re == null) return raw;
        Matcher m = re.matcher(raw);
        if (!m.find()) return null;
        if (m.groupCount() >= 1 && m.group(1) != null) return m.group(1);
        return m.group();
    }

    /** id do item: 1o candidato que serve. idRegex: 1o que casa; @text: 1o nao vazio; senao: 1o nao vazio <= 512 chars. */
    static String idDoItem(Spec s, Item item, Pattern re) {
        boolean texto = "@text".equals(s.attr());
        for (String raw : item.v()) {
            if (raw == null) continue;
            if (re != null) {
                String id = idDe(raw, re);
                if (id != null) return id;
            } else if (texto) {
                if (!raw.isEmpty()) return raw;
            } else if (!raw.isEmpty() && raw.length() <= 512) {
                return raw;
            }
        }
        return null;
    }

    static LinkedHashSet<String> idsDe(List<Item> itens, Spec s, Pattern re) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Item item : itens) {
            String id = idDoItem(s, item, re);
            if (id != null) out.add(id);
        }
        return out;
    }

    private static Pattern regex(String idRegex) {
        if (idRegex == null || idRegex.isBlank()) return null;
        return Pattern.compile(idRegex);
    }

    /**
     * Estabiliza o conjunto de ids: le a cada CAPTURE_POLL_MS ate waitMs. Conjunto NAO VAZIO so e
     * aceito quando as leituras validas desde a primeira com esse mesmo conjunto cobrem stableMs
     * (conjunto mudou = recomeca a contagem). VAZIO: regra antiga, so vale depois do waitMs inteiro
     * com as duas ultimas leituras validas vazias (lista que ainda nao carregou nao vira vazio em 1 s).
     */
    static Captura capturar(Function<String, Object> exec, Relogio r, Spec s, long waitMs) throws InterruptedException {
        String js = readScript(s, false);
        Pattern re = regex(s.idRegex());
        long inicio = r.agora();
        long stableMs = s.stableMs();
        LinkedHashSet<String> prev = null;
        long primeiraIgual = inicio;
        int validas = 0;
        while (true) {
            long agora = r.agora();
            Leitura l = ler(exec, js);
            if (l.erro() != null) throw new IllegalStateException("capture_ids falhou: " + l.erro());
            if (l.parents() > 1) {
                throw new IllegalStateException("capture_ids falhou: parentSelector ambiguo (" + l.parents() + ")");
            }
            if (l.parents() == 0) {
                prev = null;
                validas = 0;
                if (agora - inicio >= waitMs) {
                    throw new IllegalStateException("capture_ids falhou: parentSelector nao apareceu em " + waitMs + " ms");
                }
                r.dormir(CAPTURE_POLL_MS);
                continue;
            }
            if (l.itens().size() > MAX_IDS) {
                throw new IllegalStateException("capture_ids falhou: lista com mais de " + MAX_IDS
                        + " itens (truncar faria item antigo parecer novo)");
            }
            LinkedHashSet<String> atual = idsDe(l.itens(), s, re);
            validas++;
            if (prev == null || !prev.equals(atual)) {
                primeiraIgual = agora;
            }
            if (atual.size() < s.minIds()) {
                // Continua lendo ate o prazo; um conjunto abaixo do minimo ainda pode carregar.
            } else if (atual.isEmpty()) {
                if (prev != null && prev.isEmpty() && agora - inicio >= waitMs) {
                    return new Captura(s, atual, agora - inicio, validas);
                }
            } else if (agora - primeiraIgual >= stableMs) {
                return new Captura(s, atual, agora - inicio, validas);
            }
            int tamanhoAnterior = prev == null ? 0 : prev.size();
            prev = atual;
            if (agora - inicio >= waitMs) {
                if (atual.size() < s.minIds()) {
                    throw new IllegalStateException("capture_ids falhou: lista com " + atual.size()
                            + " ids (minIds " + s.minIds() + "): a lista nao carregou");
                }
                throw new IllegalStateException("capture_ids falhou: lista nao estabilizou em " + waitMs
                        + " ms (anterior " + tamanhoAnterior + " vs atual " + atual.size() + " ids)");
            }
            r.dormir(CAPTURE_POLL_MS);
        }
    }

    /** Espera um item cujo id nao esta em antes; devolve o primeiro na ordem do DOM. */
    static Novo verificar(Function<String, Object> exec, Relogio r, Spec s, Set<String> antes,
                          String expect, long timeoutMs) throws InterruptedException {
        return verificar(exec, r, s, antes, expect, timeoutMs, id -> true);
    }

    /**
     * Espera um item novo com correlacao externa (ex.: trace de publicacao): o candidato
     * so entra em novos quando id novo + texto expect + predicate true. Predicate falso
     * apenas pula o candidato; excecao de estado invalido do predicate aborta fail-fast.
     */
    static Novo verificar(Function<String, Object> exec, Relogio r, Spec s, Set<String> antes,
                          String expect, long timeoutMs, Predicate<String> correlacao) throws InterruptedException {
        boolean comTexto = expect != null && !expect.isEmpty();
        String js = readScript(s, comTexto);
        Pattern re = regex(s.idRegex());
        long inicio = r.agora();
        int vistos = 0;
        int conhecidos = 0;
        boolean candidatoSemAncora = false;
        while (true) {
            Leitura l = ler(exec, js);
            if (l.erro() != null) {
                if (r.agora() - inicio >= timeoutMs) throw new IllegalStateException("verify_new_id falhou: " + l.erro());
                r.dormir(VERIFY_POLL_MS);
                continue;
            }
            if (l.parents() == 0) {
                if (r.agora() - inicio >= timeoutMs) throw new IllegalStateException(timeoutMsg(s, expect, timeoutMs, vistos, conhecidos, candidatoSemAncora));
                r.dormir(VERIFY_POLL_MS);
                continue;
            }
            if (l.parents() > 1) {
                throw new IllegalStateException("verify_new_id falhou: parentSelector ambiguo (" + l.parents() + ")");
            }
            vistos = l.itens().size();
            int ancoras = 0;
            List<String> novos = new ArrayList<>();
            for (Item item : l.itens()) {
                String id = idDoItem(s, item, re);
                if (id == null) continue;
                if (antes.contains(id)) {
                    ancoras++;
                    continue;
                }
                if (comTexto) {
                    String texto = item.texto() != null ? item.texto() : "";
                    if (!texto.contains(expect)) continue;
                }
                if (correlacao != null && !correlacao.test(id)) continue;
                if (!novos.contains(id)) novos.add(id);
            }
            conhecidos = ancoras;
            if (!novos.isEmpty()) {
                if (antes.isEmpty() || ancoras > 0) return new Novo(novos.get(0), novos.size() - 1, ancoras);
                candidatoSemAncora = true;
            }
            if (r.agora() - inicio >= timeoutMs) throw new IllegalStateException(timeoutMsg(s, expect, timeoutMs, vistos, conhecidos, candidatoSemAncora));
            r.dormir(VERIFY_POLL_MS);
        }
    }

    private static String timeoutMsg(Spec s, String expect, long timeoutMs, int vistos, int conhecidos, boolean candidatoSemAncora) {
        boolean comTexto = expect != null && !expect.isEmpty();
        return "verify_new_id falhou: nenhum item novo"
                + (comTexto ? " com '" + corta(expect, 60) + "'" : "")
                + " em '" + corta(s.selector(), 120) + "' apos " + timeoutMs
                + " ms (itens vistos " + vistos + ", ja conhecidos " + conhecidos
                + (candidatoSemAncora && conhecidos == 0
                    ? "; sem ancora: a lista nao e a mesma da captura" : "") + ")";
    }

    private static String corta(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
