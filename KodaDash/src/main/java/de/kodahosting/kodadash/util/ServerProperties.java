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
package de.kodahosting.kodadash.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * reads and writes server.properties the same way Minecraft does: ISO-8859-1,
 * non-ASCII values as Unicode escapes, comments and unknown lines preserved.
 *
 * the dashboard route and the MCP tools share this so the file format cannot
 * drift between the two.
 */
public final class ServerProperties {

    private ServerProperties() {
    }

    public static File file() {
        return new File(".", "server.properties").getAbsoluteFile();
    }

    /** all key/value pairs, escaped values decoded into real text. */
    public static JsonObject read() throws IOException {
        return read(file());
    }

    public static JsonObject read(File serverPropertiesFile) throws IOException {
        JsonObject properties = new JsonObject();
        if (!serverPropertiesFile.exists()) return properties;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(serverPropertiesFile), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    properties.addProperty(key, decode(value));
                }
            }
        }
        return properties;
    }

    /**
     * changes the given keys, appends keys that are not in the file yet, and keeps
     * every comment and unknown line in place.
     *
     * @return the keys that were written
     */
    public static JsonArray write(JsonObject updates) throws IOException {
        return write(file(), updates);
    }

    public static JsonArray write(File serverPropertiesFile, JsonObject updates) throws IOException {
        if (!serverPropertiesFile.exists()) {
            throw new IOException("server.properties not found");
        }

        List<String> lines = new ArrayList<String>();
        JsonArray updatedKeys = new JsonArray();
        JsonObject remaining = new JsonObject();
        if (updates != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : updates.entrySet()) {
                remaining.add(entry.getKey(), entry.getValue());
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(serverPropertiesFile), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    lines.add(line);
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    if (remaining.has(key)) {
                        lines.add(key + "=" + encode(remaining.get(key).getAsString()));
                        updatedKeys.add(new JsonPrimitive(key));
                        remaining.remove(key);
                    } else {
                        lines.add(line);
                    }
                } else {
                    lines.add(line);
                }
            }
        }

        for (Map.Entry<String, com.google.gson.JsonElement> entry : remaining.entrySet()) {
            String key = entry.getKey();
            lines.add(key + "=" + encode(entry.getValue().getAsString()));
            updatedKeys.add(new JsonPrimitive(key));
        }

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(serverPropertiesFile), StandardCharsets.ISO_8859_1))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
        return updatedKeys;
    }

    /** decodes Unicode escapes and doubled backslashes the way the game writes them. */
    public static String decode(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'u' && i + 5 < value.length()) {
                    try {
                        out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) { }
                }
                if (next == '\\') {
                    out.append('\\');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** encodes non-ASCII and control characters as Unicode escapes, backslashes doubled. */
    public static String encode(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c < 0x20 || c > 0x7E) out.append(String.format("\\u%04X", (int) c));
            else out.append(c);
        }
        return out.toString();
    }
}
