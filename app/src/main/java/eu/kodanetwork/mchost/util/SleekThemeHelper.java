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
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

/**
 * the sleek design system: warm premium dark theme with cream cards and pill buttons.
 * behind the dev switch `dev_sleek_enabled`, and it outranks M3 and the classic design.
 */
public class SleekThemeHelper {

    public static boolean isSleekEnabled(Context ctx) {
        return App.getPrefs(ctx).getBoolean("dev_sleek_enabled", false);
    }

    /** call before super.onCreate. */
    public static void applyTheme(Activity activity) {
        if (!isSleekEnabled(activity)) return;
        activity.setTheme(R.style.Theme_KodaNetwork_Sleek);
        activity.getWindow().setStatusBarColor(0xFF1C1917);
        activity.getWindow().setNavigationBarColor(0xFF1C1917);
    }

    /** light touch styling for legacy views that live inside sleek screens. */
    public static void apply(Activity activity) {
        if (!isSleekEnabled(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root != null) styleTree(root);
    }

    private static void styleTree(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            // warm text colours, the cold blue gray does not fit here
            int color = tv.getCurrentTextColor();
            if (color == 0xFF8A8A9A) tv.setTextColor(0xFF9C968F);
            else if (color == 0xFF555566) tv.setTextColor(0xFF5C5852);
            else if (color == 0xFFF0F0F0) tv.setTextColor(0xFFFFFFFF);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(g.getChildAt(i));
        }
    }
}
