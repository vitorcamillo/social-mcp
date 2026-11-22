package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Valida as receitas SEM gastar cota: expande cada acao com parametros de exemplo e roda a mesma
 * validacao do BatchStore (sem gravar lote nem journal). Por acao: aceita/recusada, tipo
 * (leitura, escrita, nao suportada) e, na escrita, a conferencia do resultado
 * (forte|fraca|ausente). Tambem lista as acoes base que a rede nem mapeou nem declarou
 * {@code naoSuportado}. CLI: {@code bin/social-mcp --cli validar-receitas [rede]}.
 */
public final class RecipeValidator {
    /** Acoes base esperadas em toda rede (o YouTube soma community_post). */
    static final List<String> BASE = List.of("list_posts", "get_post", "get_profile", "read_inbox", "read_notifications",
            "read_mentions", "read_comments", "create_post", "edit_post", "delete_post", "reply", "follow", "like",
            "unfollow", "unlike");
    static final String TIPO_LEITURA = "leitura";
    static final String TIPO_ESCRITA = "escrita";
    static final String TIPO_NAO_SUPORTADA = "nao_suportada";
    static final String PROVA_ID_NOVO = "id_novo";
    static final String PROVA_REQUEST = "request";
    static final String PROVA_ESTADO = "estado";
    static final String PROVA_NENHUMA = "-";
    private static final String TEXTO_EXEMPLO = "Teste de validacao da receita (nao publicado)";

    private RecipeValidator() {}

    /** Resultado de uma acao. conferencia: forte|fraca|ausente na escrita, "-" fora dela; prova: id_novo|request|estado|-. */
    public record Item(String network, String action, String tipo, boolean aceita, String motivo, String conferencia,
                       String prova, String status) {
        /** Escrita aceita cuja conferencia nao prova o resultado. */
        public boolean escritaFraca() {
            return aceita && TIPO_ESCRITA.equals(tipo) && !BatchStore.CONF_FORTE.equals(conferencia);
        }

        /** Criacao/resposta aceita cuja prova e so por estado (trecho): precisa de verify_new_id. */
        public boolean criacaoSemIdNovo() {
            if (!aceita || !TIPO_ESCRITA.equals(tipo)) return false;
            boolean criacao = action.equals("community_post") || action.startsWith("create_") || action.startsWith("reply");
            return criacao && PROVA_ESTADO.equals(prova);
        }
    }

    /** Relatorio: itens por acao, acoes base faltando por rede e redes sem receita. */
    public record Relatorio(List<Item> itens, Map<String, List<String>> faltam, List<String> semReceita) {
        /** Meta: tudo aceito, nenhuma escrita com conferencia fraca/ausente, nenhuma criacao sem id novo e base completa. */
        public boolean ok() {
            return faltam.isEmpty() && itens.stream().allMatch(i -> i.aceita() && !i.escritaFraca() && !i.criacaoSemIdNovo());
        }
    }

    /** Valida todas as redes com receita (rede == null) ou so a informada. */
    public static Relatorio validar(Store store, String rede) {
        Recipes recipes = new Recipes(store);
        BatchStore batches = new BatchStore(store);
        boolean explicita = rede != null && !rede.isBlank();
        List<String> redes = new ArrayList<>();
        if (!explicita) {
            redes.addAll(store.knownNetworks());
        } else {
            if (!store.knownNetworks().contains(rede)) {
                throw new IllegalArgumentException("rede desconhecida: '" + rede + "'. Redes: " + store.knownNetworks());
            }
            redes.add(rede);
        }
        List<Item> itens = new ArrayList<>();
        Map<String, List<String>> faltam = new LinkedHashMap<>();
        List<String> semReceita = new ArrayList<>();
        for (String n : redes) {
            if (!Files.isRegularFile(recipes.dir().resolve(n + ".json"))) {
                semReceita.add(n);
                if (explicita) {
                    // Pedir uma rede e nao validar nada nao pode sair como "100% aceitas".
                    itens.add(new Item(n, "(receita)", TIPO_ESCRITA, false,
                            "sem receita para a rede solicitada (recipes/" + n + ".json ausente)", "-", PROVA_NENHUMA, ""));
                }
                continue;
            }
            JsonNode recipe;
            try {
                recipe = recipes.load(n);
            } catch (RuntimeException e) {
                itens.add(new Item(n, "(receita)", TIPO_ESCRITA, false, e.getMessage(), "-", PROVA_NENHUMA, ""));
                continue;
            }
            JsonNode actions = recipe.path("actions");
            for (Iterator<Map.Entry<String, JsonNode>> it = actions.fields(); it.hasNext(); ) {
                var e = it.next();
                itens.add(validarAcao(store, recipes, batches, recipe, n, e.getKey(), e.getValue()));
            }
            List<String> f = new ArrayList<>();
            List<String> base = new ArrayList<>(BASE);
            if ("youtube".equals(n)) base.add("community_post");
            for (String b : base) {
                boolean tem = false;
                for (Iterator<String> it = actions.fieldNames(); it.hasNext(); ) {
                    String a = it.next();
                    if (a.equals(b) || a.startsWith(b + "_")) {
                        tem = true;
                        break;
                    }
                }
                if (!tem) f.add(b);
            }
            if (!f.isEmpty()) faltam.put(n, f);
        }
        return new Relatorio(itens, faltam, semReceita);
    }

