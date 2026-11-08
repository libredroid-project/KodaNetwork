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
package eu.kodanetwork.mchost.util;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import eu.kodanetwork.mchost.R;

/**
 * one builder for every P.R.A.E.T.O.R. sheet, so handle, wordmark, title, rows, dividers and
 * buttons are identical everywhere. screens that need a real layout keep using their own XML,
 * everything built in code comes through here.
 *
 * rows are the same rows as the danger zones: title, optional description one size smaller,
 * chevron on the right, 14dp above and below, a 1dp line between them.
 */
public final class KodaSheet {

    public final BottomSheetDialog sheet;
    /** padded sheet surface, header and buttons live here. */
    public final LinearLayout root;
    /** rows go in here, it scrolls when the sheet gets tall. */
    public final LinearLayout body;

    private final Activity activity;
    private final float d;

    private KodaSheet(Activity activity, String title, String subtitle) {
        this.activity = activity;
        this.d = activity.getResources().getDisplayMetrics().density;

        sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);
        sheet.setCancelable(true);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_koda_sheet);
        root.setPadding((int) (24 * d), (int) (12 * d), (int) (24 * d), (int) (24 * d));

        View handle = new View(activity);
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setColor(0xFF3A3A44);
        hb.setCornerRadius(2 * d);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams((int) (32 * d), (int) (4 * d));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = (int) (18 * d);
        root.addView(handle, hlp);

        TextView wm = new TextView(activity);
        wm.setText(android.text.Html.fromHtml(
                "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY));
        wm.setTextSize(11f);
        wm.setLetterSpacing(0.18f);
        android.graphics.Typeface sg = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.space_grotesk_regular);
        wm.setTypeface(sg != null ? sg : android.graphics.Typeface.DEFAULT);
        root.addView(wm);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(0xFFF5F5F7);
        t.setTextSize(21f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) t.setTypeface(koda, android.graphics.Typeface.BOLD);
        t.setPadding(0, (int) (10 * d), 0, 0);
        root.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText(subtitle);
            s.setTextColor(0xFFBCBCC6);
            s.setTextSize(14f);
            s.setLineSpacing((int) (2 * d), 1f);
            s.setPadding(0, (int) (8 * d), 0, 0);
            if (koda != null) s.setTypeface(koda);
            root.addView(s);
        }

        ScrollView scroller = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.52f);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, (int) (14 * d), 0, 0);
        scroller.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public static KodaSheet create(Activity activity, String title) {
        return create(activity, title, null);
    }

    public static KodaSheet create(Activity activity, String title, String subtitle) {
        return new KodaSheet(activity, title, subtitle);
    }

    /** a full width row with title and description, the danger variant turns the text red. */
    public View row(String title, String description, boolean danger, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (14 * d), 0, (int) (14 * d));
        android.util.TypedValue out = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        row.setBackgroundResource(out.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextColor(danger ? 0xFFFF8A80 : 0xFFF0F0F0);
        tvTitle.setTextSize(14f);
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) tvTitle.setTypeface(koda);
        texts.addView(tvTitle);

        if (description != null && !description.isEmpty()) {
            TextView tvDesc = new TextView(activity);
            tvDesc.setText(description);
            tvDesc.setTextColor(0xFF8A8A9A);
            tvDesc.setTextSize(12f);
            if (koda != null) tvDesc.setTypeface(koda);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = (int) (2 * d);
            texts.addView(tvDesc, dlp);
        }
        row.addView(texts);

        TextView chevron = new TextView(activity);
        chevron.setText("\u203A");
        chevron.setTextColor(danger ? 0xFFFF8A80 : 0xFF6B6B78);
        chevron.setTextSize(18f);
        row.addView(chevron);

        if (click != null) {
            row.setOnClickListener(click);
            HapticUtil.applyHapticsToView(row, activity);
        }
        body.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** the 1dp line between two rows. */
    public void divider() {
        View line = new View(activity);
        line.setBackgroundColor(0xFF2A2226);
        body.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) d)));
    }

    /** small grey caption above a group, like "WHEN" or "COPIES". */
    public TextView label(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFF8A8A9A);
        tv.setTextSize(11f);
        tv.setLetterSpacing(0.06f);
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) tv.setTypeface(koda);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        lp.bottomMargin = (int) (6 * d);
        body.addView(tv, lp);
        return tv;
    }

    /** plain body text inside the scroll area. */
    public TextView text(String text, int color) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(13.5f);
        tv.setLineSpacing((int) (2 * d), 1f);
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) tv.setTypeface(koda);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * d);
        body.addView(tv, lp);
        return tv;
    }

    /** adds any view to the scrolling body. */
    public void add(View view) {
        body.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** adds a view with a fixed height, for fields and pill switches. */
    public void add(View view, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (heightDp * d));
        lp.topMargin = (int) (8 * d);
        body.addView(view, lp);
    }

    /** one line input on the sheet surface, 44dp high with rounded corners. */
    public android.widget.EditText field(String text, String hint, boolean numeric) {
        android.widget.EditText et = new android.widget.EditText(activity);
        et.setText(text);
        et.setHint(hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF666677);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1C1614);
        bg.setCornerRadius(10 * d);
        bg.setStroke(Math.max(1, (int) d), 0xFF2A2226);
        et.setBackground(bg);
        if (numeric) et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) et.setTypeface(koda);
        return et;
    }

    /** a label with a switch on the right, the switch is handed back for reading later. */
    public com.google.android.material.switchmaterial.SwitchMaterial toggle(String label,
                                                                            boolean checked) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * d), 0, 0);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFF0F0F0);
        tv.setTextSize(14f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(
                activity, R.font.font_koda);
        if (koda != null) tv.setTypeface(koda);
        row.addView(tv);

        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(activity);
        sw.setChecked(checked);
        sw.setUseMaterialThemeColors(false);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B00));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(0xFF3A2E27));
        row.addView(sw);

        body.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    /** the orange action at the bottom, 56dp like every primary action. */
    public MaterialButton primary(String text, View.OnClickListener click) {
        MaterialButton b = addPrimary(text);
        b.setOnClickListener(click);
        return b;
    }

    /**
     * a dangerous primary: locked for a few seconds with the countdown in the label, the
     * double vibration marks the moment the sheet appears. no warning triangle, the red
     * button and the wait carry the risk.
     */
    public MaterialButton primaryWithCountdown(final String text, final int seconds,
                                               View.OnClickListener click) {
        final MaterialButton b = addPrimary(activity.getString(R.string.sheet_countdown, text, seconds));
        b.setEnabled(false);
        final android.os.CountDownTimer timer = new android.os.CountDownTimer(seconds * 1000L, 1000) {
            @Override
            public void onTick(long left) {
                b.setText(activity.getString(R.string.sheet_countdown, text, (int) (left / 1000)));
            }

            @Override
            public void onFinish() {
                b.setText(text);
                b.setEnabled(true);
                KodaSheetMotion.pop(b);
            }
        };
        timer.start();
        sheet.setOnDismissListener(d -> timer.cancel());
        b.setOnClickListener(click);
        HapticUtil.forceVibrate(activity, 200);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> HapticUtil.forceVibrate(activity, 300), 300);
        return b;
    }

    private MaterialButton addPrimary(String text) {
        MaterialButton b = KodaButtons.primary(activity, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * d));
        lp.topMargin = (int) (20 * d);
        root.addView(b, lp);
        return b;
    }

    /** the quiet action below the primary one, same size, no fill. */
    public MaterialButton secondary(String text, View.OnClickListener click) {
        MaterialButton b = KodaButtons.dark(activity, text);
        b.setBackground(androidx.core.content.res.ResourcesCompat.getDrawable(
                activity.getResources(), R.drawable.bg_koda_sheet_button, activity.getTheme()));
        b.setTextColor(0xFFF0F0F0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * d));
        lp.topMargin = (int) (8 * d);
        b.setOnClickListener(click);
        root.addView(b, lp);
        return b;
    }

    /**
     * the same edge-to-edge window the PRAETOR sheets use. without it the dialog stops above
     * the nav bar and the activity shows through, which reads as a transparent nav bar.
     */
    public static void styleWindow(android.view.Window w) {
        if (w == null) return;
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
        // the sheet surface is dark in every theme, so the icons stay light
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        w.getDecorView().setSystemUiVisibility(flags);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
    }

    /**
     * moves the sheet content out of the nav bar area. the padding stays because the window
     * now draws behind the nav bar, and the nav bar itself shows the sheet colour.
     */
    public static void applyInsets(View view, int baseBottomDp) {
        if (view == null) return;
        final float density = view.getResources().getDisplayMetrics().density;
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    (int) (baseBottomDp * density) + bottom);
            return insets;
        });
    }

    /** shows the sheet, slides up like the other sheets and lets the content fly in. */
    public void show() {
        sheet.setContentView(root);
        styleWindow(sheet.getWindow());
        applyInsets(root, 24);
        SheetFix.apply(sheet);
        sheet.show();
        if (sheet.getWindow() != null) {
            sheet.getWindow().setNavigationBarColor(0xFF241C18);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                sheet.getWindow().setNavigationBarContrastEnforced(false);
            }
        }
        HapticUtil.applyHapticsToView(root, activity);
        // the reveal has to wait for the first layout, otherwise it runs while the window
        // is still invisible and the sheet just pops in without the animation
        root.post(() -> KodaSheetMotion.reveal(root));
    }
}
