package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia Chrome E: mutantes de {@link RealClick} que so podem ser observados com navegador
 * real (clique por mouse/DOM, foco, texto/atributo, trilha, roda e os caminhos de conflito e
 * erro). Um ChromeFalso + Store + BrowserPool por classe; cada teste navega para a pagina
 * que precisa e le o journal JS gravado na propria pagina (eventos reais, isTrusted,
 * coordenadas, timestamps) mais o {@link RealClick.Result} devolvido.
 */
class RealClickChromeTest {

    private static final String BASE = "https://bsky.app";

    /** Logger unico: capture de mousemove/mousedown/mouseup/click/wheel com isTrusted, x/y e ts. */
    private static final String LOG = """
            <script>
            window.__log = [];
            ['mousedown','mouseup','click','mousemove','wheel'].forEach(function(t){
              document.addEventListener(t, function(e){
                window.__log.push({t:t, x:e.clientX, y:e.clientY, trusted:e.isTrusted,
                  ts:performance.now(), alvo:(e.target&&e.target.id)||''});
              }, true);
            });
            </script>
            """;

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;
    static BrowserPool pool;
    static JavaDriverless d;

    @BeforeAll
    static void abrirFixture() throws Exception {
        store = new Store(tmp.resolve("home"));
        cf = ChromeFalso.abrir(tmp, store);
        publicarPaginas();
        pool = new BrowserPool(store);
        d = pool.get("bluesky");
    }

    @AfterAll
    static void fecharFixture() {
        if (pool != null) pool.closeAll();
        if (cf != null) cf.close();
    }

    private static void publicarPaginas() {
        cf.pagina("/focus", """
                <html><body>
                <input id="campo"><input id="outro">
                <script>document.getElementById('campo').focus();</script>
                </body></html>
                """);

        cf.pagina("/texto", """
                <html><body>
                <div id="texto">visivel<span style="display:none">oculto</span></div>
                <div id="host"></div>
                <div id="attrbox" data-k="v">a</div>
                <script>document.getElementById('host').attachShadow({mode:'open'})
                  .innerHTML='<span>dentro-shadow</span>';</script>
                </body></html>
                """);

        cf.pagina("/simples", """
                <html><body><div id="alvo">alvo</div>"""
                + LOG + """
                </body></html>
                """);

        cf.pagina("/alvo", """
                <html><head><style>
                #alvo{position:absolute;left:500px;top:120px;width:120px;height:40px}
                </style></head><body>
                <button id="alvo">Alvo</button>"""
                + LOG + """
                </body></html>
                """);

        cf.pagina("/dom", """
                <html><body>
                <button id="alvo" style="position:absolute;left:200px;top:200px;width:160px;height:50px">Alvo</button>"""
                + LOG + """
                </body></html>
                """);

        cf.pagina("/conflito-antes", """
                <html><body>
                <button id="alvo" style="position:absolute;left:300px;top:300px;width:120px;height:40px">Alvo</button>
                <button id="overlay" onmousemove="this.remove()"
                  style="position:fixed;left:0;top:0;width:100%;height:100%;opacity:0.01">cobre</button>
                </body></html>
                """);

        cf.pagina("/conflito-depois", """
                <html><body>
                <button id="alvo" onmouseover="if(!document.getElementById('overlay')){var o=document.createElement('button');
                  o.id='overlay';o.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;opacity:0.01';
                  document.body.appendChild(o);}"
                  style="position:absolute;left:300px;top:300px;width:120px;height:40px">Alvo</button>
                </body></html>
                """);

        cf.pagina("/erro-antes", """
                <html><body>
                <div id="alvo" style="position:absolute;left:300px;top:300px;width:0;height:0"></div>
                <script>
                document.addEventListener('mousemove', function once(){
                  var a=document.getElementById('alvo'); a.style.width='120px'; a.style.height='40px';
                  document.removeEventListener('mousemove', once);
                });
                </script>
                </body></html>
                """);

        cf.pagina("/erro-depois", """
                <html><body>
                <div id="alvo" style="position:absolute;left:300px;top:300px;width:120px;height:40px"></div>
                <script>
                document.addEventListener('mousemove', function once(){
                  var a=document.getElementById('alvo'); a.style.width='0px'; a.style.height='0px';
                  document.removeEventListener('mousemove', once);
                });
                </script>
                </body></html>
                """);

        cf.pagina("/roda-uma", """
                <html><body>
                <div id="alvo" style="position:absolute;left:200px;width:120px;height:30px"></div>"""
                + LOG + """
                <script>
                document.getElementById('alvo').style.top=(window.innerHeight-35)+'px';
                document.body.style.height='3000px';
                </script>
                </body></html>
                """);

        cf.pagina("/roda-sem-progresso", """
                <html><body>
                <div id="alvo" style="position:fixed;left:300px;top:3000px;width:120px;height:30px"></div>"""
                + LOG + """
                </body></html>
                """);

        cf.pagina("/roda-teto", """
                <html><body>
                <div id="alvo" style="position:absolute;left:200px;top:9000px;width:120px;height:30px"></div>"""
                + LOG + """
                <script>document.body.style.height='10000px';</script>
                </body></html>
                """);

        cf.pagina("/roda-um-pixel", """
                <html><body>
                <div id="alvo" style="position:absolute;left:200px;width:120px;height:30px"></div>"""
                + LOG + """
                <script>
                document.getElementById('alvo').style.top=(window.innerHeight-35)+'px';
                document.body.style.height='3000px';
                window.addEventListener('wheel', function(e){ e.preventDefault(); window.scrollBy(0,1); },
                  {passive:false});
                </script>
                </body></html>
                """);

        cf.pagina("/caminho", """
                <html><body>
                <button id="alvo" style="position:absolute;left:450px;top:380px;width:100px;height:40px">Alvo</button>"""
                + LOG + """
                </body></html>
                """);
    }

