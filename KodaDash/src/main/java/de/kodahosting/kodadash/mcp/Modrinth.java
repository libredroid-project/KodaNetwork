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
package de.kodahosting.kodadash.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kodahosting.kodadash.util.PluginInstaller;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * the two Modrinth calls the MCP tools need: search for a plugin and resolve
 * a compatible version file.
 *
 * every request is https to api.modrinth.com and passes the same host/address
 * check as a plugin download. the returned file url is verified again by
 * PluginInstaller before it is opened.
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "KodaDash-MCP/1.2.0 (https://host.kodanetwork.eu)";

    private Modrinth() {
    }

    public static final class Version {
        public String id = "";
        public String versionNumber = "";
        public String filename = "";
        public String url = "";
        public String sha512 = "";
        public String sha1 = "";
        public long size = 0L;
        public JsonArray gameVersions = new JsonArray();
        public JsonArray loaders = new JsonArray();
    }

    /** searches plugins, newest/relevance first. */
    public static JsonArray search(String query, int limit) throws IOException {
        String facets = "[[\"project_type:plugin\"]]";
        String url = API + "/search?query=" + encode(query)
                + "&limit=" + limit
                + "&index=relevance"
                + "&facets=" + encode(facets);

        JsonElement element = getJsonElement(url);
        JsonArray hits = element != null && element.isJsonObject()
                ? element.getAsJsonObject().getAsJsonArray("hits") : null;
        JsonArray out = new JsonArray();
        if (hits == null) return out;
        for (int i = 0; i < hits.size() && i < limit; i++) {
            JsonObject hit = hits.get(i).getAsJsonObject();
            JsonObject row = new JsonObject();
            copy(hit, row, "project_id");
            copy(hit, row, "slug");
            copy(hit, row, "title");
            copy(hit, row, "description");
            copy(hit, row, "author");
            copy(hit, row, "downloads");
            copy(hit, row, "icon_url");
            copyLimited(hit, row, "categories", 12);
            copyLimited(hit, row, "versions", 24);
            out.add(row);
        }
        return out;
    }

    /** resolves the newest file matching the game version and loader. */
    public static Version resolve(String project, String versionId, String gameVersion, String loader)
            throws IOException {
        String url = API + "/project/" + encode(project) + "/version";
        JsonElement element = getJsonElement(url);
        JsonArray versions = element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        if (versions == null || versions.size() == 0) {
            throw new IOException("Modrinth returned no versions for " + project);
        }

        JsonObject chosen = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject candidate = versions.get(i).getAsJsonObject();
            String candidateId = string(candidate, "id");
            if (!versionId.isEmpty() && !versionId.equals(candidateId)) continue;
            if (!gameVersion.isEmpty() && !arrayContains(candidate.getAsJsonArray("game_versions"), gameVersion)) {
                continue;
            }
            if (!loader.isEmpty() && !matchesLoader(candidate.getAsJsonArray("loaders"), loader)) {
                continue;
            }
            chosen = candidate;
            break;
        }

        if (chosen == null) {
            StringBuilder available = new StringBuilder();
            for (int i = 0; i < versions.size() && i < 6; i++) {
                JsonObject candidate = versions.get(i).getAsJsonObject();
                if (i > 0) available.append("; ");
                available.append(string(candidate, "version_number"))
                        .append(" loaders=").append(candidate.getAsJsonArray("loaders"))
                        .append(" games=").append(candidate.getAsJsonArray("game_versions"));
            }
            throw new IOException("No compatible version for " + project
                    + " (game " + gameVersion + ", loader " + loader + "). Available: " + available);
        }

        Version result = new Version();
        result.id = string(chosen, "id");
        result.versionNumber = string(chosen, "version_number");
        result.gameVersions = chosen.has("game_versions") ? chosen.getAsJsonArray("game_versions") : new JsonArray();
        result.loaders = chosen.has("loaders") ? chosen.getAsJsonArray("loaders") : new JsonArray();

        JsonArray files = chosen.getAsJsonArray("files");
        JsonObject selected = null;
        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                JsonObject file = files.get(i).getAsJsonObject();
                if (!string(file, "filename").toLowerCase().endsWith(".jar")) continue;
                boolean primary = file.has("primary") && file.get("primary").getAsBoolean();
                if (primary) {
                    selected = file;
                    break;
                }
                if (selected == null) selected = file;
            }
        }
        if (selected == null) {
            throw new IOException("Modrinth version " + result.versionNumber + " has no .jar file");
        }

        result.filename = string(selected, "filename");
        result.url = string(selected, "url");
        result.size = selected.has("size") ? selected.get("size").getAsLong() : 0L;
        if (selected.has("hashes")) {
            JsonObject hashes = selected.getAsJsonObject("hashes");
            result.sha512 = string(hashes, "sha512");
            result.sha1 = string(hashes, "sha1");
        }
        if (result.sha512.isEmpty() && result.sha1.isEmpty()) {
            throw new IOException("Modrinth version has no checksum");
        }
        return result;
    }

    private static JsonElement getJsonElement(String url) throws IOException {
        PluginInstaller.validateUrl(url);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Modrinth HTTP " + code);
            }
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean matchesLoader(JsonArray loaders, String loader) {
        if (loaders == null) return false;
        String wanted = loader.toLowerCase();
        for (int i = 0; i < loaders.size(); i++) {
            String value = loaders.get(i).getAsString().toLowerCase();
            if (value.equals(wanted)) return true;
            // Paper plugins are often tagged spigot/bukkit, Purpur runs Paper plugins.
            if (("paper".equals(wanted) || "purpur".equals(wanted))
                    && ("spigot".equals(value) || "bukkit".equals(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayContains(JsonArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).getAsString())) return true;
        }
        return false;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) to.add(key, from.get(key));
    }

    private static void copyLimited(JsonObject from, JsonObject to, String key, int max) {
        if (!from.has(key) || !from.get(key).isJsonArray()) return;
        JsonArray source = from.getAsJsonArray(key);
        JsonArray out = new JsonArray();
        for (int i = 0; i < source.size() && i < max; i++) out.add(source.get(i));
        to.add(key, out);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }
}
