package io.github.vitorcamillo.socialmcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reaproveitamento do Chrome principal VIVO do MESMO profile EXATO.
 *
 * <p>Contexto: o profile de cada rede ({@code store.home/.javadriverless_profiles/sdk-<rede>})
 * pode ja estar aberto num Chrome vivo. Lançar outro Chrome no mesmo user-data-dir falha no
 * SingletonLock; NAO se apaga lock nem se mata processo alheio. Em vez disso, esta classe
 * detecta o processo principal daquele profile e devolve o debuggerAddress de loopback para o
 * javaDriverless anexar ({@code ChromeOptions.setDebuggerAddress("127.0.0.1:porta")}, attach mode).
 *
 * <p>Regras da deteccao (so /proc local; nenhum endpoint remoto):
 * <ul>
 *   <li>exe com basename contendo "chrome"/"chromium", sem "sandbox";</li>
 *   <li>sem flag {@code --type=} (so o processo principal, nunca renderer/gpu/utility);</li>
 *   <li>{@code --user-data-dir} EXATAMENTE o caminho esperado normalizado (igualdade de Path,
 *       nunca substring: {@code sdk-x} nao casa {@code sdk-x-extra}); nas formas
 *       {@code --flag=valor}, {@code --flag valor} ou tudo reescrito num unico arg com espacos;</li>
 *   <li>porta de {@code --remote-debugging-port} em 1..65535;</li>
 *   <li>portas distintas entre processos do mesmo profile = ambiguidade: erro claro e NENHUM
 *       lancamento novo (o chamador propaga a excecao);</li>
 *   <li>antes de devolver, o PID e relido em /proc (nada de anexar a PID reutilizada ou a
 *       perfil trocado).</li>
 * </ul>
 *
 * <p>Saida (quit): no attach mode a lib nunca lanca processo (em
 * {@code ChromeSessionCore.startSessionAttempt} o {@code process = pb.start()} so roda com
 * {@code !isRemote}), entao {@code d.quit()} fecha so a sessao CDP e nao mata o Chrome externo;
 * o BrowserPool ainda constroi o driver com {@code useProfileManagement=false}, sem
 * gerenciamento de PID. Sem processo/porta valido, o lancamento segue normal (com a
 * recuperacao stale da propria biblioteca).
 */
public final class ExistingChrome {
    private ExistingChrome() {
    }

    /** Snapshot minimo de um processo para parse/selecao (sintetico nos testes, de /proc no real). */
    public record Snapshot(long pid, String exe, List<String> argv) {
    }

    /** Processo principal vivo do profile, com o endereco de loopback para anexar. */
    public record Selected(long pid, int port, String debuggerAddress) {
    }

    /**
     * Caminho live: escaneia /proc, seleciona o processo do profile e revalida o PID antes de
     * devolver. Vazio = sem processo/porta valido (lancamento normal). Ambiguidade lanca
     * IllegalStateException (nao lancar outro Chrome).
     */
    public static Optional<String> findForProfile(Path expectedProfileDir) {
        Path expected = normalize(expectedProfileDir);
        Optional<Selected> sel = select(expected, scanProc());
        if (sel.isEmpty()) return Optional.empty();
        // Revalida: o PID pode ter sido reutilizado entre o scan e o uso.
        Optional<Snapshot> fresh = reread(sel.get().pid());
        if (fresh.isEmpty()) return Optional.empty();
        Optional<Selected> again = select(expected, List.of(fresh.get()));
        if (again.isEmpty() || again.get().port() != sel.get().port()) return Optional.empty();
        return Optional.of(sel.get().debuggerAddress());
    }

    /**
     * Selecao pura sobre snapshots (deterministica, usada nos testes). Devolve o endereco
     * {@code 127.0.0.1:porta} quando ha exatamente uma porta distinta entre os processos do
     * profile; vazio quando nenhum processo/porta e valido.
     *
     * @throws IllegalStateException se houver portas distintas (ambiguo: nao anexar nem lancar)
     */
    public static Optional<Selected> select(Path expectedProfileDir, List<Snapshot> snapshots) {
        Path expected = normalize(expectedProfileDir);
        Map<Integer, Long> portToPid = new LinkedHashMap<>();
        for (Snapshot s : snapshots) {
            if (s == null || !isMainChromeExe(s.exe())) continue;
            List<String> tokens = flatten(s.argv());
            if (hasTypeFlag(tokens)) continue;
            String dataDir = flagValue(tokens, "--user-data-dir");
            if (dataDir == null || !samePath(dataDir, expected)) continue;
            Integer port = debugPort(tokens);
            if (port == null) continue;
            portToPid.putIfAbsent(port, s.pid());
        }
        if (portToPid.isEmpty()) return Optional.empty();
        if (portToPid.size() > 1) {
            throw new IllegalStateException("Chrome externo ambiguo para o profile " + expected
                    + ": portas de debug distintas " + portToPid.keySet()
                    + " nos PIDs " + portToPid.values()
                    + "; feche os Chromes extras do mesmo profile e tente de novo"
                    + " (nenhum Chrome novo foi lancado)");
        }
        int port = portToPid.keySet().iterator().next();
        long pid = portToPid.get(port);
        return Optional.of(new Selected(pid, port, "127.0.0.1:" + port));
    }