    private static void abrir(String caminho) throws Exception {
        d.get(BASE + caminho);
    }

    private static void limparLog() {
        d.executeScript("window.__log=[]");
    }

    /** Deixa a ultima posicao do mouse no centro da janela (onde a roda e disparada). */
    private static void mouseNoCentro(WebElement alvo) throws Exception {
        int largura = ((Number) d.executeScript("window.innerWidth")).intValue();
        int altura = ((Number) d.executeScript("window.innerHeight")).intValue();
        ultimoMouse().put(alvo.getTarget(), new double[]{largura / 2.0, altura / 2.0});
    }

    private static JsonNode eventos() {
        Object o = d.executeScript("JSON.stringify(window.__log||[])");
        JsonNode n = Json.parse(String.valueOf(o));
        return n != null && n.isArray() ? n : Json.M.createArrayNode();
    }

    private static int primeiro(JsonNode eventos, String tipo) {
        for (int i = 0; i < eventos.size(); i++) {
            if (tipo.equals(eventos.get(i).path("t").asText())) return i;
        }
        return -1;
    }

    private static int contar(JsonNode eventos, String tipo) {
        int n = 0;
        for (JsonNode e : eventos) {
            if (tipo.equals(e.path("t").asText())) n++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<Target, double[]> ultimoMouse() throws Exception {
        Field f = RealClick.class.getDeclaredField("LAST");
        f.setAccessible(true);
        return (Map<Target, double[]>) f.get(null);
    }

    private static String chamarCall(WebElement el, String fn) throws Exception {
        Method m = RealClick.class.getDeclaredMethod("call", Target.class, String.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, el.getTarget(),
                    el.getObjId().get(5, TimeUnit.SECONDS), fn);
        } catch (InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof Exception ex) throw ex;
            if (causa instanceof Error err) throw err;
            throw new RuntimeException(causa);
        }
    }

    /** Random determinista: nextDouble() fixo e nextInt por bound (trilha, press e sleep da roda). */
    private static final class RandomControlado extends Random {
        private final double valor;

        RandomControlado(double valor) {
            this.valor = valor;
        }

        @Override
        public double nextDouble() {
            return valor;
        }

        @Override
        public int nextInt(int bound) {
            return switch (bound) {
                case 18 -> 17;
                case 90 -> 89;
                case 110 -> 71;
                default -> 0;
            };
        }
    }

    // ---------- focused / text / attr / call ----------

    @Test
    @Timeout(60)
    void focusedVerdadeiroNoCampoEFalsoNoutro() throws Exception {
        abrir("/focus");
        WebElement campo = d.findElement(By.css("#campo"));
        WebElement outro = d.findElement(By.css("#outro"));

        assertTrue(RealClick.focused(campo), "foco real no campo");
        assertFalse(RealClick.focused(outro), "foco deve estar no campo, nao no outro");
    }

