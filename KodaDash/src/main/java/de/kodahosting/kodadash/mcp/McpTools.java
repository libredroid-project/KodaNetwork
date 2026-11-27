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
import com.google.gson.JsonPrimitive;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.managers.ScheduleManager;
import de.kodahosting.kodadash.managers.FileManager;
import de.kodahosting.kodadash.util.PluginInstaller;
import de.kodahosting.kodadash.util.ServerProperties;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * the MCP tool registry and dispatcher.
 *
 * full admin except server lifecycle: the AI may run console commands, touch
 * files and change settings, but start/stop/restart/hibernate are rejected here
 * and there is no server-action tool at all.
 */
public final class McpTools {
    private static final int MAX_READ_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 200 * 1024;
    /** one files_push / files_chunk_append request: base64 inflates by 4/3 and the
     * tunnel vhost accepts 25 MB bodies, so 16 MB decoded leaves enough headroom */
    private static final int MAX_PUSH_BYTES = 16 * 1024 * 1024;
    /** hard ceiling for a file assembled with files_chunk_append */
    private static final long MAX_APPEND_TOTAL = 256L * 1024 * 1024;
    private static final Set<String> LIFECYCLE = new HashSet<String>(Arrays.asList(
            "start", "stop", "restart", "hibernate", "end", "shutdown", "halt"
    ));
    private static final Set<String> CONSOLE_BLOCKED = new HashSet<String>(Arrays.asList(
            "kodadash", "execute", "function", "schedule"
    ));
    private static final String[] MCP_BLOCKED = new String[]{
            "plugins/KodaDash/config.yml",
            "plugins/KodaDash/mcp-audit.log",
            "plugins/KodaDash/mcp-audit.log.1",
            ".frpc.toml"
    };

    private final KodaDash plugin;
    private final McpSettings settings;
    private final McpInstructions instructions;

    public McpTools(KodaDash plugin, McpSettings settings, McpInstructions instructions) {
        this.plugin = plugin;
        this.settings = settings;
        this.instructions = instructions;
    }

    public static final class ToolResult {
        public final JsonObject json;
        public final boolean error;

        public ToolResult(JsonObject json, boolean error) {
            this.json = json;
            this.error = error;
        }
    }

    private static final class ToolException extends Exception {
        ToolException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------- definitions

