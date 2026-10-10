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
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeTonalSpot;

import java.util.ArrayDeque;
import java.util.Deque;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

/**
 * material 3 theming for the "Material 3" developer option.
 *
 * sticks to the official android / material 3 advice:
 * - one complete M3 scheme (Theme.KodaNetwork.Material3) with every colour
 *   role, DayNight through values / values-night.
 * - the in-app theme mode (dark/light/auto) is honoured with the official
 *   overlays, applied via Theme.applyStyle.
 * - dynamic colour uses the official DynamicColors api, which re-themes
 *   every material component by itself.
 * - custom seed colours build a full scheme at runtime with google's
 *   material-color-utilities engine (SchemeTonalSpot), the same engine
 *   behind Material Theme Builder.
 * - screens that are not token-based yet go through a traversal pass that
 *   puts M3 roles and shapes on the legacy views, so every menu ends up
 *   looking right in M3.
 */
public class Material3ThemeHelper {
    private static final String TAG = "Material3ThemeHelper";
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** the seed presets the developer options offer. */
    public static final int[] COLOR_PRESETS = {
            0xFF6750A4, // material purple
            0xFFB3261E, // ruby red
            0xFF006874, // ocean blue
            0xFF006C4C, // teal
            0xFF386A20, // forest green
            0xFF825500, // amber
            0xFFFF6B00, // kodahosting orange
            0xFF4A4458  // midnight slate
    };

    // ─── settings state ──────────────────────────────────────────

