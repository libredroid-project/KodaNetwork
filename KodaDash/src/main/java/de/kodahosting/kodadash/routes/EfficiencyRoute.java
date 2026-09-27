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
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.managers.EfficiencyManager;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;

/**
 * Power saving controls.
 *
 *   GET  /api/efficiency  - current state and options
 *   POST /api/efficiency  - {"enabled":true,"idleMinutes":10,"viewDistance":4,"simulationDistance":4}
 */
public class EfficiencyRoute extends RouteHandler {

    public EfficiencyRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        EfficiencyManager manager = plugin.getEfficiencyManager();
        JsonObject response = new JsonObject();
        response.addProperty("enabled", manager != null && manager.isEnabled());
        response.addProperty("throttled", manager != null && manager.isThrottled());
        response.addProperty("idleMinutes", plugin.getConfig().getInt("efficiency.idle-minutes", 10));
        response.addProperty("viewDistance", plugin.getConfig().getInt("efficiency.view-distance", 4));
        response.addProperty("simulationDistance", plugin.getConfig().getInt("efficiency.simulation-distance", 4));
        response.addProperty("players", org.bukkit.Bukkit.getOnlinePlayers().size());
        sendJson(exchange, 200, response);
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        EfficiencyManager manager = plugin.getEfficiencyManager();
        if (manager == null) {
            sendError(exchange, 500, "Efficiency manager not available");
            return;
        }

        JsonObject json = readJsonObject(exchange);
        if (json == null) {
            sendError(exchange, 400, "Malformed JSON body");
            return;
        }

        boolean enabled = json.has("enabled") ? json.get("enabled").getAsBoolean() : manager.isEnabled();
        int idleMinutes = json.has("idleMinutes") ? json.get("idleMinutes").getAsInt() : plugin.getConfig().getInt("efficiency.idle-minutes", 10);
        int viewDistance = json.has("viewDistance") ? json.get("viewDistance").getAsInt() : plugin.getConfig().getInt("efficiency.view-distance", 4);
        int simulationDistance = json.has("simulationDistance") ? json.get("simulationDistance").getAsInt() : plugin.getConfig().getInt("efficiency.simulation-distance", 4);

        manager.setEnabled(enabled);
        manager.setOptions(idleMinutes, viewDistance, simulationDistance);
        manager.reload();

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("enabled", enabled);
        sendJson(exchange, 200, response);
    }
}
