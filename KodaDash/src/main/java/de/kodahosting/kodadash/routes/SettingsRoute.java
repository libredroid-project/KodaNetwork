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
import com.google.gson.JsonPrimitive;

/**
 * reads and updates server.properties.
 */
public class SettingsRoute extends RouteHandler {

    public SettingsRoute(KodaDash plugin) {
        super(plugin);
        // server.properties sits in the server root, not in the plugin folder
    }

    private File getServerPropertiesFile() {
        return new File(".", "server.properties").getAbsoluteFile();
    }

    /*
     * Minecraft liest server.properties als ISO-8859-1 und schreibt Nicht-ASCII als
     * Unicode-Escape. Der Panel-Schreibpfad hat bisher mit dem Plattform-Charset (UTF-8)
     * gelesen und geschrieben - ein Farbcode § landete dadurch als zwei Bytes in der
     * Datei und kam als "Ä·" zurueck (die MOTD im Panel-Kopf war so irgendwann
     * unlesbar). Diese beiden Helfer halten sich an dasselbe Format wie das Spiel:
     * gespeichert wird ASCII mit Unicode-Escapes, angezeigt wird der echte Text.
     */
    private static String decodePropertiesValue(String value) {
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
                if (next == '\\') { out.append('\\'); i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String encodePropertiesValue(String value) {
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

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        File serverPropertiesFile = getServerPropertiesFile();
        if (!serverPropertiesFile.exists()) {
            sendError(exchange, 404, "server.properties not found");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(serverPropertiesFile), StandardCharsets.ISO_8859_1))) {
            JsonObject properties = new JsonObject();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    properties.addProperty(key, decodePropertiesValue(value));
                }
            }

            JsonObject response = new JsonObject();
            response.add("properties", properties);
            sendJson(exchange, 200, response);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read server.properties: " + e.getMessage());
            sendError(exchange, 500, "Failed to read server.properties");
        }
    }

    @Override
    protected void handlePost(HttpExchange exchange) throws IOException {
        File serverPropertiesFile = getServerPropertiesFile();
        if (!serverPropertiesFile.exists()) {
            sendError(exchange, 404, "server.properties not found");
            return;
        }

        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }

        try {
            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            if (!json.has("properties")) {
                sendError(exchange, 400, "Missing 'properties' object");
                return;
            }

            JsonObject newProperties = json.getAsJsonObject("properties");
            List<String> lines = new ArrayList<>();
            JsonArray updatedKeys = new JsonArray();

            // every line stays as it is, comments and blanks included, only touched keys change
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
                        if (newProperties.has(key)) {
                            String newValue = encodePropertiesValue(newProperties.get(key).getAsString());
                            lines.add(key + "=" + newValue);
                            updatedKeys.add(new JsonPrimitive(key));
                            newProperties.remove(key);
                        } else {
                            lines.add(line);
                        }
                    } else {
                        lines.add(line);
                    }
                }
            }

            // keys that were not in the file yet get appended at the end
            for (Map.Entry<String, com.google.gson.JsonElement> entry : newProperties.entrySet()) {
                String key = entry.getKey();
                String value = encodePropertiesValue(entry.getValue().getAsString());
                lines.add(key + "=" + value);
                updatedKeys.add(new JsonPrimitive(key));
            }

            // and the whole list goes back onto disk
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(serverPropertiesFile), StandardCharsets.ISO_8859_1))) {
                for (String outLine : lines) {
                    writer.write(outLine);
                    writer.newLine();
                }
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("updated", updatedKeys);
            response.addProperty("requiresRestart", true);

            sendJson(exchange, 200, response);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update server.properties: " + e.getMessage());
            sendError(exchange, 400, "Failed to update server.properties: " + e.getMessage());
        }
    }
}