    public static boolean isM3Enabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = App.getPrefs(context);
        return prefs != null && prefs.getBoolean("dev_material3_enabled", false);
    }

    /** "dynamic" takes the wallpaper colour, "custom" a preset seed. */
    public static String getColorMode(Context context) {
        if (context == null) return "dynamic";
        return App.getPrefs(context).getString("m3_color_mode", "dynamic");
    }

    public static int getCustomColor(Context context) {
        if (context == null) return 0xFFFF6B00;
        return App.getPrefs(context).getInt("m3_custom_color", 0xFFFF6B00);
    }

    // ─── theme plumbing (call before super.onCreate) ─────────────

    /**
     * puts the material 3 theme on the activity. has to run before
     * {@code super.onCreate}, otherwise {@code setContentView} gets the
     * old theme.
     */
    public static void applyTheme(Activity activity) {
        if (activity == null || !isM3Enabled(activity)) return;
        activity.setTheme(R.style.Theme_KodaNetwork_Material3);

        String mode = App.getPrefs(activity).getString("theme_mode", "dark");
        if ("light".equals(mode)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_KodaNetwork_M3_Light, true);
        } else if ("dark".equals(mode)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_KodaNetwork_M3_Dark, true);
        }

        if ("dynamic".equals(getColorMode(activity))) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity);
        }
    }

    // ─── color roles ─────────────────────────────────────────────

    /** every M3 role the styling engine touches. */
    public static final class Roles {
        public int primary, onPrimary, primaryContainer, onPrimaryContainer;
        public int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
        public int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
        public int error, onError, errorContainer, onErrorContainer;
        public int background, onBackground;
        public int surface, onSurface, surfaceVariant, onSurfaceVariant;
        public int surfaceDim, surfaceBright;
        public int surfaceContainerLowest, surfaceContainerLow, surfaceContainer;
        public int surfaceContainerHigh, surfaceContainerHighest;
        public int outline, outlineVariant;
        public int inverseSurface, inverseOnSurface, inversePrimary;
        public boolean dark;
    }

    /**
     * picks the active color roles. dynamic color or the default koda
     * scheme reads them straight from the (already themed) activity
     * theme; a custom seed gets a whole scheme generated by google's
     * SchemeTonalSpot engine.
     */
    public static Roles resolveRoles(Context context) {
        Roles r = new Roles();
        r.dark = !ThemeHelper.isLightMode(context);
        if ("custom".equals(getColorMode(context))) {
            int seed = getCustomColor(context);
            DynamicScheme scheme = new SchemeTonalSpot(Hct.fromInt(seed), r.dark, 0.0);
            MaterialDynamicColors mdc = new MaterialDynamicColors();
            r.primary = mdc.primary().getArgb(scheme);
            r.onPrimary = mdc.onPrimary().getArgb(scheme);
            r.primaryContainer = mdc.primaryContainer().getArgb(scheme);
            r.onPrimaryContainer = mdc.onPrimaryContainer().getArgb(scheme);
            r.secondary = mdc.secondary().getArgb(scheme);
            r.onSecondary = mdc.onSecondary().getArgb(scheme);
            r.secondaryContainer = mdc.secondaryContainer().getArgb(scheme);
            r.onSecondaryContainer = mdc.onSecondaryContainer().getArgb(scheme);
            r.tertiary = mdc.tertiary().getArgb(scheme);
            r.onTertiary = mdc.onTertiary().getArgb(scheme);
            r.tertiaryContainer = mdc.tertiaryContainer().getArgb(scheme);
            r.onTertiaryContainer = mdc.onTertiaryContainer().getArgb(scheme);
            r.error = mdc.error().getArgb(scheme);
            r.onError = mdc.onError().getArgb(scheme);
            r.errorContainer = mdc.errorContainer().getArgb(scheme);
            r.onErrorContainer = mdc.onErrorContainer().getArgb(scheme);
            r.background = mdc.background().getArgb(scheme);
            r.onBackground = mdc.onBackground().getArgb(scheme);
            r.surface = mdc.surface().getArgb(scheme);
            r.onSurface = mdc.onSurface().getArgb(scheme);
            r.surfaceVariant = mdc.surfaceVariant().getArgb(scheme);
            r.onSurfaceVariant = mdc.onSurfaceVariant().getArgb(scheme);
            r.surfaceDim = mdc.surfaceDim().getArgb(scheme);
            r.surfaceBright = mdc.surfaceBright().getArgb(scheme);
            r.surfaceContainerLowest = mdc.surfaceContainerLowest().getArgb(scheme);
            r.surfaceContainerLow = mdc.surfaceContainerLow().getArgb(scheme);
            r.surfaceContainer = mdc.surfaceContainer().getArgb(scheme);
            r.surfaceContainerHigh = mdc.surfaceContainerHigh().getArgb(scheme);
            r.surfaceContainerHighest = mdc.surfaceContainerHighest().getArgb(scheme);
            r.outline = mdc.outline().getArgb(scheme);
            r.outlineVariant = mdc.outlineVariant().getArgb(scheme);
            r.inverseSurface = mdc.inverseSurface().getArgb(scheme);
            r.inverseOnSurface = mdc.inverseOnSurface().getArgb(scheme);
            r.inversePrimary = mdc.inversePrimary().getArgb(scheme);
        } else {
            // the theme attrs already carry the koda scheme, or the dynamic
            // one when DynamicColors was applied.
            r.primary = attr(context, com.google.android.material.R.attr.colorPrimary, r.dark ? 0xFFFFB693 : 0xFF8D4D2D);
            r.onPrimary = attr(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
            r.primaryContainer = attr(context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFFFDBCC);
            r.onPrimaryContainer = attr(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF351000);
            r.secondary = attr(context, com.google.android.material.R.attr.colorSecondary, 0xFF765749);
            r.onSecondary = attr(context, com.google.android.material.R.attr.colorOnSecondary, Color.WHITE);
            r.secondaryContainer = attr(context, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFFFDBCC);
            r.onSecondaryContainer = attr(context, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF2C160B);
            r.tertiary = attr(context, com.google.android.material.R.attr.colorTertiary, 0xFF655F31);
            r.onTertiary = attr(context, com.google.android.material.R.attr.colorOnTertiary, Color.WHITE);
            r.tertiaryContainer = attr(context, com.google.android.material.R.attr.colorTertiaryContainer, 0xFFEDE4A9);
            r.onTertiaryContainer = attr(context, com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF201C00);
            r.error = attr(context, com.google.android.material.R.attr.colorError, 0xFFBA1A1A);
            r.onError = attr(context, com.google.android.material.R.attr.colorOnError, Color.WHITE);
            r.errorContainer = attr(context, com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6);
            r.onErrorContainer = attr(context, com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002);
            r.background = attr(context, android.R.attr.colorBackground, r.dark ? 0xFF1A120E : 0xFFFFF8F6);
            r.onBackground = attr(context, com.google.android.material.R.attr.colorOnBackground, r.dark ? 0xFFF0DFD8 : 0xFF221A16);
            r.surface = attr(context, com.google.android.material.R.attr.colorSurface, r.background);
            r.onSurface = attr(context, com.google.android.material.R.attr.colorOnSurface, r.onBackground);
            r.surfaceVariant = attr(context, com.google.android.material.R.attr.colorSurfaceVariant, r.dark ? 0xFF52443D : 0xFFF4DED5);
            r.onSurfaceVariant = attr(context, com.google.android.material.R.attr.colorOnSurfaceVariant, r.dark ? 0xFFD7C2B9 : 0xFF52443D);
            r.surfaceDim = attr(context, com.google.android.material.R.attr.colorSurfaceDim, r.dark ? 0xFF1A120E : 0xFFE8D6D0);
            r.surfaceBright = attr(context, com.google.android.material.R.attr.colorSurfaceBright, r.dark ? 0xFF423732 : 0xFFFFF8F6);
            r.surfaceContainerLowest = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLowest, r.dark ? 0xFF140C09 : 0xFFFFFFFF);
            r.surfaceContainerLow = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLow, r.dark ? 0xFF221A16 : 0xFFFFF1EB);
            r.surfaceContainer = attr(context, com.google.android.material.R.attr.colorSurfaceContainer, r.dark ? 0xFF271E1A : 0xFFFCEAE3);
            r.surfaceContainerHigh = attr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, r.dark ? 0xFF322824 : 0xFFF6E5DE);
            r.surfaceContainerHighest = attr(context, com.google.android.material.R.attr.colorSurfaceContainerHighest, r.dark ? 0xFF3D332E : 0xFFF0DFD8);
            r.outline = attr(context, com.google.android.material.R.attr.colorOutline, r.dark ? 0xFFA08D85 : 0xFF85736C);
            r.outlineVariant = attr(context, com.google.android.material.R.attr.colorOutlineVariant, r.dark ? 0xFF52443D : 0xFFD7C2B9);
            r.inverseSurface = attr(context, com.google.android.material.R.attr.colorSurfaceInverse, r.dark ? 0xFFF0DFD8 : 0xFF382E2A);
            r.inverseOnSurface = attr(context, com.google.android.material.R.attr.colorOnSurfaceInverse, r.dark ? 0xFF382E2A : 0xFFFFEDE6);
            r.inversePrimary = attr(context, com.google.android.material.R.attr.colorPrimaryInverse, r.dark ? 0xFF8D4D2D : 0xFFFFB693);
        }
        applyExtensionTheme(context, r);
        return r;
    }

    /**
     * extensions can bring whole themes (contributes.themes[].colors): role
     * colours that override the generated scheme, so the styling engine ends
     * up painting with the extension's palette.
     */
    private static void applyExtensionTheme(Context context, Roles r) {
        try {
            java.util.Map<String, Integer> colors =
                    eu.kodanetwork.mchost.extension.ExtensionThemes.activeColors(context);
            if (colors.isEmpty()) return;

            Integer primary = colors.get("primary");
            if (primary != null) {
                r.primary = primary;
                r.onPrimary = contrast(primary);
                r.inversePrimary = primary;
                r.primaryContainer = primary;
                r.onPrimaryContainer = contrast(primary);
            }
            Integer surface = colors.get("surface");
            if (surface != null) {
                r.surface = surface;
                r.background = surface;
                r.surfaceDim = surface;
                r.surfaceContainerLowest = surface;
                r.surfaceContainerLow = surface;
            }
            Integer card = colors.get("card");
            if (card != null) {
                r.surfaceContainer = card;
                r.surfaceContainerHigh = card;
                r.surfaceContainerHighest = card;
                r.surfaceVariant = card;
                r.surfaceBright = card;
            }
            Integer text = colors.get("text");
            if (text != null) {
                r.onSurface = text;
                r.onBackground = text;
            }
            Integer textDim = colors.get("text_dim");
            if (textDim != null) {
                r.onSurfaceVariant = textDim;
                r.outline = textDim;
                r.outlineVariant = textDim;
                r.inverseOnSurface = textDim;
            }
            Integer outline = colors.get("outline");
            if (outline != null) {
                r.outline = outline;
                r.outlineVariant = outline;
            }
            Integer online = colors.get("online");
            if (online != null) {
                r.tertiary = online;
                r.onTertiary = contrast(online);
                r.tertiaryContainer = online;
                r.onTertiaryContainer = contrast(online);
            }
            Integer error = colors.get("error");
            if (error != null) {
                r.error = error;
                r.onError = contrast(error);
            }
        } catch (Throwable ignored) {
        }
    }

    /** black or white, whichever stays readable on that colour. */
    private static int contrast(int color) {
        return androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static int attr(Context context, int attr, int fallback) {
        try {
            return MaterialColors.getColor(context, attr, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ─── styling engine (delegated from ThemeHelper) ─────────────

    /**
     * styles an activity that is already laid out. comes in through
     * {@link ThemeHelper#apply(Activity)}, which delegates here whenever the
     * M3 developer option is on.
     */
    public static void apply(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        main.postDelayed(() -> {
            try {
                Roles roles = resolveRoles(activity);
                View root = activity.findViewById(android.R.id.content);
                if (root != null) root.setBackgroundColor(roles.surface);
                styleTree(root, roles);
                applySystemBars(activity, roles);
            } catch (Throwable t) {
                Log.e(TAG, "M3 styling failed", t);
            }
        }, 50);
    }

    /** styles dialogs and bottom sheets that open while M3 is on. */
    public static void apply(Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return;
        main.postDelayed(() -> {
            try {
                Roles roles = resolveRoles(dialog.getContext());
                if (dialog.getWindow() != null
                        && !(dialog instanceof com.google.android.material.bottomsheet.BottomSheetDialog)) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(roles.surfaceContainerHigh));
                }
                View root = dialog.findViewById(android.R.id.content);
                if (root != null) styleTree(root, roles);
            } catch (Throwable ignored) {}
        }, 50);
    }

    /** styles a subtree, adapter items and bottom sheet content mostly. */
    public static void styleTree(View root, Roles roles) {
        if (root == null) return;
        Deque<View> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v == null) continue;
            Object tag = v.getTag(R.id.tag_themed);
            if ("BLOCKED".equals(tag)) {
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
                continue;
            }
            if (!"M3".equals(tag)) {
                v.setTag(R.id.tag_themed, "M3");
                try {
                    styleView(v, roles);
                } catch (Exception ignored) {}
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            }
        }
    }

    private static void applySystemBars(Activity activity, Roles roles) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(roles.surface);
            activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
            if (controller != null) {
                controller.setAppearanceLightStatusBars(!roles.dark);
                controller.setAppearanceLightNavigationBars(!roles.dark);
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                activity.getWindow().setNavigationBarContrastEnforced(false);
            }
        }
    }

    private static float dp(View v, float dp) {
        return dp * v.getResources().getDisplayMetrics().density;
    }

    private static boolean isOrange(int c) {
        return c == 0xFFFF6B00 || c == 0xFFFF6A00 || c == 0xFFFF8C38
                || c == 0xFFCC5500 || c == 0xFFE65100;
    }

    private static boolean isRedish(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r > 150 && g < 110 && b < 110;
    }

    private static void styleView(View v, Roles roles) {
        boolean isButton = (v instanceof Button || v instanceof MaterialButton)
                && !(v instanceof CompoundButton);

        // ── text ──────────────────────────────────────────────
        if (v instanceof TextView && !isButton) {
            TextView tv = (TextView) v;
            if (v instanceof EditText) {
                // M3 filled text field, the bowl is surfaceContainerHighest.
                ((EditText) v).setTextColor(roles.onSurface);
                ((EditText) v).setHintTextColor(roles.onSurfaceVariant);
                GradientDrawable field = new GradientDrawable();
                field.setColor(roles.surfaceContainerHighest);
                field.setCornerRadii(new float[]{dp(v,4), dp(v,4), dp(v,4), dp(v,4), 0, 0, 0, 0});
                v.setBackground(field);
            } else {
                // first undo the legacy type treatment, then the M3 colours go on.
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                tv.setAllCaps(false);
                tv.setLetterSpacing(0f);
                tv.setShadowLayer(0, 0, 0, 0);

                float sp = tv.getTextSize() / (tv.getResources().getDisplayMetrics().scaledDensity > 0
                        ? tv.getResources().getDisplayMetrics().scaledDensity : 1f);
                boolean header = sp > 22;
                int cur = tv.getTextColors().getDefaultColor();
                if (cur == roles.primary || cur == roles.onSurface
                        || cur == roles.onSurfaceVariant || cur == roles.error
                        || cur == roles.primaryContainer || cur == roles.onPrimary
                        || cur == roles.onPrimaryContainer || cur == roles.tertiary) {
                    // already a resolved M3 token (token-based layout or an
                    // earlier engine pass), leave it alone
                } else if (isOrange(cur)) {
                    tv.setTextColor(roles.primary);
                } else if (isRedish(cur)) {
                    tv.setTextColor(roles.error);
                } else if (header) {
                    tv.setTextColor(roles.onSurface);
                } else if (cur == Color.WHITE || cur == 0xFFF0F0F0 || cur == 0xFFEEEEEE
                        || cur == 0xFFDDDDDD || cur == 0xFFBBBBCC || cur == 0xFFE6E6FA) {
                    tv.setTextColor(roles.onSurface);
                } else {
                    // greyish secondary colours from the old designs
                    tv.setTextColor(roles.onSurfaceVariant);
                }
            }
        }

        // ── buttons: M3 filled / text / error variants ────────
        if (isButton) {
            if (v instanceof MaterialButton) {
                MaterialButton mb = (MaterialButton) v;
                int tint = mb.getBackgroundTintList() != null
                        ? mb.getBackgroundTintList().getDefaultColor() : 0;
                mb.setStateListAnimator(null);
                mb.setElevation(0f);
                mb.setStrokeWidth(0);
                mb.setCornerRadius((int) dp(v, 20)); // M3 wants it fully rounded
                mb.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                mb.setAllCaps(false);
                if (isRedish(tint)) {
                    mb.setBackgroundTintList(ColorStateList.valueOf(roles.error));
                    mb.setTextColor(roles.onError);
                    if (mb.getIconTint() != null) mb.setIconTint(ColorStateList.valueOf(roles.onError));
                } else if (tint == 0 || tint == Color.TRANSPARENT) {
                    // a transparent legacy button is an M3 text button.
                    mb.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                    mb.setTextColor(roles.primary);
                    if (mb.getIconTint() != null) mb.setIconTint(ColorStateList.valueOf(roles.primary));
                } else {
                    mb.setBackgroundTintList(ColorStateList.valueOf(roles.primary));
                    mb.setTextColor(roles.onPrimary);
                    if (mb.getIconTint() != null) mb.setIconTint(ColorStateList.valueOf(roles.onPrimary));
                }
            } else {
                Button b = (Button) v;
                int cur = b.getTextColors().getDefaultColor();
                boolean destructive = isRedish(b.getBackgroundTintList() != null
                        ? b.getBackgroundTintList().getDefaultColor() : 0) || isRedish(cur);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(destructive ? roles.error : roles.primary);
                bg.setCornerRadius(dp(v, 20));
                b.setBackground(bg);
                b.setTextColor(destructive ? roles.onError : roles.onPrimary);
                b.setAllCaps(false);
                b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                b.setPadding(b.getPaddingLeft(), b.getPaddingTop(), b.getPaddingRight(), b.getPaddingBottom());
            }
        }

        // ── switches: M3 switch colors ────────────────────────
        if (v instanceof SwitchCompat) {
            SwitchCompat sw = (SwitchCompat) v;
            int[][] states = {{android.R.attr.state_checked}, {-android.R.attr.state_checked}};
            sw.setThumbTintList(new ColorStateList(states,
                    new int[]{roles.onPrimary, roles.outline}));
            sw.setTrackTintList(new ColorStateList(states,
                    new int[]{roles.primary, roles.surfaceContainerHighest}));
        } else if (v instanceof com.google.android.material.materialswitch.MaterialSwitch) {
            com.google.android.material.materialswitch.MaterialSwitch ms =
                    (com.google.android.material.materialswitch.MaterialSwitch) v;
            int[][] states = {{android.R.attr.state_checked}, {-android.R.attr.state_checked}};
            ms.setThumbTintList(new ColorStateList(states,
                    new int[]{roles.onPrimary, roles.outline}));
            ms.setTrackTintList(new ColorStateList(states,
                    new int[]{roles.primary, roles.surfaceContainerHighest}));
            ms.setTrackDecorationTintList(new ColorStateList(states,
                    new int[]{roles.onPrimaryContainer, roles.surfaceContainerHighest}));
        }

        // ── cards: M3 filled card ─────────────────────────────
        if (v instanceof androidx.cardview.widget.CardView) {
            androidx.cardview.widget.CardView card = (androidx.cardview.widget.CardView) v;
            card.setCardBackgroundColor(roles.surfaceContainer);
            card.setRadius(dp(v, 12));
            card.setCardElevation(0f);
        }

        // ── legacy colored surfaces ───────────────────────────
        if (v instanceof ViewGroup
                && !(v instanceof androidx.cardview.widget.CardView)
                && v.getBackground() != null
                && v.getId() != android.R.id.content) {
            String cls = v.getClass().getSimpleName();
            if (cls.contains("RecyclerView") || cls.contains("Scroll")
                    || cls.contains("Pager") || cls.contains("Coordinator")) return;
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg instanceof RippleDrawable) return; // M3 ripples stay as they are
            if (bg instanceof ColorDrawable) {
                int c = ((ColorDrawable) bg).getColor();
                if (c == Color.TRANSPARENT) return;
                v.setBackgroundColor(roles.surfaceContainer);
            } else if (bg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) bg;
                gd.setColor(roles.surfaceContainer);
                gd.setStroke(0, Color.TRANSPARENT);
            }
        }

        // ── back arrows and friends ──────────────────────────────────
        if (v instanceof ImageButton && v.getId() == R.id.btn_back) {
            ((ImageButton) v).setColorFilter(roles.onSurfaceVariant);
        }
    }
}