    /** Atalho para a selecao pura quando so o endereco interessa. */
    public static Optional<String> debuggerAddressFor(Path expectedProfileDir, List<Snapshot> snapshots) {
        return select(expectedProfileDir, snapshots).map(Selected::debuggerAddress);
    }

    /** Scan real de /proc (PID/exe/cmdline), limitado e tolerante a corrida/permissionamento. */
    public static List<Snapshot> scanProc() {
        List<Snapshot> out = new ArrayList<>();
        java.nio.file.DirectoryStream<Path> ds;
        try {
            ds = Files.newDirectoryStream(Path.of("/proc"));
        } catch (Exception e) {
            return out;
        }
        try (ds) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                reread(pid).ifPresent(out::add);
            }
        } catch (Exception ignored) {
            // /proc instavel no meio do scan: devolve o que ja coletou.
        }
        return out;
    }

    /** Releitura de um PID em /proc; vazio se o processo sumiu ou nao pode ser lido. */
    static Optional<Snapshot> reread(long pid) {
        String base = "/proc/" + pid;
        try {
            String exe = Files.readSymbolicLink(Path.of(base, "exe")).toString();
            byte[] raw = Files.readAllBytes(Path.of(base, "cmdline"));
            if (raw.length == 0) return Optional.empty();
            List<String> argv = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) {
                    argv.add(new String(raw, start, i - start, StandardCharsets.UTF_8));
                    start = i + 1;
                }
            }
            if (start < raw.length) {
                argv.add(new String(raw, start, raw.length - start, StandardCharsets.UTF_8));
            }
            argv.removeIf(String::isEmpty);
            if (argv.isEmpty()) return Optional.empty();
            return Optional.of(new Snapshot(pid, exe, List.copyOf(argv)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---------- parse ----------

    /** Exe do processo principal: basename com chrome/chromium, sem "sandbox". */
    static boolean isMainChromeExe(String exe) {
        if (exe == null || exe.isBlank()) return false;
        String base = exe.substring(exe.lastIndexOf('/') + 1);
        int cut = base.indexOf(" (deleted)");
        if (cut >= 0) base = base.substring(0, cut);
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains("sandbox")) return false;
        return lower.contains("chrome") || lower.contains("chromium");
    }

    /**
     * Achata argv: args normais passam inteiros; um arg unico reescrito com espacos
     * (ex.: {@code "--user-data-dir=/x --remote-debugging-port=1"}) e repartido. Caminhos com
     * espaco na forma separada ({@code --flag valor}) nao sao tocados.
     */
    static List<String> flatten(List<String> argv) {
        List<String> out = new ArrayList<>();
        if (argv == null) return out;
        for (String raw : argv) {
            if (raw == null) continue;
            if (raw.contains("--") && raw.contains(" ")) {
                for (String piece : raw.trim().split("\\s+(?=--)")) {
                    if (!piece.isEmpty()) out.add(piece);
                }
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    static boolean hasTypeFlag(List<String> tokens) {
        for (String t : tokens) {
            if (t.equals("--type") || t.startsWith("--type=")) return true;
        }
        return false;
    }

    /** Valor da flag nas formas {@code --nome=valor} ou {@code --nome valor}; nulo se ausente. */
    static String flagValue(List<String> tokens, String name) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
            if (t.equals(name) && i + 1 < tokens.size()) return tokens.get(i + 1);
        }
        return null;
    }

    /** Primeira porta valida (1..65535) da flag; nulo se ausente/invalida. */
    static Integer debugPort(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String v = null;
            if (t.startsWith("--remote-debugging-port=")) {
                v = t.substring("--remote-debugging-port=".length());
            } else if (t.equals("--remote-debugging-port") && i + 1 < tokens.size()) {
                v = tokens.get(i + 1);
            }
            if (v == null) continue;
            try {
                int port = Integer.parseInt(v.trim());
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException ignored) {
                // valor nao numerico: ignora este processo.
            }
            return null;
        }
        return null;
    }

    static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /** Igualdade exata de caminho normalizado (nunca substring). */
    static boolean samePath(String value, Path expected) {
        if (value == null || value.isBlank()) return false;
        try {
            return Path.of(value).toAbsolutePath().normalize().equals(expected);
        } catch (Exception e) {
            return false;
        }
    }
}
