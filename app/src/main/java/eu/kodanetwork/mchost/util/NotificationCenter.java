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
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.network.supabase.SupportApi;

/**
 * the in app notification inbox.
 *
 * the server keeps the notifications (app_notifications) and per device read/delete
 * state, this class is the app side: fetch, cache, unread count, category mutes and
 * the deep links. no push service is involved, the foreground service polls every
 * 30 seconds and posts a local notification for new entries.
 */
public final class NotificationCenter {

    public static final String KIND_SYSTEM = "SYSTEM";
    public static final String KIND_SUPPORT = "SUPPORT";
    public static final String KIND_RELEASE = "RELEASE";
    public static final String KIND_SECURITY = "SECURITY";
    public static final String KIND_ACCOUNT = "ACCOUNT";
    public static final String KIND_DATA = "DATA";

    public static final String[] KINDS = {
            KIND_SYSTEM, KIND_SUPPORT, KIND_RELEASE, KIND_SECURITY, KIND_ACCOUNT, KIND_DATA
    };

    private static final String PREF_CACHE = "notif_cache";
    private static final String PREF_UNREAD = "notif_unread";
    private static final String PREF_LAST_ID = "notif_last_id";
    private static final String PREF_MUTES = "notif_mutes";
    private static final int CACHE_LIMIT = 60;

    private NotificationCenter() {
    }

    /** one row of the inbox, parsed from the RPC. */
    public static final class Item {
        public String id = "";
        public String kind = KIND_SYSTEM;
        public String title = "";
        public String body = "";
        public String action = "";
        public String severity = "NORMAL";
        public String i18nKey = "";
        public boolean read;
        public long createdAt;

        public boolean isImportant() {
            return "IMPORTANT".equalsIgnoreCase(severity);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return App.getPrefs(context);
    }

    public static List<Item> cached(Context context) {
        List<Item> items = new ArrayList<>();
        String raw = prefs(context).getString(PREF_CACHE, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Item item = fromJson(array.getJSONObject(i));
                // system notifications carry a key, so the text is localised in the app
                localise(context, item);
                items.add(item);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    public static int unreadCount(Context context) {
        return prefs(context).getInt(PREF_UNREAD, 0);
    }

    public static String lastSeenId(Context context) {
        return prefs(context).getString(PREF_LAST_ID, "");
    }

    public static void rememberLastId(Context context, String id) {
        prefs(context).edit().putString(PREF_LAST_ID, id == null ? "" : id).apply();
    }

    /** fetches the inbox and updates cache + unread counter. returns null on a failed call. */
    public static List<Item> refresh(Context context) {
        String appUuid = prefs(context).getString("app_uuid", null);
        String token = prefs(context).getString("device_token", "");
        if (appUuid == null || token.isEmpty()) return null;

        try {
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", appUuid);
            body.put("p_device_token", token);
            body.put("p_limit", CACHE_LIMIT);
            String raw = SupportApi.makeSupabaseRequest(
                    "rest/v1/rpc/rpc_get_my_notifications", "POST", body.toString(), null);

            JSONArray array = new JSONArray(raw);
            List<Item> items = new ArrayList<>();
            int unread = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                Item item = fromJson(row);
                localise(context, item);
                if (!item.read) unread++;
                items.add(item);
            }
            store(context, array, unread);
            return items;
        } catch (Exception e) {
            return null;
        }
    }

    private static Item fromJson(JSONObject row) {
        Item item = new Item();
        item.id = row.optString("id");
        item.kind = row.optString("kind", KIND_SYSTEM);
        item.title = row.optString("title");
        item.body = row.optString("body");
        item.action = row.optString("action");
        item.severity = row.optString("severity", "NORMAL");
        item.i18nKey = row.optString("i18n_key");
        item.read = row.optBoolean("is_read", false);
        item.createdAt = parseTime(row.optString("created_at"));
        return item;
    }

    private static long parseTime(String iso) {
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String clean = iso.length() > 19 ? iso.substring(0, 19) : iso;
            java.util.Date date = fmt.parse(clean);
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** known system keys get their text from the app, admin texts stay as they are. */
    private static void localise(Context context, Item item) {
        if (item.i18nKey == null || item.i18nKey.isEmpty()) return;
        switch (item.i18nKey) {
            case "data_request_approved":
                item.title = context.getString(R.string.notif_data_approved_title);
                item.body = context.getString(R.string.notif_data_approved_body);
                break;
            case "data_request_rejected":
                item.title = context.getString(R.string.notif_data_rejected_title);
                if (item.body == null || item.body.isEmpty()) {
                    item.body = context.getString(R.string.notif_data_rejected_body);
                }
                break;
            case "data_export_ready":
                item.title = context.getString(R.string.notif_export_ready_title);
                item.body = context.getString(R.string.notif_export_ready_body);
                break;
            default:
                break;
        }
    }

    private static void store(Context context, JSONArray array, int unread) {
        prefs(context).edit()
                .putString(PREF_CACHE, array.toString())
                .putInt(PREF_UNREAD, unread)
                .apply();
    }

    public static void markRead(Context context, String id) {
        call(context, "rpc_mark_notification_read", id, null);
        List<Item> items = cached(context);
        int unread = 0;
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_CACHE, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                if (id.equals(row.optString("id"))) row.put("is_read", true);
                if (!row.optBoolean("is_read", false)) unread++;
            }
            store(context, array, unread);
        } catch (Exception ignored) {
        }
        for (Item item : items) {
            if (id.equals(item.id)) item.read = true;
        }
    }

