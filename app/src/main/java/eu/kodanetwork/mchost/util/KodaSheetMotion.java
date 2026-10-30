/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.util;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * the playful part of a sheet: the rows do not appear all at once, they slide up
 * one after another with a short fade. subtle enough for a product screen, but it
 * makes the sheet feel alive instead of painted.
 */
public final class KodaSheetMotion {

    private KodaSheetMotion() {
    }

    public static void reveal(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        float density = group.getResources().getDisplayMetrics().density;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(12f * density);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(50L + i * 45L)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /** a small pop when the destructive button becomes usable. */
    public static void pop(View view) {
        if (view == null) return;
        if (!eu.kodanetwork.mchost.App.getPrefs(view.getContext())
                .getBoolean("dev_playful_motion", false)) return;
        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }
}
