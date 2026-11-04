package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.profiles.ProfileChromeOptionsFactory;
import io.github.selenium.javaDriverless.profiles.SdkProfile;
import io.github.selenium.javaDriverless.profiles.SdkProfilePresets;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um Chrome persistente por rede (profile proprio, cookies/sessao preservados).
 * Identidade tecnica estavel por conta usando as APIs do proprio javaDriveless:
 * preset nativo do host com pais/locale alinhados (SdkProfilePresets) e proxy
 * fixo por rede (setSingleProxy ou bridge autenticada) — o IP/coerencia regional
 * de cada conta nunca muda entre execucoes.
 */
public class BrowserPool implements AutoCloseable {
    /** Tamanho padrao da janela: cabe os botoes de TikTok/Facebook (934x687 cortava). */
    static final WindowSize DEFAULT_WINDOW = new WindowSize(1280, 900);
    private static final Pattern WINDOW_RE = Pattern.compile("^\\s*(\\d{3,5})\\s*[xX]\\s*(\\d{3,5})\\s*$");
    private static final int MIN_DIM = 400;
    private static final int MAX_DIM = 7680;
    private static final long WINDOW_CDP_TIMEOUT_S = 5;

    /** Largura x altura externas da janela do Chrome. */
    record WindowSize(int width, int height) {
    }

    /** Opt-in por rede para reaproveitar o Chrome vivo do profile exato (default false). */
    static final String ATTACH_OPT_IN_KEY = "attachExistingChrome";
    /** Politica suportada pela lib SOMENTE no attach a navegador externo. */
    static final String ATTACH_RUNTIME_POLICY = "legacy-spoof";
    /** Preset dos launches normais (o attach nao o altera). */
    static final String LAUNCH_RUNTIME_POLICY = "minimal-cdp";

    private final Store store;
    private final Map<String, JavaDriverless> drivers = new ConcurrentHashMap<>();

    public BrowserPool(Store store) {
        this.store = store;
    }

    public JavaDriverless get(String network) {
        return drivers.computeIfAbsent(network, n -> {
            ObjectNode cfg = store.networkCfg(n);
            // storageDir nao-nulo: o SdkProfile passa a ser dono do user-data-dir,
            // entao o gerenciamento de profiles do JavaDriverless fica OFF (exigencia dele).
            String dir = storageDir(n);
            // Chrome principal VIVO do MESMO profile EXATO: so reaproveita com opt-in
            // explicito (attachExistingChrome=true). Sem opt-in, conflito claro e NENHUM
            // lancamento novo (NAO mata processo, NAO apaga lock). Sem processo/porta
            // valido o lancamento segue normal, com a recuperacao stale da biblioteca.
            Optional<String> attachTo = resolveAttach(n, cfg, dir, ExistingChrome.findForProfile(Path.of(dir)));
            ChromeOptions options = attachTo.isPresent()
                    ? buildAttachOptions(n, cfg, dir, attachTo.get())
                    : buildOptions(n, cfg, dir);
            JavaDriverless d = new JavaDriverless("sdk-" + n, options, false);
            d.setPageLoadTimeout(45_000);
            d.implicitlyWait(4_000);
            applyWindowSize(d, n, parseWindowSize(cfg.path("windowSize").asText(null)));
            return d;
        });
    }