    public JsonArray definitions() {
        JsonArray tools = new JsonArray();
        tools.add(tool("server_context", "Server context",
                "KodaHosting, this server, the Android phone it runs on, enabled MCP capabilities and the policy. "
                        + "Local values are authoritative; the cloud/app mirror is labelled.",
                true, false, false, emptySchema()));
        tools.add(tool("mcp_status", "MCP status",
                "The current MCP mode and the tools this account may use. The token is never included.",
                true, false, false, emptySchema()));
        tools.add(tool("server_status", "Server status",
                "Live local TPS, players, RAM, uptime and software version.", true, false, false, emptySchema()));
        tools.add(tool("console_tail", "Console tail",
                "The last N lines of the server console.", true, false, false,
                schemaWith(propInt("lines", "1-500, default 100"), null)));
        tools.add(tool("players_list", "Players",
                "Online players with gamemode, health, world and op state.", true, false, false, emptySchema()));
        tools.add(tool("playtime_top", "Playtime leaderboard",
                "Top players by playtime.", true, false, false,
                schemaWith(propInt("limit", "1-100, default 20"), null)));
        tools.add(tool("stats_history", "Stats history",
                "Recent TPS, players and RAM samples.", true, false, false,
                schemaWith(propInt("samples", "1-360, default 60"), null)));
        tools.add(tool("files_list", "List files",
                "List a directory inside the server root.", true, false, false,
                schemaWith(propString("path", "Relative path, empty for the server root"), null)));
        tools.add(tool("files_read", "Read file",
                "Read a text file or binary file (base64) inside the server root.", true, false, false,
                schemaWith(propString("path", "Relative path inside the server root"), propInt("max_bytes", "1-5242880, default 262144"), "path")));
        tools.add(tool("logs_tail", "Log tail",
                "The last N lines of logs/latest.log.", true, false, false,
                schemaWith(propInt("lines", "1-2000, default 200"), null)));
        tools.add(tool("plugins_list", "Plugins",
                "Installed plugins with name, version and enabled state.", true, false, false, emptySchema()));
        tools.add(tool("modrinth_search", "Modrinth search",
                "Search Modrinth for Paper/Purpur plugins. Read-only; installs still need modrinth_install with confirm:true.",
                true, false, true,
                schemaWith(propString("query", "Search text, for example a plugin name or a feature"),
                        propInt("limit", "1-20, default 10"), "query")));
        tools.add(tool("settings_get", "Server properties",
                "The local server.properties values. rcon.password is redacted.", true, false, false, emptySchema()));
        tools.add(tool("schedule_list", "Schedules",
                "Scheduled jobs and their next run.", true, false, false, emptySchema()));
        tools.add(tool("backups_list", "Backups",
                "The backup index. Backup creation and restore stay in the KodaHosting app.", true, false, false, emptySchema()));

        if (settings.allowConsole()) {
            tools.add(tool("console_command", "Console command",
                    "Run a server console command as the console sender. Start/stop/restart/hibernate, execute, function and schedule are blocked. "
                            + "Only the human user may start, stop or restart the server.",
                    false, true, false,
                    schemaWith(propString("command", "Command without a leading slash"), propInt("wait_ms", "0-3000, default 500"), "command")));
        }
        if (settings.allowFileWrite()) {
            tools.add(tool("files_write", "Write file",
                    "Write text or base64 to a file inside the server root. Overwriting needs confirm:true.", false, true, false,
                    schemaWith(propString("path", "Relative path"), propString("content", "UTF-8 text, unless base64 is used"),
                            propString("base64", "Base64 bytes, instead of content"), propBool("confirm", "Required to overwrite an existing file"), "path")));
            tools.add(tool("files_push", "Push file (up to 16 MB)",
                    "Upload a whole file as base64 in one request, up to 16 MB decoded. Bigger files: assemble them with files_chunk_append onto a .part name and swap with files_replace_atomic. Overwriting needs confirm:true.",
                    false, true, false,
                    schemaWith(propString("path", "Relative path"), propString("base64", "Base64 bytes of the whole file"),
                            propBool("confirm", "Required to overwrite an existing file"), "path", "confirm")));
            tools.add(tool("files_chunk_append", "Append base64 chunk",
                    "Append a base64 chunk (max 16 MB decoded) to a file, creating it when missing. Assemble large jars in chunks onto a .part name, then swap with files_replace_atomic.",
                    false, true, false,
                    schemaWith(propString("path", "Relative path, a .part name while assembling"), propString("base64", "Base64 bytes of this chunk"), "path")));
            tools.add(tool("files_replace_atomic", "Atomic replace",
                    "Atomically move the finished .part file onto its final path, replacing the target in one step. confirm:true is required.",
                    false, true, false,
                    schemaWith(propString("from", "Relative path of the assembled .part file"), propString("path", "Final relative path"),
                            propBool("confirm", "Must be true"), "from", "path", "confirm")));
            tools.add(tool("files_delete", "Delete file",
                    "Delete a file or directory. confirm:true is required.", false, true, false,
                    schemaWith(propString("path", "Relative path"), propBool("recursive", "Delete a non-empty directory"),
                            propBool("confirm", "Must be true"), "path", "confirm")));
            tools.add(tool("files_rename", "Rename file",
                    "Rename a file or directory in place.", false, true, false,
                    schemaWith(propString("path", "Relative path"), propString("newName", "New plain name without separators"),
                            "path", "newName")));
            tools.add(tool("files_mkdir", "Create directory",
                    "Create a directory and missing parents.", false, false, false,
                    schemaWith(propString("path", "Relative path"), "path")));
        }
        if (settings.allowSettings()) {
            tools.add(tool("settings_update", "Update server properties",
                    "Rewrite keys in the local server.properties. A restart is required for most changes. confirm:true is required.",
                    false, true, false,
                    schemaWith(propObject("properties", "Object of key/value pairs"),
                            propBool("confirm", "Must be true"), "properties", "confirm")));
            tools.add(tool("server_setting", "Change one setting",
                    "Change one common server setting with validation: motd, max-players, difficulty, gamemode, pvp, whitelist, online-mode, view-distance, simulation-distance or spawn-protection. confirm:true is required.",
                    false, true, false,
                    schemaWith(propString("key", "Setting name"), propString("value", "New value"),
                            propBool("confirm", "Must be true"), "key", "value", "confirm")));
        }
        if (settings.allowPlugins()) {
            tools.add(tool("plugin_enable", "Enable plugin",
                    "Enable an installed plugin on the main thread.", false, false, false,
                    schemaWith(propString("plugin", "Plugin name"), "plugin")));
            tools.add(tool("plugin_disable", "Disable plugin",
                    "Disable an installed plugin. KodaDash itself cannot be disabled. confirm:true is required.",
                    false, true, false,
                    schemaWith(propString("plugin", "Plugin name"), propBool("confirm", "Must be true"), "plugin", "confirm")));
            tools.add(tool("plugin_install", "Install plugin",
                    "Download a .jar from a public https URL into plugins/. Private, loopback and reserved addresses are rejected. confirm:true is required.",
                    false, true, true,
                    schemaWith(propString("url", "Public https URL"), propString("filename", "Plain .jar filename"),
                            propBool("confirm", "Must be true"), "url", "filename", "confirm")));
            if (settings.allowModrinth()) {
                tools.add(tool("modrinth_install", "Install from Modrinth",
                        "Resolve a compatible Modrinth plugin version and install it into plugins/. The SHA-512 from Modrinth is verified. confirm:true is required.",
                        false, true, true,
                        schemaWith(propString("project", "Modrinth project id or slug"),
                                propString("version", "Optional version id; latest compatible by default"),
                                propString("game_version", "Optional Minecraft version; local version by default"),
                                propString("loader", "Optional loader: paper, purpur, spigot or bukkit"),
                                propBool("confirm", "Must be true"), "project", "confirm")));
            }
        }
        return tools;
    }

    public boolean isWriteTool(String name) {
        return "console_command".equals(name) || "files_write".equals(name)
                || "files_push".equals(name) || "files_chunk_append".equals(name)
                || "files_replace_atomic".equals(name)
                || "files_delete".equals(name) || "files_rename".equals(name)
                || "files_mkdir".equals(name) || "settings_update".equals(name)
                || "server_setting".equals(name)
                || "plugin_enable".equals(name) || "plugin_disable".equals(name)
                || "plugin_install".equals(name) || "modrinth_install".equals(name);
    }

