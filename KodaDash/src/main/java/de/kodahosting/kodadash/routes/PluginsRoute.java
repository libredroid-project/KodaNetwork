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
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.File;

public class PluginsRoute extends RouteHandler {

    public PluginsRoute(KodaDash plugin) {
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

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                if (path.endsWith("/search")) {
                    handleModrinthSearch(exchange);
                } else {
                    handleGet(exchange);
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                if (path.endsWith("/enable")) {
                    handlePluginAction(exchange, true);
                } else if (path.endsWith("/disable")) {
                    handlePluginAction(exchange, false);
                } else if (path.endsWith("/install")) {
                    handlePluginInstall(exchange);
                } else {
                    sendError(exchange, 404, "Unknown plugin action");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Plugins route error: " + e.getMessage());
            try {
                sendError(exchange, 500, "Internal Server Error");
            } catch (IOException ignored) {}
        }
    }

    /**
     * Server-side proxy for the Modrinth plugin search. Runs on the server so the browser
     * does not hit CORS restrictions and works even when the dashboard is reached through
     * the tunnel without direct internet access.
     *
     * GET /api/plugins/search?query=essentials&limit=12
     */
    private void handleModrinthSearch(HttpExchange exchange) throws IOException {
        String query = queryParam(exchange, "query");
        if (query == null || query.trim().isEmpty()) {
            sendError(exchange, 400, "Missing 'query' parameter");
            return;
        }
        int limit = 12;
        String limitParam = queryParam(exchange, "limit");
        if (limitParam != null) {
            try {
                limit = Math.max(1, Math.min(24, Integer.parseInt(limitParam)));
            } catch (NumberFormatException ignored) {}
        }

        try {
            String encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8");
            String facets = java.net.URLEncoder.encode("[[\"project_type:plugin\"]]", "UTF-8");
            String url = "https://api.modrinth.com/v2/search?query=" + encodedQuery
                    + "&facets=" + facets + "&limit=" + limit;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "KodaDash/1.0 (KodaHosting)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            if (conn.getResponseCode() != 200) {
                sendError(exchange, 502, "Modrinth API returned HTTP " + conn.getResponseCode());
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            JsonObject modrinth = new JsonParser().parse(sb.toString()).getAsJsonObject();
            JsonArray hits = modrinth.has("hits") ? modrinth.getAsJsonArray("hits") : new JsonArray();

            JsonArray results = new JsonArray();
            for (int i = 0; i < hits.size(); i++) {
                JsonObject hit = hits.get(i).getAsJsonObject();
                JsonObject entry = new JsonObject();
                entry.addProperty("projectId", getString(hit, "project_id"));
                entry.addProperty("slug", getString(hit, "slug"));
                entry.addProperty("title", getString(hit, "title"));
                entry.addProperty("description", getString(hit, "description"));
                entry.addProperty("downloads", hit.has("downloads") ? hit.get("downloads").getAsLong() : 0L);
                entry.addProperty("iconUrl", getString(hit, "icon_url"));
                entry.addProperty("author", getString(hit, "author"));
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.add("results", results);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            sendError(exchange, 502, "Modrinth search failed: " + e.getMessage());
        }
    }

    private String getString(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
                try {
                    return java.net.URLDecoder.decode(pair[1], "UTF-8");
                } catch (Exception e) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        PluginManager pm = Bukkit.getPluginManager();
        Plugin[] plugins = pm.getPlugins();

        JsonArray pluginsArray = new JsonArray();
        for (Plugin p : plugins) {
            JsonObject pJson = new JsonObject();
            pJson.addProperty("name", p.getName());
            pJson.addProperty("version", p.getDescription().getVersion());
            
            String authors = String.join(", ", p.getDescription().getAuthors());
            pJson.addProperty("authors", authors);
            
            pJson.addProperty("description", p.getDescription().getDescription());
            pJson.addProperty("enabled", p.isEnabled());
            
            pluginsArray.add(pJson);
        }

        JsonObject response = new JsonObject();
        response.add("plugins", pluginsArray);
        sendJson(exchange, 200, response);
    }

    private void handlePluginAction(HttpExchange exchange, boolean enable) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }

        try {
            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            if (!json.has("plugin")) {
                sendError(exchange, 400, "Missing plugin name");
                return;
            }

            String pluginName = json.get("plugin").getAsString();

            Bukkit.getScheduler().runTask(plugin, () -> {
                Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
                if (targetPlugin != null) {
                    if (enable && !targetPlugin.isEnabled()) {
                        Bukkit.getPluginManager().enablePlugin(targetPlugin);
                    } else if (!enable && targetPlugin.isEnabled()) {
                        Bukkit.getPluginManager().disablePlugin(targetPlugin);
                    }
                }
            });

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);

        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON format");
        }
    }

    private void handlePluginInstall(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }

        try {
            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            if (!json.has("url") || !json.has("filename")) {
                sendError(exchange, 400, "Missing url or filename");
                return;
            }

            String urlStr = json.get("url").getAsString();
            String filename = json.get("filename").getAsString();
            
            // Validate filename for security
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || !filename.endsWith(".jar")) {
                sendError(exchange, 400, "Invalid filename");
                return;
            }

            File pluginsDir = new File(Bukkit.getServer().getWorldContainer(), "plugins");
            if (!pluginsDir.exists()) pluginsDir.mkdirs();
            
            File targetFile = new File(pluginsDir, filename);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "KodaDash-Plugin/1.0");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(15000);

                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    plugin.getLogger().info("Successfully installed plugin from Modrinth: " + filename);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to download plugin: " + e.getMessage());
                }
            });

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);

        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON format or Error: " + e.getMessage());
        }
    }
}
