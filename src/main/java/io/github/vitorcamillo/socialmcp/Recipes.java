package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receitas de acoes de alto nivel: SOCIAL_MCP_HOME/recipes/&lt;rede&gt;.json (dado do dono,
 * o MCP so le). Expande {{param}} com escape por contexto:
 * url -> URL-encode; body JSON -> JSON-escape; body de formulario -> URL-encode
 * (JSON-escape + URL-encode quando o placeholder esta entre aspas, i.e. dentro de JSON);
 * selector xpath: -> placeholder entre aspas simples vira literal XPath seguro ('..', "..", ou
 * concat(...) quando o valor tem os dois tipos de aspa), para um apostrofo no valor nao quebrar a
 * expressao; demais campos (expect, text, description...) -> literal.
 * Placeholders gerados pelo MCP (nomes reservados, nao sao params): {{now}} = ISO-8601 UTC
 * com milissegundos, {{uuid}} = UUID v4 (mesmo valor em todo o passo), {{today}} = AAAA-MM-DD
 * (fuso do Store). Gerados uma vez por passo, na expansao (o lote aprovado leva o valor fixo).
 */
public class Recipes {
    /** Acoes base; readOnly vem SO da receita (o nome read_/get_/list_ nao forca nada). */
    static final String ACOES_BASE = "list_posts|list_videos|list_reels|list_community|get_post|get_profile|read_inbox|read_notifications|read_mentions|read_comments|read_requests|create_post|create_photo_post|community_post|edit_post|edit_title|delete_post|delete_community_post|reply|follow|like|unfollow|unlike";
    /**
     * Acoes aceitas; ate 3 sufixos _xxx permitem variantes (ex.: delete_post_ui, get_post_refs,
     * reply_comment_ui, get_post_comments_api). Com 1 sufixo so, reply_comment_ui das receitas
     * de 22/10 era recusado na chamada.
     */
    static final Pattern ACTION = Pattern.compile(
            "^(" + ACOES_BASE + ")(_[a-z0-9]+){0,3}$");
    private static final Pattern PH = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    /** Passos permitidos numa acao readOnly (request so com GET). */
    static final Set<String> READ_KINDS = Set.of("goto", "verify_text", "verify_attr", "verify_absent", "extract", "request", "screenshot", "wait", "capture_ids", "verify_new_id");
    /** Nomes gerados pelo MCP; receita nao pode declara-los como params. */
    static final Set<String> GERADOS = Set.of("now", "uuid", "today");
    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Store store;

    public Recipes(Store store) {
        this.store = store;
    }

    public Path dir() {
        return store.home.resolve("recipes");
    }

    /** Acao expandida: passos prontos (com network), readOnly e rotulo do lote. */
    public record Plan(String network, String action, boolean readOnly, ArrayNode steps, String label) {}

