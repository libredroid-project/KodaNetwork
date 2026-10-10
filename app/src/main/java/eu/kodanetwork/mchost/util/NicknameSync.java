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
import eu.kodanetwork.mchost.security.PraetorSecurity;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * pushes the local nickname to koda_users.nickname through the rpc_patch_user
 * RPC. runs from the automatic daily backfill (MainActivity) and from the
 * "retry" button in the developer options.
 *
 * since the security fix on 2026-09-12 reading and writing go through the
 * token-checked RPCs (rpc_get_user_profile / rpc_patch_user), direct table
 * SELECTs are blocked.
 */
public final class NicknameSync {

    private NicknameSync() {}

    /** @param interactive toasts the outcome (the dev retry button), silent otherwise */
    public static void sync(Context ctx, boolean interactive) {
        android.content.SharedPreferences prefs = App.getPrefs(ctx);
        String nick = prefs.getString("nickname", "");
        if (nick.isEmpty()) {
            if (interactive) Toast.makeText(ctx, "Kein lokaler Nickname gespeichert", Toast.LENGTH_SHORT).show();
            return;
        }
        String appUuid = prefs.getString("app_uuid", "");
        if (appUuid.isEmpty()) {
            if (interactive) Toast.makeText(ctx, "Keine app_uuid", Toast.LENGTH_SHORT).show();
            return;
        }
        String deviceToken = prefs.getString("device_token", "");
        if (deviceToken.isEmpty()) {
            if (interactive) Toast.makeText(ctx, "Noch kein device_token (App-Neustart noetig)", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = PraetorSecurity.getSupabaseUrl();
        String apikey = PraetorSecurity.getSupabaseKey();

        new Thread(() -> {
            boolean dbHasIt = false;
            String detail = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        base + "/rest/v1/rpc/rpc_get_user_profile").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apikey);
                c.setRequestProperty("Authorization", "Bearer " + apikey);
                String body = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\"}";
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                Scanner sc = new Scanner(c.getInputStream()).useDelimiter("\\A");
                JSONObject obj = new JSONObject(sc.hasNext() ? sc.next() : "{}");
                dbHasIt = !obj.isNull("nickname") && !obj.optString("nickname", "").isEmpty();
                c.disconnect();
            } catch (Exception e) {
                detail = "read: " + e.getMessage();
            }

            if (dbHasIt) {
                final String d = detail;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                if (interactive) h.post(() -> Toast.makeText(ctx, "Nickname ist bereits in der DB", Toast.LENGTH_SHORT).show());
                android.util.Log.d("Nickname", "db already has nickname");
                return;
            }

            int code = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        base + "/rest/v1/rpc/rpc_patch_user").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apikey);
                c.setRequestProperty("Authorization", "Bearer " + apikey);
                String body = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_payload\":{\"nickname\":\"" + nick + "\"}}";
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                code = c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                detail = "patch: " + e.getMessage();
            }

            final int fCode = code;
            final String fDetail = detail;
            android.util.Log.d("Nickname", "push rpc=" + fCode + " " + fDetail);
            if (interactive) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> Toast.makeText(ctx,
                        fCode >= 200 && fCode < 300
                                ? "Gesendet ✓ (rpc " + fCode + ")"
                                : "Fehlgeschlagen (rpc " + fCode + ") " + fDetail,
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
