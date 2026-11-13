package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ciclo de vida de lotes de acoes: draft -> (dono aprova via CLI) approved -> executed.
 * O agente so cria rascunho e executa o que o dono aprovou. Nao existe caminho
 * em que o agente aprove o proprio lote.
 */
public class BatchStore {
    private static final Set<String> KINDS = Set.of("goto", "click", "type", "key", "verify_text", "verify_attr", "verify_absent", "request", "extract", "set_file", "screenshot", "wait", "capture_ids", "verify_new_id");
    /** Valores aceitos em credentials do request (passados literalmente ao fetch). */
    static final Set<String> CREDENTIALS = Set.of("include", "omit", "same-origin");
    /** Extensoes aceitas no set_file (comparadas em minusculas). */
    static final Set<String> UPLOAD_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif", "mp4", "mov");
    /** Passos que enviam algo depois de um set_file (type nao envia). */
    static final Set<String> SEND_KINDS = Set.of("click", "key", "request");
    static final int DEFAULT_UPLOAD_MAX_MB = 200;
    /** Metodos de escrita aceitos em lote; GET so em acao readOnly do social_action. */
    static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final int MAX_BODY_BYTES = 16 * 1024;
    /** Redes com captura passiva de publicacao (PublishTrace). */
    static final Set<String> RESPONSE_PROOF_NETWORKS = Set.of("ig", "instagram", "tt", "tiktok", "yt", "youtube", "fb", "facebook", "bluesky", "bsky", "mastodon", "x", "twitter", "pinterest", "reddit");
    /** expect da prova de resposta: texto aprovado, 8..2200 chars. */
    static final int RESPONSE_PROOF_EXPECT_MIN = 8;
    static final int RESPONSE_PROOF_EXPECT_MAX = 2200;

    private final Store store;
    volatile Runnable antesDeConfirmarAprovacao = () -> {};

    public BatchStore(Store store) {
        this.store = store;
    }

    public JsonNode draft(String label, JsonNode actions) throws IOException {
        return draft(label, actions, null);
    }