    /** Carrega a receita da rede; erro claro se ausente ou ilegivel. */
    public JsonNode load(String network) {
        if (!store.knownNetworks().contains(network)) {
            throw new IllegalArgumentException("rede desconhecida: '" + network + "'. Redes: " + store.knownNetworks());
        }
        Path f = dir().resolve(network + ".json");
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("nenhuma receita para a rede '" + network + "' (" + f
                    + "); mapeie com scripts/redes/mapear-rede.sh " + network + " <acao>");
        }
        try {
            JsonNode r = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            String declarada = r.path("network").asText(network);
            if (!declarada.equals(network)) {
                throw new IllegalArgumentException("receita " + f + " declara network '" + declarada + "' (esperado '" + network + "')");
            }
            return r;
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler receita " + f + ": " + e.getMessage(), e);
        }
    }

    /** Expande a acao com os params informados. Nao executa nem grava nada. */
    public Plan prepare(String network, String action, JsonNode params) {
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("acao invalida '" + action
                    + "' (use " + ACOES_BASE + ", com sufixo _xxx opcional)");
        }
        JsonNode recipe = load(network);
        JsonNode def = recipe.path("actions").path(action);
        if (!def.isObject()) {
            throw new IllegalArgumentException("acao '" + action + "' nao mapeada para a rede '" + network
                    + "'; use scripts/redes/mapear-rede.sh " + network + " " + action);
        }
        if (def.hasNonNull("naoSuportado")) {
            throw new IllegalArgumentException("acao '" + action + "' nao e suportada na rede '" + network
                    + "': " + def.get("naoSuportado").asText());
        }
        JsonNode p = params == null || params.isNull() || params.isMissingNode() ? Json.obj() : params;
        if (!p.isObject()) throw new IllegalArgumentException("params deve ser objeto");
        Set<String> declarados = new LinkedHashSet<>();
        for (JsonNode n : def.path("params")) declarados.add(n.asText());
        for (String g : GERADOS) {
            if (declarados.contains(g)) {
                throw new IllegalArgumentException("receita invalida: param '" + g + "' e reservado (gerado pelo MCP: " + GERADOS + ")");
            }
        }
        for (Iterator<String> it = p.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!declarados.contains(k)) {
                throw new IllegalArgumentException("param '" + k + "' nao existe na acao '" + action + "' (params: " + declarados + ")");
            }
            if (!p.get(k).isValueNode() || p.get(k).isNull()) {
                throw new IllegalArgumentException("param '" + k + "' deve ser texto ou numero");
            }
        }
        for (String k : declarados) {
            if (!p.has(k)) throw new IllegalArgumentException("param faltando: '" + k + "' (acao '" + action + "' exige " + declarados + ")");
        }
        JsonNode patterns = def.get("paramPatterns");
        if (patterns != null && !patterns.isNull()) {
            if (!patterns.isObject()) {
                throw new IllegalArgumentException("receita invalida: paramPatterns deve ser objeto em '" + action + "'");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = patterns.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String nome = e.getKey();
                if (!declarados.contains(nome)) {
                    throw new IllegalArgumentException("receita invalida: paramPatterns com param '" + nome
                            + "' nao declarado em params (" + action + ")");
                }
                JsonNode spec = e.getValue();
                if (spec == null || !spec.isObject()) {
                    throw new IllegalArgumentException("receita invalida: paramPatterns." + nome + " deve ser objeto");
                }
                String regex = spec.path("regex").asText("");
                if (regex.isEmpty()) {
                    throw new IllegalArgumentException("receita invalida: paramPatterns." + nome + " sem regex");
                }
                if (regex.length() > 200) {
                    throw new IllegalArgumentException("receita invalida: paramPatterns." + nome + " regex acima de 200 caracteres");
                }
                Pattern pat;
                try {
                    pat = Pattern.compile(regex);
                } catch (java.util.regex.PatternSyntaxException ex) {
                    throw new IllegalArgumentException("receita invalida: paramPatterns." + nome + " regex invalida: " + ex.getMessage());
                }
                String valor = p.get(nome).asText();
                if (!pat.matcher(valor).matches()) {
                    String erro = spec.path("erro").asText("");
                    if (erro.isEmpty()) erro = "nao casa " + regex;
                    String v60 = valor.length() > 60 ? valor.substring(0, 60) + "..." : valor;
                    throw new IllegalArgumentException("param '" + nome + "' invalido: " + erro + " (valor: '" + v60 + "')");
                }
            }
        }
        String textField = def.path("textField").asText("");
        if (!textField.isBlank()) {
            if (!declarados.contains(textField)) {
                throw new IllegalArgumentException("receita invalida: textField '" + textField + "' nao esta em params");
            }
            if (p.get(textField).asText().isBlank()) throw new IllegalArgumentException("texto vazio em '" + textField + "'");
        }
        JsonNode steps = def.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            throw new IllegalArgumentException("receita invalida: acao '" + action + "' sem steps");
        }
        boolean readOnly = def.path("readOnly").asBoolean(false);
        ArrayNode out = Json.M.createArrayNode();
        for (JsonNode s : steps) {
            if (!s.isObject()) throw new IllegalArgumentException("receita invalida: passo nao e objeto em '" + action + "'");
            out.add(expandStep(network, (ObjectNode) s, p, textField));
        }
        if (readOnly) {
            validateReadOnly(out);
            BatchStore.validateWaitBudget(out); // readOnly roda na hora, numa chamada MCP so
            BatchStore.validateIdPairs(out);
        }
        return new Plan(network, action, readOnly, out, label(network, action, p));
    }

    private void validateReadOnly(ArrayNode steps) {
        int i = 0;
        for (JsonNode s : steps) {
            String kind = s.path("kind").asText("");
            if (!READ_KINDS.contains(kind)) {
                throw new IllegalArgumentException("receita readOnly com passo '" + kind + "' (permitido: " + READ_KINDS + ")");
            }
            BatchStore.validateSelector(s, i); // mesmo criterio do lote: >>> vazio, xpath: sem expressao
            switch (kind) {
                case "goto" -> {
                    if (s.path("url").asText("").isBlank()) throw new IllegalArgumentException("passo " + i + ": goto exige url");
                }
                case "verify_text" -> {
                    if (s.path("expect").asText("").isBlank()) throw new IllegalArgumentException("passo " + i + ": verify_text exige expect");
                }
                case "extract" -> BatchStore.validateExtract(s, i);
                case "verify_attr" -> BatchStore.validateVerifyAttr(s, i);
                case "verify_absent" -> BatchStore.validateVerifyAbsent(s, i);
                case "capture_ids" -> BatchStore.validateCaptureIds(s, i);
                case "verify_new_id" -> BatchStore.validateVerifyNewId(s, i);
                case "wait" -> BatchStore.validateWait(s, i);
                case "request" -> {
                    if (!"GET".equalsIgnoreCase(s.path("method").asText(""))) {
                        throw new IllegalArgumentException("passo " + i + ": request em acao readOnly so com GET");
                    }
                    BatchStore.validateRequest(store, s, i, true);
                }
                default -> {
                }
            }
            i++;
        }
    }

    ObjectNode expandStep(String network, ObjectNode step, JsonNode params, String textField) {
        return expandir(network, step, comGerados(params, Instant.now(), store.today()), textField);
    }

    /** Params + {{now}}/{{uuid}}/{{today}} gerados uma vez (mesmo valor em todo o passo). */
    static ObjectNode comGerados(JsonNode params, Instant agora, String hoje) {
        ObjectNode p = params.isObject() ? ((ObjectNode) params).deepCopy() : Json.obj();
        p.put("now", ISO_MS.format(agora));
        p.put("uuid", UUID.randomUUID().toString());
        p.put("today", hoje);
        return p;
    }

    private static ObjectNode expandir(String network, ObjectNode step, ObjectNode params, String textField) {
        ObjectNode o = Json.obj();
        String kind = step.path("kind").asText("");
        String ct = step.path("headers").isObject() ? contentType(step.path("headers")) : "";
        boolean jsonBody = ct.contains("json");
        for (Iterator<Map.Entry<String, JsonNode>> it = step.fields(); it.hasNext(); ) {
            var e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (k.equals("network")) continue;
            if (k.equals("paths")) {
                ArrayNode paths = o.putArray("paths");
                if (v.isTextual()) {
                    String expanded = expand(v.asText(), params, Ctx.LITERAL);
                    for (String part : expanded.split("\\n", -1)) {
                        String path = part.trim();
                        if (!path.isEmpty()) paths.add(path);
                    }
                } else if (v.isArray()) {
                    for (JsonNode item : v) {
                        if (!item.isTextual()) continue;
                        String path = expand(item.asText(), params, Ctx.LITERAL).trim();
                        if (!path.isEmpty()) paths.add(path);
                    }
                }
            } else if (v.isTextual()) {
                Ctx c = switch (k) {
                    case "url" -> Ctx.URL;
                    case "body" -> jsonBody ? Ctx.JSON : Ctx.FORM;
                    case "selector", "parentSelector" -> v.asText().startsWith("xpath:") ? Ctx.XPATH : Ctx.LITERAL;
                    default -> Ctx.LITERAL;
                };
                o.put(k, expand(v.asText(), params, c));
            } else if (k.equals("headers") && v.isObject()) {
                ObjectNode h = o.putObject("headers");
                v.fields().forEachRemaining(he -> h.put(he.getKey(), expand(he.getValue().asText(), params, Ctx.LITERAL)));
            } else {
                o.set(k, v.deepCopy());
            }
        }
        o.put("network", network);
        // o gate de texto valida o texto humano; o body leva so a versao codificada
        if ("request".equals(kind) && !textField.isBlank() && !o.has("text")
                && (step.path("body").asText("").contains("{{" + textField + "}}")
                || step.path("url").asText("").contains("{{" + textField + "}}"))) {
            o.put("text", params.get(textField).asText());
        }
        return o;
    }

    enum Ctx { URL, FORM, JSON, LITERAL, XPATH }

    static boolean dentroDeStringJson(String template, int ini) {
        int campoIni = template.lastIndexOf('&', ini - 1) + 1;
        int igual = template.indexOf('=', campoIni);
        int valorIni = igual >= 0 && igual < ini ? igual + 1 : campoIni;
        String valor = template.substring(valorIni, ini).trim();
        String lower = valor.toLowerCase(Locale.ROOT);
        if (!(valor.startsWith("{") || valor.startsWith("[") || lower.startsWith("%7b") || lower.startsWith("%5b"))) {
            return false;
        }

        int aspas = 0;
        for (int i = valorIni; i < ini; i++) {
            char c = template.charAt(i);
            if (c == '"' && (i == 0 || template.charAt(i - 1) != '\\')) {
                aspas++;
            } else if (c == '%' && i + 2 < ini
                    && template.regionMatches(true, i, "%22", 0, 3)) {
                boolean escapada = i > 0 && template.charAt(i - 1) == '\\'
                        || i >= 3 && template.regionMatches(true, i - 3, "%5C", 0, 3);
                if (!escapada) aspas++;
                i += 2;
            }
        }
        return (aspas & 1) == 1;
    }

    static String expand(String template, JsonNode params, Ctx ctx) {
        Matcher m = PH.matcher(template);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            String name = m.group(1);
            JsonNode v = params.get(name);
            if (v == null) throw new IllegalArgumentException("placeholder {{" + name + "}} sem param correspondente");
            String raw = v.asText();
            int ini = m.start();
            int fim = m.end();
            boolean entreAspas = ini > 0 && template.charAt(ini - 1) == '"'
                    && fim < template.length() && template.charAt(fim) == '"';
            boolean entreApostrofos = ini > 0 && template.charAt(ini - 1) == '\''
                    && fim < template.length() && template.charAt(fim) == '\'';
            String val;
            if (ctx == Ctx.XPATH && entreApostrofos && raw.indexOf('\'') >= 0) {
                // 'valor' da receita vira um literal XPath que aceita o apostrofo
                ini--;
                fim++;
                val = xpathLiteral(raw);
            } else {
                val = switch (ctx) {
                    case URL -> urlEncode(raw);
                    case JSON -> jsonEscape(raw);
                    case FORM -> entreAspas || dentroDeStringJson(template, ini)
                            ? urlEncode(jsonEscape(raw)) : urlEncode(raw);
                    case LITERAL, XPATH -> raw;
                };
            }
            sb.append(template, pos, ini).append(val);
            pos = fim;
        }
        sb.append(template.substring(pos));
        return sb.toString();
    }

    /** Literal XPath 1.0 para qualquer texto: '..', ".." ou concat() quando tem os dois tipos de aspa. */
    static String xpathLiteral(String s) {
        if (s.indexOf('\'') < 0) return "'" + s + "'";
        if (s.indexOf('"') < 0) return "\"" + s + "\"";
        StringBuilder sb = new StringBuilder("concat(");
        String[] partes = s.split("'", -1);
        for (int i = 0; i < partes.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append('\'').append(partes[i]).append('\'');
        }
        return sb.append(')').toString();
    }

    static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Conteudo de string JSON, sem as aspas externas. */
    static String jsonEscape(String s) {
        String q = Json.write(TextNode.valueOf(s));
        return q.substring(1, q.length() - 1);
    }

    static String contentType(JsonNode headers) {
        for (Iterator<Map.Entry<String, JsonNode>> it = headers.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase("content-type")) return e.getValue().asText("").toLowerCase(Locale.ROOT);
        }
        return "";
    }

    static String label(String network, String action, JsonNode params) {
        StringBuilder sb = new StringBuilder(network + " " + action);
        params.fields().forEachRemaining(e -> {
            String v = e.getValue().asText().replaceAll("\\s+", " ").trim();
            if (v.length() > 40) v = v.substring(0, 40) + "...";
            sb.append(' ').append(e.getKey()).append('=').append(v);
        });
        return sb.toString();
    }
}
