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

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;

/**
 * the themes screen behind the Appearance card in the settings. lists the app's
 * own standard colours plus every theme an enabled extension brings, each with
 * its own switch. the extension actions sheet shows the same rows, so a theme
 * flips the same way wherever the user finds it.
 */
public class ExtensionThemesActivity extends AppCompatActivity {

    private LinearLayout list;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extension_themes);
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);

        d = getResources().getDisplayMetrics().density;
        list = findViewById(R.id.ll_theme_list);
        findViewById(R.id.btn_back_themes).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            finish();
        });
        render();
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();

        // the app's own colours
        String activePack = ExtensionThemes.getActive(this);
        boolean standard = activePack.isEmpty();
        list.addView(standardRow(standard));
        list.addView(label(getString(R.string.ext_themes_hint)));

        // themes from every enabled extension
        List<ExtensionRepository.InstalledExtension> extensions = ExtensionRepository.get(this).all();
        int shown = 0;
        for (final ExtensionRepository.InstalledExtension ext : extensions) {
            if (!ext.enabled) continue;
            for (final ExtensionManifest.ThemePack theme : ext.themes) {
                list.addView(ExtensionThemeRows.build(this, ext, theme,
                        ExtensionThemeRows.isThemeActive(this, ext, theme), d, this::render));
                shown++;
            }
        }
        if (shown == 0) {
            list.addView(label(getString(R.string.ext_themes_empty)));
        }
    }

    /** row for the app's own colour mode, it switches any extension theme off. */
    private View standardRow(boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * d), 0, (int) (10 * d));

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = (int) (10 * d);
        dots.setLayoutParams(dlp);
        for (int color : new int[]{0xFFFF6B00, 0xFF12121A, 0xFF241C18}) {
            View dot = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d));
            slp.rightMargin = (int) (3 * d);
            dot.setLayoutParams(slp);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            dot.setBackground(circle);
            dots.addView(dot);
        }
        row.addView(dots);

        TextView name = new TextView(this);
        name.setText(getString(R.string.ext_theme_standard));
        name.setTextColor(0xFFE8E2D6);
        name.setTextSize(14);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setChecked(active);
        sw.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) return; // the standard row only switches back
            HapticUtil.forceVibrate(this, 30);
            ExtensionThemes.clear(this);
            eu.kodanetwork.mchost.App.getPrefs(this).edit()
                    .putString("m3_color_mode", "dynamic")
                    .apply();
            android.widget.Toast.makeText(this, R.string.ext_restart_needed, android.widget.Toast.LENGTH_LONG).show();
            render();
        });
        row.addView(sw);
        return row;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF8A8A99);
        tv.setTextSize(12);
        tv.setPadding(0, (int) (14 * d), 0, (int) (4 * d));
        return tv;
    }
}
