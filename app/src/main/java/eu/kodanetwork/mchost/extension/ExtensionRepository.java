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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * the local list of installed app extensions.
 *
 * layout: filesDir/extensions/&lt;id&gt;/&lt;versionCode&gt;/ holds the package files and
 * filesDir/extensions/&lt;id&gt;/data/ the per-extension storage, which survives
 * updates. the installed state lives in its own extensions/installed.json and
 * has to stay out of ServerRepo/koda_v3 (the hash check would flag it as
 * tampering) and out of App.getPrefs (its keystore failure path bans the device).
 */
public class ExtensionRepository {

    public static final class InstalledExtension {
        public String id = "";
        public String name = "";
        public String version = "";
        public int versionCode = 0;
        public String author = "";
        public String description = "";
        public String license = "";
        public String sourceUrl = "";
        public String uiTitle = "";
        public String uiEntry = null;
        public String automationsFile = null;
        public String iconFile = null;
        public final List<String> permissions = new ArrayList<>();
        public boolean enabled = true;
        public long installedAt = 0L;
        /** language packs the extension contributes (contributes.languages) */
        public final List<ExtensionManifest.LanguagePack> languages = new ArrayList<>();
        /** theme packs the extension contributes (contributes.themes) */
        public final List<ExtensionManifest.ThemePack> themes = new ArrayList<>();
        /** settings rows this extension adds (contributes.settings) */
        public final List<ExtensionManifest.SettingsEntry> settingsEntries = new ArrayList<>();
        /** tabs this extension adds to the server screen (contributes.server_tabs) */
        public final List<ExtensionManifest.ServerTab> serverTabs = new ArrayList<>();
        /** app view ids this extension hides (contributes.ui.hide) */
        public final List<String> hideViewIds = new ArrayList<>();
        /** extra console quick commands (contributes.ui.console_chips) */
        public final List<String> consoleChips = new ArrayList<>();
        /** version directory with the package files, set by the repository */
        public File dir;
        /** per-extension data directory, set by the repository */
        public File dataDir;

        public boolean hasUi() { return uiEntry != null && !uiEntry.isEmpty(); }
        public boolean hasAutomations() { return automationsFile != null && !automationsFile.isEmpty(); }
        public boolean hasPermission(String permission) { return permissions.contains(permission); }

        public boolean hasContributions() {
            return !languages.isEmpty() || !themes.isEmpty() || !settingsEntries.isEmpty()
                    || !hideViewIds.isEmpty() || !consoleChips.isEmpty();
        }
    }

    /** one settings row of an enabled extension, resolved for the settings screen. */
    public static final class SettingsEntryRef {
        public final InstalledExtension extension;
        public final ExtensionManifest.SettingsEntry entry;
        SettingsEntryRef(InstalledExtension extension, ExtensionManifest.SettingsEntry entry) {
            this.extension = extension;
            this.entry = entry;
        }
    }

    /** a server tab of an enabled extension, resolved for the server screen. */
    public static final class ServerTabRef {
        public final InstalledExtension extension;
        public final ExtensionManifest.ServerTab tab;
        ServerTabRef(InstalledExtension extension, ExtensionManifest.ServerTab tab) {
            this.extension = extension;
            this.tab = tab;
        }
        /** the page to show in this tab (own entry, otherwise the extension page) */
        public String entry() {
            return tab.entry != null ? tab.entry : extension.uiEntry;
        }
    }

    /** a language pack of one installed extension, resolved for the UI or the runtime. */
    public static final class LanguageRef {
        public final String extensionId;
        public final String extensionName;
        public final ExtensionManifest.LanguagePack pack;
        LanguageRef(String extensionId, String extensionName, ExtensionManifest.LanguagePack pack) {
            this.extensionId = extensionId;
            this.extensionName = extensionName;
            this.pack = pack;
        }
    }

    /** a theme of one installed extension. */
    public static final class ThemeRef {
        public final String extensionId;
        public final String extensionName;
        public final ExtensionManifest.ThemePack pack;
        ThemeRef(String extensionId, String extensionName, ExtensionManifest.ThemePack pack) {
            this.extensionId = extensionId;
            this.extensionName = extensionName;
            this.pack = pack;
        }
    }

    private static ExtensionRepository instance;

    private final Context ctx;
    private final List<InstalledExtension> list = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized ExtensionRepository get(Context c) {
        if (instance == null) {
            Context app = c.getApplicationContext();
            // during Application.attachBaseContext the application context is not ready yet
            instance = new ExtensionRepository(app != null ? app : c);
        }
        return instance;
    }

    private ExtensionRepository(Context ctx) {
        this.ctx = ctx;
        load();
    }

    public File root() { return new File(ctx.getFilesDir(), "extensions"); }

    public File dirFor(String id, int versionCode) { return new File(root(), id + "/" + versionCode); }