    /**
     * Rascunho com origem declarada pela frente de Tools:
     * {"receita": "&lt;action&gt;", "provada": true|false}. origem = null e lote cru:
     * com passo publico, exige ensaio do dono (precisaEnsaio).
     */
    public JsonNode draft(String label, JsonNode actions, ObjectNode origem) throws IOException {
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            throw new IllegalArgumentException("actions deve ser um array nao vazio");
        }
        String net = validate(actions);
        String id = "b" + System.currentTimeMillis() + "-" + Integer.toHexString(Math.abs((String.valueOf(label) + actions.size() + System.nanoTime()).hashCode()));
        ObjectNode batch = Json.obj();
        batch.put("id", id);
        batch.put("label", label == null || label.isBlank() ? "(sem rotulo)" : label);
        batch.put("createdAt", System.currentTimeMillis());
        batch.put("status", "draft");
        batch.put("network", net);
        batch.set("actions", actions);
        if (origem != null) batch.set("origem", origem);
        Files.writeString(store.batchesDir().resolve(id + ".draft.json"), Json.writePretty(batch), StandardCharsets.UTF_8);
        store.appendJournal("batch_created", net, Json.obj()
                .put("batchId", id)
                .put("label", batch.get("label").asText())
                .put("acoes", actions.size()));
        return batch;
    }

    /**
     * Lote com passo publico que exige ensaio autorizado pelo dono: cru (sem origem) ou de
     * receita nao provada (origem.provada=false). Lote sem passo publico nunca precisa.
     */
    static boolean precisaEnsaio(JsonNode batch) {
        for (JsonNode a : batch.path("actions")) {
            if (Guard.isPublic(a.path("kind").asText(""))) {
                JsonNode origem = batch.path("origem");
                return !origem.isObject() || !origem.path("provada").asBoolean(false);
            }
        }
        return false;
    }

    /**
     * Validacao completa do lote (cada passo + regras estruturais) sem gravar nada: e o que o
     * draft faz antes de persistir. Retorna a unica rede do lote.
     */
    public String validate(JsonNode actions) {
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            throw new IllegalArgumentException("actions deve ser um array nao vazio");
        }
        int i = 0;
        for (JsonNode a : actions) validateAction(a, i++);
        return validateBatchRules(actions);
    }

    /**
     * Regras estruturais do lote: um lote = uma rede e teto de passos publicos.
     * Vale no draft E na execucao (lote aprovado antes de a regra mudar, ou config
     * reduzida depois da aprovacao). Retorna a unica rede do lote.
     */
    public String validateBatchRules(JsonNode actions) {
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            throw new IllegalArgumentException("actions deve ser um array nao vazio");
        }
        String net = actions.get(0).path("network").asText();
        for (JsonNode a : actions) {
            String n = a.path("network").asText();
            if (!n.equals(net)) {
                throw new IllegalArgumentException("um lote = uma rede: acao em '" + n
                        + "' misturada com lote de '" + net + "'. Crie um lote por rede.");
            }
        }
        int passosPublicos = 0;
        for (JsonNode a : actions) {
            if (Guard.isPublic(a.path("kind").asText())) passosPublicos++;
        }
        int maxPub = store.networkCfg(net).path("maxPublicStepsPerBatch").asInt(5);
        if (passosPublicos > maxPub) {
            throw new IllegalArgumentException("lote excede o limite de passos publicos: " + passosPublicos
                    + " em '" + net + "' (maxPublicStepsPerBatch=" + maxPub + "). Divida em mais lotes.");
        }
        validateSetFileOrder(actions);
        validateWaitBudget(actions);
        validateIdPairs(actions);
        validateGotoIdNovo(actions);
        // clickMode tambem na execucao: lote aprovado antes da regra (campo ignorado) nao vira clique dom no tiktok
        int i = 0;
        for (JsonNode a : actions) {
            if ("click".equals(a.path("kind").asText())) validateClickMode(a, i);
            i++;
        }
        return net;
    }

    private void validateAction(JsonNode a, int idx) {
        String kind = a.path("kind").asText("");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("acao " + idx + ": kind invalido '" + kind + "' (use goto|click|type|key|verify_text|verify_attr|verify_absent|request|extract|set_file|screenshot|wait|capture_ids|verify_new_id)");
        }
        if (a.path("network").asText("").isBlank()) {
            throw new IllegalArgumentException("acao " + idx + ": sem network");
        }
        validateSelector(a, idx);
        if (a.has("ifPresent") && !"click".equals(kind)) {
            throw new IllegalArgumentException("acao " + idx + ": ifPresent so vale em click");
        }
        switch (kind) {
            case "goto" -> {
                String url = a.path("url").asText("");
                if (url.isBlank()) throw new IllegalArgumentException("acao " + idx + ": goto exige url");
                if (url.contains("[[")) validateGotoMarcador(url, idx);
            }
            case "wait" -> validateWait(a, idx);
            case "click" -> {
                if (a.path("selector").asText("").isBlank()) throw new IllegalArgumentException("acao " + idx + ": click exige selector");
                validateClickMode(a, idx);
            }
            case "type", "key" -> {
                if (!a.has("text")) throw new IllegalArgumentException("acao " + idx + ": " + kind + " exige text");
            }
            case "verify_text" -> {
                if (a.path("expect").asText("").isBlank()) throw new IllegalArgumentException("acao " + idx + ": verify_text exige expect");
            }
            case "verify_attr" -> validateVerifyAttr(a, idx);
            case "verify_absent" -> validateVerifyAbsent(a, idx);
            case "capture_ids" -> validateCaptureIds(a, idx);
            case "verify_new_id" -> validateVerifyNewId(a, idx);
            case "request" -> validateRequest(store, a, idx, false);
            case "extract" -> validateExtract(a, idx);
            case "set_file" -> {
                if (a.path("selector").asText("").isBlank()) throw new IllegalArgumentException("acao " + idx + ": set_file exige selector");
                boolean temPath = a.has("path");
                boolean temPaths = a.has("paths");
                if (temPath == temPaths) throw new IllegalArgumentException("acao " + idx + ": set_file exige path (string) ou paths (2..10)");
                if (temPath) {
                    validateUploadFile(store, a.get("path"), idx);
                } else {
                    JsonNode paths = a.get("paths");
                    if (!paths.isArray() || paths.size() < 2 || paths.size() > 10) {
                        throw new IllegalArgumentException("acao " + idx + ": set_file exige path (string) ou paths (2..10)");
                    }
                    Set<Path> reais = new HashSet<>();
                    for (JsonNode item : paths) {
                        Path real = validateUploadFile(store, item, idx);
                        if (!reais.add(real)) throw new IllegalArgumentException("acao " + idx + ": set_file paths nao pode repetir arquivo");
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * Passo request: method na lista, url https no host da rede, body ate 16 KiB,
     * headers/expectStatus/csrf bem formados. allowGet so para acao readOnly.
     */
    static void validateRequest(Store store, JsonNode a, int idx, boolean allowGet) {
        String p = "acao " + idx + ": request ";
        String method = a.path("method").asText("").toUpperCase(java.util.Locale.ROOT);
        boolean ok = WRITE_METHODS.contains(method) || (allowGet && "GET".equals(method));
        if (!ok) {
            throw new IllegalArgumentException(p + "method invalido '" + a.path("method").asText("")
                    + "' (use POST|PUT|PATCH|DELETE" + (allowGet ? "|GET" : "; GET so em social_action readOnly") + ")");
        }
        String network = a.path("network").asText("");
        String url = a.path("url").asText("");
        java.net.URI uri;
        try {
            uri = new java.net.URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(p + "url invalida: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(p + "exige url https absoluta: " + url);
        }
        if (!hostDaRede(store, network, uri.getHost())) {
            throw new IllegalArgumentException(p + "host '" + uri.getHost() + "' nao pertence a rede '" + network
                    + "' (startUrl " + store.networkCfg(network).path("startUrl").asText("") + ")");
        }
        JsonNode body = a.get("body");
        if (body != null && !body.isNull()) {
            if (!body.isTextual()) throw new IllegalArgumentException(p + "body deve ser string");
            if ("GET".equals(method)) throw new IllegalArgumentException(p + "GET nao leva body");
            if (body.asText().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException(p + "body acima de 16 KiB");
            }
        }
        JsonNode headers = a.get("headers");
        if (headers != null && !headers.isNull()) {
            if (!headers.isObject()) throw new IllegalArgumentException(p + "headers deve ser objeto");
            var it = headers.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (!e.getValue().isTextual() || e.getKey().isBlank()
                        || (e.getKey() + e.getValue().asText()).matches("(?s).*[\\r\\n].*")) {
                    throw new IllegalArgumentException(p + "header invalido '" + e.getKey() + "'");
                }
                if (e.getKey().equalsIgnoreCase("cookie")) {
                    throw new IllegalArgumentException(p + "header '" + e.getKey() + "' proibido: a sessao e a do navegador");
                }
            }
        }
        JsonNode st = a.get("expectStatus");
        if (st != null && !st.isNull()) {
            if (!st.isArray() || st.isEmpty()) throw new IllegalArgumentException(p + "expectStatus deve ser array nao vazio");
            for (JsonNode n : st) {
                if (!n.isInt() || n.asInt() < 100 || n.asInt() > 599) {
                    throw new IllegalArgumentException(p + "expectStatus com valor invalido: " + n);
                }
            }
        }
        JsonNode eb = a.get("expectBody");
        if (eb != null && !eb.isNull() && !eb.isTextual()) throw new IllegalArgumentException(p + "expectBody deve ser string");
        JsonNode csrf = a.get("csrf");
        if (csrf != null && !csrf.isNull()) {
            if (!csrf.isObject() || csrf.path("cookie").asText("").isBlank()
                    || (csrf.path("header").asText("").isBlank() && !csrf.hasNonNull("bodyField"))) {
                throw new IllegalArgumentException(p + "csrf exige {cookie, header e/ou bodyField}");
            }
            if (!csrf.path("header").asText("").isBlank() && !csrf.path("header").asText("").matches(HEADER_NAME)) {
                throw new IllegalArgumentException(p + "csrf.header invalido");
            }
            validateBodyField(a, csrf, "csrf", p);
        }
        JsonNode cr = a.get("credentials");
        if (cr != null && !cr.isNull() && (!cr.isTextual() || !CREDENTIALS.contains(cr.asText()))) {
            throw new IllegalArgumentException(p + "credentials invalido '" + (cr.isTextual() ? cr.asText() : cr.toString())
                    + "' (use include|omit|same-origin)");
        }
        validateAuth(a, p);
        String bfCsrf = a.path("csrf").path("bodyField").asText("");
        if (!bfCsrf.isEmpty() && bfCsrf.equals(a.path("auth").path("bodyField").asText(""))) {
            throw new IllegalArgumentException(p + "auth.bodyField '" + bfCsrf + "' repete o bodyField do csrf");
        }
    }

    private static final String HEADER_NAME = "[A-Za-z0-9!#$%&'*+.^_`|~-]+";

    /** Tipo do corpo pelo content-type dos headers literais: "json", "form" ou "" (outro/ausente). */
    static String bodyKind(JsonNode a) {
        String ct = a.path("headers").isObject() ? Recipes.contentType(a.path("headers")) : "";
        if (ct.contains("json")) return "json";
        if (ct.contains("application/x-www-form-urlencoded")) return "form";
        return "";
    }

    /**
     * bodyField opcional de csrf/auth: caminho por pontos onde o valor lido na pagina entra no
     * corpo. So com content-type JSON (corpo objeto JSON ou ausente) ou form-urlencoded; nunca em GET.
     */
    static void validateBodyField(JsonNode a, JsonNode spec, String nome, String p) {
        JsonNode bf = spec.get("bodyField");
        if (bf == null || bf.isNull()) return;
        if (!bf.isTextual() || !AUTH_PATH.matcher(bf.asText()).matches()) {
            throw new IllegalArgumentException(p + nome + ".bodyField invalido (caminho por pontos, ex.: variables.input.csrf_token)");
        }
        for (String seg : bf.asText().split("\\.")) {
            if (seg.equals("__proto__") || seg.equals("constructor") || seg.equals("prototype")) {
                throw new IllegalArgumentException(p + nome + ".bodyField com segmento reservado '" + seg + "'");
            }
        }
        if ("GET".equalsIgnoreCase(a.path("method").asText(""))) {
            throw new IllegalArgumentException(p + nome + ".bodyField nao vale em GET (sem corpo)");
        }
        String kind = bodyKind(a);
        if (kind.isEmpty()) {
            throw new IllegalArgumentException(p + nome + ".bodyField exige content-type JSON ou application/x-www-form-urlencoded em headers");
        }
        if ("form".equals(kind) && bf.asText().contains(".")) {
            throw new IllegalArgumentException(p + nome + ".bodyField em form e um nome de campo simples (sem pontos)");
        }
        String body = a.path("body").asText("");
        if ("json".equals(kind) && !body.isBlank()) {
            JsonNode parsed;
            try {
                parsed = Json.parse(body);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(p + nome + ".bodyField exige body JSON valido");
            }
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(p + nome + ".bodyField exige body JSON objeto");
            }
        }
    }

    static final Set<String> AUTH_FROM = Set.of("cookie", "localStorage", "sessionStorage", "element-json");
    private static final java.util.regex.Pattern AUTH_PATH =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_$-]+(\\.[A-Za-z0-9_$-]+)*$");

    /**
     * auth opcional: token lido NA PAGINA e posto num header e/ou no corpo (bodyField). from=cookie|localStorage|sessionStorage
     * exige key; element-json exige selector e path. path = caminho por pontos no JSON.
     * O header nao pode repetir um header literal nem o do csrf.
     */
    static void validateAuth(JsonNode a, String p) {
        JsonNode au = a.get("auth");
        if (au == null || au.isNull()) return;
        if (!au.isObject()) throw new IllegalArgumentException(p + "auth deve ser objeto");
        String header = au.path("header").asText("");
        boolean temBodyField = au.hasNonNull("bodyField");
        if (header.isBlank() && !temBodyField) {
            throw new IllegalArgumentException(p + "auth exige header valido e/ou bodyField");
        }
        if (!header.isBlank() && !header.matches(HEADER_NAME)) {
            throw new IllegalArgumentException(p + "auth exige header valido");
        }
        if (header.equalsIgnoreCase("cookie")) throw new IllegalArgumentException(p + "auth.header 'cookie' proibido");
        validateBodyField(a, au, "auth", p);
        JsonNode prefix = au.get("prefix");
        if (prefix != null && !prefix.isNull()
                && (!prefix.isTextual() || prefix.asText().matches("(?s).*[\\r\\n].*"))) {
            throw new IllegalArgumentException(p + "auth.prefix deve ser string sem quebra de linha");
        }
        String from = au.path("from").asText("");
        if (!AUTH_FROM.contains(from)) {
            throw new IllegalArgumentException(p + "auth.from invalido '" + from + "' (use cookie|localStorage|sessionStorage|element-json)");
        }
        if (from.equals("element-json")) {
            if (au.path("selector").asText("").isBlank()) throw new IllegalArgumentException(p + "auth element-json exige selector");
            if (au.path("path").asText("").isBlank()) throw new IllegalArgumentException(p + "auth element-json exige path");
        } else if (au.path("key").asText("").isBlank()) {
            throw new IllegalArgumentException(p + "auth from=" + from + " exige key");
        }
        JsonNode path = au.get("path");
        if (path != null && !path.isNull() && (!path.isTextual() || !AUTH_PATH.matcher(path.asText()).matches())) {
            throw new IllegalArgumentException(p + "auth.path invalido (caminho por pontos, ex.: session.currentAccount.accessJwt)");
        }
        if (header.isBlank()) return;
        JsonNode headers = a.path("headers");
        if (headers.isObject()) {
            for (var it = headers.fieldNames(); it.hasNext(); ) {
                if (it.next().equalsIgnoreCase(header)) {
                    throw new IllegalArgumentException(p + "auth.header '" + header + "' repete um header literal");
                }
            }
        }
        if (a.path("csrf").path("header").asText("").equalsIgnoreCase(header)) {
            throw new IllegalArgumentException(p + "auth.header '" + header + "' repete o header do csrf");
        }
    }

    /** Sufixos publicos de dois rotulos comuns (extrato minimo da Public Suffix List). */
    private static final Set<String> SUFIXOS_2_ROTULOS = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
            "com.br", "net.br", "org.br", "gov.br", "edu.br",
            "com.au", "net.au", "org.au", "co.nz", "org.nz",
            "com.co", "co.jp", "com.cn", "com.tw", "co.kr", "com.mx", "com.ar",
            "co.in", "com.in", "com.sg", "com.hk", "com.tr", "co.za");

    /**
     * Dominio registravel do host: ultimos 2 rotulos, ou 3 quando os 2 finais
     * formam um sufixo publico (ex.: co.uk, com.br). Sem isto, derivar pelos 2
     * ultimos rotulos de social.example.co.uk daria "co.uk" e liberaria qualquer
     * *.co.uk, inclusive attacker.co.uk.
     */
    static String dominioRegistravel(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String ult2 = p[p.length - 2] + "." + p[p.length - 1];
        boolean sufixoPublico = SUFIXOS_2_ROTULOS.contains(ult2)
                || (p[p.length - 1].length() == 2
                    && Set.of("com", "co", "org", "net", "ac", "gov", "edu", "or", "me").contains(p[p.length - 2]));
        if (sufixoPublico) return p[p.length - 3] + "." + ult2;
        return ult2;
    }

    /** Host igual/subdominio do dominio registravel do startUrl da rede, ou listado em requestHosts. */
    static boolean hostDaRede(Store store, String network, String host) {
        if (!store.knownNetworks().contains(network)) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        ObjectNode cfg = store.networkCfg(network);
        for (JsonNode extra : cfg.path("requestHosts")) {
            if (hostCasa(h, extra.asText(""))) return true;
        }
        String start;
        try {
            start = new java.net.URI(cfg.path("startUrl").asText("")).getHost();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        if (start == null) return false;
        String base = dominioRegistravel(start);
        return h.equals(base) || h.endsWith("." + base);
    }

    /** Padrao de requestHosts: host exato, ou "*.sufixo" (so subdominios, nao o proprio sufixo). */
    static boolean hostCasa(String host, String padrao) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        String pd = padrao.toLowerCase(java.util.Locale.ROOT).trim();
        if (pd.startsWith("*.")) {
            String suf = pd.substring(1);
            return suf.length() > 1 && suf.indexOf('*') < 0 && h.endsWith(suf) && h.length() > suf.length();
        }
        return !pd.isEmpty() && pd.indexOf('*') < 0 && h.equals(pd);
    }

    /** set_file so faz sentido se um passo de envio (click/key/request) vier depois dele no lote. */
    static void validateSetFileOrder(JsonNode actions) {
        boolean pendente = false;
        int idx = -1;
        int i = 0;
        for (JsonNode a : actions) {
            String kind = a.path("kind").asText("");
            if ("set_file".equals(kind)) {
                if (!pendente) idx = i;
                pendente = true;
            } else if (SEND_KINDS.contains(kind)) {
                pendente = false;
            }
            i++;
        }
        if (pendente) {
            throw new IllegalArgumentException("acao " + idx + ": set_file sem passo de envio (click/key/request) depois dele");
        }
    }

    /**
     * Arquivo do set_file: caminho absoluto, existente, regular, dentro de uma raiz de
     * uploadRoots (comparando caminhos reais: symlink e ".." nao escapam), extensao da lista
     * e tamanho ate uploadMaxMb. Retorna o caminho real validado (usado na execucao).
     */
    static Path validateUploadFile(Store store, JsonNode pathNode, int idx) {
        String p = "acao " + idx + ": set_file ";
        if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            throw new IllegalArgumentException(p + "exige path (string)");
        }
        String raw = pathNode.asText();
        if (raw.matches("(?s).*[\\r\\n\\x00].*")) throw new IllegalArgumentException(p + "path com caractere invalido");
        Path given;
        try {
            given = Path.of(raw);
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException(p + "path invalido");
        }
        if (!given.isAbsolute()) throw new IllegalArgumentException(p + "path deve ser absoluto");
        if (!Files.exists(given)) throw new IllegalArgumentException(p + "arquivo inexistente: " + given.getFileName());
        Path real;
        try {
            real = given.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(p + "arquivo ilegivel: " + given.getFileName());
        }
        if (!Files.isRegularFile(real)) throw new IllegalArgumentException(p + "path nao e arquivo regular");
        List<Path> roots = uploadRoots(store);
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(p + "sem raiz permitida (configure defaults.uploadRoots)");
        }
        boolean dentro = false;
        for (Path r : roots) {
            if (real.startsWith(r)) {
                dentro = true;
                break;
            }
        }
        if (!dentro) throw new IllegalArgumentException(p + "arquivo fora das raizes permitidas " + roots);
        String nome = real.getFileName().toString();
        int dot = nome.lastIndexOf('.');
        String ext = dot < 0 ? "" : nome.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!UPLOAD_EXTS.contains(ext)) {
            throw new IllegalArgumentException(p + "extensao '" + ext + "' nao aceita (use png|jpg|jpeg|webp|gif|mp4|mov)");
        }
        long maxMb = store.defaultsCfg().path("uploadMaxMb").asLong(DEFAULT_UPLOAD_MAX_MB);
        long tamanho;
        try {
            tamanho = Files.size(real);
        } catch (IOException e) {
            throw new IllegalArgumentException(p + "arquivo ilegivel: " + nome);
        }
        if (tamanho > maxMb * 1024L * 1024L) {
            throw new IllegalArgumentException(p + "arquivo de " + tamanho + " bytes acima de uploadMaxMb=" + maxMb);
        }
        return real;
    }

    /**
     * Raizes de upload (caminhos reais existentes): defaults.uploadRoots ou, sem ele, a pasta
     * configurada em defaults.uploadRoots ou, opcionalmente, derivada do caminho do gate.
     */
    static List<Path> uploadRoots(Store store) {
        List<String> brutas = new ArrayList<>();
        JsonNode cfg = store.defaultsCfg().get("uploadRoots");
        if (cfg != null && cfg.isArray()) {
            for (JsonNode r : cfg) if (r.isTextual() && !r.asText().isBlank()) brutas.add(r.asText());
        } else {
            String gate = store.defaultsCfg().path("gate").path("config").asText("");
            if (!gate.isBlank()) {
                Path projeto = Path.of(gate).toAbsolutePath().getParent();
                projeto = projeto == null ? null : projeto.getParent();
                if (projeto != null) brutas.add(projeto.resolve("marketing").toString());
            }
        }
        List<Path> out = new ArrayList<>();
        for (String b : brutas) {
            try {
                Path r = Path.of(b);
                if (r.isAbsolute() && Files.isDirectory(r)) out.add(r.toRealPath());
            } catch (IOException | java.nio.file.InvalidPathException ignored) {
                // raiz inexistente ou invalida nao conta
            }
        }
        return out;
    }

    /** Teto da espera fixa (wait ms). */
    static final int WAIT_MAX_MS = 30_000;
    /**
     * Teto do timeoutMs do wait com expect (11 min: verificacao de conteudo do TikTok leva ~10).
     * O wait com expect sai assim que o texto aparece; o teto so vale se nao aparecer.
     */
    static final int WAIT_EXPECT_MAX_MS = 660_000;
    /**
     * Soma maxima das esperas de um lote (ms + timeoutMs, padrao 15000 quando ausente). O
     * lote roda numa unica chamada MCP: o cliente precisa de prazo maior (Codex:
     * tool_timeout_sec = 900 em ~/.codex/config.toml).
     */
    static final int WAIT_BATCH_MAX_MS = 720_000;
    /** timeoutMs padrao do wait com expect. */
    static final int WAIT_DEFAULT_TIMEOUT_MS = 15_000;
    /** waitMs padrao do capture_ids (estabilizacao antes do passo publico). */
    static final int WAIT_CAPTURE_DEFAULT_MS = 10_000;
    /** stableMs do capture_ids: estabilidade minima do conjunto NAO VAZIO (intervalo e padrao). */
    static final long STABLE_MS_MIN = 1_000;
    static final long STABLE_MS_MAX = 30_000;
    static final long STABLE_MS_PADRAO = 1_000;
    /** Nome de atributo aceito em verify_attr. */
    private static final java.util.regex.Pattern ATTR_NAME = java.util.regex.Pattern.compile("[A-Za-z_:][-A-Za-z0-9_:.]{0,63}");
    /** Nome do par capture_ids/verify_new_id. */
    private static final java.util.regex.Pattern ID_NAME = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** click: clickMode opcional mouse|dom; dom (evento sintetico) recusado nas redes com telemetria de gesto. */
    static void validateClickMode(JsonNode a, int idx) {
        JsonNode ip = a.get("ifPresent");
        if (ip != null && !ip.isNull() && !ip.isBoolean()) {
            throw new IllegalArgumentException("acao " + idx + ": click ifPresent deve ser true|false");
        }
        JsonNode m = a.get("clickMode");
        if (m == null || m.isNull()) return;
        String p = "acao " + idx + ": click ";
        if (!m.isTextual() || !RealClick.MODES.contains(m.asText())) {
            throw new IllegalArgumentException(p + "clickMode deve ser mouse|dom");
        }
        String net = a.path("network").asText("");
        if (RealClick.MODE_DOM.equals(m.asText()) && RealClick.DOM_BLOCKED_NETWORKS.contains(net)) {
            throw new IllegalArgumentException(p + "clickMode dom (el.click(), isTrusted=false) proibido em '" + net
                    + "': a rede coleta telemetria de gesto; use mouse");
        }
    }

    /** verify_attr: selector, attr (nome de atributo) e expect (texto exato, pode ser vazio). */
    static void validateVerifyAttr(JsonNode a, int idx) {
        String p = "acao " + idx + ": verify_attr ";
        if (a.path("selector").asText("").isBlank()) throw new IllegalArgumentException(p + "exige selector");
        if (!ATTR_NAME.matcher(a.path("attr").asText("")).matches()) {
            throw new IllegalArgumentException(p + "exige attr (nome de atributo, ex.: aria-checked)");
        }
        if (!a.path("expect").isTextual()) throw new IllegalArgumentException(p + "exige expect (texto exato do atributo)");
    }

    /** {@code xpath:} sem expressao: a string nao esta em branco, mas a consulta sim. */
    static boolean xpathVazio(String selector) {
        return selector.startsWith("xpath:") && selector.substring(6).isBlank();
    }

    /**
     * verify_absent: selector obrigatorio; expect opcional (texto: so conta elementos que o
     * contem); timeoutMs opcional (1..WAIT_EXPECT_MAX_MS, padrao WAIT_DEFAULT_TIMEOUT_MS): espera
     * ate o elemento sumir. Nao e publico.
     */
    static void validateVerifyAbsent(JsonNode a, int idx) {
        String p = "acao " + idx + ": verify_absent ";
        if (a.path("selector").asText("").isBlank()) throw new IllegalArgumentException(p + "exige selector");
        if (xpathVazio(a.path("selector").asText(""))) {
            throw new IllegalArgumentException(p + "seletor 'xpath:' sem expressao");
        }
        JsonNode e = a.get("expect");
        if (e != null && !e.isNull() && (!e.isTextual() || e.asText().isBlank())) {
            throw new IllegalArgumentException(p + "expect, se presente, deve ser texto nao vazio");
        }
        JsonNode t = a.get("timeoutMs");
        if (t != null && !t.isNull() && (!t.isIntegralNumber() || t.asLong() < 1 || t.asLong() > WAIT_EXPECT_MAX_MS)) {
            throw new IllegalArgumentException(p + "timeoutMs deve ser inteiro entre 1 e " + WAIT_EXPECT_MAX_MS);
        }
    }

    /**
     * capture_ids: guarda o conjunto de ids de uma lista antes do passo publico.
     * selector obrigatorio (itens da lista); parentSelector opcional; attr (ATTR_NAME ou @text);
     * name ([a-z][a-z0-9_]{0,31}); attrSelector css puro; idRegex compila; stableMs 1000..30000
     * (padrao 1000); minIds 0..1000 (padrao 0); waitMs >= stableMs + 1000 e ate MAX.
     * {@code @text} deve apontar para texto do corpo, sem contadores nem horario variavel.
     */
    static void validateCaptureIds(JsonNode a, int idx) {
        validateIdCommon(a, idx, "capture_ids");
        JsonNode minIds = a.get("minIds");
        if (minIds != null && !minIds.isNull()
                && (!minIds.isIntegralNumber() || minIds.asLong() < 0 || minIds.asLong() > 1_000)) {
            throw new IllegalArgumentException("acao " + idx + ": capture_ids minIds deve ser inteiro entre 0 e 1000");
        }
        JsonNode s = a.get("stableMs");
        long stable = STABLE_MS_PADRAO;
        if (s != null && !s.isNull()) {
            if (!s.isIntegralNumber() || s.asLong() < STABLE_MS_MIN || s.asLong() > STABLE_MS_MAX) {
                throw new IllegalArgumentException("acao " + idx + ": capture_ids stableMs deve ser inteiro entre "
                        + STABLE_MS_MIN + " e " + STABLE_MS_MAX);
            }
            stable = s.asLong();
        }
        JsonNode w = a.get("waitMs");
        if (w != null && !w.isNull() && (!w.isIntegralNumber() || w.asLong() > WAIT_EXPECT_MAX_MS)) {
            throw new IllegalArgumentException("acao " + idx + ": capture_ids waitMs deve ser inteiro ate " + WAIT_EXPECT_MAX_MS);
        }
        if (a.path("waitMs").asLong(WAIT_CAPTURE_DEFAULT_MS) < stable + 1_000) {
            throw new IllegalArgumentException("acao " + idx + ": capture_ids waitMs deve ser >= stableMs + 1000 (stableMs=" + stable + ")");
        }
        validateResponseProofFlag(a, idx, "capture_ids");
        if (a.path("responseProof").asBoolean(false)) {
            String expect = a.path("expect").asText("");
            if (expect.length() < RESPONSE_PROOF_EXPECT_MIN || expect.length() > RESPONSE_PROOF_EXPECT_MAX) {
                throw new IllegalArgumentException("acao " + idx + ": capture_ids com responseProof exige expect com "
                        + RESPONSE_PROOF_EXPECT_MIN + ".." + RESPONSE_PROOF_EXPECT_MAX + " caracteres (texto aprovado)");
            }
            String net = a.path("network").asText("").toLowerCase(java.util.Locale.ROOT);
            if (!RESPONSE_PROOF_NETWORKS.contains(net)) {
                throw new IllegalArgumentException("acao " + idx + ": capture_ids com responseProof so vale em ig|tt|yt|facebook|bluesky|mastodon|x|pinterest|reddit");
            }
        }
    }

    /**
     * verify_new_id: espera um item cujo id nao esta no conjunto do capture_ids de mesmo name.
     * Mesmos campos do capture_ids, mais expect (texto nao vazio) e timeoutMs 1..MAX.
     * {@code @text} deve apontar para texto do corpo, sem contadores nem horario variavel.
     */
    static void validateVerifyNewId(JsonNode a, int idx) {
        validateIdCommon(a, idx, "verify_new_id");
        JsonNode e = a.get("expect");
        if (e != null && !e.isNull() && (!e.isTextual() || e.asText().isBlank())) {
            throw new IllegalArgumentException("acao " + idx + ": verify_new_id expect, se presente, deve ser texto nao vazio");
        }
        JsonNode t = a.get("timeoutMs");
        if (t != null && !t.isNull() && (!t.isIntegralNumber() || t.asLong() < 1 || t.asLong() > WAIT_EXPECT_MAX_MS)) {
            throw new IllegalArgumentException("acao " + idx + ": verify_new_id timeoutMs deve ser inteiro entre 1 e " + WAIT_EXPECT_MAX_MS);
        }
        validateResponseProofFlag(a, idx, "verify_new_id");
        if (a.path("responseProof").asBoolean(false)) {
            String expect = a.path("expect").asText("");
            if (expect.length() < RESPONSE_PROOF_EXPECT_MIN || expect.length() > RESPONSE_PROOF_EXPECT_MAX) {
                throw new IllegalArgumentException("acao " + idx + ": verify_new_id com responseProof exige expect com "
                        + RESPONSE_PROOF_EXPECT_MIN + ".." + RESPONSE_PROOF_EXPECT_MAX + " caracteres (mesmo texto do capture)");
            }
        }
    }

    private static void validateResponseProofFlag(JsonNode a, int idx, String kind) {
        JsonNode rp = a.get("responseProof");
        if (rp != null && !rp.isBoolean()) {
            throw new IllegalArgumentException("acao " + idx + ": " + kind + " responseProof deve ser boolean quando presente");
        }
    }

    /** Regras comuns aos dois passos (selector, parentSelector, attr, name, attrSelector, idRegex). */
    private static void validateIdCommon(JsonNode a, int idx, String kind) {
        String p = "acao " + idx + ": " + kind + " ";
        String selector = a.path("selector").asText("");
        if (selector.isBlank()) throw new IllegalArgumentException(p + "exige selector");
        if (xpathVazio(selector)) throw new IllegalArgumentException(p + "seletor 'xpath:' sem expressao");
        String parent = a.path("parentSelector").asText("");
        if (!parent.isBlank()) {
            if (xpathVazio(parent)) throw new IllegalArgumentException(p + "parentSelector 'xpath:' sem expressao");
            try {
                ShadowDom.parts(parent);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(p + "parentSelector " + e.getMessage());
            }
            if (selector.startsWith("id:")) {
                throw new IllegalArgumentException(p + "selector id: nao vale com parentSelector");
            }
            if (selector.startsWith("xpath:")) {
                String expr = selector.substring(6).trim();
                if (!xpathRelativoComParent(expr)) {
                    throw new IllegalArgumentException(p + "selector xpath com parentSelector precisa ser relativo (.//...)");
                }
            }
        }
        String attr = a.path("attr").asText("");
        if (!"@text".equals(attr) && !ATTR_NAME.matcher(attr).matches()) {
            throw new IllegalArgumentException(p + "exige attr (nome de atributo, ex.: href, ou @text)");
        }
        if (!ID_NAME.matcher(a.path("name").asText("")).matches()) {
            throw new IllegalArgumentException(p + "exige name ([a-z][a-z0-9_]{0,31})");
        }
        String as = a.path("attrSelector").asText("");
        if (!as.isBlank()) {
            String css = as.startsWith("css:") ? as.substring(4) : as;
            if (as.startsWith("xpath:") || as.startsWith("id:") || css.contains(ShadowDom.PIERCE) || css.isBlank()) {
                throw new IllegalArgumentException(p + "attrSelector deve ser css puro (sem xpath:/id:/>>>)");
            }
        }
        String ir = a.path("idRegex").asText("");
        if (!ir.isBlank()) {
            if (ir.length() > 200) throw new IllegalArgumentException(p + "idRegex acima de 200 caracteres");
            try {
                java.util.regex.Pattern.compile(ir);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalArgumentException(p + "idRegex invalido: " + e.getMessage());
            }
        }
    }

    private static boolean xpathRelativoComParent(String expr) {
        for (String alternativa : expr.split("\\|", -1)) {
            int inicio = 0;
            while (inicio < alternativa.length()
                    && (Character.isWhitespace(alternativa.charAt(inicio)) || alternativa.charAt(inicio) == '(')) {
                inicio++;
            }
            if (!alternativa.startsWith("./", inicio)) return false;
        }
        return true;
    }

    /**
     * Paridade capture_ids/verify_new_id no lote: name de capture_ids nao repete; todo
     * verify_new_id precisa de um capture_ids ANTERIOR com o mesmo name e selector, parentSelector,
     * attr, attrSelector e idRegex (ausente == ausente).
     */
    static void validateIdPairs(JsonNode actions) {
        java.util.Map<String, Integer> captures = new java.util.HashMap<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        int i = 0;
        for (JsonNode a : actions) {
            String kind = a.path("kind").asText("");
            String name = a.path("name").asText("");
            if ("capture_ids".equals(kind)) {
                if (!vistos.add(name)) {
                    throw new IllegalArgumentException("acao " + i + ": capture_ids com name repetido '" + name + "'");
                }
                captures.put(name, i);
            } else if ("verify_new_id".equals(kind)) {
                Integer capIdx = captures.get(name);
                if (capIdx == null) {
                    throw new IllegalArgumentException("acao " + i + ": verify_new_id '" + name + "' sem capture_ids anterior");
                }
                JsonNode c = actions.get(capIdx);
                if (!c.path("selector").asText("").equals(a.path("selector").asText(""))
                        || !c.path("parentSelector").asText("").equals(a.path("parentSelector").asText(""))
                        || !c.path("attr").asText("").equals(a.path("attr").asText(""))
                        || !c.path("attrSelector").asText("").equals(a.path("attrSelector").asText(""))
                        || !c.path("idRegex").asText("").equals(a.path("idRegex").asText(""))) {
                    throw new IllegalArgumentException("acao " + i + ": verify_new_id '" + name
                            + "' com selector/parentSelector/attr/attrSelector/idRegex diferentes do capture_ids '" + name
                            + "' (o par precisa olhar a MESMA lista)");
                }
                boolean rpC = c.path("responseProof").asBoolean(false);
                boolean rpV = a.path("responseProof").asBoolean(false);
                if (rpC != rpV) {
                    throw new IllegalArgumentException("acao " + i + ": verify_new_id '" + name
                            + "' com responseProof diferente do capture_ids (o par opt-in precisa do mesmo valor)");
                }
                if (rpC) {
                    String eC = c.path("expect").asText("");
                    String eV = a.path("expect").asText("");
                    if (!eC.equals(eV) && (eV.length() < RESPONSE_PROOF_EXPECT_MIN || !eC.contains(eV))) {
                        throw new IllegalArgumentException("acao " + i + ": verify_new_id '" + name
                                + "' com expect que nao e o texto aprovado do capture_ids nem trecho dele (>= 8 caracteres)");
                    }
                    boolean digitado = false;
                    for (int j = capIdx + 1; j < i; j++) {
                        JsonNode t = actions.get(j);
                        if ("type".equals(t.path("kind").asText("")) && eC.equals(t.path("text").asText(""))) {
                            digitado = true;
                            break;
                        }
                        if ("request".equals(t.path("kind").asText("")) && "POST".equalsIgnoreCase(t.path("method").asText(""))) {
                            String body = t.path("body").asText("");
                            String escaped = Json.write(com.fasterxml.jackson.databind.node.TextNode.valueOf(eC));
                            String escapedText = escaped.substring(1, escaped.length() - 1);
                            if (body.contains(eC) || body.contains(escapedText) || containsDecoded(body, eC, escapedText)) { digitado = true; break; }
                        }
                    }
                    if (!digitado) {
                        throw new IllegalArgumentException("acao " + i + ": capture_ids '" + name
                                + "' com responseProof exige um type ou request POST posterior com o texto EXATO do expect");
                    }
                }
            }
            i++;
        }
    }

    private static boolean containsDecoded(String body, String approved, String escaped) {
        try {
            String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
            return decoded.contains(approved) || decoded.contains(escaped);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Marcador valido no goto: [[novo:NOME]] (NOME casa name do par capture/verify). */
    private static final java.util.regex.Pattern MARCADOR_GOTO_OK =
            java.util.regex.Pattern.compile("\\[\\[novo:[A-Za-z][A-Za-z0-9_]*]]");
    /** Qualquer [[...]] no url do goto: cada um precisa ser um marcador valido. */
    private static final java.util.regex.Pattern MARCADOR_GOTO_QUALQUER =
            java.util.regex.Pattern.compile("\\[\\[.*?]]");

    /**
     * Marcador [[novo:NOME]] no goto: todo [[...]] tem de casar [[novo:NOME]]; tirando os
     * marcadores (cada um vira X) a url tem de ser https com host; e o marcador so vale no
     * path (depois da primeira / seguinte a ://), nunca no host.
     */
    private static void validateGotoMarcador(String url, int idx) {
        java.util.regex.Matcher m = MARCADOR_GOTO_QUALQUER.matcher(url);
        StringBuilder semMarcador = new StringBuilder();
        int ultimo = 0;
        int primeiro = -1;
        while (m.find()) {
            if (!MARCADOR_GOTO_OK.matcher(m.group()).matches()) {
                throw new IllegalArgumentException("acao " + idx + ": goto com marcador invalido (use [[novo:NOME]])");
            }
            if (primeiro < 0) primeiro = m.start();
            semMarcador.append(url, ultimo, m.start()).append('X');
            ultimo = m.end();
        }
        semMarcador.append(url, ultimo, url.length());
        if (semMarcador.indexOf("[[") >= 0) {
            // "[[" sem fecho: nao e um marcador completo
            throw new IllegalArgumentException("acao " + idx + ": goto com marcador invalido (use [[novo:NOME]])");
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(semMarcador.toString());
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        int fimScheme = url.indexOf("://");
        int inicioPath = fimScheme < 0 ? -1 : url.indexOf('/', fimScheme + 3);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || inicioPath < 0 || primeiro <= inicioPath) {
            throw new IllegalArgumentException("acao " + idx + ": [[novo:...]] so no path da url");
        }
    }

    /** goto com [[novo:NOME]] exige verify_new_id com name NOME em indice anterior do mesmo lote. */
    static void validateGotoIdNovo(JsonNode actions) {
        Set<String> verificados = new HashSet<>();
        int i = 0;
        for (JsonNode a : actions) {
            String kind = a.path("kind").asText("");
            if ("verify_new_id".equals(kind)) {
                verificados.add(a.path("name").asText(""));
            } else if ("goto".equals(kind)) {
                java.util.regex.Matcher m = MARCADOR_GOTO_OK.matcher(a.path("url").asText(""));
                while (m.find()) {
                    String marcador = m.group();
                    String nome = marcador.substring("[[novo:".length(), marcador.length() - 2);
                    if (!verificados.contains(nome)) {
                        throw new IllegalArgumentException("acao " + i + ": [[novo:" + nome
                                + "]] sem verify_new_id anterior com esse name");
                    }
                }
            }
            i++;
        }
    }

    static final String CONF_FORTE = "forte";
    static final String CONF_FRACA = "fraca";
    static final String CONF_AUSENTE = "ausente";
    static final String CONF_SEM_PUBLICO = "sem_publico";

    /**
     * Como o lote prova o efeito do ULTIMO passo publico (licao de 22/10: o YouTube salvou
     * privado e o lote "publicou"). forte = depois dele vem verify_attr ou verify_text/wait
     * com selector (estado de um elemento), ou verify_new_id cujo capture_ids esta antes do
     * passo publico, ou o proprio passo e um request com expectBody e status so 2xx.
     * verify_absent so e forte acompanhado de uma confirmacao positiva da pagina (verify_attr
     * ou verify_text/wait com selector) depois do passo publico: sozinho ele tambem passa numa
     * pagina de login ou ainda vazia, onde o item "sumiu" sem a exclusao ter acontecido.
     * verify_text/wait com seletor de pagina inteira (main/body/html/article) conta como fraca:
     * achar texto em qualquer lugar da pagina nao prova o estado de um elemento.
     * fraca = so texto solto na pagina (pode ser o proprio texto digitado ainda no editor) ou
     * verify_absent sem confirmacao positiva; ausente = nada confere; sem_publico = lote sem
     * passo publico.
     */
    static String conferencia(JsonNode actions) {
        int last = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (Guard.isPublic(actions.get(i).path("kind").asText(""))) last = i;
        }
        if (last < 0) return CONF_SEM_PUBLICO;
        if (provaPorIdNovo(actions)) return CONF_FORTE;
        boolean fraca = false;
        if ("request".equals(actions.get(last).path("kind").asText(""))) {
            if (requestSeConfere(actions.get(last))) return CONF_FORTE;
            fraca = true; // status ate pode bater, mas o corpo nao foi conferido: segue procurando verify depois
        }
        boolean positiva = false;
        boolean ausencia = false;
        for (int i = last + 1; i < actions.size(); i++) {
            JsonNode a = actions.get(i);
            String k = a.path("kind").asText("");
            boolean comSelector = !a.path("selector").asText("").isBlank();
            if ("verify_attr".equals(k)) {
                positiva = true;
            } else if ("verify_absent".equals(k)) {
                ausencia = true;
            } else if ("verify_text".equals(k) || ("wait".equals(k) && a.hasNonNull("expect"))) {
                if (comSelector && !seletorDePaginaInteira(a.path("selector").asText(""))) {
                    positiva = true;
                } else {
                    fraca = true;
                }
            }
        }
        if (positiva) return CONF_FORTE;
        if (ausencia) return CONF_FRACA;
        return fraca ? CONF_FRACA : CONF_AUSENTE;
    }

    /** Seletores de pagina inteira (apos trim e minusculas): nao provam estado de um elemento. */
    private static final Set<String> PAGINA_INTEIRA = Set.of(
            "main", "body", "html", "article",
            "css:main", "css:body", "css:html", "css:article",
            "xpath://main", "xpath://body", "xpath://html", "xpath://article",
            "//main", "//body");

    static boolean seletorDePaginaInteira(String sel) {
        return sel != null && PAGINA_INTEIRA.contains(sel.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Request so prova o efeito com expectBody e com status esperados todos 2xx (ou o padrao 2xx). */
    static boolean requestSeConfere(JsonNode a) {
        if (a.path("expectBody").asText("").isEmpty()) return false;
        for (JsonNode n : a.path("expectStatus")) {
            if (n.asInt() < 200 || n.asInt() > 299) return false;
        }
        return true;
    }

    /**
     * Prova por id novo: um verify_new_id DEPOIS do ultimo passo publico, cujo capture_ids de
     * mesmo name esta ANTES desse passo publico. Prova forte de criacao/resposta.
     */
    static boolean provaPorIdNovo(JsonNode actions) {
        int last = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (Guard.isPublic(actions.get(i).path("kind").asText(""))) last = i;
        }
        if (last < 0) return false;
        for (int i = last + 1; i < actions.size(); i++) {
            JsonNode a = actions.get(i);
            if (!"verify_new_id".equals(a.path("kind").asText(""))) continue;
            String name = a.path("name").asText("");
            for (int j = 0; j < last; j++) {
                JsonNode c = actions.get(j);
                if ("capture_ids".equals(c.path("kind").asText("")) && name.equals(c.path("name").asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Soma das esperas do lote (wait e o prazo do verify_absent) ate WAIT_BATCH_MAX_MS. */
    static void validateWaitBudget(JsonNode actions) {
        long total = 0;
        for (JsonNode a : actions) {
            String k = a.path("kind").asText();
            if ("verify_absent".equals(k)) {
                total += a.path("timeoutMs").asLong(WAIT_DEFAULT_TIMEOUT_MS);
                continue;
            }
            if ("capture_ids".equals(k)) {
                total += a.path("waitMs").asLong(WAIT_CAPTURE_DEFAULT_MS);
                continue;
            }
            if ("verify_new_id".equals(k)) {
                total += a.path("timeoutMs").asLong(WAIT_DEFAULT_TIMEOUT_MS);
                continue;
            }
            if (!"wait".equals(k)) continue;
            total += a.hasNonNull("ms") ? a.get("ms").asLong() : a.path("timeoutMs").asLong(WAIT_DEFAULT_TIMEOUT_MS);
        }
        if (total > WAIT_BATCH_MAX_MS) {
            throw new IllegalArgumentException("esperas do lote somam " + total + " ms (max " + WAIT_BATCH_MAX_MS
                    + " por lote: o lote roda numa unica chamada MCP). Reduza timeoutMs ou divida o lote.");
        }
    }

    /** Seletor com >>> (shadow DOM) precisa ser css: com trechos nao vazios; xpath: exige expressao. */
    static void validateSelector(JsonNode a, int idx) {
        String sel = a.path("selector").asText("");
        try {
            ShadowDom.parts(sel);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("acao " + idx + ": " + e.getMessage());
        }
        if (xpathVazio(sel)) {
            throw new IllegalArgumentException("acao " + idx + ": seletor 'xpath:' sem expressao");
        }
    }

    /** wait: ms (1..30000) OU expect (texto) com timeoutMs opcional (1..660000, padrao 15000). */
    static void validateWait(JsonNode a, int idx) {
        String p = "acao " + idx + ": wait ";
        boolean temMs = a.has("ms") && !a.get("ms").isNull();
        boolean temExpect = a.has("expect") && !a.get("expect").isNull();
        if (temMs == temExpect) throw new IllegalArgumentException(p + "exige ms OU expect (um dos dois)");
        if (temMs) {
            JsonNode ms = a.get("ms");
            if (!ms.isIntegralNumber() || ms.asLong() < 1 || ms.asLong() > WAIT_MAX_MS) {
                throw new IllegalArgumentException(p + "ms deve ser inteiro entre 1 e " + WAIT_MAX_MS);
            }
            return;
        }
        if (!a.get("expect").isTextual() || a.get("expect").asText().isBlank()) {
            throw new IllegalArgumentException(p + "expect deve ser texto nao vazio");
        }
        JsonNode t = a.get("timeoutMs");
        if (t != null && !t.isNull() && (!t.isIntegralNumber() || t.asLong() < 1 || t.asLong() > WAIT_EXPECT_MAX_MS)) {
            throw new IllegalArgumentException(p + "timeoutMs deve ser inteiro entre 1 e " + WAIT_EXPECT_MAX_MS);
        }
    }

    static void validateExtract(JsonNode a, int idx) {
        String what = a.path("what").asText("");
        if (!what.equals("links") && !what.equals("text")) {
            throw new IllegalArgumentException("acao " + idx + ": extract exige what=links|text");
        }
        JsonNode m = a.get("match");
        if (m != null && !m.isNull() && !m.isTextual()) throw new IllegalArgumentException("acao " + idx + ": extract match deve ser string");
    }

    /** SHA-256 (hex minusculo) do conteudo de um arquivo, lido em fluxo. */
    static String sha256Arquivo(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Entrada de "midias" do lote aprovado para o passo, ou null. */
    static JsonNode midiaDoPasso(JsonNode batch, int passo) {
        for (JsonNode m : batch.path("midias")) {
            if (m.path("passo").asInt(-1) == passo) return m;
        }
        return null;
    }

    /** Entradas das midias aprovadas para o passo, em ordem de indice. */
    static List<JsonNode> midiasDoPasso(JsonNode batch, int passo) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : batch.path("midias")) {
            if (m.path("passo").asInt(-1) == passo && m.has("indice")) out.add(m);
        }
        out.sort(java.util.Comparator.comparingInt(m -> m.path("indice").asInt(-1)));
        return out;
    }

    /** Nome seguro da copia aprovada (so o nome do arquivo, dentro de batches/). */
    static Path copiaAprovada(Store store, JsonNode midia) {
        String bruto = midia.path("copia").asText("");
        Path nome = bruto.isBlank() ? null : Path.of(bruto).getFileName();
        if (nome == null) throw new IllegalStateException("lote aprovado com copia de midia sem nome");
        return store.batchesDir().resolve(nome.toString());
    }

    /**
     * Vinculo da midia na aprovacao: para cada set_file, valida o arquivo, grava
     * o SHA-256 em "midias" e copia o conteudo para batches/ (copia imutavel que
     * a execucao usa). Trocar o arquivo depois muda o digest e a aprovacao cai.
     */
    private ArrayNode vincularMidias(String id, JsonNode actions) throws IOException {
        ArrayNode midias = Json.M.createArrayNode();
        List<Path> criadas = new ArrayList<>();
        try {
        int i = 0;
        for (JsonNode a : actions) {
            if ("set_file".equals(a.path("kind").asText(""))) {
                boolean multiplo = a.path("paths").isArray();
                int j = 0;
                for (JsonNode item : multiplo ? a.path("paths") : List.of(a.get("path"))) {
                    Path real;
                    try {
                        real = validateUploadFile(store, item, i);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException("midia do passo " + (i + 1) + " invalida na aprovacao: " + e.getMessage(), e);
                    }
                    String hash = sha256Arquivo(real);
                    String sufixo = multiplo ? "-" + j : "";
                    Path copia = store.batchesDir().resolve(id + "-media-" + i + sufixo + "-" + real.getFileName());
                    Files.copy(real, copia, StandardCopyOption.REPLACE_EXISTING);
                    criadas.add(copia);
                    if (!hash.equals(sha256Arquivo(copia))) {
                        Files.deleteIfExists(copia);
                        throw new IllegalStateException("copia da midia do passo " + (i + 1) + " divergiu na aprovacao");
                    }
                    ObjectNode m = midias.addObject();
                    m.put("passo", i);
                    if (multiplo) m.put("indice", j);
                    m.put("arquivo", real.getFileName().toString());
                    m.put("sha256", hash);
                    m.put("bytes", Files.size(real));
                    m.put("copia", copia.getFileName().toString());
                    j++;
                }
            }
            i++;
        }
        return midias;
        } catch (IOException | RuntimeException e) {
            for (Path copia : criadas) {
                try { Files.deleteIfExists(copia); } catch (IOException ignored) { }
            }
            throw e;
        }
    }

    /** Recusa a execucao se qualquer midia aprovada sumiu, mudou ou teve a copia adulterada. */
    private void verificarMidias(String id, JsonNode b) throws IOException {
        String net = b.path("network").asText(null);
        JsonNode actions = b.path("actions");
        int i = 0;
        for (JsonNode a : actions) {
            if ("set_file".equals(a.path("kind").asText(""))) {
                boolean multiplo = a.path("paths").isArray();
                List<JsonNode> aprovadas = multiplo ? midiasDoPasso(b, i) : java.util.Collections.singletonList(midiaDoPasso(b, i));
                int esperadaQtd = multiplo ? a.path("paths").size() : 1;
                boolean indicesOk = !multiplo || (aprovadas.size() == esperadaQtd
                        && java.util.stream.IntStream.range(0, esperadaQtd).allMatch(j -> aprovadas.get(j).path("indice").asInt(-1) == j));
                if (aprovadas.size() != esperadaQtd || !indicesOk || aprovadas.stream().anyMatch(m -> m == null || m.path("sha256").asText("").isBlank())) {
                    recusar(id, net, "midia_sem_hash");
                    throw new IllegalStateException("lote " + id + " aprovado sem digest da midia do passo " + (i + 1) + "; rejeite e reaprove");
                }
                int j = 0;
                for (JsonNode m : aprovadas) {
                    String esperado = m.path("sha256").asText();
                    JsonNode item = multiplo ? a.path("paths").get(j) : a.get("path");
                    Path real;
                    try {
                        real = validateUploadFile(store, item, i);
                    } catch (IllegalArgumentException e) {
                        recusar(id, net, "midia_invalida");
                        throw new IllegalStateException("midia do passo " + (i + 1) + " do lote " + id + " invalida: " + e.getMessage(), e);
                    }
                    if (!esperado.equals(sha256Arquivo(real))) {
                        recusar(id, net, "midia_divergente");
                        throw new IllegalStateException("arquivo do passo " + (i + 1) + " do lote " + id
                                + " foi alterado depois da aprovacao; rejeite e reaprove");
                    }
                    Path copia = copiaAprovada(store, m);
                    if (!Files.isRegularFile(copia) || !esperado.equals(sha256Arquivo(copia))) {
                        recusar(id, net, "copia_divergente");
                        throw new IllegalStateException("copia aprovada da midia do passo " + (i + 1) + " do lote " + id
                                + " ausente ou alterada; rejeite e reaprove");
                    }
                    j++;
                }
            }
            i++;
        }
    }

    public JsonNode requireApproved(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("batchId invalido: '" + id + "'");
        Path executed = store.batchesDir().resolve(id + ".executed.json");
        if (Files.exists(executed)) {
            throw new IllegalStateException("lote " + id + " ja executado; lote nao reexecuta. Crie um novo rascunho e peca nova aprovacao.");
        }
        if (Files.exists(running(id))) {
            throw new IllegalStateException("lote " + id + " ja esta em execucao (ou a execucao anterior morreu no meio); lote nao reexecuta. Confira o journal.");
        }
        Path approved = store.batchesDir().resolve(id + ".approved.json");
        if (!Files.exists(approved)) {
            Path draft = store.batchesDir().resolve(id + ".draft.json");
            if (Files.exists(draft)) {
                throw new IllegalStateException("lote " + id + " e um RASCUNHO nao aprovado. Mostre o lote ao dono; so ele pode aprovar: bin/social-mcp --cli approve " + id);
            }
            throw new IllegalStateException("lote " + id + " nao encontrado (procure com bin/social-mcp --cli batches)");
        }
        JsonNode b = Json.parse(Files.readString(approved, StandardCharsets.UTF_8));
        String net = b.path("network").asText(null);
        String hash = b.path("actionsHash").asText("");
        if (hash.isBlank()) {
            recusar(id, net, "sem_hash");
            throw new IllegalStateException("lote " + id + " aprovado sem gate/hash (versao antiga); rejeite e reaprove");
        }
        if (!hash.equals(TextGate.hashActions(b.path("actions")))) {
            recusar(id, net, "hash_divergente");
            throw new IllegalStateException("lote " + id + " foi alterado depois da aprovacao; rejeite e reaprove");
        }
        verificarMidias(id, b);
        long horas = store.defaultsCfg().path("gate").path("maxApprovalAgeHours").asLong(24);
        long idadeMs = System.currentTimeMillis() - b.path("approvedAt").asLong(0);
        if (idadeMs > horas * 3_600_000L) {
            recusar(id, net, "aprovacao_vencida");
            throw new IllegalStateException("aprovacao do lote " + id + " venceu (" + horas + "h); rejeite e reaprove");
        }
        if (precisaEnsaio(b) && !b.path("ensaioAutorizado").asBoolean(false)) {
            recusar(id, net, "ensaio_ausente");
            throw new IllegalStateException("lote " + id + " usa receita nao provada ou acoes cruas com passo publico; "
                    + "precisa de ensaio autorizado pelo dono no terminal: bin/social-mcp --cli approve " + id
                    + " humano \"<nota>\" --ensaio");
        }
        return b;
    }

    private void recusar(String id, String net, String motivo) {
        store.appendJournal("batch_execute_refused", net, Json.obj().put("batchId", id).put("motivo", motivo));
    }

    /** Aprovacao com gate de texto obrigatorio e sem fichas de fatos. */
    public String approve(String id, String aprovador, String note) throws IOException {
        return approve(id, aprovador, note, List.of(), false, false);
    }

    public String approve(String id, String aprovador, String note, List<Path> fichas, boolean forcar) throws IOException {
        return approve(id, aprovador, note, fichas, forcar, false);
    }

    /**
     * Aprovação é SEMPRE fora do MCP, por quem está vendo: o dono (humano) ou
     * o orquestrador ativo. A operadora/agente que executa não tem como aprovar.
     * aprovador: "humano" | "orquestrador" — fica registrado no lote e no journal.
     * O gate de texto roda aqui (fail-closed); so o humano pode forcar sem gate.
     * ensaio=true grava ensaioAutorizado/ensaioPor no lote e o evento ensaio_autorizado
     * no journal: e o que destrava execucao de lote cru ou de receita nao provada.
     * O lote aprovado leva o hash das acoes: qualquer edicao posterior invalida a aprovacao.
     * Retorna o resultado do gate (PASS|WARN|FORCADO).
     */
    public String approve(String id, String aprovador, String note, List<Path> fichas, boolean forcar, boolean ensaio) throws IOException {
        if (!id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("batchId invalido: '" + id + "'");
        // transicao do lote serializada entre processos: sem a trava, reject() pode
        // rejeitar e apagar o rascunho depois da ultima leitura e o approve publica mesmo assim
        try (Store.Trava t = store.tryLock(loteLock(id))) {
            if (t == null) {
                throw new IllegalStateException("lote " + id + " em transicao (aprovacao/rejeicao concorrente); tente de novo");
            }
            return approveLocked(id, aprovador, note, fichas, forcar, ensaio);
        }
    }

    private String approveLocked(String id, String aprovador, String note, List<Path> fichas, boolean forcar, boolean ensaio) throws IOException {
        Path executed = store.batchesDir().resolve(id + ".executed.json");
        Path approvedAnterior = store.batchesDir().resolve(id + ".approved.json");
        if (Files.exists(executed) || Files.exists(approvedAnterior)) {
            throw new IllegalStateException("lote " + id + " ja aprovado ou executado; lote nao reaprova. Crie um novo rascunho para um novo lote.");
        }
        Path draft = store.batchesDir().resolve(id + ".draft.json");
        if (!Files.exists(draft)) {
            throw new IllegalStateException("rascunho " + id + " nao encontrado. Veja: bin/social-mcp --cli batches");
        }
        String por = aprovador != null && aprovador.equals("orquestrador") ? "orquestrador" : "humano";
        if (forcar && !"humano".equals(aprovador)) {
            throw new IllegalArgumentException("--forcar so vale para aprovacao humana");
        }
        List<Path> fs = fichas == null ? List.of() : fichas;
        boolean temNota = note != null && !note.isBlank();
        // le o lote ANTES do gate: o hash gravado e o do conteudo que o gate examinou
        String conteudo = Files.readString(draft, StandardCharsets.UTF_8);
        ObjectNode b = (ObjectNode) Json.parse(conteudo);
        String resultado;
        int exit;
        if (forcar) {
            resultado = "FORCADO";
            exit = 0;
        } else {
            TextGate.Resultado r = TextGate.rodarPre(store, draft, fs);
            resultado = r.resultado();
            exit = r.exit();
            if ("FAIL".equals(resultado) || "ERRO".equals(resultado)) {
                throw new IllegalStateException("gate de texto reprovou o lote " + id + " (" + resultado + "):\n" + r.saida());
            }
            if ("WARN".equals(resultado) && !temNota) {
                throw new IllegalStateException("gate com avisos: informe a nota justificando");
            }
            if (!conteudo.equals(Files.readString(draft, StandardCharsets.UTF_8))) {
                throw new IllegalStateException("rascunho " + id + " mudou durante o gate; aprove de novo");
            }
        }
        b.put("status", "approved");
        b.put("approvedAt", System.currentTimeMillis());
        b.put("aprovadoPor", por);
        if (ensaio) {
            b.put("ensaioAutorizado", true);
            b.put("ensaioPor", aprovador);
        }
        if (temNota) b.put("nota", note);
        b.put("actionsHash", TextGate.hashActions(b.path("actions")));
        b.set("midias", vincularMidias(id, b.path("actions")));
        ObjectNode gate = b.putObject("gate");
        gate.put("resultado", resultado);
        gate.put("exit", exit);
        gate.put("forcado", forcar);
        var arr = gate.putArray("fichas");
        for (Path f : fs) arr.add(f.toString());
        gate.put("em", System.currentTimeMillis());
        Path temp = writeTemp(b, id + ".approved.tmp");
        antesDeConfirmarAprovacao.run();
        Path approving = store.batchesDir().resolve(id + ".approving.json");
        try {
            try {
                Files.move(draft, approving, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(draft, approving);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            Files.deleteIfExists(temp);
            apagarCopias(b);
            throw new IllegalStateException("rascunho " + id + " foi rejeitado ou removido durante a aprovacao; nada foi aprovado");
        }
        if (!conteudo.equals(Files.readString(approving, StandardCharsets.UTF_8))) {
            try {
                Files.move(approving, draft, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(approving, draft);
            }
            Files.deleteIfExists(temp);
            apagarCopias(b);
            throw new IllegalStateException("rascunho " + id + " mudou durante a aprovacao; aprove de novo");
        }
        atomicMove(temp, store.batchesDir().resolve(id + ".approved.json"));
        Files.deleteIfExists(approving);
        store.appendJournal("batch_approved", null, Json.obj().put("batchId", id).put("aprovadoPor", por)
                .put("nota", note == null ? "" : note).put("gate", resultado).put("forcado", forcar));
        if (ensaio) {
            store.appendJournal("ensaio_autorizado", null, Json.obj().put("batchId", id)
                    .put("aprovador", aprovador == null ? "" : aprovador));
        }
        return resultado;
    }

    /** Rejeita rascunho ou revoga lote ja aprovado (ainda nao executado). */
    public void reject(String id, String note) throws IOException {
        if (!id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("batchId invalido: '" + id + "'");
        // mesma trava do approve: as transicoes do lote nao se intercalam entre processos
        try (Store.Trava t = store.tryLock(loteLock(id))) {
            if (t == null) {
                throw new IllegalStateException("lote " + id + " em transicao (aprovacao/rejeicao concorrente); tente de novo");
            }
            rejectLocked(id, note);
        }
    }

    private void rejectLocked(String id, String note) throws IOException {
        Path draft = store.batchesDir().resolve(id + ".draft.json");
        Path approved = store.batchesDir().resolve(id + ".approved.json");
        Path rejected = store.batchesDir().resolve(id + ".rejected.json");
        if (Files.exists(running(id))) {
            throw new IllegalStateException("lote " + id + " esta em execucao; para parar use o kill-switch (stop on)");
        }
        boolean revogar = false;
        try {
            try {
                Files.move(draft, rejected, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(draft, rejected);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            if (Files.exists(approved)) {
                // Aprovacao ou revogacao compete pelo mesmo rename do estado aprovado.
                try {
                    Files.move(approved, rejected, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(approved, rejected);
                } catch (java.nio.file.NoSuchFileException gone) {
                    if (Files.exists(running(id))) {
                        throw new IllegalStateException("lote " + id + " esta em execucao; para parar use o kill-switch (stop on)");
                    }
                    throw new IllegalStateException("lote " + id + " deixou de estar aprovado (executado, revogado ou reservado por outro processo)");
                }
                revogar = true;
            } else if (Files.exists(store.batchesDir().resolve(id + ".approving.json"))) {
                throw new IllegalStateException("lote " + id + " esta sendo aprovado neste instante; tente de novo");
            } else {
                throw new IllegalStateException("rascunho " + id + " nao encontrado.");
            }
        }
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(rejected, StandardCharsets.UTF_8));
        b.put("status", "rejected");
        if (revogar) b.put("revogadoEm", System.currentTimeMillis());
        if (note != null && !note.isBlank()) b.put("nota", note);
        atomicMove(writeTemp(b, id + ".rejected.tmp"), rejected);
        apagarCopias(b);
        if (revogar) {
            store.appendJournal("batch_revoked", b.path("network").asText(null), Json.obj().put("batchId", id)
                    .put("nota", note == null ? "" : note));
        } else {
            String net = b.path("network").asText(null);
            if (net == null || net.isBlank()) net = b.path("actions").path(0).path("network").asText(null);
            store.appendJournal("batch_rejected", net, Json.obj().put("batchId", id)
                    .put("nota", note == null ? "" : note));
        }
    }

    private Path running(String id) {
        return store.batchesDir().resolve(id + ".running.json");
    }

    /** Trava de transicao do lote (approve/reject); vale entre processos e threads. */
    static String loteLock(String id) {
        return "lote-" + id;
    }

    /**
     * Reserva o lote para UM processo: move approved -> running (rename atomico). Varios
     * clientes sobem o MCP por stdio; so quem move executa, o outro recebe erro antes de
     * qualquer passo. Confere de novo o hash no arquivo reservado. Retorna o lote reservado.
     */
    public JsonNode claim(String id, JsonNode validado) throws IOException {
        try (Store.Trava ignored = store.tryLock(loteLock(id))) {
            if (ignored == null) throw new IllegalStateException("lote " + id + " em transicao; tente novamente");
            Path approved = store.batchesDir().resolve(id + ".approved.json");
            Path run = running(id);
            if (Files.exists(run)) {
                throw new IllegalStateException("lote " + id + " ja esta em execucao em outro processo");
            }
            try {
                Files.move(approved, run, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.NoSuchFileException e) {
                throw new IllegalStateException("lote " + id + " deixou de estar aprovado (executado, revogado ou reservado por outro processo)");
            }
            JsonNode b = Json.parse(Files.readString(run, StandardCharsets.UTF_8));
            String hash = b.path("actionsHash").asText("");
            if (hash.isBlank() || !hash.equals(validado.path("actionsHash").asText(""))
                    || !hash.equals(TextGate.hashActions(b.path("actions")))) {
                recusar(id, b.path("network").asText(null), "hash_divergente");
                atomicMove(run, store.batchesDir().resolve(id + ".approved.json"));
                throw new IllegalStateException("lote " + id + " mudou entre a conferencia e a reserva; rejeite e reaprove");
            }
            store.appendJournal("batch_claimed", b.path("network").asText(null), Json.obj().put("batchId", id)
                    .put("pid", ProcessHandle.current().pid()));
            return b;
        }
    }

    /** Copias de midia aprovadas (batches/) do lote; so caminhos dentro de batches/. */
    private void apagarCopias(JsonNode b) {
        for (JsonNode m : b.path("midias")) {
            try {
                Files.deleteIfExists(copiaAprovada(store, m));
            } catch (IOException | RuntimeException ignored) {
                // melhor esforco: copia orfa nao muda o estado do lote
            }
        }
    }

    public void markExecuted(String id, JsonNode summary) throws IOException {
        Path approved = Files.exists(running(id)) ? running(id) : store.batchesDir().resolve(id + ".approved.json");
        ObjectNode b = (ObjectNode) Json.parse(Files.readString(approved, StandardCharsets.UTF_8));
        b.put("status", "executed");
        b.put("executedAt", System.currentTimeMillis());
        b.set("result", summary);
        Path dest = store.batchesDir().resolve(id + ".executed.json");
        atomicMove(writeTemp(b, id + ".executed.tmp"), dest);
        // lote consumido: sem arquivo approved/running o mesmo lote nao pode reexecutar
        Files.deleteIfExists(approved);
        apagarCopias(b);
        String net = b.path("network").asText(null);
        if (net == null) net = b.path("actions").path(0).path("network").asText(null);
        store.appendJournal("batch_executed", net, Json.obj().put("batchId", id).put("day", store.today())
                .put("abortado", summary.path("abortado").asBoolean(false))
                .put("conferencia", summary.path("conferencia").asText("")));
        promoverProva(id, b, summary, net);
    }

    /**
     * Lote nascido de receita com conferencia forte prova a receita: grava network -> receita -> id
     * em provas.json (le-altera-escreve; provas.json.tmp + move atomico). Falha de I/O nao derruba
     * o markExecuted: vira evento receita_provada_falhou no journal e a execucao segue.
     */
    private void promoverProva(String id, JsonNode b, JsonNode summary, String net) {
        String receita = b.path("origem").path("receita").asText("");
        if (receita.isBlank() || !CONF_FORTE.equals(summary.path("conferencia").asText(""))) return;
        try {
            if (net == null || net.isBlank()) return;
            Path provas = store.batchesDir().getParent().resolve("provas.json");
            ObjectNode raiz = Json.obj();
            if (Files.isRegularFile(provas)) {
                JsonNode lido = Json.parse(Files.readString(provas, StandardCharsets.UTF_8));
                if (lido.isObject()) raiz = (ObjectNode) lido;
            }
            JsonNode redeNode = raiz.get(net);
            ObjectNode rede = redeNode != null && redeNode.isObject() ? (ObjectNode) redeNode : raiz.putObject(net);
            rede.put(receita, id);
            Path tmp = provas.resolveSibling("provas.json.tmp");
            Files.writeString(tmp, Json.writePretty(raiz), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, provas, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, provas, StandardCopyOption.REPLACE_EXISTING);
            }
            store.appendJournal("receita_provada", net, Json.obj().put("receita", receita).put("batchId", id));
        } catch (IOException | RuntimeException e) {
            store.appendJournal("receita_provada_falhou", net, Json.obj().put("receita", receita)
                    .put("batchId", id).put("erro", String.valueOf(e.getMessage())));
        }
    }

    private Path writeTemp(ObjectNode b, String tmpName) throws IOException {
        Path tmp = store.batchesDir().resolve(tmpName);
        Files.writeString(tmp, Json.writePretty(b), StandardCharsets.UTF_8);
        return tmp;
    }

    private void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> list() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(store.batchesDir())) return out;
        try (var s = Files.list(store.batchesDir())) {
            List<Path> files = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".draft.json") || n.endsWith(".approved.json") || n.endsWith(".running.json")
                        || n.endsWith(".executed.json") || n.endsWith(".rejected.json");
            }).sorted().toList();
            for (Path p : files) {
                try {
                    JsonNode b = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                    String linha = b.path("id").asText(p.getFileName().toString()) + "  "
                            + b.path("status").asText("?") + "  "
                            + b.path("label").asText("?") + "  ("
                            + b.path("actions").size() + " acoes)";
                    if ("executed".equals(b.path("status").asText(""))
                            && b.path("result").path("abortado").asBoolean(false)) {
                        String conf = b.path("result").path("conferencia").asText("");
                        if (conf.isBlank()) conf = "sem_conferencia";
                        linha += "  [abortado: " + conf + "]";
                    }
                    out.add(linha);
                } catch (Exception e) {
                    out.add(p.getFileName().toString() + "  (ilegivel: " + e.getMessage() + ")");
                }
            }
        }
        return out;
    }
}
