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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kodahosting.kodadash.KodaDash;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

/**
 * one JSON line per MCP tool call. this is the only reliable record of what an
 * AI did, because the AI client's own transcript is not on the server.
 *
 * file contents and base64 payloads are replaced by their length, not written
 * to disk. the token and password arguments are never logged.
 */
public final class McpAudit {
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private final KodaDash plugin;
    private final McpSettings settings;

    public McpAudit(KodaDash plugin, McpSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void log(String tool, JsonObject arguments, boolean ok, String error,
                    long durationMs, String remote) {
        if (!settings.audit()) return;

        JsonObject line = new JsonObject();
        line.addProperty("ts", isoNow());
        line.addProperty("tool", tool == null ? "?" : tool);
        line.add("args", redact(arguments));
        line.addProperty("ok", ok);
        if (error != null && !error.isEmpty()) line.addProperty("error", truncate(error, 500));
        line.addProperty("ms", durationMs);
        if (remote != null && !remote.isEmpty()) line.addProperty("remote", remote);

        String json = line.toString();
        synchronized (McpAudit.class) {
            try {
                File folder = plugin.getDataFolder();
                if (!folder.exists() && !folder.mkdirs()) return;
                File file = new File(folder, "mcp-audit.log");
                rotate(file);
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                    writer.write(json);
                    writer.newLine();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not write MCP audit log: " + e.getMessage());
            }
        }
    }

    private static void rotate(File file) {
        if (!file.isFile() || file.length() < MAX_BYTES) return;
        File old = new File(file.getParentFile(), file.getName() + ".1");
        if (old.exists()) old.delete();
        file.renameTo(old);
    }

    private static JsonObject redact(JsonObject arguments) {
        if (arguments == null) return new JsonObject();
        JsonElement redacted = redactElement(null, arguments);
        return redacted != null && redacted.isJsonObject() ? redacted.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement redactElement(String key, JsonElement value) {
        if (value == null || value.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (key != null && isSecretKey(key)) {
            int length = value.isJsonPrimitive() ? value.getAsString().length() : 0;
            return new com.google.gson.JsonPrimitive("<omitted:" + length + " chars>");
        }
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                out.add(entry.getKey(), redactElement(entry.getKey(), entry.getValue()));
            }
            return out;
        }
        if (value.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                out.add(redactElement(null, item));
            }
            return out;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new com.google.gson.JsonPrimitive(truncate(value.getAsString(), 300));
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("api-key")
                || lower.contains("apikey") || "content".equals(lower)
                || "base64".equals(lower);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
