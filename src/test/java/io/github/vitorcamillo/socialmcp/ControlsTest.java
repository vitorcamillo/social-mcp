package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrato do social_inspect: limites, registro da tool, script fixo sem
 * entrada arbitraria e sanitize que nunca expoe segredo nem valor.
 *
 * <p>A execucao real usa uma unica fixture local no Chrome headless, sem rede.
 */
class ControlsTest {

    @TempDir
    Path tmp;

    @Test
    void limites() {
        assertEquals(60, Controls.clampMaxControls(Json.obj()));
        assertEquals(60, Controls.clampMaxControls(null));
        assertEquals(60, Controls.clampMaxControls(Json.parse("{\"maxControls\": \"x\"}")));
        assertEquals(60, Controls.clampMaxControls(Json.parse("{\"maxControls\": 1.5}")));
        assertEquals(1, Controls.clampMaxControls(Json.parse("{\"maxControls\": 0}")));
        assertEquals(1, Controls.clampMaxControls(Json.parse("{\"maxControls\": -5}")));
        assertEquals(100, Controls.clampMaxControls(Json.parse("{\"maxControls\": 101}")));
        assertEquals(7, Controls.clampMaxControls(Json.parse("{\"maxControls\": 7}")));
    }

    @Test
    void toolRegistrada() {
        Store s = new Store(tmp.resolve("home" + System.nanoTime()));
        ArrayNode defs = new Tools(s).definitions();
        JsonNode inspect = null;
        for (JsonNode d : defs) {
            if ("social_inspect".equals(d.path("name").asText(""))) {
                inspect = d;
            }
        }
        assertNotNull(inspect, "social_inspect registrada em definitions()");
        assertTrue(inspect.path("description").asText("").toLowerCase().contains("leitura"));
        boolean temNetwork = false;
        for (JsonNode r : inspect.path("inputSchema").path("required")) {
            if ("network".equals(r.asText())) {
                temNetwork = true;
            }
        }
        assertTrue(temNetwork, "network obrigatorio");
        JsonNode max = inspect.path("inputSchema").path("properties").path("maxControls");
        assertEquals(1, max.path("minimum").asInt(-1));
        assertEquals(100, max.path("maximum").asInt(-1));
    }

    @Test
    void scriptFixoSemEntradaArbitraria() {
        String js = Controls.inspectScript(60);
        assertEquals(js, Controls.inspectScript(60), "deterministico");
        assertTrue(js.contains("MAX=60"));
        assertTrue(Controls.inspectScript(10).contains("MAX=10"));
        // atravessa shadow DOM aberto e filtra sensiveis
        assertTrue(js.contains("shadowRoot"));
        assertTrue(js.contains("password"));
        assertTrue(js.contains("hidden"));
        // somente leitura: nada que navegue, extraia sessao, simule input ou mute o DOM
        for (String proibido : new String[]{"document.cookie", "localStorage", "sessionStorage",
                "fetch(", "XMLHttpRequest", "dispatchEvent", ".click(", ".focus(", "location.href",
                "innerHTML", "outerHTML", "setAttribute", "appendChild", "requestSubmit"}) {
            assertFalse(js.contains(proibido), "script nao contem " + proibido);
        }
    }

    /** Simula o criar Pagina do Facebook: nome + categoria + botao passam, segredos caem. */
    private static JsonNode brutoFacebook() {
        return Json.parse("""
                {"controls": [
                  {"tag": "INPUT", "type": "text", "id": "page-name", "name": "page_name",
                   "label": "Nome da P\\u00e1gina", "xpath": "//input[1]", "css": "#page-name"},
                  {"tag": "INPUT", "type": "text", "id": "cat", "label": "Categoria",
                   "xpath": "//input[2]", "css": "#cat"},
                  {"tag": "BUTTON", "id": "criar", "text": "Criar P\\u00e1gina",
                   "xpath": "//button[1]", "css": "#criar"},
                  {"tag": "INPUT", "type": "password", "id": "pw", "label": "Senha",
                   "xpath": "//input[3]", "css": "#pw"},
                  {"tag": "INPUT", "type": "hidden", "id": "tok", "xpath": "//input[4]", "css": "#tok"},
                  {"tag": "INPUT", "type": "text", "id": "otp", "label": "C\\u00f3digo OTP",
                   "xpath": "//input[5]", "css": "#otp"},
                  {"tag": "INPUT", "type": "text", "name": "api_token", "label": "Chave",
                   "xpath": "//input[6]", "css": "#tok2"},
                  {"tag": "INPUT", "type": "text", "id": "pin-field", "label": "PIN",
                   "xpath": "//input[7]", "css": "#pin-field"},
                  {"tag": "TEXTAREA", "id": "bio", "label": "Bio",
                   "value": "segredo digitado", "xpath": "//textarea[1]", "css": "#bio"},
                  {"tag": "A", "role": "button", "id": "ajuda", "text": "Ajuda",
                   "href": "https://facebook.com/x?token=abc#frag",
                   "html": "<a>Ajuda</a>", "xpath": "//a[1]", "css": "#ajuda"}
                ], "count": 10, "truncated": false}""");
    }

