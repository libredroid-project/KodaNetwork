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

import com.google.android.material.bottomsheet.BottomSheetDialog;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.KodaButtons;

/**
 * actions sheet of one installed extension: apply its language packs, apply
 * its theme, see what its UI tweaks do. opened from the hub (extensions without
 * a screen, or on long press) and from the host menu. same sheet idiom as the
 * rest of the app: KodaBottomSheetDialog, 11sp section labels, Koda buttons.
 */
public final class ExtensionActionsSheet {

    private ExtensionActionsSheet() {}

    public static void show(Activity activity, final ExtensionRepository.InstalledExtension ext) {
        if (activity == null || ext == null) return;
        final float d = activity.getResources().getDisplayMetrics().density;

        final BottomSheetDialog sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        root.setPadding(pad, (int) (18 * d), pad, (int) (18 * d));

        TextView title = new TextView(activity);
        title.setText(ext.name);
        title.setTextColor(0xFFF0F0F0);
        title.setTextSize(16);
        title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.font_koda),
                android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView version = new TextView(activity);
        version.setText("v" + ext.version);
        version.setTextColor(0xFFFF6B00);
        version.setTextSize(12);
        root.addView(version);

        // a disabled extension contributes nothing, applying something turns it back on
        if (!ext.enabled) {
            root.addView(note(activity, activity.getString(R.string.ext_apply_enables), d));
        }

        // ─── Languages ───────────────────────────────────────────
        if (!ext.languages.isEmpty()) {
            root.addView(sectionLabel(activity, activity.getString(R.string.ext_lang_label), d));
            final String activePack = ExtensionLanguagePacks.getActive(activity);
            for (final ExtensionManifest.LanguagePack pack : ext.languages) {
                final boolean isActive = activePack.equals(ext.id + ":" + pack.code);
                root.addView(row(activity, pack.name + "  (" + pack.code + ")",
                        activity.getString(isActive ? R.string.ext_deactivate : R.string.ext_apply),
                        isActive, d, v -> {
                            if (isActive) {
                                ExtensionLanguagePacks.clear(activity);
                            } else {
                                ExtensionThemeRows.ensureEnabled(activity, ext);
                                ExtensionLanguagePacks.setActive(activity, ext.id, pack.code);
                            }
                            sheet.dismiss();
                            restartToast(activity);
                        }));
            }
            TextView note = note(activity, activity.getString(R.string.ext_lang_partial_note), d);
            root.addView(note);
        }

        // ─── Themes, each with its own switch ───────────────────
        if (!ext.themes.isEmpty()) {
            root.addView(sectionLabel(activity, activity.getString(R.string.ext_theme_label), d));
            root.addView(note(activity, activity.getString(R.string.ext_theme_m3_note), d));
            for (final ExtensionManifest.ThemePack theme : ext.themes) {
                root.addView(ExtensionThemeRows.build(activity, ext, theme,
                        ExtensionThemeRows.isThemeActive(activity, ext, theme), d, sheet::dismiss));
            }
        }

        // ─── UI tweaks (active as long as the extension is enabled) ──
        if (!ext.hideViewIds.isEmpty() || !ext.consoleChips.isEmpty()) {
            root.addView(sectionLabel(activity, activity.getString(R.string.ext_ui_label), d));
            if (!ext.hideViewIds.isEmpty()) {
                root.addView(note(activity, activity.getString(
                        R.string.ext_ui_hidden_count, ext.hideViewIds.size()), d));
            }
            if (!ext.consoleChips.isEmpty()) {
                root.addView(note(activity, activity.getString(
                        R.string.ext_ui_chips_count, ext.consoleChips.size()), d));
            }
        }

        sheet.setContentView(root);
        eu.kodanetwork.mchost.util.SheetFix.apply(sheet);
        sheet.show();
        eu.kodanetwork.mchost.util.ThemeHelper.apply(sheet);
    }

    private static TextView sectionLabel(Activity a, String text, float d) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(0xFFFF6B00);
        tv.setTextSize(11);
        tv.setLetterSpacing(0.1f);
        tv.setPadding(0, (int) (18 * d), 0, (int) (6 * d));
        return tv;
    }

    private static TextView note(Activity a, String text, float d) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(0xFF8A8A9A);
        tv.setTextSize(12);
        tv.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        return tv;
    }

    /** a label with an action button next to it. */
    private static View row(Activity a, String label, String buttonText, boolean active, float d,
                            View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));

        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextColor(0xFFE8E2D6);
        tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        com.google.android.material.button.MaterialButton btn = active
                ? KodaButtons.dark(a, buttonText)
                : KodaButtons.primary(a, buttonText);
        btn.setOnClickListener(v -> {
            HapticUtil.forceVibrate(a, 30);
            onClick.onClick(v);
        });
        row.addView(btn);
        return row;
    }

    private static void restartToast(Activity activity) {
        Toast.makeText(activity, R.string.ext_restart_needed, Toast.LENGTH_LONG).show();
    }
}