    static Item validarAcao(Store store, Recipes recipes, BatchStore batches, JsonNode recipe, String network,
                            String action, JsonNode def) {
        String status = def.path("status").asText("");
        if (def.hasNonNull("naoSuportado")) {
            return new Item(network, action, TIPO_NAO_SUPORTADA, true, def.get("naoSuportado").asText(), "-", PROVA_NENHUMA, status);
        }
        boolean readOnly = def.path("readOnly").asBoolean(false);
        String tipo = readOnly ? TIPO_LEITURA : TIPO_ESCRITA;
        ObjectNode params;
        try {
            params = exemploParams(store, recipe, def);
        } catch (IllegalStateException e) {
            return new Item(network, action, tipo, false, e.getMessage(), "-", PROVA_NENHUMA, status);
        }
        try {
            Recipes.Plan plan = recipes.prepare(network, action, params);
            if (plan.readOnly()) {
                return new Item(network, action, TIPO_LEITURA, true, "", "-", PROVA_NENHUMA, status);
            }
            batches.validate(plan.steps());
            String conf = BatchStore.conferencia(plan.steps());
            return new Item(network, action, TIPO_ESCRITA, true, "", conf, prova(plan.steps()), status);
        } catch (RuntimeException e) {
            return new Item(network, action, tipo, false, String.valueOf(e.getMessage()), "-", PROVA_NENHUMA, status);
        }
    }

