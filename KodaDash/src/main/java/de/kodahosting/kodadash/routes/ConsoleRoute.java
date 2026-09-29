package de.kodahosting.kodadash.routes;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles console reading, SSE streaming, and command execution.
 * Overrides handle() for sub-path routing (/stream, /command).
 *
 * Reconnect handling: every SSE frame carries an {@code id:} with the line index, so the
 * browser sends {@code Last-Event-ID} on reconnect. We use that (or an explicit
 * {@code ?since=} parameter) to backfill the lines the client missed - no output is lost.
 */
public class ConsoleRoute extends RouteHandler {

    public ConsoleRoute(KodaDash plugin) {
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

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (path.endsWith("/stream") && "GET".equalsIgnoreCase(method)) {
                handleStream(exchange);
            } else if (path.endsWith("/commands") && "GET".equalsIgnoreCase(method)) {
                handleCommandList(exchange);
            } else if (path.endsWith("/command") && "POST".equalsIgnoreCase(method)) {
                handleCommand(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Console route error: " + e.getMessage());
            try {
                sendError(exchange, 500, "Internal Server Error");
            } catch (IOException ignored) {}
        }
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        int since = readIntParam(exchange, "since", -1);

        Object linesData;
        if (since >= 0) {
            linesData = plugin.getConsoleManager().getLinesSince(since);
        } else {
            linesData = plugin.getConsoleManager().getRecentLines();
        }

        JsonObject response = new JsonObject();
        response.add("lines", gson.toJsonTree(linesData));
        response.addProperty("total", plugin.getConsoleManager().getTotalLines());
        response.addProperty("clients", plugin.getConsoleManager().getConnectedClients());

        sendJson(exchange, 200, response);
    }

    /**
     * SSE endpoint for real-time console streaming. Sends a backfill snapshot first, then
     * hands the socket to a dedicated writer thread inside the ConsoleManager.
     */
    private void handleStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        os.write("retry: 1000\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();

        // Resume point: browser sends Last-Event-ID after a reconnect, ?since= wins if given
        int since = readIntParam(exchange, "since", -1);
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (since < 0 && lastEventId != null) {
            try {
                since = Integer.parseInt(lastEventId.trim()) + 1;
            } catch (NumberFormatException ignored) {}
        }

        List<JsonObject> backfill = since >= 0
                ? plugin.getConsoleManager().getLinesSince(since)
                : plugin.getConsoleManager().getRecentLines();
        List<String> frames = new ArrayList<>(backfill.size());
        for (JsonObject line : backfill) {
            frames.add(plugin.getConsoleManager().buildFrame(line));
        }

        plugin.getConsoleManager().registerSseListener(os, exchange, frames);
        // The exchange stays open on purpose - the ConsoleManager writer owns the socket now.
    }

    /**
     * Execute a server command via POST.
     */
    private void handleCommand(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null) {
            sendError(exchange, 413, "Request body too large");
            return;
        }
        if (body.trim().isEmpty()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }

        JsonObject json;
        try {
            json = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON body");
            return;
        }

        if (!json.has("command") || json.get("command").isJsonNull()) {
            sendError(exchange, 400, "Missing command parameter");
            return;
        }

        String command = json.get("command").getAsString().trim();
        if (command.isEmpty()) {
            sendError(exchange, 400, "Empty command");
            return;
        }

        if (plugin.getConsoleManager().isCommandBlocked(command)) {
            sendError(exchange, 403, "This command is blocked");
            return;
        }

        boolean dispatched = plugin.getConsoleManager().executeCommand(command);

        JsonObject response = new JsonObject();
        response.addProperty("success", dispatched);
        response.addProperty("command", command);
        sendJson(exchange, dispatched ? 200 : 403, response);
    }

    /**
     * Suggestions for the console input: every command the server knows (namespaces stripped)
     * plus the online player names. Argument hints for a few common commands are included so the
     * browser can offer a second-level completion.
     */
    private void handleCommandList(HttpExchange exchange) throws IOException {
        JsonArray commands = new JsonArray();
        java.util.TreeSet<String> names = new java.util.TreeSet<>();

        try {
            Object server = Bukkit.getServer();
            Object commandMap = server.getClass().getMethod("getCommandMap").invoke(server);
            Object all = commandMap.getClass().getMethod("getCommands").invoke(commandMap);

            // Older servers return a Map<String, Command>, newer ones a Collection<Command>.
            if (all instanceof java.util.Map) {
                for (Object key : ((java.util.Map<?, ?>) all).keySet()) {
                    addCommandName(names, String.valueOf(key));
                }
            } else if (all instanceof java.util.Collection) {
                for (Object entry : (java.util.Collection<?>) all) {
                    if (entry instanceof Command) {
                        Command command = (Command) entry;
                        addCommandName(names, command.getName());
                        for (String alias : command.getAliases()) addCommandName(names, alias);
                    } else {
                        addCommandName(names, String.valueOf(entry));
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read the command map: " + t.getMessage());
        }

        for (String name : names) commands.add(new JsonPrimitive(name));

        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) players.add(new JsonPrimitive(player.getName()));

        JsonObject arguments = new JsonObject();
        addHints(arguments, "gamemode", "survival", "creative", "adventure", "spectator");
        addHints(arguments, "difficulty", "peaceful", "easy", "normal", "hard");
        addHints(arguments, "weather", "clear", "rain", "thunder");
        addHints(arguments, "time", "set", "add", "query");
        addHints(arguments, "kill", "@e", "@a");
        addHints(arguments, "defaultgamemode", "survival", "creative", "adventure", "spectator");

        JsonObject response = new JsonObject();
        response.add("commands", commands);
        response.add("players", players);
        response.add("arguments", arguments);
        sendJson(exchange, 200, response);
    }

    /** Command names arrive namespaced ("minecraft:give") - the plain name is what users type. */
    private void addCommandName(java.util.Set<String> names, String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        if (!name.isEmpty() && name.matches("[a-z0-9_-]+")) names.add(name);
    }

    private void addHints(JsonObject target, String command, String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(new JsonPrimitive(value));
        target.add(command, array);
    }

    /** Read an integer query parameter with a fallback. */
    private int readIntParam(HttpExchange exchange, String name, int fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return fallback;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1 && name.equals(pair[0])) {
                try {
                    return Integer.parseInt(pair[1]);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
