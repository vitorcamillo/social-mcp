package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream saidaGate = p.getInputStream();
            Thread leitor = Thread.ofVirtual().name("text-gate-saida").unstarted(() -> copiar(saidaGate, buf));
            leitor.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                matar(p);
                return new Resultado(1, "ERRO", "gate excedeu " + timeoutSec + "s");
            }
            leitor.join(5000);
            String saida;
            synchronized (buf) {
                saida = buf.toString(StandardCharsets.UTF_8);
            }
            int exit = p.exitValue();
            return new Resultado(exit, resultadoDe(saida, exit), saida);
        } catch (IOException e) {
            return new Resultado(1, "ERRO", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            matar(p);
            Thread.currentThread().interrupt();
            return new Resultado(1, "ERRO", "gate interrompido");
        }
    }

    /** Fail-closed: gate abandonado (timeout ou interrupcao) nao fica rodando orfao. */
    private static void matar(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void copiar(InputStream in, ByteArrayOutputStream buf) {
        byte[] b = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(b)) != -1) {
                synchronized (buf) {
                    buf.write(b, 0, n);
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
