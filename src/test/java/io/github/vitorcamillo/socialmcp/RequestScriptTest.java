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
 * Executa o script do passo request (Tools.requestScript) no node com uma pagina falsa
 * (document.cookie, localStorage, fetch gravador). Sem node no PATH o teste e pulado.
 */
class RequestScriptTest {

    @TempDir
    Path tmp;

    private static final String PAGINA = """
            var calls=[];
            globalThis.window=globalThis;
            globalThis.document={cookie:'outro=1; csrf_token=abc%3D123; token_v2=tk-cookie',
              querySelector:function(){return null;}};
            globalThis.localStorage={getItem:function(k){return k==='S'?JSON.stringify({a:{jwt:'jwt-ls'}}):null;}};
            globalThis.sessionStorage={getItem:function(){return null;}};
            globalThis.fetch=function(u,o){calls.push({u:u,o:o});
              return Promise.resolve({status:200,text:function(){return Promise.resolve('{"ok":true}');}});};
            """;

    /** Roda o script e devolve {calls:[{u,o}], res:{s,b}}. */
    private JsonNode rodar(String passo) throws Exception {
        String js = Tools.requestScript("K", Json.parse(passo));
        Path f = tmp.resolve("t.js");
        Files.writeString(f, PAGINA + "var ret=" + js + ";\n"
                + "setTimeout(function(){console.log(JSON.stringify({ret:ret,calls:calls,res:JSON.parse(window.K||'null')}));},50);\n",
                StandardCharsets.UTF_8);
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
    void csrfEAuthNoCorpoJsonAninhado() throws Exception {
        JsonNode r = rodar("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/svc/shreddit/graphql",
                 "headers":{"content-type":"application/json"},
                 "body":"{\\"operation\\":\\"CreatePost\\",\\"variables\\":{\\"input\\":{\\"title\\":\\"oi\\"}}}",
                 "csrf":{"cookie":"csrf_token","bodyField":"variables.input.csrf_token"},
                 "auth":{"header":"authorization","prefix":"Bearer ","from":"localStorage","key":"S","path":"a.jwt","bodyField":"meta.novo.tk"}}
                """);
        assertEquals("ok", r.path("ret").asText());
        JsonNode o = r.path("calls").get(0).path("o");
        JsonNode corpo = Json.parse(o.path("body").asText());
        assertEquals("abc=123", corpo.path("variables").path("input").path("csrf_token").asText());
        assertEquals("oi", corpo.path("variables").path("input").path("title").asText());
        assertEquals("CreatePost", corpo.path("operation").asText());
        // token do auth no corpo sem prefixo; no header com prefixo
        assertEquals("jwt-ls", corpo.path("meta").path("novo").path("tk").asText());
        assertEquals("Bearer jwt-ls", o.path("headers").path("authorization").asText());
        // csrf sem header: nenhum header extra alem de content-type e authorization
        assertEquals(2, o.path("headers").size(), o.toString());
        assertEquals(200, r.path("res").path("s").asInt());
    }

    @Test
    void csrfNoFormEHeaderJuntos() throws Exception {
        JsonNode r = rodar("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/api/x",
                 "headers":{"Content-Type":"application/x-www-form-urlencoded"},"body":"a=1&b=x%20y",
                 "csrf":{"header":"x-csrf","cookie":"csrf_token","bodyField":"csrf_token"},
                 "auth":{"from":"cookie","key":"token_v2","bodyField":"tk"}}
                """);
        JsonNode o = r.path("calls").get(0).path("o");
        assertEquals("a=1&b=x%20y&csrf_token=abc%3D123&tk=tk-cookie", o.path("body").asText());
        assertEquals("abc=123", o.path("headers").path("x-csrf").asText());
        assertFalse(o.path("headers").has("undefined"), o.toString());
    }

