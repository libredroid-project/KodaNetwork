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
import de.kodahosting.kodadash.server.RouteHandler;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;

/**
 * Deliberate server control (restart / stop) for the dashboard.
 *
 * These actions are on the blocked-command list for the console, but the dashboard may
 * trigger them explicitly: a countdown is broadcast to all players and the action can be
 * cancelled while it runs. Disabled entirely via config {@code server-actions.enabled}.
 *
 * Endpoints:
 *   GET  /api/server-action  - current pending action (or null)
 *   POST /api/server-action  - {action: "restart"|"stop"|"cancel"}
 */
public class ServerActionRoute extends RouteHandler {
    private volatile String pendingAction = null;
    private volatile int pendingSeconds = 0;
    private volatile BukkitTask pendingTask = null;

    public ServerActionRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("enabled", plugin.getConfig().getBoolean("server-actions.enabled", true));
        if (pendingAction == null) {
            response.addProperty("pending", false);
        } else {
            response.addProperty("pending", true);
            response.addProperty("action", pendingAction);
            response.addProperty("seconds", pendingSeconds);
        }
        response.addProperty("allowStop", plugin.getConfig().getBoolean("server-actions.allow-stop", true));
        sendJson(exchange, 200, response);
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        if (!plugin.getConfig().getBoolean("server-actions.enabled", true)) {
            sendError(exchange, 403, "Server actions are disabled");
            return;
        }

        JsonObject json = readJsonObject(exchange);
        if (json == null || !json.has("action")) {
            sendError(exchange, 400, "Missing 'action' parameter");
            return;
        }

        String action = json.get("action").getAsString().toLowerCase();
        JsonObject response = new JsonObject();

        if ("cancel".equals(action)) {
            boolean cancelled = cancelPending();
            response.addProperty("success", true);
            response.addProperty("cancelled", cancelled);
            sendJson(exchange, 200, response);
            return;
        }

        if (!"restart".equals(action) && !"stop".equals(action)) {
            sendError(exchange, 400, "Unknown action (use restart, stop or cancel)");
            return;
        }

        if ("stop".equals(action) && !plugin.getConfig().getBoolean("server-actions.allow-stop", true)) {
            sendError(exchange, 403, "Stopping the server is disabled (see config: server-actions.allow-stop)");
            return;
        }

        if (pendingAction != null) {
            sendError(exchange, 409, "A " + pendingAction + " is already scheduled");
            return;
        }

        int seconds = plugin.getConfig().getInt("server-actions.countdown-seconds", 30);
        if (seconds < 5) seconds = 5;
        startCountdown(action, seconds);

        response.addProperty("success", true);
        response.addProperty("action", action);
        response.addProperty("seconds", seconds);
        sendJson(exchange, 200, response);
    }

    private void startCountdown(final String action, final int seconds) {
        pendingAction = action;
        pendingSeconds = seconds;

        final String label = "restart".equals(action) ? "Restart" : "Shutdown";
        Bukkit.broadcastMessage("\u00a7c[KodaDash] \u00a7eServer " + label + " in " + seconds + " seconds!");

        pendingTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                remaining--;
                pendingSeconds = remaining;
                if (remaining <= 0) {
                    finish();
                    return;
                }
                if (remaining <= 5 || remaining % 10 == 0) {
                    Bukkit.broadcastMessage("\u00a7c[KodaDash] \u00a7eServer " + label + " in " + remaining + " seconds!");
                }
            }
        }, 20L, 20L);
    }

    private void finish() {
        String action = pendingAction;
        cancelPending();
        final String command = "stop".equals(action) ? "stop" : "restart";
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });
    }

    private boolean cancelPending() {
        boolean had = false;
        if (pendingTask != null) {
            try { pendingTask.cancel(); } catch (Exception ignored) {}
            pendingTask = null;
            had = true;
        }
        pendingAction = null;
        pendingSeconds = 0;
        if (had) {
            Bukkit.broadcastMessage("\u00a7a[KodaDash] \u00a7ePending restart cancelled.");
        }
        return had;
    }
}
