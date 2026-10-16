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
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.SheetFix;

/** store detail sheet: description, screenshots, permissions, changelog and the install button. */
public final class ExtensionStoreSheet {

    private ExtensionStoreSheet() {}

    /** human readable permission text, same wording as the manifest consent dialog. */
    public static String permissionLabel(Context context, String permission) {
        switch (permission) {
            case "storage": return context.getString(R.string.ext_perm_storage);
            case "servers.read": return context.getString(R.string.ext_perm_servers_read);
            case "console.read": return context.getString(R.string.ext_perm_console_read);
            case "console.send": return context.getString(R.string.ext_perm_console_send);
            case "servers.control": return context.getString(R.string.ext_perm_servers_control);
            case "players.read": return context.getString(R.string.ext_perm_players_read);
            case "players.actions": return context.getString(R.string.ext_perm_players_actions);
            case "files.read": return context.getString(R.string.ext_perm_files_read);
            case "notifications": return context.getString(R.string.ext_perm_notifications);
            default: return permission;
        }
    }

    public interface InstallAction {
        void onInstall(ExtensionStoreApi.StoreExtension ext);
    }

    /**
     * @param installedCode version code of the installed copy, 0 when not installed
     */
    public static void show(final Activity activity, final ExtensionStoreApi.StoreExtension ext,
                            final int installedCode, final InstallAction onInstall) {
        if (activity == null || ext == null) return;
        final float d = activity.getResources().getDisplayMetrics().density;

        final BottomSheetDialog sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        root.setPadding(pad, (int) (18 * d), pad, (int) (18 * d));
        scroll.addView(root);

        // ─── header ─────────────────────────────────────────────
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(activity);
        int iconSize = (int) (52 * d);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd((int) (14 * d));
        icon.setLayoutParams(iconLp);
        header.addView(icon);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(ext.name);
        name.setTextColor(0xFFF0F0F0);
        name.setTextSize(17);
        name.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.font_koda), Typeface.BOLD);
        titles.addView(name);
        TextView meta = new TextView(activity);
        String author = ext.author == null || ext.author.isEmpty() ? "" : "  \u00B7  " + ext.author;
        String version = ext.latest != null && !ext.latest.version.isEmpty() ? "v" + ext.latest.version : "";
        meta.setText(version + author);
        meta.setTextColor(0xFFFF6B00);
        meta.setTextSize(12);
        titles.addView(meta);
        TextView downloads = new TextView(activity);
        downloads.setText(activity.getString(R.string.ext_store_downloads, ext.downloads));
        downloads.setTextColor(0xFF8A8A9A);
        downloads.setTextSize(11);
        titles.addView(downloads);
        header.addView(titles);
        root.addView(header);

        if (ext.iconUrl != null && !ext.iconUrl.isEmpty()) {
            try {
                Glide.with(activity).load(ext.iconUrl).into(icon);
            } catch (Throwable ignored) {}
        }

        // ─── description ────────────────────────────────────────
        String body = ext.description == null || ext.description.isEmpty() ? ext.summary : ext.description;
        if (body != null && !body.isEmpty()) {
            root.addView(text(activity, body, 0xFFB7AE9F, 13, d, (int) (14 * d)));
        }

        // ─── screenshots ────────────────────────────────────────
        if (!ext.screenshots.isEmpty()) {
            android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(activity);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout shots = new LinearLayout(activity);
            shots.setOrientation(LinearLayout.HORIZONTAL);
            for (String url : ext.screenshots) {
                ImageView img = new ImageView(activity);
                int w = (int) (170 * d);
                int h = (int) (100 * d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.setMarginEnd((int) (8 * d));
                img.setLayoutParams(lp);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(0xFF26221E);
                try {
                    Glide.with(activity).load(url).into(img);
                } catch (Throwable ignored) {}
                shots.addView(img);
            }
            strip.addView(shots);
            LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stripLp.topMargin = (int) (14 * d);
            root.addView(strip, stripLp);
        }

        // ─── permissions ────────────────────────────────────────
        if (ext.latest != null && !ext.latest.permissions.isEmpty()) {
            root.addView(sectionLabel(activity, activity.getString(R.string.ext_permissions), d));
            for (String permission : ext.latest.permissions) {
                root.addView(text(activity, "\u2022  " + permissionLabel(activity, permission),
                        0xFFB7AE9F, 12, d, (int) (5 * d)));
            }
        }

        // ─── license + links ────────────────────────────────────
        if (ext.license != null && !ext.license.isEmpty()) {
            root.addView(text(activity, activity.getString(R.string.ext_store_license, ext.license),
                    0xFF8A8A9A, 12, d, (int) (12 * d)));
        }
        if (ext.sourceUrl != null && !ext.sourceUrl.isEmpty()) {
            root.addView(linkRow(activity, activity.getString(R.string.ext_store_source), ext.sourceUrl, d));
        }
        if (ext.homepageUrl != null && !ext.homepageUrl.isEmpty()) {
            root.addView(linkRow(activity, activity.getString(R.string.ext_store_homepage), ext.homepageUrl, d));
        }

        // ─── versions ───────────────────────────────────────────
        if (!ext.versions.isEmpty()) {
            root.addView(sectionLabel(activity, activity.getString(R.string.ext_store_versions), d));
            for (int i = 0; i < ext.versions.size() && i < 5; i++) {
                ExtensionStoreApi.StoreVersion entry = ext.versions.get(i);
                String head = "v" + entry.version;
                if (entry.fileSize > 0) head += "  \u00B7  " + ExtensionStoreApi.formatSize(entry.fileSize);
                if (entry.minAppVersion > 0) {
                    head += "  \u00B7  " + activity.getString(R.string.ext_store_requires, entry.minAppVersion);
                }
                root.addView(text(activity, head, 0xFFF0F0F0, 12, d, (int) (8 * d)));
                if (entry.changelog != null && !entry.changelog.isEmpty()) {
                    root.addView(text(activity, entry.changelog, 0xFF8A8A9A, 11, d, (int) (2 * d)));
                }
            }
        }

        // ─── action ─────────────────────────────────────────────
        MaterialButton button = new MaterialButton(activity);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setCornerRadius((int) (12 * d));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * d));
        buttonLp.topMargin = (int) (18 * d);
        boolean installed = installedCode > 0;
        boolean update = installed && ext.latest != null && ext.latest.versionCode > installedCode;
        if (!installed) {
            button.setText(activity.getString(R.string.ext_store_install));
        } else if (update) {
            button.setText(activity.getString(R.string.ext_store_update));
        } else {
            button.setText(activity.getString(R.string.ext_store_installed));
            button.setEnabled(false);
        }
        if (button.isEnabled()) {
            button.setOnClickListener(v -> {
                HapticUtil.forceVibrate(activity, 30);
                sheet.dismiss();
                onInstall.onInstall(ext);
            });
        }
        root.addView(button, buttonLp);

        sheet.setContentView(scroll);
        SheetFix.apply(sheet);
        sheet.show();
    }

    private static TextView text(Activity activity, String value, int color, int sizeSp, float d, int topMargin) {
        TextView tv = new TextView(activity);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setLineSpacing(0, 1.15f);
        if (topMargin > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMargin;
            tv.setLayoutParams(lp);
        }
        return tv;
    }

    private static TextView sectionLabel(Activity activity, String value, float d) {
        TextView tv = text(activity, value, 0xFFFF6B00, 11, d, (int) (18 * d));
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.font_koda), Typeface.BOLD);
        return tv;
    }

    private static TextView linkRow(Activity activity, String label, final String url, float d) {
        TextView tv = text(activity, label + "  \u2192", 0xFFFF6B00, 12, d, (int) (8 * d));
        tv.setOnClickListener(v -> {
            try {
                activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
            } catch (Throwable ignored) {}
        });
        return tv;
    }
}