    /** Prova do resultado: id_novo (provaPorIdNovo), request (requestSeConfere no ultimo publico), estado (forte) ou -. */
    private static String prova(JsonNode steps) {
        if (BatchStore.provaPorIdNovo(steps)) return PROVA_ID_NOVO;
        int last = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (Guard.isPublic(steps.get(i).path("kind").asText(""))) last = i;
        }
        if (last >= 0 && "request".equals(steps.get(last).path("kind").asText(""))
                && BatchStore.requestSeConfere(steps.get(last))) {
            return PROVA_REQUEST;
        }
        if (BatchStore.CONF_FORTE.equals(BatchStore.conferencia(steps))) return PROVA_ESTADO;
        return PROVA_NENHUMA;
    }

    /**
     * Parametros de exemplo: {@code exemplo} da acao, depois {@code exemplos} da receita (por nome
     * de param), depois um valor plausivel pelo nome. Param usado como path de set_file recebe um
     * arquivo real de uploadRoots (sem arquivo, a acao fica recusada com motivo claro).
     */
    static ObjectNode exemploParams(Store store, JsonNode recipe, JsonNode def) {
        ObjectNode p = Json.obj();
        JsonNode exAcao = def.path("exemplo");
        JsonNode exRede = recipe.path("exemplos");
        for (JsonNode n : def.path("params")) {
            String k = n.asText();
            if (exAcao.hasNonNull(k)) {
                p.set(k, exAcao.get(k));
            } else if (exRede.hasNonNull(k)) {
                p.set(k, exRede.get(k));
            } else if (ehArquivoDeSetFile(def, k)) {
                Optional<Path> f = arquivoExemplo(store);
                if (f.isEmpty()) {
                    throw new IllegalStateException("sem arquivo de exemplo para '" + k + "' em uploadRoots "
                            + BatchStore.uploadRoots(store) + " (declare exemplo." + k + " na receita)");
                }
                p.put(k, f.get().toString());
            } else {
                p.put(k, valorPadrao(k));
            }
        }
        return p;
    }

    private static boolean ehArquivoDeSetFile(JsonNode def, String param) {
        for (JsonNode s : def.path("steps")) {
            if ("set_file".equals(s.path("kind").asText("")) && s.path("path").asText("").contains("{{" + param + "}}")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Primeiro arquivo que passa na MESMA validacao do set_file (raiz real, extensao, tamanho), png antes
     * dos demais, ate 3 niveis abaixo de uploadRoots. Candidato invalido (ex.: png acima de uploadMaxMb)
     * e pulado em vez de reprovar a receita.
     */
    static Optional<Path> arquivoExemplo(Store store) {
        Path qualquer = null;
        for (Path root : BatchStore.uploadRoots(store)) {
            try (Stream<Path> w = Files.walk(root, 3)) {
                for (Path f : (Iterable<Path>) w::iterator) {
                    if (!Files.isRegularFile(f)) continue;
                    try {
                        BatchStore.validateUploadFile(store, com.fasterxml.jackson.databind.node.TextNode.valueOf(f.toString()), 0);
                    } catch (IllegalArgumentException invalido) {
                        continue;
                    }
                    if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) return Optional.of(f);
                    if (qualquer == null) qualquer = f;
                }
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // raiz ilegivel nao conta
            }
        }
        return Optional.ofNullable(qualquer);
    }

    /** Valor plausivel pelo nome do param (so para validar formato; nada e executado). */
    static String valorPadrao(String k) {
        String l = k.toLowerCase(Locale.ROOT);
        if (l.contains("url") || l.equals("link") || l.contains("uri")) return "https://example.com/p/123";
        if (l.contains("id") || l.contains("cid") || l.contains("start") || l.contains("end")) return "123456789";
        if (l.contains("handle") || l.contains("user") || l.contains("profile") || l.contains("nick")) return "example_account";
        if (l.contains("subreddit")) return "brasil";
        if (l.contains("pasta") || l.contains("board")) return "Shows e eventos";
        return TEXTO_EXEMPLO;
    }

    /** Relatorio em texto: tabela por acao, faltas por rede e resumo. */
    public static String texto(Relatorio r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %-28s %-14s %-9s %-8s %-8s %s%n", "REDE", "ACAO", "TIPO", "RESULTADO", "CONF.", "PROVA", "STATUS / MOTIVO"));
        int aceitas = 0, recusadas = 0, forte = 0, fraca = 0, ausente = 0, ns = 0, leitura = 0, idNovo = 0;
        for (Item i : r.itens()) {
            String res = i.aceita() ? "aceita" : "RECUSADA";
            String obs = i.aceita() ? (TIPO_NAO_SUPORTADA.equals(i.tipo()) ? "nao suportado: " + i.motivo() : i.status()) : i.motivo();
            sb.append(String.format("%-10s %-28s %-14s %-9s %-8s %-8s %s%n", i.network(), i.action(), i.tipo(), res, i.conferencia(), i.prova(), obs));
            if (i.aceita()) aceitas++; else recusadas++;
            if (TIPO_NAO_SUPORTADA.equals(i.tipo())) ns++;
            else if (TIPO_LEITURA.equals(i.tipo())) leitura++;
            else if (i.aceita()) {
                if (PROVA_ID_NOVO.equals(i.prova())) idNovo++;
                switch (i.conferencia()) {
                    case BatchStore.CONF_FORTE -> forte++;
                    case BatchStore.CONF_FRACA -> fraca++;
                    default -> ausente++;
                }
            }
        }
        for (var e : r.faltam().entrySet()) {
            sb.append("FALTAM em ").append(e.getKey()).append(" (nem mapeada nem naoSuportado): ")
                    .append(String.join(", ", e.getValue())).append('\n');
        }
        for (String n : r.semReceita()) sb.append("sem receita: ").append(n).append('\n');
        sb.append(String.format("RESUMO: %d acoes; %d aceitas, %d recusadas; leitura %d; escrita forte %d, fraca %d, ausente %d; nao suportadas %d; id novo %d%n",
                r.itens().size(), aceitas, recusadas, leitura, forte, fraca, ausente, ns, idNovo));
        sb.append(r.ok() ? "OK: 100% aceitas, criacao/resposta provada por verify_new_id (ou request com expectBody), zero escrita sem conferencia forte e acoes base completas."
                : "FALHOU: ha acao recusada, escrita com conferencia fraca/ausente, criacao/resposta sem verify_new_id (prova por trecho/estado nao basta), acao base faltando ou rede solicitada sem receita.");
        return sb.toString();
    }
}
