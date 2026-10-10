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
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;

/**
 * one theme row: palette preview, name and its own switch. the extension
 * actions sheet and the themes screen under Appearance both use this, so a
 * theme flips the same way wherever the user finds it.
 */
public final class ExtensionThemeRows {

    public interface OnChanged {
        /** fires after a switch flip, so the caller can refresh or dismiss itself. */
        void onThemeChanged();
    }

    private ExtensionThemeRows() {}

    /** is this theme on right now, as a full theme pack or as the app's own seed? */
    public static boolean isThemeActive(Activity a, ExtensionRepository.InstalledExtension ext,
                                        ExtensionManifest.ThemePack theme) {
        if (ExtensionThemes.isActive(a, ext.id, theme.id)) return true;
        if (!theme.colors.isEmpty()) return false;
        SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(a);
        return prefs.getInt("m3_custom_color", 0) == theme.color
                && "custom".equals(prefs.getString("m3_color_mode", "dynamic"));
    }

    public static View build(final Activity a, final ExtensionRepository.InstalledExtension ext,
                             final ExtensionManifest.ThemePack theme, boolean active, float d,
                             final OnChanged onChanged) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * d), 0, (int) (10 * d));

        LinearLayout dots = new LinearLayout(a);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = (int) (10 * d);
        dots.setLayoutParams(dlp);
        int[] preview = theme.colors.isEmpty()
                ? new int[]{theme.color, theme.color, theme.color}
                : new int[]{palette(theme, "primary"), palette(theme, "surface"),
                            palette(theme, "card"), palette(theme, "text")};
        for (int color : preview) {
            View dot = new View(a);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d));
            slp.rightMargin = (int) (3 * d);
            dot.setLayoutParams(slp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(color);
            dot.setBackground(circle);
            dots.addView(dot);
        }
        row.addView(dots);

        TextView name = new TextView(a);
        name.setText(theme.name);
        name.setTextColor(0xFFE8E2D6);
        name.setTextSize(14);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        SwitchMaterial sw = new SwitchMaterial(a);
        sw.setChecked(active);
        sw.setOnCheckedChangeListener((button, checked) -> {
            HapticUtil.forceVibrate(a, 30);
            ExtensionRepository.InstalledExtension target = ext;
            if (target != null) ensureEnabled(a, target);
            if (checked) {
                if (theme.colors.isEmpty()) {
                    // accent only: hand it to the app's own Material 3 seed
                    eu.kodanetwork.mchost.App.getPrefs(a).edit()
                            .putString("m3_color_mode", "custom")
                            .putInt("m3_custom_color", theme.color)
                            .apply();
                    ExtensionThemes.clear(a);
                } else {
                    ExtensionThemes.setActive(a, target.id, theme.id);
                }
            } else if (target != null && isThemeActive(a, target, theme)) {
                ExtensionThemes.clear(a);
                eu.kodanetwork.mchost.App.getPrefs(a).edit()
                        .putString("m3_color_mode", "dynamic")
                        .apply();
            }
            Toast.makeText(a, R.string.ext_restart_needed, Toast.LENGTH_LONG).show();
            if (onChanged != null) onChanged.onThemeChanged();
        });
        row.addView(sw);
        return row;
    }

    private static int palette(ExtensionManifest.ThemePack theme, String key) {
        Integer color = theme.colors.get(key);
        return color != null ? color : theme.color;
    }

    /** a disabled extension contributes nothing, so applying its pack turns it on. */
    public static void ensureEnabled(Activity a, ExtensionRepository.InstalledExtension ext) {
        try {
            if (ext.enabled) return;
            ExtensionRepository.get(a).setEnabled(ext.id, true);
            ext.enabled = true;
        } catch (Throwable ignored) {}
    }
}
