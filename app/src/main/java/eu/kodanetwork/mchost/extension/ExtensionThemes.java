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
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;

/**
 * theme packs extensions bring: a seed colour that goes into the app's
 * Material 3 engine, plus optional per-role colour overrides. only one
 * extension theme is active at a time and the state lives in the "koda_ext"
 * prefs, never in App.getPrefs, whose keystore failure path bans the device.
 * the Material 3 helper reads the colours through this class.
 */
public final class ExtensionThemes {

    private static final String PREFS = "koda_ext";
    private static final String KEY_ACTIVE = "theme_pack"; // "<extensionId>:<themeId>", empty if unset

    private ExtensionThemes() {}

    public static String getActive(Context c) {
        return prefs(c).getString(KEY_ACTIVE, "");
    }

    public static void setActive(Context c, String extensionId, String themeId) {
        prefs(c).edit().putString(KEY_ACTIVE, extensionId + ":" + themeId).apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().putString(KEY_ACTIVE, "").apply();
    }

    public static boolean isActive(Context c, String extensionId, String themeId) {
        return getActive(c).equals(extensionId + ":" + themeId);
    }

    /** the theme that is really active, null when none is set or it is gone. */
    public static ExtensionManifest.ThemePack active(Context c) {
        try {
            String value = getActive(c);
            int separator = value.indexOf(':');
            if (separator <= 0) return null;
            String extensionId = value.substring(0, separator);
            String themeId = value.substring(separator + 1);

            ExtensionRepository.InstalledExtension ext = ExtensionRepository.get(c).byId(extensionId);
            if (ext == null || !ext.enabled) return null;
            for (ExtensionManifest.ThemePack pack : ext.themes) {
                if (pack.id.equals(themeId)) return pack;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** per-role overrides of the active theme, empty when there is no theme. */
    public static Map<String, Integer> activeColors(Context c) {
        ExtensionManifest.ThemePack pack = active(c);
        if (pack == null || pack.colors == null) return Collections.emptyMap();
        return pack.colors;
    }

    /** the accent colour extensions bring, or {@code fallback}. */
    public static int activeAccent(Context c, int fallback) {
        Map<String, Integer> colors = activeColors(c);
        Integer primary = colors.get("primary");
        if (primary != null) return primary;
        ExtensionManifest.ThemePack pack = active(c);
        return pack != null ? pack.color : fallback;
    }

    private static SharedPreferences prefs(Context c) {
        Context base = c;
        try {
            Context app = c.getApplicationContext();
            if (app != null) base = app;
        } catch (Throwable ignored) {
        }
        if (base == null) base = c;
        return base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
