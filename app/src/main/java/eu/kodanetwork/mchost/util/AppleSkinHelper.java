/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
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
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

/**
 * the apple skin: grey canvas, white grouped cards with inset separators, system blue, chevrons on
 * tappable rows. behind `dev_apple_skin_enabled`, off by default and mutually exclusive with the
 * notion skin and with sleek/terminal, so no two skins paint the same tree.
 * the animations are ios flavoured: staggered group entry, soft content fades, press scale without
 * any ripple and a light spring on the fab and the switches.
 */
public class AppleSkinHelper {

    public static final String PREF_ENABLED = "dev_apple_skin_enabled";
    private static final String PREF_INTRO = "apple_skin_intro_pending";
    private static final String CHEVRON = "\u203A";

    public static boolean isAppleEnabled(Context ctx) {
        if (ctx == null) return false;
        if (App.getPrefs(ctx).getBoolean("dev_notion_skin_enabled", false)) return false;
        return App.getPrefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    public static void markIntroPending(Context ctx) {
        App.getPrefs(ctx).edit().putBoolean(PREF_INTRO, true).apply();
    }

    // ─── theme plumbing, has to run before super.onCreate ─────────────

    public static void applyTheme(Activity activity) {
        if (activity == null || !isAppleEnabled(activity)) return;
        Palette p = palette(activity);
        activity.setTheme(R.style.Theme_KodaNetwork_Apple);
        activity.getTheme().applyStyle(
                p.dark ? R.style.ThemeOverlay_KodaNetwork_Apple_Dark : R.style.ThemeOverlay_KodaNetwork_Apple_Light, true);
        activity.getWindow().setStatusBarColor(p.canvas);
        activity.getWindow().setNavigationBarColor(p.canvas);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (controller != null) controller.setAppearanceLightStatusBars(!p.dark);
    }

    // ─── the view tree pass, has to run after setContentView ──────────

    public static void apply(Activity activity) {
        if (activity == null || !isAppleEnabled(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        applyToView(activity, root);
        installTabFades(activity, root);
        groupEntryAnimation(activity, root);
        maybePlayIntro(activity, root);
    }

    /** also usable for rows an adapter inflates later. */
    public static void applyToView(Context ctx, View root) {
        if (ctx == null || root == null || !isAppleEnabled(ctx)) return;
        Palette p = palette(ctx);
        Typeface sans = ResourcesCompat.getFont(ctx, R.font.golos_text);
        paint(ctx, p, sans, root);
    }

    /** breadth first, backgrounds keep their shape and only change colour and radius. */
    private static void paint(Context ctx, Palette p, Typeface sans, View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        IdentityHashMap<View, Boolean> groups = new IdentityHashMap<>();
        queue.add(root);
        depths.add(0);
        int visited = 0;

        while (!queue.isEmpty() && visited < 900) {
            View v = queue.poll();
            Integer depthBox = depths.poll();
            int depth = depthBox == null ? 0 : depthBox;
            visited++;

            boolean chrome = isChrome(v);
            if (depth == 0) {
                setSolidBackground(v, p.canvas);
            } else if (depth == 1) {
                setSolidBackground(v, chrome ? p.chrome : p.canvas);
            } else if (isSeparator(ctx, v)) {
                setInsetSeparator(ctx, p, v);
            } else if (v instanceof MaterialCardView) {
                MaterialCardView card = (MaterialCardView) v;
                card.setCardBackgroundColor(p.card);
                card.setRadius(dp(ctx, 12));
                card.setStrokeWidth(0);
                card.setCardElevation(0f);
                groups.put(v, Boolean.TRUE);
            } else {
                Drawable bg = v.getBackground();
                if (bg instanceof GradientDrawable || bg instanceof ColorDrawable) {
                    tintShape(ctx, v, p.card, 12f);
                    groups.put(v, Boolean.TRUE);
                }
            }

            if (v instanceof TextView) {
                paintText((TextView) v, p, sans);
            }
            if (v instanceof EditText) {
                ((EditText) v).setHintTextColor(p.textMuted);
            }
            if (v instanceof SwitchMaterial) {
                paintSwitch((SwitchMaterial) v, p);
                installSpring(v);
            }
            if (v instanceof FloatingActionButton) {
                paintFab((FloatingActionButton) v, p);
                installSpring(v);
            } else if (v instanceof MaterialButton) {
                paintButton(ctx, p, sans, (MaterialButton) v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    queue.add(g.getChildAt(i));
                    depths.add(depth + 1);
                }
            }
        }

        // second pass: tappable rows inside a group card get the ios chevron and press behaviour
        for (View group : groups.keySet()) {
            installIosPress(group, p, false);
            if (group instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) group;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View row = g.getChildAt(i);
                    if (!row.isClickable()) continue;
                    if (row instanceof SwitchMaterial || row instanceof MaterialButton) continue;
                    addChevron(ctx, row, p, sans);
                    installIosPress(row, p, true);
                }
            }
        }
    }

    private static void paintText(TextView tv, Palette p, Typeface sans) {
        int current = tv.getCurrentTextColor();
        int out;
        if (looksLikeBrandAccent(current)) out = p.accent;
        else if (looksMuted(current)) out = p.textMuted;
        else out = p.text;
        tv.setTextColor(out);

        float sp = tv.getTextSize() / tv.getResources().getDisplayMetrics().scaledDensity;
        if (sp >= 22f) {
            // ios large titles, the screen title is the only text that gets this loud
            tv.setTypeface(sans, Typeface.BOLD);
        } else if (sp >= 16f) {
            tv.setTypeface(sans, sp >= 17f ? Typeface.BOLD : Typeface.NORMAL);
        } else {
            tv.setTypeface(sans, Typeface.NORMAL);
        }
    }

    private static void paintButton(Context ctx, Palette p, Typeface sans, MaterialButton b) {
        boolean filled = looksFilled(b);
        b.setBackgroundTintList(ColorStateList.valueOf(filled ? p.accent : p.fill));
        b.setTextColor(filled ? 0xFFFFFFFF : p.accent);
        b.setCornerRadius(dp(ctx, 10));
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setAllCaps(false);
        b.setTypeface(sans, Typeface.BOLD);
    }

    private static void paintFab(FloatingActionButton fab, Palette p) {
        fab.setBackgroundTintList(ColorStateList.valueOf(p.accent));
        fab.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        fab.setElevation(0f);
    }

    private static void paintSwitch(SwitchMaterial sw, Palette p) {
        ColorStateList track = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{p.accent, p.line});
        ColorStateList thumb = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF});
        sw.setTrackTintList(track);
        sw.setThumbTintList(thumb);
    }

    /** the chevron ios puts on every row that opens something. */
    private static void addChevron(Context ctx, View row, Palette p, Typeface sans) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout g = (LinearLayout) row;
        if (g.getOrientation() != LinearLayout.HORIZONTAL) return;
        int count = g.getChildCount();
        if (count == 0 || count > 4) return;
        View last = g.getChildAt(count - 1);
        if (!(last instanceof TextView)) return;
        String text = ((TextView) last).getText() == null ? "" : ((TextView) last).getText().toString();
        if (CHEVRON.equals(text)) return;

        TextView chevron = new TextView(ctx);
        chevron.setText(CHEVRON);
        chevron.setTextColor(p.textMuted);
        chevron.setTextSize(17f);
        chevron.setTypeface(sans);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMarginStart(dp(ctx, 6));
        chevron.setLayoutParams(lp);
        try {
            g.addView(chevron);
        } catch (Exception ignored) {
        }
    }

    /** press = grey fill plus a 0.97 scale over 90 ms, then a soft spring back. no ripple. */
    private static void installIosPress(View v, Palette p, boolean scale) {
        final Drawable original = v.getBackground();
        final GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(p.press);
        pressed.setCornerRadius(cornerOf(original, 10f));
        final boolean doScale = scale;
        v.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setBackground(pressed);
                if (doScale) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.setBackground(original);
                if (doScale) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(new OvershootInterpolator(0.6f)).start();
                }
            }
            return false;
        });
    }

    /** a light spring on the fab and the switches, barely an overshoot. */
    private static void installSpring(View v) {
        v.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(new OvershootInterpolator(0.9f)).start();
            }
            return false;
        });
    }

    /** bottom bar taps fade the content in, the ios way of switching a tab. */
    private static void installTabFades(Activity activity, View root) {
        View bar = findViewByName(activity, "main_bottom_bar");
        if (!(bar instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) bar;
        Palette p = palette(activity);
        for (int i = 0; i < group.getChildCount(); i++) {
            View item = group.getChildAt(i);
            if (!item.isClickable()) continue;
            final Drawable original = item.getBackground();
            final GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(p.press);
            pressed.setCornerRadius(cornerOf(original, 10f));
            item.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    view.setBackground(pressed);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    view.setBackground(original);
                    view.postDelayed(() -> fadeContent(activity, root), 70);
                }
                return false;
            });
        }
    }

    /** soft fade for tab and section switches. */
    public static void fadeContent(Activity activity) {
        if (activity == null || !isAppleEnabled(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root != null) fadeContent(activity, root);
    }

    private static void fadeContent(Activity activity, View root) {
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        root.setAlpha(0f);
        root.setTranslationY(dp(activity, 4));
        root.animate().alpha(1f).translationY(0f).setDuration(170)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** the group cards of a screen slide in one after another, 12 dp up, no bounce. */
    private static void groupEntryAnimation(Activity activity, View root) {
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        List<View> cards = collectBlocks(root);
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            card.setAlpha(0f);
            card.setTranslationY(dp(activity, 12));
            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setStartDelay(i * 26L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private static List<View> collectBlocks(View root) {
        List<View> out = new ArrayList<>();
        ViewGroup container = null;
        View cursor = root;
        int guard = 0;
        while (cursor instanceof ViewGroup && guard++ < 12) {
            ViewGroup g = (ViewGroup) cursor;
            if (g.getChildCount() >= 2) {
                container = g;
                break;
            }
            if (g.getChildCount() == 1) cursor = g.getChildAt(0);
            else break;
        }
        if (container == null) return out;
        for (int i = 0; i < container.getChildCount() && i < 14; i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) out.add(child);
        }
        return out;
    }

    /** the first screen after flipping the switch fades in with a tiny scale. */
    private static void maybePlayIntro(Activity activity, View root) {
        if (!App.getPrefs(activity).getBoolean(PREF_INTRO, false)) return;
        App.getPrefs(activity).edit().putBoolean(PREF_INTRO, false).apply();
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        root.setAlpha(0f);
        root.setScaleX(0.98f);
        root.setScaleY(0.98f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ─── shared bits ─────────────────────────────────────────────────


    /**
     * the app styles a lot of its widgets with selectors and layers, so a single background cast
     * would miss them. walk the wrapper drawables and recolour the leaves.
     */
    private static void tintWrapped(Drawable bg, int color, float radiusPx) {
        if (bg == null) return;
        if (bg instanceof android.graphics.drawable.StateListDrawable) {
            android.graphics.drawable.StateListDrawable states = (android.graphics.drawable.StateListDrawable) bg;
            for (int i = 0; i < states.getStateCount(); i++) {
                tintWrapped(states.getStateDrawable(i), color, radiusPx);
            }
        } else if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) bg;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                tintWrapped(layers.getDrawable(i), color, radiusPx);
            }
        } else if (bg instanceof android.graphics.drawable.InsetDrawable) {
            tintWrapped(((android.graphics.drawable.InsetDrawable) bg).getDrawable(), color, radiusPx);
        } else if (bg instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) bg;
            g.setColor(color);
            float current = g.getCornerRadius();
            if (current <= 0 && radiusPx > 0) g.setCornerRadius(radiusPx);
            else if (current > radiusPx && radiusPx > 0) g.setCornerRadius(radiusPx);
            g.setStroke(0, 0);
        } else if (bg instanceof ColorDrawable) {
            ((ColorDrawable) bg).setColor(color);
        }
    }

    private static boolean isSeparator(Context ctx, View v) {
        if (v instanceof TextView) return false;
        int h = v.getLayoutParams() == null ? 0 : v.getLayoutParams().height;
        return h > 0 && h <= dp(ctx, 2) && v.getBackground() != null;
    }

    /** ios insets its separators so they start where the text starts. */
    private static void setInsetSeparator(Context ctx, Palette p, View v) {
        InsetDrawable inset = new InsetDrawable(new ColorDrawable(p.line), dp(ctx, 16), 0, 0, 0);
        v.setBackground(inset);
    }

    private static void setSolidBackground(View v, int color) {
        Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.StateListDrawable
                || bg instanceof android.graphics.drawable.LayerDrawable
                || bg instanceof android.graphics.drawable.InsetDrawable) {
            tintWrapped(bg, color, 0f);
            return;
        }
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setColor(color);
        } else if (bg instanceof ColorDrawable) {
            ((ColorDrawable) bg).setColor(color);
        } else if (bg == null) {
            v.setBackgroundColor(color);
        } else if (bg instanceof RippleDrawable) {
            ((RippleDrawable) bg).setColor(ColorStateList.valueOf(withAlpha(color, 0x33)));
        }
    }

    private static void tintShape(Context ctx, View v, int color, float radiusDp) {
        tintWrapped(v.getBackground(), color, dp(ctx, radiusDp));
    }

    private static float cornerOf(Drawable bg, float fallbackDp) {
        if (bg instanceof GradientDrawable) {
            float r = ((GradientDrawable) bg).getCornerRadius();
            if (r > 0) return r;
        }
        return fallbackDp;
    }

    private static View findViewByName(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        if (id == 0) return null;
        return activity.findViewById(id);
    }

    private static boolean isChrome(View v) {
        String name = idName(v);
        return name.contains("bar") || name.contains("nav") || name.contains("header")
                || name.contains("toolbar") || name.contains("title");
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        try {
            return v.getResources().getResourceEntryName(id).toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean looksLikeBrandAccent(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return r > 170 && g > 50 && g < 190 && b < 110;
    }

    private static boolean looksMuted(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha < 230) return true;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        boolean greyish = Math.abs(r - g) < 26 && Math.abs(g - b) < 26;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return greyish && lum > 0.32 && lum < 0.75;
    }

    private static boolean looksFilled(MaterialButton b) {
        ColorStateList tint = b.getBackgroundTintList();
        if (tint == null) return false;
        int color = tint.getDefaultColor();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int bl = color & 0xFF;
        int max = Math.max(r, Math.max(g, bl));
        int min = Math.min(r, Math.min(g, bl));
        return (max - min) > 40;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Palette palette(Context ctx) {
        Palette p = new Palette();
        p.dark = !ThemeHelper.isLightMode(ctx);
        if (p.dark) {
            p.canvas = ContextCompat.getColor(ctx, R.color.apple_bg_dark);
            p.chrome = ContextCompat.getColor(ctx, R.color.apple_bg_dark);
            p.card = ContextCompat.getColor(ctx, R.color.apple_group_dark);
            p.text = ContextCompat.getColor(ctx, R.color.apple_text_dark);
            p.textMuted = ContextCompat.getColor(ctx, R.color.apple_text_muted_dark);
            p.line = ContextCompat.getColor(ctx, R.color.apple_line_dark);
            p.fill = ContextCompat.getColor(ctx, R.color.apple_fill_dark);
            p.press = ContextCompat.getColor(ctx, R.color.apple_fill_dark);
            p.accent = ContextCompat.getColor(ctx, R.color.apple_accent_dark);
        } else {
            p.canvas = ContextCompat.getColor(ctx, R.color.apple_bg_light);
            p.chrome = ContextCompat.getColor(ctx, R.color.apple_bg_light);
            p.card = ContextCompat.getColor(ctx, R.color.apple_group_light);
            p.text = ContextCompat.getColor(ctx, R.color.apple_text_light);
            p.textMuted = ContextCompat.getColor(ctx, R.color.apple_text_muted_light);
            p.line = ContextCompat.getColor(ctx, R.color.apple_line_light);
            p.fill = ContextCompat.getColor(ctx, R.color.apple_fill_light);
            p.press = ContextCompat.getColor(ctx, R.color.apple_fill_light);
            p.accent = ContextCompat.getColor(ctx, R.color.apple_accent_light);
        }
        return p;
    }

    private static class Palette {
        boolean dark;
        int canvas, chrome, card, text, textMuted, line, fill, press, accent;
    }
}
