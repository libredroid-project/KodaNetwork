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
import eu.kodanetwork.mchost.App;
import android.net.TrafficStats;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * network budget system. the rules belong to a server, the usage is measured
 * APP-WIDE (every server shares one UID and TrafficStats cannot split per
 * server), so a rule really says "when the app used X on mobile/wifi in
 * period Y, do Z to THIS server".
 *
 * every meter tick reads the system UID counters and books the delta on the
 * network type that is active right now (mobile or wifi). the period
 * snapshots survive reboots, the UID counters do not.
 */
public class NetworkPolicy {

    public static final String ACTION_WARN = "warn";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_BLOCK = "block";

    public static class Rule {
        public String id;
        public long limitBytes;
        public int periodDays;          // 1 = day, 7 = week, 30 = month
        public boolean mobile, wifi;
        public String action;           // warn | stop | block
        public boolean paused;
    }

    public static class Usage {
        public long mobileBytes, wifiBytes;   // so far in this period
        public long periodStartMs;
    }

    // ── rules persistence (per server) ──────────────────────────────

    public static List<Rule> getRules(Context ctx, String serverId) {
        List<Rule> out = new ArrayList<>();
        try {
            String raw = App.getPrefs(ctx).getString("net_rules_" + serverId, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Rule r = new Rule();
                r.id = o.optString("id", UUID.randomUUID().toString());
                r.limitBytes = o.getLong("limit");
                r.periodDays = o.optInt("period", 7);
                r.mobile = o.optBoolean("mobile", true);
                r.wifi = o.optBoolean("wifi", true);
                r.action = o.optString("action", ACTION_WARN);
                r.paused = o.optBoolean("paused", false);
                out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveRules(Context ctx, String serverId, List<Rule> rules) {
        try {
            JSONArray arr = new JSONArray();
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("limit", r.limitBytes);
                o.put("period", r.periodDays);
                o.put("mobile", r.mobile);
                o.put("wifi", r.wifi);
                o.put("action", r.action);
                o.put("paused", r.paused);
                arr.put(o);
            }
            App.getPrefs(ctx).edit().putString("net_rules_" + serverId, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ── usage metering ───────────────────────────────────────────────

    private static long lastUidBytes = -1;
    private static long lastDeltaBytes = 0;
    private static long lastTickMs = 0;

    /** the speed from the last meter tick, in bytes per second. */
    public static float speedBps() {
        long dt = System.currentTimeMillis() - lastTickMs;
        if (dt <= 0 || dt > 60000) return 0f;
        return lastDeltaBytes / (dt / 1000f);
    }
    private static boolean lastWasMobile = false;

    /**
     * call this every few seconds while servers run. the UID traffic delta goes
     * onto the active network type. period windows roll over by themselves.
     */
    public static void meterTick(Context ctx, boolean onMobile) {
        try {
            long now = System.currentTimeMillis();
            long uid = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    + TrafficStats.getUidTxBytes(android.os.Process.myUid());
            if (lastUidBytes < 0) { lastUidBytes = uid; lastWasMobile = onMobile; return; }
            long delta = uid - lastUidBytes;
            lastUidBytes = uid;
            lastDeltaBytes = Math.max(0, delta);
            lastTickMs = System.currentTimeMillis();
            if (delta <= 0) return;

            long prev = App.getPrefs(ctx).getLong("net_acc_bytes", 0);
            App.getPrefs(ctx).edit().putLong("net_acc_bytes", prev + delta).apply();

            String key = onMobile ? "net_acc_mobile" : "net_acc_wifi";
            // the type may have switched mid-tick, so the delta goes on the new one
            long prevType = App.getPrefs(ctx).getLong(key, 0);
            App.getPrefs(ctx).edit().putLong(key, prevType + delta)
                    .putLong("net_last_tick", now).apply();
            lastWasMobile = onMobile;
        } catch (Exception ignored) {}
    }

    /** usage for one period, the counters roll over once the window expired. */
    public static Usage usageFor(Context ctx, int periodDays) {
        Usage u = new Usage();
        long now = System.currentTimeMillis();
        long winMs = periodDays * 24L * 3600L * 1000L;
        long start = App.getPrefs(ctx).getLong("net_period_start_" + periodDays, 0);
        if (start == 0 || now - start >= winMs) {
            // the window starts, so the current totals become the baseline
            start = now;
            App.getPrefs(ctx).edit()
                    .putLong("net_period_start_" + periodDays, now)
                    .putLong("net_base_mobile_" + periodDays, App.getPrefs(ctx).getLong("net_acc_mobile", 0))
                    .putLong("net_base_wifi_" + periodDays, App.getPrefs(ctx).getLong("net_acc_wifi", 0))
                    .apply();
        }
        u.periodStartMs = start;
        u.mobileBytes = App.getPrefs(ctx).getLong("net_acc_mobile", 0)
                - App.getPrefs(ctx).getLong("net_base_mobile_" + periodDays, 0);
        u.wifiBytes = App.getPrefs(ctx).getLong("net_acc_wifi", 0)
                - App.getPrefs(ctx).getLong("net_base_wifi_" + periodDays, 0);
        return u;
    }

    /**
     * the harshest rule that is broken right now, for the CURRENT network type.
     * returns null when none of them is.
     */
    public static Rule violatedRule(Context ctx, String serverId, boolean onMobile) {
        Rule worst = null;
        for (Rule r : getRules(ctx, serverId)) {
            if (r.paused) continue;
            if (!(onMobile ? r.mobile : r.wifi)) continue;
            Usage u = usageFor(ctx, r.periodDays);
            long used = onMobile ? u.mobileBytes : u.wifiBytes;
            if (used >= r.limitBytes) {
                if (worst == null || severity(r.action) > severity(worst.action)) worst = r;
            }
        }
        return worst;
    }

    /** 0..100 for the most consumed matching rule, the dashboard tile wants this. */
    public static int progressPercent(Context ctx, String serverId, boolean onMobile) {
        int max = 0;
        for (Rule r : getRules(ctx, serverId)) {
            if (r.paused) continue;
            if (r.paused) continue;
            if (!(onMobile ? r.mobile : r.wifi)) continue;
            Usage u = usageFor(ctx, r.periodDays);
            long used = onMobile ? u.mobileBytes : u.wifiBytes;
            if (r.limitBytes <= 0) continue;
            max = Math.max(max, (int) Math.min(100, used * 100 / r.limitBytes));
        }
        return max;
    }

    public static int severity(String action) {
        if (ACTION_BLOCK.equals(action)) return 3;
        if (ACTION_STOP.equals(action)) return 2;
        if (ACTION_WARN.equals(action)) return 1;
        return 0;
    }

    /** traffic light colours: green under 70, yellow under 85, orange under 90, red from 90. */
    public static int colorForPercent(int pct) {
        if (pct >= 90) return 0xFFFF4444;
        if (pct >= 85) return 0xFFFF8C38;
        if (pct >= 70) return 0xFFFFCC00;
        return 0xFF3DBE3D;
    }

    public static String humanBytes(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f GB", b / 1073741824f);
        if (b >= 1024L * 1024) return String.format(java.util.Locale.US, "%.0f MB", b / 1048576f);
        return (b / 1024) + " KB";
    }
}
