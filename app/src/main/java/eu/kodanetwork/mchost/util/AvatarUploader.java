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

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONObject;

import eu.kodanetwork.mchost.App;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * uploads the account avatar through the same api the website uses:
 * POST /v1/me/avatar gives a short lived signed upload slot, the bytes go
 * straight to storage, then PATCH /v1/me stores the public url.
 *
 * the bearer token is the stored supabase session, the same one the support
 * ticket screens already send to api.kodanetwork.eu.
 */
public final class AvatarUploader {

    private static final String API = "https://api.kodanetwork.eu";
    private static final int MAX_DIM = 512;
    private static final int QUALITY = 88;

    public interface Callback {
        void onSuccess(String publicUrl);
        void onError(String message);
    }

    private AvatarUploader() {}

    public static void upload(Activity activity, Uri uri, Callback callback) {
        new Thread(() -> {
            try {
                byte[] jpeg = readJpeg(activity, uri);
                if (jpeg == null) {
                    fail(activity, callback, "Could not read the image");
                    return;
                }
                String token = App.getPrefs(activity).getString("koda_session_token", "");
                if (token.isEmpty()) {
                    // the access token is gone but the refresh token may still be there
                    eu.kodanetwork.mchost.network.supabase.SupabaseAuth.refreshTokenSync(activity);
                    token = App.getPrefs(activity).getString("koda_session_token", "");
                }
                if (token.isEmpty()) {
                    fail(activity, callback, "Not signed in");
                    return;
                }

                JSONObject request = new JSONObject().put("content_type", "image/jpeg");
                JSONObject slot = requestWithRefresh(activity, "POST", API + "/v1/me/avatar", request.toString());
                JSONObject upload = slot.optJSONObject("upload");
                String uploadUrl = upload == null ? "" : upload.optString("signed_url", "");
                String publicUrl = slot.optString("public_url", "");
                if (uploadUrl.isEmpty() || publicUrl.isEmpty()) {
                    fail(activity, callback, "No upload slot");
                    return;
                }

                HttpURLConnection put = (HttpURLConnection) new URL(uploadUrl).openConnection();
                put.setRequestMethod("PUT");
                put.setRequestProperty("Content-Type", "image/jpeg");
                put.setDoOutput(true);
                put.setFixedLengthStreamingMode(jpeg.length);
                try (OutputStream os = put.getOutputStream()) {
                    os.write(jpeg);
                }
                int code = put.getResponseCode();
                put.disconnect();
                if (code != 200 && code != 201) {
                    fail(activity, callback, "Upload failed (" + code + ")");
                    return;
                }

                JSONObject patch = new JSONObject().put("avatar_url", publicUrl);
                requestWithRefresh(activity, "PATCH", API + "/v1/me", patch.toString());
                activity.runOnUiThread(() -> callback.onSuccess(publicUrl));
            } catch (Exception e) {
                fail(activity, callback, e.getMessage() == null ? "Upload failed" : e.getMessage());
            }
        }, "KodaAvatarUpload").start();
    }

    private static void fail(Activity activity, Callback callback, String message) {
        activity.runOnUiThread(() -> callback.onError(message));
    }

    /**
     * the stored access token lives about an hour. When the api answers 401/403,
     * refresh it once and retry the same request instead of failing the upload.
     * Restarting the app used to be the workaround because login refreshed it.
     */
    private static JSONObject requestWithRefresh(Activity activity, String method, String url, String body) throws Exception {
        String[] token = {App.getPrefs(activity).getString("koda_session_token", "")};
        if (token[0].isEmpty()) {
            eu.kodanetwork.mchost.network.supabase.SupabaseAuth.refreshTokenSync(activity);
            token[0] = App.getPrefs(activity).getString("koda_session_token", "");
        }
        if (token[0].isEmpty()) throw new Exception("Not signed in");
        try {
            return request(method, url, token[0], body);
        } catch (Exception e) {
            if (!isUnauthorized(e)) throw e;
            if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.refreshTokenSync(activity)) throw e;
            token[0] = App.getPrefs(activity).getString("koda_session_token", "");
            if (token[0].isEmpty()) throw e;
            return request(method, url, token[0], body);
        }
    }

    private static boolean isUnauthorized(Exception e) {
        String message = e.getMessage();
        return message != null && (message.startsWith("HTTP 401") || message.startsWith("HTTP 403"));
    }

    private static JSONObject request(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            Scanner sc = new Scanner(is).useDelimiter("\\A");
            text = sc.hasNext() ? sc.next() : "";
        }
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text);
    }

    /** reads, downscales and compresses the picked image to a small jpeg. */
    private static byte[] readJpeg(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > MAX_DIM) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
