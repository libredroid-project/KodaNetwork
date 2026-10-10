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

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PaperMCDownloader {
    private static final String TAG = "PaperMCDownloader";
    private static final String API_BASE_URL = "https://fill.papermc.io/v3/projects/paper";

    public interface DownloadCallback {
        void onProgress(String message);
        void onSuccess(File jarFile);
        void onError(String error);
    }

    public static void downloadLatestPaperMC(String version, File serverDir, DownloadCallback callback) {
        new Thread(() -> {
            try {
                callback.onProgress("Prüfe aktuelle PaperMC Version für " + version + "...");
                
                // 1. the newest build for this version
                String buildsUrl = API_BASE_URL + "/versions/" + version + "/builds";
                String buildsJson = fetchJson(buildsUrl);
                if (buildsJson == null) {
                    callback.onError("Fehler beim Abrufen der Builds für Version " + version);
                    return;
                }

                JSONArray buildsArray = new JSONArray(buildsJson);
                if (buildsArray.length() == 0) {
                    callback.onError("Keine Builds gefunden für " + version);
                    return;
                }
                
                JSONObject latestBuildObj = buildsArray.getJSONObject(0);
                int latestBuild = latestBuildObj.getInt("id");
                
                String jarFileName = "paper-" + version + "-" + latestBuild + ".jar";
                File targetJar = new File(serverDir, jarFileName);

                if (targetJar.exists()) {
                    callback.onProgress("Aktuellste Version (" + latestBuild + ") ist bereits installiert.");
                    callback.onSuccess(targetJar);
                    return;
                }

                // old paper jars go, two jars in one folder is a mess
                File[] existingJars = serverDir.listFiles((dir, name) -> name.startsWith("paper-") && name.endsWith(".jar"));
                if (existingJars != null) {
                    for (File oldJar : existingJars) {
                        oldJar.delete();
                    }
                }

                // 2. and here comes the download
                callback.onProgress("Lade PaperMC Build " + latestBuild + " herunter...");
                String downloadUrl = latestBuildObj.getJSONObject("downloads").getJSONObject("server:default").getString("url");
                
                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                
                if (conn.getResponseCode() != 200) {
                    callback.onError("Download fehlgeschlagen mit HTTP " + conn.getResponseCode());
                    return;
                }

                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(targetJar)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }

                callback.onProgress("Download erfolgreich abgeschlossen.");
                callback.onSuccess(targetJar);

            } catch (Exception e) {
                Log.e(TAG, "Fehler beim PaperMC Download", e);
                callback.onError("Fehler: " + e.getMessage());
            }
        }).start();
    }

    public static File downloadLatestPaperSync(String version, File serverDir, DownloadCallback callback) throws Exception {
        if (callback != null) callback.onProgress("Prüfe aktuelle PaperMC Version für " + version + "...");
        
        String buildsUrl = API_BASE_URL + "/versions/" + version + "/builds";
        String buildsJson = fetchJson(buildsUrl);
        if (buildsJson == null) {
            throw new Exception("Fehler beim Abrufen der Builds für Version " + version);
        }

        JSONArray buildsArray = new JSONArray(buildsJson);
        if (buildsArray.length() == 0) {
            throw new Exception("Keine Builds gefunden für " + version);
        }
        
        JSONObject latestBuildObj = buildsArray.getJSONObject(0);
        int latestBuild = latestBuildObj.getInt("id");
        
        String jarFileName = "paper-" + version + "-" + latestBuild + ".jar";
        File targetJar = new File(serverDir, jarFileName);

        if (targetJar.exists()) {
            if (callback != null) callback.onProgress("Aktuellste Version (" + latestBuild + ") ist bereits installiert.");
            return targetJar;
        }

        File[] existingJars = serverDir.listFiles((dir, name) -> name.startsWith("paper-") && name.endsWith(".jar"));
        if (existingJars != null) {
            for (File oldJar : existingJars) {
                oldJar.delete();
            }
        }

        if (callback != null) callback.onProgress("Lade PaperMC Build " + latestBuild + " herunter...");
        String downloadUrl = latestBuildObj.getJSONObject("downloads").getJSONObject("server:default").getString("url");
        
        HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        
        if (conn.getResponseCode() != 200) {
            throw new Exception("Download fehlgeschlagen mit HTTP " + conn.getResponseCode());
        }

        try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(targetJar)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
        return targetJar;
    }

    /**
     * every minecraft version the paper project offers, newest first.
     *
     * this uses the current v3 API (fill.papermc.io). the old api.papermc.io/v2 is sunset and
     * answers HTTP 410, which looked like "no internet" in the UI.
     */
    public static java.util.List<String> fetchPaperVersions() {
        java.util.List<String> versions = new java.util.ArrayList<>();
        String json = fetchJson(API_BASE_URL);
        if (json == null) return versions;
        try {
            // Gson keeps the order of the response, org.json uses a HashMap and would hand the
            // version families back in a random order, old and new mixed together.
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject families = root.getAsJsonObject("versions");
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> family : families.entrySet()) {
                com.google.gson.JsonArray arr = family.getValue().getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    String version = arr.get(i).getAsString().trim();
                    // releases only: pre-releases and RCs (1.21.11-rc3) are not what anyone
                    // hosts a world on, and they would land under the "stable" label
                    if (version.isEmpty() || version.contains("-")) continue;
                    versions.add(version);
                }
            }
        } catch (Exception e) {
            return versions;
        }
        // newest first, whatever order the api used for the families
        versions.sort((a, b) -> MinecraftVersion.compare(b, a));
        return versions;
    }

    private static String fetchJson(String urlString) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "KodaHosting/1.0 (contact@kodanetwork.eu)");
            
            if (conn.getResponseCode() != 200) {
                return null;
            }
            
            try (InputStream is = conn.getInputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                StringBuilder sb = new StringBuilder();
                while ((len = is.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, len));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim API Aufruf", e);
            return null;
        }
    }
}
