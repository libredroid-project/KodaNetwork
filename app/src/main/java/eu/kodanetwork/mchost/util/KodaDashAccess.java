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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.security.PraetorSecurity;

/**
 * Reads the KodaDash beta flag from the signed-in account.
 *
 * KodaDash used to be gated by BuildConfig.KODADASH_ACCOUNT, which meant one APK
 * per tester and an empty gate in the public snapshot. The flag now lives on
 * koda_users and comes back with rpc_get_user_profile, so the team can enable a
 * tester without rebuilding the app.
 */
public final class KodaDashAccess {

    public interface Callback {
        void onResult(boolean enabled);
    }

    private KodaDashAccess() {
    }

    /** Checks the flag in the background and answers on the main thread. */
    public static void check(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = App.getPrefs(app);
        final String appUuid = prefs.getString("app_uuid", "");
        final String deviceToken = prefs.getString("device_token", "");
        if (appUuid.isEmpty() || deviceToken.isEmpty()) {
            callback.onResult(false);
            return;
        }

        new Thread(() -> {
            boolean enabled = false;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_get_user_profile");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
                connection.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey());

                String body = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\"}";
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (Scanner scanner = new Scanner(connection.getInputStream()).useDelimiter("\\A")) {
                        JSONObject profile = new JSONObject(scanner.hasNext() ? scanner.next() : "{}");
                        enabled = profile.optBoolean("kodadash_beta", false);
                    }
                } else {
                    Log.w("KodaDashAccess", "profile rpc returned " + code);
                }
            } catch (Exception e) {
                Log.w("KodaDashAccess", "profile check failed: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }

            final boolean result = enabled;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }).start();
    }
}
