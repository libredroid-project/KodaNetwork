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
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;

/**
 * restart and stop for the dashboard, deliberately and not through the console.
 *
 * both are on the blocked-command list, but the dashboard may ask for them directly: everyone
 * online gets a countdown and the thing can still be cancelled while it runs. the config key
 * {@code server-actions.enabled} switches all of it off.
 *
 * endpoints:
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

    /**
     * queues a restart or stop with the player countdown. the scheduler calls this too, so a
     * scheduled restart warns players just like one started from the dashboard.
     *
     * @return true when the action was queued, false when one is already pending or disabled
     */
    public boolean request(String action, int seconds) {
        if (!plugin.getConfig().getBoolean("server-actions.enabled", true)) return false;
        if (!"restart".equals(action) && !"stop".equals(action)) return false;
        if ("stop".equals(action) && !plugin.getConfig().getBoolean("server-actions.allow-stop", true)) return false;
        if (pendingAction != null) return false;
        if (seconds < 5) seconds = 5;
        startCountdown(action, seconds);
        return true;
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
