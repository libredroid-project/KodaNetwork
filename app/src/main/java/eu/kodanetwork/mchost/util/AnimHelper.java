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
import android.content.Intent;
import android.content.SharedPreferences;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

public class AnimHelper {

    public static boolean isAnimationsEnabled(Activity activity) {
        SharedPreferences prefs = App.getPrefs(activity);
        return prefs.getBoolean("animations_enabled", true);
    }

    public static void startWithFade(Activity activity, Intent intent) {
        activity.startActivity(intent);
        if (isAnimationsEnabled(activity)) {
            activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }

    public static void finishWithFade(Activity activity) {
        activity.finish();
        if (isAnimationsEnabled(activity)) {
            activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }

    public static void startSlideVertical(Activity activity, Intent intent) {
        activity.startActivity(intent);
        if (isAnimationsEnabled(activity)) {
            activity.overridePendingTransition(R.anim.slide_in_top, R.anim.slide_out_bottom);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }

}
