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
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.util.ServerProperties;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * builds the MCP system prompt and the server_context tool result.
 *
 * rule of thumb: local data wins. the running server knows its software, version,
 * worlds, plugins and properties; the phone values come from device-info.json
 * written by the Android app. the cloud/app mirror (server-info.json) is only a
 * secondary source and is always labelled as such, because the cloud's
 * server_version has been wrong before.
 */
public final class McpInstructions {

    private static final long STALE_AFTER_MS = 10 * 60 * 1000L;

    private final KodaDash plugin;
    private final McpSettings settings;

    public McpInstructions(KodaDash plugin, McpSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public String build() {
        return render(contextJson());
    }

    /**
     * the full context as JSON, also used by the server_context tool. no
     * tokens, no rcon password, no .frpc.toml, no public IP addresses.
     */
    public JsonObject contextJson() {
        JsonObject root = new JsonObject();
        root.addProperty("kodaHosting",
                "KodaHosting runs Minecraft servers on Android phones, not on a VPS. "
                        + "The server lives on the phone's flash storage and is reachable through "
                        + "KodaHosting's frp relay and HTTPS vhost. The phone can sleep, throttle or "
                        + "hibernate, so the server may go offline. Backups, start, stop, restart and "
                        + "hibernate are owned by the KodaHosting app and its human owner.");

        JsonObject server = localServer();
        root.add("server", server);

        File dataFolder = plugin.getDataFolder();
        JsonObject device = readJson(new File(dataFolder, "device-info.json"));
        JsonObject mirror = readJson(new File(dataFolder, "server-info.json"));
        root.add("phone", phone(device));
        root.add("mirror", mirror(mirror, server));

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("console", settings.allowConsole());
        capabilities.addProperty("fileWrite", settings.allowFileWrite());
        capabilities.addProperty("settings", settings.allowSettings());
        capabilities.addProperty("plugins", settings.allowPlugins());
        capabilities.addProperty("readOnly", settings.readOnly());
        capabilities.addProperty("audit", settings.audit());
        root.add("capabilities", capabilities);

        JsonObject sources = new JsonObject();
        sources.addProperty("server", "local running server");
        sources.addProperty("phone", "app/device-info.json");
        sources.addProperty("mirror", "app/server-info.json (cloud values may be stale)");
        JsonObject stale = new JsonObject();
        stale.addProperty("deviceInfo", isStale(device));
        stale.addProperty("serverInfo", isStale(mirror));
        sources.add("stale", stale);
        root.add("sources", sources);

        root.addProperty("policy", policy());
        return root;
    }

    private JsonObject localServer() {
        JsonObject server = new JsonObject();
        server.addProperty("software", safe(Bukkit.getName(), "unknown"));
        server.addProperty("version", safe(Bukkit.getVersion(), "unknown"));
        server.addProperty("bukkitVersion", safe(Bukkit.getBukkitVersion(), "unknown"));
        server.addProperty("kodaDashVersion", safe(plugin.getDescription().getVersion(), "unknown"));

        JsonArray worlds = new JsonArray();
        try {
            List<World> worldList = new ArrayList<World>(Bukkit.getWorlds());
            for (World world : worldList) worlds.add(new com.google.gson.JsonPrimitive(world.getName()));
        } catch (Exception ignored) { }
        server.add("worlds", worlds);

        JsonArray plugins = new JsonArray();
        try {
            Plugin[] list = Bukkit.getPluginManager().getPlugins();
            if (list != null) {
                for (int i = 0; i < list.length && i < 40; i++) {
                    plugins.add(new com.google.gson.JsonPrimitive(
                            list[i].getName() + " " + list[i].getDescription().getVersion()));
                }
            }
        } catch (Exception ignored) { }
        server.add("plugins", plugins);

        try {
            server.addProperty("playersOnline", Bukkit.getOnlinePlayers().size());
            server.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        } catch (Exception ignored) { }

        try {
            server.addProperty("tps", plugin.getStatsManager().getTps());
            server.addProperty("ramUsedMb", plugin.getStatsManager().getUsedRam());
            server.addProperty("ramMaxMb", plugin.getStatsManager().getMaxRam());
            server.addProperty("uptimeMs", plugin.getStatsManager().getUptime());
        } catch (Exception ignored) { }

        JsonObject properties = new JsonObject();
        try {
            JsonObject all = ServerProperties.read();
            copy(all, properties, "server-port");
            copy(all, properties, "motd");
            copy(all, properties, "max-players");
            copy(all, properties, "online-mode");
            copy(all, properties, "gamemode");
            copy(all, properties, "difficulty");
            copy(all, properties, "level-name");
            copy(all, properties, "view-distance");
            copy(all, properties, "simulation-distance");
        } catch (Exception e) {
            properties.addProperty("error", "server.properties could not be read");
        }
        server.add("properties", properties);
        return server;
    }

    private JsonObject phone(JsonObject device) {
        JsonObject phone = new JsonObject();
        if (device == null) {
            phone.addProperty("available", false);
            phone.addProperty("reason", "device-info.json has not been written by the KodaHosting app yet");
            return phone;
        }
        phone.addProperty("available", true);
        phone.addProperty("manufacturer", str(device, "manufacturer", ""));
        phone.addProperty("model", str(device, "model", ""));
        phone.addProperty("brand", str(device, "brand", ""));
        phone.addProperty("android", str(device, "android", ""));
        phone.addProperty("sdk", num(device, "sdk", -1));
        phone.addProperty("cpuCores", num(device, "cpuCores", -1));
        phone.addProperty("soc", str(device, "soc", ""));
        phone.addProperty("totalRamMb", num(device, "totalRamMb", -1));
        phone.addProperty("freeRamMb", num(device, "freeRamMb", -1));
        phone.addProperty("batteryPct", num(device, "batteryPct", -1));
        phone.addProperty("charging", bool(device, "charging", false));
        phone.addProperty("thermalStatus", num(device, "thermalStatus", -1));
        phone.addProperty("storageTotalGb", dbl(device, "storageTotalGb", -1));
        phone.addProperty("storageFreeGb", dbl(device, "storageFreeGb", -1));
        phone.addProperty("updatedAt", num(device, "updatedAt", 0));
        phone.addProperty("stale", isStale(device));
        return phone;
    }

    private JsonObject mirror(JsonObject mirror, JsonObject server) {
        JsonObject out = new JsonObject();
        if (mirror == null) {
            out.addProperty("available", false);
            out.addProperty("reason", "server-info.json has not been written by the KodaHosting app yet");
            return out;
        }
        out.addProperty("available", true);
        out.addProperty("host", str(mirror, "host", ""));
        out.addProperty("baseDomain", str(mirror, "baseDomain", ""));
        out.addProperty("joinAddress", str(mirror, "joinAddress", ""));
        out.addProperty("kodadashPort", num(mirror, "kodadashPort", 0));
        out.addProperty("kodadashHttps", bool(mirror, "kodadashHttps", false));
        out.addProperty("appVersion", str(mirror, "appVersion", ""));
        out.addProperty("cloudServerVersion", str(mirror, "cloudServerVersion", ""));
        out.addProperty("appState", str(mirror, "appState", ""));
        out.addProperty("lastOnline", str(mirror, "lastOnline", ""));
        out.addProperty("cloudFetchedAt", str(mirror, "cloudFetchedAt", ""));
        out.addProperty("updatedAt", num(mirror, "updatedAt", 0));
        out.addProperty("stale", isStale(mirror));

        String cloudVersion = str(mirror, "cloudServerVersion", "");
        if (!cloudVersion.isEmpty()) {
            String localVersion = str(server, "bukkitVersion", "") + " / " + str(server, "version", "");
            if (!localVersion.contains(cloudVersion)) {
                out.addProperty("cloudVersionConflictsWithLocal", true);
            }
        }
        return out;
    }

    private String render(JsonObject ctx) {
        JsonObject server = ctx.getAsJsonObject("server");
        JsonObject props = server.has("properties") ? server.getAsJsonObject("properties") : new JsonObject();
        JsonObject phone = ctx.getAsJsonObject("phone");
        JsonObject mirror = ctx.getAsJsonObject("mirror");
        JsonObject caps = ctx.getAsJsonObject("capabilities");
        JsonObject sources = ctx.getAsJsonObject("sources");
        JsonObject stale = sources.has("stale") ? sources.getAsJsonObject("stale") : new JsonObject();

        StringBuilder b = new StringBuilder();
        b.append("You are connected to KodaDash, the control panel of a Minecraft server that\n");
        b.append("runs on an Android phone through the KodaHosting app.\n\n");

        b.append("WHAT KODAHOSTING IS\n");
        b.append(str(ctx, "kodaHosting", "")).append("\n\n");

        b.append("THIS SERVER (local, authoritative)\n");
        b.append("Software: ").append(str(server, "software", "unknown"))
                .append(" ").append(str(server, "version", "unknown")).append("\n");
        b.append("Bukkit version: ").append(str(server, "bukkitVersion", "unknown")).append("\n");
        b.append("Server port: ").append(str(props, "server-port", "?"));
        b.append(", MOTD: ").append(str(props, "motd", "?")).append("\n");
        b.append("Max players: ").append(str(props, "max-players", "?"));
        b.append(", online-mode: ").append(str(props, "online-mode", "?")).append("\n");
        b.append("Gamemode: ").append(str(props, "gamemode", "?"));
        b.append(", difficulty: ").append(str(props, "difficulty", "?"));
        b.append(", world: ").append(str(props, "level-name", "?")).append("\n");
        b.append("View distance: ").append(str(props, "view-distance", "?"));
        b.append(", simulation distance: ").append(str(props, "simulation-distance", "?")).append("\n");
        b.append("Worlds: ").append(join(server.getAsJsonArray("worlds"), ", ")).append("\n");
        b.append("Plugins: ").append(truncate(join(server.getAsJsonArray("plugins"), "; "), 900)).append("\n");
        b.append("KodaDash: ").append(str(server, "kodaDashVersion", "unknown"));
        b.append("; MCP capabilities: console=").append(caps.get("console").getAsBoolean());
        b.append(", file-write=").append(caps.get("fileWrite").getAsBoolean());
        b.append(", settings=").append(caps.get("settings").getAsBoolean());
        b.append(", plugins=").append(caps.get("plugins").getAsBoolean());
        b.append(", read-only=").append(caps.get("readOnly").getAsBoolean());
        b.append(", audit=").append(caps.get("audit").getAsBoolean()).append("\n");
        b.append("Current: ").append(num(server, "playersOnline", -1)).append(" players online, TPS ")
                .append(server.has("tps") ? server.get("tps").getAsString() : "?").append("\n\n");

        b.append("CLOUD/APP MIRROR (secondary, may be stale; local wins)\n");
        if (mirror.get("available").getAsBoolean()) {
            b.append("Public host: ").append(str(mirror, "joinAddress", "unknown")).append(" (mirror)\n");
            b.append("KodaHosting app: ").append(str(mirror, "appVersion", "unknown")).append(" (mirror)\n");
            String cloudVersion = str(mirror, "cloudServerVersion", "");
            if (!cloudVersion.isEmpty()) {
                b.append("Cloud server_version: ").append(cloudVersion)
                        .append(" (mirror; may be wrong, do not trust this)\n");
            }
            String cloudState = str(mirror, "appState", "");
            if (!cloudState.isEmpty()) {
                b.append("Cloud app state: ").append(cloudState).append(" (mirror; may be stale)\n");
            }
            String lastOnline = str(mirror, "lastOnline", "");
            if (!lastOnline.isEmpty()) {
                b.append("Cloud last seen: ").append(lastOnline).append(" (mirror)\n");
            }
            b.append("Mirror updated: ").append(ago(num(mirror, "updatedAt", 0))).append("\n");
            if (bool(mirror, "cloudVersionConflictsWithLocal", false)) {
                b.append("Note: the cloud version differs from the local server version. Trust the local version above.\n");
            }
            if (bool(stale, "serverInfo", false)) {
                b.append("The mirror is older than 10 minutes; treat it as history, not as live state.\n");
            }
        } else {
            b.append("Unavailable: ").append(str(mirror, "reason", "no server-info.json")).append("\n");
        }
        b.append("\n");

        b.append("THIS PHONE (local device-info.json)\n");
        if (phone.get("available").getAsBoolean()) {
            String maker = str(phone, "manufacturer", "");
            String model = str(phone, "model", "");
            b.append(maker.isEmpty() ? "" : maker + " ").append(model.isEmpty() ? "unknown model" : model);
            b.append(", Android ").append(str(phone, "android", "?"))
                    .append(" (SDK ").append(num(phone, "sdk", -1)).append(")\n");
            b.append(num(phone, "cpuCores", -1)).append(" CPU cores");
            String soc = str(phone, "soc", "");
            if (!soc.isEmpty()) b.append(", ").append(soc);
            b.append(", ").append(num(phone, "totalRamMb", -1)).append(" MB RAM (")
                    .append(num(phone, "freeRamMb", -1)).append(" free)\n");
            b.append("Battery ").append(num(phone, "batteryPct", -1)).append("% (")
                    .append(bool(phone, "charging", false) ? "charging" : "discharging")
                    .append("), thermal status ").append(thermal((int) num(phone, "thermalStatus", -1))).append("\n");
            b.append("Storage ").append(dbl(phone, "storageFreeGb", -1)).append("/")
                    .append(dbl(phone, "storageTotalGb", -1)).append(" GB; device info updated ")
                    .append(ago(num(phone, "updatedAt", 0))).append("\n");
            if (bool(stale, "deviceInfo", false)) {
                b.append("The device info is older than 10 minutes; values may have changed.\n");
            }
            b.append("Do not assume desktop resources. Avoid heavy operations and warn when RAM, battery or thermal state is low.\n");
        } else {
            b.append("Unavailable: ").append(str(phone, "reason", "no device-info.json")).append("\n");
        }
        b.append("\n");

        b.append(policy());
        return b.toString();
    }

    public static String policy() {
        return "RULES\n"
                + "1. Never start, stop, restart, hibernate or shut down the server. Only the human user may do that, in the KodaHosting app or the dashboard. If a change needs a restart, say so and let the user do it.\n"
                + "2. Before deleting or overwriting files, changing server.properties, installing/disabling plugins, or running destructive console commands, explain exactly what will change and wait for explicit confirmation.\n"
                + "3. Never reveal tokens, passwords, plugin config secrets, .frpc.toml or plugins/KodaDash/config.yml contents.\n"
                + "4. Prefer read-only tools. Make the smallest change that solves the task.\n"
                + "5. If a tool returns isError, report it; do not retry blindly.\n"
                + "6. Do not change server-port, online-mode, whitelist or level-name without explaining the lockout or world risk and getting explicit confirmation.\n"
                + "7. Backups are managed by the KodaHosting app. Do not claim to have created a backup.\n"
                + "\n"
                + "The server enforces rule 1. The other rules are policy for you.";
    }

    private static String thermal(int status) {
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unknown";
        }
    }

    private static String ago(long timestamp) {
        if (timestamp <= 0) return "unknown";
        long delta = System.currentTimeMillis() - timestamp;
        if (delta < 60 * 1000L) return "just now";
        if (delta < 60 * 60 * 1000L) return (delta / (60 * 1000L)) + " min ago";
        if (delta < 24 * 60 * 60 * 1000L) return (delta / (60 * 60 * 1000L)) + " h ago";
        return (delta / (24 * 60 * 60 * 1000L)) + " d ago";
    }

    private static boolean isStale(JsonObject object) {
        if (object == null) return true;
        long updated = num(object, "updatedAt", 0);
        return updated <= 0 || System.currentTimeMillis() - updated > STALE_AFTER_MS;
    }

    private static JsonObject readJson(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement element = new JsonParser().parse(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) to.add(key, from.get(key));
    }

    private static String join(JsonArray array, String separator) {
        if (array == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) out.append(separator);
            out.append(array.get(i).getAsString());
        }
        return out.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "... (" + (value.length() - max) + " more chars)";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String str(JsonObject object, String key, String fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static long num(JsonObject object, String key, long fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsLong();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static double dbl(JsonObject object, String key, double fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsDouble();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsBoolean();
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
