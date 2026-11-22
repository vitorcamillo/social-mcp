package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia D (rodada 3): mutantes de Tools que precisam de navegador real
 * (inspect/url, probe com params [] x {}, probe interrompida no poll de
 * presente). Um ChromeFalso + Store + Tools por classe.
 */
class ToolsChromeLeitura2Test {

    @TempDir
    static Path tmp;

    static Store store;
    static ChromeFalso cf;
    static Tools tools;

    @BeforeAll
    static void abrir() throws Exception {
        store = new Store(tmp.resolve("home"));
        Path dir = store.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), """
                {"network":"bluesky","actions":{
                  "get_post":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/sonda"},
                    {"kind":"verify_text","expect":"{{text}}"},
                    {"kind":"click","selector":"css:button#alvo-sonda","description":"abrir alvo"}]},
                  "get_profile":{"params":["text"],"steps":[
                    {"kind":"goto","url":"https://bsky.app/sonda"},
                    {"kind":"verify_text","expect":"{{text}}"},
                    {"kind":"click","selector":"css:#botao-que-nao-existe","description":"alvo ausente"}]}
                }}
                """, StandardCharsets.UTF_8);
        cf = ChromeFalso.abrir(tmp, store);
        cf.pagina("/sonda", """
                <html><head><title>Pagina Sonda</title></head><body>
                <h1>Sonda da rede</h1>
                <p id="boas">frase-sonda-xyz de boas-vindas a pagina de testes.</p>
                <input aria-label="Nome completo" name="nome" type="text">
                <button id="alvo-sonda">Entrar na sonda</button>
                </body></html>
                """);
        tools = new Tools(store);
    }

    @AfterAll
    static void fechar() {
        try {
            tools.shutdown();
        } finally {
            cf.close();
        }
    }

    private static void saude(String estado) {
        store.setState("bluesky", estado, "teste leitura2", "teste");
    }

    private static ObjectNode probeArgs(String acao, JsonNode params) {
        ObjectNode a = Json.obj();
        a.put("network", "bluesky");
        a.put("action", acao);
        if (params != null) a.set("params", params);
        return a;
    }

    @Test
    @Timeout(60)
    void inspectUrlESafeUrlDaPagina() throws Exception {
        tools.call("social_browse", Json.obj()
                .put("network", "bluesky").put("url", "https://bsky.app/sonda?x=1"));
        String inspecao = tools.call("social_inspect", Json.obj().put("network", "bluesky"));
        JsonNode insp = Json.parse(inspecao);
        assertTrue(insp.path("controles").size() >= 2, inspecao);
        assertEquals("https://bsky.app/sonda", insp.path("url").asText(), inspecao);
        assertEquals(Controls.safeUrl("https://bsky.app/sonda?x=1"),
                insp.path("url").asText(), inspecao);
    }

    @Test
    @Timeout(60)
    void probeArrayVazioNaoUsaExemploEnquantoObjetoVazioUsa() throws Exception {
        saude("healthy");
        JsonNode saidaLista = Json.parse(tools.call("social_probe",
                probeArgs("get_post", Json.M.createArrayNode())));
        assertFalse(saidaLista.path("ok").asBoolean(true), saidaLista.toString());
        assertEquals("params deve ser objeto", saidaLista.path("motivo").asText(),
                saidaLista.toString());
        assertEquals(0, saidaLista.path("passos").size(), saidaLista.toString());
        assertTrue(saidaLista.path("primeiroControle").isNull(), saidaLista.toString());

        JsonNode saidaObjeto = Json.parse(tools.call("social_probe",
                probeArgs("get_post", Json.obj())));
        assertFalse(saidaObjeto.path("ok").asBoolean(true), saidaObjeto.toString());
        assertTrue(saidaObjeto.path("motivo").asText("").contains("passo 2 falhou"),
                saidaObjeto.toString());
        assertEquals(2, saidaObjeto.path("passos").size(), saidaObjeto.toString());
        assertTrue(saidaObjeto.path("primeiroControle").isNull(), saidaObjeto.toString());
    }

    @Test
    @Timeout(60)
    void probeInterrompidaNoPollDePresenteReligaFlag() throws Exception {
        saude("healthy");
        TypeText.Pausa original = Tools.pausa;
        Tools.pausa = ms -> {
            if (ms == 300) throw new InterruptedException("pausa-teste");
        };
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> tools.call(
                    "social_probe",
                    probeArgs("get_profile", Json.obj().put("text", "frase-sonda-xyz"))));
            assertTrue(e.getMessage().contains(
                    "sonda interrompida ao conferir 'css:#botao-que-nao-existe'"), e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "flag de interrupt ausente (L549)");
        } finally {
            Tools.pausa = original;
            Thread.interrupted();
        }
    }
}