    @Test
    void camposDoFacebookPassamESensiveisCaem() {
        var out = Controls.sanitizeResult("facebook", "https://www.facebook.com/pages/creation",
                brutoFacebook(), 60);
        assertEquals("facebook", out.path("network").asText(""));
        assertEquals(5, out.path("total").asInt(), out.toString());
        assertFalse(out.path("truncado").asBoolean(true));
        boolean nome = false;
        boolean categoria = false;
        boolean botao = false;
        for (JsonNode c : out.path("controles")) {
            String s = c.toString();
            assertFalse(c.has("value"), "nunca value: " + s);
            assertFalse(c.has("href"), "nunca href: " + s);
            assertFalse(c.has("html"), "nunca html: " + s);
            if (s.contains("Nome da P")) {
                nome = true;
                assertTrue(c.hasNonNull("css"), "CSS suficiente: " + s);
            }
            if (s.contains("Categoria")) {
                categoria = true;
            }
            if (s.contains("Criar P")) {
                botao = true;
                assertEquals("BUTTON", c.path("tag").asText(""));
            }
            assertFalse(s.contains("OTP") || s.contains("api_token") || s.contains("pin-field")
                    || s.contains("ajuda") && s.contains("token=abc"),
                    "sensivel omitido: " + s);
        }
        assertTrue(nome && categoria && botao, out.toString());
        // textarea util passa, mas SEM o valor digitado
        boolean bio = false;
        for (JsonNode c : out.path("controles")) {
            if ("bio".equals(c.path("id").asText(""))) {
                bio = true;
                assertFalse(c.toString().contains("segredo digitado"));
            }
        }
        assertTrue(bio);
    }

    @Test
    void stringsSaoLimitadas() {
        String longa = "x".repeat(500);
        JsonNode bruto = Json.parse("{\"controls\": [{\"tag\": \"INPUT\", \"type\": \"text\","
                + " \"id\": \"nome\", \"label\": \"" + longa + "\","
                + " \"xpath\": \"//input[1]\", \"css\": \"#nome\"}]}");
        var out = Controls.sanitizeResult("facebook", "https://user:secret@x/path?token=abc#fragment", bruto, 60);
        JsonNode c = out.path("controles").get(0);
        assertTrue(c.path("rotulo").asText("").length() <= Controls.TETO_STR);
        assertTrue(c.toString().length() < longa.length());
        assertEquals("https://x/path", out.path("url").asText());
    }

    @Test
    void cssNaoETruncadoEContinuaOpcionalComTetoDe2048() {
        String css = "." + "x".repeat(300);
        JsonNode bruto = Json.parse("{\"controls\":["
                + "{\"tag\":\"INPUT\",\"id\":\"ok\",\"css\":\"" + css + "\"},"
                + "{\"tag\":\"BUTTON\",\"id\":\"sem-css\",\"xpath\":\"//button[1]\"},"
                + "{\"tag\":\"BUTTON\",\"id\":\"longo\",\"css\":\"." + "y".repeat(2049) + "\"}]}" );
        var out = Controls.sanitizeResult("facebook", "https://x/path?query=1#frag", bruto, 60);
        assertEquals("https://x/path", out.path("url").asText());
        JsonNode primeiro = out.path("controles").get(0);
        assertEquals(css, primeiro.path("css").asText());
        assertTrue(primeiro.path("css").asText().length() > Controls.TETO_STR);
        JsonNode semCss = out.path("controles").get(1);
        assertEquals("sem-css", semCss.path("id").asText());
        assertFalse(semCss.has("css"));
        JsonNode cssExcessivo = out.path("controles").get(2);
        assertFalse(cssExcessivo.has("css"));
    }