    private boolean available(String name) {
        if ("console_command".equals(name)) return settings.allowConsole();
        if ("files_write".equals(name) || "files_delete".equals(name)
                || "files_push".equals(name) || "files_chunk_append".equals(name)
                || "files_replace_atomic".equals(name)
                || "files_rename".equals(name) || "files_mkdir".equals(name)) {
            return settings.allowFileWrite();
        }
        if ("settings_update".equals(name) || "server_setting".equals(name)) {
            return settings.allowSettings();
        }
        if ("plugin_enable".equals(name) || "plugin_disable".equals(name)
                || "plugin_install".equals(name)) {
            return settings.allowPlugins();
        }
        if ("modrinth_install".equals(name)) {
            return settings.allowPlugins() && settings.allowModrinth();
        }
        return true;
    }

    // ---------------------------------------------------------------- dispatch

    public ToolResult call(String name, JsonObject args) {
        long start = System.currentTimeMillis();
        try {
            if (!available(name)) {
                throw new ToolException("this tool is disabled by the MCP settings");
            }
            JsonObject result = dispatch(name, args == null ? new JsonObject() : args);
            return new ToolResult(result, false);
        } catch (ToolException e) {
            return new ToolResult(errorJson(e.getMessage()), true);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
            return new ToolResult(errorJson(message), true);
        }
    }

    private JsonObject dispatch(String name, JsonObject args) throws Exception {
        if ("server_context".equals(name)) return instructions.contextJson();
        if ("mcp_status".equals(name)) return mcpStatus();
        if ("server_status".equals(name)) return serverStatus();
        if ("console_tail".equals(name)) return consoleTail(args);
        if ("players_list".equals(name)) return playersList();
        if ("playtime_top".equals(name)) return playtimeTop(args);
        if ("stats_history".equals(name)) return statsHistory(args);
        if ("files_list".equals(name)) return filesList(args);
        if ("files_read".equals(name)) return filesRead(args);
        if ("logs_tail".equals(name)) return logsTail(args);
        if ("plugins_list".equals(name)) return pluginsList();
        if ("modrinth_search".equals(name)) return modrinthSearch(args);
        if ("settings_get".equals(name)) return settingsGet();
        if ("schedule_list".equals(name)) return scheduleList();
        if ("backups_list".equals(name)) return backupsList();
        if ("console_command".equals(name)) return consoleCommand(args);
        if ("files_write".equals(name)) return filesWrite(args);
        if ("files_push".equals(name)) return filesPush(args);
        if ("files_chunk_append".equals(name)) return filesChunkAppend(args);
        if ("files_replace_atomic".equals(name)) return filesReplaceAtomic(args);
        if ("files_delete".equals(name)) return filesDelete(args);
        if ("files_rename".equals(name)) return filesRename(args);
        if ("files_mkdir".equals(name)) return filesMkdir(args);
        if ("settings_update".equals(name)) return settingsUpdate(args);
        if ("server_setting".equals(name)) return serverSetting(args);
        if ("plugin_enable".equals(name)) return pluginToggle(args, true);
        if ("plugin_disable".equals(name)) return pluginToggle(args, false);
        if ("plugin_install".equals(name)) return pluginInstall(args);
        if ("modrinth_install".equals(name)) return modrinthInstall(args);
        throw new ToolException("unknown tool: " + name);
    }

    // --------------------------------------------------------------- read tools

    private JsonObject serverStatus() {
        JsonObject status = plugin.getStatsManager().getStats();
        status.addProperty("software", Bukkit.getName());
        status.addProperty("version", Bukkit.getVersion());
        status.addProperty("bukkitVersion", Bukkit.getBukkitVersion());
        status.addProperty("kodaDashVersion", plugin.getDescription().getVersion());
        try {
            JsonObject properties = ServerProperties.read();
            if (properties.has("motd")) status.add("motd", properties.get("motd"));
            if (properties.has("max-players")) status.add("maxPlayersSetting", properties.get("max-players"));
        } catch (Exception ignored) { }
        return status;
    }

    private JsonObject mcpStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("enabled", settings.enabled());
        result.addProperty("readOnly", settings.readOnly());
        result.addProperty("allowConsole", settings.allowConsole());
        result.addProperty("allowFileWrite", settings.allowFileWrite());
        result.addProperty("allowSettings", settings.allowSettings());
        result.addProperty("allowPlugins", settings.allowPlugins());
        result.addProperty("allowModrinth", settings.allowModrinth());
        result.addProperty("audit", settings.audit());
        result.addProperty("toolCount", definitions().size());
        result.addProperty("lifecycle", "user-only");
        return result;
    }

    private JsonObject consoleTail(JsonObject args) {
        int lines = clamp(optInt(args, "lines", 100), 1, 500);
        JsonArray out = new JsonArray();
        List<JsonObject> recent = plugin.getConsoleManager().getRecentLines(lines);
        int from = Math.max(0, recent.size() - lines);
        for (int i = from; i < recent.size(); i++) out.add(consoleLine(recent.get(i)));
        JsonObject result = new JsonObject();
        result.add("lines", out);
        return result;
    }

