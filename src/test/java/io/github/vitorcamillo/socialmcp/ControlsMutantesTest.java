package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mata mutantes de {@link Controls} (PIT). Cada teste observa o contrato atual
 * no limite exato: truncado quando a quantidade passa do maximo, allowlist de
 * URL, teto de 2048 do seletor, texto so em controles de acao e presenca dos
 * campos no JSON sanitizado. Nao altera src/main.
 */
class ControlsMutantesTest {

    private static JsonNode no(String json) {
        return Json.parse(json);
    }

    @Test
    void truncadoSoQuandoPassaDaQuantidadePedida() {
        JsonNode dois = no("{\"controls\":["
                + "{\"tag\":\"INPUT\",\"id\":\"a\",\"css\":\"#a\"},"
                + "{\"tag\":\"INPUT\",\"id\":\"b\",\"css\":\"#b\"}]}");

        var noLimite = Controls.sanitizeResult("x", "https://x", dois, 2);
        assertEquals(2, noLimite.path("total").asInt());
        assertFalse(noLimite.path("truncado").asBoolean(true),
                "crus == max nao pode marcar truncado");

        var acima = Controls.sanitizeResult("x", "https://x", dois, 1);
        assertEquals(1, acima.path("total").asInt());
        assertTrue(acima.path("truncado").asBoolean(false),
                "crus > max precisa marcar truncado");

        var marcado = Controls.sanitizeResult("x", "https://x",
                no("{\"controls\":[],\"truncated\":true}"), 60);
        assertTrue(marcado.path("truncado").asBoolean(false),
                "flag truncated do script tambem marca truncado");
    }

    @Test
    void urlSoAceitaHttpHttpsComHost() {
        assertEquals("https://exemplo.com/a/b",
                Controls.safeUrl("https://exemplo.com/a/b?x=1#f"));
        assertEquals("http://exemplo.com:8080/a",
                Controls.safeUrl("http://exemplo.com:8080/a"));
        assertEquals("https://exemplo.com/a",
                Controls.safeUrl("https://user:senha@exemplo.com/a"));

        assertEquals("", Controls.safeUrl("ftp://exemplo.com/a"));
        assertEquals("", Controls.safeUrl("javascript:alert(1)"));
        assertEquals("", Controls.safeUrl("file:///etc/passwd"));
        assertEquals("", Controls.safeUrl("http:sem-host"));
        assertEquals("", Controls.safeUrl(""));
        assertEquals("", Controls.safeUrl(null));
    }

    @Test
    void seletorAceitaExatamente2048Caracteres() {
        String noLimite = "c".repeat(2048);
        JsonNode aceito = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("css", noLimite));
        assertNotNull(aceito);
        assertEquals(noLimite, aceito.path("css").asText(""));

