package de.kodahosting.kodadash.routes;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;

/**
 * Live metrics and the recorded history for the dashboard charts.
 *
 *   GET /api/stats          - current TPS, RAM, players, CPU, threads, uptime
 *   GET /api/stats/history  - the rolling series (one sample every ten seconds)
 */
public class StatsRoute extends RouteHandler {

    public StatsRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!plugin.getAuthManager().authenticate(exchange)) {
                sendError(exchange, 401, "Unauthorized");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            JsonObject response = new JsonObject();
            if (path.endsWith("/history")) {
                response.add("history", plugin.getStatsManager().getHistory());
                response.addProperty("intervalSeconds", plugin.getConfig().getInt("stats-history.interval-seconds", 10));
                sendJson(exchange, 200, response);
            } else {
                response.add("stats", plugin.getStatsManager().getStats());
                response.add("history", plugin.getStatsManager().getHistory());
                sendJson(exchange, 200, response);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Stats route error: " + e.getMessage());
            try {
                sendError(exchange, 500, "Internal Server Error");
            } catch (IOException ignored) {}
        }
    }
}