    public File dataDirFor(String id) { return new File(root(), id + "/data"); }

    private File stateFile() { return new File(root(), "installed.json"); }

    public List<InstalledExtension> all() {
        synchronized (list) { return new ArrayList<>(list); }
    }

    public InstalledExtension byId(String id) {
        synchronized (list) { return byIdInternal(id); }
    }

    private InstalledExtension byIdInternal(String id) {
        for (InstalledExtension e : list) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    public void addListener(Runnable r) { listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    // ─── Contributions of all ENABLED extensions ─────────────────

    public List<LanguageRef> enabledLanguages() {
        List<LanguageRef> out = new ArrayList<>();
        for (InstalledExtension e : all()) {
            if (!e.enabled) continue;
            for (ExtensionManifest.LanguagePack p : e.languages) out.add(new LanguageRef(e.id, e.name, p));
        }
        return out;
    }

    public List<ThemeRef> enabledThemes() {
        List<ThemeRef> out = new ArrayList<>();
        for (InstalledExtension e : all()) {
            if (!e.enabled) continue;
            for (ExtensionManifest.ThemePack p : e.themes) out.add(new ThemeRef(e.id, e.name, p));
        }
        return out;
    }

    /** server tabs contributed by all enabled extensions, in install order. */
    public List<ServerTabRef> serverTabs() {
        List<ServerTabRef> out = new ArrayList<>();
        for (InstalledExtension e : all()) {
            if (!e.enabled || e.uiEntry == null) continue;
            for (ExtensionManifest.ServerTab tab : e.serverTabs) out.add(new ServerTabRef(e, tab));
        }
        return out;
    }

    /** settings rows contributed by all enabled extensions, in install order. */
    public List<SettingsEntryRef> settingsEntries() {
        List<SettingsEntryRef> out = new ArrayList<>();
        for (InstalledExtension e : all()) {
            if (!e.enabled) continue;
            for (ExtensionManifest.SettingsEntry entry : e.settingsEntries) out.add(new SettingsEntryRef(e, entry));
        }
        return out;
    }

    /** all view ids that enabled extensions want hidden. */
    public java.util.Set<String> hiddenViewIds() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (InstalledExtension e : all()) {
            if (e.enabled) out.addAll(e.hideViewIds);
        }
        return out;
    }

    /** extra console quick commands of all enabled extensions. */
    public List<String> consoleChips() {
        List<String> out = new ArrayList<>();
        for (InstalledExtension e : all()) {
            if (!e.enabled) continue;
            for (String c : e.consoleChips) {
                if (!out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    /** installs or updates from a .kodaext package, data of an existing install is kept. */
    public InstalledExtension installFromZip(File zip, int appVersionCode) throws ExtensionInstaller.InstallException {
        ExtensionManifest mf = ExtensionInstaller.readManifest(zip, appVersionCode);
        synchronized (list) {
            InstalledExtension prev = byIdInternal(mf.id);
            if (prev != null && mf.versionCode < prev.versionCode) {
                throw new ExtensionInstaller.InstallException("newer version already installed");
            }

            File tmp = new File(root(), ".tmp-" + mf.id);
            File target = dirFor(mf.id, mf.versionCode);
            deleteRecursive(tmp);
            deleteRecursive(target);
            ExtensionInstaller.extract(zip, tmp);

            if (mf.uiEntry != null && !new File(tmp, mf.uiEntry).isFile()) {
                deleteRecursive(tmp);
                throw new ExtensionInstaller.InstallException("ui.entry not found in package");
            }
            if (mf.automationsFile != null && !new File(tmp, mf.automationsFile).isFile()) {
                deleteRecursive(tmp);
                throw new ExtensionInstaller.InstallException("automations file not found in package");
            }
            if (mf.languages != null) {
                for (ExtensionManifest.LanguagePack pack : mf.languages) {
                    if (pack.file == null || pack.file.isEmpty() || !new File(tmp, pack.file).isFile()) {
                        deleteRecursive(tmp);
                        throw new ExtensionInstaller.InstallException("language pack not found in package: " + pack.file);
                    }
                }
            }

            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!tmp.renameTo(target)) {
                deleteRecursive(tmp);
                throw new ExtensionInstaller.InstallException("could not store extension");
            }

            // keep only the newest version directory, the data directory always survives
            File idDir = new File(root(), mf.id);
            File[] children = idDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !child.getName().equals("data")
                            && !child.getName().equals(String.valueOf(mf.versionCode))) {
                        deleteRecursive(child);
                    }
                }
            }

            InstalledExtension ext = prev != null ? prev : new InstalledExtension();
            applyManifest(ext, mf);
            if (prev == null) {
                ext.enabled = true;
                ext.installedAt = System.currentTimeMillis();
                list.add(ext);
            }
            save();
            notifyListeners();
            return ext;
        }
    }

    /** toggled from the list row itself, so this does not ping every listener. */
    public void setEnabled(String id, boolean enabled) {
        synchronized (list) {
            InstalledExtension e = byIdInternal(id);
            if (e == null || e.enabled == enabled) return;
            e.enabled = enabled;
            save();
        }
    }

    /** removes the extension including all of its data. */
    public void uninstall(String id) {
        synchronized (list) {
            InstalledExtension e = byIdInternal(id);
            if (e == null) return;
            list.remove(e);
            deleteRecursive(new File(root(), id));
            save();
            notifyListeners();
        }
    }

    private void applyManifest(InstalledExtension e, ExtensionManifest mf) {
        e.id = mf.id;
        e.name = mf.name;
        e.version = mf.version;
        e.versionCode = mf.versionCode;
        e.author = mf.author;
        e.description = mf.description;
        e.license = mf.license;
        e.sourceUrl = mf.sourceUrl == null ? "" : mf.sourceUrl;
        e.uiTitle = mf.uiTitle;
        e.uiEntry = mf.uiEntry;
        e.automationsFile = mf.automationsFile;
        e.iconFile = mf.iconFile;
        e.permissions.clear();
        e.permissions.addAll(mf.permissions);
        e.languages.clear();
        if (mf.languages != null) e.languages.addAll(mf.languages);
        e.themes.clear();
        if (mf.themes != null) e.themes.addAll(mf.themes);
        e.settingsEntries.clear();
        if (mf.settingsEntries != null) e.settingsEntries.addAll(mf.settingsEntries);
        e.serverTabs.clear();
        if (mf.serverTabs != null) e.serverTabs.addAll(mf.serverTabs);
        e.hideViewIds.clear();
        e.consoleChips.clear();
        if (mf.uiTweaks != null) {
            e.hideViewIds.addAll(mf.uiTweaks.hideViewIds);
            e.consoleChips.addAll(mf.uiTweaks.consoleChips);
        }
        e.dir = dirFor(e.id, e.versionCode);
        e.dataDir = dataDirFor(e.id);
    }

    private void load() {
        synchronized (list) {
            list.clear();
            File f = stateFile();
            if (!f.isFile()) return;
            try {
                byte[] raw = Files.readAllBytes(f.toPath());
                JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                JSONArray arr = o.optJSONArray("extensions");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    InstalledExtension ext = new InstalledExtension();
                    ext.id = e.optString("id", "");
                    ext.name = e.optString("name", "");
                    ext.version = e.optString("version", "");
                    ext.versionCode = e.optInt("versionCode", 0);
                    ext.author = e.optString("author", "");
                    ext.description = e.optString("description", "");
                    ext.license = e.optString("license", "");
                    ext.sourceUrl = e.optString("sourceUrl", "");
                    ext.uiTitle = e.optString("uiTitle", "");
                    ext.uiEntry = e.isNull("uiEntry") ? null : e.optString("uiEntry", null);
                    ext.automationsFile = e.isNull("automationsFile") ? null : e.optString("automationsFile", null);
                    ext.iconFile = e.isNull("iconFile") ? null : e.optString("iconFile", null);
                    ext.enabled = e.optBoolean("enabled", true);
                    ext.installedAt = e.optLong("installedAt", 0L);
                    JSONArray perms = e.optJSONArray("permissions");
                    if (perms != null) {
                        for (int k = 0; k < perms.length(); k++) ext.permissions.add(perms.optString(k, ""));
                    }
                    JSONArray langs = e.optJSONArray("languages");
                    if (langs != null) {
                        for (int k = 0; k < langs.length(); k++) {
                            JSONObject l = langs.optJSONObject(k);
                            if (l != null) ext.languages.add(new ExtensionManifest.LanguagePack(
                                    l.optString("code", ""), l.optString("name", ""), l.optString("file", "")));
                        }
                    }
                    JSONArray themes = e.optJSONArray("themes");
                    if (themes != null) {
                        for (int k = 0; k < themes.length(); k++) {
                            JSONObject t = themes.optJSONObject(k);
                            if (t == null) continue;
                            java.util.Map<String, Integer> colors = new java.util.LinkedHashMap<>();
                            JSONObject colorObject = t.optJSONObject("colors");
                            if (colorObject != null) {
                                java.util.Iterator<String> it = colorObject.keys();
                                while (it.hasNext()) {
                                    String key = it.next();
                                    String hex = colorObject.optString(key, "");
                                    if (hex.startsWith("#") && hex.length() == 7) {
                                        try {
                                            colors.put(key, 0xFF000000 | Integer.parseInt(hex.substring(1), 16));
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                            ext.themes.add(new ExtensionManifest.ThemePack(
                                    t.optString("id", ""), t.optString("name", ""), t.optInt("color", 0xFF6B00), colors));
                        }
                    }
                    JSONArray serverTabs = e.optJSONArray("serverTabs");
                    if (serverTabs != null) {
                        for (int k = 0; k < serverTabs.length(); k++) {
                            JSONObject t = serverTabs.optJSONObject(k);
                            if (t != null) ext.serverTabs.add(new ExtensionManifest.ServerTab(
                                    t.optString("id", ""), t.optString("title", ""),
                                    t.isNull("entry") ? null : t.optString("entry", null),
                                    t.isNull("target") ? null : t.optString("target", null)));
                        }
                    }
                    JSONArray settings = e.optJSONArray("settings");
                    if (settings != null) {
                        for (int k = 0; k < settings.length(); k++) {
                            JSONObject s = settings.optJSONObject(k);
                            if (s != null) ext.settingsEntries.add(new ExtensionManifest.SettingsEntry(
                                    s.optString("title", ""), s.optString("subtitle", ""),
                                    s.isNull("target") ? null : s.optString("target", null)));
                        }
                    }
                    JSONArray hides = e.optJSONArray("hideViewIds");
                    if (hides != null) {
                        for (int k = 0; k < hides.length(); k++) ext.hideViewIds.add(hides.optString(k, ""));
                    }
                    JSONArray chips = e.optJSONArray("consoleChips");
                    if (chips != null) {
                        for (int k = 0; k < chips.length(); k++) ext.consoleChips.add(chips.optString(k, ""));
                    }
                    ext.dir = dirFor(ext.id, ext.versionCode);
                    ext.dataDir = dataDirFor(ext.id);
                    // drop entries whose files disappeared
                    if (!ext.id.isEmpty() && ext.dir.isDirectory()) list.add(ext);
                }
            } catch (Throwable ignored) {
                // a broken state file must never take the app down
            }
        }
    }

    private void save() {
        synchronized (list) {
            try {
                File f = stateFile();
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                JSONObject o = new JSONObject();
                o.put("schema", 1);
                JSONArray arr = new JSONArray();
                for (InstalledExtension e : list) {
                    JSONObject j = new JSONObject();
                    j.put("id", e.id);
                    j.put("name", e.name);
                    j.put("version", e.version);
                    j.put("versionCode", e.versionCode);
                    j.put("author", e.author);
                    j.put("description", e.description);
                    j.put("license", e.license);
                    j.put("sourceUrl", e.sourceUrl);
                    j.put("uiTitle", e.uiTitle);
                    j.put("uiEntry", e.uiEntry == null ? JSONObject.NULL : e.uiEntry);
                    j.put("automationsFile", e.automationsFile == null ? JSONObject.NULL : e.automationsFile);
                    j.put("iconFile", e.iconFile == null ? JSONObject.NULL : e.iconFile);
                    j.put("permissions", new JSONArray(e.permissions));
                    JSONArray langs = new JSONArray();
                    for (ExtensionManifest.LanguagePack p : e.languages) {
                        langs.put(new JSONObject().put("code", p.code).put("name", p.name).put("file", p.file));
                    }
                    j.put("languages", langs);
                    JSONArray themes = new JSONArray();
                    for (ExtensionManifest.ThemePack p : e.themes) {
                        JSONObject t = new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color);
                        if (p.colors != null && !p.colors.isEmpty()) {
                            JSONObject colors = new JSONObject();
                            for (java.util.Map.Entry<String, Integer> c : p.colors.entrySet()) {
                                colors.put(c.getKey(), String.format("#%06X", 0xFFFFFF & c.getValue()));
                            }
                            t.put("colors", colors);
                        }
                        themes.put(t);
                    }
                    j.put("themes", themes);
                    JSONArray serverTabs = new JSONArray();
                    for (ExtensionManifest.ServerTab t : e.serverTabs) {
                        serverTabs.put(new JSONObject()
                                .put("id", t.id).put("title", t.title)
                                .put("entry", t.entry == null ? JSONObject.NULL : t.entry)
                                .put("target", t.target == null ? JSONObject.NULL : t.target));
                    }
                    j.put("serverTabs", serverTabs);
                    JSONArray settings = new JSONArray();
                    for (ExtensionManifest.SettingsEntry s : e.settingsEntries) {
                        settings.put(new JSONObject()
                                .put("title", s.title)
                                .put("subtitle", s.subtitle == null ? "" : s.subtitle)
                                .put("target", s.target == null ? JSONObject.NULL : s.target));
                    }
                    j.put("settings", settings);
                    j.put("hideViewIds", new JSONArray(e.hideViewIds));
                    j.put("consoleChips", new JSONArray(e.consoleChips));
                    j.put("enabled", e.enabled);
                    j.put("installedAt", e.installedAt);
                    arr.put(j);
                }
                o.put("extensions", arr);

                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    tmp.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Runnable r : listeners) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }
}
