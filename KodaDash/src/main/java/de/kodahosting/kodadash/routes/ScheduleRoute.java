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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.managers.ScheduleManager;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.IOException;

/**
 * Scheduled automation (restarts, stops, commands, announcements, saves).
 *
 *   GET  /api/schedule                 - all jobs with their next run
 *   POST /api/schedule                 - {"action":"save","entry":{...}}
 *   POST /api/schedule                 - {"action":"remove","id":"..."}
 *   POST /api/schedule                 - {"action":"run","id":"..."}
 */
public class ScheduleRoute extends RouteHandler {

    public ScheduleRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        ScheduleManager manager = plugin.getScheduleManager();
        JsonObject response = new JsonObject();
        response.addProperty("enabled", plugin.getConfig().getBoolean("schedule.enabled", true));
        JsonArray jobs = new JsonArray();
        if (manager != null) {
            for (ScheduleManager.Entry entry : manager.getEntries()) {
                JsonObject job = entry.toJson();
                job.addProperty("description", manager.describe(entry));
                job.addProperty("nextRunText", manager.describeNextRun(entry));
                jobs.add(job);
            }
        }
        response.add("jobs", jobs);
        sendJson(exchange, 200, response);
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        if (!plugin.getConfig().getBoolean("schedule.enabled", true)) {
            sendError(exchange, 403, "Scheduling is disabled in the configuration");
            return;
        }
        ScheduleManager manager = plugin.getScheduleManager();
        if (manager == null) {
            sendError(exchange, 500, "Scheduler not available");
            return;
        }

        JsonObject json = readJsonObject(exchange);
        if (json == null || !json.has("action")) {
            sendError(exchange, 400, "Missing 'action' parameter");
            return;
        }

        String action = json.get("action").getAsString().toLowerCase();
        JsonObject response = new JsonObject();

        if ("remove".equals(action)) {
            if (!json.has("id")) {
                sendError(exchange, 400, "Missing 'id'");
                return;
            }
            boolean removed = manager.remove(json.get("id").getAsString());
            response.addProperty("success", removed);
            sendJson(exchange, removed ? 200 : 404, response);
            return;
        }

        if ("run".equals(action)) {
            if (!json.has("id")) {
                sendError(exchange, 400, "Missing 'id'");
                return;
            }
            String result = manager.runNow(json.get("id").getAsString());
            response.addProperty("success", true);
            response.addProperty("result", result);
            sendJson(exchange, 200, response);
            return;
        }

        if (!"save".equals(action)) {
            sendError(exchange, 400, "Unknown action (use save, remove or run)");
            return;
        }

        if (!json.has("entry") || !json.get("entry").isJsonObject()) {
            sendError(exchange, 400, "Missing 'entry' object");
            return;
        }

        ScheduleManager.Entry incoming = ScheduleManager.Entry.fromJson(json.getAsJsonObject("entry"));
        if (!isValidType(incoming.type)) {
            sendError(exchange, 400, "Unknown type (use restart, stop, announce, command or save)");
            return;
        }
        if (incoming.intervalMinutes < 1) incoming.intervalMinutes = 1;
        if (incoming.intervalMinutes > 10080) incoming.intervalMinutes = 10080;
        if (!isValidTime(incoming.time)) incoming.time = "04:00";

        ScheduleManager.Entry stored = manager.upsert(incoming);
        JsonObject job = stored.toJson();
        job.addProperty("description", manager.describe(stored));
        job.addProperty("nextRunText", manager.describeNextRun(stored));
        response.addProperty("success", true);
        response.add("job", job);
        sendJson(exchange, 200, response);
    }

    private boolean isValidType(String type) {
        if (type == null) return false;
        String value = type.toLowerCase();
        return "restart".equals(value) || "stop".equals(value) || "announce".equals(value)
                || "command".equals(value) || "save".equals(value);
    }

    private boolean isValidTime(String time) {
        if (time == null) return false;
        return time.trim().matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$");
    }
}
