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

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * language packs extensions bring: JSON files that map string resource names
 * ("jars_title") to translated texts. two mechanisms apply them:
 *
 *  1. a Resources wrapper on the application context (App.attachBaseContext),
 *     so everything that resolves strings through resources is translated, and
 *  2. a view pass on activity resume that translates texts resolved before the
 *     wrapper could apply (belt and braces, so the feature always shows some
 *     visible result).
 *
 * packs are partial by design: every missing key falls back to the app language.
 * the state lives in its own "koda_ext" prefs file, never in App.getPrefs,
 * whose keystore failure path bans the device.
 */
public final class ExtensionLanguagePacks {

    private static final String PREFS = "koda_ext";
    private static final String KEY_ACTIVE = "language_pack"; // "<extensionId>:<code>", empty if unset
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    private static final int MAX_ENTRIES = 3000;
    private static final int MAX_TEXT = 600;

    private static Map<String, String> byKey;      // resource name -> translated text
    private static Map<String, String> byText;     // default text -> translated text (view pass)
    private static boolean active;
    private static boolean resolved;

    private ExtensionLanguagePacks() {}

    // ─── Active pack state ───────────────────────────────────────

    public static String getActive(Context c) {
        return prefs(c).getString(KEY_ACTIVE, "");
    }

    public static void setActive(Context c, String extensionId, String code) {
        prefs(c).edit().putString(KEY_ACTIVE, extensionId + ":" + code).apply();
        resetCaches();
    }

    public static void clear(Context c) {
        prefs(c).edit().putString(KEY_ACTIVE, "").apply();
        resetCaches();
    }

    /** a pack is set and still resolvable, so the extension is installed and enabled. */
    public static synchronized boolean isActive() {
        return active;
    }

    // ─── Application context wrapper ─────────────────────────────

    /**
     * wraps the application context so resource lookups go through the active
     * pack. called from App.attachBaseContext, where the application context is
     * not ready yet, so the pack resolves lazily on the first resource lookup;
     * without a pack the base resources come back unchanged.
     */
    public static Context wrap(Context base) {
        try {
            if (base == null) return null;
            return new WrappedContext(base);
        } catch (Throwable t) {
            return base;
        }
    }

    static String lookup(String resourceName) {
        if (!active || byKey == null || resourceName == null) return null;
        return byKey.get(resourceName);
    }

    /** re-resolves the active pack, call it after install, uninstall or enable changes. */
    public static synchronized void resetCaches() {
        byKey = null;
        byText = null;
        active = false;
        resolved = false;
    }

    private static synchronized void resolve(Context c) {
        if (resolved) return;
        resolved = true;
        try {
            String value = getActive(c);
            int sep = value.indexOf(':');
            if (sep <= 0) return;
            String extensionId = value.substring(0, sep);
            String code = value.substring(sep + 1);

            ExtensionRepository.InstalledExtension ext = ExtensionRepository.get(c).byId(extensionId);
            if (ext == null || !ext.enabled) return;
            ExtensionManifest.LanguagePack pack = null;
            for (ExtensionManifest.LanguagePack p : ext.languages) {
                if (p.code.equals(code)) { pack = p; break; }
            }
            if (pack == null) return;

            File file = new File(ext.dir, pack.file);
            if (!file.isFile()) return;
            byte[] raw = Files.readAllBytes(file.toPath());
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));

            Map<String, String> map = new HashMap<>();
            Iterator<String> keys = o.keys();
            while (keys.hasNext() && map.size() < MAX_ENTRIES) {
                String key = keys.next();
                if (!KEY_PATTERN.matcher(key).matches()) continue;
                String text = o.optString(key, "");
                if (text.isEmpty() || text.length() > MAX_TEXT) continue;
                map.put(key, text);
            }
            if (map.isEmpty()) return;
            byKey = map;
            active = true;
        } catch (Throwable ignored) {
            // a broken pack must never take the app down, it just does not apply
        }
    }

    // ─── View pass (belt and braces) ─────────────────────────────

    /**
     * translates the texts of an already inflated screen. runs on every activity
     * resume while a pack is active; texts missing from the pack stay in the app
     * language.
     */
    public static void overlay(Activity activity) {
        try {
            if (activity == null || !isActive()) return;
            resolve(activity);
            if (!active || byKey == null) return;

            Resources rawRes = rawResources(activity);
            Map<String, String> textMap = byText;
            if (textMap == null) {
                textMap = buildTextMap(rawRes, byKey);
                byText = textMap;
            }

            Deque<View> stack = new ArrayDeque<>();
            stack.push(activity.getWindow().getDecorView());
            while (!stack.isEmpty()) {
                View v = stack.pop();
                if (v instanceof TextView && !(v instanceof EditText)) {
                    translate((TextView) v, rawRes, textMap);
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void translate(TextView tv, Resources rawRes, Map<String, String> textMap) {
        try {
            CharSequence current = tv.getText();
            if (current == null || current.length() == 0) return;
            String translated = null;
            if (tv.getId() != View.NO_ID) {
                try {
                    String name = rawRes.getResourceEntryName(tv.getId());
                    translated = byKey.get(name);
                } catch (Throwable ignored) {}
            }
            if (translated == null) translated = textMap.get(current.toString());
            if (translated != null && !translated.contentEquals(current)) {
                tv.setText(translated);
            }
        } catch (Throwable ignored) {
        }
    }

    /** resources that are not our wrapper, so defaults resolve to the app language. */
    private static Resources rawResources(Context c) {
        try {
            return c.createConfigurationContext(c.getResources().getConfiguration()).getResources();
        } catch (Throwable t) {
            return c.getResources();
        }
    }

    private static Map<String, String> buildTextMap(Resources rawRes, Map<String, String> keyMap) {
        Map<String, String> out = new HashMap<>();
        String pkg = "eu.kodanetwork.mchost";
        for (Map.Entry<String, String> e : keyMap.entrySet()) {
            try {
                int id = rawRes.getIdentifier(e.getKey(), "string", pkg);
                if (id == 0) continue;
                String def = rawRes.getString(id);
                if (def != null && !def.isEmpty() && !def.equals(e.getValue())) {
                    out.put(def, e.getValue());
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        Context base = c;
        try {
            Context app = c.getApplicationContext();
            if (app != null) base = app;
        } catch (Throwable ignored) {}
        if (base == null) base = c;
        return base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Wraps a context so its resources resolve through the active pack. */
    private static final class WrappedContext extends ContextWrapper {
        private Resources resources;

        WrappedContext(Context base) { super(base); }

        @Override public Resources getResources() {
            if (resources == null) {
                Resources baseRes = super.getResources();
                try {
                    resolve(getBaseContext());
                    resources = active ? new ExtensionResources(baseRes) : baseRes;
                } catch (Throwable t) {
                    resources = baseRes;
                }
            }
            return resources;
        }
    }

    /** locale for formatting translated strings that take arguments. */
    static Locale formatLocale(Resources res) {
        try {
            Locale l = res.getConfiguration().getLocales().get(0);
            if (l != null) return l;
        } catch (Throwable ignored) {}
        return Locale.getDefault();
    }
}