    @Test
    void urlsRemovemCredenciaisQueryEFragmento() {
        var out = Controls.sanitizeResult("facebook", "https://alice:senha@facebook.com/a/b?token=secreto#parte",
                Json.parse("{}"), 60);
        assertEquals("https://facebook.com/a/b", out.path("url").asText());
        assertFalse(out.toString().contains("alice"));
        assertFalse(out.toString().contains("secreto"));
        assertFalse(out.toString().contains("parte"));
    }

    @Test
    void omiteCamposDeCodigoVerificacaoEAutocompleteOtp() {
        JsonNode bruto = Json.parse("""
                {"controls":[
                  {"tag":"INPUT","type":"text","id":"verification-code","name":"code","label":"Verification code","css":"#verification-code"},
                  {"tag":"INPUT","type":"text","id":"delivery","autocomplete":"one-time-code","css":"#delivery"},
                  {"tag":"INPUT","type":"text","id":"normal","name":"category","label":"Category","css":"#normal"}
                ]}
                """);
        var out = Controls.sanitizeResult("facebook", "https://x", bruto, 60);
        assertEquals(1, out.path("total").asInt(), out.toString());
        assertEquals("normal", out.path("controles").get(0).path("id").asText());
        assertFalse(out.toString().contains("verification"));
        assertFalse(out.toString().contains("delivery"));
    }

    @Test
    void scriptRealNoChromeHeadlessComFixtureLocal() throws Exception {
        StringBuilder nesting = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            nesting.append("<div>");
        }
        nesting.append("<label for='odd\"[id]'>Nome da Página</label><input id='odd\"[id]' name='page_name' aria-label='Nome da Página'>");
        for (int i = 0; i < 40; i++) {
            nesting.append("</div>");
        }
        String fixture = "<!doctype html><html><head><meta charset='utf-8'></head><body>"
                + "<input name='category' aria-label='Categoria'><button id='create'>Criar</button>"
                + "<input type='password' value='SECRETO'><input type='hidden' value='SECRETO'>"
                + "<input name='verification-code' value='SECRETO'><input autocomplete='one-time-code' value='SECRETO'>"
                + "<input id='pin-field' value='SECRETO'>" + nesting
                + "<pre id='test-result'></pre><script>"
                + "var before=document.documentElement.outerHTML;"
                + "var result=" + Controls.inspectScript(60) + ";"
                + "var controls=JSON.parse(result).controls;var valid=true;"
                + "for(var i=0;i<controls.length;i++){var c=controls[i];"
                + "if(!c.css||c.css.length>2048){valid=false;continue;}"
                + "try{var found=document.querySelectorAll(c.css);if(found.length!==1||!found[0]||!found[0].isConnected){valid=false;}if(c.xpath&&document.evaluate(c.xpath,document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue!==found[0]){valid=false;}}"
                + "catch(e){valid=false;}}"
                + "document.getElementById('test-result').textContent='';"
                + "var unchanged=before===document.documentElement.outerHTML;"
                + "document.getElementById('test-result').textContent=btoa(unescape(encodeURIComponent(JSON.stringify({result:result,valid:valid,unchanged:unchanged}))));"
                + "</script></body></html>";
        Path html = tmp.resolve("controls-fixture.html");
        Files.writeString(html, fixture, StandardCharsets.UTF_8);
        // Espera deterministica de carregamento/script via relogio virtual do Chrome
        // (sem sleep nem retries): --dump-dom serializa apos o budget, entao o
        // script sincrono da fixture sempre resolveu antes do dump.
        Process process = new ProcessBuilder("/usr/bin/google-chrome", "--headless", "--no-sandbox",
                "--disable-gpu", "--dump-dom", "--virtual-time-budget=5000",
                "--user-data-dir=" + tmp.resolve("chrome-profile"),
                html.toUri().toString()).redirectErrorStream(true).start();
        CompletableFuture<CapturedOutput> stdout = CompletableFuture.supplyAsync(
                () -> readLimited(process.getInputStream(), 1_000_000));
        boolean completed = process.waitFor(30_000, TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
            fail("Chrome excedeu timeout de 30 segundos");
        }
        CapturedOutput captured = stdout.get(5, TimeUnit.SECONDS);
        assertFalse(captured.truncated(), "stdout do Chrome excedeu 1 MB");
        String dom = new String(captured.bytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "Chrome falhou: " + dom.substring(0, Math.min(2000, dom.length())));
        // Diagnostico em duas etapas (limitado a trechos curtos): distingue
        // "pre ausente no dump" de "script nao preencheu" de "conteudo invalido".
        Matcher tag = Pattern.compile("<pre id=\"test-result\">(.*?)</pre>", Pattern.DOTALL).matcher(dom);
        if (!tag.find()) {
            fail("resultado da fixture ausente no dump-dom (tag pre#test-result nao encontrada)"
                    + " exit=" + process.exitValue()
                    + " domLength=" + dom.length()
                    + " temPre=" + dom.contains("test-result")
                    + " inicio=" + dom.substring(0, Math.min(2000, dom.length())));
        }
        String payload = tag.group(1).trim();
        if (payload.isEmpty()) {
            fail("test-result vazio (script da fixture nao preencheu antes do dump)"
                    + " exit=" + process.exitValue()
                    + " domLength=" + dom.length()
                    + " inicio=" + dom.substring(0, Math.min(2000, dom.length())));
        }
        assertTrue(Pattern.compile("\\A[A-Za-z0-9+/=]+\\z").matcher(payload).find(),
                "conteudo de test-result nao e base64, primeiros 200 caracteres: "
                        + payload.substring(0, Math.min(200, payload.length()))
                        + " domLength=" + dom.length());
        JsonNode run = Json.parse(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
        assertTrue(run.path("valid").asBoolean(), "CSS seleciona exatamente um elemento: " + run);
        assertTrue(run.path("unchanged").asBoolean(), "DOM preservado fora de test-result");
        String result = run.path("result").asText();
        assertFalse(result.contains("SECRETO"), "nenhum valor secreto deve aparecer");
        JsonNode controls = Json.parse(result).path("controls");
        assertEquals(3, controls.size(), controls.toString());
        assertTrue(controls.toString().contains("page_name"));
        assertFalse(controls.toString().contains("verification-code"));
        assertFalse(controls.toString().contains("one-time-code"));
        assertFalse(controls.toString().contains("pin-field"));
        JsonNode deep = null;
        for (JsonNode control : controls) {
            if ("page_name".equals(control.path("name").asText())) {
                deep = control;
            }
        }
        assertNotNull(deep, controls.toString());
        assertTrue(!deep.path("css").asText().isEmpty() && deep.path("css").asText().length() <= 2048,
                "controle profundo tem seletor valido (curto ou caminho completo), ja resolvido pela fixture");
    }

