package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Clique dos passos {@code click} e {@code type} e leitura de estado de um elemento
 * ({@code verify_text} com selector, {@code verify_attr}).
 *
 * <p>Motivo (lotes de 22/10/2026): o {@code click()} da javadriveless move o mouse ate o
 * meio do elemento SEM rolar. Abaixo da dobra (Publicar do TikTok, titulo do Pinterest) ou
 * dentro do scroll do dialogo do Studio, o clique caia fora do alvo e o passo dizia
 * "clicou". O seletor de pasta do Pinterest so abre com mouse real.
 *
 * <p>Modo {@code mouse} (padrao): rola com a RODA do mouse ({@code Input.dispatchMouseEvent}
 * mouseWheel) sobre o conteiner que rola ate o centro do elemento entrar na area visivel
 * (atravessa shadow DOM); so se a roda nao resolver cai em {@code scrollIntoView}. Depois
 * mede o centro de novo, faz hit test (elementFromPoint descendo nos shadow roots abertos),
 * leva o mouse ate la em trilha suave, repete a medicao/hit test (hover pode cobrir o alvo)
 * e so entao manda pressed/released. Centro coberto por outro
 * elemento nao impede o clique: vira aviso no passo; quem prova o efeito e o verify seguinte.
 * Motivo da roda e da trilha: o TikTok coleta telemetria de interacao (webmssdk) e pune
 * escrita "sem gesto" (mapeamento/tiktok.md); scroll programatico e mouse teletransportado
 * sao sinais faceis.
 *
 * <p>Modo {@code dom}: {@code scrollIntoView} + {@code el.click()} (evento sintetico,
 * isTrusted=false). So para componente que comprovadamente ignora o mouse; o BatchStore o
 * recusa nas redes de {@link #DOM_BLOCKED_NETWORKS}.
 */
final class RealClick {

    static final String MODE_MOUSE = "mouse";
    static final String MODE_DOM = "dom";
    static final Set<String> MODES = Set.of(MODE_MOUSE, MODE_DOM);
    /** Redes com telemetria de gesto: clique sintetico proibido. */
    static final Set<String> DOM_BLOCKED_NETWORKS = Set.of("tiktok");
    /** Tempo para o layout assentar depois de rolar. */
    static final long SETTLE_MS = 350;
    static final int MAX_WHEEL_TICKS = 40;
    static final double WHEEL_STEP = 120;
    private static final long CDP_TIMEOUT_S = 5;
    /** Ultima posicao do mouse por aba, para a trilha comecar de onde parou. */
    private static final Map<Target, double[]> LAST = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Resultado do clique: modo usado, como rolou, ponto (mouse) e aviso de alvo coberto. */
    record Result(String mode, String scroll, double x, double y, boolean hit, String cover) {
        String info() {
            if (MODE_DOM.equals(mode)) return "clicou (dom: scrollIntoView + el.click())";
            String base = String.format(java.util.Locale.ROOT, "clicou (mouse em %.0f,%.0f; rolagem %s)", x, y, scroll);
            return hit ? base : base + "; AVISO: centro coberto por " + cover;
        }
    }

    private RealClick() {
    }

    /** Falha garantidamente ANTES de qualquer efeito (nada apertado nem digitado): o lote sabe que nada foi publicado. */
    static final class NaoAgiu extends IllegalStateException {
        NaoAgiu(String msg) {
            super(msg);
        }

        NaoAgiu(String msg, Throwable causa) {
            super(msg, causa);
        }
    }

    static <T> T antesDeAgir(String contexto, Callable<T> c) throws NaoAgiu {
        try {
            return c.call();
        } catch (NaoAgiu e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NaoAgiu(contexto + " interrompido antes de apertar", e);
        } catch (Exception e) {
            throw new NaoAgiu(contexto + " falhou antes de apertar: " + e.getMessage(), e);
        }
    }

    /** Modo do passo: "dom" so se pedido; ausente/vazio = mouse. Valor fora da lista e erro (BatchStore valida antes). */
    static String mode(JsonNode a) {
        String m = a == null ? "" : a.path("clickMode").asText("").trim();
        if (m.isEmpty()) return MODE_MOUSE;
        if (!MODES.contains(m)) throw new IllegalArgumentException("clickMode invalido '" + m + "' (use mouse|dom)");
        return m;
    }

    /**
     * Funcoes JS chamadas com this = elemento (Runtime.callFunctionOn). Puras, testadas no
     * node com DOM falso. Coordenadas devolvidas sao do viewport de topo (soma o deslocamento
     * de iframes da mesma origem).
     */
    static final String LIB = ShadowDom.LIB
            + "function rjUp(n){return n.parentNode||n.host||null;}"
            + "function rjScrollIntoView(el){try{el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}"
            + "catch(e){el.scrollIntoView({block:'center',inline:'center'});}}"
            + "function rjDesc(n){if(!n){return '(nada)';}var s=String(n.tagName||n.nodeName||'?').toLowerCase();"
            + "if(n.id){s+='#'+n.id;}var c=typeof n.className==='string'?n.className.trim().split(/\\s+/)[0]:'';"
            + "if(c){s+='.'+c;}var t=n.getAttribute?n.getAttribute('data-test-id')||n.getAttribute('data-e2e'):null;"
            + "if(t){s+='['+t+']';}return s;}"
            + "function rjInside(n,el){while(n){if(n===el){return true;}n=rjUp(n);}return false;}"
            + "function rjHit(doc,x,y){var h=doc.elementFromPoint(x,y);while(h&&h.shadowRoot){"
            + "var h2=h.shadowRoot.elementFromPoint(x,y);if(!h2||h2===h){break;}h=h2;}return h;}"
            // controle interativo mais proximo (subindo pelo composed tree)
            + "var RJ_CTL={BUTTON:1,A:1,INPUT:1,SELECT:1,TEXTAREA:1};"
            + "var RJ_ROLE={button:1,link:1,menuitem:1,option:1,tab:1,checkbox:1,radio:1,'switch':1,textbox:1,combobox:1};"
            + "function rjCtl(n){while(n){if(n.nodeType===1){if(RJ_CTL[String(n.tagName||'').toUpperCase()]){return n;}"
            + "var ro=n.getAttribute?n.getAttribute('role'):null;if(ro&&RJ_ROLE[ro]){return n;}"
            + "if(n.getAttribute&&n.getAttribute('contenteditable')==='true'){return n;}}n=rjUp(n);}return null;}"
            // o ponto cai noutro controle que nao e o alvo, nem o contem, nem esta dentro dele
            + "function rjConflict(h,el){var c=rjCtl(h);return c&&!rjInside(c,el)&&!rjInside(el,c)?c:null;}"
            + "function rjOffset(el){var w=el.ownerDocument.defaultView;var ox=0,oy=0;"
            + "try{while(w&&w!==w.top){var fe=w.frameElement;if(!fe){return null;}var fr=fe.getBoundingClientRect();"
            + "ox+=fr.left+(fe.clientLeft||0);oy+=fr.top+(fe.clientTop||0);w=w.parent;}}catch(e){return null;}return [ox,oy];}"
            + "function rjScroller(el){var w=el.ownerDocument.defaultView;var n=rjUp(el);while(n){if(n.nodeType===1){"
            + "var cs=w.getComputedStyle(n);if(/(auto|scroll|overlay)/.test(cs.overflowY)&&n.scrollHeight>n.clientHeight+1){return n;}}"
            + "n=rjUp(n);}return null;}"
            // area visivel (viewport recortado pelo conteiner que rola) e quanto falta rolar
            + "function rjScrollInfo(el){var r=el.getBoundingClientRect();var w=el.ownerDocument.defaultView;"
            + "var a={l:0,t:0,r:w.innerWidth,b:w.innerHeight};var sc=rjScroller(el);if(sc){var c=sc.getBoundingClientRect();"
            + "a={l:Math.max(0,c.left),t:Math.max(0,c.top),r:Math.min(w.innerWidth,c.right),b:Math.min(w.innerHeight,c.bottom)};"
            // conteiner fora da janela: rola o documento primeiro (roda no meio da janela)
            + "if(a.r-a.l<20||a.b-a.t<20){a={l:0,t:0,r:w.innerWidth,b:w.innerHeight};sc=null;}}"
            + "var cx=r.left+r.width/2,cy=r.top+r.height/2;var m=Math.min(40,(a.b-a.t)/4);"
            + "var dentro=cx>=a.l&&cx<=a.r&&cy>=a.t+m&&cy<=a.b-m;var off=rjOffset(el);"
            + "return {dentro:dentro,frame:off===null,wx:(a.l+a.r)/2+(off?off[0]:0),wy:(a.t+a.b)/2+(off?off[1]:0),"
            + "dy:cy-(a.t+a.b)/2,conteiner:sc?rjDesc(sc):'documento'};}"
            // centro, limites e hit test no documento do elemento E em cada documento ancestral (iframes)
            + "function rjMeasure(el){var r=el.getBoundingClientRect();if(!(r.width>0&&r.height>0)){"
            + "return {erro:'elemento sem caixa visivel ('+r.width+'x'+r.height+')'};}"
            + "var x=r.left+r.width/2,y=r.top+r.height/2;var doc=el.ownerDocument;var w=doc.defaultView;"
            + "if(x<0||y<0||x>w.innerWidth||y>w.innerHeight){return {erro:'centro fora da janela depois de rolar ('"
            + "+Math.round(x)+','+Math.round(y)+')'};}"
            + "var h=rjHit(doc,x,y);var ok=rjInside(h,el);var alvo=rjDesc(h);var ctl=rjConflict(h,el);"
            + "try{while(w&&w!==w.top){var fe=w.frameElement;if(!fe){return {erro:'elemento em iframe de outra origem'};}"
            + "var fr=fe.getBoundingClientRect();x+=fr.left+(fe.clientLeft||0);y+=fr.top+(fe.clientTop||0);w=w.parent;"
            + "if(x<0||y<0||x>w.innerWidth||y>w.innerHeight){return {erro:'centro fora da janela (iframe)'};}"
            + "var ph=rjHit(w.document,x,y);if(!rjInside(ph,fe)){ok=false;alvo=rjDesc(ph);ctl=ctl||rjCtl(ph);}}}"
            + "catch(e){return {erro:'elemento em iframe de outra origem'};}"
            + "return {x:x,y:y,w:r.width,h:r.height,acertou:ok,alvo:alvo,conflito:ctl?rjDesc(ctl):null};}"
            // editor efetivo: campo de texto ou contenteditable (ancestral comum, ex. body, nao conta)
            + "function rjEditor(n){if(!n||n.nodeType!==1){return false;}var t=String(n.tagName||'').toUpperCase();"
            + "if(t==='INPUT'||t==='TEXTAREA'){return true;}if(n.isContentEditable){return true;}"
            + "var cp=n.contentEditable;if(cp!=null&&String(cp).toLowerCase()!=='false'&&String(cp).toLowerCase()!=='inherit'){return true;}"
            + "var ce=n.getAttribute?n.getAttribute('contenteditable'):null;"
            + "if(ce!=null&&String(ce).toLowerCase()!=='false'){return true;}"
            + "var ro=n.getAttribute?n.getAttribute('role'):null;return ro==='textbox';}"
            // foco (descendo nos shadow roots) no elemento/descendente, ou no editor ancestral efetivo
            + "function rjFocused(el){var a=el.ownerDocument.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement){"
            + "a=a.shadowRoot.activeElement;}return !!a&&(rjInside(a,el)||(rjInside(el,a)&&rjEditor(a)));}"
            // so texto renderizado: elemento/filho sem caixa (display:none, fora do layout) nao conta
            + "function rjVis(n){return !!(n.getClientRects&&n.getClientRects().length);}"
            + "function rjElText(el){if(!rjVis(el)){return '';}var out=[el.innerText||''];var rs=rjRoots(el,[]).slice(1);"
            + "if(el.shadowRoot){rjRoots(el.shadowRoot,rs);}rs.forEach(function(r){var cs=r.children||[];"
            + "for(var i=0;i<cs.length;i++){if(!RJ_SKIP[String(cs[i].tagName||'').toUpperCase()]&&rjVis(cs[i])){"
            + "out.push(cs[i].innerText||'');}}});"
            + "return out.filter(function(s){return s;}).join('\\n');}";

    /** this = elemento: JSON (string) do rjScrollInfo. */
    static String scrollInfoFn() {
        return "function(){" + LIB + "return JSON.stringify(rjScrollInfo(this));}";
    }

    /** this = elemento: scrollIntoView (fallback do mouse; e o modo dom, que ja clica). */
    static String scrollIntoViewFn(boolean clickDom) {
        return "function(){" + LIB + "rjScrollIntoView(this);" + (clickDom ? "this.click();" : "") + "return 'ok';}";
    }

    /** this = elemento: JSON (string) com x, y, w, h, acertou, alvo ou erro. */
    static String measureFn() {
        return "function(){" + LIB + "return JSON.stringify(rjMeasure(this));}";
    }

    /** this = elemento: "1" se o foco (com shadow DOM) esta no elemento, num descendente ou no editor ancestral efetivo. */
    static String focusFn() {
        return "function(){" + LIB + "return rjFocused(this)?'1':'0';}";
    }

    /** Foco esta no elemento, num descendente ou no editor ancestral efetivo (contenteditable/input/textarea)? */
    static boolean focused(WebElement el) throws Exception {
        return "1".equals(call(el.getTarget(), el.getObjId().get(CDP_TIMEOUT_S, TimeUnit.SECONDS), focusFn()));
    }

    /** this = elemento: texto visivel do elemento, inclusive dos shadow roots abertos dentro dele. */
    static String textFn() {
        return "function(){" + LIB + "return rjElText(this);}";
    }

    /** this = elemento: JSON {v: valor do atributo ou null}. */
    static String attrFn(String attr) {
        return "function(){var v=this.getAttribute(" + Json.write(TextNode.valueOf(attr)) + ");"
                + "return JSON.stringify({v:v});}";
    }

    /**
     * Clica conforme o modo. Falha ANTES de apertar se o elemento nao tiver caixa visivel, estiver em
     * iframe de outra origem ou se o centro cair em outro controle interativo (botao, link, campo, role
     * de controle) que nao e o alvo nem o contem: a acao aprovada nunca vai para outro controle. Overlay
     * nao interativo por cima so vira aviso (coberto).
     */
    static Result click(WebElement el, String mode, Random rnd) throws Exception {
        Target t = antesDeAgir("click", el::getTarget);
        String obj = antesDeAgir("click", () -> el.getObjId().get(CDP_TIMEOUT_S, TimeUnit.SECONDS));
        if (MODE_DOM.equals(mode)) {
            call(t, obj, scrollIntoViewFn(true));
            return new Result(MODE_DOM, "js", 0, 0, true, "");
        }
        record Ready(String scroll, double x, double y, boolean hit, String cover) {}
        Ready ready = antesDeAgir("click (mouse)", () -> {
            String scroll = wheelIntoView(t, obj, rnd);
            Thread.sleep(SETTLE_MS);
            JsonNode m = Json.parse(call(t, obj, measureFn()));
            if (m.hasNonNull("erro")) throw new NaoAgiu("click (mouse): " + m.get("erro").asText());
            if (m.hasNonNull("conflito")) {
                throw new NaoAgiu("click (mouse) abortado antes de apertar: o centro do alvo cai em outro controle ("
                        + m.get("conflito").asText() + ", sob " + m.path("alvo").asText("?") + "); feche o que esta por cima");
            }
            moveTo(t, m.path("x").asDouble(), m.path("y").asDouble(), rnd);
            // hover durante a trilha pode abrir menu que cobre o alvo: mede e valida de novo antes de apertar
            m = Json.parse(call(t, obj, measureFn()));
            if (m.hasNonNull("erro")) throw new NaoAgiu("click (mouse): " + m.get("erro").asText());
            if (m.hasNonNull("conflito")) {
                throw new NaoAgiu("click (mouse) abortado antes de apertar: o centro do alvo cai em outro controle ("
                        + m.get("conflito").asText() + ", sob " + m.path("alvo").asText("?") + "); feche o que esta por cima");
            }
            double x = m.path("x").asDouble();
            double y = m.path("y").asDouble();
            LAST.put(t, new double[]{x, y});
            return new Ready(scroll, x, y, m.path("acertou").asBoolean(false), m.path("alvo").asText("?"));
        });
        double x = ready.x();
        double y = ready.y();
        dispatch(t, "mousePressed", x, y, "left", 1, 1);
        Thread.sleep(50 + rnd.nextInt(90));
        dispatch(t, "mouseReleased", x, y, "left", 0, 1);
        return new Result(MODE_MOUSE, ready.scroll(), x, y, ready.hit(), ready.cover());
    }

    /**
     * Rola com a roda sobre a area visivel do conteiner ate o centro do elemento entrar nela.
     * Devolve "nenhuma", "roda (N)" ou "scrollIntoView" (roda sem progresso, iframe ou teto de ticks).
     */
    private static String wheelIntoView(Target t, String obj, Random rnd) throws Exception {
        int ticks = 0;
        int semProgresso = 0;
        double ultimoDy = Double.NaN;
        while (ticks < MAX_WHEEL_TICKS) {
            JsonNode s = Json.parse(call(t, obj, scrollInfoFn()));
            if (s.path("dentro").asBoolean(false)) return ticks == 0 ? "nenhuma" : "roda (" + ticks + ")";
            if (s.path("frame").asBoolean(false)) break;
            double dy = s.path("dy").asDouble();
            if (!Double.isNaN(ultimoDy) && Math.abs(dy - ultimoDy) < 1) {
                if (++semProgresso >= 2) break;
            } else {
                semProgresso = 0;
            }
            ultimoDy = dy;
            double wx = s.path("wx").asDouble();
            double wy = s.path("wy").asDouble();
            if (ticks == 0) moveTo(t, wx, wy, rnd);
            dispatchWheel(t, wx, wy, wheelDelta(dy, rnd));
            ticks++;
            Thread.sleep(70 + rnd.nextInt(110));
        }
        call(t, obj, scrollIntoViewFn(false));
        return "scrollIntoView";
    }

    /** Passo da roda na direcao do alvo: ate ~WHEEL_STEP com jitter, menor perto do alvo. */
    static double wheelDelta(double dy, Random rnd) {
        double passo = WHEEL_STEP * (0.8 + rnd.nextDouble() * 0.4);
        return Math.copySign(Math.min(Math.abs(dy), passo), dy);
    }

    /** Texto visivel do elemento (com shadow DOM aberto dentro dele). */
    static String text(WebElement el) throws Exception {
        return call(el.getTarget(), el.getObjId().get(CDP_TIMEOUT_S, TimeUnit.SECONDS), textFn());
    }

    /** Valor do atributo, ou null se ausente. */
    static String attr(WebElement el, String attr) throws Exception {
        JsonNode r = Json.parse(call(el.getTarget(), el.getObjId().get(CDP_TIMEOUT_S, TimeUnit.SECONDS), attrFn(attr)));
        JsonNode v = r.get("v");
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Runtime.callFunctionOn com returnByValue; excecao JS vira IllegalStateException. */
    private static String call(Target t, String objectId, String fn) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("objectId", objectId);
        p.put("functionDeclaration", fn);
        p.put("returnByValue", true);
        JsonNode r = t.executeCdpCmd("Runtime.callFunctionOn", p, (float) CDP_TIMEOUT_S)
                .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS);
        if (r != null && r.hasNonNull("exceptionDetails")) {
            JsonNode ex = r.get("exceptionDetails");
            throw new IllegalStateException("erro JS no elemento: "
                    + ex.path("exception").path("description").asText(ex.path("text").asText("?")));
        }
        JsonNode v = r == null ? null : r.path("result").get("value");
        return v == null || v.isNull() ? "" : v.asText();
    }

    /**
     * Trilha do mouse de (x0,y0) ate (x1,y1): curva quadratica com ponto de controle deslocado
     * e easing (acelera e freia); 6 a 25 pontos conforme a distancia; o ultimo e o alvo exato.
     */
    static List<double[]> path(double x0, double y0, double x1, double y1, Random rnd) {
        double dist = Math.hypot(x1 - x0, y1 - y0);
        int n = (int) Math.max(6, Math.min(25, dist / 25));
        double desvio = Math.min(80, dist * 0.2) * (rnd.nextDouble() - 0.5) * 2;
        double cx = (x0 + x1) / 2 - (y1 - y0) / Math.max(dist, 1) * desvio;
        double cy = (y0 + y1) / 2 + (x1 - x0) / Math.max(dist, 1) * desvio;
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            double u = (double) i / n;
            // ease-in-out quadratico sem desvio: 2u^2 ate a metade, 1 - 2(1-u)^2 depois
            double s = Math.max(0, 2 * u - 1);
            double e = 2 * u * u - s * s;
            double px = (1 - e) * (1 - e) * x0 + 2 * (1 - e) * e * cx + e * e * x1;
            double py = (1 - e) * (1 - e) * y0 + 2 * (1 - e) * e * cy + e * e * y1;
            out.add(new double[]{Math.max(0, px), Math.max(0, py)});
        }
        out.set(out.size() - 1, new double[]{x1, y1});
        return out;
    }

    /** Leva o mouse ate (x,y) pela trilha, a partir da ultima posicao conhecida nesta aba. */
    private static void moveTo(Target t, double x, double y, Random rnd) throws Exception {
        double[] de = LAST.get(t);
        if (de == null) de = new double[]{x - 120 - rnd.nextDouble() * 200, y + 60 + rnd.nextDouble() * 120};
        for (double[] pt : path(de[0], de[1], x, y, rnd)) {
            dispatch(t, "mouseMoved", pt[0], pt[1], "none", 0, 0);
            pausa.dormir(8 + rnd.nextInt(18));
        }
        LAST.put(t, new double[]{x, y});
    }

    /** Pausa entre os pontos da trilha do mouse: injetavel em teste; em producao, Thread.sleep. */
    static TypeText.Pausa pausa = Thread::sleep;

    static Map<String, Object> mouseEvent(String type, double x, double y, String button, int buttons, int clickCount) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", type);
        p.put("x", x);
        p.put("y", y);
        p.put("button", button);
        p.put("buttons", buttons);
        p.put("clickCount", clickCount);
        p.put("pointerType", "mouse");
        return p;
    }

    private static void dispatch(Target t, String type, double x, double y, String button, int buttons, int clickCount) throws Exception {
        t.executeCdpCmd("Input.dispatchMouseEvent", mouseEvent(type, x, y, button, buttons, clickCount), (float) CDP_TIMEOUT_S)
                .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS);
    }

    private static void dispatchWheel(Target t, double x, double y, double deltaY) throws Exception {
        Map<String, Object> p = mouseEvent("mouseWheel", x, y, "none", 0, 0);
        p.put("deltaX", 0);
        p.put("deltaY", deltaY);
        t.executeCdpCmd("Input.dispatchMouseEvent", p, (float) CDP_TIMEOUT_S)
                .get(CDP_TIMEOUT_S + 2, TimeUnit.SECONDS);
    }
}
