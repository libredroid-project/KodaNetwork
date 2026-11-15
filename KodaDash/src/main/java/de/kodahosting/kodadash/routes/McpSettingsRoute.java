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
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.mcp.McpSettings;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;

/**
 * the dashboard/app side of MCP: enable, read-only, capability switches, token
 * rotation and the system prompt preview.
 *
 * this route uses the normal dashboard token (and optional password). the MCP
 * endpoint itself uses the separate mcp.token.
 */
public class McpSettingsRoute extends RouteHandler {

    public McpSettingsRoute(KodaDash plugin) {
        super(plugin);
    }

    private McpSettings settings() {
        return plugin.getMcpSettings();
    }

    private JsonObject state() {
        JsonObject json = settings().toJson();
        try {
            json.addProperty("instructions", plugin.getMcpInstructions().build());
        } catch (Exception e) {
            json.addProperty("instructions", "Could not build the prompt: " + e.getMessage());
        }
        return json;
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, state());
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }
        JsonObject json;
        try {
            json = new JsonParser().parse(body).getAsJsonObject();
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON");
            return;
        }
        settings().apply(json);
        sendJson(exchange, 200, state());
    }
}
