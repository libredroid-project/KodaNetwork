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
package eu.kodanetwork.mchost.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * the parsed and validated manifest.json of a .kodaext package.
 *
 * pure Java on purpose (no Android imports, only org.json), so the whole
 * validation runs in JVM unit tests. callers pass the current
 * BuildConfig.VERSION_CODE as appVersionCode.
 */
public final class ExtensionManifest {

    public static final int SCHEMA = 1;

    /** permissions the runtime knows about in API version 1. */
    public static final Set<String> BASE_PERMISSIONS = new HashSet<>(Arrays.asList(
            "storage", "servers.read", "console.read", "console.send", "servers.control",
            "players.read", "players.actions", "files.read", "notifications"));

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9_-]+)+$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");
    private static final Pattern LANG_CODE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Za-z]{2,4})?$");
    private static final Pattern PACK_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern VIEW_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,48}$");

    private static final int MAX_ID_LEN = 64;
    private static final int MAX_NAME_LEN = 48;
    private static final int MAX_VERSION_LEN = 24;
    private static final int MAX_AUTHOR_LEN = 64;
    private static final int MAX_DESCRIPTION_LEN = 600;
    private static final int MAX_LICENSE_LEN = 64;
    private static final int MAX_TITLE_LEN = 32;
    private static final int MAX_SUBTITLE_LEN = 80;
    private static final int MAX_PATH_LEN = 200;
    private static final int MAX_URL_LEN = 300;
    private static final int MAX_PERMISSIONS = 16;

    public final String id;
    public final String name;
    public final String version;
    public final int versionCode;
    public final String author;
    public final String description;
    /** required, the store lists every extension together with its license. */
    public final String license;
    public final String sourceUrl;
    public final String homepage;
    public final int minAppVersion;
    public final List<String> permissions;
    /** relative path inside the package, null when the extension has no screen. */
    public final String uiEntry;
    public final String uiTitle;
    /** relative path of automations.json, null when there are no automations. */
    public final String automationsFile;
    /** relative path of the icon, may be null (falls back to icon.png in the root). */
    public final String iconFile;

    /** a language pack: a JSON file with string resource names as keys. */
    public static final class LanguagePack {
        public final String code;
        public final String name;
        public final String file;
        LanguagePack(String code, String name, String file) { this.code = code; this.name = name; this.file = file; }
    }

    /** a theme: an accent seed plus optional full colour overrides for the Material 3 engine. */
    public static final class ThemePack {
        public final String id;
        public final String name;
        public final int color;
        /** role overrides, keyed by the names in THEME_COLOR_KEYS; may be empty */
        public final java.util.Map<String, Integer> colors;
        ThemePack(String id, String name, int color, java.util.Map<String, Integer> colors) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.colors = colors;
        }
    }

    /** colour keys a theme may override (contributes.themes[].colors). */
    public static final java.util.List<String> THEME_COLOR_KEYS = java.util.Arrays.asList(
            "primary", "surface", "card", "text", "text_dim", "outline", "online", "error");

    /** a tab the extension adds to the server screen. */
    public static final class ServerTab {
        public final String id;
        public final String title;
        /** optional own page, defaults to ui.entry */
        public final String entry;
        /** optional target appended to the page URL as #target */
        public final String target;
        ServerTab(String id, String title, String entry, String target) {
            this.id = id;
            this.title = title;
            this.entry = entry;
            this.target = target;
        }
    }

    /** a settings entry the extension adds to the app's settings screen. */
    public static final class SettingsEntry {
        public final String title;
        public final String subtitle;
        /** optional screen target, passed to the extension page as "#target" */
        public final String target;
        SettingsEntry(String title, String subtitle, String target) {
            this.title = title;
            this.subtitle = subtitle;
            this.target = target;
        }
    }

    /** declarative UI tweaks: hide app elements, add console quick commands. */
    public static final class UiTweaks {
        public final List<String> hideViewIds;
        public final List<String> consoleChips;
        UiTweaks(List<String> hideViewIds, List<String> consoleChips) {
            this.hideViewIds = hideViewIds;
            this.consoleChips = consoleChips;
        }
    }

    public final List<LanguagePack> languages;
    public final List<ThemePack> themes;
    public final UiTweaks uiTweaks;
    /** settings entries that show up in the app's settings screen (contributes.settings). */
    public final List<SettingsEntry> settingsEntries;
    /** tabs added to the server screen (contributes.server_tabs). */
    public final List<ServerTab> serverTabs;

    private ExtensionManifest(String id, String name, String version, int versionCode, String author,
                              String description, String license, String sourceUrl, String homepage,
                              int minAppVersion, List<String> permissions, String uiEntry, String uiTitle,
                              String automationsFile, String iconFile,
                              List<LanguagePack> languages, List<ThemePack> themes, UiTweaks uiTweaks,
                              List<SettingsEntry> settingsEntries, List<ServerTab> serverTabs) {
        this.languages = languages;
        this.themes = themes;
        this.uiTweaks = uiTweaks;
        this.settingsEntries = settingsEntries;
        this.serverTabs = serverTabs;
        this.id = id;
        this.name = name;
        this.version = version;
        this.versionCode = versionCode;
        this.author = author;
        this.description = description;
        this.license = license;
        this.sourceUrl = sourceUrl;
        this.homepage = homepage;
        this.minAppVersion = minAppVersion;
        this.permissions = permissions;
        this.uiEntry = uiEntry;
        this.uiTitle = uiTitle;
        this.automationsFile = automationsFile;
        this.iconFile = iconFile;
    }

    /** a rejected manifest carries a human readable reason, the user gets to see it. */
    public static final class InvalidManifestException extends Exception {
        public InvalidManifestException(String reason) { super(reason); }
    }

    public boolean hasUi() { return uiEntry != null; }
    public boolean hasAutomations() { return automationsFile != null; }

    public boolean hasContributions() {
        return (languages != null && !languages.isEmpty())
                || (themes != null && !themes.isEmpty())
                || (settingsEntries != null && !settingsEntries.isEmpty())
                || (serverTabs != null && !serverTabs.isEmpty())
                || (uiTweaks != null && (!uiTweaks.hideViewIds.isEmpty() || !uiTweaks.consoleChips.isEmpty()));
    }

    public boolean hasPermission(String permission) { return permissions.contains(permission); }

    public static ExtensionManifest parse(String json, int appVersionCode) throws InvalidManifestException {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            throw new InvalidManifestException("manifest.json is not valid JSON");
        }

        if (o.optInt("schema", -1) != SCHEMA) {
            throw new InvalidManifestException("unsupported schema");
        }

        String id = reqString(o, "id", MAX_ID_LEN);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new InvalidManifestException("invalid id: " + id);
        }

        String name = reqString(o, "name", MAX_NAME_LEN);

        String version = reqString(o, "version", MAX_VERSION_LEN);
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new InvalidManifestException("invalid version: " + version);
        }

        int versionCode = o.optInt("versionCode", -1);
        if (versionCode < 1) {
            throw new InvalidManifestException("versionCode must be >= 1");
        }

        int minAppVersion = o.optInt("minAppVersion", 0);
        if (minAppVersion < 0) minAppVersion = 0;
        if (minAppVersion > appVersionCode) {
            throw new InvalidManifestException("needs a newer app (minAppVersion " + minAppVersion + ")");
        }

        String author = optLimited(o, "author", MAX_AUTHOR_LEN);
        String description = optLimited(o, "description", MAX_DESCRIPTION_LEN);

        // license is required, everything in the store is listed with its license
        String license = reqString(o, "license", MAX_LICENSE_LEN);

        String sourceUrl = null;
        if (o.has("source_url")) sourceUrl = optUrl(o, "source_url");
        if (sourceUrl == null && o.has("sourceUrl")) sourceUrl = optUrl(o, "sourceUrl");
        String homepage = optUrl(o, "homepage");

        List<String> permissions = parsePermissions(o.optJSONArray("permissions"));

        String uiEntry = null;
        String uiTitle = name;
        JSONObject ui = o.optJSONObject("ui");
        if (ui != null) {
            uiEntry = relPath(ui.optString("entry", ""), "ui.entry");
            String title = ui.optString("title", "").trim();
            if (!title.isEmpty()) {
                if (title.length() > MAX_TITLE_LEN) throw new InvalidManifestException("ui.title too long");
                uiTitle = title;
            }
        }

        String automationsFile = null;
        if (o.has("automations") && !o.isNull("automations")) {
            automationsFile = relPath(o.optString("automations", ""), "automations");
            if (!automationsFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                throw new InvalidManifestException("automations must be a .json file");
            }
        }

        String iconFile = null;
        if (o.has("icon") && !o.isNull("icon")) {
            iconFile = relPath(o.optString("icon", ""), "icon");
        }

        JSONObject contributes = o.optJSONObject("contributes");
        List<LanguagePack> languages = parseLanguages(contributes);
        List<ThemePack> themes = parseThemes(contributes);
        UiTweaks uiTweaks = parseUiTweaks(contributes);
        List<SettingsEntry> settingsEntries = parseSettingsEntries(contributes);
        List<ServerTab> serverTabs = parseServerTabs(contributes);

        return new ExtensionManifest(id, name, version, versionCode, author, description, license,
                sourceUrl, homepage, minAppVersion, permissions, uiEntry, uiTitle, automationsFile, iconFile,
                languages, themes, uiTweaks, settingsEntries, serverTabs);
    }

    /** contributes.server_tabs: tabs added to the server screen. */
    private static List<ServerTab> parseServerTabs(JSONObject contributes) throws InvalidManifestException {
        List<ServerTab> out = new ArrayList<>();
        JSONArray arr = contributes == null ? null : contributes.optJSONArray("server_tabs");
        if (arr == null) return out;
        if (arr.length() > 4) throw new InvalidManifestException("too many server tabs");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) throw new InvalidManifestException("server_tabs entries must be objects");
            String tabId = e.optString("id", "").trim();
            if (!PACK_ID_PATTERN.matcher(tabId).matches()) {
                throw new InvalidManifestException("invalid server tab id: " + tabId);
            }
            if (!seen.add(tabId)) throw new InvalidManifestException("duplicate server tab id: " + tabId);
            String title = reqString(e, "title", MAX_TITLE_LEN);
            // no entry = the tab reuses the extension's main page (ui.entry)
            String entry = null;
            if (e.has("entry") && !e.isNull("entry")) {
                entry = relPath(e.optString("entry", ""), "server_tabs.entry");
                if (!entry.toLowerCase(Locale.ROOT).endsWith(".html")) {
                    throw new InvalidManifestException("server tab entry must be an .html file");
                }
            }
            String target = null;
            if (e.has("target") && !e.isNull("target")) {
                target = e.optString("target", "").trim();
                if (!PACK_ID_PATTERN.matcher(target).matches()) {
                    throw new InvalidManifestException("invalid server tab target: " + target);
                }
            }
            out.add(new ServerTab(tabId, title, entry, target));
        }
        return out;
    }

    /** contributes.settings: rows the extension adds to the app's settings screen. */
    private static List<SettingsEntry> parseSettingsEntries(JSONObject contributes) throws InvalidManifestException {
        List<SettingsEntry> out = new ArrayList<>();
        JSONArray arr = contributes == null ? null : contributes.optJSONArray("settings");
        if (arr == null) return out;
        if (arr.length() > 4) throw new InvalidManifestException("too many settings entries");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) throw new InvalidManifestException("settings entries must be objects");
            String title = reqString(e, "title", MAX_TITLE_LEN);
            String subtitle = optLimited(e, "subtitle", MAX_SUBTITLE_LEN);
            String target = null;
            if (e.has("target") && !e.isNull("target")) {
                target = e.optString("target", "").trim();
                if (!PACK_ID_PATTERN.matcher(target).matches()) {
                    throw new InvalidManifestException("invalid settings target: " + target);
                }
            }
            out.add(new SettingsEntry(title, subtitle, target));
        }
        return out;
    }

    /** contributes.languages: text packs the user can switch the app to. */
    private static List<LanguagePack> parseLanguages(JSONObject contributes) throws InvalidManifestException {
        List<LanguagePack> out = new ArrayList<>();
        JSONArray arr = contributes == null ? null : contributes.optJSONArray("languages");
        if (arr == null) return out;
        if (arr.length() > 8) throw new InvalidManifestException("too many language packs");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) throw new InvalidManifestException("languages entries must be objects");
            String code = e.optString("code", "").trim();
            if (!LANG_CODE_PATTERN.matcher(code).matches()) {
                throw new InvalidManifestException("invalid language code: " + code);
            }
            if (!seen.add(code.toLowerCase(Locale.ROOT))) {
                throw new InvalidManifestException("duplicate language code: " + code);
            }
            String name = reqString(e, "name", MAX_TITLE_LEN);
            String file = relPath(e.optString("file", ""), "languages.file");
            if (!file.toLowerCase(Locale.ROOT).endsWith(".json")) {
                throw new InvalidManifestException("language pack must be a .json file");
            }
            out.add(new LanguagePack(code, name, file));
        }
        return out;
    }

    /** contributes.themes: accent seed colours for the app's Material 3 engine. */
    private static List<ThemePack> parseThemes(JSONObject contributes) throws InvalidManifestException {
        List<ThemePack> out = new ArrayList<>();
        JSONArray arr = contributes == null ? null : contributes.optJSONArray("themes");
        if (arr == null) return out;
        if (arr.length() > 8) throw new InvalidManifestException("too many themes");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) throw new InvalidManifestException("themes entries must be objects");
            String themeId = e.optString("id", "").trim();
            if (!PACK_ID_PATTERN.matcher(themeId).matches()) {
                throw new InvalidManifestException("invalid theme id: " + themeId);
            }
            if (!seen.add(themeId)) throw new InvalidManifestException("duplicate theme id: " + themeId);
            String name = reqString(e, "name", MAX_TITLE_LEN);
            String color = e.optString("color", "").trim();
            if (!HEX_COLOR_PATTERN.matcher(color).matches()) {
                throw new InvalidManifestException("theme color must look like #RRGGBB");
            }
            // optional full theme: role colours on top of the seed
            java.util.Map<String, Integer> colors = new java.util.LinkedHashMap<>();
            JSONObject overrides = e.optJSONObject("colors");
            if (overrides != null) {
                java.util.Iterator<String> keys = overrides.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!THEME_COLOR_KEYS.contains(key)) {
                        throw new InvalidManifestException("unknown theme color: " + key);
                    }
                    String value = overrides.optString(key, "").trim();
                    if (!HEX_COLOR_PATTERN.matcher(value).matches()) {
                        throw new InvalidManifestException("theme color " + key + " must look like #RRGGBB");
                    }
                    colors.put(key, 0xFF000000 | Integer.parseInt(value.substring(1), 16));
                }
            }
            out.add(new ThemePack(themeId, name, 0xFF000000 | Integer.parseInt(color.substring(1), 16), colors));
        }
        return out;
    }

    /** contributes.ui: hide app elements and add console quick commands. */
    private static UiTweaks parseUiTweaks(JSONObject contributes) throws InvalidManifestException {
        List<String> hide = new ArrayList<>();
        List<String> chips = new ArrayList<>();
        JSONObject ui = contributes == null ? null : contributes.optJSONObject("ui");
        if (ui == null) return new UiTweaks(hide, chips);

        JSONArray hideArr = ui.optJSONArray("hide");
        if (hideArr != null) {
            if (hideArr.length() > 24) throw new InvalidManifestException("too many hidden elements");
            for (int i = 0; i < hideArr.length(); i++) {
                String viewId = hideArr.optString(i, "").trim();
                if (!VIEW_ID_PATTERN.matcher(viewId).matches()) {
                    throw new InvalidManifestException("invalid view id: " + viewId);
                }
                hide.add(viewId);
            }
        }

        JSONArray chipArr = ui.optJSONArray("console_chips");
        if (chipArr != null) {
            if (chipArr.length() > 16) throw new InvalidManifestException("too many console chips");
            for (int i = 0; i < chipArr.length(); i++) {
                String cmd = chipArr.optString(i, "").trim();
                if (cmd.isEmpty() || cmd.length() > 64 || cmd.contains("\n")) {
                    throw new InvalidManifestException("invalid console chip: " + cmd);
                }
                chips.add(cmd);
            }
        }
        return new UiTweaks(hide, chips);
    }

    private static String reqString(JSONObject o, String key, int maxLen) throws InvalidManifestException {
        if (!o.has(key) || o.isNull(key)) {
            throw new InvalidManifestException("missing field: " + key);
        }
        String v = o.optString(key, "").trim();
        if (v.isEmpty()) {
            throw new InvalidManifestException("empty field: " + key);
        }
        if (v.length() > maxLen) {
            throw new InvalidManifestException("field too long: " + key);
        }
        return v;
    }

    private static String optLimited(JSONObject o, String key, int maxLen) throws InvalidManifestException {
        String v = o.optString(key, "").trim();
        if (v.length() > maxLen) {
            throw new InvalidManifestException("field too long: " + key);
        }
        return v;
    }

    private static String optUrl(JSONObject o, String key) throws InvalidManifestException {
        String v = o.optString(key, "").trim();
        if (v.isEmpty()) return null;
        if (v.length() > MAX_URL_LEN) throw new InvalidManifestException("url too long: " + key);
        if (!v.startsWith("https://")) throw new InvalidManifestException(key + " must be an https url");
        return v;
    }

    /** relative path inside the package: no absolute paths, no "..", no backslashes. */
    private static String relPath(String raw, String field) throws InvalidManifestException {
        String v = raw.trim().replace('\\', '/');
        if (v.isEmpty()) throw new InvalidManifestException("missing field: " + field);
        if (v.length() > MAX_PATH_LEN) throw new InvalidManifestException("path too long: " + field);
        if (v.startsWith("/") || v.contains("..") || v.contains(":")) {
            throw new InvalidManifestException("illegal path in " + field);
        }
        return v;
    }

    private static List<String> parsePermissions(JSONArray array) throws InvalidManifestException {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (array == null) return new ArrayList<>(out);
        if (array.length() > MAX_PERMISSIONS) {
            throw new InvalidManifestException("too many permissions");
        }
        for (int i = 0; i < array.length(); i++) {
            String p = array.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) throw new InvalidManifestException("empty permission");
            if (p.startsWith("http:")) {
                String host = p.substring("http:".length());
                if (!HOST_PATTERN.matcher(host).matches()) {
                    throw new InvalidManifestException("invalid http permission: " + p);
                }
                out.add("http:" + host);
            } else if (BASE_PERMISSIONS.contains(p)) {
                out.add(p);
            } else {
                throw new InvalidManifestException("unknown permission: " + p);
            }
        }
        return new ArrayList<>(out);
    }
}
