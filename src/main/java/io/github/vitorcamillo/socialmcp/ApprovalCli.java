package io.github.vitorcamillo.socialmcp;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * CLI do aprovador unico. So roda em terminal, fora do alcance do agente executor.
 * Quem aprova: o dono (humano) ou o orquestrador ativo que esta vendo a operacao.
 * Sem orquestrador vendo, o lote espera aprovacao humana.
 * Comandos que reativam a rede ou afrouxam o gate (state healthy, --forcar, --ensaio)
 * exigem TTY interativo do dono + CONFIRMO: agente (sem TTY) recebe codigo 2 sem
 * alterar nada. Pausar (cooldown/paused/banned) e o kill-switch continuam abertos:
 * pausar e sempre seguro.
 */
public final class ApprovalCli {
    private ApprovalCli() {}

    /** Ponto de injecao para teste; o padrao usa System.console() (null = sem TTY = agente). */
    interface Terminal {
        boolean interativo();
        String lerLinha(String prompt);
    }

    /** Teste nao tem TTY (System.console() sempre null): injetavel para exercitar o caso com console. */
    static Supplier<Object> consoleDoSistema = System::console;

    static Terminal terminal = new Terminal() {
        @Override public boolean interativo() { return terminalReal(consoleDoSistema.get(), Console.class); }
        @Override public String lerLinha(String prompt) {
            Console c = System.console();
            return c == null ? null : c.readLine(prompt);
        }
    };