    /**
     * Garante o tamanho final: os defaults da lib incluem --start-maximized, que pode
     * sobrepor --window-size. Volta a janela a "normal" e aplica bounds via CDP
     * (Browser.setWindowBounds). Falha aqui nao derruba a sessao (so loga em stderr).
     */
    private static void applyWindowSize(JavaDriverless d, String network, WindowSize size) {
        try {
            d.getChrome().normalizeWindow().get(WINDOW_CDP_TIMEOUT_S, TimeUnit.SECONDS);
            d.getChrome().setWindowSize(size.width(), size.height()).get(WINDOW_CDP_TIMEOUT_S, TimeUnit.SECONDS);
            // No Linux o Chrome desenha o proprio frame (CSD) e a sombra entra nos bounds:
            // 1280 de janela viravam 1204 visiveis. Mede o viewport e compensa a sombra.
            Object inner = d.executeScript("window.innerWidth");
            if (inner instanceof Number n) {
                WindowSize fixed = compensateShadow(size, n.intValue());
                if (!fixed.equals(size)) {
                    d.getChrome().setWindowSize(fixed.width(), fixed.height()).get(WINDOW_CDP_TIMEOUT_S, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            System.err.println("[social-mcp] " + network + ": nao ajustou janela para "
                    + size.width() + "x" + size.height() + ": " + e.getMessage());
        }
    }

    /** Maior sombra de frame aceita como compensacao; acima disso e outra coisa (zoom, painel). */
    static final int MAX_SHADOW_DELTA = 200;

    /**
     * Se o viewport (innerWidth) saiu menor que a largura pedida so pela sombra do frame,
     * soma a mesma diferenca nos dois eixos (a sombra e simetrica). Fora de (0, 200] nao mexe.
     */
    static WindowSize compensateShadow(WindowSize target, int innerWidth) {
        int delta = target.width() - innerWidth;
        if (delta <= 0 || delta > MAX_SHADOW_DELTA) return target;
        return new WindowSize(target.width() + delta, target.height() + delta);
    }

    /**
     * "LARGURAxALTURA" (ex.: "1280x900"). Nulo, vazio, formato invalido ou fora de
     * [400, 7680] em qualquer eixo cai no padrao 1280x900.
     */
    static WindowSize parseWindowSize(String raw) {
        if (raw == null) return DEFAULT_WINDOW;
        Matcher m = WINDOW_RE.matcher(raw);
        if (!m.matches()) return DEFAULT_WINDOW;
        int w = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        if (w < MIN_DIM || h < MIN_DIM || w > MAX_DIM || h > MAX_DIM) return DEFAULT_WINDOW;
        return new WindowSize(w, h);
    }

    private String storageDir(String network) {
        return store.home.resolve(".javadriverless_profiles").resolve("sdk-" + network).toString();
    }

    /**
     * Opt-in de reaproveitamento do Chrome vivo (default false). So true autoriza o
     * attach ao processo principal do MESMO profile EXATO detectado por ExistingChrome.
     */
    static boolean attachOptIn(ObjectNode cfg) {
        return cfg.path(ATTACH_OPT_IN_KEY).asBoolean(false);
    }

    /**
     * Resolve o reaproveitamento: sem Chrome detectado, vazio (launch normal). Com Chrome
     * vivo e sem opt-in, lanca IllegalStateException com o conflito e a opcao suportada
     * (NENHUM Chrome novo e lancado). Com opt-in, devolve o endereco de loopback tal como
     * detectado (igualdade exata de profile garantida por ExistingChrome).
     */
    static Optional<String> resolveAttach(String network, ObjectNode cfg, String storageDir,
            Optional<String> detected) {
        if (detected.isEmpty()) return Optional.empty();
        if (!attachOptIn(cfg)) throw new IllegalStateException(attachConflictMessage(network, storageDir, detected.get()));
        return detected;
    }

    static String attachConflictMessage(String network, String storageDir, String debuggerAddress) {
        return "Chrome externo vivo no profile exato " + storageDir + " (debug em " + debuggerAddress + ")"
                + "; por padrao (" + ATTACH_OPT_IN_KEY + "=false) nenhum Chrome novo e lancado"
                + " para a rede '" + network + "'. Para reaproveitar esse navegador, configure \""
                + ATTACH_OPT_IN_KEY + "\": true na rede '" + network + "' (o attach usa a politica suportada "
                + ATTACH_RUNTIME_POLICY + "; o launch normal continua no preset " + LAUNCH_RUNTIME_POLICY + ")."
                + " Alternativa: feche o Chrome externo desse profile.";
    }

    /**
     * Opcoes por rede. Chaves de config (networkCfg):
     * "pais" (default BR), "proxy" ("host:port", "http://host:port/" ou
     * "http://user:senha@host:port" — com @ usa a bridge autenticada do javaDriveless),
     * "windowSize" ("1280x900"; defaults.windowSize ou networks.&lt;rede&gt;.windowSize),
     * "attachExistingChrome" (default false; true reaproveita o Chrome vivo do profile exato).
     * storageDir define onde a sessao (cookies/historico) persiste; null = default do preset.
     */
    public static ChromeOptions buildOptions(String network, ObjectNode cfg, String storageDir) {
        ChromeOptions options = ProfileChromeOptionsFactory.fromProfile(buildProfile(network, cfg, storageDir));
        applyProxy(options, cfg);
        return options;
    }

    /**
     * Opcoes de attach ao Chrome vivo: mesma identidade do launch normal (mesmo profile
     * exato, mesmo proxy, mesma janela), mas com a politica suportada pela lib para anexar
     * (legacy-spoof) e o debuggerAddress de loopback detectado. Launch normal nao e tocado.
     */
    public static ChromeOptions buildAttachOptions(String network, ObjectNode cfg, String storageDir,
            String debuggerAddress) {
        Objects.requireNonNull(debuggerAddress, "debuggerAddress");
        ChromeOptions options = ProfileChromeOptionsFactory.fromProfile(buildAttachProfile(network, cfg, storageDir));
        applyProxy(options, cfg);
        options.setDebuggerAddress(debuggerAddress);
        return options;
    }

    /** Profile base (identidade da rede); o launch normal usa o preset da lib sem retoques. */
    static SdkProfile buildProfile(String network, ObjectNode cfg, String storageDir) {
        String pais = cfg.path("pais").asText("BR");
        SdkProfile profile = SdkProfilePresets.defaultRuntime("sdk-" + network, "Social MCP " + network, pais);
        if (storageDir != null && !storageDir.isBlank()) {
            profile.storage.userDataDir = storageDir;
        }
        // A factory da lib transforma windowWidth/Height em --window-size=W,H.
        WindowSize size = parseWindowSize(cfg.path("windowSize").asText(null));
        profile.browser.windowWidth = size.width();
        profile.browser.windowHeight = size.height();
        return profile;
    }

    /**
     * Profile de attach: igual ao base, exceto fingerprint.runtimePolicy=legacy-spoof.
     * Justificativa: a lib nao permite attach com o preset de launch (minimal-cdp) — o
     * ChromeSessionCore rejeita a sessao e recomenda legacy-spoof para anexar. O navegador
     * externo NAO pertence a esta instancia MCP (mesmo profile), entao nunca se mata
     * processo nem se apaga lock: so se anexa via CDP de loopback ao profile exato.
     */
    static SdkProfile buildAttachProfile(String network, ObjectNode cfg, String storageDir) {
        SdkProfile profile = buildProfile(network, cfg, storageDir);
        profile.fingerprint.runtimePolicy = ATTACH_RUNTIME_POLICY;
        return profile;
    }

    private static void applyProxy(ChromeOptions options, ObjectNode cfg) {
        String proxy = cfg.path("proxy").asText("");
        if (!proxy.isBlank()) {
            if (proxy.contains("@")) {
                options.setAuthenticatedProxyBridge(proxy);
            } else {
                options.setSingleProxy(normalizeProxy(proxy));
            }
        }
    }

    static String normalizeProxy(String p) {
        if (!p.contains("://")) p = "http://" + p;
        if (!p.endsWith("/")) p = p + "/";
        return p;
    }

    public boolean isOpen(String network) {
        return drivers.containsKey(network);
    }

    public int openCount() {
        return drivers.size();
    }

    public void closeAll() {
        drivers.values().forEach(d -> {
            try {
                d.quit();
            } catch (Exception ignored) {
            }
        });
        drivers.clear();
    }

    @Override
    public void close() {
        closeAll();
    }

    /** Sinais de bloqueio: titulo + innerText de alertas/dialogos/aria-live/h1/h2 em todas as roots (ShadowDom.rjRoots, inclui shadow aberto; dedupe, "\n", max 4000). Erro = "". */
    static final String ALERT_TEXT_JS = "(() => {" + ShadowDom.LIB + " try {"
            + " const out = []; const seen = new Set();"
            + " const push = (s) => { s = (s == null ? '' : String(s)).trim();"
            + " if (!s || seen.has(s)) return; seen.add(s); out.push(s); };"
            + " push(document.title);"
            + " const sel = '[role=alert],[role=alertdialog],[role=dialog],[aria-live],[aria-modal=true],h1,h2';"
            + " rjRoots(document, []).forEach((root) => { root.querySelectorAll(sel).forEach((el) => { push(el.innerText); }); });"
            + " return out.join('\\n').slice(0, 4000);"
            + " } catch (e) { return ''; } })()";

    public static String alertText(JavaDriverless d) {
        try {
            Object o = d.executeScript(ALERT_TEXT_JS);
            String s = o == null ? "" : String.valueOf(o);
            if (s.length() > 4000) return s.substring(0, 4000);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** Texto visivel da pagina (innerText), atravessando shadow DOM aberto (ShadowDom.textScript). */
    public static String visibleText(JavaDriverless d, int maxChars) {
        Object o = d.executeScript(ShadowDom.textScript());
        String s = o == null ? "" : String.valueOf(o);
        if (s.length() > maxChars) {
            return s.substring(0, maxChars) + "...[truncado]";
        }
        return s;
    }
}
