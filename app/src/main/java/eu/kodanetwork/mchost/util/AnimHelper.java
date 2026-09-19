package eu.kodanetwork.mchost.util;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */

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