    private record CapturedOutput(byte[] bytes, boolean truncated) {
    }

    private static CapturedOutput readLimited(InputStream input, int maxBytes) {
        try (input; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                int remaining = maxBytes - kept.size();
                if (remaining > 0) {
                    kept.write(buffer, 0, Math.min(read, remaining));
                }
                total += read;
            }
            return new CapturedOutput(kept.toByteArray(), total > maxBytes);
        } catch (IOException e) {
            throw new IllegalStateException("falha lendo stdout limitado do Chrome", e);
        }
    }

    @Test
    void tetoDeQuantidade() {
        StringBuilder sb = new StringBuilder("{\"controls\": [");
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"tag\": \"BUTTON\", \"id\": \"b").append(i)
                    .append("\", \"text\": \"B").append(i)
                    .append("\", \"xpath\": \"//button[").append(i + 1)
                    .append("]\", \"css\": \"#b").append(i).append("\"}");
        }
        sb.append("]}");
        JsonNode bruto = Json.parse(sb.toString());
        var dois = Controls.sanitizeResult("facebook", "https://x", bruto, 2);
        assertEquals(2, dois.path("total").asInt());
        assertTrue(dois.path("truncado").asBoolean(false));
        var todos = Controls.sanitizeResult("facebook", "https://x", bruto, 60);
        assertEquals(5, todos.path("total").asInt());
        assertFalse(todos.path("truncado").asBoolean(true));
    }

    @Test
    void brutoInvalidoOuVazioNaoQuebra() {
        var vazio = Controls.sanitizeResult("facebook", "https://x", Json.parse("{}"), 60);
        assertEquals(0, vazio.path("total").asInt());
        assertFalse(vazio.path("truncado").asBoolean(true));
        var nulo = Controls.sanitizeResult("facebook", "https://x", null, 60);
        assertEquals(0, nulo.path("total").asInt());
    }
}