    @Test
    @Timeout(60)
    void textoIncluiShadowEAttrDistinguePresenteDeAusente() throws Exception {
        abrir("/texto");
        WebElement texto = d.findElement(By.css("#texto"));
        WebElement host = d.findElement(By.css("#host"));
        WebElement attrbox = d.findElement(By.css("#attrbox"));

        assertEquals("visivel", RealClick.text(texto));
        assertTrue(RealClick.text(host).contains("dentro-shadow"), RealClick.text(host));
        assertEquals("v", RealClick.attr(attrbox, "data-k"));
        assertNull(RealClick.attr(attrbox, "data-ausente"));
    }

    @Test
    @Timeout(60)
    void callDevolveValorTrataUndefinedENullEPropagaExcecaoJS() throws Exception {
        abrir("/simples");
        WebElement alvo = d.findElement(By.css("#alvo"));

        assertEquals("abc", chamarCall(alvo, "function(){return 'abc';}"));
        assertEquals("", chamarCall(alvo, "function(){}"));
        assertEquals("", chamarCall(alvo, "function(){return null;}"));
        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> chamarCall(alvo, "function(){throw new Error('boom');}"));
        assertTrue(erro.getMessage().contains("erro JS no elemento"), erro.getMessage());
    }

    // ---------- mouse / DOM ----------

    @Test
    @Timeout(60)
    void clickMouseDisparaEventosReaisComCoordenadasEPressHold() throws Exception {
        abrir("/alvo");
        WebElement alvo = d.findElement(By.css("#alvo"));
        Map<Target, double[]> ultimo = ultimoMouse();
        ultimo.clear();
        ultimo.put(alvo.getTarget(), new double[]{560, 140});
        limparLog();

        long inicio = System.nanoTime();
        RealClick.Result r = RealClick.click(alvo, RealClick.MODE_MOUSE, new RandomControlado(0.0));
        long duracao = (System.nanoTime() - inicio) / 1_000_000;

        assertNotNull(r);
        assertEquals("mouse", r.mode());
        assertEquals("nenhuma", r.scroll());
        assertEquals(560.0, r.x(), 0.6);
        assertEquals(140.0, r.y(), 0.6);
        assertTrue(r.hit(), r.info());
        assertEquals("button#alvo", r.cover(), "cover = descricao do elemento no centro (o proprio alvo)");
        assertTrue(duracao >= 400, "SETTLE_MS ausente? duracao=" + duracao);

        JsonNode ev = eventos();
        int mm = primeiro(ev, "mousemove");
        int md = primeiro(ev, "mousedown");
        int mu = primeiro(ev, "mouseup");
        int cl = primeiro(ev, "click");
        assertTrue(mm >= 0, "sem mouseMoved: " + ev);
        assertTrue(md > mm, "press antes de mover o mouse: " + ev);
        assertTrue(mu > md, "sem mouseup depois do pressed: " + ev);
        assertTrue(cl > mu, "sem click depois do released: " + ev);
        assertEquals(560.0, ev.get(mm).path("x").asDouble(), 0.6, "mousemove no alvo: " + ev);
        assertEquals(140.0, ev.get(mm).path("y").asDouble(), 0.6, "mousemove no alvo: " + ev);
        assertEquals("alvo", ev.get(md).path("alvo").asText(), ev.toString());
        for (JsonNode e : ev) {
            if (!"mousemove".equals(e.path("t").asText())) continue;
            assertTrue(e.path("trusted").asBoolean(false), "evento sintetico: " + e);
        }
        assertTrue(ev.get(md).path("trusted").asBoolean(false), ev.toString());
        assertTrue(ev.get(mu).path("trusted").asBoolean(false), ev.toString());
        assertTrue(ev.get(cl).path("trusted").asBoolean(false), ev.toString());
        double gapPress = ev.get(mu).path("ts").asDouble() - ev.get(md).path("ts").asDouble();
        assertTrue(gapPress >= 40, "press/release sem espera: " + gapPress);
    }

    @Test
    @Timeout(60)
    void clickModoDomClicaComEventoSintetico() throws Exception {
        abrir("/dom");
        WebElement alvo = d.findElement(By.css("#alvo"));
        limparLog();

        RealClick.Result r = RealClick.click(alvo, RealClick.MODE_DOM, new Random(1));

        assertNotNull(r);
        assertEquals("dom", r.mode());
        assertEquals("js", r.scroll());
        assertEquals(0.0, r.x(), 0.0);
        assertEquals(0.0, r.y(), 0.0);
        assertTrue(r.hit());
        assertEquals("", r.cover());
        assertEquals("clicou (dom: scrollIntoView + el.click())", r.info());
        JsonNode ev = eventos();
        int cl = primeiro(ev, "click");
        assertTrue(cl >= 0, "el.click() nao gerou click: " + ev);
        assertFalse(ev.get(cl).path("trusted").asBoolean(true), "modo dom deve ser sintetico: " + ev);
        assertEquals(-1, primeiro(ev, "mousedown"), "modo dom nao aperta o mouse: " + ev);
    }

    // ---------- conflito (outro controle por cima) ----------

    @Test
    @Timeout(60)
    void conflitoNaPrimeiraMedidaAbortaAntesDeMover() throws Exception {
        abrir("/conflito-antes");
        WebElement alvo = d.findElement(By.css("#alvo"));

        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(1)));
        assertTrue(e.getMessage().contains("outro controle"), e.getMessage());
        assertTrue(e.getMessage().contains("overlay"), e.getMessage());
    }

    @Test
    @Timeout(60)
    void conflitoQueSurgeNoHoverAbortaNaSegundaMedida() throws Exception {
        abrir("/conflito-depois");
        WebElement alvo = d.findElement(By.css("#alvo"));
        ultimoMouse().clear();
        limparLog();

        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(1)));
        assertTrue(e.getMessage().contains("outro controle"), e.getMessage());
        assertTrue(e.getMessage().contains("overlay"), e.getMessage());
    }

    // ---------- erro (elemento sem caixa) ----------

    @Test
    @Timeout(60)
    void erroNaPrimeiraMedidaAbortaAntesDeMover() throws Exception {
        abrir("/erro-antes");
        WebElement alvo = d.findElement(By.css("#alvo"));

        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(1)));
        assertTrue(e.getMessage().contains("elemento sem caixa visivel"), e.getMessage());
    }

    @Test
    @Timeout(60)
    void erroNaSegundaMedidaAbortaAntesDeApertar() throws Exception {
        abrir("/erro-depois");
        WebElement alvo = d.findElement(By.css("#alvo"));
        ultimoMouse().clear();
        limparLog();

        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(1)));
        assertTrue(e.getMessage().contains("elemento sem caixa visivel"), e.getMessage());
    }

    // ---------- roda: uma volta, sem progresso e teto ----------

    @Test
    @Timeout(60)
    void rodaUmaVezMoveMouseAntesEDevolveRodaDeUmTick() throws Exception {
        abrir("/roda-uma");
        WebElement alvo = d.findElement(By.css("#alvo"));
        limparLog();

        RealClick.Result r = RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(3));

        assertEquals("roda (1)", r.scroll(), r.info());
        JsonNode ev = eventos();
        assertEquals(1, contar(ev, "wheel"), "uma roda exata: " + ev);
        int w = primeiro(ev, "wheel");
        assertTrue(w > 0, "roda sem mover o mouse antes: " + ev);
        double wx = ev.get(w).path("x").asDouble();
        double wy = ev.get(w).path("y").asDouble();
        boolean moveuAntes = false;
        for (int i = 0; i < w; i++) {
            JsonNode e = ev.get(i);
            if ("mousemove".equals(e.path("t").asText())
                    && Math.abs(e.path("x").asDouble() - wx) < 1
                    && Math.abs(e.path("y").asDouble() - wy) < 1) {
                moveuAntes = true;
            }
        }
        assertTrue(moveuAntes, "moveTo(wx,wy) antes da primeira roda: " + ev);
    }

    @Test
    @Timeout(60)
    void rodaSemProgressoDesisteDepoisDeDuasVoltas() throws Exception {
        abrir("/roda-sem-progresso");
        WebElement alvo = d.findElement(By.css("#alvo"));
        limparLog();

        RealClick.NaoAgiu e = assertThrows(RealClick.NaoAgiu.class,
                () -> RealClick.click(alvo, RealClick.MODE_MOUSE, new Random(1)));
        assertTrue(e.getMessage().contains("centro fora da janela"), e.getMessage());
        JsonNode ev = eventos();
        assertEquals(2, contar(ev, "wheel"), "sem progresso deve desistir na terceira medicao: " + ev);
    }

    @Test
    @Timeout(60)
    void rodaAteOTetoDe40TicksCaiNoScrollIntoView() throws Exception {
        abrir("/roda-teto");
        WebElement alvo = d.findElement(By.css("#alvo"));
        mouseNoCentro(alvo);
        limparLog();

        long inicio = System.nanoTime();
        RealClick.Result r = RealClick.click(alvo, RealClick.MODE_MOUSE, new RandomControlado(0.0));
        long duracao = (System.nanoTime() - inicio) / 1_000_000;

        assertEquals("scrollIntoView", r.scroll(), r.info());
        assertEquals(40, contar(eventos(), "wheel"), "teto exato de MAX_WHEEL_TICKS: " + eventos());
        assertTrue(duracao >= 2500, "sleep entre ticks ausente? duracao=" + duracao);
    }

    @Test
    @Timeout(60)
    void rodaComUmPixelPorTickTrataDiferencaDeUmComoProgresso() throws Exception {
        abrir("/roda-um-pixel");
        WebElement alvo = d.findElement(By.css("#alvo"));
        limparLog();

        RealClick.Result r = RealClick.click(alvo, RealClick.MODE_MOUSE, new RandomControlado(0.0));

        assertEquals("roda (20)", r.scroll(), r.info());
        assertEquals(20, contar(eventos(), "wheel"), "1 px por tick nao e falta de progresso: " + eventos());
    }

    // ---------- trilha do mouse ----------

    @Test
    @Timeout(60)
    void trilhaComecaNoPontoInicialCalculadoEPerdeTempoEntrePontos() throws Exception {
        abrir("/caminho");
        WebElement alvo = d.findElement(By.css("#alvo"));
        ultimoMouse().clear();
        limparLog();

        long inicio = System.nanoTime();
        RealClick.click(alvo, RealClick.MODE_MOUSE, new RandomControlado(0.75));
        long duracao = (System.nanoTime() - inicio) / 1_000_000;

        JsonNode ev = eventos();
        int mm = primeiro(ev, "mousemove");
        assertTrue(mm >= 0, "sem mousemove: " + ev);
        // de = (500-120-0.75*200, 400+60+0.75*120) = (230,550); primeiro ponto da Bezier = (234.16,548.66)
        assertEquals(234.2, ev.get(mm).path("x").asDouble(), 1.5, "inicio da trilha: " + ev.get(mm));
        assertEquals(548.7, ev.get(mm).path("y").asDouble(), 1.5, "inicio da trilha: " + ev.get(mm));
        assertTrue(duracao >= 600, "sleep SETTLE/trilha ausente? duracao=" + duracao);
    }

    @Test
    @Timeout(60)
    void moveToDormeUmaVezPorPontoDaTrilhaEntre8E25ms() throws Exception {
        abrir("/alvo");
        WebElement alvo = d.findElement(By.css("#alvo"));
        ultimoMouse().clear();
        ultimoMouse().put(alvo.getTarget(), new double[]{560.0, 140.0});
        List<Long> pausas = Collections.synchronizedList(new ArrayList<>());
        TypeText.Pausa original = RealClick.pausa;
        RealClick.pausa = pausas::add;
        Random rnd = new Random(7);
        try {
            RealClick.Result r = RealClick.click(alvo, RealClick.MODE_MOUSE, rnd);
            assertEquals("nenhuma", r.scroll(), r.info());
        } finally {
            RealClick.pausa = original;
        }
        assertTrue(pausas.size() >= 6, "moveTo sem pausas (L345): " + pausas);
        for (long ms : pausas) {
            assertTrue(ms >= 8 && ms < 26, "pausa fora de [8,26): " + ms + " em " + pausas);
        }
        Random ref = new Random(7);
        ref.nextDouble();
        List<Long> esperadas = new ArrayList<>();
        for (int i = 0; i < pausas.size(); i++) esperadas.add(8L + ref.nextInt(18));
        assertEquals(esperadas, pausas, "pausas nao seguem 8+rnd.nextInt(18)");
    }
}
