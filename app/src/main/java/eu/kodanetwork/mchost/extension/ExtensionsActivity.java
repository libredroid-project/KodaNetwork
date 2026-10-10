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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;

import eu.kodanetwork.mchost.BuildConfig;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;

/**
 * hub for app extensions: the list of installed ones plus the discover pane that
 * will hold the store in a later phase. new packages come in through the system
 * file picker (.kodaext sideload).
 */
public class ExtensionsActivity extends AppCompatActivity {

    private static final int REQ_INSTALL = 8801;

    private ExtensionRepository repo;
    private LinearLayout llInstalled;
    private TextView tvEmptyInstalled;
    private ScrollView svInstalled;
    private ScrollView svStore;
    private boolean m3 = false;
    private int tab = 0;

    private final Runnable refreshListener = this::refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        m3 = eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this);
        setContentView(m3 ? R.layout.activity_extensions_m3 : R.layout.activity_extensions);
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);

        repo = ExtensionRepository.get(this);

        findViewById(R.id.btn_back_ext).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            finish();
        });
        findViewById(R.id.btn_add_ext).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            pickPackage();
        });

        llInstalled = findViewById(R.id.ll_extensions);
        tvEmptyInstalled = findViewById(R.id.tv_ext_empty);
        svInstalled = findViewById(R.id.sv_ext_installed);
        svStore = findViewById(R.id.sv_ext_store);

        View tabs = makeTextTabs(
                new String[]{getString(R.string.ext_tab_installed), getString(R.string.ext_tab_discover)},
                0, sel -> {
                    tab = sel;
                    applyTab();
                });
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * density));
        tabsLp.topMargin = (int) (4 * density);
        ((LinearLayout) findViewById(R.id.ll_ext_content)).addView(tabs, 0, tabsLp);

        repo.addListener(refreshListener);
        refresh();
    }

    private void applyTab() {
        svInstalled.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        svStore.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        if (llInstalled == null) return;
        llInstalled.removeAllViews();
        java.util.List<ExtensionRepository.InstalledExtension> list = repo.all();
        for (final ExtensionRepository.InstalledExtension ext : list) {
            View row = getLayoutInflater().inflate(
                    m3 ? R.layout.item_extension_m3 : R.layout.item_extension, llInstalled, false);

            ImageView ivIcon = row.findViewById(R.id.iv_ext_icon);
            File icon = ext.iconFile != null ? new File(ext.dir, ext.iconFile) : new File(ext.dir, "icon.png");
            if (icon.isFile()) {
                try {
                    com.bumptech.glide.Glide.with(this).load(icon).into(ivIcon);
                } catch (Throwable ignored) {}
            }

            ((TextView) row.findViewById(R.id.tv_ext_name)).setText(ext.name);

            TextView tvDesc = row.findViewById(R.id.tv_ext_desc);
            if (ext.description == null || ext.description.isEmpty()) {
                tvDesc.setVisibility(View.GONE);
            } else {
                tvDesc.setText(ext.description);
            }

            ((TextView) row.findViewById(R.id.tv_ext_meta)).setText(
                    "v" + ext.version + (ext.author == null || ext.author.isEmpty() ? "" : "  \u00B7  " + ext.author));

            SwitchMaterial sw = row.findViewById(R.id.switch_ext_enabled);
            sw.setChecked(ext.enabled);
            sw.setOnCheckedChangeListener((b, checked) -> {
                HapticUtil.forceVibrate(this, 30);
                repo.setEnabled(ext.id, checked);
            });

            MaterialButton btnRemove = row.findViewById(R.id.btn_ext_remove);
            btnRemove.setOnClickListener(v -> confirmRemove(ext));

            row.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 30);
                if (ext.hasUi()) {
                    startActivity(new Intent(this, ExtensionHostActivity.class).putExtra("id", ext.id));
                } else if (ext.hasContributions()) {
                    ExtensionActionsSheet.show(this, ext);
                } else {
                    Toast.makeText(this, R.string.ext_not_openable, Toast.LENGTH_SHORT).show();
                }
            });
            // long press always opens the actions (language packs, theme, ui tweaks)
            row.setOnLongClickListener(v -> {
                if (!ext.hasContributions()) return false;
                HapticUtil.forceVibrate(this, 30);
                ExtensionActionsSheet.show(this, ext);
                return true;
            });

            llInstalled.addView(row);
        }
        tvEmptyInstalled.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        applyTab();
    }

    private void confirmRemove(final ExtensionRepository.InstalledExtension ext) {
        HapticUtil.forceVibrate(this, 30);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ext_remove_title)
                .setMessage(R.string.ext_remove_message)
                .setPositiveButton(R.string.ext_remove, (d, w) -> repo.uninstall(ext.id))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickPackage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_INSTALL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installFromUri(data.getData());
        }
    }

    private void installFromUri(final Uri uri) {
        Toast.makeText(this, R.string.ext_installing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final File tmp = new File(getCacheDir(), "kodaext-install.zip");
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("cannot read file");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                repo.installFromZip(tmp, BuildConfig.VERSION_CODE);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.ext_installed, Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (final Exception e) {
                android.util.Log.e("KodaExtensions", "install failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.ext_install_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            } finally {
                tmp.delete();
            }
        }, "KodaExtInstall").start();
    }

    @Override
    protected void onDestroy() {
        repo.removeListener(refreshListener);
        super.onDestroy();
    }

    /**
     * text tabs with the sliding orange line, copied 1:1 from ServerDetailActivity
     * so the hub switches exactly like the server manager does (install/installed there).
     */
    private View makeTextTabs(final String[] labels, int selected, final java.util.function.IntConsumer onPick) {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout wrap = new FrameLayout(this);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final View indicator = new View(this);
        android.graphics.drawable.GradientDrawable indicatorBg = new android.graphics.drawable.GradientDrawable();
        indicatorBg.setColor(0xFFFF6B00);
        indicatorBg.setCornerRadius(1.5f * d);
        indicator.setBackground(indicatorBg);

        final TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(13);
            tv.setLetterSpacing(0.08f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda),
                    android.graphics.Typeface.BOLD);
            tv.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            tv.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 30);
                onPick.accept(index);
                moveTabIndicator(items, indicator, index, d);
            });
            items[i] = tv;
            row.addView(tv);
        }

        wrap.addView(row, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(0, (int) (2 * d));
        indicatorLp.gravity = android.view.Gravity.BOTTOM;
        wrap.addView(indicator, indicatorLp);

        // place the line under the active label once the layout is measured
        final int[] tries = {0};
        Runnable[] place = new Runnable[1];
        place[0] = () -> {
            TextView active = items[Math.max(0, Math.min(selected, items.length - 1))];
            if (active.getWidth() == 0 && tries[0]++ < 10) {
                wrap.post(place[0]);
                return;
            }
            moveTabIndicator(items, indicator, Math.max(0, Math.min(selected, items.length - 1)), d);
        };
        wrap.post(place[0]);
        return wrap;
    }

    /** slides the orange line under the given tab and recolours the labels. */
    private void moveTabIndicator(TextView[] items, View indicator, int index, float d) {
        View target = items[index];
        android.view.ViewGroup.LayoutParams lp = indicator.getLayoutParams();
        lp.width = target.getWidth();
        indicator.setLayoutParams(lp);
        indicator.animate().translationX(target.getLeft()).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        for (int i = 0; i < items.length; i++) {
            items[i].setTextColor(i == index ? 0xFFFF6B00 : 0xFF9A8B80);
        }
    }
}