    private JsonObject playersList() throws Exception {
        JsonArray players = onMain(new Callable<JsonArray>() {
            @Override
            public JsonArray call() {
                JsonArray out = new JsonArray();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", player.getName());
                    row.addProperty("uuid", player.getUniqueId().toString());
                    row.addProperty("gamemode", player.getGameMode().name());
                    row.addProperty("health", player.getHealth());
                    row.addProperty("food", player.getFoodLevel());
                    row.addProperty("world", player.getWorld().getName());
                    row.addProperty("op", player.isOp());
                    out.add(row);
                }
                return out;
            }
        });
        JsonObject result = new JsonObject();
        result.add("players", players);
        return result;
    }

    private JsonObject playtimeTop(JsonObject args) {
        int limit = clamp(optInt(args, "limit", 20), 1, 100);
        JsonArray all = plugin.getPlaytimeManager().leaderboard();
        JsonArray out = new JsonArray();
        for (int i = 0; i < all.size() && i < limit; i++) out.add(all.get(i));
        JsonObject result = new JsonObject();
        result.add("players", out);
        return result;
    }

    private JsonObject statsHistory(JsonObject args) {
        int samples = clamp(optInt(args, "samples", 60), 1, 360);
        JsonArray all = plugin.getStatsManager().getHistory();
        JsonArray out = new JsonArray();
        int from = Math.max(0, all.size() - samples);
        for (int i = from; i < all.size(); i++) out.add(all.get(i));
        JsonObject result = new JsonObject();
        result.add("samples", out);
        result.addProperty("intervalSeconds", plugin.getConfig().getInt("stats-history.interval-seconds", 10));
        return result;
    }

    private JsonObject filesList(JsonObject args) throws Exception {
        String path = optString(args, "path", "");
        ensureMcpSafe(path);
        JsonObject result = new JsonObject();
        result.addProperty("path", path.isEmpty() ? "/" : path);
        result.add("entries", plugin.getFileManager().listDirectory(path));
        return result;
    }

    private JsonObject filesRead(JsonObject args) throws Exception {
        String path = requireString(args, "path");
        ensureMcpSafe(path);
        File file = plugin.getFileManager().resolve(path);
        if (!file.isFile()) throw new ToolException("not a file: " + path);

        int max = clamp(optInt(args, "max_bytes", 262144), 1, MAX_READ_BYTES);
        boolean truncated = file.length() > max;
        byte[] bytes = readUpTo(file, max);

        String content;
        String encoding = "utf8";
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            content = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            encoding = "base64";
            content = Base64.getEncoder().encodeToString(bytes);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("size", file.length());
        result.addProperty("encoding", encoding);
        result.addProperty("truncated", truncated);
        result.addProperty("content", content);
        return result;
    }

    private JsonObject logsTail(JsonObject args) throws Exception {
        int lines = clamp(optInt(args, "lines", 200), 1, 2000);
        File file = plugin.getFileManager().resolve("logs/latest.log");
        if (!file.isFile()) throw new ToolException("logs/latest.log not found");

        long size = file.length();
        long start = Math.max(0, size - 2L * 1024 * 1024);
        byte[] bytes = new byte[(int) (size - start)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(bytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] split = text.split("\\r?\\n");
        int from = Math.max(0, split.length - lines);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < split.length; i++) {
            if (i > from) out.append('\n');
            out.append(split[i]);
        }
        JsonObject result = new JsonObject();
        result.addProperty("lines", Math.min(lines, split.length));
        result.addProperty("truncated", start > 0 || split.length > lines);
        result.addProperty("content", cap(out.toString(), MAX_TEXT_BYTES));
        return result;
    }

    private JsonObject pluginsList() {
        JsonArray out = new JsonArray();
        Plugin[] list = Bukkit.getPluginManager().getPlugins();
        if (list != null) {
            for (Plugin item : list) {
                JsonObject row = new JsonObject();
                row.addProperty("name", item.getName());
                row.addProperty("version", item.getDescription().getVersion());
                row.addProperty("enabled", item.isEnabled());
                out.add(row);
            }
        }
        JsonObject result = new JsonObject();
        result.add("plugins", out);
        return result;
    }

    private JsonObject modrinthSearch(JsonObject args) throws Exception {
        String query = requireString(args, "query");
        int limit = clamp(optInt(args, "limit", 10), 1, 20);
        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.add("results", Modrinth.search(query, limit));
        return result;
    }

    private JsonObject settingsGet() throws Exception {
        JsonObject properties = ServerProperties.read();
        List<String> redact = new ArrayList<String>();
        for (java.util.Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("password") || key.contains("secret") || key.contains("token")
                    || key.contains("api-key") || key.contains("apikey")) {
                redact.add(entry.getKey());
            }
        }
        for (String key : redact) properties.addProperty(key, "<redacted>");
        JsonObject result = new JsonObject();
        result.add("properties", properties);
        return result;
    }

    private JsonObject scheduleList() {
        JsonArray out = new JsonArray();
        List<ScheduleManager.Entry> entries = plugin.getScheduleManager().getEntries();
        for (ScheduleManager.Entry entry : entries) {
            JsonObject row = entry.toJson();
            row.addProperty("description", plugin.getScheduleManager().describe(entry));
            row.addProperty("nextRunText", plugin.getScheduleManager().describeNextRun(entry));
            out.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("jobs", out);
        return result;
    }

    private JsonObject backupsList() {
        JsonObject result = new JsonObject();
        try {
            String raw = plugin.getFileManager().readFile("plugins/KodaDash/backups.json");
            JsonElement parsed = new JsonParser().parse(raw);
            result.addProperty("available", true);
            result.add("index", parsed);
        } catch (Exception e) {
            result.addProperty("available", false);
            result.addProperty("reason", "no readable plugins/KodaDash/backups.json");
        }
        return result;
    }

    // -------------------------------------------------------------- write tools

    private JsonObject consoleCommand(JsonObject args) throws Exception {
        String command = requireString(args, "command").trim();
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new ToolException("multi-line commands are not allowed");
        }
        String cleaned = command;
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1).trim();
        if (cleaned.isEmpty()) throw new ToolException("empty command");

        String base = cleaned.split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);
        if (LIFECYCLE.contains(base)) {
            throw new ToolException("server lifecycle commands are reserved for the human user");
        }
        if (CONSOLE_BLOCKED.contains(base)) {
            throw new ToolException("this command is not available over MCP: " + base);
        }

        int wait = clamp(optInt(args, "wait_ms", 500), 0, 3000);
        int start = plugin.getConsoleManager().getTotalLines();
        boolean dispatched = plugin.getConsoleManager().executeCommandRaw(cleaned);
        if (!dispatched) throw new ToolException("command was not dispatched");
        if (wait > 0) Thread.sleep(wait);

        List<JsonObject> produced = plugin.getConsoleManager().getLinesSince(start);
        JsonArray output = new JsonArray();
        int from = Math.max(0, produced.size() - 200);
        for (int i = from; i < produced.size(); i++) output.add(consoleLine(produced.get(i)));

        JsonObject result = new JsonObject();
        result.addProperty("dispatched", true);
        result.addProperty("command", cleaned);
        result.add("output", output);
        result.addProperty("note", "Console output is best effort; long-running commands may print after this response.");
        return result;
    }

    private JsonObject filesWrite(JsonObject args) throws Exception {
        String path = requireString(args, "path");
        ensureMcpSafe(path);
        if (path.trim().isEmpty()) throw new ToolException("missing path");

        File file = new File(".", path).getCanonicalFile();
        boolean overwritten = file.exists();
        if (overwritten && !optBool(args, "confirm", false)) {
            throw new ToolException("file exists; pass confirm:true to overwrite it");
        }

        long bytes;
        if (args.has("base64")) {
            String base64 = requireString(args, "base64");
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(base64);
            } catch (Exception e) {
                throw new ToolException("invalid base64");
            }
            if (decoded.length > MAX_READ_BYTES) throw new ToolException("file is larger than 5 MB");
            plugin.getFileManager().writeBase64File(path, base64);
            bytes = decoded.length;
        } else {
            String content = requireString(args, "content");
            byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_READ_BYTES) throw new ToolException("file is larger than 5 MB");
            plugin.getFileManager().writeFile(path, content);
            bytes = encoded.length;
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("bytes", bytes);
        result.addProperty("overwritten", overwritten);
        return result;
    }

    /**
     * one-shot upload without the 5 MB text cap. 16 MB decoded per request keeps the
     * base64 body inside the 25 MB the tunnel vhost accepts; bigger files go through
     * files_chunk_append + files_replace_atomic.
     */
    private JsonObject filesPush(JsonObject args) throws Exception {
        String path = requireString(args, "path").trim();
        if (path.isEmpty()) throw new ToolException("missing path");
        ensureMcpSafe(path);
        String base64 = requireString(args, "base64");

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new ToolException("invalid base64");
        }
        if (decoded.length > MAX_PUSH_BYTES) {
            throw new ToolException("payload is larger than 16 MB; use files_chunk_append + files_replace_atomic instead");
        }

        File file = plugin.getFileManager().resolve(path);
        boolean overwritten = file.exists();
        if (overwritten && !optBool(args, "confirm", false)) {
            throw new ToolException("file exists; pass confirm:true to overwrite it");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(file.toPath(), decoded);

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("bytes", decoded.length);
        result.addProperty("overwritten", overwritten);
        return result;
    }

    /**
     * create-or-append for big files: repeated calls assemble a jar in chunks. the
     * caller keeps the chunks on a .part name and swaps with files_replace_atomic,
     * so a broken transfer never leaves a half-written live jar behind.
     */
    private JsonObject filesChunkAppend(JsonObject args) throws Exception {
        String path = requireString(args, "path").trim();
        if (path.isEmpty()) throw new ToolException("missing path");
        ensureMcpSafe(path);
        String base64 = requireString(args, "base64");

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new ToolException("invalid base64");
        }
        if (decoded.length > MAX_PUSH_BYTES) {
            throw new ToolException("chunk is larger than 16 MB; split it further");
        }

        File file = plugin.getFileManager().resolve(path);
        long total = file.length() + decoded.length;
        if (total > MAX_APPEND_TOTAL) {
            throw new ToolException("assembled file would exceed 256 MB");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(file.toPath(), decoded,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("bytes_appended", decoded.length);
        result.addProperty("total_bytes", total);
        return result;
    }

    /**
     * the swap: moves the assembled .part file over its final name with
     * ATOMIC_MOVE, so readers never see a half jar even if the swap happens
     * while the server is running.
     */
    private JsonObject filesReplaceAtomic(JsonObject args) throws Exception {
        String from = requireString(args, "from").trim();
        String path = requireString(args, "path").trim();
        if (from.isEmpty() || path.isEmpty()) throw new ToolException("missing from or path");
        if (from.equals(path)) throw new ToolException("from and path are the same file");
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to replace");
        ensureMcpSafe(from);
        ensureMcpSafe(path);

        File source = plugin.getFileManager().resolve(from);
        if (!source.isFile()) throw new ToolException("from file does not exist");
        File target = plugin.getFileManager().resolve(path);
        if (target.isDirectory()) throw new ToolException("target path is a directory");

        try {
            Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // same server root in practice; the fallback keeps the tool usable anyway
            Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("bytes", target.length());
        result.addProperty("replaced", true);
        return result;
    }

    private JsonObject filesDelete(JsonObject args) throws Exception {
        String path = requireString(args, "path").trim();
        if (path.isEmpty() || ".".equals(path) || "/".equals(path)) {
            throw new ToolException("refusing to delete the server root");
        }
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to delete");
        ensureMcpSafe(path);

        File target = plugin.getFileManager().resolve(path);
        if (!target.exists()) throw new ToolException("not found: " + path);
        boolean recursive = optBool(args, "recursive", false);
        if (target.isDirectory() && !recursive) {
            throw new ToolException("directory is not empty; pass recursive:true");
        }
        if (target.isDirectory()) {
            // fail before the first delete if a protected file sits anywhere inside
            ensureNoBlockedInside(target, new File(".").getCanonicalFile());
        }
        deleteRecursive(target, new File(".").getCanonicalFile());

        JsonObject result = new JsonObject();
        result.addProperty("deleted", path);
        result.addProperty("recursive", recursive);
        return result;
    }

    private JsonObject filesRename(JsonObject args) throws Exception {
        String path = requireString(args, "path");
        String newName = requireString(args, "newName");
        ensureMcpSafe(path);
        File source = plugin.getFileManager().resolve(path);
        File target = new File(source.getParentFile(), newName);
        ensureMcpSafe(relativeToRoot(target));

        String newPath = plugin.getFileManager().rename(path, newName);
        JsonObject result = new JsonObject();
        result.addProperty("oldPath", path);
        result.addProperty("newPath", newPath);
        return result;
    }

    private JsonObject filesMkdir(JsonObject args) throws Exception {
        String path = requireString(args, "path");
        ensureMcpSafe(path);
        boolean created = plugin.getFileManager().createDirectory(path);
        if (!created) throw new ToolException("could not create directory");
        JsonObject result = new JsonObject();
        result.addProperty("created", path);
        return result;
    }

    private JsonObject settingsUpdate(JsonObject args) throws Exception {
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to change server.properties");
        if (!args.has("properties") || !args.get("properties").isJsonObject()) {
            throw new ToolException("missing properties object");
        }
        JsonArray updated = ServerProperties.write(args.getAsJsonObject("properties"));
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("updated", updated);
        result.addProperty("requiresRestart", true);
        return result;
    }

    private JsonObject serverSetting(JsonObject args) throws Exception {
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to change a setting");
        String key = requireString(args, "key").toLowerCase(Locale.ROOT);
        String value = requireString(args, "value").trim();
        String propertyKey;
        String normalized;

        if ("motd".equals(key)) {
            if (value.length() > 200) throw new ToolException("motd is longer than 200 characters");
            propertyKey = "motd";
            normalized = value;
        } else if ("max-players".equals(key)) {
            propertyKey = "max-players";
            normalized = String.valueOf(intRange(value, 1, 1000));
        } else if ("difficulty".equals(key)) {
            propertyKey = "difficulty";
            normalized = oneOf(value, "peaceful", "easy", "normal", "hard");
        } else if ("gamemode".equals(key)) {
            propertyKey = "gamemode";
            normalized = oneOf(value, "survival", "creative", "adventure", "spectator");
        } else if ("pvp".equals(key)) {
            propertyKey = "pvp";
            normalized = boolValue(value);
        } else if ("whitelist".equals(key) || "white-list".equals(key)) {
            propertyKey = "white-list";
            normalized = boolValue(value);
        } else if ("online-mode".equals(key)) {
            propertyKey = "online-mode";
            normalized = boolValue(value);
        } else if ("view-distance".equals(key)) {
            propertyKey = "view-distance";
            normalized = String.valueOf(intRange(value, 2, 32));
        } else if ("simulation-distance".equals(key)) {
            propertyKey = "simulation-distance";
            normalized = String.valueOf(intRange(value, 2, 32));
        } else if ("spawn-protection".equals(key)) {
            propertyKey = "spawn-protection";
            normalized = String.valueOf(intRange(value, 0, 1000));
        } else {
            throw new ToolException("unknown setting: " + key);
        }

        JsonObject updates = new JsonObject();
        updates.addProperty(propertyKey, normalized);
        JsonArray updated = ServerProperties.write(updates);
        JsonObject result = new JsonObject();
        result.addProperty("key", propertyKey);
        result.addProperty("value", normalized);
        result.add("updated", updated);
        result.addProperty("requiresRestart", true);
        return result;
    }

    private JsonObject pluginToggle(JsonObject args, final boolean enable) throws Exception {
        final String name = requireString(args, "plugin");
        if (!enable && !optBool(args, "confirm", false)) {
            throw new ToolException("pass confirm:true to disable a plugin");
        }
        final Plugin target = findPlugin(name);
        if (target == null) throw new ToolException("plugin not found: " + name);
        if (!enable && "KodaDash".equalsIgnoreCase(target.getName())) {
            throw new ToolException("refusing to disable KodaDash itself");
        }

        Boolean enabled = onMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                if (enable) Bukkit.getPluginManager().enablePlugin(target);
                else Bukkit.getPluginManager().disablePlugin(target);
                return target.isEnabled();
            }
        });
        JsonObject result = new JsonObject();
        result.addProperty("plugin", target.getName());
        result.addProperty("enabled", enabled != null && enabled);
        return result;
    }

    private JsonObject pluginInstall(JsonObject args) throws Exception {
        String url = requireString(args, "url");
        String filename = requireString(args, "filename");
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to install a plugin");

        File target = new File(new File(Bukkit.getServer().getWorldContainer(), "plugins"), filename);
        if (target.exists() && !optBool(args, "confirm", false)) {
            throw new ToolException("plugin file exists; confirm:true is required to replace it");
        }
        File installed = PluginInstaller.install(plugin, url, filename);
        JsonObject result = new JsonObject();
        result.addProperty("path", installed.getPath());
        result.addProperty("size", installed.length());
        result.addProperty("note", "Restart the server to load the new plugin; only the user may restart it.");
        return result;
    }

    private JsonObject modrinthInstall(JsonObject args) throws Exception {
        String project = requireString(args, "project");
        if (!optBool(args, "confirm", false)) throw new ToolException("pass confirm:true to install a plugin");
        String versionId = optString(args, "version", "");
        String gameVersion = optString(args, "game_version", localGameVersion());
        String loader = optString(args, "loader", localLoader());

        Modrinth.Version version = Modrinth.resolve(project, versionId, gameVersion, loader);
        File installed = PluginInstaller.install(plugin, version.url, version.filename);

        String algorithm = version.sha512.isEmpty() ? "SHA-1" : "SHA-512";
        String expected = version.sha512.isEmpty() ? version.sha1 : version.sha512;
        String actual = hashFile(installed, algorithm);
        if (!actual.equalsIgnoreCase(expected)) {
            installed.delete();
            throw new ToolException("checksum mismatch; the downloaded file was deleted");
        }

        JsonObject result = new JsonObject();
        result.addProperty("project", project);
        result.addProperty("version", version.versionNumber);
        result.addProperty("version_id", version.id);
        result.addProperty("filename", version.filename);
        result.addProperty("size", installed.length());
        result.addProperty("checksum", actual);
        result.addProperty("checksum_algorithm", algorithm);
        result.addProperty("game_version", gameVersion);
        result.addProperty("loader", loader);
        result.addProperty("note", "Restart the server to load the new plugin; only the user may restart it.");
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static String localGameVersion() {
        try {
            String version = Bukkit.getVersion();
            int marker = version.indexOf("(MC: ");
            if (marker >= 0) {
                int end = version.indexOf(')', marker);
                if (end > marker) return version.substring(marker + 5, end).trim();
            }
            String bukkit = Bukkit.getBukkitVersion();
            int dash = bukkit.indexOf('-');
            return dash > 0 ? bukkit.substring(0, dash) : bukkit;
        } catch (Exception e) {
            return "";
        }
    }

    private static String localLoader() {
        try {
            String name = Bukkit.getName().toLowerCase(Locale.ROOT);
            if ("purpur".equals(name)) return "purpur";
        } catch (Exception ignored) { }
        return "paper";
    }

    private static String hashFile(File file, String algorithm) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static int intRange(String value, int min, int max) throws ToolException {
        try {
            int number = Integer.parseInt(value.trim());
            if (number < min || number > max) {
                throw new ToolException("value must be between " + min + " and " + max);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new ToolException("value is not a number");
        }
    }

    private static String oneOf(String value, String... allowed) throws ToolException {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(lower)) return lower;
        }
        throw new ToolException("value must be one of: " + String.join(", ", allowed));
    }

    private static String boolValue(String value) throws ToolException {
        String lower = value.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "on".equals(lower) || "yes".equals(lower)) return "true";
        if ("false".equals(lower) || "off".equals(lower) || "no".equals(lower)) return "false";
        throw new ToolException("value must be true or false");
    }

    private static JsonObject errorJson(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message == null ? "unknown error" : message);
        return json;
    }

    private static JsonObject consoleLine(JsonObject line) {
        JsonObject out = new JsonObject();
        if (line.has("level")) out.add("level", line.get("level"));
        if (line.has("timestamp")) out.add("timestamp", line.get("timestamp"));
        if (line.has("message")) out.add("message", line.get("message"));
        return out;
    }

    private <T> T onMain(Callable<T> task) throws Exception {
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(5, TimeUnit.SECONDS);
    }

    private Plugin findPlugin(String name) {
        Plugin[] list = Bukkit.getPluginManager().getPlugins();
        if (list == null) return null;
        for (Plugin item : list) {
            if (item.getName().equalsIgnoreCase(name)) return item;
        }
        return null;
    }

    private void ensureMcpSafe(String path) throws Exception {
        if (path == null) throw new ToolException("missing path");
        String normalized = path.replace('\\', '/').trim();
        if (normalized.startsWith("/")) throw new ToolException("absolute paths are not allowed");
        File root = new File(".").getCanonicalFile();
        File file = new File(root, normalized);
        String canonicalRoot = root.getCanonicalPath();
        String canonical = file.getCanonicalPath();
        if (!canonical.equals(canonicalRoot)
                && !canonical.startsWith(canonicalRoot + File.separator)) {
            throw new ToolException("path leaves the server directory");
        }

        for (String blocked : MCP_BLOCKED) {
            File blockedFile = new File(root, blocked);
            String blockedCanonical = blockedFile.getCanonicalPath();
            if (canonical.equalsIgnoreCase(blockedCanonical)
                    || canonical.toLowerCase(Locale.ROOT).startsWith(
                            blockedCanonical.toLowerCase(Locale.ROOT) + File.separator)) {
                throw new ToolException("this path is protected: " + blocked);
            }
        }
    }

    private String relativeToRoot(File file) throws Exception {
        File root = new File(".").getCanonicalFile();
        String canonical = file.getCanonicalPath();
        String canonicalRoot = root.getCanonicalPath();
        if (!canonical.equals(canonicalRoot)
                && !canonical.startsWith(canonicalRoot + File.separator)) {
            throw new ToolException("path leaves the server directory");
        }
        if (canonical.equals(canonicalRoot)) return "";
        return canonical.substring(canonicalRoot.length() + 1).replace('\\', '/');
    }

    private void ensureNoBlockedInside(File file, File root) throws Exception {
        ensureMcpSafe(relativeToRoot(file));
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) ensureNoBlockedInside(child, root);
            }
        }
    }

    private void deleteRecursive(File file, File root) throws Exception {
        String canonicalRoot = root.getCanonicalPath();
        String canonical = file.getCanonicalPath();
        if (!canonical.equals(canonicalRoot)
                && !canonical.startsWith(canonicalRoot + File.separator)) {
            throw new ToolException("path leaves the server directory");
        }
        ensureMcpSafe(relativeToRoot(file));

        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child, root);
            }
        }
        Files.delete(file.toPath());
    }

    private static byte[] readUpTo(File file, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int remaining = max;
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return out.toByteArray();
    }

    private static String cap(String value, int maxBytes) {
        if (value == null) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "\n... (truncated)";
    }

    private static String requireString(JsonObject args, String key) throws ToolException {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            throw new ToolException("missing argument: " + key);
        }
        String value = args.get(key).getAsString();
        if (value == null || value.trim().isEmpty()) throw new ToolException("empty argument: " + key);
        return value;
    }

    private static String optString(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int optInt(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject args, String key, boolean fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------- json schema

    private static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject schemaWith(Object... items) {
        JsonObject schema = emptySchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonArray required = schema.getAsJsonArray("required");
        for (int i = 0; i < items.length; i++) {
            Object item = items[i];
            if (item instanceof JsonObject) {
                JsonObject prop = (JsonObject) item;
                String name = prop.get("name").getAsString();
                properties.add(name, prop);
            } else if (item instanceof String) {
                required.add(new JsonPrimitive((String) item));
            }
        }
        return schema;
    }

    private static JsonObject propString(String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name);
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject propInt(String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name);
        prop.addProperty("type", "integer");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject propBool(String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name);
        prop.addProperty("type", "boolean");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject propObject(String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name);
        prop.addProperty("type", "object");
        prop.addProperty("description", description);
        prop.addProperty("additionalProperties", true);
        return prop;
    }

    private static JsonObject tool(String name, String title, String description,
                                   boolean readOnly, boolean destructive, boolean openWorld,
                                   JsonObject schema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("title", title);
        tool.addProperty("description", description);
        tool.add("inputSchema", cleanSchema(schema));
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", destructive);
        annotations.addProperty("idempotentHint", readOnly);
        annotations.addProperty("openWorldHint", openWorld);
        tool.add("annotations", annotations);
        return tool;
    }

    /** the helper props carry name for our builder; strip it from the wire schema. */
    private static JsonObject cleanSchema(JsonObject schema) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "object");
        JsonObject cleanProps = new JsonObject();
        if (schema.has("properties")) {
            JsonObject props = schema.getAsJsonObject("properties");
            for (java.util.Map.Entry<String, JsonElement> entry : props.entrySet()) {
                JsonObject prop = entry.getValue().getAsJsonObject();
                JsonObject clean = new JsonObject();
                for (java.util.Map.Entry<String, JsonElement> field : prop.entrySet()) {
                    if (!"name".equals(field.getKey())) {
                        clean.add(field.getKey(), field.getValue());
                    }
                }
                cleanProps.add(entry.getKey(), clean);
            }
        }
        out.add("properties", cleanProps);
        out.add("required", schema.has("required") ? schema.getAsJsonArray("required") : new JsonArray());
        out.addProperty("additionalProperties", false);
        return out;
    }
}
