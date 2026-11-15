/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package de.kodahosting.kodadash.routes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.mcp.McpAudit;
import de.kodahosting.kodadash.mcp.McpSettings;
import de.kodahosting.kodadash.mcp.McpTools;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * the MCP endpoint.
 *
 * Streamable HTTP with JSON responses. No SSE: a GET returns 405, because this
 * server has no server-initiated messages. The JSON-RPC subset is initialize,
 * ping, tools/list and tools/call; notifications get a 202 with no body.
 */
public class McpRoute extends RouteHandler {

    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final Set<String> KNOWN_PROTOCOLS = new HashSet<String>(Arrays.asList(
            "2024-11-05", "2025-03-26", "2025-06-18"
    ));

    private final Gson pretty = new GsonBuilder().setPrettyPrinting().create();

    public McpRoute(KodaDash plugin) {
        super(plugin);
    }

    private McpSettings settings() {
        return plugin.getMcpSettings();
    }

    private McpTools tools() {
        return plugin.getMcpTools();
    }

    private McpAudit audit() {
        return plugin.getMcpAudit();
    }

    /**
     * when MCP is off, the request falls through to handlePost/Get which answer
     * 404 without leaking whether a token exists.
     */
    @Override
    protected boolean authenticate(HttpExchange exchange) {
        McpSettings settings = settings();
        if (!settings.enabled() || settings.token().isEmpty()) return true;
        return plugin.getAuthManager().authenticateMcp(exchange, settings.token());
    }

    private boolean disabled() {
        McpSettings settings = settings();
        return !settings.enabled() || settings.token().isEmpty();
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        methodNotAllowed(exchange);
    }

    @Override
    protected void handleDelete(HttpExchange exchange) throws IOException {
        methodNotAllowed(exchange);
    }

    @Override
    protected void handlePut(HttpExchange exchange) throws IOException {
        methodNotAllowed(exchange);
    }

    private void methodNotAllowed(HttpExchange exchange) throws IOException {
        if (disabled()) {
            sendError(exchange, 404, "Not found");
            return;
        }
        exchange.getResponseHeaders().set("Allow", "POST");
        exchange.getResponseHeaders().set("MCP-Protocol-Version", DEFAULT_PROTOCOL);
        sendError(exchange, 405, "Method Not Allowed");
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        if (disabled()) {
            sendError(exchange, 404, "Not found");
            return;
        }
        if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Not found");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && !contentType.toLowerCase().contains("application/json")) {
            sendError(exchange, 415, "Content-Type must be application/json");
            return;
        }

        String body = readBody(exchange);
        if (body == null) {
            sendError(exchange, 413, "Request body too large");
            return;
        }
        if (body.trim().isEmpty()) {
            sendRpc(exchange, rpcError(null, -32700, "Parse error: empty body"));
            return;
        }

        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(body);
        } catch (Exception e) {
            sendRpc(exchange, rpcError(null, -32700, "Parse error"));
            return;
        }

        if (parsed == null) {
            sendRpc(exchange, rpcError(null, -32700, "Parse error"));
            return;
        }

        if (parsed.isJsonArray()) {
            handleBatch(exchange, parsed.getAsJsonArray());
            return;
        }
        if (!parsed.isJsonObject()) {
            sendRpc(exchange, rpcError(null, -32600, "Invalid Request"));
            return;
        }

        JsonObject response = handleRequest(exchange, parsed.getAsJsonObject());
        if (response == null) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        sendRpc(exchange, response);
    }

    private void handleBatch(HttpExchange exchange, JsonArray batch) throws IOException {
        if (batch.size() == 0 || batch.size() > 20) {
            sendRpc(exchange, rpcError(null, -32600, "Invalid batch size"));
            return;
        }
        JsonArray responses = new JsonArray();
        for (int i = 0; i < batch.size(); i++) {
            JsonElement entry = batch.get(i);
            if (entry == null || !entry.isJsonObject()) {
                responses.add(rpcError(null, -32600, "Invalid Request"));
                continue;
            }
            JsonObject response = handleRequest(exchange, entry.getAsJsonObject());
            if (response != null) responses.add(response);
        }
        if (responses.size() == 0) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        sendRpc(exchange, responses);
    }

    /**
     * @return the JSON-RPC response, or null for a notification.
     */
    private JsonObject handleRequest(HttpExchange exchange, JsonObject request) {
        JsonElement id = request.has("id") ? request.get("id") : null;
        boolean notification = id == null;

        if (!request.has("method") || request.get("method").isJsonNull()
                || !request.get("method").isJsonPrimitive()) {
            return rpcError(id, -32600, "Invalid Request: missing method");
        }
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();

        if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) {
            return notification ? null : rpcResult(id, new JsonObject());
        }
        if ("ping".equals(method)) {
            return notification ? null : rpcResult(id, new JsonObject());
        }
        if ("initialize".equals(method)) {
            return notification ? null : rpcResult(id, initialize(params));
        }
        if ("tools/list".equals(method)) {
            JsonObject result = new JsonObject();
            result.add("tools", tools().definitions());
            return notification ? null : rpcResult(id, result);
        }
        if ("tools/call".equals(method)) {
            return notification ? null : toolsCall(exchange, id, params);
        }
        return rpcError(id, -32601, "Method not found: " + method);
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion")
                ? params.get("protocolVersion").getAsString() : DEFAULT_PROTOCOL;
        String protocol = KNOWN_PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL;

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocol);

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        capabilities.add("tools", toolsCapability);
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "KodaDash");
        serverInfo.addProperty("version", plugin.getDescription().getVersion());
        result.add("serverInfo", serverInfo);

        result.addProperty("instructions", plugin.getMcpInstructions().build());
        return result;
    }

    private JsonObject toolsCall(HttpExchange exchange, JsonElement id, JsonObject params) {
        if (!params.has("name") || params.get("name").isJsonNull()) {
            return rpcError(id, -32602, "Invalid params: missing tool name");
        }
        String name = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        long started = System.currentTimeMillis();
        McpTools.ToolResult result = tools().call(name, arguments);
        long duration = System.currentTimeMillis() - started;

        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        String error = result.error && result.json.has("error")
                ? result.json.get("error").getAsString() : null;
        audit().log(name, arguments, !result.error, error, duration, remote);
        if (tools().isWriteTool(name)) {
            plugin.getLogger().info("MCP " + name + " from " + remote + ": "
                    + (result.error ? "error" : "ok") + " (" + duration + " ms)");
        }

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", pretty.toJson(result.json));
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject response = new JsonObject();
        response.add("content", contentArray);
        response.addProperty("isError", result.error);
        return rpcResult(id, response);
    }

    private void sendRpc(HttpExchange exchange, JsonObject response) throws IOException {
        exchange.getResponseHeaders().set("MCP-Protocol-Version", DEFAULT_PROTOCOL);
        sendJson(exchange, 200, response);
    }

    private void sendRpc(HttpExchange exchange, JsonArray responses) throws IOException {
        exchange.getResponseHeaders().set("MCP-Protocol-Version", DEFAULT_PROTOCOL);
        sendJson(exchange, 200, responses);
    }

    private static JsonObject rpcResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result);
        return response;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        return response;
    }
}
