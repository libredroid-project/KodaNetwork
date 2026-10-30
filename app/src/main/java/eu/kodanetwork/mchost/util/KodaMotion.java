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
import android.view.animation.DecelerateInterpolator;

/**
 * the app wide motion language: things do not just appear, they slide a few dp up
 * while fading in. lists stagger, a tab panel comes in as one block. short, calm,
 * and always the same curve so it reads as the app's own rhythm.
 */
public final class KodaMotion {

    private KodaMotion() {
    }

    /** the playful motion is a developer option; off means everything stays static. */
    public static boolean enabled(View view) {
        return view != null && eu.kodanetwork.mchost.App.getPrefs(view.getContext())
                .getBoolean("dev_playful_motion", false);
    }

    /** one list item, order is the position in the entrance (capped externally). */
    public static void revealItem(View view, int order) {
        if (view == null || !enabled(view)) return;
        float density = view.getResources().getDisplayMetrics().density;
        view.setAlpha(0f);
        view.setTranslationY(14f * density);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(300L, order * 45L))
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** a whole screen or tab panel, one calm slide instead of a hard cut. */
    public static void revealPanel(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || !enabled(view)) return;
        float density = view.getResources().getDisplayMetrics().density;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(10f * density);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }
}
