package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.WebElement;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tools expostos via MCP. Filosofia:
 *  - leitura (browse/read/screenshot/status/journal): livre, porem registrada;
 *  - acao publica (click/type/key): SO em lote rascunho aprovado pelo dono via CLI;
 *  - qualquer padrao de anti-bot na pagina => rede vai para cooldown e o lote aborta.
 */
public class Tools {
    private final Store store;
    private final BatchStore batches;
    private final BrowserPool pool;
    private final Recipes recipes;
    private final ArrayNode defs;
    /** Conjunto de ids capturado por name dentro do lote/acao readOnly em curso. */
    private final Map<String, NewIds.Captura> capturas = new HashMap<>();
    /** Trace passivo de resposta de publicacao por name (opt-in responseProof); fechado no finally do lote/readOnly. */
    private final Map<String, PublishTrace> traces = new HashMap<>();
    /** idNovo provado por verify_new_id (name -> id) no lote em curso, para [[novo:NOME]] no goto. */
    private final Map<String, String> idsNovosLote = new HashMap<>();
    /** Serializa as chamadas MCP com o fechamento de Chromes ociosos (um Chrome por rede). */
    private final Object chamada = new Object();
    /** Fecha Chromes ociosos a cada 60 s (daemon: nao segura a JVM). */
    private final ScheduledExecutorService idle;

    /** Fecha TODOS os traces e limpa o mapa; nao falha o lote se um close falhar. */
    private int fecharTraces() {
        int falhas = 0;
        for (PublishTrace t : traces.values()) {
            try {
                t.close();
            } catch (RuntimeException ignored) {
                falhas++;
            }
        }
        traces.clear();
        if (falhas > 0) {
            try {
                store.appendJournal("trace_cleanup_failed", null, Json.obj()
                        .put("count", falhas).put("error", "falha generica ao remover listener CDP"));
            } catch (RuntimeException ignored) {
                // A falha de journal nao deve interromper a limpeza nem refazer uma acao publica.
            }
        }
        return falhas;
    }

