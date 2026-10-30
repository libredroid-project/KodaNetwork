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

import android.content.Context;
import android.telephony.TelephonyManager;

import java.util.Locale;
import java.util.TimeZone;

/**
 * the app is not offered in mainland China, so a device that sits there does not get
 * past the first screen. checked before any identity or server work happens.
 *
 * this is the client side signal (sim/network country and the time zone), not a geo ip
 * lookup. a vpn cannot hide the time zone, but someone can change it by hand.
 */
public final class RegionGuard {

    private RegionGuard() {
    }

    public static boolean isBlocked(Context context) {
        // dev option: pretend to be in China for one minute to check the notice
        if (context != null) {
            try {
                long until = eu.kodanetwork.mchost.App.getPrefs(context)
                        .getLong("dev_region_simulate_until", 0L);
                if (until > System.currentTimeMillis()) return true;
            } catch (Exception ignored) {
            }
        }
        try {
            if (context != null) {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    String sim = tm.getSimCountryIso();
                    String network = tm.getNetworkCountryIso();
                    if ("cn".equalsIgnoreCase(sim) || "cn".equalsIgnoreCase(network)) return true;
                }
            }
        } catch (Exception ignored) {
            // no telephony (tablet, wifi only) falls through to the time zone
        }
        try {
            String id = TimeZone.getDefault().getID();
            if (id != null) {
                id = id.toLowerCase(Locale.ROOT);
                if (id.equals("asia/shanghai") || id.equals("asia/chongqing")
                        || id.equals("asia/harbin") || id.equals("asia/urumqi")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
