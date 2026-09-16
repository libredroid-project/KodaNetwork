package eu.kodanetwork.mchost.network;

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

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTP-Versand/-Verifikation fuer die Registrierung.
 *
 * Seit dem Security-Fix 2026-09-12: Der Code wird SERVERSEITIG generiert
 * (die App schickt nur noch die E-Mail) und ueber functions/v1/verify-otp
 * geprueft. Vorher konnte jeder mit dem Anon-Key beliebigen Text an
 * beliebige Adressen von unserer Absender-Adresse verschicken.
 */
public class ResendHelper {

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public interface VerifyCallback {
        void onResult(boolean valid, String error);
    }

    /** Fordert einen Code an. Die E-Mail wird von der Edge Function mit Server-Code verschickt. */
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

    /** Prueft den eingegebenen Code serverseitig (Hash-Vergleich, max. 5 Versuche). */
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
