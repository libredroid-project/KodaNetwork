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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * reads the metadata every server software writes into a mod or plugin jar, so the
 * installed list shows an icon, a name, a description and a version, not a bare file name.
 *
 * Paper/Bukkit: plugin.yml (name, version, description) + an icon in the jar.
 * Fabric: fabric.mod.json (id, name, version, description, icon).
 * Forge/NeoForge: META-INF/mods.toml (modId, version, displayName, description, logoFile).
 */
public final class JarInfo {

    public String name = "";
    public String version = "";
    public String description = "";
    public Bitmap icon;
    public String fileName = "";

    private JarInfo() {
    }

    public static JarInfo read(File jarFile) {
        JarInfo info = new JarInfo();
        info.fileName = jarFile.getName();
        info.name = jarFile.getName().replace(".disabled", "").replace(".jar", "");
        try (ZipFile zip = new ZipFile(jarFile)) {
            readPluginYml(zip, info);
            readFabricJson(zip, info);
            readModsToml(zip, info);
            readIcon(zip, info);
        } catch (Exception ignored) {
            // the file name stays as the fallback
        }
        return info;
    }

    private static void readPluginYml(ZipFile zip, JarInfo info) {
        String yml = readEntry(zip, "plugin.yml");
        if (yml == null) return;
        info.name = value(yml, "name", info.name);
        info.version = value(yml, "version", info.version);
        String description = value(yml, "description", "");
        if (!description.isEmpty()) info.description = description;
    }

    private static void readFabricJson(ZipFile zip, JarInfo info) {
        String json = readEntry(zip, "fabric.mod.json");
        if (json == null) return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            info.name = obj.optString("name", info.name);
            info.version = obj.optString("version", info.version);
            info.description = obj.optString("description", info.description);
        } catch (Exception ignored) {
        }
    }

    private static void readModsToml(ZipFile zip, JarInfo info) {
        String toml = readEntry(zip, "META-INF/mods.toml");
        if (toml == null) toml = readEntry(zip, "META-INF/neoforge.mods.toml");
        if (toml == null) return;
        String id = value(toml, "modId", "");
        info.name = value(toml, "displayName", id.isEmpty() ? info.name : id);
        info.version = value(toml, "version", info.version);
        String description = value(toml, "description", "");
        if (!description.isEmpty()) info.description = description;
    }

    /** the icon files mod loaders and bukkit plugins like to ship. */
    private static void readIcon(ZipFile zip, JarInfo info) {
        String[] candidates = {
                "icon.png", "logo.png", "icon.jpg", "icon.jpeg",
                "assets/icon.png", "assets/logo.png",
        };
        for (String candidate : candidates) {
            byte[] bytes = readBytes(zip, candidate);
            if (bytes != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    info.icon = bitmap;
                    return;
                }
            }
        }
        // fabric and forge often tuck it next to the assets, the first icon-ish png wins
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().toLowerCase();
            if (name.endsWith("icon.png") || name.endsWith("logo.png")) {
                byte[] bytes = readBytes(zip, entry.getName());
                if (bytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        info.icon = bitmap;
                        return;
                    }
                }
            }
        }
    }

    /** a tiny "key: value" reader for the yaml/toml lines in these files. */
    private static String value(String text, String key, String fallback) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.startsWith(key)) continue;
            int colon = trimmed.indexOf(':');
            int equals = trimmed.indexOf('=');
            int separator = colon > 0 ? colon : equals;
            if (separator < 0) continue;
            String candidateKey = trimmed.substring(0, separator).trim();
            if (!candidateKey.equalsIgnoreCase(key)) continue;
            String value = trimmed.substring(separator + 1).trim();
            value = value.replaceAll("^[\"']|[\"']$", "");
            if (value.equalsIgnoreCase("'''") || value.startsWith("'''")) {
                // multi-line toml strings, only the first line is taken
                value = value.replace("'''", "").trim();
            }
            if (!value.isEmpty()) return value;
        }
        return fallback;
    }

    private static String readEntry(ZipFile zip, String name) {
        byte[] bytes = readBytes(zip, name);
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = is.read(buffer)) > 0 && total < 2_000_000) {
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
