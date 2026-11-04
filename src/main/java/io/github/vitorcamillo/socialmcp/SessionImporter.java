package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.JavaDriverless;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Importa cookies (JSON de exportacao) na sessao persistente de uma rede.
 * Comando do dono via CLI — fora do alcance do agente executor.
 * Uso: bin/social-mcp --cli import-cookies <rede> <arquivo.json>
 */
public final class SessionImporter {
    private SessionImporter() {}

    private static final String[] MARCADORES_LOGIN = {
            "fazer login", "entrar", "log in", "sign in", "log in or sign up",
            "cadastre-se", "sign up", "create account", "registrar-se", "criar conta"
    };

    public static String run(Store store, String network, String arquivo) throws Exception {
        if (!store.knownNetworks().contains(network)) {
            throw new IllegalArgumentException("rede desconhecida: " + network);
        }
        JsonNode arr = Json.parse(Files.readString(Path.of(arquivo), StandardCharsets.UTF_8));
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("arquivo sem cookies: " + arquivo);
        }
        BrowserPool pool = new BrowserPool(store);
        Runtime.getRuntime().addShutdownHook(new Thread(pool::closeAll));
        try {
            JavaDriverless d = pool.get(network);
            String start = store.networkCfg(network).path("startUrl").asText("https://www." + network + ".com/");
            d.get(start);
            d.sleep(1.0);
            int ok = 0;
            int falha = 0;
            for (JsonNode c : arr) {
                try {
                    Map<String, Object> cookie = new HashMap<>();
                    cookie.put("name", c.get("name").asText());
                    cookie.put("value", c.get("value").asText());
                    cookie.put("domain", c.get("domain").asText());
                    cookie.put("path", c.path("path").asText("/"));
                    if (c.hasNonNull("secure")) cookie.put("secure", c.get("secure").asBoolean());
                    if (c.hasNonNull("httpOnly")) cookie.put("httpOnly", c.get("httpOnly").asBoolean());
                    long exp = c.path("expires").asLong(0);
                    if (exp > 0) cookie.put("expires", exp);
                    if (c.hasNonNull("sameSite")) cookie.put("sameSite", c.get("sameSite").asText());
                    d.addCookie(cookie);
                    ok++;
                } catch (Exception e) {
                    falha++;
                }
            }
            d.refresh();
            d.sleep(3.0);
            String texto = BrowserPool.visibleText(d, 5000).toLowerCase();
            String marcador = null;
            for (String m : MARCADORES_LOGIN) {
                if (texto.contains(m)) {
                    marcador = m;
                    break;
                }
            }
            String shot = store.screenshotsDir().resolve(System.currentTimeMillis() + "-import-" + network + ".png").toString();
            try {
                d.screenshot(shot);
            } catch (Exception ignored) {
            }
            store.appendJournal("session_import", network, Json.obj()
                    .put("cookiesOk", ok)
                    .put("cookiesFalha", falha)
                    .put("marcadorLogin", marcador == null ? "" : marcador));
            return "rede=" + network
                    + " | cookies aplicados=" + ok + " falhas=" + falha
                    + " | marcadorLogin=" + (marcador == null ? "NAO encontrado (provavelmente LOGADA — confirme no screenshot)" : "'" + marcador + "' (deslogada?)")
                    + " | screenshot=" + shot;
        } finally {
            pool.closeAll();
        }
    }
}
