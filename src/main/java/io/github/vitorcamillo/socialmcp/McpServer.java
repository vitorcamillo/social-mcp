package io.github.vitorcamillo.socialmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Servidor MCP sobre stdio (JSON-RPC newline-delimited).
 * NADA além de respostas JSON-RPC pode ir para stdout.
 */
public final class McpServer {
    private McpServer() {}

    public static void run() throws Exception {
        Store store = new Store();
        Tools tools = new Tools(store);
        Runtime.getRuntime().addShutdownHook(new Thread(tools::shutdown));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8)), false);

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JsonNode req;
            try {
                req = Json.parse(line);
            } catch (Exception e) {
                continue;
            }
            if (req.path("id").isMissingNode() || req.path("id").isNull()) continue; // notification: sem resposta

            ObjectNode resp = Json.obj();
            resp.put("jsonrpc", "2.0");
            resp.set("id", req.get("id"));
            String method = req.path("method").asText("");
            try {
                switch (method) {
                    case "initialize" -> {
                        ObjectNode r = Json.obj();
                        r.put("protocolVersion", req.path("params").path("protocolVersion").asText("2025-06-18"));
                        ObjectNode caps = r.putObject("capabilities");
                        caps.putObject("tools").put("listChanged", false);
                        ObjectNode si = r.putObject("serverInfo");
                        si.put("name", "social-mcp");
                        si.put("version", "0.1.0");
                        resp.set("result", r);
                    }
                    case "ping" -> resp.set("result", Json.obj());
                    case "tools/list" -> {
                        ObjectNode r = Json.obj();
                        r.set("tools", tools.definitions());
                        resp.set("result", r);
                    }
                    case "tools/call" -> {
                        JsonNode p = req.path("params");
                        String name = p.path("name").asText("");
                        JsonNode toolArgs = p.path("arguments");
                        try {
                            String text = tools.call(name, toolArgs);
                            ObjectNode r = Json.obj();
                            ObjectNode content = r.putArray("content").addObject();
                            content.put("type", "text");
                            content.put("text", text);
                            r.put("isError", false);
                            resp.set("result", r);
                        } catch (Exception e) {
                            ObjectNode r = Json.obj();
                            ObjectNode content = r.putArray("content").addObject();
                            content.put("type", "text");
                            content.put("text", "ERRO: " + e.getMessage());
                            r.put("isError", true);
                            resp.set("result", r);
                        }
                    }
                    default -> {
                        ObjectNode err = Json.obj();
                        err.put("code", -32601);
                        err.put("message", "method not found: " + method);
                        resp.set("error", err);
                    }
                }
            } catch (Exception e) {
                ObjectNode err = Json.obj();
                err.put("code", -32603);
                err.put("message", "internal error: " + e.getMessage());
                resp.set("error", err);
            }
            out.println(Json.write(resp));
            out.flush();
        }
        // stdin fechou (cliente encerrou): mata os Chromes e sai — threads do CDP sao nao-daemon
        tools.shutdown();
        System.exit(0);
    }
}