    /**
     * TTY de verdade. Ate o JDK 21, System.console() so existe com terminal; do 22 em diante ele
     * existe tambem com entrada/saida redirecionada (agente) e quem responde e Console.isTerminal().
     * Na duvida (erro ao perguntar), nao e terminal: fail-closed. O metodo e buscado no tipo
     * publico (Console), nao na classe concreta, que no JDK 22+ nao e acessivel por reflexao.
     */
    static boolean terminalReal(Object console, Class<?> tipo) {
        if (console == null) return false;
        try {
            return Boolean.TRUE.equals(tipo.getMethod("isTerminal").invoke(console));
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Pontos de injecao para teste; o padrao e o home real, System.exit e o importador real. */
    static Supplier<Path> homePadrao = Store::defaultHome;
    static IntConsumer saida = System::exit;

    interface Importador {
        String importar(Store store, String rede, String arquivo) throws Exception;
    }

    static Importador importador = SessionImporter::run;

    public static void run(String[] args) {
        int c = principal(args, homePadrao.get());
        if (args.length > 0) saida.accept(c);
    }

    /** Nucleo testavel do run(String[]): mesma logica sem chamar System.exit. */
    static int principal(String[] args, Path home) {
        if (args.length == 0) {
            uso();
            return 0;
        }
        if (args[0].equals("validar-receitas")) {
            // so le: nao cria home/config/journal (o construtor do Store cria o que faltar)
            if (!homePronta(home)) {
                System.out.println("ERRO: home do MCP incompleta (" + home + "): falta config.json ou recipes/; nada foi criado");
                return 1;
            }
        }
        return run(args, new Store(home));
    }

    /** Despachante testavel: devolve o codigo de saida em vez de chamar System.exit. */
    static int run(String[] args, Store store) {
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
                        System.out.println("uso: approve <batchId> [humano|orquestrador] [nota] [--fatos <arquivo>]... [--forcar] [--ensaio]");
                        return 1;
                    }
                    String aprovador = "humano";
                    String nota = "";
                    boolean forcar = false;
                    boolean ensaio = false;
                    List<Path> fichas = new ArrayList<>();
                    List<String> posicionais = new ArrayList<>();
                    for (int i = 2; i < args.length; i++) {
                        String a = args[i];
                        if (a.equals("--forcar")) {
                            forcar = true;
                        } else if (a.equals("--ensaio")) {
                            ensaio = true;
                        } else if (a.equals("--fatos")) {
                            if (i + 1 >= args.length) {
                                System.out.println("ERRO: --fatos exige um arquivo");
                                return 1;
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
                    String comando = "approve " + args[1] + (forcar ? " --forcar" : "") + (ensaio ? " --ensaio" : "");
                    if (forcar || ensaio) {
                        int g = exigirConfirmacao(store, comando);
                        if (g != 0) return g;
                    }
                    registrarCli(store, comando);
                    String resultado = batches.approve(args[1], aprovador, nota, fichas, forcar, ensaio);
                    String gate = forcar ? "gate FORCADO por humano" : "gate " + resultado;
                    System.out.println("Lote " + args[1] + " APROVADO por " + aprovador + " (" + gate + "). A operadora ja pode executar com social_execute_batch.");
                }
                case "reject" -> {
                    if (args.length < 2) {
                        System.out.println("uso: reject <batchId> [nota]");
                        return 1;
                    }
                    batches.reject(args[1], args.length >= 3 ? args[2] : "");
                    System.out.println("Lote " + args[1] + " rejeitado/revogado.");
                }
                case "state" -> {
                    if (args.length < 3) {
                        System.out.println("uso: state <rede> <healthy|cooldown|paused|banned> [motivo]");
                        return 1;
                    }
                    String estado = args[2];
                    if (!List.of("healthy", "cooldown", "paused", "banned").contains(estado)) {
                        System.out.println("estado invalido: " + estado);
                        return 1;
                    }
                    String motivo = args.length >= 4 ? args[3] : "(sem motivo)";
                    String comando = "state " + args[1] + " " + estado;
                    String by = "dono (CLI)";
                    if ("healthy".equals(estado)) {
                        int g = exigirConfirmacao(store, comando);
                        if (g != 0) return g;
                        by = "dono (CLI tty)";
                    }
                    registrarCli(store, comando);
                    store.setState(args[1], estado, motivo, by);
                    System.out.println("Rede '" + args[1] + "' agora esta '" + estado + "'.");
                }
                case "stop" -> {
                    if (args.length < 2) {
                        System.out.println("uso: stop on|off");
                        return 1;
                    }
                    boolean on = aplicarStop(store, args[1]);
                    System.out.println(on ? "Kill-switch ATIVO: nenhuma acao ate 'stop off'." : "Kill-switch desativado.");
                }
                case "import-cookies" -> {
                    if (args.length < 3) {
                        System.out.println("uso: import-cookies <rede> <arquivo.json>");
                        return 1;
                    }
                    System.out.println(importador.importar(store, args[1], args[2]));
                }
                case "validar-receitas" -> {
                    RecipeValidator.Relatorio r = RecipeValidator.validar(store, args.length >= 2 ? args[1] : null);
                    System.out.println(RecipeValidator.texto(r));
                    return r.ok() ? 0 : 1;
                }
                case "journal" -> {
                    int n = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
                    List<String> lines = store.tail(n);
                    System.out.println(lines.isEmpty() ? "(journal vazio)" : String.join("\n", lines));
                }
                default -> uso();
            }
            return 0;
        } catch (Exception e) {
            System.out.println("ERRO: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Comando sensivel (reativar rede, --forcar, --ensaio): so o dono num terminal
     * interativo. Sem TTY (agente) recusa com codigo 2 sem alterar nada; com TTY,
     * qualquer resposta que nao seja CONFIRMO recusa do mesmo jeito.
     */
    private static int exigirConfirmacao(Store store, String comando) {
        if (!confirmacaoExigida(store)) {
            // dono desligou o CONFIRMO em config.json (defaults.exigirConfirmo=false): segue, mas fica no journal
            store.appendJournal("confirmacao_dispensada", null, Json.obj().put("comando", comando)
                    .put("tty", terminal.interativo()));
            return 0;
        }
        if (!terminal.interativo()) {
            System.err.println("RECUSADO: '" + comando + "' so pelo dono num terminal interativo (sem TTY = agente)");
            return 2;
        }
        String resp = terminal.lerLinha("Digite CONFIRMO para seguir: ");
        if (!"CONFIRMO".equals(resp)) {
            System.err.println("RECUSADO: '" + comando + "' sem CONFIRMO");
            return 2;
        }
        return 0;
    }

    /** CONFIRMO so deixa de ser exigido com defaults.exigirConfirmo == false (booleano); ausente/invalido = exige. */
    static boolean confirmacaoExigida(Store store) {
        try {
            com.fasterxml.jackson.databind.JsonNode v = store.defaultsCfg().get("exigirConfirmo");
            return !(v != null && v.isBoolean() && !v.asBoolean());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Trilha de quem chamou a CLI: comando, usuario, se havia TTY e o processo pai. */
    private static void registrarCli(Store store, String comando) {
        ProcessHandle pai = ProcessHandle.current().parent().orElse(null);
        store.appendJournal("cli_chamada", null, Json.obj()
                .put("comando", comando)
                .put("usuario", System.getProperty("user.name"))
                .put("tty", terminal.interativo())
                .put("ppid", pai == null ? -1L : pai.pid())
                .put("paiCmd", pai == null ? "" : pai.info().command().orElse("")));
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
                  approve <batchId> [humano|orquestrador] [nota] [--fatos <arquivo>]... [--forcar] [--ensaio]
                                                             aprova lote apos o gate de texto (aprovador unico:
                                                             dono ou orquestrador ativo); --forcar e --ensaio so
                                                             pelo dono num terminal interativo (CONFIRMO)
                  reject <batchId> [nota]                    rejeita rascunho ou revoga lote aprovado
                  state <rede> <healthy|cooldown|paused|banned> [motivo]
                                                             reativar rede (healthy) so pelo dono num terminal
                                                             interativo (CONFIRMO); pausar segue aberto
                  stop on|off                                kill-switch global
                  journal [n]                                ultimos n eventos
                  validar-receitas [rede]                    expande cada acao das receitas com params de exemplo
                                                             e roda a validacao do lote SEM gravar nada (nao gasta
                                                             cota): aceita/recusada e conferencia forte|fraca|ausente;
                                                             exit 1 se algo foi recusado ou escrita sem conferencia forte""");
    }
}
