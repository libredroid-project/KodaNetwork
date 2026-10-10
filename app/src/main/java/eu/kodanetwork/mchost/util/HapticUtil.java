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
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;

public class HapticUtil {

    public static void forceVibrate(Context context, int duration) {
        if (context == null) return;
        boolean enabled = eu.kodanetwork.mchost.App.getPrefs(context).getBoolean("haptics_enabled", true);
        if (!enabled) return;
        try {
            Vibrator v = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    v = vm.getDefaultVibrator();
                }
            }
            if (v == null) {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, 200));
                } else {
                    v.vibrate(duration);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void applyHaptics(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View root = activity.getWindow().getDecorView().getRootView();
        applyHapticsToView(root, activity);
    }

    public static void applyHapticsToView(View root, Context context) {
        if (root == null) return;

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyHapticsToView(vg.getChildAt(i), context);
            }
        }

        // SeekBars have their own fine grained gestures, leave them out
        if (root instanceof android.widget.SeekBar) {
            return;
        }

        // SleekTouch handles the whole touch feedback (press animation + click +
        // haptic). stealing its listener here would silently kill the button click.
        if (root.getTag(eu.kodanetwork.mchost.R.id.sleek_touch_tag) != null) {
            return;
        }

        // everything clickable: buttons, cards, textviews with a click listener
        if (root.isClickable() || root.hasOnClickListeners() || root instanceof Button || root instanceof ImageButton || root.getClass().getName().contains("Button")) {
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    // short and soft, a long buzz on a tap is awful
                    forceVibrate(context, 40);
                }
                return false; 
            });
        }
        
        // scroll vibrations were removed, they are way too annoying
        // NumberPicker wheel feedback
        if (root instanceof android.widget.NumberPicker) {
            android.widget.NumberPicker np = (android.widget.NumberPicker) root;
            np.setOnScrollListener((view, scrollState) -> {
                // NumberPicker.OnScrollListener.SCROLL_STATE_FLING or SCROLL_STATE_TOUCH_SCROLL
                // actually, a small buzz per state change is enough, settling would be nicer
                // OnValueChangedListener would be cleaner, but CreateServerActivity already has it
                // and overwriting it from here would break that screen
                // so the scroll state has to do
                if (scrollState == android.widget.NumberPicker.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL || scrollState == android.widget.NumberPicker.OnScrollListener.SCROLL_STATE_FLING) {
                    forceVibrate(context, 40);
                }
            });
            // buzzing when it settles would need the value listener, which may be taken
            // the formatter runs on every step, so it can trigger the buzz instead
            np.setFormatter(value -> {
                forceVibrate(context, 40);
                return String.valueOf(value);
            });
        }
    }
}
