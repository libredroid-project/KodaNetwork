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
package de.kodahosting.kodadash.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.kodahosting.kodadash.KodaDash;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * the base class every API route extends.
 *
 * it brings CORS with a single matching origin, per-IP rate limits with real counters,
 * a body reader that stops at the configured upload limit, and the send helpers.
 */
public abstract class RouteHandler implements HttpHandler {
    protected final KodaDash plugin;
    protected final Gson gson = new Gson();

    /** ip -> {windowStartMillis, requestCount} */
    private static final ConcurrentHashMap<String, long[]> rateLimits = new ConcurrentHashMap<>();
    /** ip -> attempt counter inside the current window (auth endpoint) */
    private static final ConcurrentHashMap<String, AtomicInteger> authAttempts = new ConcurrentHashMap<>();
    private static volatile long authWindowStart = 0L;

    public RouteHandler(KodaDash plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            applyCors(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!checkRateLimit(exchange)) {
                sendError(exchange, 429, "Too many requests");
                return;
            }

            if (requireAuth()) {
                if (!plugin.getAuthManager().authenticate(exchange)) {
                    if (!countAuthAttempt(exchange)) {
                        sendError(exchange, 429, "Too many failed attempts");
                        return;
                    }
                    sendError(exchange, 401, "Unauthorized");
                    return;
                }
            }

            String method = exchange.getRequestMethod().toUpperCase();
            switch (method) {
                case "GET": handleGet(exchange); break;
                case "POST": handlePost(exchange); break;
                case "PUT": handlePut(exchange); break;
                case "DELETE": handleDelete(exchange); break;
                default: sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Route error: " + e.getMessage());
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    protected boolean requireAuth() {
        return true;
    }

    protected void handleGet(HttpExchange exchange) throws IOException { sendError(exchange, 405, "Method Not Allowed"); }
    protected void handlePost(HttpExchange exchange) throws IOException { sendError(exchange, 405, "Method Not Allowed"); }
    protected void handlePut(HttpExchange exchange) throws IOException { sendError(exchange, 405, "Method Not Allowed"); }
    protected void handleDelete(HttpExchange exchange) throws IOException { sendError(exchange, 405, "Method Not Allowed"); }

    /**
     * CORS: exactly one origin goes back (a browser rejects a comma-separated list), and
     * caches learn that the value depends on the request origin.
     */
    protected void applyCors(HttpExchange exchange) {
        String configured = plugin.getConfig().getString("cors-origins", "*");
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        String allowed = "*";

        if (configured != null && !configured.trim().isEmpty() && !"*".equals(configured.trim())) {
            for (String candidate : configured.split(",")) {
                if (requestOrigin != null && requestOrigin.equalsIgnoreCase(candidate.trim())) {
                    allowed = requestOrigin;
                    break;
                }
            }
            // no match, so nothing gets echoed and the value stays "*"
            allowed = "*";
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowed);
        exchange.getResponseHeaders().set("Vary", "Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Token, X-Dashboard-Password");
    }

    /** real per-IP counting (config: rate-limit.max-requests per minute). */
    private boolean checkRateLimit(HttpExchange exchange) {
        int max = plugin.getConfig().getInt("rate-limit.max-requests", 120);
        if (max <= 0) return true;
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        long[] entry = rateLimits.get(ip);
        if (entry == null) {
            rateLimits.put(ip, new long[]{now, 1});
            if (rateLimits.size() > 5000) {
                rateLimits.entrySet().removeIf(e -> now - e.getValue()[0] > 60000);
            }
            return true;
        }
        synchronized (entry) {
            if (now - entry[0] > 60000) {
                entry[0] = now;
                entry[1] = 1;
                return true;
            }
            entry[1]++;
            return entry[1] <= max;
        }
    }

    /** cutoff for failed auth attempts per IP (config: rate-limit.max-auth-attempts). */
    private boolean countAuthAttempt(HttpExchange exchange) {
        int max = plugin.getConfig().getInt("rate-limit.max-auth-attempts", 10);
        if (max <= 0) return true;
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        synchronized (RouteHandler.class) {
            if (now - authWindowStart > 60000) {
                authWindowStart = now;
                authAttempts.clear();
            }
            AtomicInteger counter = authAttempts.get(ip);
            if (counter == null) {
                counter = new AtomicInteger(0);
                authAttempts.put(ip, counter);
            }
            return counter.incrementAndGet() <= max;
        }
    }

    protected void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        sendJson(exchange, statusCode, error);
    }

    /**
     * reads the request body and stops at the configured upload limit. null comes back when
     * the body is too big, then the caller answers with a 413.
     */
    protected String readBody(HttpExchange exchange) throws IOException {
        int limit = plugin.getConfig().getInt("max-upload-size", 10 * 1024 * 1024);
        int bufferSize = 4096;
        char[] buffer = new char[bufferSize];
        StringBuilder out = new StringBuilder();
        try (Reader in = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            int charsRead;
            while ((charsRead = in.read(buffer, 0, buffer.length)) > 0) {
                out.append(buffer, 0, charsRead);
                if (limit > 0 && out.length() > limit) {
                    return null;
                }
            }
        }
        return out.toString();
    }

    /**
     * reads a JSON body and returns null on garbage, so a route can answer 400 instead of
     * letting a parse exception turn into a 500.
     */
    protected JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null) return null;
        if (body.trim().isEmpty()) return new JsonObject();
        try {
            return new com.google.gson.JsonParser().parse(body).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    protected String getPathParam(HttpExchange exchange, String basePath) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith(basePath)) {
            String param = path.substring(basePath.length());
            if (param.startsWith("/")) param = param.substring(1);
            return param;
        }
        return "";
    }
}
