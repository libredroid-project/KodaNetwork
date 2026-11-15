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

import com.google.gson.JsonObject;
import de.kodahosting.kodadash.KodaDash;

import java.security.SecureRandom;

/**
 * the MCP configuration: its own token, the read-only switch and the capability
 * groups. every getter reads the live config, so the settings route can change
 * things without restarting the server.
 *
 * all keys are read with a default because saveDefaultConfig() only writes the
 * file on first enable and never merges new keys into an existing config.
 */
public final class McpSettings {
    private final KodaDash plugin;

    public McpSettings(KodaDash plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("mcp.enabled", false);
    }

    public String token() {
        String token = plugin.getConfig().getString("mcp.token", "");
        return token == null ? "" : token.trim();
    }

    public boolean readOnly() {
        return plugin.getConfig().getBoolean("mcp.read-only", false);
    }

    public boolean audit() {
        return plugin.getConfig().getBoolean("mcp.audit", true);
    }

    public boolean allowConsole() {
        return !readOnly() && plugin.getConfig().getBoolean("mcp.allow-console", true);
    }

    public boolean allowFileWrite() {
        return !readOnly() && plugin.getConfig().getBoolean("mcp.allow-file-write", true);
    }

    public boolean allowSettings() {
        return !readOnly() && plugin.getConfig().getBoolean("mcp.allow-settings", true);
    }

    public boolean allowPlugins() {
        return !readOnly() && plugin.getConfig().getBoolean("mcp.allow-plugins", true);
    }

    /** makes sure a token exists and returns it. */
    public String ensureToken() {
        String current = token();
        if (!current.isEmpty()) return current;
        return regenerateToken();
    }

    /** a fresh token invalidates the old one immediately. */
    public String regenerateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String token = sb.toString();
        plugin.getConfig().set("mcp.token", token);
        plugin.saveConfig();
        return token;
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("mcp.enabled", enabled);
        if (enabled) {
            plugin.getConfig().set("mcp.token", ensureToken());
        }
        plugin.saveConfig();
    }

    public void setReadOnly(boolean readOnly) {
        plugin.getConfig().set("mcp.read-only", readOnly);
        plugin.saveConfig();
    }

    public void setAllowConsole(boolean allow) {
        plugin.getConfig().set("mcp.allow-console", allow);
        plugin.saveConfig();
    }

    public void setAllowFileWrite(boolean allow) {
        plugin.getConfig().set("mcp.allow-file-write", allow);
        plugin.saveConfig();
    }

    public void setAllowSettings(boolean allow) {
        plugin.getConfig().set("mcp.allow-settings", allow);
        plugin.saveConfig();
    }

    public void setAllowPlugins(boolean allow) {
        plugin.getConfig().set("mcp.allow-plugins", allow);
        plugin.saveConfig();
    }

    public void setAudit(boolean audit) {
        plugin.getConfig().set("mcp.audit", audit);
        plugin.saveConfig();
    }

    /**
     * applies a JSON patch from the settings route. unknown keys are ignored.
     */
    public void apply(JsonObject body) {
        if (body == null) return;
        if (body.has("enabled")) setEnabled(body.get("enabled").getAsBoolean());
        if (body.has("readOnly")) setReadOnly(body.get("readOnly").getAsBoolean());
        if (body.has("allowConsole")) setAllowConsole(body.get("allowConsole").getAsBoolean());
        if (body.has("allowFileWrite")) setAllowFileWrite(body.get("allowFileWrite").getAsBoolean());
        if (body.has("allowSettings")) setAllowSettings(body.get("allowSettings").getAsBoolean());
        if (body.has("allowPlugins")) setAllowPlugins(body.get("allowPlugins").getAsBoolean());
        if (body.has("audit")) setAudit(body.get("audit").getAsBoolean());
        if (body.has("regenerate") && body.get("regenerate").getAsBoolean()) regenerateToken();
        // enabling after a patch has to leave a usable token behind
        if (enabled() && token().isEmpty()) ensureToken();
    }

    /** the state the dashboard and the app render. never include the token in logs. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled());
        json.addProperty("token", token());
        json.addProperty("readOnly", readOnly());
        // raw switches, so the UI can show what comes back when read-only is turned off
        json.addProperty("allowConsole", plugin.getConfig().getBoolean("mcp.allow-console", true));
        json.addProperty("allowFileWrite", plugin.getConfig().getBoolean("mcp.allow-file-write", true));
        json.addProperty("allowSettings", plugin.getConfig().getBoolean("mcp.allow-settings", true));
        json.addProperty("allowPlugins", plugin.getConfig().getBoolean("mcp.allow-plugins", true));
        json.addProperty("audit", audit());
        return json;
    }
}