    public static void markAllRead(Context context) {
        call(context, "rpc_mark_all_notifications_read", null, null);
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_CACHE, "[]"));
            for (int i = 0; i < array.length(); i++) {
                array.getJSONObject(i).put("is_read", true);
            }
            store(context, array, 0);
        } catch (Exception ignored) {
        }
    }

    public static void delete(Context context, String id) {
        call(context, "rpc_delete_notification", id, null);
        try {
            JSONArray old = new JSONArray(prefs(context).getString(PREF_CACHE, "[]"));
            JSONArray next = new JSONArray();
            int unread = 0;
            for (int i = 0; i < old.length(); i++) {
                JSONObject row = old.getJSONObject(i);
                if (id.equals(row.optString("id"))) continue;
                if (!row.optBoolean("is_read", false)) unread++;
                next.put(row);
            }
            store(context, next, unread);
        } catch (Exception ignored) {
        }
    }

    public static void clearAll(Context context) {
        call(context, "rpc_clear_notifications", null, null);
        prefs(context).edit().putString(PREF_CACHE, "[]").putInt(PREF_UNREAD, 0).apply();
    }

    /** mute state per kind, stored on the server so it survives a reinstall. */
    public static Set<String> mutedKinds(Context context) {
        Set<String> muted = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_MUTES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                if (row.optBoolean("muted", false)) muted.add(row.optString("kind"));
            }
        } catch (Exception ignored) {
        }
        return muted;
    }

    public static Set<String> emailKinds(Context context) {
        Set<String> mail = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_MUTES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                if (row.optBoolean("email_enabled", false)) mail.add(row.optString("kind"));
            }
        } catch (Exception ignored) {
        }
        return mail;
    }

    public static void loadMutes(Context context) {
        String appUuid = prefs(context).getString("app_uuid", null);
        String token = prefs(context).getString("device_token", "");
        if (appUuid == null || token.isEmpty()) return;
        try {
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", appUuid);
            body.put("p_device_token", token);
            String raw = SupportApi.makeSupabaseRequest(
                    "rest/v1/rpc/rpc_get_notification_mutes", "POST", body.toString(), null);
            prefs(context).edit().putString(PREF_MUTES, new JSONArray(raw).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void setMute(Context context, String kind, boolean muted, boolean email) {
        call(context, "rpc_set_notification_mute", null, kind, muted, email);
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_MUTES, "[]"));
            boolean found = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                if (kind.equals(row.optString("kind"))) {
                    row.put("muted", muted);
                    row.put("email_enabled", email);
                    found = true;
                }
            }
            if (!found) {
                array.put(new JSONObject().put("kind", kind).put("muted", muted)
                        .put("email_enabled", email));
            }
            prefs(context).edit().putString(PREF_MUTES, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void call(Context context, String rpc, String id, String kind) {
        call(context, rpc, id, kind, null, null);
    }

    private static void call(Context context, String rpc, String id, String kind,
                             Boolean muted, Boolean email) {
        final Context appContext = context.getApplicationContext();
        final String appUuid = prefs(context).getString("app_uuid", null);
        final String token = prefs(context).getString("device_token", "");
        if (appUuid == null || token.isEmpty()) return;
        // network never runs on the ui thread, the local cache is the source of truth
        // until the next refresh
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("p_app_uuid", appUuid);
                body.put("p_device_token", token);
                if (id != null) body.put("p_id", id);
                if (kind != null) body.put("p_kind", kind);
                if (muted != null) body.put("p_muted", muted);
                if (email != null) body.put("p_email", email);
                SupportApi.makeSupabaseRequest("rest/v1/rpc/" + rpc, "POST", body.toString(), null);
            } catch (Exception ignored) {
            }
        }, "KodaNotifRpc").start();
    }

    /** human readable category name. */
    public static String kindLabel(Context context, String kind) {
        switch (kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT)) {
            case KIND_SUPPORT:
                return context.getString(R.string.notif_kind_support);
            case KIND_RELEASE:
                return context.getString(R.string.notif_kind_release);
            case KIND_SECURITY:
                return context.getString(R.string.notif_kind_security);
            case KIND_ACCOUNT:
                return context.getString(R.string.notif_kind_account);
            case KIND_DATA:
                return context.getString(R.string.notif_kind_data);
            default:
                return context.getString(R.string.notif_kind_system);
        }
    }

    /** accent colour per category, same palette the app uses elsewhere. */
    public static int kindColor(String kind) {
        switch (kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT)) {
            case KIND_SUPPORT:
                return 0xFFFF8C38;
            case KIND_RELEASE:
                return 0xFF64B5F6;
            case KIND_SECURITY:
                return 0xFFFF5449;
            case KIND_ACCOUNT:
                return 0xFFB388FF;
            case KIND_DATA:
                return 0xFF4DD0E1;
            default:
                return 0xFF8A8A9A;
        }
    }

    /**
     * opens the target of a notification. only https links leave the app, and only to
     * our own hosts, everything else is ignored.
     */
    public static void openAction(Context context, String action) {
        if (action == null || action.trim().isEmpty()) return;
        String value = action.trim();

        try {
            if (value.startsWith("ticket:")) {
                Intent intent = new Intent(context, eu.kodanetwork.mchost.ui.SupportChatActivity.class);
                intent.putExtra("TICKET_ID", value.substring("ticket:".length()));
                intent.putExtra("TICKET_TITLE", "");
                intent.putExtra("TICKET_STATUS", "");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
            if (value.startsWith("server:")) {
                Intent intent = new Intent(context, eu.kodanetwork.mchost.ui.ServerDetailActivity.class);
                intent.putExtra("id", value.substring("server:".length()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
            if (value.startsWith("data_request:")) {
                Intent intent = new Intent(context, eu.kodanetwork.mchost.ui.DataRequestActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
            if ("release".equals(value)) {
                Intent intent = new Intent(context, eu.kodanetwork.mchost.ui.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
                return;
            }
            if (value.startsWith("url:")) {
                String raw = value.substring("url:".length());
                Uri uri = Uri.parse(raw);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
                boolean allowed = "https".equals(uri.getScheme())
                        && (host.equals("host.kodanetwork.eu")
                        || host.equals("kodanetwork.eu")
                        || host.equals("www.kodanetwork.eu")
                        || host.equals("kodaserv.eu")
                        || host.endsWith(".kodaserv.eu"));
                if (!allowed) return;
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {
        }
    }
}
