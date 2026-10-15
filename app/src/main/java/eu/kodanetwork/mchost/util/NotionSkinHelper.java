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
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

/**
 * the notion skin: plain white canvas, grey chrome, hairline separators and blue links, with the
 * serif carrying the headings. sits behind `dev_notion_skin_enabled`, off by default, and it is
 * mutually exclusive with the apple skin and with sleek/terminal, so nothing paints over anything.
 */
public class NotionSkinHelper {

    public static final String PREF_ENABLED = "dev_notion_skin_enabled";
    private static final String PREF_INTRO = "notion_skin_intro_pending";

    public static boolean isNotionEnabled(Context ctx) {
        if (ctx == null) return false;
        if (App.getPrefs(ctx).getBoolean("dev_apple_skin_enabled", false)) return false;
        return App.getPrefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    /** the one shot transition flag, set when the switch is flipped and eaten on the next screen. */
    public static void markIntroPending(Context ctx) {
        App.getPrefs(ctx).edit().putBoolean(PREF_INTRO, true).apply();
    }

    // ─── theme plumbing, has to run before super.onCreate ─────────────

    public static void applyTheme(Activity activity) {
        if (activity == null || !isNotionEnabled(activity)) return;
        Palette p = palette(activity);
        activity.setTheme(R.style.Theme_KodaNetwork_Notion);
        activity.getTheme().applyStyle(
                p.dark ? R.style.ThemeOverlay_KodaNetwork_Notion_Dark : R.style.ThemeOverlay_KodaNetwork_Notion_Light, true);
        activity.getWindow().setStatusBarColor(p.canvas);
        activity.getWindow().setNavigationBarColor(p.canvas);
        applyBarIcons(activity, !p.dark);
    }

    private static void applyBarIcons(Activity activity, boolean lightBars) {
        View decor = activity.getWindow().getDecorView();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(activity.getWindow(), decor);
        if (controller != null) controller.setAppearanceLightStatusBars(lightBars);
    }

    // ─── the view tree pass, has to run after setContentView ──────────

    public static void apply(Activity activity) {
        if (activity == null || !isNotionEnabled(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        applyToView(activity, root);
        enterAnimation(activity, root);
        maybePlayIntro(activity, root);
    }

    /** also usable for rows a recycler adapter inflates later. */
    public static void applyToView(Context ctx, View root) {
        if (ctx == null || root == null || !isNotionEnabled(ctx)) return;
        Palette p = palette(ctx);
        Typeface serif = ResourcesCompat.getFont(ctx, R.font.literata);
        Typeface sans = ResourcesCompat.getFont(ctx, R.font.golos_text);
        paint(ctx, p, serif, sans, root);
    }

    /**
     * breadth first pass over the tree. backgrounds keep their shape, only the colour and the
     * corner radius change, so no layout breaks.
     */
    private static void paint(Context ctx, Palette p, Typeface serif, Typeface sans, View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
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
            } else {
                styleDeepView(ctx, p, sans, v, chrome);
            }

            if (v instanceof TextView) {
                paintText((TextView) v, p, serif, sans);
            }
            if (v instanceof EditText) {
                EditText et = (EditText) v;
                et.setHintTextColor(p.textFaint);
            }
            if (v instanceof SwitchMaterial) {
                paintSwitch((SwitchMaterial) v, p);
            }
            if (v instanceof MaterialButton) {
                paintButton(ctx, p, sans, (MaterialButton) v);
            }
            if (isClickableRow(v)) {
                installPress(v, p);
            }

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    queue.add(child);
                    depths.add(depth + 1);
                }
            }
        }
    }

    private static void styleDeepView(Context ctx, Palette p, Typeface sans, View v, boolean chrome) {
        Drawable bg = v.getBackground();
        int h = v.getLayoutParams() == null ? 0 : v.getLayoutParams().height;
        // a view that is one or two dp tall is a separator, those turn into notion hairlines
        if (h > 0 && h <= dp(ctx, 2) && bg != null && !(v instanceof TextView)) {
            setSolidBackground(v, p.line);
            return;
        }
        if (v instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) v;
            card.setCardBackgroundColor(p.surface);
            card.setRadius(dp(ctx, 8));
            card.setStrokeWidth(0);
            card.setCardElevation(0f);
            return;
        }
        if (bg instanceof GradientDrawable || bg instanceof ColorDrawable) {
            tintShape(ctx, v, p.surface, 8f);
        }
    }

    private static void paintText(TextView tv, Palette p, Typeface serif, Typeface sans) {
        int current = tv.getCurrentTextColor();
        int out;
        if (looksLikeBrandAccent(current)) out = p.accent;
        else if (looksMuted(current)) out = p.textMuted;
        else out = p.text;
        tv.setTextColor(out);

        // headings carry the serif, everything else stays sans. notion never uses mono here.
        float sp = tv.getTextSize() / tv.getResources().getDisplayMetrics().scaledDensity;
        tv.setTypeface(sp >= 19f ? serif : sans);
    }

    private static void paintButton(Context ctx, Palette p, Typeface sans, MaterialButton b) {
        boolean filled = looksFilled(b);
        b.setBackgroundTintList(ColorStateList.valueOf(filled ? p.accent : p.chrome));
        b.setTextColor(filled ? 0xFFFFFFFF : p.text);
        b.setCornerRadius(dp(ctx, 6));
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setAllCaps(false);
        b.setTypeface(sans);
    }

    private static void paintSwitch(SwitchMaterial sw, Palette p) {
        ColorStateList track = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{p.accent, p.line});
        ColorStateList thumb = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, p.textFaint});
        sw.setTrackTintList(track);
        sw.setThumbTintList(thumb);
    }

    /** a quiet grey flash instead of the ripple, notion has no bouncing circles. */
    private static void installPress(View v, Palette p) {
        final Drawable original = v.getBackground();
        final GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(p.hover);
        pressed.setCornerRadius(cornerOf(original, 8f));
        v.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setBackground(pressed);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.setBackground(original);
            }
            return false;
        });
    }

    // ─── animations, all of them honour the animations_enabled pref ───

    private static void enterAnimation(Activity activity, View root) {
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        List<View> blocks = collectBlocks(root);
        for (int i = 0; i < blocks.size(); i++) {
            View block = blocks.get(i);
            block.setAlpha(0f);
            block.setTranslationY(dp(activity, 6));
            block.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setStartDelay(i * 22L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /** calm fade for tab and section switches, no overshoot anywhere. */
    public static void fadeContent(Activity activity) {
        if (activity == null || !isNotionEnabled(activity)) return;
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
    }

    private static void maybePlayIntro(Activity activity, View root) {
        if (!App.getPrefs(activity).getBoolean(PREF_INTRO, false)) return;
        App.getPrefs(activity).edit().putBoolean(PREF_INTRO, false).apply();
        if (!AnimHelper.isAnimationsEnabled(activity)) return;
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** the blocks of a screen, used for the staggered entry. */
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
        tintWrapped(v.getBackground(), color, radiusDp > 0 ? dp(ctx, radiusDp) : 0f);
    }

    private static float cornerOf(Drawable bg, float fallbackDp) {
        if (bg instanceof GradientDrawable) {
            float r = ((GradientDrawable) bg).getCornerRadius();
            if (r > 0) return r;
        }
        return fallbackDp;
    }

    private static boolean isClickableRow(View v) {
        if (!v.isClickable()) return false;
        if (v instanceof MaterialButton || v instanceof SwitchMaterial || v instanceof EditText) return false;
        return v instanceof ViewGroup;
    }

    private static boolean isChrome(View v) {
        String name = idName(v);
        return name.contains("bar") || name.contains("nav") || name.contains("header")
                || name.contains("toolbar") || name.contains("chrome");
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
        // the app paints its brand orange in a few shades, all of them land in here
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
        // grey or dark buttons are the quiet ones, saturated ones are the primary action
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
            p.canvas = ContextCompat.getColor(ctx, R.color.notion_bg_dark);
            p.chrome = ContextCompat.getColor(ctx, R.color.notion_chrome_dark);
            p.surface = ContextCompat.getColor(ctx, R.color.notion_surface_dark);
            p.text = ContextCompat.getColor(ctx, R.color.notion_text_dark);
            p.textMuted = ContextCompat.getColor(ctx, R.color.notion_text_muted_dark);
            p.textFaint = ContextCompat.getColor(ctx, R.color.notion_text_faint_dark);
            p.line = ContextCompat.getColor(ctx, R.color.notion_line_dark);
            p.hover = ContextCompat.getColor(ctx, R.color.notion_hover_dark);
            p.accent = ContextCompat.getColor(ctx, R.color.notion_accent_dark);
            p.accentSoft = ContextCompat.getColor(ctx, R.color.notion_accent_soft_dark);
        } else {
            p.canvas = ContextCompat.getColor(ctx, R.color.notion_bg_light);
            p.chrome = ContextCompat.getColor(ctx, R.color.notion_chrome_light);
            p.surface = ContextCompat.getColor(ctx, R.color.notion_surface_light);
            p.text = ContextCompat.getColor(ctx, R.color.notion_text_light);
            p.textMuted = ContextCompat.getColor(ctx, R.color.notion_text_muted_light);
            p.textFaint = ContextCompat.getColor(ctx, R.color.notion_text_faint_light);
            p.line = ContextCompat.getColor(ctx, R.color.notion_line_light);
            p.hover = ContextCompat.getColor(ctx, R.color.notion_hover_light);
            p.accent = ContextCompat.getColor(ctx, R.color.notion_accent_light);
            p.accentSoft = ContextCompat.getColor(ctx, R.color.notion_accent_soft_light);
        }
        return p;
    }

    private static class Palette {
        boolean dark;
        int canvas, chrome, surface, text, textMuted, textFaint, line, hover, accent, accentSoft;
    }
}
