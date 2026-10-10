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
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * the touch feedback of the sleek design system, no android ripple anywhere.
 * on press it scales to 0.90 and dims a bit, then springs back with overshoot.
 */
public class SleekTouch {

    public static void apply(final View v, final Runnable onClick) {
        apply(v, onClick, 50);
    }

    public static void apply(final View v, final Runnable onClick, final int hapticMs) {
        // mark it as SleekTouch territory, HapticUtil.applyHaptics would otherwise
        // replace this listener with a vibration-only one and eat the click
        v.setTag(eu.kodanetwork.mchost.R.id.sleek_touch_tag, Boolean.TRUE);
        v.setClickable(true);
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean pressed = false;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pressed = true;
                        view.animate().cancel();
                        view.animate()
                                .scaleX(0.90f).scaleY(0.90f)
                                .alpha(0.7f)
                                .setDuration(70)
                                .start();
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (pressed) {
                            pressed = false;
                            // the finger may have slid off the view
                            boolean inside = e.getX() >= 0 && e.getX() <= view.getWidth()
                                    && e.getY() >= 0 && e.getY() <= view.getHeight();
                            view.animate().cancel();
                            view.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(200)
                                    .setInterpolator(new OvershootInterpolator(3f))
                                    .start();
                            if (inside) {
                                if (hapticMs > 0) {
                                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(
                                            (android.content.Context) view.getContext(), hapticMs);
                                }
                                if (onClick != null) onClick.run();
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        pressed = false;
                        view.animate().cancel();
                        view.animate()
                                .scaleX(1f).scaleY(1f)
                                .alpha(1f)
                                .setDuration(150)
                                .start();
                        return true;
                }
                return false;
            }
        });
    }

    /** shortcut for the usual case: haptics plus the scale bounce. */
    public static void button(View v, Runnable onClick) {
        apply(v, onClick, 50);
    }

    /** visual press effect only, no haptics. */
    public static void passive(View v) {
        apply(v, null, 0);
    }
}
