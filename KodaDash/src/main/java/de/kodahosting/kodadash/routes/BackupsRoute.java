package de.kodahosting.kodadash.routes;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Read-only view of the backups the app created.
 *
 * The ZIPs live outside the server folder (the app owns them), so the plugin only reads the
 * index the app writes into {@code plugins/KodaDash/backups.json}. Creating, restoring and
 * deleting backups stays in the app: that keeps a stolen dashboard token from destroying the
 * only copy of a world.
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
