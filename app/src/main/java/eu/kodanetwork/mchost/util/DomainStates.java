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

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * the per-domain switch the admins control in the panel and the admin app
 * (app_settings.domain_states):
 *
 *   "active"  normal, new servers and existing ones both fine
 *   "no_new"  existing servers keep running, NEW servers cannot pick this domain
 *   "off"     completely off: existing servers are offered a temporary move to
 *             another domain and move back automatically once the domain is back
 *
 * a missing entry counts as active. cached for a minute.
 */
public final class DomainStates {

    public static final String ACTIVE = "active";
    public static final String NO_NEW = "no_new";
    public static final String OFF = "off";

    /** the domains the network knows. */
    public static final String[] KNOWN_DOMAINS = { "kodanetwork.eu", "kodaserv.eu" };

    private static Map<String, String> cache = null;
    private static long fetchedAt = 0L;

    private DomainStates() {}

    /** the current modes, never null; on a network error the last known set stays. */
    public static synchronized Map<String, String> get(Context ctx) {
        if (cache != null && System.currentTimeMillis() - fetchedAt < 60_000L) return cache;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/rest/v1/app_settings?key=eq.domain_states&select=value").openConnection();
            c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            try (InputStream is = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) out.write(buf, 0, r);
                org.json.JSONArray arr = new org.json.JSONArray(out.toString("UTF-8"));
                if (arr.length() > 0) {
                    JSONObject value = arr.getJSONObject(0).optJSONObject("value");
                    Map<String, String> parsed = new HashMap<>();
                    if (value != null) {
                        java.util.Iterator<String> keys = value.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            parsed.put(key.toLowerCase(), value.optString(key, ACTIVE));
                        }
                    }
                    cache = parsed;
                    fetchedAt = System.currentTimeMillis();
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("DomainStates", "fetch failed: " + e.getMessage());
        }
        return cache != null ? cache : new HashMap<>();
    }

    public static String modeOf(Context ctx, String domain) {
        if (domain == null) return ACTIVE;
        String mode = get(ctx).get(domain.toLowerCase());
        return mode == null ? ACTIVE : mode;
    }

    /** new servers may only go onto fully active domains. */
    public static boolean allowsNewServers(String mode) {
        return ACTIVE.equals(mode);
    }

    /** "off" domains offer their servers a temporary move. */
    public static boolean isOff(String mode) {
        return OFF.equals(mode);
    }

    /** the first other domain that can serve a temporary move, null when there is none. */
    public static String fallbackDomain(Context ctx, String currentDomain) {
        for (String domain : KNOWN_DOMAINS) {
            if (domain.equalsIgnoreCase(currentDomain)) continue;
            if (!isOff(modeOf(ctx, domain))) return domain;
        }
        return null;
    }
}
