package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gate de texto externo (scripts/redes/gate_texto.py) rodado DENTRO do approve.
 * Fail-closed: sem comando configurado, timeout ou erro de execucao, o lote nao aprova.
 */
public final class TextGate {
    private TextGate() {}

    /** resultado: PASS|WARN|FAIL|ERRO. */
    public record Resultado(int exit, String resultado, String saida) {}

    /** Roda o gate externo em modo pre sobre o arquivo do lote. Fail-closed. */
    public static Resultado rodarPre(Store store, Path arquivoLote, List<Path> fichas) {
        JsonNode gate = store.defaultsCfg().path("gate");
        List<String> cmd = new ArrayList<>();
        for (JsonNode c : gate.path("command")) {
            if (c.isTextual() && !c.asText().isBlank()) cmd.add(c.asText());
        }
        if (cmd.isEmpty()) {
            throw new IllegalStateException("gate de texto nao configurado em config.json (defaults.gate.command); aprovacao bloqueada");
        }
        int timeoutSec = gate.path("timeoutSec").asInt(120);
        if (timeoutSec <= 0) timeoutSec = 120;
        cmd.add("pre");
        cmd.add("--batch");
        cmd.add(arquivoLote.toAbsolutePath().toString());
        String cfg = gate.path("config").asText("");
        if (!cfg.isBlank()) {
            cmd.add("--config");
            cmd.add(cfg);
        }
        cmd.add("--json");
        if (fichas != null) {
            for (Path f : fichas) {
                cmd.add("--fatos");
                cmd.add(f.toString());
            }
        }
        // grupo proprio (setsid -w: com fork, espera e repassa o exit) para matar tudo de uma vez
        boolean grupo = Files.isExecutable(Path.of(SETSID)) && Files.isExecutable(Path.of(KILL));
        if (grupo) cmd.addAll(0, List.of(SETSID, "-w"));
        Process p = null;
        try {
            // start + registro atomicos frente ao gancho: Ctrl-C entre os dois deixaria o gate orfao
            synchronized (INICIO) {
                if (encerrando) return new Resultado(1, "ERRO", "JVM encerrando");
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                VIVOS.put(p, grupo);
            }
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream saidaGate = p.getInputStream();
            Thread leitor = Thread.ofVirtual().name("text-gate-saida").unstarted(() -> copiar(saidaGate, buf));
            leitor.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                matar(p, grupo);
                return new Resultado(1, "ERRO", "gate excedeu " + timeoutSec + "s");
            }
            leitor.join(5000);
            // sobra em segundo plano: no grupo proprio ela nao veria o Ctrl-C e sairia do registro
            matar(p, grupo);
            String saida;
            synchronized (buf) {
                saida = buf.toString(StandardCharsets.UTF_8);
            }
            int exit = p.exitValue();
            return new Resultado(exit, resultadoDe(saida, exit), saida);
        } catch (IOException e) {
            return new Resultado(1, "ERRO", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            matar(p, grupo);
            Thread.currentThread().interrupt();
            return new Resultado(1, "ERRO", "gate interrompido");
        } finally {
            if (p != null) VIVOS.remove(p);
        }
    }

    static String SETSID = "/usr/bin/setsid";
    static String KILL = "/bin/kill";

    /**
     * Gates rodando agora. Em grupo proprio o gate nao recebe o Ctrl-C do terminal da CLI; o
     * gancho de encerramento da JVM (SIGINT/SIGTERM) mata o grupo de cada um.
     */
    static final Map<Process, Boolean> VIVOS = new ConcurrentHashMap<>();
    private static final Object INICIO = new Object();
    static volatile boolean encerrando;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TextGate::matarVivos, "text-gate-encerramento"));
    }

    static void matarVivos() {
        synchronized (INICIO) {
            encerrando = true; // espera um start em andamento e barra os proximos
        }
        VIVOS.forEach(TextGate::matar);
    }

    /**
     * Fail-closed: gate abandonado (timeout ou interrupcao) nao fica rodando orfao. O SIGKILL no
     * grupo pega ate o filho criado durante o kill (o snapshot de descendants() nao pegaria).
     */
    private static void matar(Process p, boolean grupo) {
        if (grupo) {
            try {
                Process k = new ProcessBuilder(KILL, "-KILL", "--", "-" + p.pid()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                k.waitFor(5, TimeUnit.SECONDS);
            } catch (IOException e) {
                // sem kill: fica o fallback por descendants() logo abaixo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void copiar(InputStream in, ByteArrayOutputStream buf) {
        byte[] b = new byte[8192];
        // segura o monitor do stream a copia inteira: quando o gate sai, o JDK (processExited,
        // synchronized no mesmo stream) drena e FECHA o pipe se o leitor estiver entre dois read;
        // a saida que um descendente ainda escreveria depois disso se perderia
        try (in) {
            synchronized (in) {
                int n;
                while ((n = in.read(b)) != -1) {
                    synchronized (buf) {
                        buf.write(b, 0, n);
                    }
                }
            }
        } catch (IOException ignored) {
            // processo morto: fica com o que ja foi lido
        }
    }

    private static final String[] NOMES = {"PASS", "WARN", "FAIL", "ERRO"};

    private static int severidade(String r) {
        return switch (r) {
            case "PASS" -> 0;
            case "WARN" -> 1;
            case "FAIL" -> 2;
            default -> 3;
        };
    }

    /**
     * Resultado do gate: vale o mais severo entre o JSON e o exit. Um JSON
     * "PASS" nunca abafa exit de falha (2 ou outro erro), e um FAIL do JSON
     * tambem reprova com exit 0 — resposta contraditoria reprova, nao libera.
     */
    static String resultadoDe(String saida, int exit) {
        String doExit = switch (exit) {
            case 0 -> "PASS";
            case 3 -> "WARN";
            case 2 -> "FAIL";
            default -> "ERRO";
        };
        String[] linhas = saida == null ? new String[0] : saida.split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            String l = linhas[i].trim();
            if (!l.startsWith("{")) continue;
            try {
                String r = Json.parse(l).path("resultado").asText("").trim().toUpperCase();
                if (List.of("PASS", "WARN", "FAIL", "ERRO").contains(r)) {
                    return NOMES[Math.max(severidade(r), severidade(doExit))];
                }
            } catch (RuntimeException ignored) {
                // cai para o exit
            }
            break;
        }
        return doExit;
    }

    /** SHA-256 (hex minusculo) de Json.write(actions) em UTF-8. */
    public static String hashActions(JsonNode actions) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Json.write(actions).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
