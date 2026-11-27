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
import java.net.InetAddress;
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
                } else if (path.endsWith("/icon")) {
                    handleIcon(exchange);
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
     * server-side proxy for the modrinth plugin search. it runs on the server so the browser
     * never runs into CORS, and so it still works when the dashboard is reached through the
     * tunnel and has no direct internet.
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
            if (!isAllowedRemoteUrl(url)) {
                sendError(exchange, 400, "Blocked Modrinth URL");
                return;
            }

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
            pJson.addProperty("hasIcon", jarIconBytes(p.getName()) != null);

            pluginsArray.add(pJson);
        }

        JsonObject response = new JsonObject();
        response.add("plugins", pluginsArray);
        sendJson(exchange, 200, response);
    }

    /** hands out the icon a plugin ships in its own jar. results are cached per plugin name. */
    private void handleIcon(HttpExchange exchange) throws IOException {
        String name = queryParam(exchange, "plugin");
        if (name == null || name.trim().isEmpty()) {
            sendError(exchange, 400, "Missing 'plugin' parameter");
            return;
        }
        byte[] icon = iconBytes(name.trim());
        if (icon == null) {
            sendError(exchange, 404, "No icon for this plugin");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", iconMime(icon));
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.sendResponseHeaders(200, icon.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(icon);
        }
    }

    private static final java.util.Map<String, byte[]> iconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Boolean> iconMisses = new java.util.concurrent.ConcurrentHashMap<>();

    private byte[] jarIconBytes(String pluginName) {
        if (iconCache.containsKey(pluginName)) return iconCache.get(pluginName);
        if (iconMisses.containsKey(pluginName)) return null;

        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null) {
            iconMisses.put(pluginName, Boolean.TRUE);
            return null;
        }

        // Every plugin stores its icon differently: root icon.png, assets/<name>/icon.png,
        // a logo next to the main class, webp/jpeg, or only a pack.png. Check the known
        // names first, then scan the jar for a raster image called icon/logo/pack and
        // prefer the shallowest path. That covers nearly every plugin without guessing.
        String lowerName = pluginName.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        String[] candidates = {
                "icon.png", "assets/icon.png", "logo.png", "icon.jpg", "icon.jpeg",
                "icon.webp", "assets/logo.png", "pack.png", "plugin-icon.png",
                "assets/" + lowerName + "/icon.png", "assets/" + lowerName + "/logo.png",
                lowerName + ".png", "assets/" + lowerName + ".png"
        };
        try {
            java.io.File jar = new java.io.File(
                    target.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jar.isFile()) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
                    for (String candidate : candidates) {
                        java.util.zip.ZipEntry entry = zip.getEntry(candidate);
                        if (entry == null) continue;
                        byte[] bytes = readIconEntry(zip, entry);
                        if (bytes != null) {
                            iconCache.put(pluginName, bytes);
                            return bytes;
                        }
                    }

                    java.util.List<java.util.zip.ZipEntry> images = new java.util.ArrayList<>();
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String path = entry.getName().replace('\\', '/');
                        String lower = path.toLowerCase(java.util.Locale.ROOT);
                        if (lower.startsWith("meta-inf/")) continue;
                        String base = lower.substring(lower.lastIndexOf('/') + 1);
                        if (!(base.equals("icon.png") || base.equals("icon.jpg")
                                || base.equals("icon.jpeg") || base.equals("icon.webp")
                                || base.equals("logo.png") || base.equals("logo.jpg")
                                || base.equals("logo.jpeg") || base.equals("logo.webp")
                                || base.equals("pack.png"))) continue;
                        if (entry.getSize() > 512 * 1024) continue;
                        images.add(entry);
                    }
                    images.sort((a, b) -> {
                        String na = a.getName().replace('\\', '/');
                        String nb = b.getName().replace('\\', '/');
                        int da = na.length() - na.replace("/", "").length();
                        int db = nb.length() - nb.replace("/", "").length();
                        if (da != db) return Integer.compare(da, db);
                        String ba = na.substring(na.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
                        String bb = nb.substring(nb.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
                        boolean ia = ba.startsWith("icon");
                        boolean ib = bb.startsWith("icon");
                        if (ia != ib) return ia ? -1 : 1;
                        return Integer.compare(na.length(), nb.length());
                    });
                    for (java.util.zip.ZipEntry entry : images) {
                        byte[] bytes = readIconEntry(zip, entry);
                        if (bytes != null) {
                            iconCache.put(pluginName, bytes);
                            return bytes;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        iconMisses.put(pluginName, Boolean.TRUE);
        return null;
    }

    private byte[] readIconEntry(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry) {
        if (entry.getSize() > 512 * 1024) return null;
        try (java.io.InputStream in = zip.getInputStream(entry);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < 512 * 1024) {
                out.write(buffer, 0, read);
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String iconMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
    }

    /** jar icon first; when the jar has none, ask Modrinth for the project icon. */
    private byte[] iconBytes(String pluginName) {
        byte[] local = jarIconBytes(pluginName);
        return local != null ? local : modrinthIconBytes(pluginName);
    }

    private static final java.util.Map<String, byte[]> modrinthIconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> modrinthIconMisses = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MODRINTH_RETRY_MS = 10 * 60 * 1000L;

    /**
     * Most Bukkit plugins ship no image at all. The app solves that with a Modrinth
     * search by filename; do the same here, server-side, so the browser never hits
     * CORS and the lookup works through the tunnel. Results are cached per plugin.
     */
    private byte[] modrinthIconBytes(String pluginName) {
        String key = pluginName.toLowerCase(java.util.Locale.ROOT);
        byte[] cached = modrinthIconCache.get(key);
        if (cached != null) return cached;
        Long missedAt = modrinthIconMisses.get(key);
        if (missedAt != null && System.currentTimeMillis() - missedAt < MODRINTH_RETRY_MS) {
            return null;
        }

        try {
            String query = java.net.URLEncoder.encode(pluginName, "UTF-8");
            String facets = java.net.URLEncoder.encode("[[\"project_type:plugin\"]]", "UTF-8");
            String searchUrl = "https://api.modrinth.com/v2/search?query=" + query
                    + "&facets=" + facets + "&limit=5";
            byte[] searchBytes = downloadRemote(searchUrl, 512 * 1024);
            if (searchBytes == null) {
                modrinthIconMisses.put(key, System.currentTimeMillis());
                return null;
            }

            JsonObject search = new JsonParser()
                    .parse(new String(searchBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray hits = search.has("hits") ? search.getAsJsonArray("hits") : new JsonArray();
            String wanted = normalizedName(pluginName);
            String exact = null;
            String first = null;
            for (int i = 0; i < hits.size(); i++) {
                JsonObject hit = hits.get(i).getAsJsonObject();
                String iconUrl = getString(hit, "icon_url");
                if (iconUrl.isEmpty() || !isAllowedRemoteUrl(iconUrl)) continue;
                if (first == null) first = iconUrl;
                String title = normalizedName(getString(hit, "title"));
                String slug = normalizedName(getString(hit, "slug"));
                if (title.equals(wanted) || slug.equals(wanted)) {
                    exact = iconUrl;
                    break;
                }
            }

            String chosen = exact != null ? exact : first;
            if (chosen == null) {
                modrinthIconMisses.put(key, System.currentTimeMillis());
                return null;
            }
            byte[] icon = downloadRemote(chosen, 512 * 1024);
            if (icon == null) {
                modrinthIconMisses.put(key, System.currentTimeMillis());
                return null;
            }
            modrinthIconCache.put(key, icon);
            modrinthIconMisses.remove(key);
            return icon;
        } catch (Exception e) {
            modrinthIconMisses.put(key, System.currentTimeMillis());
            return null;
        }
    }

    private String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** follows up to three redirects, but every hop has to be a public http(s) host. */
    private byte[] downloadRemote(String value, int maxBytes) {
        String current = value;
        for (int hop = 0; hop < 4; hop++) {
            if (!isAllowedRemoteUrl(current)) return null;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "KodaDash/1.0 (KodaHosting)");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) return null;
                    current = new URL(url, location).toString();
                    continue;
                }
                if (code != 200) return null;
                try (InputStream in = conn.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        if (out.size() > maxBytes) return null;
                    }
                    return out.size() > 0 ? out.toByteArray() : null;
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** only public http(s) destinations: no loopback, private, link-local or reserved hosts. */
    private boolean isAllowedRemoteUrl(String value) {
        try {
            URL url = new URL(value);
            String protocol = url.getProtocol().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) return false;
            if (url.getUserInfo() != null) return false;
            String host = url.getHost();
            if (host == null || host.isEmpty()) return false;
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost")
                    || lower.endsWith(".local") || lower.endsWith(".internal")
                    || lower.endsWith(".lan") || lower.endsWith(".home")) return false;

            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return false;
                byte[] bytes = address.getAddress();
                if (bytes.length == 4) {
                    int first = bytes[0] & 0xFF;
                    int second = bytes[1] & 0xFF;
                    if (first == 0 || first == 10 || first == 127
                            || (first == 169 && second == 254)
                            || (first == 172 && second >= 16 && second <= 31)
                            || (first == 192 && second == 168)
                            || (first == 100 && second >= 64 && second <= 127)
                            || (first == 198 && (second == 18 || second == 19))
                            || first >= 224) return false;
                } else if (bytes.length == 16) {
                    int first = bytes[0] & 0xFF;
                    int second = bytes[1] & 0xFF;
                    if ((first & 0xFE) == 0xFC) return false;          // unique local fc00::/7
                    if (first == 0xFE && (second & 0xC0) == 0x80) return false; // link local fe80::/10
                    if (first == 0x00) return false;                    // unspecified/reserved
                }
            }
            return true;
        } catch (Exception e) {
            return false;
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
            
            // no separators and no "..", a download must not land outside plugins/
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || !filename.endsWith(".jar")) {
                sendError(exchange, 400, "Invalid filename");
                return;
            }
            // a user-provided install URL must never reach loopback, private or reserved hosts
            if (!isAllowedRemoteUrl(urlStr)) {
                sendError(exchange, 400, "Only public http(s) download URLs are allowed");
                return;
            }

            File pluginsDir = new File(Bukkit.getServer().getWorldContainer(), "plugins");
            if (!pluginsDir.exists()) pluginsDir.mkdirs();
            
            File targetFile = new File(pluginsDir, filename);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String current = urlStr;
                try {
                    for (int hop = 0; hop < 4; hop++) {
                        if (!isAllowedRemoteUrl(current)) {
                            throw new IOException("Blocked download URL");
                        }
                        URL url = new URL(current);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestProperty("User-Agent", "KodaDash-Plugin/1.0");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(15000);
                        int code = conn.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String location = conn.getHeaderField("Location");
                            conn.disconnect();
                            if (location == null || location.isEmpty()) throw new IOException("Bad redirect");
                            current = new URL(url, location).toString();
                            continue;
                        }
                        if (code != 200) {
                            conn.disconnect();
                            throw new IOException("HTTP " + code);
                        }
                        long total = 0;
                        try (InputStream in = conn.getInputStream();
                             FileOutputStream out = new FileOutputStream(targetFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                total += bytesRead;
                                if (total > 64L * 1024 * 1024) {
                                    throw new IOException("Plugin jar is larger than 64 MB");
                                }
                                out.write(buffer, 0, bytesRead);
                            }
                        } finally {
                            conn.disconnect();
                        }
                        plugin.getLogger().info("Successfully installed plugin from Modrinth: " + filename);
                        return;
                    }
                    throw new IOException("Too many redirects");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to download plugin: " + e.getMessage());
                    if (targetFile.isFile()) targetFile.delete();
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
