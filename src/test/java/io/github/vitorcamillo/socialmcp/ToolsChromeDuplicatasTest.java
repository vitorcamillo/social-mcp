package io.github.vitorcamillo.socialmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fatia Chrome B: mutantes de {@code Tools.findDuplicates} (navegacao real via
 * {@link ChromeFalso}) e de {@code Tools.jitterMs} (exato, sem cronometro).
 * {@code redigirSubarvores} nao tem teste: equivalentes provados (relatorio da fatia).
 * Uma fixture por classe; receita e paginas trocadas por teste; estado da rede saneado
 * em {@code @AfterEach}. As costuras {@code Tools.pausa} (gravador de ms) e
 * {@code Tools.sorteio} (0.25 fixo) sao injetadas por teste: a classe inteira roda sem
 * dormir de verdade.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolsChromeDuplicatasTest {

    @TempDir
    static Path tmp;

    private static Store store;
    private static Tools tools;
    private static ChromeFalso falso;

    /** Pausas recebidas por {@code Tools.pausa} no teste atual (ms), na ordem. */
    private static final List<Long> PAUSAS = Collections.synchronizedList(new ArrayList<>());
    private static TypeText.Pausa pausaOriginal;
    private static DoubleSupplier sorteioOriginal;

    private static final String PARAGRAFO =
            "Relatorio consolidado de resultados trimestrais com metas superadas em todos "
            + "os indicadores principais do periodo avaliado";

    @BeforeAll
    static void abrir() throws Exception {
        pausaOriginal = Tools.pausa;
        sorteioOriginal = Tools.sorteio;
        store = new Store(tmp.resolve("home"));
        falso = ChromeFalso.abrir(tmp, store);
        tools = new Tools(store);
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/list"},
                  {"kind":"extract","what":"links"}]}}}""");
        falso.pagina("/list",
                "<html><head><title>perfil</title></head><body>"
                + "<nav>voltar inicio perfil buscar mensagens</nav>"
                + "<a href=\"https://bsky.app/post/1\">post um</a>"
                + "<a href=\"https://bsky.app/post/2\">post dois</a>"
                + "<a href=\"mailto:amigo@exemplo.com\">contato</a>"
                + "</body></html>");
        String post = "<html><head><title>post</title></head><body>"
                + "<nav>voltar inicio perfil buscar mensagens</nav>"
                + "<article><p>" + PARAGRAFO + "</p></article>"
                + "</body></html>";
        falso.pagina("/post/1", post);
        falso.pagina("/post/2", post);
        falso.pagina("/post/3",
                "<html><head><title>post3</title></head><body><p>terceiro texto distinto aqui</p></body></html>");
    }

    @AfterAll
    static void fechar() {
        if (tools != null) tools.shutdown();
        if (falso != null) falso.close();
    }

    @BeforeEach
    void costurasDeterministicas() {
        PAUSAS.clear();
        Tools.pausa = ms -> {
            PAUSAS.add(ms);
        };
        Tools.sorteio = () -> 0.25;
    }

    @AfterEach
    void restaurarCosturas() {
        Tools.pausa = pausaOriginal;
        Tools.sorteio = sorteioOriginal;
    }

    @AfterEach
    void sanearRede() {
        store.setState("bluesky", "healthy", "saneamento entre testes", "teste");
    }

    /** Copia das pausas gravadas (gravador sincronizado; ordem de chegada). */
    private static List<Long> pausas() {
        synchronized (PAUSAS) {
            return new ArrayList<>(PAUSAS);
        }
    }

    private static void escreverReceita(String json) throws Exception {
        Path dir = store.home.resolve("recipes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bluesky.json"), json, StandardCharsets.UTF_8);
    }

    private static JsonNode duplicatas(double limiar, int maxPosts) throws Exception {
        String res = tools.call("social_find_duplicates",
                Json.obj().put("network", "bluesky").put("limiar", limiar).put("maxPosts", maxPosts));
        assertFalse(res == null || res.isBlank(), "resposta vazia");
        return Json.parse(res);
    }

    // Caminho feliz: limiar ecoado, analisados/falhas/duplicados, journal e a pausa de
    // findDuplicates (sorteio 0.25 => 2500 ms) exatamente uma vez por URL visitada.
    @Test
    @Order(1)
    @Timeout(60)
    void duplicadosHappyPathAgrupaDoisPostsIdenticos() throws Exception {
        JsonNode out = duplicatas(0.9, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertTrue(Math.abs(out.path("limiar").asDouble() - 0.9) < 1e-9, out.toString());
        assertEquals(2, out.path("analisados").asInt(-1), out.toString());
        assertFalse(out.path("tripwire").asBoolean(true), out.toString());
        assertFalse(out.has("tripwirePadrao"), out.toString());
        assertFalse(out.has("tripwireContexto"), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
        assertEquals(1, out.path("duplicados").size(), out.toString());
        JsonNode grupo = out.path("duplicados").get(0);
        assertTrue(Math.abs(grupo.path("similaridade").asDouble() - 1.0) < 1e-9, out.toString());
        assertEquals(2, grupo.path("posts").size(), out.toString());
        String urls = grupo.path("posts").toString();
        assertTrue(urls.contains("https://bsky.app/post/1"), urls);
        assertTrue(urls.contains("https://bsky.app/post/2"), urls);
        String cauda = String.join("\n", store.tail(30));
        assertTrue(cauda.contains("\"type\":\"duplicates_check\""), cauda);
        assertEquals(List.of(2500L, 2500L), pausas(), "uma pausa de 2500 por URL visitada");
    }

    // Fronteira do laco INTERNO (>=): maxPosts=2 com 3 links num unico extract => 2 analisados.
    // Com ">" o set iria a 3 e o mutante morre aqui.
    @Test
    @Order(2)
    @Timeout(60)
    void limiteMaxPostsCortaNoExato() throws Exception {
        falso.pagina("/list3",
                "<html><head><title>l3</title></head><body>"
                + "<a href=\"https://bsky.app/post/1\">a</a>"
                + "<a href=\"https://bsky.app/post/2\">b</a>"
                + "<a href=\"https://bsky.app/post/3\">c</a>"
                + "</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/list3"},
                  {"kind":"extract","what":"links"}]}}}""");
        JsonNode out = duplicatas(0.6, 2);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(2, out.path("analisados").asInt(-1), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
    }

    // Recusa de receita: lista com ok=false devolve JSON de fora (com motivo), nao "".
    @Test
    @Order(3)
    @Timeout(60)
    void listaComFalhaDevolveForaComMotivo() throws Exception {
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://evil.example/"},
                  {"kind":"extract","what":"links"}]}}}""");
        String res = tools.call("social_find_duplicates",
                Json.obj().put("network", "bluesky").put("maxPosts", 2));
        assertFalse(res == null || res.isBlank(), "resposta vazia");
        JsonNode out = Json.parse(res);
        assertFalse(out.path("ok").asBoolean(true), out.toString());
        assertTrue(out.path("motivo").asText("").contains("passo 1 falhou"), out.toString());
        assertFalse(out.path("tripwire").asBoolean(true), out.toString());
    }

    // Rede banned lanca na validacao de estado ("nao permitida") antes de listar.
    @Test
    @Order(4)
    @Timeout(60)
    void redeBanidaRecusaAntesDeListar() {
        store.setState("bluesky", "banned", "contencao do mutante", "teste");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tools.call("social_find_duplicates",
                        Json.obj().put("network", "bluesky").put("maxPosts", 2)));
        assertTrue(e.getMessage().contains("nao permitida"), e.getMessage());
    }

    // http passa no filtro de links e cai na validacao de navegacao, com motivo claro.
    @Test
    @Order(5)
    @Timeout(60)
    void urlHttpCaiNaValidacaoDeNavegacao() throws Exception {
        falso.pagina("/listhttp",
                "<html><head><title>lh</title></head><body>"
                + "<a href=\"https://bsky.app/post/1\">seguro</a>"
                + "<a href=\"http://bsky.app/inseguro\">inseguro</a>"
                + "</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listhttp"},
                  {"kind":"extract","what":"links"}]}}}""");
        JsonNode out = duplicatas(0.6, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(1, out.path("analisados").asInt(-1), out.toString());
        assertEquals(1, out.path("falhas").size(), out.toString());
        assertEquals("http://bsky.app/inseguro", out.path("falhas").get(0).path("url").asText(""),
                out.toString());
        // mensagem EXATA de BatchStore.validarNavegacao (L692): sem a chamada, o item seria aberto
        assertEquals("navegacao bloqueada: so https para host da rede bluesky"
                + " (startUrl/requestHosts/browseHosts): http://bsky.app",
                out.path("falhas").get(0).path("erro").asText(""), out.toString());
    }

    // Redirect para fora da rede depois do load cai no conferirDestino (falha do item).
    @Test
    @Order(6)
    @Timeout(60)
    void redirectParaForaDaRedeViraFalhaDoItem() throws Exception {
        falso.pagina("/listevil",
                "<html><head><title>le</title></head><body>"
                + "<a href=\"https://bsky.app/evil-post\">e</a>"
                + "</body></html>");
        falso.pagina("/evil-post",
                "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://evil.example/\"></head>"
                + "<body>saindo</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listevil"},
                  {"kind":"extract","what":"links"}]}}}""");
        // a espera pos-get (2500 gravado) dorme de verdade 400 ms: o meta refresh dispara antes do
        // conferirDestino, como em producao (2-4 s), e nao vaza para o teste seguinte
        Tools.pausa = ms -> {
            PAUSAS.add(ms);
            if (ms == 2_500L) Thread.sleep(400);
        };
        JsonNode out = duplicatas(0.6, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(0, out.path("analisados").asInt(-1), out.toString());
        assertEquals(1, out.path("falhas").size(), out.toString());
        assertTrue(out.path("falhas").get(0).path("erro").asText("").contains("navegacao bloqueada"),
                out.toString());
    }

    // Tripwire no post: ok:false, padrao+contexto no retorno, rede em cooldown e nada analisado.
    @Test
    @Order(7)
    @Timeout(60)
    void tripwireNoPostAbortaComPadraoEContexto() throws Exception {
        falso.pagina("/listtrip",
                "<html><head><title>lt</title></head><body>"
                + "<a href=\"https://bsky.app/trippost\">t</a>"
                + "</body></html>");
        falso.pagina("/trippost",
                "<html><head><title>bloqueio</title></head><body>"
                + "<p>Aviso: Rate limit exceeded, tente novamente mais tarde por favor</p>"
                + "<p>" + PARAGRAFO + "</p>"
                + "</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listtrip"},
                  {"kind":"extract","what":"links"}]}}}""");
        String res = tools.call("social_find_duplicates",
                Json.obj().put("network", "bluesky").put("maxPosts", 2));
        assertFalse(res == null || res.isBlank(), "resposta vazia");
        JsonNode out = Json.parse(res);
        assertFalse(out.path("ok").asBoolean(true), out.toString());
        assertTrue(out.path("tripwire").asBoolean(false), out.toString());
        assertEquals(0, out.path("analisados").asInt(-1), out.toString());
        assertEquals("rate limit", out.path("tripwirePadrao").asText(), out.toString());
        assertFalse(out.path("tripwireContexto").asText("").isBlank(), out.toString());
        assertEquals("cooldown", store.effectiveState("bluesky"));
    }

    // itemTexto: o item ausente vira falha (nao le a pagina inteira) e o retry do
    // RealClick.text passa por pausa.dormir: 1000 ms entre as VERIFY_TENTATIVAS
    // (=3) tentativas, ou seja, duas pausas depois da de 2500 daquela URL.
    @Test
    @Order(8)
    @Timeout(60)
    void itemTextoUsaSoOTextoDoItem() throws Exception {
        falso.pagina("/listitem",
                "<html><head><title>li</title></head><body>"
                + "<a href=\"https://bsky.app/item/1\">um</a>"
                + "<a href=\"https://bsky.app/item/2\">dois</a>"
                + "</body></html>");
        falso.pagina("/item/1",
                "<html><head><title>i1</title></head><body>"
                + "<nav>voltar inicio perfil</nav>"
                + "<article id=\"artigo-1\"><p>" + PARAGRAFO + "</p></article>"
                + "</body></html>");
        falso.pagina("/item/2",
                "<html><head><title>i2</title></head><body>"
                + "<nav>voltar inicio perfil</nav>"
                + "<p>texto solto sem artigo por aqui</p>"
                + "</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listitem"},
                  {"kind":"extract","what":"links"}],
                  "itemTexto":{"idRegex":"/item/([0-9]+)","selector":"id:artigo-{{id}}"}}}}""");
        JsonNode out = duplicatas(0.6, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(1, out.path("analisados").asInt(-1), out.toString());
        assertEquals(1, out.path("falhas").size(), out.toString());
        // L718 negado trocaria os papeis: o item achado viraria falha e o ausente entraria nos
        // analisados (as duas contagens coincidem em 1, por isso a URL exata e os grupos vazios)
        assertEquals("https://bsky.app/item/2", out.path("falhas").get(0).path("url").asText(""),
                out.toString());
        assertTrue(out.path("falhas").get(0).path("erro").asText("").contains("item nao encontrado"),
                out.toString());
        assertEquals(0, out.path("duplicados").size(), out.toString());
        assertEquals(List.of(2500L, 2500L, 1000L, 1000L), pausas(),
                "item/2 ausente: 2500 da visita + duas pausas de retry do itemTexto");
    }

    // Segundo passo extract ainda soma quando ha cota: o laco externo so corta ao atingir maxPosts.
    @Test
    @Order(9)
    @Timeout(60)
    void segundoPassoExtractSomaQuandoHaCota() throws Exception {
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/list"},
                  {"kind":"extract","what":"links","match":"post/1"},
                  {"kind":"extract","what":"links","match":"post/2"}]}}}""");
        JsonNode out = duplicatas(0.6, 10);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(2, out.path("analisados").asInt(-1), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
    }

    // jitterMs exato, sem cronometro: par valido, par invertido (Math.max(1,...)), nao-array
    // e arrays de tamanho != 2 caem na faixa padrao [1500,6000), e o asLong(default) e por
    // POSICAO (elemento nao numerico usa o default daquela posicao).
    @Test
    @Order(10)
    @Timeout(20)
    void jitterMsRespeitaFaixaEDefaultsPorPosicao() {
        assertEquals(200L, Tools.jitterMs(Json.parse("[100,300]"), 0.5));
        assertEquals(500L, Tools.jitterMs(Json.parse("[500,100]"), 0.5));
        assertEquals(3750L, Tools.jitterMs(Json.parse("[]"), 0.5));
        assertEquals(3750L, Tools.jitterMs(Json.parse("[100]"), 0.5));
        assertEquals(3750L, Tools.jitterMs(Json.parse("[100,300,500]"), 0.5));
        assertEquals(3750L, Tools.jitterMs(Json.parse("{\"min\":100}"), 0.5));
        assertEquals(3750L, Tools.jitterMs(Json.parse("[\"abc\",\"def\"]"), 0.5));
        assertEquals(3100L, Tools.jitterMs(Json.parse("[200,\"abc\"]"), 0.5));
    }

    // sleepJitter (privado, reflexao) usa defaults.stepDelayMs e entrega o valor exato ao
    // gravador (sem cronometro): [200,200] com sorteio 0.25 => exatamente 200.
    @Test
    @Order(11)
    @Timeout(20)
    void sleepJitterDormeStepDelayMsDaConfig() throws Exception {
        Path cfg = store.configPath();
        String antes = Files.readString(cfg, StandardCharsets.UTF_8);
        try {
            ObjectNode raiz = (ObjectNode) Json.parse(antes);
            ((ObjectNode) raiz.path("defaults")).putArray("stepDelayMs").add(200).add(200);
            Files.writeString(cfg, Json.writePretty(raiz), StandardCharsets.UTF_8);
            Method m = Tools.class.getDeclaredMethod("sleepJitter", String.class);
            m.setAccessible(true);
            m.invoke(tools, "bluesky");
            assertEquals(List.of(200L), pausas(), "sleepJitter deve dormir exatamente stepDelayMs");
        } finally {
            Files.writeString(cfg, antes, StandardCharsets.UTF_8);
        }
    }

    // Fronteira do laco EXTERNO (>=): o primeiro extract casa exatamente maxPosts=1 link e o
    // segundo extract traz PRIMEIRO uma URL NOVA (post/2). Original: para antes de olhar o
    // segundo extract. Com ">" o segundo extract seria processado e post/2 entraria (2
    // analisados, 2 pausas de 2500). Se o segundo extract comecasse pela URL repetida, o
    // mutante morreria no break interno e sobreviveria.
    @Test
    @Order(12)
    @Timeout(60)
    void lacoExternoParaQuandoAtingeMaxPosts() throws Exception {
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/list"},
                  {"kind":"extract","what":"links","match":"post/1"},
                  {"kind":"extract","what":"links","match":"post/2"}]}}}""");
        JsonNode out = duplicatas(0.6, 1);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(1, out.path("analisados").asInt(-1), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
        assertEquals(List.of(2500L), pausas(), out.toString());
    }

    // Fronteira das VERIFY_TENTATIVAS (<=, L711): o texto do item so fica legivel na 3a
    // tentativa. O findElement tem implicitlyWait de 4 s, entao um setTimeout de ~300 ms que
    // ADICIONA o item seria achado ja na 1a tentativa; o que o codigo lê e o texto do elemento
    // (RealClick.text), e o getter de innerText abaixo falha nas 2 primeiras leituras ("ainda
    // carregando") e responde na 3a — mesmo efeito no laco. O gravador dorme 400 ms de verdade
    // SO na 2a pausa de 1000 (como o setTimeout, mas no relogio do teste): com "<" (2
    // tentativas) o item nao sai e vira falha do item.
    @Test
    @Order(13)
    @Timeout(60)
    void itemTextoSoSaiNaTerceiraTentativa() throws Exception {
        falso.pagina("/listlate",
                "<html><head><title>ll</title></head><body>"
                + "<a href=\"https://bsky.app/late/1\">um</a>"
                + "</body></html>");
        falso.pagina("/late/1",
                "<html><head><title>late</title></head><body>"
                + "<nav>voltar inicio perfil</nav>"
                + "<article id=\"artigo-1\"><p>" + PARAGRAFO + "</p></article>"
                + "<script>"
                + "var artigo=document.getElementById('artigo-1');var leituras=0;"
                + "Object.defineProperty(artigo,'innerText',{configurable:true,get:function(){"
                + "leituras++;if(leituras<3){throw new Error('item ainda carregando');}"
                + "return this.textContent;}});"
                + "</script></body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listlate"},
                  {"kind":"extract","what":"links"}],
                  "itemTexto":{"idRegex":"/late/([0-9]+)","selector":"id:artigo-{{id}}"}}}}""");
        int[] retries = {0};
        Tools.pausa = ms -> {
            PAUSAS.add(ms);
            if (ms == 1_000L && ++retries[0] == 2) Thread.sleep(400);
        };
        JsonNode out = duplicatas(0.6, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(1, out.path("analisados").asInt(-1), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
        assertEquals(0, out.path("duplicados").size(), out.toString());
        assertEquals(List.of(2500L, 1000L, 1000L), pausas(),
                "3a leitura acha: 2500 da visita + duas pausas de retry (com < so haveria 2 leituras)");
    }

    // L718 negado: item ACHADO nao pode virar falha nem sair dos analisados. Uma unica URL com
    // o artigo presente: analisados=1 exato, falhas vazias e nenhum grupo. Negado, o item
    // acharia "item nao encontrado", analisados=0 e a falha apareceria.
    @Test
    @Order(14)
    @Timeout(60)
    void itemTextoAchadoNaoViraFalha() throws Exception {
        falso.pagina("/listfound",
                "<html><head><title>lf</title></head><body>"
                + "<a href=\"https://bsky.app/item/1\">um</a>"
                + "</body></html>");
        escreverReceita("""
                {"network":"bluesky","actions":{"list_posts":{"readOnly":true,"steps":[
                  {"kind":"goto","url":"https://bsky.app/listfound"},
                  {"kind":"extract","what":"links"}],
                  "itemTexto":{"idRegex":"/item/([0-9]+)","selector":"id:artigo-{{id}}"}}}}""");
        JsonNode out = duplicatas(0.6, 12);
        assertTrue(out.path("ok").asBoolean(false), out.toString());
        assertEquals(1, out.path("analisados").asInt(-1), out.toString());
        assertEquals(0, out.path("falhas").size(), out.toString());
        assertEquals(0, out.path("duplicados").size(), out.toString());
        assertEquals(List.of(2500L), pausas(), out.toString());
    }
}
