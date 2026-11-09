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

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.network.supabase.SupportApi;

/**
 * the verified state of the account e-mail. submitting extensions needs it, and the
 * account sheet offers a button to do it later for people who skipped it on signup.
 */
public final class EmailVerification {

    public interface StatusCallback {
        void onStatus(boolean verified);
    }

    public interface DoneCallback {
        void onDone(boolean ok);
    }

    private EmailVerification() {
    }

    /** asks the server whether this account proved control of its mail address. */
    public static void check(Context context, StatusCallback callback) {
        String uuid = App.getPrefs(context).getString("app_uuid", null);
        String token = App.getPrefs(context).getString("device_token", "");
        if (uuid == null || token.isEmpty()) {
            callback.onStatus(false);
            return;
        }
        new Thread(() -> {
            boolean verified = false;
            try {
                JSONObject body = new JSONObject();
                body.put("p_app_uuid", uuid);
                body.put("p_device_token", token);
                String raw = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_email_status", "POST", body.toString(), null);
                verified = new JSONObject(raw).optBoolean("verified", false);
            } catch (Exception ignored) {
            }
            final boolean result = verified;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onStatus(result));
        }, "KodaEmailStatus").start();
    }

    /** marks the account as verified after the OTP was accepted. */
    public static void markVerified(Context context, String email, DoneCallback callback) {
        String uuid = App.getPrefs(context).getString("app_uuid", null);
        String token = App.getPrefs(context).getString("device_token", "");
        if (uuid == null || token.isEmpty()) {
            callback.onDone(false);
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            try {
                JSONObject body = new JSONObject();
                body.put("p_app_uuid", uuid);
                body.put("p_device_token", token);
                body.put("p_email", email == null ? "" : email);
                String raw = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_mark_email_verified", "POST", body.toString(), null);
                ok = "true".equals(raw.trim());
            } catch (Exception ignored) {
            }
            final boolean result = ok;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onDone(result));
        }, "KodaEmailVerify").start();
    }
}