        JsonNode xpathAceito = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("xpath", noLimite));
        assertNotNull(xpathAceito);
        assertEquals(noLimite, xpathAceito.path("xpath").asText(""));

        String acima = "c".repeat(2049);
        JsonNode recusado = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("css", acima));
        assertNotNull(recusado);
        assertFalse(recusado.has("css"), "2049 nao cabe no teto de 2048");
    }

    @Test
    void textoSoEntraEmTagDeAcaoOuRoleButton() {
        String[][] incluem = {
                {"BUTTON", "", "Botao"},
                {"SELECT", "", "Opcao"},
                {"A", "", "Link"},
                {"DIV", "button", "Comprar"},
                {"INPUT", "button", "Comprar2"},
        };
        for (String[] caso : incluem) {
            ObjectNode n = Json.obj().put("tag", caso[0]).put("id", "id-" + caso[0].toLowerCase());
            if (!caso[1].isEmpty()) {
                n.put("role", caso[1]);
            }
            n.put("text", caso[2]);
            JsonNode out = Controls.sanitizeNode(n);
            assertNotNull(out, caso[0]);
            assertEquals(caso[2], out.path("texto").asText(""),
                    "texto de " + caso[0] + " role=" + caso[1]);
        }

        String[][] excluem = {
                {"INPUT", ""},
                {"TEXTAREA", ""},
                {"DIV", ""},
        };
        for (String[] caso : excluem) {
            ObjectNode n = Json.obj().put("tag", caso[0])
                    .put("id", "sem-texto-" + caso[0].toLowerCase())
                    .put("text", "NaoMostra");
            if (!caso[1].isEmpty()) {
                n.put("role", caso[1]);
            }
            JsonNode out = Controls.sanitizeNode(n);
            assertNotNull(out, caso[0]);
            assertFalse(out.has("texto"),
                    "texto nao vale para " + caso[0] + " role=" + caso[1]);
        }
    }

    @Test
    void noSemNenhumDadoUtilEhDescartado() {
        assertNull(Controls.sanitizeNode(Json.obj().put("tag", "INPUT")),
                "sem id/name/rotulo/texto/xpath/css o no some");
        assertNull(Controls.sanitizeNode(Json.obj().put("tag", "BUTTON")),
                "botao sem nenhum dado util tambem some");
    }

    @Test
    void noComUmUnicoDadoUtilEhMantido() {
        JsonNode soId = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("id", "so-id"));
        assertNotNull(soId);
        assertEquals("so-id", soId.path("id").asText(""));

        JsonNode soNome = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("name", "so-nome"));
        assertNotNull(soNome);
        assertEquals("so-nome", soNome.path("name").asText(""));

        JsonNode soRotulo = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("label", "So rotulo"));
        assertNotNull(soRotulo);
        assertEquals("So rotulo", soRotulo.path("rotulo").asText(""));

        JsonNode soTexto = Controls.sanitizeNode(Json.obj().put("tag", "BUTTON").put("text", "SoTexto"));
        assertNotNull(soTexto);
        assertEquals("SoTexto", soTexto.path("texto").asText(""));

        JsonNode soXpath = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("xpath", "//input[9]"));
        assertNotNull(soXpath);
        assertEquals("//input[9]", soXpath.path("xpath").asText(""));

        JsonNode soCss = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("css", "#so-css"));
        assertNotNull(soCss);
        assertEquals("#so-css", soCss.path("css").asText(""));
    }

    @Test
    void typeSoVaiQuandoNaoVazio() {
        JsonNode com = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("type", "text"));
        assertNotNull(com);
        assertEquals("text", com.path("type").asText(""));

        JsonNode sem = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("id", "campo"));
        assertNotNull(sem);
        assertFalse(sem.has("type"), "campo type vazio nao vai ao JSON");
    }

    @Test
    void roleSoVaiQuandoNaoVazio() {
        JsonNode com = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("role", "button"));
        assertNotNull(com);
        assertEquals("button", com.path("role").asText(""));

        JsonNode sem = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("id", "campo"));
        assertNotNull(sem);
        assertFalse(sem.has("role"), "campo role vazio nao vai ao JSON");
    }

    @Test
    void nameSoVaiQuandoNaoVazio() {
        JsonNode com = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("name", "campo_nome"));
        assertNotNull(com);
        assertEquals("campo_nome", com.path("name").asText(""));

        JsonNode sem = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("id", "campo"));
        assertNotNull(sem);
        assertFalse(sem.has("name"), "campo name vazio nao vai ao JSON");
    }

    @Test
    void xpathSoVaiQuandoNaoVazio() {
        JsonNode com = Controls.sanitizeNode(Json.obj()
                .put("tag", "INPUT").put("id", "campo").put("xpath", "//input[1]"));
        assertNotNull(com);
        assertEquals("//input[1]", com.path("xpath").asText(""));

        JsonNode sem = Controls.sanitizeNode(Json.obj().put("tag", "INPUT").put("id", "campo"));
        assertNotNull(sem);
        assertFalse(sem.has("xpath"), "campo xpath vazio nao vai ao JSON");
    }
}
