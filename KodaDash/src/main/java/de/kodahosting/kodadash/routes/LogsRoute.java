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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * hands out the server log: a tail for the log tools in the dashboard and a download.
 *
 * endpoints:
 *   GET /api/logs?tail=N   - last N lines of logs/latest.log (default 500, max 5000)
 *   GET /api/logs/download - raw logs/latest.log as attachment
 */
public class LogsRoute extends RouteHandler {
    private static final String DEFAULT_LOG = "logs/latest.log";

    public LogsRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/download")) {
            handleDownload(exchange);
            return;
        }

        int tail = readIntParam(exchange, "tail", 500);
        if (tail < 1) tail = 500;
        if (tail > 5000) tail = 5000;

        File logFile;
        try {
            logFile = plugin.getFileManager().resolve(DEFAULT_LOG);
        } catch (SecurityException e) {
            sendError(exchange, 403, "Access denied");
            return;
        }

        JsonObject response = new JsonObject();
        if (logFile == null || !logFile.exists() || !logFile.isFile()) {
            response.addProperty("exists", false);
            response.add("lines", new JsonArray());
            sendJson(exchange, 200, response);
            return;
        }

        response.addProperty("exists", true);
        response.addProperty("size", logFile.length());
        response.addProperty("lastModified", logFile.lastModified());
        response.add("lines", gson.toJsonTree(tailLines(logFile, tail)));
        sendJson(exchange, 200, response);
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        File logFile;
        try {
            logFile = plugin.getFileManager().resolve(DEFAULT_LOG);
        } catch (SecurityException e) {
            sendError(exchange, 403, "Access denied");
            return;
        }
        if (logFile == null || !logFile.exists() || !logFile.isFile()) {
            sendError(exchange, 404, "Log file not found");
            return;
        }
        if (logFile.length() > 200L * 1024 * 1024) {
            sendError(exchange, 413, "Log file too large to download");
            return;
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(logFile.toPath());
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"latest.log\"");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** the last {@code count} lines of a file that may be huge, without loading all of it. */
    private java.util.List<String> tailLines(File file, int count) {
        Deque<String> lines = new ArrayDeque<>(count);
        final int MAX_BYTES = 4 * 1024 * 1024;
        long start = Math.max(0L, file.length() - MAX_BYTES);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            String line;
            while ((line = raf.readLine()) != null) {
                // readLine() decodes as ISO-8859-1, so the bytes get decoded a second time as UTF-8
                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                if (lines.size() == count) lines.pollFirst();
                lines.addLast(decoded);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Log tail failed: " + e.getMessage());
        }
        return new java.util.ArrayList<>(lines);
    }

    private int readIntParam(HttpExchange exchange, String name, int fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return fallback;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
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
