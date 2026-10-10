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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class PlayerStatsParser {
    public static String getWorldName(File serverDir) {
        File props = new File(serverDir, "server.properties");
        if (props.exists()) {
            try {
                java.util.Scanner sc = new java.util.Scanner(props);
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.startsWith("level-name=")) {
                        sc.close();
                        return line.substring("level-name=".length()).trim();
                    }
                }
                sc.close();
            } catch (Exception e) {}
        }
        return "world";
    }

    public static java.io.File getPlayerDataFile(java.io.File serverDir, String uuid) {
        String world = getWorldName(serverDir);
        java.io.File f1 = new java.io.File(serverDir, world + "/players/data/" + uuid + ".dat");
        if (f1.exists()) return f1;
        java.io.File f2 = new java.io.File(serverDir, world + "/playerdata/" + uuid + ".dat");
        return f2.exists() ? f2 : f1;
    }

    public static java.io.File getStatsFile(java.io.File serverDir, String uuid) {
        String world = getWorldName(serverDir);
        java.io.File f1 = new java.io.File(serverDir, world + "/players/stats/" + uuid + ".json");
        if (f1.exists()) return f1;
        java.io.File f2 = new java.io.File(serverDir, world + "/stats/" + uuid + ".json");
        return f2.exists() ? f2 : f1;
    }

    public static class PlayerStats {
        public long hoursPlayed = 0;
        public long deaths = 0;
        public long mobsKilled = 0;
        public long damageTaken = 0;
        public long blocksMined = 0;
    }

    public static String getUuidFromName(File serverDir, String playerName) {
        java.util.List<String> candidates = new java.util.ArrayList<>();

        // 1. usercache.json, it survives malformed entries
        File cacheFile = new File(serverDir, "usercache.json");
        if (cacheFile.exists()) {
            try {
                byte[] bytes = new byte[(int) cacheFile.length()];
                try (FileInputStream fis = new FileInputStream(cacheFile)) {
                    fis.read(bytes);
                }
                JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    String name = obj.optString("name", "").trim();
                    String uuid = obj.optString("uuid", "").trim();
                    if (!name.isEmpty() && !uuid.isEmpty() && name.equalsIgnoreCase(playerName)) {
                        candidates.add(uuid);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 2. the online UUID from mojang. offline-mode servers store the offline
        //    one, so this is only a candidate, the file check below decides
        try {
            java.net.URL url = new java.net.URL("https://api.mojang.com/users/profiles/minecraft/" + playerName);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() == 200) {
                java.io.InputStream in = conn.getInputStream();
                java.util.Scanner scanner = new java.util.Scanner(in).useDelimiter("\\A");
                String json = scanner.hasNext() ? scanner.next() : "";
                in.close();
                org.json.JSONObject obj = new org.json.JSONObject(json);
                String id = obj.getString("id");
                candidates.add(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. the offline UUID, derived from the name
        candidates.add(java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)).toString());

        // whichever candidate really has playerdata or stats files wins
        for (String uuid : candidates) {
            if (getPlayerDataFile(serverDir, uuid).exists() || getStatsFile(serverDir, uuid).exists()) {
                return uuid;
            }
        }
        return candidates.get(candidates.size() - 1);
    }


    public static java.io.File getLiveStatsFile(java.io.File serverDir, String uuid) {
        String world = getWorldName(serverDir);
        // the custom plugin folder comes first
        java.io.File pluginFile = new java.io.File(serverDir, "plugins/KodaTransfer/playerdata/stats/" + uuid + "_live.json");
        if (pluginFile.exists()) return pluginFile;
        
        java.io.File f1 = new java.io.File(serverDir, world + "/players/stats/" + uuid + "_live.json");
        if (f1.exists()) return f1;
        java.io.File f2 = new java.io.File(serverDir, world + "/stats/" + uuid + "_live.json");
        return f2;
    }

    public static PlayerStats getStats(File serverDir, String uuid) {
        PlayerStats stats = new PlayerStats();
        File normalFile = getStatsFile(serverDir, uuid);
        File liveFile = getLiveStatsFile(serverDir, uuid);

        // KodaTransfer rewrites its live file every 2s while the player is on,
        // so it is never older than the vanilla stats file and gets preferred
        // whenever it exists. comparing mtimes loses that race as soon as
        // vanilla writes its stats between two plugin ticks.
        File statsFile = normalFile;
        boolean isLiveFormat = false;

        if (liveFile.exists()) {
            statsFile = liveFile;
            isLiveFormat = true;
        }

        if (!statsFile.exists()) return stats;
        try {
            parseStatsInto(stats, statsFile, isLiveFormat);
        } catch (Exception e) {
            // the live file is rewritten every 2s, so reading it mid-write fails.
            // fall back to the vanilla file instead of showing zeros.
            if (isLiveFormat && normalFile.exists()) {
                try { parseStatsInto(stats, normalFile, false); } catch (Exception ignored) {}
            }
        }
        return stats;
    }

    private static void parseStatsInto(PlayerStats stats, File file, boolean isLiveFormat) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        String jsonStr = new String(bytes, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(jsonStr);

        if (isLiveFormat) {
            // the custom live format KodaTransfer writes every 2 seconds
            if (root.has("hoursPlayed")) stats.hoursPlayed = root.getLong("hoursPlayed");
            else if (root.has("playOneMinute")) stats.hoursPlayed = (root.getLong("playOneMinute") + 36000) / 72000; // play ticks, rounded to the hour
            if (root.has("deaths")) stats.deaths = root.getLong("deaths");
            if (root.has("mobsKilled")) stats.mobsKilled = root.getLong("mobsKilled");
            if (root.has("damageTaken")) stats.damageTaken = root.getLong("damageTaken") / 10;
            if (root.has("blocksMined")) stats.blocksMined = root.getLong("blocksMined");
            return;
        }

        if (!root.has("stats")) return;
        JSONObject statsObj = root.getJSONObject("stats");

        if (statsObj.has("minecraft:custom")) {
            JSONObject custom = statsObj.getJSONObject("minecraft:custom");
            if (custom.has("minecraft:play_time")) {
                stats.hoursPlayed = (custom.getLong("minecraft:play_time") + 36000) / 72000; // ticks to the nearest hour
            }
            if (custom.has("minecraft:deaths")) {
                stats.deaths = custom.getLong("minecraft:deaths");
            }
            if (custom.has("minecraft:mob_kills")) {
                stats.mobsKilled = custom.getLong("minecraft:mob_kills");
            }
            if (custom.has("minecraft:damage_taken")) {
                stats.damageTaken = custom.getLong("minecraft:damage_taken") / 10; // damage is stored in tenths of a heart
            }
        }

        if (statsObj.has("minecraft:mined")) {
            JSONObject mined = statsObj.getJSONObject("minecraft:mined");
            long totalMined = 0;
            java.util.Iterator<String> keys = mined.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                totalMined += mined.getLong(key);
            }
            stats.blocksMined = totalMined;
        }
    }
}
