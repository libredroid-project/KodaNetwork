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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.security.PraetorSecurity;

/**
 * the DSGVO requests: export (Art. 15) and full deletion (Art. 17). both need an
 * admin decision, so a request is a small row in data_requests with a status.
 */
public final class DataRequests {

    public static final String EXPORT = "EXPORT";
    public static final String DELETE = "DELETE";

    private static final String API_BASE = "https://api.kodanetwork.eu";

    private DataRequests() {
    }

    public static final class Request {
        public String id = "";
        public String kind = EXPORT;
        public String status = "PENDING";
        public String note = "";
        public String adminNote = "";
        public long requestedAt;
        public long decidedAt;
        public long fulfilledAt;
        public long exportExpiresAt;
        public boolean hasExport;
    }

    private static String appUuid(Context context) {
        return App.getPrefs(context).getString("app_uuid", null);
    }

    private static String deviceToken(Context context) {
        return App.getPrefs(context).getString("device_token", "");
    }

    public static List<Request> list(Context context) {
        List<Request> out = new ArrayList<>();
        String uuid = appUuid(context);
        if (uuid == null) return out;
        try {
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", uuid);
            body.put("p_device_token", deviceToken(context));
            String raw = SupportApi.makeSupabaseRequest(
                    "rest/v1/rpc/rpc_get_my_data_requests", "POST", body.toString(), null);
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                Request r = new Request();
                r.id = row.optString("id");
                r.kind = row.optString("kind", EXPORT);
                r.status = row.optString("status", "PENDING");
                r.note = row.optString("note");
                r.adminNote = row.optString("admin_note");
                r.requestedAt = parseTime(row.optString("requested_at"));
                r.decidedAt = parseTime(row.optString("decided_at"));
                r.fulfilledAt = parseTime(row.optString("fulfilled_at"));
                r.exportExpiresAt = parseTime(row.optString("export_expires_at"));
                r.hasExport = row.optBoolean("has_export", false);
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** result of a create call: the id, or the reason the server refused. */
    public static final class CreateResult {
        public final boolean ok;
        public final String id;
        public final String error;

        private CreateResult(boolean ok, String id, String error) {
            this.ok = ok;
            this.id = id;
            this.error = error;
        }
    }

    public static CreateResult create(Context context, String kind, String note) {
        String uuid = appUuid(context);
        if (uuid == null) return new CreateResult(false, null, "Not logged in");
        try {
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", uuid);
            body.put("p_device_token", deviceToken(context));
            body.put("p_kind", kind);
            body.put("p_note", note == null ? "" : note);
            String raw = SupportApi.makeSupabaseRequest(
                    "rest/v1/rpc/rpc_create_data_request", "POST", body.toString(), null).trim();

            // success is the uuid as a json string. anything else is the postgrest error,
            // which used to be mistaken for an id (the toast said "sent" and nothing happened).
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 2) {
                String id = raw.substring(1, raw.length() - 1);
                if (id.matches("[0-9a-fA-F-]{36}")) return new CreateResult(true, id, null);
            }
            String message = raw;
            try {
                message = new JSONObject(raw).optString("message", raw);
            } catch (Exception ignored) {
            }
            return new CreateResult(false, null, message);
        } catch (Exception e) {
            return new CreateResult(false, null, e.getMessage());
        }
    }

    /** maps the server refusal to something the user can act on. */
    public static int errorMessage(String error) {
        String lower = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("already open")) {
            return eu.kodanetwork.mchost.R.string.data_request_already_open;
        }
        if (lower.contains("slow down")) {
            return eu.kodanetwork.mchost.R.string.data_request_slow_down;
        }
        return eu.kodanetwork.mchost.R.string.data_request_failed;
    }

    public static boolean cancel(Context context, String id) {
        String uuid = appUuid(context);
        if (uuid == null) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", uuid);
            body.put("p_device_token", deviceToken(context));
            body.put("p_id", id);
            SupportApi.makeSupabaseRequest(
                    "rest/v1/rpc/rpc_cancel_data_request", "POST", body.toString(), null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * asks the api for a short signed download url. the url must stay https and on a
     * supabase/storage host, otherwise it is dropped.
     */
    public static String exportUrl(Context context, String requestId) {
        String uuid = appUuid(context);
        if (uuid == null) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "/v1/device/data-export");
            if (!"https".equals(url.getProtocol())) return null;

            JSONObject body = new JSONObject();
            body.put("app_uuid", uuid);
            body.put("device_token", deviceToken(context));
            body.put("request_id", requestId);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;
            String raw = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(raw);
            String link = json.optString("url");
            if (link == null || link.isEmpty()) return null;

            URL target = new URL(link);
            String host = target.getHost() == null ? "" : target.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean allowed = "https".equals(target.getProtocol())
                    && (host.endsWith(".supabase.co") || host.equals("api.kodanetwork.eu"));
            return allowed ? link : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static byte[] download(String link) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(link);
            if (!"https".equals(url.getProtocol())) return null;
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!(host.endsWith(".supabase.co") || host.equals("api.kodanetwork.eu"))) return null;

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    public static String statusLabel(Context context, String status) {
        switch (status == null ? "" : status.toUpperCase(java.util.Locale.ROOT)) {
            case "PENDING":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_pending);
            case "APPROVED":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_approved);
            case "READY":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_ready);
            case "REJECTED":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_rejected);
            case "FULFILLED":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_fulfilled);
            case "CANCELLED":
                return context.getString(eu.kodanetwork.mchost.R.string.data_request_status_cancelled);
            default:
                return status == null ? "" : status;
        }
    }

    public static int statusColor(String status) {
        switch (status == null ? "" : status.toUpperCase(java.util.Locale.ROOT)) {
            case "APPROVED":
                return 0xFF00E676;
            case "READY":
                return 0xFF64B5F6;
            case "REJECTED":
                return 0xFFFF8A80;
            case "FULFILLED":
                return 0xFF4DD0E1;
            case "CANCELLED":
                return 0xFF8A8A9A;
            default:
                return 0xFFFFB74D;
        }
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String clean = iso.replace(' ', 'T');
            if (clean.length() > 19) clean = clean.substring(0, 19);
            java.util.Date date = fmt.parse(clean);
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
