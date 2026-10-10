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

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;

/**
 * the live metrics and the recorded history behind the dashboard charts.
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