    public Tools(Store store) {
        this.store = store;
        this.batches = new BatchStore(store);
        this.pool = new BrowserPool(store);
        this.recipes = new Recipes(store);
        this.defs = (ArrayNode) Json.parse(TOOL_DEFS);
        this.idle = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "social-mcp-idle");
            t.setDaemon(true);
            return t;
        });
        this.idle.scheduleAtFixedRate(() -> {
            try {
                synchronized (chamada) {
                    List<String> f = pool.closeIdle();
                    if (!f.isEmpty()) System.err.println("[social-mcp] Chrome ocioso fechado: " + f);
                }
            } catch (Exception e) {
                System.err.println("[social-mcp] falha ao fechar Chrome ocioso: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {
        idle.shutdownNow();
        pool.closeAll();
    }

    public ArrayNode definitions() {
        return defs;
    }

    public String call(String name, JsonNode args) throws Exception {
        synchronized (chamada) {
            return switch (name) {
                case "social_status" -> status();
                case "social_browse" -> browse(args);
                case "social_read" -> read(args);
                case "social_inspect" -> inspect(args);
                case "social_query" -> query(args);
                case "social_screenshot" -> screenshot(args);
                case "social_draft_batch" -> draftBatch(args);
                case "social_action" -> action(args);
                case "social_probe" -> probe(args);
                case "social_find_duplicates" -> findDuplicates(args);
                case "social_resolve_incerteza" -> resolverIncerteza(args);
                case "social_execute_batch" -> executeBatch(args);
                case "social_set_network_state" -> setNetworkState(args);
                case "social_journal_tail" -> journalTail(args);
                default -> throw new IllegalArgumentException("tool desconhecida: " + name);
            };
        }
    }

    // ---------- leitura ----------

    private String status() {
        ObjectNode out = Json.obj();
        out.put("killSwitch", store.isStopped());
        out.put("home", store.home.toString());
        out.put("chromesAbertos", pool.openCount());
        ObjectNode nets = out.putObject("redes");
        for (String n : store.knownNetworks()) {
            ObjectNode cfg = store.networkCfg(n);
            Store.Usage u = store.usageFor(n);
            ObjectNode e = nets.putObject(n);
            e.put("estado", store.effectiveState(n));
            String reason = store.stateReason(n);
            if (reason != null) e.put("motivoEstado", reason);
            e.put("startUrl", cfg.path("startUrl").asText(""));
            e.put("acoesPublicasHoje", u.publicActions);
            e.put("cotaDiaria", cfg.path("maxPublicActionsPerDay").asInt(5));
            e.put("minutosEntrePublicas", cfg.path("minMinutesBetweenPublicActions").asInt(30));
            e.put("lotesHoje", u.batches);
            e.put("cotaLotes", cfg.path("maxBatchesPerDay").asInt(4));
            e.put("maxPassosPublicosPorLote", cfg.path("maxPublicStepsPerBatch").asInt(5));
            e.put("ultimaAcaoPublica", u.lastPublicActionMs == 0 ? "nunca" : Instant.ofEpochMilli(u.lastPublicActionMs).toString());
        }
        return Json.writePretty(out);
    }

    private void exigirGuarda(String network, String kind) {
        Guard.Decision dec = Guard.check(store, network, kind);
        if (!dec.allowed()) throw new IllegalStateException(dec.reason());
    }

    /** Acima disso, so os alertas entram na guarda (texto longo fora de alerta nao dispara). */
    static final int LIMITE_TEXTO_GUARDA = 1500;

    /**
     * Texto checado pelo tripwire em browse/read: alertas + texto inteiro se curto
     * (<=1500). Alerta vazio (ex.: script de alertas falhou) usa o texto inteiro
     * como reserva, para a guarda nunca ficar sem nada para checar.
     */
    static String textoParaGuarda(String alerta, String text) {
        String a = alerta == null ? "" : alerta;
        String t = text == null ? "" : text;
        if (a.isBlank()) return t;
        if (t.isEmpty()) return a;
        if (t.length() <= LIMITE_TEXTO_GUARDA) return a + "\n" + t;
        return a;
    }

    private String browse(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        String url = args.path("url").asText("");
        if (url.isBlank()) throw new IllegalArgumentException("url obrigatoria");
        requireNetwork(network);
        exigirGuarda(network, "goto");
        JavaDriverless d = pool.get(network);
        d.get(url);
        d.sleep(1.5);
        int maxChars = args.has("maxChars") && args.get("maxChars").isInt()
                ? args.get("maxChars").asInt()
                : store.networkCfg(network).path("pageTextMaxChars").asInt(12_000);
        String text = BrowserPool.visibleText(d, maxChars);
        store.appendJournal("browse", network, Json.obj().put("url", url));
        ObjectNode out = Json.obj();
        out.put("network", network);
        out.put("estadoRede", store.effectiveState(network));
        out.put("url", safe(() -> d.getCurrentUrl()));
        out.put("title", safe(() -> d.getTitle()));
        String alerta = BrowserPool.alertText(d);
        aplicarTripwire(network, textoParaGuarda(alerta, text), "padrao de anti-bot detectado ao navegar: " + url, out);
        out.put("textoVisivel", text);
        return Json.writePretty(out);
    }

    /**
     * Tripwire em leitura (browse/read): a rede vai para cooldown e a resposta leva o
     * aviso — sem isso, o agente leria o padrao de anti-bot e seguiria executando lotes.
     */
    static void reagirTripwire(Store store, String network, ObjectNode out, String motivo) {
        store.setState(network, "cooldown", motivo, "auto");
        out.put("AVISO", "pagina contem padrao de bloqueio/anti-bot; rede em cooldown. Investigue manualmente antes de qualquer acao.");
    }

    private String read(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        requireNetwork(network);
        exigirGuarda(network, "goto");
        JavaDriverless d = pool.get(network);
        int maxChars = args.has("maxChars") && args.get("maxChars").isInt()
                ? args.get("maxChars").asInt()
                : store.networkCfg(network).path("pageTextMaxChars").asInt(12_000);
        String text = BrowserPool.visibleText(d, maxChars);
        store.appendJournal("read", network, Json.obj());
        ObjectNode out = Json.obj();
        out.put("network", network);
        String url = safe(() -> d.getCurrentUrl());
        out.put("url", url);
        String alerta = BrowserPool.alertText(d);
        aplicarTripwire(network, textoParaGuarda(alerta, text), "padrao de anti-bot detectado na leitura: " + url, out);
        out.put("textoVisivel", text);
        return Json.writePretty(out);
    }

    boolean aplicarTripwire(String network, String text, String motivo, ObjectNode out) {
        ObjectNode cfg = store.networkCfg(network);
        String padrao = Guard.tripwireMatch(text, cfg);
        boolean tripwire = padrao != null;
        out.put("tripwire", tripwire);
        if (tripwire) {
            String contexto = Guard.contextoTripwire(text, padrao);
            String detalhado = motivo + " — padrão \"" + padrao + "\" em \"" + contexto + "\"";
            store.setState(network, "cooldown", detalhado, "auto");
            out.put("AVISO", "pagina contem padrao de bloqueio/anti-bot; rede em cooldown. Investigue manualmente antes de qualquer acao.");
            out.put("tripwirePadrao", padrao);
            out.put("tripwireContexto", contexto);
        }
        return tripwire;
    }

    /**
     * social_inspect: controles interativos visiveis da pagina atual, para
     * achar onde digitar/clicar quando o texto visivel nao basta (ex.: Nome
     * da Pagina e categoria no criar Pagina do Facebook). Somente leitura:
     * mesma guarda do social_read (nao e acao publica, nao gasta cota, nao
     * pede aprovacao) e journal analogo. Nunca devolve valores digitados.
     */
    private String inspect(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        requireNetwork(network);
        exigirGuarda(network, "goto");
        JavaDriverless d = pool.get(network);
        int max = Controls.clampMaxControls(args);
        Object raw;
        try {
            raw = d.executeScript(Controls.inspectScript(max));
        } catch (Exception e) {
            throw new IllegalStateException("inspect falhou no navegador: " + e.getMessage(), e);
        }
        JsonNode bruto;
        try {
            bruto = Json.parse(raw == null ? "{\"controls\":[]}" : String.valueOf(raw));
        } catch (RuntimeException e) {
            bruto = Json.parse("{\"controls\":[]}");
        }
        ObjectNode out = Controls.sanitizeResult(network, safe(() -> d.getCurrentUrl()), bruto, max);
        store.appendJournal("inspect", network,
                Json.obj().put("maxControls", max).put("controles", out.path("controles").size()));
        return Json.writePretty(out);
    }

    /**
     * social_query: consulta de DOM so leitura na pagina atual da rede — conta quantos
     * elementos casam o seletor e devolve atributos-chave e caminho de ancestrais dos
     * primeiros N. Mesma guarda do social_inspect (nao e acao publica, nao gasta cota,
     * nao pede aprovacao) e journal analogo. Nunca clica nem le valor de campo.
     */
    private String query(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        String selector = args.path("selector").asText("");
        if (network.isBlank()) throw new IllegalArgumentException("network obrigatoria");
        if (selector.isBlank()) throw new IllegalArgumentException("selector obrigatorio");
        int max = 5;
        if (args.has("max") && args.get("max").isInt()) {
            max = args.get("max").asInt();
        }
        max = Math.min(20, Math.max(1, max));
        requireNetwork(network);
        exigirGuarda(network, "goto");
        JavaDriverless d = pool.get(network);
        Object raw;
        try {
            raw = d.executeScript(ShadowDom.queryScript(selector, max));
        } catch (Exception e) {
            throw new IllegalStateException("query falhou no navegador: " + e.getMessage(), e);
        }
        JsonNode bruto;
        try {
            bruto = Json.parse(raw == null ? "{\"erro\":\"resposta vazia do navegador\"}" : String.valueOf(raw));
        } catch (RuntimeException e) {
            bruto = Json.obj().put("erro", "resposta invalida do navegador");
        }
        int total = bruto.path("total").asInt(0);
        ObjectNode out = Json.obj();
        out.put("network", network);
        out.put("url", safe(() -> d.getCurrentUrl()));
        out.put("selector", selector);
        if (bruto.has("erro")) {
            out.put("erro", bruto.path("erro").asText(""));
        } else {
            out.put("total", total);
            JsonNode itens = bruto.path("itens");
            out.set("itens", itens.isArray() ? itens : Json.M.createArrayNode());
        }
        store.appendJournal("query", network, Json.obj().put("selector", selector).put("total", total));
        return Json.writePretty(out);
    }

    private String screenshot(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        requireNetwork(network);
        exigirGuarda(network, "screenshot");
        String f = saveScreenshot(network);
        store.appendJournal("screenshot", network, Json.obj().put("arquivo", f));
        return "screenshot salvo em: " + f;
    }

    /** PNG da tela atual em SOCIAL_MCP_HOME/screenshots/; devolve o caminho. */
    private String saveScreenshot(String network) {
        String f = store.screenshotsDir().resolve(System.currentTimeMillis() + "-" + network + ".png").toString();
        pool.get(network).screenshot(f);
        return f;
    }

    // ---------- lotes ----------

    private String draftBatch(JsonNode args) throws Exception {
        return draftResponse(args.path("label").asText(""), args.get("actions"));
    }

    private String draftResponse(String label, JsonNode actions) throws Exception {
        return draftResponse(label, actions, null);
    }

    /** origem (receita + provada) so vem de social_action; rascunho manual usa null. Resposta igual. */
    private String draftResponse(String label, JsonNode actions, ObjectNode origem) throws Exception {
        JsonNode batch = origem == null ? batches.draft(label, actions) : batches.draft(label, actions, origem);
        String id = batch.get("id").asText();

        // preflight: mostra o que a guarda diria HOJE (pode mudar ate a aprovacao)
        ArrayNode pre = Json.M.createArrayNode();
        int i = 1;
        for (JsonNode a : actions) {
            ObjectNode p = pre.addObject();
            p.put("passo", i++);
            p.put("network", a.path("network").asText());
            p.put("kind", a.path("kind").asText());
            Guard.Decision dec = Guard.check(store, a.path("network").asText(), a.path("kind").asText(), id);
            p.put("guarda", dec.toString());
        }
        ObjectNode out = Json.obj();
        out.put("batchId", id);
        out.put("status", "draft");
        out.set("preflight", pre);
        String conf = BatchStore.conferencia(actions);
        out.put("conferencia", conf);
        if (BatchStore.CONF_FRACA.equals(conf) || BatchStore.CONF_AUSENTE.equals(conf)) {
            out.put("AVISO_CONFERENCIA", "depois do ultimo passo publico o lote nao prova o resultado ("
                    + conf + "): para criacao/resposta, capture_ids antes do 1o passo publico + verify_new_id depois = conferencia forte."
                    + " Senao, acrescente verify_attr, ou verify_text/wait com selector, no que so existe depois do envio"
                    + " (verify_absent sozinho nao basta: pareie com um verify com selector da mesma pagina)."
                    + " Sem isso o resumo da execucao sai com conferencia=" + conf + " e AVISO.");
        }
        out.put("PROXIMO_PASSO", "Mostre o lote completo ao aprovador unico (alvos + textos exatos): o dono OU o orquestrador ativo que esta vendo. Sem orquestrador vendo, espera aprovacao humana. Configure um gate de texto externo em defaults.gate.command e aprove no terminal com bin/social-mcp --cli approve " + id + " orquestrador \"nota\" --fatos ficha.md; so depois chame social_execute_batch.");
        return Json.writePretty(out);
    }

    // ---------- acoes de alto nivel (receitas) ----------

    private String action(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        String action = args.path("action").asText("");
        requireNetwork(network);
        Recipes.Plan plan = recipes.prepare(network, action, args.get("params"));
        String state = store.effectiveState(network);
        validateActionState(state, plan.readOnly());
        if (!plan.readOnly()) {
            String label = args.path("label").asText("");
            ObjectNode origem = Json.obj().put("receita", plan.action()).put("provada", plan.provada());
            return draftResponse(label.isBlank() ? plan.label() : label, plan.steps(), origem);
        }
        return runReadOnly(plan);
    }

    static void validateActionState(String state, boolean readOnly) {
        if ("healthy".equals(state) || (readOnly && ("cooldown".equals(state) || "paused".equals(state)))) {
            return;
        }
        throw new IllegalStateException("social_action " + (readOnly ? "readOnly" : "publica")
                + " nao permitida em rede estado '" + state + "'");
    }

    /** Acao readOnly: executa na hora, sem lote; devolve extracoes e verificacoes. */
    private String runReadOnly(Recipes.Plan plan) throws Exception {
        String network = plan.network();
        Guard.Decision dec = Guard.check(store, network, "goto");
        if (!dec.allowed()) throw new IllegalStateException(dec.reason());
        ArrayNode results = Json.M.createArrayNode();
        ArrayNode urls = Json.M.createArrayNode();
        String erro = null;
        boolean trip = false;
        int n = 1;
        prazoFimMs = System.currentTimeMillis() + PRAZO_LOTE_MS;
        capturas.clear();
        idsNovosLote.clear();
        int traceCleanupFailures = fecharTraces();
        try {
            for (JsonNode a : plan.steps()) {
                String kind = a.path("kind").asText();
                ObjectNode step = results.addObject();
                step.put("passo", n++);
                step.put("kind", kind);
                if (a.has("url")) urls.add(a.path("url").asText());
                try {
                    String info = runStep(network, kind, a, step);
                    step.put("ok", true);
                    if (info != null) step.put("info", info);
                } catch (Exception e) {
                    step.put("ok", false);
                    step.put("erro", String.valueOf(e.getMessage()));
                    erro = "passo " + (n - 1) + " falhou: " + e.getMessage();
                }
                String sample = BrowserPool.visibleText(pool.get(network), 4000);
                ObjectNode cfgTw = store.networkCfg(network);
                String padraoTw = Guard.tripwireMatch(sample, cfgTw);
                String fonteTw = sample;
                if (padraoTw == null) {
                    String snippetTw = step.path("bodySnippet").asText("");
                    padraoTw = Guard.tripwireMatch(snippetTw, cfgTw);
                    fonteTw = snippetTw;
                }
                if (padraoTw != null) {
                    String contextoTw = Guard.contextoTripwire(fonteTw, padraoTw);
                    store.setState(network, "cooldown", "padrao de anti-bot na acao readOnly " + plan.action()
                            + " — padrão \"" + padraoTw + "\" em \"" + contextoTw + "\"", "auto");
                    store.appendJournal("tripwire", network, Json.obj().put("action", plan.action())
                            .put("padrao", padraoTw).put("contexto", contextoTw));
                    trip = true;
                    step.put("tripwire", true);
                    break;
                }
                if (erro != null) break;
            }
        } finally {
            prazoFimMs = Long.MAX_VALUE;
            capturas.clear();
            idsNovosLote.clear();
            traceCleanupFailures += fecharTraces();
        }
        store.appendJournal("browse", network, Json.obj().put("action", plan.action()).put("ok", erro == null && !trip).set("urls", urls));
        ObjectNode out = Json.obj();
        out.put("network", network);
        out.put("action", plan.action());
        out.put("readOnly", true);
        out.put("ok", erro == null && !trip);
        if (erro != null) out.put("motivo", erro);
        out.put("tripwire", trip);
        if (trip) out.put("AVISO", "pagina contem padrao de bloqueio/anti-bot; rede em cooldown. Investigue manualmente antes de qualquer acao.");
        if (traceCleanupFailures > 0) {
            String aviso = "limpeza de " + traceCleanupFailures
                    + " trace(s) falhou; consulte trace_cleanup_failed no journal.";
            String anterior = out.path("AVISO").asText("");
            out.put("AVISO", anterior.isEmpty() ? aviso : anterior + " " + aviso);
        }
        ObjectNode idsNovos = Json.obj();
        for (JsonNode st : results) {
            if ("verify_new_id".equals(st.path("kind").asText("")) && st.path("ok").asBoolean(false) && st.has("idNovo")) {
                idsNovos.put(st.path("name").asText(""), st.path("idNovo").asText(""));
            }
        }
        if (!idsNovos.isEmpty()) out.set("idsNovos", idsNovos);
        out.set("passos", results);
        return Json.writePretty(out);
    }

    /** Passos que a sonda executa: so leitura do inicio da receita, nunca acao. */
    static boolean probeExecuta(String kind) {
        return "goto".equals(kind) || "wait".equals(kind) || "verify_text".equals(kind)
                || "verify_attr".equals(kind) || "capture_ids".equals(kind);
    }

    /**
     * social_probe: sonda de receita (so leitura). Executa os passos de leitura do inicio da
     * acao e confere se o primeiro controle de acao ainda existe na pagina, sem clicar,
     * digitar, enviar arquivo ou fazer request. Nunca gasta cota publica.
     */
    private String probe(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        String action = args.path("action").asText("");
        requireNetwork(network);
        JsonNode params = args.path("params");
        ArrayNode passos = Json.M.createArrayNode();
        ObjectNode primeiroControle = null;
        String primeiroSelector = "";
        String erro = null;
        boolean trip = false;
        String padraoTrip = null;
        String falhaPreparo = null;
        try {
            Recipes.Plan plan = null;
            try {
                JsonNode p = params;
                if (p == null || p.isMissingNode() || p.isNull() || (p.isObject() && p.isEmpty())) {
                    JsonNode recipe = recipes.load(network);
                    p = RecipeValidator.exemploParams(store, recipe, recipe.path("actions").path(action));
                }
                plan = recipes.prepare(network, action, p);
            } catch (RuntimeException e) {
                falhaPreparo = String.valueOf(e.getMessage());
            }
            String motivo;
            if (falhaPreparo != null) {
                motivo = falhaPreparo;
            } else {
                String state = store.effectiveState(network);
                validateActionState(state, true);
                Guard.Decision dec = Guard.check(store, network, "goto");
                if (!dec.allowed()) throw new IllegalStateException(dec.reason());
                int n = 1;
                for (JsonNode a : plan.steps()) {
                    String kind = a.path("kind").asText("");
                    if (!probeExecuta(kind)) {
                        primeiroControle = Json.obj();
                        primeiroSelector = a.path("selector").asText("");
                        primeiroControle.put("passo", n);
                        primeiroControle.put("kind", kind);
                        primeiroControle.put("selector", primeiroSelector);
                        if (primeiroSelector.isBlank()) {
                            primeiroControle.putNull("presente");
                        } else {
                            try {
                                primeiroControle.put("presente",
                                        presente(pool.get(network)::executeScript, primeiroSelector));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "sonda interrompida ao conferir '" + primeiroSelector + "'", ie);
                            }
                        }
                        break;
                    }
                    ObjectNode step = passos.addObject();
                    step.put("passo", n);
                    step.put("kind", kind);
                    try {
                        String info = runStep(network, kind, a, step);
                        step.put("ok", true);
                        if (info != null) step.put("info", info);
                    } catch (Exception e) {
                        step.put("ok", false);
                        step.put("erro", String.valueOf(e.getMessage()));
                        erro = "passo " + n + " falhou: " + e.getMessage();
                    }
                    String sample = BrowserPool.visibleText(pool.get(network), 4000);
                    ObjectNode cfgTw = store.networkCfg(network);
                    String padraoTw = Guard.tripwireMatch(sample, cfgTw);
                    String fonteTw = sample;
                    if (padraoTw == null) {
                        String snippetTw = step.path("bodySnippet").asText("");
                        padraoTw = Guard.tripwireMatch(snippetTw, cfgTw);
                        fonteTw = snippetTw;
                    }
                    if (padraoTw != null) {
                        String contextoTw = Guard.contextoTripwire(fonteTw, padraoTw);
                        store.setState(network, "cooldown", "padrao de anti-bot na sonda " + action
                                + " — padrão \"" + padraoTw + "\" em \"" + contextoTw + "\"", "auto");
                        store.appendJournal("tripwire", network, Json.obj().put("action", action)
                                .put("padrao", padraoTw).put("contexto", contextoTw));
                        trip = true;
                        padraoTrip = padraoTw;
                        step.put("tripwire", true);
                        break;
                    }
                    if (erro != null) break;
                    n++;
                }
                boolean controleOk =
                        primeiroControle == null || primeiroControle.path("presente").asBoolean(true);
                if (trip) {
                    motivo = "tripwire: " + padraoTrip;
                } else if (erro != null) {
                    motivo = erro;
                } else if (!controleOk) {
                    motivo = "controle ausente: " + primeiroSelector;
                } else {
                    motivo = "receita viva";
                }
            }
            boolean ok = falhaPreparo == null && erro == null && !trip
                    && (primeiroControle == null || primeiroControle.path("presente").asBoolean(true));
            store.appendJournal("probe", network,
                    Json.obj().put("action", action).put("ok", ok).put("motivo", motivo));
            ObjectNode out = Json.obj();
            out.put("network", network);
            out.put("action", action);
            out.put("ok", ok);
            out.put("motivo", motivo);
            out.set("passos", passos);
            if (primeiroControle != null) out.set("primeiroControle", primeiroControle);
            else out.putNull("primeiroControle");
            out.put("tripwire", trip);
            return Json.writePretty(out);
        } finally {
            try {
                if (store.networkCfg(network).path("closeAfterBatch").asBoolean(true)) {
                    pool.close(network);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * social_find_duplicates: somente leitura, sem cota. Lista os posts do perfil
     * (receita list_posts — OBRIGATORIAMENTE readOnly), abre cada um, tira o texto de
     * interface comum e agrupa legendas parecidas (Jaccard de shingles). Nunca clica,
     * digita ou publica.
     */
    private String findDuplicates(JsonNode args) throws Exception {
        String network = args.path("network").asText("");
        requireNetwork(network);
        int maxPosts = args.path("maxPosts").asInt(12);
        maxPosts = Math.min(30, Math.max(1, maxPosts));
        double limiar = args.path("limiar").asDouble(0.6);
        if (Double.isNaN(limiar)) limiar = 0.6;
        limiar = Math.min(0.95, Math.max(0.3, limiar));
        Recipes.Plan plan = recipes.prepare(network, "list_posts", Json.obj());
        if (!plan.readOnly()) {
            // runReadOnly executa os passos na hora: sem essa recusa, uma receita list_posts
            // sem readOnly rodaria cliques/requests sem lote, aprovacao ou guarda publica
            throw new IllegalStateException("receita list_posts de '" + network + "' nao declara readOnly:"
                    + " social_find_duplicates so executa receita de leitura");
        }
        validateActionState(store.effectiveState(network), true);
        JsonNode lista = Json.parse(runReadOnly(plan));
        if (!lista.path("ok").asBoolean(false)) {
            ObjectNode fora = Json.obj();
            fora.put("network", network);
            fora.put("ok", false);
            fora.put("motivo", lista.path("motivo").asText(""));
            fora.put("tripwire", lista.path("tripwire").asBoolean(false));
            return Json.writePretty(fora);
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (JsonNode passo : lista.path("passos")) {
            JsonNode extraido = passo.path("extraido");
            if (!extraido.isArray()) continue;
            for (JsonNode item : extraido) {
                String cand;
                if (item.isTextual()) {
                    cand = item.asText();
                } else if (item.isObject()) {
                    cand = item.path("href").asText("");
                    if (cand.isBlank()) cand = item.path("url").asText("");
                } else {
                    cand = item.asText("");
                }
                if (cand == null) continue;
                cand = cand.trim();
                int hash = cand.indexOf('#');
                if (hash >= 0) cand = cand.substring(0, hash);
                if (cand.startsWith("http://") || cand.startsWith("https://")) urls.add(cand);
                if (urls.size() >= maxPosts) break;
            }
            if (urls.size() >= maxPosts) break;
        }
        // itemTexto (opcional na receita list_posts): compara so o texto do item da URL, nao a
        // pagina inteira. Ex.: permalink de comentario do Reddit abre a thread toda e comentarios
        // diferentes na mesma thread pareciam duplicados.
        JsonNode itemTexto = recipes.load(network).path("actions").path("list_posts").path("itemTexto");
        String itemIdRegex = itemTexto.path("idRegex").asText("");
        String itemModelo = itemTexto.path("selector").asText("");
        boolean porItem = itemTexto.isObject();
        List<Duplicates.Pagina> paginas = new ArrayList<>();
        ArrayNode falhas = Json.M.createArrayNode();
        ObjectNode tmp = Json.obj();
        boolean tripwire = false;
        for (String url : urls) {
            Guard.Decision dec = Guard.check(store, network, "goto");
            if (!dec.allowed()) {
                falhas.addObject().put("url", url).put("erro", dec.reason());
                break;
            }
            try {
                JavaDriverless d = pool.get(network);
                d.get(url);
                d.sleep(2.0 + Math.random() * 2.0);
                int maxChars = store.networkCfg(network).path("pageTextMaxChars").asInt(12_000);
                String texto = BrowserPool.visibleText(d, maxChars);
                if (aplicarTripwire(network, textoParaGuarda(BrowserPool.alertText(d), texto),
                        "padrao de anti-bot no social_find_duplicates: " + url, tmp)) {
                    tripwire = true;
                    break;
                }
                if (porItem) {
                    String sel = Duplicates.seletorDoItem(url, itemIdRegex, itemModelo);
                    if (sel == null) {
                        falhas.addObject().put("url", url).put("erro", "itemTexto: URL sem id do item");
                        continue;
                    }
                    String doItem = null;
                    for (int tentativa = 1; tentativa <= VERIFY_TENTATIVAS && doItem == null; tentativa++) {
                        try {
                            doItem = RealClick.text(el(d, sel));
                        } catch (Exception e) {
                            if (tentativa < VERIFY_TENTATIVAS) Thread.sleep(1_000);
                        }
                    }
                    if (doItem == null) {
                        // sem o item, a pagina inteira daria falso positivo: fica de fora e avisa
                        falhas.addObject().put("url", url).put("erro", "itemTexto: item nao encontrado (" + sel + ")");
                        continue;
                    }
                    texto = doItem;
                }
                paginas.add(new Duplicates.Pagina(url, texto));
            } catch (Exception e) {
                falhas.addObject().put("url", url).put("erro", String.valueOf(e.getMessage()));
            }
        }
        if (tripwire) {
            ObjectNode out = Json.obj();
            out.put("network", network);
            out.put("ok", false);
            out.put("readOnly", true);
            out.put("analisados", paginas.size());
            out.put("limiar", limiar);
            out.putArray("duplicados");
            out.set("falhas", falhas);
            out.put("tripwire", true);
            out.put("AVISO", tmp.path("AVISO").asText(
                    "pagina contem padrao de bloqueio/anti-bot; rede em cooldown. Investigue manualmente antes de qualquer acao."));
            if (tmp.has("tripwirePadrao")) out.put("tripwirePadrao", tmp.path("tripwirePadrao").asText(""));
            if (tmp.has("tripwireContexto")) out.put("tripwireContexto", tmp.path("tripwireContexto").asText(""));
            return Json.writePretty(out);
        }
        List<Duplicates.Grupo> grupos = Duplicates.agrupar(paginas, limiar);
        store.appendJournal("duplicates_check", network,
                Json.obj().put("analisados", paginas.size()).put("grupos", grupos.size()));
        ObjectNode out = Json.obj();
        out.put("network", network);
        out.put("ok", true);
        out.put("readOnly", true);
        out.put("analisados", paginas.size());
        out.put("limiar", limiar);
        ArrayNode dups = out.putArray("duplicados");
        for (Duplicates.Grupo g : grupos) {
            ObjectNode e = dups.addObject();
            e.put("similaridade", g.similaridade());
            ArrayNode posts = e.putArray("posts");
            for (int i = 0; i < g.urls().size(); i++) {
                posts.addObject().put("url", g.urls().get(i)).put("inicio", g.inicios().get(i));
            }
        }
        out.set("falhas", falhas);
        out.put("tripwire", false);
        return Json.writePretty(out);
    }

    /** Resultados aceitos na conferencia NA REDE registrada pelo social_resolve_incerteza. */
    static final Set<String> RESULTADOS_INCERTEZA = Set.of("no_ar", "nao_saiu", "apagado");

    /**
     * social_resolve_incerteza: registra o que a conferencia NA REDE mostrou para um lote com
     * envio incerto ou duplicata suspeita e libera a trava da rede (evento incerteza_resolvida
     * no journal). Nunca publica nem apaga nada: prova e o que foi VISTO na rede.
     */
    private String resolverIncerteza(JsonNode args) throws Exception {
        String batchId = args.path("batchId").asText("");
        if (!batchId.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("batchId invalido: '" + batchId + "'");
        }
        java.nio.file.Path executed = store.batchesDir().resolve(batchId + ".executed.json");
        if (!java.nio.file.Files.isRegularFile(executed)) {
            throw new IllegalArgumentException("lote " + batchId + " nao esta executado (sem "
                    + executed.getFileName() + " em batches/)");
        }
        JsonNode b;
        try {
            b = Json.parse(java.nio.file.Files.readString(executed, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("lote " + batchId + " ilegivel: " + e.getMessage());
        }
        String network = b.path("network").asText(b.path("actions").path(0).path("network").asText(""));
        String conf = b.path("result").path("conferencia").asText("");
        if (!Store.CONFERENCIAS_INCERTAS.contains(conf)) {
            throw new IllegalArgumentException("lote " + batchId + " nao tem envio incerto nem duplicata suspeita"
                    + " (conferencia=" + conf + "); nao precisa de resolucao");
        }
        String resultado = args.path("resultado").asText("");
        if (!RESULTADOS_INCERTEZA.contains(resultado)) {
            throw new IllegalArgumentException("resultado invalido '" + resultado + "' (use no_ar|nao_saiu|apagado)");
        }
        String prova = args.path("prova").asText("");
        if (prova.trim().length() < 8) {
            throw new IllegalArgumentException("prova obrigatoria com pelo menos 8 caracteres depois do trim:"
                    + " URL/id do post no ar, ou descricao objetiva da leitura que mostrou que nao saiu/foi apagado");
        }
        if (!store.incertezasPendentes(network).contains(batchId)) {
            throw new IllegalArgumentException("lote " + batchId + " ja foi resolvido (ou e anterior a travaIncertezaDesde)");
        }
        store.appendJournal("incerteza_resolvida", network, Json.obj()
                .put("batchId", batchId)
                .put("resultado", resultado)
                .put("prova", prova.trim())
                .put("nota", args.path("nota").asText("")));
        ObjectNode out = Json.obj();
        out.put("batchId", batchId);
        out.put("network", network);
        out.put("resultado", resultado);
        ArrayNode pendentes = out.putArray("pendentesNaRede");
        for (String p : store.incertezasPendentes(network)) pendentes.add(p);
        return Json.writePretty(out);
    }

    /**
     * Prazo da execucao de um lote (e da acao readOnly): o lote roda numa unica chamada MCP e
     * o cliente corta antes (Codex: tool_timeout_sec = 900). Passado o prazo, o lote aborta
     * antes do proximo passo e os waits encurtam para caber.
     */
    static final long PRAZO_LOTE_MS = 840_000;
    /** Fim do prazo da execucao em curso (servidor stdio e de uma chamada por vez). */
    private long prazoFimMs = Long.MAX_VALUE;

    private String executeBatch(JsonNode args) throws Exception {
        String id = args.path("batchId").asText("");
        JsonNode previa = batches.requireApproved(id); // lança se nao aprovado
        String netPrevia = previa.path("network").asText("");
        if (!netPrevia.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("lote " + id + " sem rede valida");
        // varios clientes sobem o MCP por stdio: um lote por rede por vez em toda a maquina
        try (Store.Trava trava = store.tryLock("exec-" + netPrevia)) {
            try {
                if (trava == null) {
                    throw new IllegalStateException("outro processo do MCP esta executando um lote de '" + netPrevia
                            + "' agora; tente depois que ele terminar (cota e espacamento valem entre processos)");
                }
                return executeLocked(id);
            } finally {
                // libera o Chrome da rede apos o lote (o proximo uso reabre): erro no
                // fechamento nunca troca o resultado nem a excecao original do lote
                if (trava != null) {
                    try {
                        if (store.networkCfg(netPrevia).path("closeAfterBatch").asBoolean(true)) {
                            pool.close(netPrevia);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private String executeLocked(String id) throws Exception {
        JsonNode batch = batches.requireApproved(id); // de novo, ja sob a trava
        if (store.batchStarted(id)) { // journal ja tem action deste lote: execucao parcial ou completa
            throw new IllegalStateException("lote " + id + " ja foi iniciado antes; lote nao reexecuta. Crie um novo rascunho e peca nova aprovacao.");
        }
        JsonNode actions = batch.get("actions");

        // revalida regras novas em lote ja aprovado (aprovado antes da regra mudar
        // ou config reduzida depois); a rede validada e a fonte para toda a execucao
        String net = batches.validateBatchRules(actions);
        if (!net.equals(batch.path("network").asText(""))) {
            throw new IllegalStateException("lote " + id + ": rede das acoes difere da rede do lote");
        }

        if (store.isStopped()) {
            throw new IllegalStateException("kill-switch ativo; lote nao executado. O dono decide: bin/social-mcp --cli stop off");
        }
        // limite de lotes por dia e POR REDE (cada agente cuida de uma rede)
        int maxBatches = store.networkCfg(net).path("maxBatchesPerDay").asInt(4);
        int feitos = store.usageFor(net).batches;
        if (feitos >= maxBatches) {
            throw new IllegalStateException("cota diaria de lotes de '" + net + "' esgotada (" + feitos + "/" + maxBatches + "). Volte amanha.");
        }

        // fail-fast: valida cota, espacamento e estado da rede do lote. Cota e
        // espacamento contam LOTES: o proprio lote (id) nao se bloqueia.
        boolean temPassoPublico = false;
        for (JsonNode a : actions) {
            if (Guard.isPublic(a.path("kind").asText())) {
                temPassoPublico = true;
                break;
            }
        }
        if (temPassoPublico) {
            // mesma trava do Guard.check, aqui antes do claim: o lote nem e reservado
            List<String> pendentes = store.incertezasPendentes(net);
            if (!pendentes.isEmpty()) {
                throw new IllegalStateException(Guard.motivoTravaIncerteza(net, pendentes));
            }
            Store.Usage u = store.usageFor(net, id);
            int maxPub = store.networkCfg(net).path("maxPublicActionsPerDay").asInt(5);
            if (u.publicActions + 1 > maxPub) {
                throw new IllegalStateException("lote excede a cota diaria de '" + net + "': " + u.publicActions
                        + " usadas + 1 do lote > " + maxPub);
            }
            if (u.lastPublicActionMs > 0) {
                long need = store.networkCfg(net).path("minMinutesBetweenPublicActions").asInt(30) * 60_000L;
                long falta = need - (System.currentTimeMillis() - u.lastPublicActionMs);
                if (falta > 0) {
                    throw new IllegalStateException("espacamento de '" + net + "' insuficiente; proxima acao publica liberada em ~"
                            + ((falta + 59_999) / 60_000) + " min");
                }
            }
            String state = store.effectiveState(net);
            if (!"healthy".equals(state)) {
                throw new IllegalStateException("rede '" + net + "' esta '" + state + "'; reexecute depois que o dono liberar");
            }
        }

        batch = batches.claim(id, batch);
        actions = batch.get("actions");
        ArrayNode results = Json.M.createArrayNode();
        int done = 0;
        boolean publicoFeito = false;
        boolean envioFeito = false;
        boolean duplicataSuspeita = false;
        boolean textoDivergente = false;
        String abortReason = null;
        long inicio = System.currentTimeMillis();
        prazoFimMs = inicio + PRAZO_LOTE_MS;
        capturas.clear();
        idsNovosLote.clear();
        int traceCleanupFailures = fecharTraces();
        try {
            for (int passoIdx = 0; passoIdx < actions.size(); passoIdx++) {
                JsonNode a = actions.get(passoIdx);
                String network = a.path("network").asText();
                String kind = a.path("kind").asText();
                if (System.currentTimeMillis() >= prazoFimMs) {
                    abortReason = "prazo do lote (" + (PRAZO_LOTE_MS / 1000) + " s) esgotado antes do passo " + (done + 1)
                            + "; a chamada MCP seria cortada pelo cliente";
                    break;
                }
                Guard.Decision dec = Guard.check(store, network, kind, id);
                if (!dec.allowed()) {
                    abortReason = "guarda bloqueou o passo " + (done + 1) + " (" + network + "/" + kind + "): " + dec.reason();
                    break;
                }
                ObjectNode step = Json.obj();
                step.put("passo", done + 1);
                step.put("network", network);
                step.put("kind", kind);
                step.put("descricao", a.path("description").asText(""));
                try {
                    String info = runStep(network, kind, a, step, batch, passoIdx);
                    step.put("ok", true);
                    if (info != null) step.put("info", info);
                    if ("verify_new_id".equals(kind) && !step.path("idNovo").asText("").isEmpty()) {
                        idsNovosLote.put(a.path("name").asText(""), step.path("idNovo").asText());
                    }
                    journalAction(store, network, kind, a, id, step, true);
                    if (Guard.isPublic(kind) && !step.path("pulado").asBoolean(false)) publicoFeito = true;
                    // envio de fato (ex.: botao Publicar), nao clique de foco/composer; se pulado (ifPresent
                    // ausente) nao houve tentativa; verify_new_id com 2+ itens novos = possivel publicacao em dobro
                    if (ehEnvio(actions, passoIdx) && !step.path("pulado").asBoolean(false)) envioFeito = true;
                    if ("verify_new_id".equals(kind)) {
                        if (step.path("outrosNovos").asInt(0) > 0) duplicataSuspeita = true;
                        if (step.path("textoDivergente").asBoolean(false)) textoDivergente = true;
                    }
                    String sample = BrowserPool.visibleText(pool.get(network), 4000);
                    ObjectNode cfgTw2 = store.networkCfg(network);
                    String padraoTw2 = Guard.tripwireMatch(sample, cfgTw2);
                    String fonteTw2 = sample;
                    if (padraoTw2 == null) {
                        String snippetTw2 = step.path("bodySnippet").asText("");
                        padraoTw2 = Guard.tripwireMatch(snippetTw2, cfgTw2);
                        fonteTw2 = snippetTw2;
                    }
                    if (padraoTw2 != null) {
                        String contextoTw2 = Guard.contextoTripwire(fonteTw2, padraoTw2);
                        store.setState(network, "cooldown", "padrao de anti-bot apos o passo " + (done + 1) + " do lote " + id
                                + " — padrão \"" + padraoTw2 + "\" em \"" + contextoTw2 + "\"", "auto");
                        store.appendJournal("tripwire", network, Json.obj().put("batchId", id).put("day", store.today())
                                .put("padrao", padraoTw2).put("contexto", contextoTw2));
                        step.put("tripwire", true);
                        results.add(step);
                        done++;
                        abortReason = "padrao de anti-bot detectado apos o passo " + done + "; rede '" + network
                                + "' em cooldown; passos restantes cancelados. NAO tente de novo nesta rede hoje.";
                        break;
                    }
                    step.put("tripwire", false);
                } catch (Exception e) {
                    step.put("ok", false);
                    step.put("erro", String.valueOf(e.getMessage()));
                    // clique/digitacao/tecla que falharam DEPOIS de despachar podem ter agido:
                    // toda tentativa reconhecida por talvezAgiu vai ao journal (cota/espacamento).
                    // NaoAgiu = falha garantidamente antes do efeito, nao conta.
                    if (talvezAgiu(kind, e, step)) {
                        publicoFeito = true;
                        if (ehEnvio(actions, passoIdx)) envioFeito = true;
                        journalAction(store, network, kind, a, id, step, false);
                    }
                    if ("request".equals(kind) && (step.path("status").asInt(0) > 0 || step.path("incerto").asBoolean(false))) {
                        String snippetTw3 = step.path("bodySnippet").asText("");
                        String padraoTw3 = Guard.tripwireMatch(snippetTw3, store.networkCfg(network));
                        if (padraoTw3 != null) {
                            String contextoTw3 = Guard.contextoTripwire(snippetTw3, padraoTw3);
                            store.setState(network, "cooldown", "padrao de anti-bot na resposta do passo " + (done + 1) + " do lote " + id
                                    + " — padrão \"" + padraoTw3 + "\" em \"" + contextoTw3 + "\"", "auto");
                            store.appendJournal("tripwire", network, Json.obj().put("batchId", id).put("day", store.today())
                                    .put("padrao", padraoTw3).put("contexto", contextoTw3));
                            step.put("tripwire", true);
                        }
                    }
                    results.add(step);
                    abortReason = "passo " + (done + 1) + " falhou: " + e.getMessage();
                    break;
                }
                results.add(step);
                done++;
                if (done < actions.size()) sleepJitter(network);
            }
        } catch (RuntimeException e) {
            // erro fora do passo (journal, guarda, navegador): o lote reservado ainda vira executed
            abortReason = "erro interno no passo " + (done + 1) + ": " + e.getMessage();
        } finally {
            prazoFimMs = Long.MAX_VALUE;
            capturas.clear();
            idsNovosLote.clear();
            traceCleanupFailures += fecharTraces();
        }

        ObjectNode summary = Json.obj();
        summary.put("batchId", id);
        summary.put("passosExecutados", done);
        summary.put("passosTotais", actions.size());
        summary.put("abortado", abortReason != null);
        if (abortReason != null) summary.put("motivo", abortReason);
        String conf = conferenciaFinal(actions, abortReason != null, publicoFeito, envioFeito, duplicataSuspeita, textoDivergente);
        summary.put("conferencia", conf);
        if (publicoFeito && !BatchStore.CONF_FORTE.equals(conf)) {
            summary.put("AVISO", avisoConferencia(conf));
        }
        if (traceCleanupFailures > 0) {
            String avisoCleanup = "limpeza de " + traceCleanupFailures
                    + " trace(s) falhou; o resultado dos passos executados foi preservado e pode exigir inspecao. Consulte trace_cleanup_failed no journal.";
            String avisoAnterior = summary.path("AVISO").asText("");
            summary.put("AVISO", avisoAnterior.isEmpty() ? avisoCleanup : avisoAnterior + " " + avisoCleanup);
        }
        summary.put("duracaoMs", System.currentTimeMillis() - inicio);
        ObjectNode idsNovos = Json.obj();
        for (JsonNode st : results) {
            if ("verify_new_id".equals(st.path("kind").asText("")) && st.path("ok").asBoolean(false) && st.has("idNovo")) {
                idsNovos.put(st.path("name").asText(""), st.path("idNovo").asText(""));
            }
        }
        if (!idsNovos.isEmpty()) summary.set("idsNovos", idsNovos);
        summary.set("passos", results);
        batches.markExecuted(id, summary);
        return Json.writePretty(summary);
    }

    /**
     * Passo publico que falhou pode ter agido (ex.: timeout do CDP depois do mouseReleased): conservador,
     * exceto falha garantidamente antes do efeito (RealClick.NaoAgiu) e request que nem saiu (status <= 0, nao incerto).
     */
    static boolean talvezAgiu(String kind, Exception e, JsonNode step) {
        if (!Guard.isPublic(kind) || e instanceof RealClick.NaoAgiu) return false;
        if ("request".equals(kind)) return step.path("status").asInt(0) > 0 || step.path("incerto").asBoolean(false);
        return true;
    }

    /** O clique ja ocorreu: foco errado nao prova ausencia de efeito publico. */
    static RuntimeException falhaFocoAposClique(String sel) {
        return new IllegalStateException("type: depois do clique o foco nao esta em '" + sel
                + "'; nada foi digitado, mas o clique pode ter tido efeito (tentativa registrada)");
    }

    static final String CONF_ABORTADO_APOS_PUBLICO = "abortado_apos_publico";
    static final String CONF_ABORTADO_ANTES_ENVIO = "abortado_antes_do_envio";
    static final String CONF_DUPLICATA_SUSPEITA = "duplicata_suspeita";
    static final String CONF_NADA_PUBLICO = "nenhum_passo_publico_feito";

    /** Passos de conferencia que fecham a busca pelo ultimo passo publico do envio (ehEnvio). */
    private static final Set<String> KINDS_CONFERENCIA = Set.of("verify_text", "verify_attr", "verify_absent", "verify_new_id");

    /**
     * Passo publico de envio: tem "envio": true; ou, quando NENHUM passo do lote declara envio,
     * e o ULTIMO passo publico antes do proximo passo de conferencia (ou do fim do lote) — o
     * clique de foco ou de abrir composer, que vem antes do type e do botao Publicar, nao e envio.
     */
    static boolean ehEnvio(JsonNode actions, int i) {
        JsonNode a = actions.get(i);
        if (!Guard.isPublic(a.path("kind").asText(""))) return false;
        if (a.path("envio").asBoolean(false)) return true;
        for (JsonNode p : actions) {
            if (p.has("envio")) return false; // declaracao explicita em qualquer passo: so o marcado e envio
        }
        for (int j = i + 1; j < actions.size(); j++) {
            String k = actions.get(j).path("kind").asText("");
            if (KINDS_CONFERENCIA.contains(k)) return true;
            if (Guard.isPublic(k)) return false;
        }
        return true;
    }

    /**
     * No aborto com passo publico feito e nenhum envio identificado, o envio pode ter sido um
     * passo publico ANTERIOR ao escolhido pela heuristica de ehEnvio: sem marcacao explicita
     * "envio" ela so aposta que o ULTIMO passo publico e o envio, e um envio seguido de outro
     * passo publico (ex.: clique de fechar depois do Publicar) quebra a aposta. Com 2+ passos
     * publicos sem marcacao, classificar o aborto como se o envio pudesse ter acontecido.
     */
    static boolean envioPodeSerPassoAnterior(JsonNode actions) {
        int publicos = 0;
        for (JsonNode a : actions) {
            if (a.has("envio")) return false; // declaracao explicita em qualquer passo: so o marcado e envio
            if (Guard.isPublic(a.path("kind").asText(""))) publicos++;
        }
        return publicos >= 2;
    }

    /** Conferencia do resultado: a estatica do lote se rodou inteiro; senao, o que se sabe do aborto. */
    static String conferenciaFinal(JsonNode actions, boolean abortado, boolean publicoFeito) {
        return conferenciaFinal(actions, abortado, publicoFeito, publicoFeito, false, false);
    }

    /**
     * Conferencia com o que a execucao aprendeu: duplicata suspeita vence tudo (possivel publicacao
     * em dobro); aborto DEPOIS do envio segue abortado_apos_publico (scripts externos leem esse
     * texto), aborto ANTES do envio e novo — mas com 2+ passos publicos sem marcacao explicita o
     * envio pode ter sido um passo anterior (envioPodeSerPassoAnterior) e o aborto e classificado
     * como apos publico; prova forte cujo texto exibido nao cobriu o digitado inteiro cai para fraca.
     */
    static String conferenciaFinal(JsonNode actions, boolean abortado, boolean publicoFeito,
                                   boolean envioFeito, boolean duplicataSuspeita, boolean textoDivergente) {
        if (duplicataSuspeita) return CONF_DUPLICATA_SUSPEITA;
        if (!publicoFeito) {
            return BatchStore.CONF_SEM_PUBLICO.equals(BatchStore.conferencia(actions)) ? BatchStore.CONF_SEM_PUBLICO : CONF_NADA_PUBLICO;
        }
        if (abortado) {
            return envioFeito || envioPodeSerPassoAnterior(actions)
                    ? CONF_ABORTADO_APOS_PUBLICO : CONF_ABORTADO_ANTES_ENVIO;
        }
        String base = BatchStore.conferencia(actions);
        if (BatchStore.CONF_FORTE.equals(base) && textoDivergente) return BatchStore.CONF_FRACA;
        return base;
    }

    static String avisoConferencia(String conf) {
        return switch (conf) {
            case CONF_DUPLICATA_SUSPEITA -> "apareceram 2+ itens novos com o mesmo texto: possivel publicacao em dobro. Rede travada para lote publico ate social_resolve_incerteza.";
            case CONF_ABORTADO_APOS_PUBLICO -> "lote abortou DEPOIS de um passo publico: a acao pode ter tido efeito (ou nao). Confira na rede antes de qualquer novo lote. Rede travada para lote publico ate social_resolve_incerteza.";
            case CONF_ABORTADO_ANTES_ENVIO -> "lote abortou antes do passo de envio: nada deveria ter saido, mas confira se ficou rascunho ou composer aberto.";
            case BatchStore.CONF_FRACA -> "o lote rodou, mas a conferencia e fraca: texto solto na pagina (pode ser o proprio texto digitado ainda no editor) ou ausencia sem prova positiva de que a pagina carregou (pagina de login/vazia tambem nao tem o item). NAO declare publicado sem conferir na rede.";
            default -> "o lote rodou sem nenhuma conferencia depois do ultimo passo publico. NAO declare publicado sem conferir na rede.";
        };
    }

    /** Journal da acao (ok=false = passo que falhou depois de despachar; conta para cota).
     * Request grava so url, method, status, auth.from e 300 chars do corpo (nunca headers/token/body enviado). */
    static void journalAction(Store store, String network, String kind, JsonNode a, String batchId, ObjectNode step, boolean ok) {
        ObjectNode j = Json.obj()
                .put("kind", kind)
                .put("cls", step.path("pulado").asBoolean(false) ? "skipped" : Guard.isPublic(kind) ? "public" : "read")
                .put("batchId", batchId)
                .put("day", store.today())
                .put("desc", a.path("description").asText(""));
        if ("key".equals(kind)) {
            j.put("modo", step.path("modo").asText("literal"));
        }
        if ("type".equals(kind)) {
            j.put("modo", step.path("modo").asText(TypeText.MODE_INSERT));
        }
        if ("click".equals(kind)) {
            j.put("modo", step.path("modo").asText(RealClick.MODE_MOUSE));
            if (step.has("coberto")) j.put("coberto", step.path("coberto").asText(""));
        }
        if ("screenshot".equals(kind)) {
            j.put("arquivo", step.path("arquivo").asText(""));
        }
        if ("set_file".equals(kind)) {
            j.put("arquivo", step.path("arquivo").asText(""));
            j.put("bytes", step.path("bytes").asLong(0));
        }
        if ("capture_ids".equals(kind)) {
            j.put("idsVistos", step.path("idsVistos").asInt(0));
        }
        if ("verify_new_id".equals(kind)) {
            String id = step.path("idNovo").asText("");
            j.put("idNovo", id.length() > 200 ? id.substring(0, 200) : id);
        }
        if ("request".equals(kind)) {
            j.put("url", a.path("url").asText(""));
            j.put("method", a.path("method").asText("").toUpperCase(java.util.Locale.ROOT));
            j.put("status", step.path("status").asInt(0));
            if (a.path("auth").isObject()) j.putObject("auth").put("from", a.path("auth").path("from").asText(""));
            String b = step.path("bodySnippet").asText("");
            j.put("bodySnippet", b.length() > 300 ? b.substring(0, 300) : b);
            if (step.path("incerto").asBoolean(false)) j.put("incerto", true);
            j.put("ok", ok);
        }
        if (!ok) j.put("ok", false);
        store.appendJournal("action", network, j);
    }

    private String setNetworkState(JsonNode args) {
        String network = args.path("network").asText("");
        String state = args.path("state").asText("");
        requireNetwork(network);
        if (!state.equals("cooldown") && !state.equals("paused")) {
            throw new IllegalArgumentException("o agente so pode definir cooldown|paused; 'healthy' e 'banned' sao decisao do dono via CLI");
        }
        String reason = args.path("reason").asText("(sem motivo)");
        store.setState(network, state, reason, "agente");
        return "rede '" + network + "' agora esta '" + state + "'. So o dono reativa: bin/social-mcp --cli state " + network + " healthy";
    }

    private String journalTail(JsonNode args) {
        int n = args.has("n") && args.get("n").isInt() ? args.get("n").asInt() : 30;
        return String.join("\n", store.tail(Math.min(Math.max(n, 1), 500)));
    }

    // ---------- execucao de passo ----------

    /** Executa um passo fora de lote (readOnly); sem midia aprovada vinculada. */
    private String runStep(String network, String kind, JsonNode a, ObjectNode out) throws Exception {
        return runStep(network, kind, a, out, null, -1);
    }

    /** Marcador [[novo:NOME]] no url do goto; NOME e o name do par capture/verify ([A-Za-z][A-Za-z0-9_]*). */
    static final java.util.regex.Pattern MARCADOR_ID_NOVO =
            java.util.regex.Pattern.compile("\\[\\[novo:([A-Za-z][A-Za-z0-9_]*)]]");
    /** id novo aceito na substituicao (o que o verify_new_id extraiu): sem chars de url tipo / ? &. */
    private static final java.util.regex.Pattern ID_NOVO_OK = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,200}");

    /**
     * Troca cada [[novo:NOME]] do url pelo idNovo do verify_new_id com name NOME que deu ok antes
     * no mesmo lote. Sem marcador, devolve o url igual; marcador sem verify ok anterior, ou id
     * com caracteres que nao sao de id (ex.: / ?), falham o passo.
     */
    static String resolverIdNovo(String url, java.util.Map<String, String> ids) {
        java.util.regex.Matcher m = MARCADOR_ID_NOVO.matcher(url);
        StringBuilder sb = new StringBuilder();
        int ultimo = 0;
        while (m.find()) {
            String nome = m.group(1);
            String id = ids.get(nome);
            if (id == null) {
                throw new IllegalStateException("goto: [[novo:" + nome
                        + "]] sem verify_new_id ok anterior com esse name neste lote");
            }
            if (!ID_NOVO_OK.matcher(id).matches()) {
                throw new IllegalStateException("goto: id novo com caracteres invalidos");
            }
            sb.append(url, ultimo, m.start()).append(id);
            ultimo = m.end();
        }
        if (ultimo == 0) return url;
        sb.append(url, ultimo, url.length());
        return sb.toString();
    }

    /** Executa um passo; extras (status, bodySnippet, extraido) vao em out. batch/passoIdx localizam a midia aprovada. */
    private String runStep(String network, String kind, JsonNode a, ObjectNode out, JsonNode batch, int passoIdx) throws Exception {
        JavaDriverless d = pool.get(network);
        switch (kind) {
            case "goto" -> {
                String url = resolverIdNovo(a.path("url").asText(), idsNovosLote);
                d.get(url);
                d.sleep(1.2);
                return "navegou para " + safe(() -> d.getCurrentUrl());
            }
            case "click" -> {
                String sel = a.path("selector").asText();
                // ifPresent: controle condicional (ex.: aviso que so aparece as vezes). So pula com ausencia
                // comprovada; seletor invalido ou falha do navegador continuam sendo erro do passo
                WebElement alvo;
                try {
                    if (a.path("ifPresent").asBoolean(false) && !presente(d, sel)) {
                        out.put("pulado", true);
                        return "ausente (ifPresent): nada clicado";
                    }
                    alvo = el(d, sel);
                } catch (InterruptedException e) {
                    // falha antes do clique; a interrupcao sobrevive a conversao
                    Thread.currentThread().interrupt();
                    throw new RealClick.NaoAgiu("click interrompido antes de agir em '" + sel + "'", e);
                } catch (RealClick.NaoAgiu e) {
                    throw e;
                } catch (Exception e) {
                    throw new RealClick.NaoAgiu(String.valueOf(e.getMessage()), e);
                }
                RealClick.Result r = RealClick.click(alvo, RealClick.mode(a), RND);
                out.put("modo", r.mode());
                if (!r.hit()) out.put("coberto", r.cover());
                d.sleep(1.0);
                return r.info();
            }
            case "type" -> {
                String sel = a.path("selector").asText("");
                if (!sel.isBlank()) {
                    WebElement campo;
                    try {
                        campo = el(d, sel);
                    } catch (Exception e) {
                        throw new RealClick.NaoAgiu(String.valueOf(e.getMessage()), e);
                    }
                    RealClick.click(campo, RealClick.MODE_MOUSE, RND);
                    d.sleep(0.8);
                    // nunca digitar no foco errado (clique caiu num overlay e o foco ficou em outro lugar)
                    if (!RealClick.focused(campo)) {
                        throw falhaFocoAposClique(sel);
                    }
                }
                String text = a.path("text").asText();
                ObjectNode cfg = store.networkCfg(network);
                String modo = TypeText.mode(cfg);
                out.put("modo", modo);
                if (TypeText.MODE_KEYBOARD.equals(modo)) {
                    d.getKeyboard().typeHumanized(text).get();
                    return "digitou " + text.length() + " caracteres (teclado humanizado)";
                }
                int pedacos = TypeText.insert(d, text, TypeText.chunkDelay(cfg), new java.util.Random());
                return "digitou " + text.length() + " caracteres (insertText, " + pedacos + " pedacos)";
            }
            case "key" -> {
                String text = a.path("text").asText();
                java.util.Optional<KeyCombo> combo = KeyCombo.parse(text);
                if (combo.isPresent()) {
                    sendCombo(d, combo.get());
                    out.put("modo", "tecla");
                    return "tecla enviada: " + combo.get().label();
                }
                d.getKeyboard().type(text, 60).get();
                out.put("modo", "literal");
                return "teclas enviadas (literal, " + text.length() + " caracteres)";
            }
            case "verify_text" -> {
                return runVerifyText(d, a);
            }
            case "verify_attr" -> {
                return runVerifyAttr(d, a);
            }
            case "verify_absent" -> {
                return runVerifyAbsent(d, a);
            }
            case "capture_ids" -> {
                return runCaptureIds(d, a, out);
            }
            case "verify_new_id" -> {
                return runVerifyNewId(d, a, out, batch, passoIdx);
            }
            case "wait" -> {
                return runWait(d, a);
            }
            case "screenshot" -> {
                String f = saveScreenshot(network);
                out.put("arquivo", f);
                return "screenshot salvo em: " + f;
            }
            case "request" -> {
                return runRequest(d::executeScript, a, out);
            }
            case "set_file" -> {
                return runSetFile(d, a, out, batch, passoIdx);
            }
            case "extract" -> {
                ArrayNode lista = extract(d, a);
                out.set("extraido", lista);
                return "extraiu " + lista.size() + " itens";
            }
            default -> throw new IllegalArgumentException("kind invalido: " + kind);
        }
    }

    private static final float KEY_TIMEOUT_S = 5.0f;
    private static final int VERIFY_TENTATIVAS = 3;
    private static final long WAIT_POLL_MS = 500;
    private static final java.util.Random RND = new java.util.Random();

    /**
     * verify_text: sem selector, a pagina inteira contem expect; com selector, o texto DO
     * elemento (com shadow DOM dentro dele) contem expect. Tolera SPA ainda carregando: ate 3
     * leituras com 1 s entre elas.
     */
    private String runVerifyText(JavaDriverless d, JsonNode a) throws Exception {
        String expect = a.path("expect").asText();
        String sel = a.path("selector").asText("");
        String visto = "";
        for (int tentativa = 1; tentativa <= VERIFY_TENTATIVAS; tentativa++) {
            if (sel.isBlank()) {
                if (BrowserPool.visibleText(d, 300_000).contains(expect)) return "texto verificado";
            } else {
                try {
                    visto = RealClick.text(el(d, sel));
                    if (visto.contains(expect)) return "texto verificado no elemento";
                } catch (Exception e) {
                    visto = "(elemento indisponivel: " + e.getMessage() + ")";
                }
            }
            if (tentativa < VERIFY_TENTATIVAS) Thread.sleep(1_000);
        }
        if (sel.isBlank()) {
            throw new IllegalStateException("verify_text falhou: '" + expect + "' nao encontrado na pagina ("
                    + VERIFY_TENTATIVAS + " tentativas)");
        }
        throw new IllegalStateException("verify_text falhou: elemento '" + sel + "' nao contem '" + expect
                + "' (texto: '" + corta(visto, 120) + "'; " + VERIFY_TENTATIVAS + " tentativas)");
    }

    /** verify_attr: o atributo attr do elemento e exatamente expect (ate 3 leituras, 1 s entre elas). */
    private String runVerifyAttr(JavaDriverless d, JsonNode a) throws Exception {
        String sel = a.path("selector").asText();
        String attr = a.path("attr").asText();
        String expect = a.path("expect").asText();
        String visto = null;
        for (int tentativa = 1; tentativa <= VERIFY_TENTATIVAS; tentativa++) {
            try {
                visto = RealClick.attr(el(d, sel), attr);
                if (expect.equals(visto)) return "atributo verificado: " + attr + "=" + expect;
            } catch (Exception e) {
                visto = "(elemento indisponivel: " + e.getMessage() + ")";
            }
            if (tentativa < VERIFY_TENTATIVAS) Thread.sleep(1_000);
        }
        throw new IllegalStateException("verify_attr falhou: '" + sel + "' tem " + attr + "="
                + (visto == null ? "(ausente)" : "'" + corta(visto, 120) + "'") + ", esperado '" + expect + "' ("
                + VERIFY_TENTATIVAS + " tentativas)");
    }

    /**
     * verify_absent: espera (ate timeoutMs, encurtado ao prazo do lote) nenhum elemento casar o
     * selector (com expect: nenhum que contenha o texto). Prova de ausencia depois de um delete.
     * Pareie com um verify_text/wait com selector da mesma pagina: pagina que nao carregou tambem
     * "nao tem" o elemento. Erro de JS (seletor invalido) nao conta como ausencia.
     */
    private String runVerifyAbsent(JavaDriverless d, JsonNode a) throws InterruptedException {
        String sel = a.path("selector").asText();
        String expect = a.path("expect").asText("");
        long resta = Math.max(1, prazoFimMs - System.currentTimeMillis());
        long timeout = Math.min(Math.min(Math.max(a.path("timeoutMs").asLong(BatchStore.WAIT_DEFAULT_TIMEOUT_MS), 1),
                BatchStore.WAIT_EXPECT_MAX_MS), resta);
        long inicio = System.currentTimeMillis();
        String js = ShadowDom.countScript(sel, expect);
        int n;
        String erro = null;
        while (true) {
            try {
                Object o = d.executeScript(js);
                n = o instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(o).trim());
                erro = null;
            } catch (Exception e) {
                n = -1;
                erro = String.valueOf(e.getMessage());
            }
            if (n == 0) {
                return "ausente" + (expect.isEmpty() ? "" : " (nenhum com '" + corta(expect, 60) + "')")
                        + " em " + (System.currentTimeMillis() - inicio) + " ms";
            }
            if (System.currentTimeMillis() - inicio >= timeout) break;
            Thread.sleep(WAIT_POLL_MS);
        }
        if (erro != null) {
            throw new IllegalStateException("verify_absent falhou: nao conseguiu avaliar '" + sel + "' (" + corta(erro, 160) + ")");
        }
        throw new IllegalStateException("verify_absent falhou: '" + sel + "'" + (expect.isEmpty() ? "" : " com '" + corta(expect, 60) + "'")
                + " ainda presente (" + n + ") apos " + timeout + " ms");
    }

    /**
     * capture_ids: estabiliza e guarda o conjunto de ids da lista antes do passo publico.
     * O prazo encurta para caber no que resta do lote.
     */
    private String runCaptureIds(JavaDriverless d, JsonNode a, ObjectNode out) throws Exception {
        String name = a.path("name").asText();
        NewIds.Spec spec = NewIds.Spec.of(a);
        long resta = Math.max(1, prazoFimMs - System.currentTimeMillis());
        long limite = Math.min(a.path("waitMs").asLong(BatchStore.WAIT_CAPTURE_DEFAULT_MS), resta);
        NewIds.Captura c = NewIds.capturar(d::executeScript, NewIds.real(), spec, limite);
        capturas.put(name, c);
        out.put("name", name);
        out.put("idsVistos", c.ids().size());
        if (a.path("responseProof").asBoolean(false)) {
            String network = a.path("network").asText("");
            PublishTrace anterior = traces.remove(name);
            if (anterior != null) anterior.close();
            PublishTrace trace = PublishTrace.start(d.getChrome().getCurrentTarget().get(), network, a.path("expect").asText(""));
            traces.put(name, trace);
        }
        return "capturou " + c.ids().size() + " ids em '" + name + "' (estavel por " + c.ms() + " ms)";
    }

    /**
     * verify_new_id: espera um item cujo id nao esta no conjunto do capture_ids de mesmo name.
     * Sem capture_ids no lote em curso = erro (o par so vale dentro do mesmo lote/acao).
     * Com o texto integral digitado (textoCompleto da acao ou o text do ultimo type antes deste
     * passo), confere se o texto EXIBIDO no item novo cobre o texto digitado inteiro: nao cobrindo,
     * marca textoDivergente e o passo segue ok (a conferencia cai para fraca).
     */
    private String runVerifyNewId(JavaDriverless d, JsonNode a, ObjectNode out, JsonNode batch, int passoIdx) throws InterruptedException {
        String name = a.path("name").asText();
        NewIds.Captura c = capturas.get(name);
        if (c == null) {
            throw new IllegalStateException("capture_ids '" + name + "' nao rodou neste lote");
        }
        NewIds.Spec spec = NewIds.Spec.of(a);
        String expect = a.path("expect").asText("");
        long resta = Math.max(1, prazoFimMs - System.currentTimeMillis());
        long timeout = Math.min(Math.min(Math.max(a.path("timeoutMs").asLong(BatchStore.WAIT_DEFAULT_TIMEOUT_MS), 1),
                BatchStore.WAIT_EXPECT_MAX_MS), resta);
        NewIds.Novo novo;
        if (a.path("responseProof").asBoolean(false)) {
            PublishTrace trace = traces.get(name);
            if (trace == null) {
                throw new IllegalStateException("capture_ids '" + name + "' com responseProof sem trace de publicacao armado");
            }
            java.util.function.Predicate<String> correlacao = id -> {
                try {
                    trace.assertMatched(id);
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            };
            try {
                novo = NewIds.verificar(d::executeScript, NewIds.real(), spec, c.ids(), expect, timeout, correlacao);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(e.getMessage() + " | trace: " + trace.diagnostico(), e);
            }
            out.put("trace", trace.diagnostico());
        } else {
            novo = NewIds.verificar(d::executeScript, NewIds.real(), spec, c.ids(), expect, timeout);
        }
        out.put("name", name);
        out.put("idNovo", novo.id());
        if (novo.outros() > 0) out.put("outrosNovos", novo.outros());
        String info = "id novo em '" + name + "': " + corta(novo.id(), 120);
        if (novo.outros() > 0) info += " (ATENCAO: mais " + novo.outros() + " itens novos)";
        String textoCompleto = textoDigitadoDoLote(a, batch, passoIdx);
        if (!textoCompleto.isBlank() && !NewIds.cobreSegmentos(novo.texto(), textoCompleto)) {
            out.put("textoDivergente", true);
            info += " (ATENCAO: texto exibido nao cobre o texto digitado inteiro)";
        }
        return info;
    }

    /**
     * Texto integral que deveria aparecer no item novo: o campo textoCompleto da acao ou, sem
     * ele, o text do ultimo passo type ANTES deste no lote (passos vem do lote via runStep);
     * fora de lote (readOnly), vazio: sem texto digitado nao ha o que conferir.
     */
    static String textoDigitadoDoLote(JsonNode a, JsonNode batch, int passoIdx) {
        String campo = a.path("textoCompleto").asText("");
        if (!campo.isBlank()) return campo;
        if (batch == null) return "";
        JsonNode actions = batch.path("actions");
        for (int j = Math.min(passoIdx, actions.size()) - 1; j >= 0; j--) {
            JsonNode prev = actions.get(j);
            if (prev != null && "type".equals(prev.path("kind").asText(""))) return prev.path("text").asText("");
        }
        return "";
    }

    private static String corta(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * wait: espera fixa (ms) ou ate o texto conter expect: o da pagina (com shadow DOM) ou, com
     * selector, o do elemento (prova de estado: ex. o dialogo pos-publicacao). Encurta para caber
     * no prazo do lote.
     */
    private String runWait(JavaDriverless d, JsonNode a) throws InterruptedException {
        long resta = Math.max(1, prazoFimMs - System.currentTimeMillis());
        if (a.hasNonNull("ms")) {
            long ms = Math.min(Math.min(Math.max(a.get("ms").asLong(), 1), BatchStore.WAIT_MAX_MS), resta);
            Thread.sleep(ms);
            return "esperou " + ms + " ms";
        }
        String expect = a.path("expect").asText();
        String sel = a.path("selector").asText("");
        long timeout = Math.min(Math.min(Math.max(a.path("timeoutMs").asLong(BatchStore.WAIT_DEFAULT_TIMEOUT_MS), 1),
                BatchStore.WAIT_EXPECT_MAX_MS), resta);
        long inicio = System.currentTimeMillis();
        while (true) {
            String visto;
            if (sel.isBlank()) {
                visto = BrowserPool.visibleText(d, 300_000);
            } else {
                try {
                    visto = RealClick.text(el(d, sel));
                } catch (Exception e) {
                    visto = "";
                }
            }
            if (visto.contains(expect)) {
                return (sel.isBlank() ? "texto" : "texto do elemento") + " apareceu em " + (System.currentTimeMillis() - inicio) + " ms";
            }
            if (System.currentTimeMillis() - inicio >= timeout) break;
            Thread.sleep(WAIT_POLL_MS);
        }
        throw new IllegalStateException("wait: '" + expect + "' nao apareceu" + (sel.isBlank() ? "" : " em '" + sel + "'")
                + " em " + timeout + " ms");
    }

    /**
     * Combinacao via CDP direto (a lib so manda modifiers quando a propria tecla e
     * modificador e nao manda code/windowsVirtualKeyCode): keyDown de cada
     * modificador, keyDown/keyUp da tecla com modifiers, keyUp dos modificadores
     * na ordem inversa.
     */
    private static void sendCombo(JavaDriverless d, KeyCombo k) throws Exception {
        int mask = 0;
        List<String> mods = k.modifierNames();
        try {
            for (String m : mods) {
                Object[] mi = KeyCombo.modifierInfo(m);
                mask |= (int) mi[3];
                dispatchKey(d, "rawKeyDown", (String) mi[0], (String) mi[1], (int) mi[2], "", mask, null);
            }
            String cmd = k.editCommand().orElse(null);
            dispatchKey(d, k.text().isEmpty() ? "rawKeyDown" : "keyDown", k.key(), k.code(), k.keyCode(), k.text(), k.modifiers(), cmd);
            dispatchKey(d, "keyUp", k.key(), k.code(), k.keyCode(), "", k.modifiers(), null);
        } finally {
            for (int i = mods.size() - 1; i >= 0; i--) {
                Object[] mi = KeyCombo.modifierInfo(mods.get(i));
                mask &= ~(int) mi[3];
                try {
                    dispatchKey(d, "keyUp", (String) mi[0], (String) mi[1], (int) mi[2], "", mask, null);
                } catch (Exception ignore) {
                    // melhor esforco: nao deixar modificador preso
                }
            }
        }
    }

    private static void dispatchKey(JavaDriverless d, String type, String key, String code, int vk,
                                    String text, int modifiers, String command) throws Exception {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("type", type);
        p.put("key", key);
        p.put("code", code);
        p.put("windowsVirtualKeyCode", vk);
        p.put("nativeVirtualKeyCode", vk);
        p.put("modifiers", modifiers);
        if (!text.isEmpty()) {
            p.put("text", text);
            p.put("unmodifiedText", text);
        }
        if (command != null) p.put("commands", List.of(command));
        io.github.selenium.javaDriverless.types.Target t = d.getChrome().getCurrentTarget()
                .get((long) KEY_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
        t.executeCdpCmd("Input.dispatchKeyEvent", p, KEY_TIMEOUT_S)
                .get((long) KEY_TIMEOUT_S + 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final long SET_FILE_TIMEOUT_S = 15;

    /**
     * Upload: revalida o arquivo (pode ter mudado desde a aprovacao), acha o input pelo
     * seletor sem exigir visibilidade e chama DOM.setFileInputFiles (WebElement.setFiles da lib).
     * Nome e tamanho vao em out; o caminho completo nao sai do passo.
     */
    private String runSetFile(JavaDriverless d, JsonNode a, ObjectNode out, JsonNode batch, int passoIdx) throws Exception {
        boolean multiplo = a.path("paths").isArray();
        List<java.nio.file.Path> arquivos = new java.util.ArrayList<>();
        List<String> nomes = new java.util.ArrayList<>();
        long bytes = 0;
        if (batch == null) {
            if (multiplo) {
                for (JsonNode path : a.path("paths")) {
                    java.nio.file.Path real = BatchStore.validateUploadFile(store, path, passoIdx);
                    arquivos.add(real);
                    nomes.add(real.getFileName().toString());
                    bytes += Files.size(real);
                }
            } else {
                java.nio.file.Path real = BatchStore.validateUploadFile(store, a.get("path"), passoIdx);
                arquivos.add(real);
                nomes.add(real.getFileName().toString());
                bytes = Files.size(real);
            }
        } else {
            List<JsonNode> midias = multiplo ? BatchStore.midiasDoPasso(batch, passoIdx)
                    : java.util.Collections.singletonList(BatchStore.midiaDoPasso(batch, passoIdx));
            if (midias.isEmpty() || (multiplo && midias.size() != a.path("paths").size())) {
                throw new IllegalStateException("set_file sem digest de midia aprovado no passo " + (passoIdx + 1));
            }
            for (int i = 0; i < midias.size(); i++) {
                JsonNode midia = midias.get(i);
                if (midia == null || midia.path("sha256").asText("").isBlank()
                        || (multiplo && midia.path("indice").asInt(-1) != i)) {
                    throw new IllegalStateException("set_file sem digest de midia aprovado no passo " + (passoIdx + 1));
                }
                java.nio.file.Path real = BatchStore.copiaAprovada(store, midia);
                if (!Files.isRegularFile(real) || !midia.path("sha256").asText().equals(BatchStore.sha256Arquivo(real))) {
                    throw new IllegalStateException("copia aprovada da midia do passo " + (passoIdx + 1) + " ausente ou alterada");
                }
                arquivos.add(real);
                nomes.add(midia.path("arquivo").asText(real.getFileName().toString()));
                bytes += Files.size(real);
            }
        }
        WebElement input = el(d, a.path("selector").asText());
        input.setFiles(arquivos.stream().map(java.nio.file.Path::toString).toList())
                .get(SET_FILE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
        d.sleep(1.0);
        String nome = String.join(", ", nomes);
        out.put("arquivo", nome);
        out.put("bytes", bytes);
        if (multiplo) out.put("arquivos", arquivos.size());
        return "arquivo definido: " + nome + " (" + bytes + ")";
    }

    private static final long REQUEST_TIMEOUT_MS = 30_000;

    /**
     * fetch DENTRO da pagina (cookies/sessao do Chrome, credentials do passo; padrao include). O
     * script grava a resposta numa chave de window e o Java consulta ate chegar
     * (expressao sem "return", como visibleText). Nunca sai request fora do navegador.
     */
    /** exec = executeScript da aba (seam de teste: o JS roda na pagina logada). */
    static String runRequest(java.util.function.Function<String, Object> exec, JsonNode a, ObjectNode out) throws Exception {
        String key = "__smcpReq" + System.nanoTime();
        String method = a.path("method").asText("POST").toUpperCase(java.util.Locale.ROOT);
        String js = requestScript(key, a);
        String raw = "";
        String falhaDriver = null;
        try {
            exec.apply(js);
            long fim = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
            while (System.currentTimeMillis() < fim) {
                Object o = exec.apply("(window[" + jsStr(key) + "]||'')");
                raw = o == null ? "" : String.valueOf(o);
                if (!raw.isBlank() && !"null".equals(raw)) break;
                Thread.sleep(300);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            falhaDriver = "interrompido";
        } catch (Exception e) {
            // pagina navegou/CDP caiu com o fetch possivelmente ja disparado: resultado incerto, conta
            falhaDriver = String.valueOf(e.getMessage());
        }
        if (falhaDriver != null) {
            out.put("status", 0);
            out.put("incerto", true);
            out.put("bodySnippet", "falha do navegador durante o request; resultado incerto");
            throw new IllegalStateException("request com resultado incerto (falha do navegador: " + corta(falhaDriver, 200)
                    + "); o fetch pode ter chegado ao servidor; tentativa registrada");
        }
        if (raw.isBlank() || "null".equals(raw)) {
            // timeout: cancela o fetch na pagina e marca resultado incerto — a
            // requisicao pode ter chegado ao servidor, entao conta para cota/espacamento
            try {
                exec.apply("(function(){var c=window[" + jsStr(key + "__ctrl") + "];if(c){c.abort();}})()");
            } catch (Exception ignored) {
            }
            try {
                exec.apply("(delete window[" + jsStr(key) + "])");
            } catch (Exception ignored) {
            }
            out.put("status", 0);
            out.put("incerto", true);
            out.put("bodySnippet", "timeout de " + (REQUEST_TIMEOUT_MS / 1000) + "s; fetch cancelado, resultado incerto");
            throw new IllegalStateException("request sem resposta em " + (REQUEST_TIMEOUT_MS / 1000)
                    + " s (resultado incerto: o fetch foi cancelado, mas pode ter chegado ao servidor; tentativa registrada)");
        }
        try {
            exec.apply("(delete window[" + jsStr(key) + "])");
        } catch (Exception ignored) {
        }
        JsonNode r = Json.parse(raw);
        int status = r.path("s").asInt(0);
        String snippet = r.path("b").asText("");
        out.put("status", status);
        // a resposta vai para o resultado, o lote executado e o journal: nunca com token/sessao
        out.put("bodySnippet", redigir(snippet));
        if (status == 0) {
            // fetch rejeitado (rede/CORS/abort): o pedido pode ter chegado ao servidor
            out.put("incerto", true);
            throw new IllegalStateException("request com resultado incerto: " + redigir(snippet));
        }
        if (status < 0) throw new IllegalStateException("request nao enviado: " + snippet);
        if (!statusEsperado(a.path("expectStatus"), status)) {
            throw new IllegalStateException("request respondeu status " + status + " fora de expectStatus " + a.path("expectStatus"));
        }
        String expectBody = a.path("expectBody").asText("");
        if (!expectBody.isEmpty() && !snippet.contains(expectBody)) {
            throw new IllegalStateException("request: expectBody '" + expectBody + "' ausente na resposta");
        }
        return method + " " + a.path("url").asText() + " -> " + status;
    }

    /**
     * Script do fetch: le csrf (cookie) e auth (token) na pagina, poe em header e/ou no corpo
     * (bodyField) e dispara o fetch. Tudo no mesmo script: o valor lido nunca volta ao Java.
     * Codigos de fim antes do fetch: -1 csrf ausente, -2 auth ausente, -3 corpo incompativel.
     */
    static String requestScript(String key, JsonNode a) {
        String method = a.path("method").asText("POST").toUpperCase(java.util.Locale.ROOT);
        JsonNode headers = a.path("headers").isObject() ? a.get("headers") : Json.obj();
        JsonNode csrf = a.path("csrf").isObject() ? csrfSpec(a.get("csrf")) : null;
        JsonNode auth = a.path("auth").isObject() ? authSpec(a.get("auth")) : null;
        JsonNode body = a.path("body").isTextual() ? a.get("body") : null;
        String cred = a.path("credentials").asText("include");
        if (!BatchStore.CREDENTIALS.contains(cred)) cred = "include";
        return "(function(){var k=" + jsStr(key) + ";window[k]=null;"
                + "var fim=function(s,b){window[k]=JSON.stringify({s:s,b:String(b).substring(0,2000)});};"
                + "var h=" + Json.write(headers) + ";var bf=[];var c=" + (csrf == null ? "null" : Json.write(csrf)) + ";"
                + "if(c){var v=null;document.cookie.split(';').forEach(function(x){x=x.trim();"
                + "if(x.indexOf(c.cookie+'=')===0){v=x.substring(c.cookie.length+1);}});"
                + "if(v===null){fim(-1,'csrf: cookie '+c.cookie+' ausente');return 'sem-csrf';}"
                + "try{v=decodeURIComponent(v);}catch(e){}if(c.header){h[c.header]=v;}if(c.bodyField){bf.push([c.bodyField,v]);}}"
                + AUTH_JS.replace("@AUTH@", auth == null ? "null" : Json.write(auth))
                + "var b=" + (body == null ? "null" : Json.write(body)) + ";var bk=" + jsStr(BatchStore.bodyKind(a)) + ";"
                + BODY_JS
                + "var ctrl=new AbortController();window[k+'__ctrl']=ctrl;"
                + "var o={method:" + jsStr(method) + ",headers:h,credentials:" + jsStr(cred) + ",signal:ctrl.signal};"
                + "if(b!==null){o.body=b;}"
                + "fetch(" + jsStr(a.path("url").asText()) + ",o).then(function(r){return r.text().then(function(t){fim(r.status,t);});})"
                + ".catch(function(e){fim(0,'erro fetch: '+String(e).substring(0,300));});return 'ok';})()";
    }

    /**
     * Insere os valores de bf ([campo, valor]) no corpo b: JSON (bk='json') e parseado e o campo
     * setado no caminho por pontos, criando objetos intermediarios; form (bk='form') ganha
     * campo=valor URL-encoded. Corpo incompativel: fim(-3) ANTES do fetch, sem o valor na mensagem.
     */
    static final String BODY_JS = "if(bf.length){try{"
            + "var proib=/^(__proto__|constructor|prototype)$/;"
            + "if(bk==='json'){var ob=(b===null||b==='')?{}:JSON.parse(b);"
            + "if(ob===null||typeof ob!=='object'||Array.isArray(ob)){throw 0;}"
            + "bf.forEach(function(f){var ps=f[0].split('.');var cur=ob;"
            + "for(var i=0;i<ps.length;i++){if(proib.test(ps[i])){throw 0;}}"
            + "for(var j=0;j<ps.length-1;j++){var nx=Object.prototype.hasOwnProperty.call(cur,ps[j])?cur[ps[j]]:undefined;"
            + "if(nx===undefined||nx===null){nx={};cur[ps[j]]=nx;}"
            + "else if(typeof nx!=='object'||Array.isArray(nx)){throw 0;}cur=nx;}"
            + "cur[ps[ps.length-1]]=f[1];});b=JSON.stringify(ob);}"
            + "else if(bk==='form'){bf.forEach(function(f){"
            + "b=((b===null||b==='')?'':b+'&')+encodeURIComponent(f[0])+'='+encodeURIComponent(f[1]);});}"
            + "else{throw 0;}"
            + "}catch(e){fim(-3,'bodyField: corpo incompativel (content-type '+(bk||'nenhum')+')');return 'sem-body';}}";

    /** So os campos de csrf que o JS usa. */
    static ObjectNode csrfSpec(JsonNode c) {
        ObjectNode o = Json.obj();
        o.put("header", c.path("header").asText(""));
        o.put("cookie", c.path("cookie").asText(""));
        o.put("bodyField", c.path("bodyField").asText(""));
        return o;
    }

    /**
     * Le o token NA PAGINA (cookie/localStorage/sessionStorage/JSON de elemento, path por
     * pontos) e poe em h[header] com o prefixo. Ausente/vazio/nao-string ou com quebra de
     * linha: fim(-2) ANTES do fetch (sem request = sem cota). O valor nunca volta ao Java.
     */
    static final String AUTH_JS = "var au=@AUTH@;if(au){var t=null;try{var src=null;"
            + "if(au.from==='cookie'){document.cookie.split(';').forEach(function(x){x=x.trim();"
            + "if(x.indexOf(au.key+'=')===0){src=x.substring(au.key.length+1);}});"
            + "if(src!==null){try{src=decodeURIComponent(src);}catch(e){}}}"
            + "else if(au.from==='localStorage'){src=window.localStorage.getItem(au.key);}"
            + "else if(au.from==='sessionStorage'){src=window.sessionStorage.getItem(au.key);}"
            + "else if(au.from==='element-json'){var el=document.querySelector(au.selector);src=el?el.textContent:null;}"
            + "if(src!==null&&au.path){var ob=JSON.parse(src);au.path.split('.').forEach(function(p){"
            + "ob=(ob!==null&&ob!==undefined&&typeof ob==='object')?ob[p]:undefined;});t=ob;}else{t=src;}"
            + "}catch(e){t=null;}"
            + "if(typeof t==='number'){t=String(t);}"
            + "if(typeof t!=='string'||t===''||/[\\r\\n]/.test(t)){fim(-2,'auth: token ausente (from '+au.from+')');return 'sem-auth';}"
            + "if(au.header){h[au.header]=(au.prefix||'')+t;}if(au.bodyField){bf.push([au.bodyField,t]);}}";

    /** So os campos de auth que o JS usa (nada de campo extra no script). */
    static ObjectNode authSpec(JsonNode au) {
        ObjectNode o = Json.obj();
        o.put("header", au.path("header").asText(""));
        o.put("prefix", au.path("prefix").asText(""));
        o.put("from", au.path("from").asText(""));
        o.put("key", au.path("key").asText(""));
        o.put("selector", au.path("selector").asText(""));
        o.put("path", au.path("path").asText(""));
        o.put("bodyField", au.path("bodyField").asText(""));
        return o;
    }

    private static final java.util.regex.Pattern JWT =
            java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(\\.[A-Za-z0-9_-]*)?");
    /** Campo JSON (ou par chave=valor) com nome de segredo: o valor vira [redigido]. */
    private static final java.util.regex.Pattern CAMPO_SEGREDO = java.util.regex.Pattern.compile(
            "(?i)(\"?[A-Za-z0-9_.-]*(?:token|jwt|secret|senha|password|passwd|session|sessid|cookie|csrf|xsrf|authorization|apikey|api_key|bearer)[A-Za-z0-9_.-]*\"?\\s*[:=]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|\"[^\"]*$|[^\\s,;&{}\\[\\]\"]+)");
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");

    /** So a chave de um campo de segredo (com o ':'/'='), para achar valores objeto/lista. */
    private static final java.util.regex.Pattern CHAVE_SEGREDO = java.util.regex.Pattern.compile(
            "(?i)\"?[A-Za-z0-9_.-]*(?:token|jwt|secret|senha|password|passwd|session|sessid|cookie|csrf|xsrf|authorization|apikey|api_key|bearer)[A-Za-z0-9_.-]*\"?\\s*[:=]\\s*");

    /** Tira token/sessao da resposta de um request antes de ela sair do passo (resultado, lote, journal). */
    static String redigir(String s) {
        if (s == null || s.isEmpty()) return s;
        String r = JWT.matcher(s).replaceAll("[redigido]");
        r = BEARER.matcher(r).replaceAll("$1[redigido]");
        r = redigirSubarvores(r);
        r = CAMPO_SEGREDO.matcher(r).replaceAll("$1\"[redigido]\"");
        return r;
    }

    /** Valor objeto/lista de campo sensivel: redige a subarvore inteira, nao so escalares. */
    private static String redigirSubarvores(String s) {
        java.util.regex.Matcher m = CHAVE_SEGREDO.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        int busca = 0;
        while (busca < s.length()) {
            if (!m.find(busca)) break;
            int inicioValor = m.end();
            if (inicioValor >= s.length()
                    || (s.charAt(inicioValor) != '{' && s.charAt(inicioValor) != '[')) {
                busca = Math.max(inicioValor, m.start() + 1);
                continue;
            }
            int fimValor = fimDaSubarvore(s, inicioValor);
            out.append(s, last, inicioValor);
            out.append("\"[redigido]\"");
            last = fimValor;
            busca = fimValor;
        }
        if (last == 0) return s;
        return out.append(s, last, s.length()).toString();
    }

    /** Fim (exclusivo) da subarvore que abre em inicio; string/escape nao contam colchetes. Sem fecho: fim do texto. */
    private static int fimDaSubarvore(String s, int inicio) {
        java.util.ArrayDeque<Character> pilha = new java.util.ArrayDeque<>();
        boolean emString = false;
        boolean escape = false;
        for (int i = inicio; i < s.length(); i++) {
            char c = s.charAt(i);
            if (emString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') emString = false;
                continue;
            }
            if (c == '"') emString = true;
            else if (c == '{' || c == '[') pilha.push(c);
            else if (c == '}' || c == ']') {
                if (!pilha.isEmpty()) pilha.pop();
                if (pilha.isEmpty()) return i + 1;
            }
        }
        return s.length();
    }

    static boolean statusEsperado(JsonNode expect, int status) {
        if (expect == null || !expect.isArray() || expect.isEmpty()) return status >= 200 && status < 300;
        for (JsonNode n : expect) if (n.asInt() == status) return true;
        return false;
    }

    private ArrayNode extract(JavaDriverless d, JsonNode a) {
        String match = a.path("match").asText("");
        ArrayNode lista = Json.M.createArrayNode();
        if ("links".equals(a.path("what").asText())) {
            Object o = d.executeScript(ShadowDom.linksScript(match));
            JsonNode arr = Json.parse(o == null ? "[]" : String.valueOf(o));
            if (arr.isArray()) arr.forEach(lista::add);
        } else {
            String txt = BrowserPool.visibleText(d, store.networkCfg(a.path("network").asText()).path("pageTextMaxChars").asInt(12_000));
            for (String linha : txt.split("\\R")) {
                String l = linha.trim();
                if (!l.isEmpty() && (match.isEmpty() || l.contains(match))) lista.add(l);
            }
        }
        return lista;
    }

    private static String jsStr(String s) {
        return Json.write(com.fasterxml.jackson.databind.node.TextNode.valueOf(s));
    }

    /** Prazo para um controle ifPresent aparecer (o mesmo do implicitlyWait do findElement). */
    private static final long PRESENCA_TIMEOUT_MS = 4_000;

    /** JS que diz se o seletor casa algo: "1", "0" ou "erro:<msg>" (seletor invalido). */
    static String presencaScript(String selector) {
        List<String> parts = ShadowDom.parts(selector);
        String expr;
        if (parts != null) {
            expr = "!!" + ShadowDom.findScript(parts);
        } else if (selector.startsWith("id:")) {
            expr = "!!document.getElementById(" + jsStr(selector.substring(3)) + ")";
        } else if (selector.startsWith("xpath:")) {
            expr = "document.evaluate(" + jsStr(selector.substring(6))
                    + ",document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue!==null";
        } else {
            expr = "document.querySelector(" + jsStr(selector.startsWith("css:") ? selector.substring(4) : selector) + ")!==null";
        }
        return "(function(){try{return (" + expr + ")?'1':'0';}catch(e){return 'erro:'+String(e&&e.message||e);}})()";
    }

    /** Ausencia comprovada (ate PRESENCA_TIMEOUT_MS) = false; seletor invalido/falha do navegador = excecao. */
    private static boolean presente(JavaDriverless d, String selector) throws InterruptedException {
        return presente(d::executeScript, selector);
    }

    /** exec = executeScript da aba (seam de teste). Falha do navegador aqui e antes de qualquer clique: NaoAgiu. */
    static boolean presente(java.util.function.Function<String, Object> exec, String selector) throws InterruptedException {
        String js = presencaScript(selector);
        long fim = System.currentTimeMillis() + PRESENCA_TIMEOUT_MS;
        while (true) {
            String r;
            try {
                r = String.valueOf(exec.apply(js));
            } catch (RuntimeException e) {
                throw new RealClick.NaoAgiu("ifPresent: falha do navegador ao consultar '" + selector + "'", e);
            }
            if ("1".equals(r)) return true;
            if (!"0".equals(r)) {
                throw new RealClick.NaoAgiu("ifPresent: nao deu para saber se '" + selector + "' existe (" + corta(r, 160) + ")");
            }
            if (System.currentTimeMillis() >= fim) return false;
            Thread.sleep(300);
        }
    }

    private static final long SHADOW_FIND_TIMEOUT_MS = 4_000;

    private WebElement el(JavaDriverless d, String selector) throws Exception {
        List<String> parts = ShadowDom.parts(selector);
        if (parts != null) return shadowEl(d, parts, selector);
        if (selector.startsWith("id:")) return d.findElement(By.id(selector.substring(3)));
        if (selector.startsWith("xpath:")) return d.findElement(By.xpath(selector.substring(6)));
        if (selector.startsWith("css:")) return d.findElement(By.css(selector.substring(4)));
        return d.findElement(By.css(selector));
    }

    /**
     * Seletor com >>>: o JS resolve trecho a trecho dentro de cada shadowRoot e devolve o no;
     * a serializacao "deep" da lib converte o no em WebElement (backendNodeId). Repete ate
     * SHADOW_FIND_TIMEOUT_MS, como o implicitlyWait do findElement.
     */
    private static WebElement shadowEl(JavaDriverless d, List<String> parts, String selector) throws Exception {
        String js = ShadowDom.findScript(parts);
        long fim = System.currentTimeMillis() + SHADOW_FIND_TIMEOUT_MS;
        while (true) {
            io.github.selenium.javaDriverless.types.Target t = d.getChrome().getCurrentTarget()
                    .get((long) KEY_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
            Object o = t.executeScript(js, null, true, "deep").get((long) KEY_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
            if (o instanceof WebElement w) return w;
            if (System.currentTimeMillis() >= fim) {
                throw new IllegalStateException("elemento nao encontrado no shadow DOM: '" + selector + "'");
            }
            Thread.sleep(300);
        }
    }

    private void sleepJitter(String network) {
        JsonNode r = store.networkCfg(network).path("stepDelayMs");
        long min = r.isArray() && r.size() == 2 ? r.get(0).asLong(1500) : 1500;
        long max = r.isArray() && r.size() == 2 ? r.get(1).asLong(6000) : 6000;
        long ms = min + (long) (Math.random() * Math.max(1, max - min));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- helpers ----------

    private void requireNetwork(String network) {
        if (!store.knownNetworks().contains(network)) {
            throw new IllegalArgumentException("rede desconhecida: '" + network + "'. Redes: " + store.knownNetworks());
        }
    }

    private String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (Exception e) {
            return "(indisponivel: " + e.getMessage() + ")";
        }
    }

    private static final String TOOL_DEFS = """
            [
              {
                "name": "social_status",
                "description": "Estado das redes do Social MCP: kill-switch, saude por rede, cotas de hoje e ultima acao publica. Consulte SEMPRE antes de planejar acoes.",
                "inputSchema": { "type": "object", "properties": {} }
              },
              {
                "name": "social_browse",
                "description": "Abre uma URL na sessao persistente da rede (Chrome real, profile proprio). Somente leitura. Retorna titulo, URL e texto visivel. NUNCA digita senha; se a sessao caiu, pare e avise o dono.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network", "url"],
                  "properties": {
                    "network": { "type": "string", "description": "threads, reddit, instagram, facebook, tiktok, youtube, x, pinterest, bluesky, mastodon, kwai" },
                    "url": { "type": "string" },
                    "maxChars": { "type": "integer" }
                  }
                }
              },
              {
                "name": "social_read",
                "description": "Le o texto visivel da pagina atual da rede, sem navegar.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network"],
                  "properties": {
                    "network": { "type": "string" },
                    "maxChars": { "type": "integer" }
                  }
                }
              },
              {
                "name": "social_inspect",
                "description": "Lista os controles interativos VISIVEIS da pagina atual da rede (inputs, textarea, selects, botoes, [role=button], contenteditable): tag, rotulo curto, id e seletores css/xpath prontos para usar no lote. Somente leitura: nao clica, nao foca, nao altera nada, nao le valores digitados nem senhas (campo sensivel e omitido). Use para achar onde digitar/clicar quando o texto visivel nao basta (ex.: Nome da Pagina e categoria no criar Pagina do Facebook). Nao consome cota nem exige aprovacao.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network"],
                  "properties": {
                    "network": { "type": "string" },
                    "maxControls": { "type": "integer", "minimum": 1, "maximum": 100, "default": 60 }
                  }
                }
              },
              {
                "name": "social_query",
                "description": "Consulta de DOM so leitura na pagina atual da rede: conta quantos elementos casam o seletor (css:, xpath:, id: ou css com >>> para shadow DOM) e devolve atributos-chave (aria-*, data-testid, href, hidden, slot...) e o caminho de ancestrais dos primeiros N. Nunca clica nem le valor de campo. Use para mapear receita e provar que o controle e unico e preso ao alvo.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network", "selector"],
                  "properties": {
                    "network": { "type": "string" },
                    "selector": { "type": "string" },
                    "max": { "type": "integer", "minimum": 1, "maximum": 20, "default": 5 }
                  }
                }
              },
              {
                "name": "social_screenshot",
                "description": "Salva um PNG da tela atual da rede e retorna o caminho.",
                "inputSchema": { "type": "object", "required": ["network"], "properties": { "network": { "type": "string" } } }
              },
              {
                "name": "social_draft_batch",
                "description": "Cria um LOTE (rascunho) de UMA rede, valida e NAO executa. Um lote com acao publica (click/type/key) conta como 1 acao da cota diaria da rede; maximo de passos publicos por lote em maxPublicStepsPerBatch (padrao 5). Toda acao publica exige aprovacao do aprovador unico via CLI antes do execute. Inclua o clique no botao de envio: digitar nao publica. Upload: passo set_file {selector do input type=file, path absoluto dentro de defaults.uploadRoots (padrao: pasta marketing do projeto), png|jpg|jpeg|webp|gif|mp4|mov, ate uploadMaxMb}; nao e publico, mas o lote precisa de um click/key/request depois dele. Para criacao/resposta, prove por ID NOVO: capture_ids antes do 1o passo publico + verify_new_id depois = conferencia forte (melhor que texto). Senao prove ESTADO antes do envio e DEPOIS dele (verify_attr, verify_absent para o que deve sumir, ou verify_text/wait com selector), nao so texto solto na pagina: o resumo da execucao traz conferencia=forte|fraca|ausente e AVISO quando o resultado nao foi provado.",
                "inputSchema": {
                  "type": "object",
                  "required": ["label", "actions"],
                  "properties": {
                    "label": { "type": "string", "description": "rotulo humano do lote, ex: 'resposta thread X 27/09'" },
                    "actions": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["network", "kind"],
                        "properties": {
                          "network": { "type": "string" },
                          "kind": { "type": "string", "enum": ["goto", "click", "type", "key", "verify_text", "verify_attr", "verify_absent", "request", "extract", "set_file", "screenshot", "wait", "capture_ids", "verify_new_id"] },
                          "url": { "type": "string" },
                          "selector": { "type": "string", "description": "formato css:... / id:... / xpath:... (padrao css:); em set_file o input pode estar oculto; css com >>> entra no shadowRoot aberto do trecho anterior (ex: css:div[data-testid=xchatEmbedRoute] >>> a[href*='/i/chat/']). verify_absent: passa quando NENHUM elemento casa (prova de ausencia depois de delete/unfollow; pareie com um verify com selector da mesma pagina)" },
                          "ms": { "type": "integer", "description": "wait: espera fixa em ms (1..30000)" },
                          "timeoutMs": { "type": "integer", "description": "wait com expect: prazo em ms (padrao 15000, max 660000; sai assim que o texto aparece; checa a cada 500 ms; com selector, espera o texto DO elemento). verify_absent: prazo para o elemento sumir (mesmos limites). verify_new_id: prazo para o item novo aparecer (padrao 15000, checa a cada 500 ms). Soma das esperas do lote (ms + timeoutMs + waitMs) ate 720000; o lote inteiro tem prazo de 840 s" },
                          "ifPresent": { "type": "boolean", "description": "click: true = controle condicional (ex.: modal que so aparece as vezes); se o seletor nao achar nada em ~4 s o passo passa sem clicar (journal cls=skipped). Ainda conta no teto de passos publicos" },
                          "clickMode": { "type": "string", "enum": ["mouse", "dom"], "description": "click: mouse (padrao: rola com a roda ate o centro, trilha de mouse e clique real por CDP) ou dom (scrollIntoView + el.click(), evento sintetico; so para componente que ignora o mouse; proibido no tiktok)" },
                          "attr": { "type": "string", "description": "verify_attr: nome do atributo do elemento (ex.: aria-checked, aria-selected, disabled). capture_ids/verify_new_id: nome do atributo que guarda o id do item (ex.: href, data-id, data-tid) ou @text (o id e o texto do item/descendente: innerText/textContent com espacos colapsados, trim e cortado em 300 chars). Para @text, aponte ao texto do corpo, sem contadores ou horario (curtidas e 'ha 3 min' mudam e viram id novo)" },
                          "name": { "type": "string", "description": "capture_ids/verify_new_id: nome do par ([a-z][a-z0-9_]{0,31}); o verify_new_id referencia o capture_ids ANTERIOR de mesmo name" },
                          "attrSelector": { "type": "string", "description": "capture_ids/verify_new_id: css puro (sem xpath:/id:/>>>; prefixo css: opcional) dos descendentes do item de onde o attr e lido (todos os que casam, ate 10 na ordem do DOM; o id e o 1o que serve); sem ele, do proprio item" },
                          "idRegex": { "type": "string", "description": "capture_ids/verify_new_id: regex Java (ate 200 chars) aplicada ao valor cru (vale tambem com attr=@text); o id e o grupo 1 (se existir) ou o match inteiro; sem match o candidato e ignorado" },
                          "parentSelector": { "type": "string", "description": "capture_ids/verify_new_id: restringe os itens aos descendentes de UM elemento (mesmos formatos de selector). Com parent, selector css roda em parent.querySelectorAll (>>> a partir do parent) e xpath usa o parent como no de contexto e precisa ser relativo (.//...). XPath absoluto com parent e recusado. Parent casando 0 = ainda nao carregou; mais de 1 = erro. selector, parentSelector, attr, attrSelector e idRegex precisam ser iguais nos dois passos do par" },
                          "waitMs": { "type": "integer", "description": "capture_ids: prazo da estabilizacao em ms (>= stableMs + 1000 .. 660000, padrao 10000); conjunto NAO VAZIO estabiliza quando fica igual por stableMs; com minIds, conjuntos menores continuam esperando" },
                          "stableMs": { "type": "integer", "description": "capture_ids: estabilidade minima do conjunto NAO VAZIO em ms (1000..30000, padrao 1000; com poll de 1000 e 1000 = 2 leituras seguidas iguais). Listas que carregam em 2 ondas precisam de mais" },
                          "minIds": { "type": "integer", "minimum": 0, "maximum": 1000, "description": "capture_ids: quantidade minima de ids para aceitar conjunto estavel (0..1000, padrao 0); abaixo do minimo continua lendo ate waitMs" },
                          "path": { "type": "string", "description": "set_file: caminho absoluto do arquivo dentro de defaults.uploadRoots" },
                          "paths": { "type": "array", "minItems": 2, "maxItems": 10, "items": { "type": "string" }, "description": "set_file: 2..10 caminhos absolutos dentro de defaults.uploadRoots, enviados juntos na ordem" },
                          "text": { "type": "string" },
                          "expect": { "type": "string", "description": "verify_text: substring que deve existir na pagina, ou no texto do elemento quando ha selector (prova de estado: ex. #done-button contem 'Publicar'); verify_attr: valor EXATO do atributo (ex.: 'true'); ate 3 leituras, 1 s entre elas. verify_absent (opcional): so conta elementos cujo texto contem expect (ex.: a linha da lista com o id apagado). wait: espera ate o texto aparecer. verify_new_id (opcional): o texto do ITEM novo (innerText/textContent + alt/aria-label do item e descendentes) precisa conter expect; com responseProof=true, expect do verify e o texto aprovado completo ou trecho dele com ao menos 8 chars" },
                          "responseProof": { "type": "boolean", "description": "capture_ids/verify_new_id: true = prova de resposta opt-in (ig|tt|yt|facebook|bluesky|mastodon|x|pinterest|reddit): capture exige expect 8..2200; verify exige mesmo responseProof e expect igual ou trecho (>= 8 chars); o lote precisa de type ou request POST posterior com o texto exato do capture" },
                          "method": { "type": "string", "description": "request: POST|PUT|PATCH|DELETE (fetch dentro da pagina logada; conta como acao publica)" },
                          "headers": { "type": "object", "description": "request: headers extras literais (cookie proibido: a sessao e a do navegador; token de usuario vai em auth, nunca aqui)" },
                          "body": { "type": "string", "description": "request: corpo ja codificado, ate 16 KiB" },
                          "expectStatus": { "type": "array", "items": { "type": "integer" }, "description": "request: status aceitos (padrao 2xx)" },
                          "expectBody": { "type": "string", "description": "request: substring obrigatoria na resposta" },
                          "credentials": { "type": "string", "enum": ["include", "omit", "same-origin"], "description": "request: credentials do fetch (padrao include; omit para API com Bearer que recusa CORS com credenciais, ex.: PDS do Bluesky)" },
                          "csrf": { "type": "object", "description": "request: {cookie, header?, bodyField?}: valor do cookie (document.cookie) vai no header e/ou no corpo (bodyField: caminho por pontos no JSON ou campo do form; exige content-type JSON ou form)" },
                          "auth": { "type": "object", "description": "request: {header, prefix?, from: cookie|localStorage|sessionStorage|element-json, key (cookie/storage) ou selector (element-json), path? (pontos no JSON; obrigatorio em element-json), bodyField? (token sem prefixo no corpo JSON/form; header ou bodyField obrigatorio)}: token lido na pagina, nunca gravado; ausente = passo falha antes do fetch" },
                          "what": { "type": "string", "enum": ["links", "text"], "description": "extract: o que extrair da pagina" },
                          "match": { "type": "string", "description": "extract: substring filtro (href ou linha de texto)" },
                          "description": { "type": "string", "description": "o que este passo faz, em portugues" }
                        }
                      }
                    }
                  }
                }
              },
              {
                "name": "social_action",
                "description": "Acao de alto nivel por rede a partir da receita do dono (SOCIAL_MCP_HOME/recipes/<rede>.json). Acao readOnly (so quando a receita declara readOnly; ex.: list_posts, get_post, read_inbox) roda na hora em rede healthy, cooldown ou paused e devolve o que foi extraido; banned ou estado desconhecido bloqueiam. Acao publica (create_post, edit_post, delete_post, reply, follow, like) NAO executa: vira um rascunho de lote e devolve batchId + preflight; segue o fluxo normal de aprovacao pelo aprovador unico e social_execute_batch. Acoes publicas exigem rede healthy; receita ausente => mapear com scripts/redes/mapear-rede.sh.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network", "action"],
                  "properties": {
                    "network": { "type": "string" },
                    "action": { "type": "string", "description": "list_posts|list_videos|list_reels|list_community|get_post|get_profile|read_inbox|read_notifications|read_mentions|read_comments|read_requests|create_post|create_photo_post|community_post|edit_post|edit_title|delete_post|delete_community_post|reply|follow|like|unfollow|unlike (ate 3 sufixos de variante, ex: delete_post_ui, reply_comment_ui)" },
                    "params": { "type": "object", "description": "valores dos params declarados na receita, ex: postId=123 vira {postId: 123}" },
                    "label": { "type": "string", "description": "rotulo do lote; padrao '<rede> <acao> <params resumidos>'" }
                  }
                }
              },
              {
                "name": "social_execute_batch",
                "description": "Executa um lote APROVADO fora do MCP pelo aprovador unico: o dono (humano) ou o orquestrador ativo que esta vendo. A operadora (voce) NUNCA aprova. Falha antes do primeiro passo se nao aprovado, kill-switch, cota ou espacamento insuficiente. Respeita ritmo humano entre passos, registra tudo no journal e desarma a rede ao detectar anti-bot. Cota e espacamento valem por lote; limite de lotes por dia e por rede.",
                "inputSchema": { "type": "object", "required": ["batchId"], "properties": { "batchId": { "type": "string" } } }
              },
              {
                "name": "social_set_network_state",
                "description": "Disjuntor: coloca a rede em cooldown ou paused. O agente NUNCA pode reativar (healthy) — isso e decisao do dono via CLI.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network", "state"],
                  "properties": {
                    "network": { "type": "string" },
                    "state": { "type": "string", "enum": ["cooldown", "paused"] },
                    "reason": { "type": "string" }
                  }
                }
              },
              {
                "name": "social_probe",
                "description": "Sonda de receita (so leitura): executa os passos de leitura do inicio da acao da receita e confere se o primeiro controle de acao (click/type/set_file) ainda existe na pagina, SEM clicar nem digitar. Nao gasta cota publica. Use antes de rascunhar lote e na checagem diaria de saude.",
                "inputSchema": {
                  "type": "object",
                  "required": ["network", "action"],
                  "properties": {
                    "network": { "type": "string" },
                    "action": { "type": "string" },
                    "params": { "type": "object" }
                  }
                }
              },
              {
                "name": "social_find_duplicates",
                "description": "Somente leitura, sem cota: lista os posts do perfil (receita list_posts), abre cada um, tira o texto de interface comum e agrupa posts com legenda parecida (Jaccard de shingles). Use para confirmar publicação duplicada depois de lote abortado ou refeito. Não apaga nada: exclusão segue o fluxo de lote com aprovação.",
                "inputSchema": { "type": "object", "properties": {
                  "network": { "type": "string" },
                  "maxPosts": { "type": "integer", "default": 12 },
                  "limiar": { "type": "number", "default": 0.6 }
                }, "required": ["network"] }
              },
              {
                "name": "social_resolve_incerteza",
                "description": "Registra o que a conferencia NA REDE mostrou para um lote com envio incerto ou duplicata suspeita (libera a trava da rede). prova = URL/id do post no ar, ou descricao objetiva da leitura que mostrou que nao saiu/foi apagado. Nao publica nem apaga nada.",
                "inputSchema": {
                  "type": "object",
                  "required": ["batchId", "resultado", "prova"],
                  "properties": {
                    "batchId": { "type": "string" },
                    "resultado": { "type": "string", "enum": ["no_ar", "nao_saiu", "apagado"], "description": "no_ar = esta publicado; nao_saiu = nunca foi publicado; apagado = foi publicado e ja foi apagado" },
                    "prova": { "type": "string", "description": "URL/id do post no ar, ou descricao objetiva da leitura que mostrou que nao saiu/foi apagado (>= 8 caracteres)" },
                    "nota": { "type": "string", "description": "observacao opcional (ex.: o que foi feito com a duplicata)" }
                  }
                }
              },
              {
                "name": "social_journal_tail",
                "description": "Ultimos N eventos do journal: auditoria de tudo que o MCP fez (navegacoes, acoes, aprovacoes, tripwires).",
                "inputSchema": { "type": "object", "properties": { "n": { "type": "integer", "default": 30 } } }
              }
            ]
            """;
}
