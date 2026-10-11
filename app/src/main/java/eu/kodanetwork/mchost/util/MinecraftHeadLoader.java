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

/**
 * player heads for the account list and the player manager.
 *
 * the public head services were not reliable: for SDI_0 (a real account with a
 * custom skin) minotar answered with the default steve forever, and mc-heads
 * only had the right skin when the uuid was passed instead of the name. so the
 * real way comes first now: name to uuid at mojang, profile to texture url,
 * download the skin png and cut the face out ourselves. the old services stay as
 * fallbacks, because they still work fine for most accounts.
 *
 * heads are cached per name in memory, a reopened list is instant.
 */
public final class MinecraftHeadLoader {

    private static final java.util.HashMap<String, android.graphics.Bitmap> cache = new java.util.HashMap<>();
    private static final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // one worker for everything: the mojang name endpoint does not like bursts
    private static final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String USER_AGENT = "KodaHosting/1.0";
    private static final int CACHE_PX = 64;

    public interface Callback {
        /** head is null when nothing could be fetched, the caller keeps its placeholder. */
        void onHead(android.graphics.Bitmap head);
    }

    private MinecraftHeadLoader() {
    }

    public static void load(String name, int sizePx, Callback callback) {
        if (name == null || name.isEmpty() || callback == null) return;
        final String key = name.toLowerCase(java.util.Locale.ROOT);
        synchronized (cache) {
            android.graphics.Bitmap cached = cache.get(key);
            if (cached != null) {
                callback.onHead(scale(cached, sizePx));
                return;
            }
        }
        worker.execute(() -> {
            android.graphics.Bitmap head = fetch(name);
            if (head != null) {
                synchronized (cache) {
                    if (cache.size() > 128) cache.clear();
                    cache.put(key, head);
                }
            }
            final android.graphics.Bitmap result = head == null ? null : scale(head, sizePx);
            mainHandler.post(() -> callback.onHead(result));
        });
    }

    private static android.graphics.Bitmap fetch(String name) {
        String uuid = mojangUuid(name);
        if (uuid != null) {
            android.graphics.Bitmap fromSkin = mojangSkin(uuid);
            if (fromSkin != null) return fromSkin;
            // the uuid based services have the correct data even when their
            // name lookup is stuck on the default skin
            android.graphics.Bitmap byUuid = downloadHead("https://mc-heads.net/avatar/" + uuid + "/" + CACHE_PX);
            if (byUuid == null) {
                byUuid = downloadHead("https://crafatar.com/avatars/" + uuid + "?size=" + CACHE_PX + "&overlay");
            }
            if (byUuid != null) return byUuid;
        }
        // offline mode names (no mojang account) can only come from the old services
        android.graphics.Bitmap byName = downloadHead("https://mc-heads.net/avatar/" + name + "/" + CACHE_PX);
        if (byName == null) {
            byName = downloadHead("https://minotar.net/helm/" + name + "/" + CACHE_PX + ".png");
        }
        return byName;
    }

    private static String mojangUuid(String name) {
        try {
            String body = getText("https://api.mojang.com/users/profiles/minecraft/"
                    + java.net.URLEncoder.encode(name, "UTF-8"));
            if (body == null) return null;
            String id = new org.json.JSONObject(body).optString("id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static android.graphics.Bitmap mojangSkin(String uuid) {
        try {
            String body = getText("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            if (body == null) return null;
            org.json.JSONArray props = new org.json.JSONObject(body).optJSONArray("properties");
            if (props == null) return null;
            String encoded = null;
            for (int i = 0; i < props.length(); i++) {
                org.json.JSONObject prop = props.getJSONObject(i);
                if ("textures".equals(prop.optString("name"))) {
                    encoded = prop.optString("value", "");
                    break;
                }
            }
            if (encoded == null || encoded.isEmpty()) return null;
            String decoded = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), "UTF-8");
            String url = new org.json.JSONObject(decoded)
                    .optJSONObject("textures").optJSONObject("SKIN").optString("url", "");
            if (url.isEmpty()) return null;
            // the profile hands out plain http, the cdn speaks https too (and the
            // app blocks cleartext, so http would fail anyway)
            url = url.replace("http://", "https://");
            byte[] png = getBytes(url);
            if (png == null) return null;
            android.graphics.Bitmap skin = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (skin == null) return null;
            android.graphics.Bitmap head = faceFromSkin(skin);
            skin.recycle();
            return head;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * the face is the 8x8 patch at 8,8 of the skin. modern skins (64 high) carry
     * the hat and hair overlay at 40,8, that one is drawn on top. legacy skins
     * (64x32) have no second layer, so nothing to do there.
     */
    private static android.graphics.Bitmap faceFromSkin(android.graphics.Bitmap skin) {
        if (skin.getWidth() < 64 || skin.getHeight() < 32) return null;
        android.graphics.Bitmap face = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(face);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setFilterBitmap(false);
        canvas.drawBitmap(skin, new android.graphics.Rect(8, 8, 16, 16), new android.graphics.Rect(0, 0, 8, 8), paint);
        if (skin.getHeight() >= 64) {
            canvas.drawBitmap(skin, new android.graphics.Rect(40, 8, 48, 16), new android.graphics.Rect(0, 0, 8, 8), paint);
        }
        // nearest neighbour on purpose: a minecraft head is pixel art, smoothing
        // would turn it into a blurry mess
        android.graphics.Bitmap big = android.graphics.Bitmap.createScaledBitmap(face, CACHE_PX, CACHE_PX, false);
        if (big != face) face.recycle();
        return big;
    }

    private static android.graphics.Bitmap scale(android.graphics.Bitmap head, int sizePx) {
        if (sizePx <= 0 || sizePx == head.getWidth()) return head;
        return android.graphics.Bitmap.createScaledBitmap(head, sizePx, sizePx, false);
    }

    private static android.graphics.Bitmap downloadHead(String urlStr) {
        try {
            byte[] data = getBytes(urlStr);
            if (data == null) return null;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getText(String urlStr) {
        byte[] data = getBytes(urlStr);
        if (data == null) return null;
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] getBytes(String urlStr) {
        java.io.InputStream in = null;
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (conn.getResponseCode() != 200) return null;
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
