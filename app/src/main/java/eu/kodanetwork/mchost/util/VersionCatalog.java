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
package eu.kodanetwork.mchost.util;

import eu.kodanetwork.mchost.model.ServerInstance;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * the minecraft versions per server software, for the pickers that need a list.
 *
 * paper, folia, purpur and fabric come from their apis, the rest use the same fixed
 * lists the setup wizard shows. all of them newest first, releases only, because
 * nobody hosts a world on a release candidate.
 */
public final class VersionCatalog {

    /** what the setup wizard offers for the software that has no "list all versions" api. */
    public static final String[] VANILLA_VERSIONS = {"1.21.4", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1",
            "1.19.4", "1.18.2", "1.17.1", "1.16.5", "1.15.2", "1.14.4", "1.13.2", "1.12.2", "1.11.2", "1.10.2",
            "1.9.4", "1.8.9"};
    public static final String[] FABRIC_VERSIONS = {"1.21.4", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1",
            "1.19.4", "1.18.2", "1.17.1", "1.16.5", "1.15.2", "1.14.4", "1.8.9"};
    public static final String[] FORGE_VERSIONS = {"1.21.1", "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.15.2",
            "1.14.4", "1.13.2", "1.12.2", "1.11.2", "1.10.2", "1.9.4", "1.8.9"};
    public static final String[] NEOFORGE_VERSIONS = {"1.21.4", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1"};
    public static final String[] VELOCITY_VERSIONS = {"3.4.0", "3.3.0", "3.2.0", "3.1.2", "3.1.1", "3.1.0"};

    private VersionCatalog() {
    }

    /**
     * can chunky, the thing behind pre-generation, run on this server at all?
     *
     * the answers come from the chunky project on modrinth (checked october 2026): the
     * bukkit family from 1.13.2, folia from 1.18, fabric from 1.16, forge from 1.16.5 and
     * neoforge from 1.20.2. vanilla and the proxy have no plugin support at all, and
     * purpur counts as paper because that is the build it downloads.
     */
    public static boolean chunkySupports(ServerInstance.Type type, String version) {
        if (type == null) return false;
        int[] min;
        switch (type) {
            case PAPER:
            case PURPUR:      min = new int[]{1, 13, 2}; break;
            case VELOCITY:    return false;   // a proxy hosts no world
            case FOLIA:       min = new int[]{1, 18, 0}; break;
            case FABRIC:      min = new int[]{1, 16, 0}; break;
            case FORGE:       min = new int[]{1, 16, 5}; break;
            case NEOFORGE:    min = new int[]{1, 20, 2}; break;
            case VANILLA:
            case PUMPKIN:
            default:          return false;
        }
        return atLeast(version, min);
    }

    /** true when the minecraft version is at least the given one, year scheme included. */
    private static boolean atLeast(String version, int[] min) {
        if (version == null) return false;
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            if (major > 1) return true;                 // 26.x and newer are always fine
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major != min[0]) return major > min[0];
            if (minor != min[1]) return minor > min[1];
            return patch >= min[2];
        } catch (Exception e) {
            return false;
        }
    }

    /** the software a server can be, in the order the pickers show them. */
    public static ServerInstance.Type[] types() {
        return new ServerInstance.Type[]{
                ServerInstance.Type.PAPER, ServerInstance.Type.PURPUR, ServerInstance.Type.FOLIA,
                ServerInstance.Type.FABRIC, ServerInstance.Type.FORGE, ServerInstance.Type.NEOFORGE,
                ServerInstance.Type.VANILLA, ServerInstance.Type.VELOCITY, ServerInstance.Type.PUMPKIN,
        };
    }

    /**
     * versions to offer for that software. blocking, so call it off the main thread.
     * falls back to the fixed list when the api cannot be reached.
     */
    public static List<String> forType(ServerInstance.Type type) {
        List<String> live = new ArrayList<>();
        switch (type) {
            case PAPER:  live = fetchPaperProject("paper"); break;
            case FOLIA:  live = fetchPaperProject("folia"); break;
            case PURPUR: live = fetchPurpur(); break;
            case FABRIC: live = fetchFabric(); break;
            default: break;
        }
        if (!live.isEmpty()) return live;
        return new ArrayList<>(Arrays.asList(staticFor(type)));
    }

    private static String[] staticFor(ServerInstance.Type type) {
        switch (type) {
            case NEOFORGE: return NEOFORGE_VERSIONS;
            case FORGE:    return FORGE_VERSIONS;
            case VELOCITY: return VELOCITY_VERSIONS;
            case FABRIC:   return FABRIC_VERSIONS;
            case PAPER:
            case PURPUR:
            case FOLIA:
            case VANILLA:  return VANILLA_VERSIONS;
            default:       return VANILLA_VERSIONS;
        }
    }

    private static List<String> fetchPaperProject(String project) {
        List<String> versions = new ArrayList<>();
        if ("paper".equals(project)) {
            // the shared helper already knows the v3 api and the version ordering
            versions = PaperMCDownloader.fetchPaperVersions();
            if (!versions.isEmpty()) return versions;
        }
        try {
            String json = get("https://fill.papermc.io/v3/projects/" + project);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject families = root.getAsJsonObject("versions");
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> family : families.entrySet()) {
                com.google.gson.JsonArray arr = family.getValue().getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    String v = arr.get(i).getAsString().trim();
                    if (v.isEmpty() || v.contains("-")) continue;   // releases only
                    versions.add(v);
                }
            }
            versions.sort((a, b) -> MinecraftVersion.compare(b, a));
        } catch (Exception ignored) {}
        return versions;
    }

    private static List<String> fetchPurpur() {
        List<String> res = new ArrayList<>();
        try {
            org.json.JSONObject obj = new org.json.JSONObject(get("https://api.purpurmc.org/v2/purpur"));
            org.json.JSONArray arr = obj.getJSONArray("versions");
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.getString(i).trim();
                if (!v.isEmpty()) res.add(v);
            }
            Collections.reverse(res);   // the api hands them back oldest first
            res.sort((a, b) -> MinecraftVersion.compare(b, a));
        } catch (Exception ignored) {}
        return res;
    }

    private static List<String> fetchFabric() {
        List<String> res = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(get("https://meta.fabricmc.net/v2/versions/game"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (!o.optBoolean("stable", false)) continue;
                String v = o.optString("version", "");
                if (isFabricVersion(v)) res.add(v);
            }
        } catch (Exception ignored) {}
        return res;
    }

    /** fabric runs on 1.8 and newer, and on everything with the year based scheme. */
    private static boolean isFabricVersion(String v) {
        if (v == null || v.isEmpty()) return false;
        String[] parts = v.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            if (major > 1) return true;
            if (major == 1 && parts.length >= 2) return Integer.parseInt(parts[1]) >= 8;
        } catch (NumberFormatException ignored) {}
        return false;
    }

    private static String get(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
        try (InputStream is = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