    @Test
    void corpoAusenteViraObjetoESemBodyFieldCorpoIntacto() throws Exception {
        JsonNode r = rodar("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/x",
                 "headers":{"content-type":"application/json"},
                 "csrf":{"cookie":"csrf_token","bodyField":"csrf_token"}}
                """);
        assertEquals("{\"csrf_token\":\"abc=123\"}", r.path("calls").get(0).path("o").path("body").asText());
        r = rodar("""
                {"network":"pinterest","kind":"request","method":"POST","url":"https://br.pinterest.com/x",
                 "body":"a=1","csrf":{"header":"x-csrftoken","cookie":"csrf_token"}}
                """);
        JsonNode o = r.path("calls").get(0).path("o");
        assertEquals("a=1", o.path("body").asText());
        assertEquals("abc=123", o.path("headers").path("x-csrftoken").asText());
    }

    @Test
    void corpoIncompativelFalhaAntesDoFetchSemVazarToken() throws Exception {
        // intermediario que e string: nao da para criar o caminho
        JsonNode r = rodar("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/x",
                 "headers":{"content-type":"application/json"},"body":"{\\"variables\\":\\"s\\"}",
                 "csrf":{"cookie":"csrf_token","bodyField":"variables.csrf_token"}}
                """);
        assertEquals("sem-body", r.path("ret").asText());
        assertEquals(0, r.path("calls").size());
        assertEquals(-3, r.path("res").path("s").asInt());
        assertFalse(r.path("res").path("b").asText().contains("abc"), r.toString());
        // cookie ausente continua falhando antes do fetch
        r = rodar("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/x",
                 "headers":{"content-type":"application/json"},
                 "csrf":{"cookie":"nao_tem","bodyField":"csrf_token"}}
                """);
        assertEquals(-1, r.path("res").path("s").asInt());
        assertEquals(0, r.path("calls").size());
    }

    @Test
    void scriptNaoCarregaCampoExtraDoPasso() {
        String js = Tools.requestScript("K", Json.parse("""
                {"network":"reddit","kind":"request","method":"POST","url":"https://www.reddit.com/x",
                 "headers":{"content-type":"application/json"},
                 "csrf":{"cookie":"c","bodyField":"t","lixo":"SEGREDO"},
                 "auth":{"from":"cookie","key":"k","bodyField":"u","extra":"SEGREDO"}}
                """));
        assertFalse(js.contains("SEGREDO"));
    }

    @Test
    void scriptUsaAbortControllerParaCancelarNoTimeout() {
        String js = Tools.requestScript("K", Json.parse("""
                {"network":"pinterest","kind":"request","method":"POST","url":"https://br.pinterest.com/x","body":"a=1"}
                """));
        assertTrue(js.contains("AbortController"), js);
        assertTrue(js.contains("signal:ctrl.signal"), js);
        assertTrue(js.contains("__ctrl"), js);
    }

    @Test
    void credentialsDoPassoVaiParaOFetch() throws Exception {
        JsonNode r = rodar("""
                {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x",
                 "headers":{"content-type":"application/json"},"body":"{}","credentials":"omit"}
                """);
        assertEquals("omit", r.path("calls").get(0).path("o").path("credentials").asText());
        // padrao include
        r = rodar("""
                {"network":"pinterest","kind":"request","method":"POST","url":"https://br.pinterest.com/x","body":"a=1"}
                """);
        assertEquals("include", r.path("calls").get(0).path("o").path("credentials").asText());
        // valor fora da lista nunca chega ao script
        String js = Tools.requestScript("K", Json.parse("""
                {"network":"pinterest","kind":"request","method":"POST","url":"https://br.pinterest.com/x","credentials":"x'+alert(1)+'"}
                """));
        assertFalse(js.contains("alert"));
    }

    private static final JsonNode POST = Json.parse("""
            {"network":"bluesky","kind":"request","method":"POST","url":"https://bsky.social/xrpc/x","body":"{}",
             "headers":{"content-type":"application/json"}}
            """);

    @Test
    void falhaDoNavegadorDepoisDoFetchViraIncerto() {
        int[] n = {0};
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.obj();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Tools.runRequest(js -> {
            if (n[0]++ == 0) return "ok"; // dispara o fetch
            throw new RuntimeException("Inspected target navigated or closed");
        }, POST, out));
        assertTrue(e.getMessage().contains("incerto"), e.getMessage());
        assertTrue(out.path("incerto").asBoolean(false), out.toString());
        assertEquals(0, out.path("status").asInt(-1));
    }

    @Test
    void respostaDoRequestSaiSemToken() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.obj();
        String resp = "{\"s\":200,\"b\":\"{\\\"uri\\\":\\\"at://x\\\",\\\"accessJwt\\\":\\\"" + String.join(".", "eyJ" + "a".repeat(12), "eyJ" + "b".repeat(12), "c".repeat(10)) + "\\\"}\"}";
        String info = Tools.runRequest(js -> js.startsWith("(window[") ? resp : "ok", POST, out);
        assertTrue(info.endsWith("-> 200"), info);
        assertTrue(out.path("bodySnippet").asText().contains("at://x"), out.toString());
        assertFalse(out.toString().contains("eyJ"), out.toString());
    }

    @Test
    void fetchRejeitadoViraIncertoECodigoNegativoENaoEnviado() {
        // status 0 = fetch rejeitado DEPOIS de disparar (CORS/erro de rede): servidor pode ter aplicado
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.obj();
        String rejeitado = "{\"s\":0,\"b\":\"erro fetch: TypeError: Failed to fetch\"}";
        IllegalStateException e0 = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(js -> js.startsWith("(window[") ? rejeitado : "ok", POST, out));
        assertTrue(e0.getMessage().contains("incerto"), e0.getMessage());
        assertTrue(out.path("incerto").asBoolean(false), out.toString());
        assertEquals(0, out.path("status").asInt(-1));
        assertTrue(Tools.talvezAgiu("request", e0, out), "tentativa incerta conta para cota");
        // negativo = falha ANTES do fetch (csrf/auth/corpo): request nao saiu, nao conta
        com.fasterxml.jackson.databind.node.ObjectNode outNeg = Json.obj();
        String semCsrf = "{\"s\":-1,\"b\":\"csrf: cookie ausente\"}";
        IllegalStateException eNeg = assertThrows(IllegalStateException.class,
                () -> Tools.runRequest(js -> js.startsWith("(window[") ? semCsrf : "ok", POST, outNeg));
        assertTrue(eNeg.getMessage().contains("nao enviado"), eNeg.getMessage());
        assertFalse(outNeg.path("incerto").asBoolean(false), outNeg.toString());
        assertFalse(Tools.talvezAgiu("request", eNeg, outNeg));
    }
}
