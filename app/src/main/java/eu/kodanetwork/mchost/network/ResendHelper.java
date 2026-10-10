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
package eu.kodanetwork.mchost.network;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * sends and verifies the OTP for the registration.
 *
 * since the security fix of 2026-09-12 the code is generated server side (the
 * app only sends the email) and checked through functions/v1/verify-otp.
 * before that anyone with the anon key could send any text to any address from
 * our sender address.
 */
public class ResendHelper {

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public interface VerifyCallback {
        void onResult(boolean valid, String error);
    }

    /** asks for a code. the edge function sends the mail out with the server generated code. */
    public static void sendOTP(String email, Callback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/functions/v1/send-otp");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("email", email);

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess());
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError(readError(conn, responseCode)));
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /** checks the entered code on the server (hash compare, 5 tries at most). */
    public static void verifyOtp(String email, String code, VerifyCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/functions/v1/verify-otp");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("code", code);

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStreamReader r = new InputStreamReader(conn.getInputStream());
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = r.read()) != -1) sb.append((char) c);
                    r.close();
                    JSONObject obj = new JSONObject(sb.toString());
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(obj.optBoolean("valid", false), obj.optString("error", "")));
                } else {
                    String err = readError(conn, responseCode);
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, err));
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(false, e.getMessage()));
            }
        }).start();
    }

    private static String readError(HttpURLConnection conn, int responseCode) {
        try {
            InputStreamReader r = new InputStreamReader(conn.getErrorStream());
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) sb.append((char) c);
            r.close();
            String errMsg = "Error " + responseCode;
            try {
                JSONObject err = new JSONObject(sb.toString());
                if (err.has("error")) errMsg = err.getString("error");
            } catch (Exception e) {}
            return errMsg;
        } catch (Exception e) {
            return "Error " + responseCode;
        }
    }
}
