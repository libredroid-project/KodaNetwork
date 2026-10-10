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
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * a read-only view of the backups the app made.
 *
 * the ZIPs live outside the server folder, the app owns them, so the plugin only reads the
 * index the app writes into {@code plugins/KodaDash/backups.json}. creating, restoring and
 * deleting stays in the app, and that is the point: a stolen dashboard token must not
 * destroy the only copy of a world.
 *
 *   GET /api/backups - {mode, keep, updatedAt, backups:[{name,sizeMb,time}]}
 */
public class BackupsRoute extends RouteHandler {

    public BackupsRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        File index = new File(plugin.getDataFolder(), "backups.json");
        if (!index.isFile()) {
            response.addProperty("available", false);
            sendJson(exchange, 200, response);
            return;
        }
        try (FileReader reader = new FileReader(index)) {
            JsonObject data = new JsonParser().parse(reader).getAsJsonObject();
            response.addProperty("available", true);
            response.add("mode", data.get("mode"));
            response.add("keep", data.get("keep"));
            response.add("updatedAt", data.get("updatedAt"));
            response.add("backups", data.has("backups") ? data.get("backups") : new com.google.gson.JsonArray());
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read backups.json: " + e.getMessage());
            sendError(exchange, 500, "Could not read the backup list");
        }
    }
}
