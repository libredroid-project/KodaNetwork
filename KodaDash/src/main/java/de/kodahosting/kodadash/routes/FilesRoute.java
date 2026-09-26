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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;

/**
 * Handles file management via the API with sub-path routing.
 *
 * Endpoints:
 *   GET    /api/files/<path>          - directory listing or file content (JSON)
 *   GET    /api/files/download?path=  - raw file download (attachment)
 *   POST   /api/files/<path>          - write file {content} or {base64}
 *   POST   /api/files/rename          - {path, newName}
 *   PUT    /api/files/<path>          - create directory
 *   DELETE /api/files/<path>          - delete file or empty directory
 */
public class FilesRoute extends RouteHandler {

    public FilesRoute(KodaDash plugin) {
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

            String filePath = getPathParam(exchange, "/api/files");
            if (filePath == null) filePath = "";
            String method = exchange.getRequestMethod().toUpperCase();

            // Special sub-routes (checked before the generic path handling)
            if ("rename".equals(filePath)) {
                handleRename(exchange);
                return;
            }
            if ("download".equals(filePath)) {
                handleDownload(exchange);
                return;
            }

            switch (method) {
                case "GET": handleFileGet(exchange, filePath); break;
                case "POST": handleFilePost(exchange, filePath); break;
                case "PUT": handleFilePut(exchange, filePath); break;
                case "DELETE": handleFileDelete(exchange, filePath); break;
                default: sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (SecurityException e) {
            sendError(exchange, 403, e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().warning("Files route error: " + e.getMessage());
            try {
                sendError(exchange, 500, "Internal Server Error");
            } catch (IOException ignored) {}
        }
    }

    private void handleFileGet(HttpExchange exchange, String path) throws IOException {
        try {
            JsonObject result = plugin.getFileManager().getFileOrDirectory(path);
            if (result == null) {
                sendError(exchange, 404, "File or directory not found");
            } else {
                sendJson(exchange, 200, result);
            }
        } catch (SecurityException e) {
            sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            sendError(exchange, 500, "Failed to read: " + e.getMessage());
        }
    }

    /** Raw download: /api/files/download?path=<relative path> */
    private void handleDownload(HttpExchange exchange) throws IOException {
        String path = queryParam(exchange, "path");
        if (path == null || path.isEmpty()) {
            sendError(exchange, 400, "Missing 'path' parameter");
            return;
        }
        try {
            byte[] bytes = plugin.getFileManager().readBytes(path);
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replace("\"", "") + "\"");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (SecurityException e) {
            sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            sendError(exchange, 400, e.getMessage());
        }
    }

    /** Rename: POST /api/files/rename {path, newName} */
    private void handleRename(HttpExchange exchange) throws IOException {
        JsonObject json = readJsonObject(exchange);
        if (json == null) {
            sendError(exchange, 400, "Malformed JSON body");
            return;
        }
        if (!json.has("path") || !json.has("newName")) {
            sendError(exchange, 400, "Missing 'path' or 'newName' parameter");
            return;
        }
        try {
            String newPath = plugin.getFileManager().rename(json.get("path").getAsString(), json.get("newName").getAsString());
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("path", newPath);
            sendJson(exchange, 200, response);
        } catch (SecurityException e) {
            sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            sendError(exchange, 400, e.getMessage());
        }
    }

    private void handleFilePost(HttpExchange exchange, String path) throws IOException {
        String body = readBody(exchange);
        if (body == null) {
            sendError(exchange, 413, "Request body exceeds the configured upload limit");
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

        try {
            if (!json.has("content") && !json.has("base64")) {
                sendError(exchange, 400, "Missing 'content' or 'base64' parameter");
                return;
            }

            boolean success;
            if (json.has("base64")) {
                success = plugin.getFileManager().writeBase64File(path, json.get("base64").getAsString());
            } else {
                success = plugin.getFileManager().writeFile(path, json.get("content").getAsString());
            }

            if (success) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("path", path);
                sendJson(exchange, 200, response);
            } else {
                sendError(exchange, 500, "Failed to write file");
            }
        } catch (SecurityException e) {
            sendError(exchange, 403, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid request: " + e.getMessage());
        }
    }

    private void handleFilePut(HttpExchange exchange, String path) throws IOException {
        // PUT is used for creating directories
        if (path == null || path.trim().isEmpty()) {
            sendError(exchange, 400, "Missing directory path");
            return;
        }
        boolean success = plugin.getFileManager().createDirectory(path);
        if (success) {
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("path", path);
            sendJson(exchange, 200, response);
        } else {
            sendError(exchange, 500, "Failed to create directory");
        }
    }

    private void handleFileDelete(HttpExchange exchange, String path) throws IOException {
        boolean success = plugin.getFileManager().deleteFileOrDirectory(path);
        if (success) {
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);
        } else {
            sendError(exchange, 500, "Failed to delete. Make sure directory is empty.");
        }
    }

    /** Read a URL-decoded query parameter. */
    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
                try {
                    return URLDecoder.decode(pair[1], "UTF-8");
                } catch (Exception e) {
                    return pair[1];
                }
            }
        }
        return null;
    }
}
