package io.github.vitorcamillo.socialmcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI do aprovador unico. So roda em terminal, fora do alcance do agente executor.
 * Quem aprova: o dono (humano) ou o orquestrador ativo que esta vendo a operacao.
 * Sem orquestrador vendo, o lote espera aprovacao humana.
 */
public final class ApprovalCli {
    private ApprovalCli() {}

    public static void run(String[] args) {
        if (args.length == 0) {
            uso();
            return;
        }
        if (args[0].equals("validar-receitas")) {
            // so le: nao cria home/config/journal (o construtor do Store cria o que faltar)
            Path home = Store.defaultHome();
            if (!homePronta(home)) {
                System.out.println("ERRO: home do MCP incompleta (" + home + "): falta config.json ou recipes/; nada foi criado");
                System.exit(1);
            }
        }
        Store store = new Store();
        BatchStore batches = new BatchStore(store);
        String cmd = args[0];
        try {
            switch (cmd) {
                case "status" -> System.out.println(statusTexto(store));
                case "batches" -> {
                    List<String> all = batches.list();
                    System.out.println(all.isEmpty() ? "(nenhum lote)" : String.join("\n", all));
                }
                case "approve" -> {
                    if (args.length < 2) {
                        System.out.println("uso: approve <batchId> [humano|orquestrador] [nota] [--fatos <arquivo>]... [--forcar]");
                        System.exit(1);
                    }
                    String aprovador = "humano";
                    String nota = "";
                    boolean forcar = false;
                    List<Path> fichas = new ArrayList<>();
                    List<String> posicionais = new ArrayList<>();
                    for (int i = 2; i < args.length; i++) {
                        String a = args[i];
                        if (a.equals("--forcar")) {
                            forcar = true;
                        } else if (a.equals("--fatos")) {
                            if (i + 1 >= args.length) {
                                System.out.println("ERRO: --fatos exige um arquivo");
                                System.exit(1);
                            }
                            fichas.add(Path.of(args[++i]));
                        } else {
                            posicionais.add(a);
                        }
                    }
                    int idx = 0;
                    if (!posicionais.isEmpty() && (posicionais.get(0).equals("humano") || posicionais.get(0).equals("orquestrador"))) {
                        aprovador = posicionais.get(0);
                        idx = 1;
                    }
                    if (posicionais.size() > idx) nota = posicionais.get(idx);
                    String resultado = batches.approve(args[1], aprovador, nota, fichas, forcar);
                    String gate = forcar ? "gate FORCADO por humano" : "gate " + resultado;
                    System.out.println("Lote " + args[1] + " APROVADO por " + aprovador + " (" + gate + "). A operadora ja pode executar com social_execute_batch.");
                }
                case "reject" -> {
                    if (args.length < 2) {
                        System.out.println("uso: reject <batchId> [nota]");
                        System.exit(1);
                    }
                    batches.reject(args[1], args.length >= 3 ? args[2] : "");
                    System.out.println("Lote " + args[1] + " rejeitado/revogado.");
                }
                case "state" -> {
                    if (args.length < 3) {
                        System.out.println("uso: state <rede> <healthy|cooldown|paused|banned> [motivo]");
                        System.exit(1);
                    }
                    String estado = args[2];
                    if (!List.of("healthy", "cooldown", "paused", "banned").contains(estado)) {
                        System.out.println("estado invalido: " + estado);
                        System.exit(1);
                    }
                    String motivo = args.length >= 4 ? args[3] : "(sem motivo)";
                    store.setState(args[1], estado, motivo, "dono (CLI)");
                    System.out.println("Rede '" + args[1] + "' agora esta '" + estado + "'.");
                }
                case "stop" -> {
                    if (args.length < 2) {
                        System.out.println("uso: stop on|off");
                        System.exit(1);
                    }
                    boolean on = aplicarStop(store, args[1]);
                    System.out.println(on ? "Kill-switch ATIVO: nenhuma acao ate 'stop off'." : "Kill-switch desativado.");
                }
                case "import-cookies" -> {
                    if (args.length < 3) {
                        System.out.println("uso: import-cookies <rede> <arquivo.json>");
                        System.exit(1);
                    }
                    System.out.println(SessionImporter.run(store, args[1], args[2]));
                }
                case "validar-receitas" -> {
                    RecipeValidator.Relatorio r = RecipeValidator.validar(store, args.length >= 2 ? args[1] : null);
                    System.out.println(RecipeValidator.texto(r));
                    if (!r.ok()) System.exit(1);
                }
                case "journal" -> {
                    int n = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
                    List<String> lines = store.tail(n);
                    System.out.println(lines.isEmpty() ? "(journal vazio)" : String.join("\n", lines));
                }
                default -> uso();
            }
        } catch (Exception e) {
            System.out.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Home pronta para validar sem escrever: config.json, recipes/, batches/, screenshots/, health.json
     * e journal.jsonl ja existem (o construtor do Store nao cria nada nela).
     */
    static boolean homePronta(Path home) {
        return java.nio.file.Files.isRegularFile(home.resolve("config.json"))
                && java.nio.file.Files.isDirectory(home.resolve("recipes"))
                && java.nio.file.Files.isDirectory(home.resolve("batches"))
                && java.nio.file.Files.isDirectory(home.resolve("screenshots"))
                && java.nio.file.Files.isRegularFile(home.resolve("health.json"))
                && java.nio.file.Files.isRegularFile(home.resolve("journal.jsonl"));
    }

    /** So "on"/"off" exatos mexem no kill-switch; qualquer outro valor e rejeitado sem tocar no estado. */
    static boolean aplicarStop(Store store, String arg) throws IOException {
        if (!"on".equals(arg) && !"off".equals(arg)) {
            throw new IllegalArgumentException("stop invalido: '" + arg + "' (use on|off)");
        }
        boolean on = "on".equals(arg);
        store.setStopped(on);
        return on;
    }

    private static String statusTexto(Store store) {
        StringBuilder sb = new StringBuilder();
        sb.append("kill-switch: ").append(store.isStopped() ? "ATIVO (STOP)" : "inativo").append("\n");
        sb.append("home: ").append(store.home).append("\n");
        sb.append(String.format("%-11s %-9s %-14s %-11s %s%n", "REDE", "ESTADO", "PUBLICAS HOJE", "LOTES HOJE", "ULTIMA ACAO"));
        for (String n : store.knownNetworks()) {
            var u = store.usageFor(n);
            String lotes = u.batches + "/" + store.networkCfg(n).path("maxBatchesPerDay").asInt(4);
            sb.append(String.format("%-11s %-9s %d/%-11d %-11s %s%n", n, store.effectiveState(n),
                    u.publicActions, store.networkCfg(n).path("maxPublicActionsPerDay").asInt(5),
                    lotes,
                    u.lastPublicActionMs == 0 ? "nunca" : java.time.Instant.ofEpochMilli(u.lastPublicActionMs).toString()));
        }
        return sb.toString();
    }

    private static void uso() {
        System.out.println("""
                Social MCP — CLI do aprovador unico

                uso: bin/social-mcp --cli <comando>
                  status                                     estado das redes e cotas
                  batches                                    lista lotes (draft/approved/executed/rejected)
                  approve <batchId> [humano|orquestrador] [nota] [--fatos <arquivo>]... [--forcar]
                                                             aprova lote apos o gate de texto (aprovador unico:
                                                             dono ou orquestrador ativo); --forcar so humano
                  reject <batchId> [nota]                    rejeita rascunho ou revoga lote aprovado
                  state <rede> <healthy|cooldown|paused|banned> [motivo]
                  stop on|off                                kill-switch global
                  journal [n]                                ultimos n eventos
                  validar-receitas [rede]                    expande cada acao das receitas com params de exemplo
                                                             e roda a validacao do lote SEM gravar nada (nao gasta
                                                             cota): aceita/recusada e conferencia forte|fraca|ausente;
                                                             exit 1 se algo foi recusado ou escrita sem conferencia forte""");
    }
}
