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
package eu.kodanetwork.mchost.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.KodaSheet;
import eu.kodanetwork.mchost.util.NotificationCenter;
import eu.kodanetwork.mchost.util.ThemeHelper;

/**
 * the notification inbox: filters per category, the notes themselves and the
 * per category delivery settings. rows open their deep link, the small x deletes one.
 */
public class NotificationsActivity extends AppCompatActivity {

    private LinearLayout list;
    private LinearLayout filters;
    private View empty;
    private String activeKind = null;
    private List<NotificationCenter.Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.apply(this);
        setContentView(R.layout.activity_notifications);

        list = findViewById(R.id.layout_list);
        filters = findViewById(R.id.layout_filters);
        empty = findViewById(R.id.ll_notif_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ImageView settings = findViewById(R.id.btn_notif_settings);
        if (settings != null) settings.setOnClickListener(v -> showSettings());

        findViewById(R.id.btn_mark_all).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            NotificationCenter.markAllRead(this);
            render();
        });
        findViewById(R.id.btn_clear_all).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            NotificationCenter.clearAll(this);
            render();
        });

        buildFilters();
        render();
        reload();
        styleScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the theme pass runs again on resume, so the font/color fix has to run after it
        styleScreen();
        // something new may have arrived while the screen sat in the background
        reload();
    }

    /**
     * ThemeHelper forces its own typeface on the views it sees when it runs, and rows
     * that are inflated later keep font_koda. resetting the whole screen after every
     * theme pass keeps the header, the empty state and the rows identical.
     */
    private void styleScreen() {
        applyKodaFont(findViewById(android.R.id.content));
        // the shared title id gets the accent caps style from the theme, this screen
        // wants the normal white header like the ticket list
        TextView title = findViewById(R.id.tv_title);
        if (title != null) {
            title.setTextColor(0xFFFFFFFF);
            title.setLetterSpacing(0f);
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTag(R.id.tag_themed, "STYLED");
        }
    }

    /** a tap opens the full text first, the link is one more tap away. */
    private void openDetail(final NotificationCenter.Item item) {
        HapticUtil.forceVibrate(this, 30);
        NotificationCenter.markRead(this, item.id);

        final KodaSheet sheet = KodaSheet.create(this, item.title);
        String meta = NotificationCenter.kindLabel(this, item.kind);
        String when = fullDate(item.createdAt);
        if (!when.isEmpty()) meta = meta + " \u00b7 " + when;
        sheet.label(meta);
        sheet.text(item.body == null ? "" : item.body, 0xFFBCBCC6);

        boolean hasAction = item.action != null && !item.action.trim().isEmpty();
        if (hasAction) {
            sheet.primary(getString(R.string.notif_open), v -> {
                sheet.sheet.dismiss();
                NotificationCenter.openAction(this, item.action);
                reload();
            });
        }
        sheet.row(getString(R.string.notif_delete_one), null, true, v -> {
            sheet.sheet.dismiss();
            NotificationCenter.delete(this, item.id);
            Toast.makeText(this, R.string.notif_deleted, Toast.LENGTH_SHORT).show();
            reload();
        });
        if (!hasAction) {
            sheet.secondary(getString(R.string.praetor_action_cancel), v -> sheet.sheet.dismiss());
        }
        sheet.show();
        render();
    }

    private String fullDate(long timestamp) {
        if (timestamp <= 0) return "";
        return new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(timestamp));
    }

    /** same font everywhere on this screen, no matter when a row was inflated. */
    private void applyKodaFont(View view) {
        if (view == null) return;
        android.graphics.Typeface koda =
                androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda);
        if (koda == null) return;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTypeface(koda, tv.getTypeface() == null
                    ? android.graphics.Typeface.NORMAL : tv.getTypeface().getStyle());
            // mark it so the delayed theme pass does not put monospace back on it
            tv.setTag(R.id.tag_themed, "STYLED");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyKodaFont(group.getChildAt(i));
            }
        }
    }

    private void reload() {
        new Thread(() -> {
            final List<NotificationCenter.Item> fetched = NotificationCenter.refresh(this);
            runOnUiThread(() -> {
                if (fetched != null) items = fetched;
                render();
            });
        }, "KodaNotifLoad").start();
    }

    private void buildFilters() {
        filters.removeAllViews();
        addChip(getString(R.string.notif_filter_all), null);
        for (String kind : NotificationCenter.KINDS) {
            addChip(NotificationCenter.kindLabel(this, kind), kind);
        }
    }

    private void addChip(String label, final String kind) {
        boolean active = (kind == null && activeKind == null)
                || (kind != null && kind.equals(activeKind));
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
        chip.setPadding((int) (14 * getResources().getDisplayMetrics().density), 0,
                (int) (14 * getResources().getDisplayMetrics().density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * getResources().getDisplayMetrics().density);
        bg.setColor(active ? 0xFFFF6B00 : 0xFF1C1C22);
        chip.setBackground(bg);
        chip.setTextColor(active ? 0xFFFFFFFF : 0xFFBCBCC6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * getResources().getDisplayMetrics().density));
        lp.setMargins(0, 0, (int) (8 * getResources().getDisplayMetrics().density), 0);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 20);
            activeKind = kind;
            buildFilters();
            render();
        });
        filters.addView(chip, lp);
    }

    private void render() {
        list.removeAllViews();
        int shown = 0;
        long lastDay = -1;
        for (NotificationCenter.Item item : items) {
            if (activeKind != null && !activeKind.equalsIgnoreCase(item.kind)) continue;
            long day = dayOf(item.createdAt);
            if (day != lastDay) {
                list.addView(dayHeader(day));
                lastDay = day;
            }
            list.addView(row(item));
            shown++;
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        // rows are inflated after the theme pass, so the font fix runs again here
        applyKodaFont(list);
    }

    private long dayOf(long timestamp) {
        if (timestamp <= 0) return 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private View dayHeader(long day) {
        TextView tv = new TextView(this);
        tv.setText(dayLabel(day));
        tv.setTextColor(0xFF6B6B78);
        tv.setTextSize(11);
        tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
        tv.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (2 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String dayLabel(long day) {
        if (day <= 0) return "";
        java.util.Calendar today = java.util.Calendar.getInstance();
        long todayStart = dayOf(today.getTimeInMillis());
        long diff = (todayStart - day) / (24L * 60L * 60L * 1000L);
        if (diff == 0) return getString(R.string.notif_today);
        if (diff == 1) return getString(R.string.notif_yesterday);
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(day));
    }

    private View row(final NotificationCenter.Item item) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_notification, list, false);

        View dot = view.findViewById(R.id.view_kind_dot);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(NotificationCenter.kindColor(item.kind));
        if (dot != null) dot.setBackground(dotBg);

        TextView kind = view.findViewById(R.id.tv_kind);
        kind.setText(NotificationCenter.kindLabel(this, item.kind));
        kind.setTextColor(NotificationCenter.kindColor(item.kind));

        TextView time = view.findViewById(R.id.tv_time);
        time.setText(timeLabel(item.createdAt));

        TextView title = view.findViewById(R.id.tv_notif_title);
        title.setText(item.title);
        title.setAlpha(item.read ? 0.82f : 1f);

        TextView body = view.findViewById(R.id.tv_notif_body);
        body.setText(item.body);
        body.setVisibility(item.body == null || item.body.trim().isEmpty() ? View.GONE : View.VISIBLE);
        body.setAlpha(item.read ? 0.72f : 1f);

        if (!item.read) {
            title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda),
                    android.graphics.Typeface.BOLD);
        }

        view.setOnClickListener(v -> openDetail(item));

        View del = view.findViewById(R.id.btn_delete_notif);
        if (del != null) {
            del.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 30);
                NotificationCenter.delete(this, item.id);
                Toast.makeText(this, R.string.notif_deleted, Toast.LENGTH_SHORT).show();
                reload();
            });
        }

        View line = new View(this);
        line.setBackgroundColor(0xFF2A2226);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(view);
        wrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) getResources().getDisplayMetrics().density)));
        return wrapper;
    }

    private String timeLabel(long timestamp) {
        if (timestamp <= 0) return "";
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(timestamp));
    }

    /** per category delivery settings, server side so they survive a reinstall. */
    private void showSettings() {
        new Thread(() -> {
            NotificationCenter.loadMutes(this);
            runOnUiThread(this::showSettingsSheet);
        }, "KodaNotifMutes").start();
    }

    private void showSettingsSheet() {
        final KodaSheet sheet = KodaSheet.create(this,
                getString(R.string.notif_settings_title), getString(R.string.notif_settings_desc));
        for (final String kind : NotificationCenter.KINDS) {
            boolean muted = NotificationCenter.mutedKinds(this).contains(kind);
            sheet.row(NotificationCenter.kindLabel(this, kind),
                    muted ? getString(R.string.notif_mute) : null, false, v -> {
                        sheet.sheet.dismiss();
                        showKindSettings(kind);
                    });
            sheet.divider();
        }
        sheet.secondary(getString(R.string.praetor_action_cancel), v -> sheet.sheet.dismiss());
        sheet.show();
    }

    private void showKindSettings(final String kind) {
        final KodaSheet sheet = KodaSheet.create(this, NotificationCenter.kindLabel(this, kind));
        final boolean muted = NotificationCenter.mutedKinds(this).contains(kind);
        final boolean mail = NotificationCenter.emailKinds(this).contains(kind);

        final com.google.android.material.switchmaterial.SwitchMaterial muteSwitch =
                sheet.toggle(getString(R.string.notif_mute), muted);
        final com.google.android.material.switchmaterial.SwitchMaterial mailSwitch =
                sheet.toggle(getString(R.string.notif_email), mail);

        sheet.primary(getString(R.string.praetor_action_save), v -> {
            NotificationCenter.setMute(this, kind, muteSwitch.isChecked(), mailSwitch.isChecked());
            sheet.sheet.dismiss();
            Toast.makeText(this, R.string.notif_settings_saved, Toast.LENGTH_SHORT).show();
        });
        sheet.secondary(getString(R.string.praetor_action_cancel), v -> sheet.sheet.dismiss());
        sheet.show();
    }
}
