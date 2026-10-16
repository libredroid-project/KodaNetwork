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
package eu.kodanetwork.mchost.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * thin client for the public extension store on api.kodanetwork.eu.
 *
 * reads are anonymous (the catalog and the downloads are public), everything runs
 * on a background thread. a downloaded package is hashed while it streams to disk
 * and only installed when the sha256 matches what the store announced.
 */
public final class ExtensionStoreApi {

    /** the public API host; also used by the website (host.kodanetwork.eu/store). */
    public static final String BASE = "https://api.kodanetwork.eu";

    private static final int TIMEOUT_MS = 15000;
    private static final long MAX_PACKAGE_BYTES = 20L * 1024 * 1024;

    private ExtensionStoreApi() {}

    public static final class StoreVersion {
        public String version = "";
        public int versionCode = 0;
        public int minAppVersion = 0;
        public String changelog = "";
        public final List<String> permissions = new ArrayList<>();
        public String sha256 = "";
        public long fileSize = 0;
    }

    public static final class StoreExtension {
        public String extId = "";
        public String name = "";
        public String summary = "";
        public String description = "";
        public String category = "";
        public String iconUrl = "";
        public String author = "";
        public String license = "";
        public String homepageUrl = "";
        public String sourceUrl = "";
        public long downloads = 0;
        public final List<String> screenshots = new ArrayList<>();
        public StoreVersion latest = new StoreVersion();
        public final List<StoreVersion> versions = new ArrayList<>();
    }

    /** the published catalog, newest first. */
    public static List<StoreExtension> fetchCatalog() throws Exception {
        JSONObject root = new JSONObject(get(BASE + "/v1/extensions"));
        JSONArray items = root.optJSONArray("items");
        List<StoreExtension> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            StoreExtension ext = new StoreExtension();
            ext.extId = item.optString("ext_id", "");
            ext.name = item.optString("name", "");
            ext.summary = item.optString("summary", "");
            ext.category = item.optString("category", "");
            ext.iconUrl = item.optString("icon_url", "");
            ext.author = item.optString("author", "");
            ext.license = item.optString("license", "");
            ext.downloads = item.optLong("downloads", 0);
            JSONObject latest = item.optJSONObject("latest_version");
            if (latest != null) {
                ext.latest = parseVersion(latest);
                ext.versions.add(ext.latest);
            }
            if (!ext.extId.isEmpty()) out.add(ext);
        }
        return out;
    }

    /** one extension with all released versions. */
    public static StoreExtension fetchDetail(String extId) throws Exception {
        JSONObject root = new JSONObject(get(BASE + "/v1/extensions/" + URLEncoder.encode(extId, "UTF-8")));
        JSONObject item = root.getJSONObject("extension");
        StoreExtension ext = new StoreExtension();
        ext.extId = item.optString("ext_id", "");
        ext.name = item.optString("name", "");
        ext.summary = item.optString("summary", "");
        ext.description = item.optString("description", "");
        ext.category = item.optString("category", "");
        ext.iconUrl = item.optString("icon_url", "");
        ext.author = item.optString("author", "");
        ext.license = item.optString("license", "");
        ext.homepageUrl = item.optString("homepage_url", "");
        ext.sourceUrl = item.optString("source_url", "");
        ext.downloads = item.optLong("downloads", 0);
        JSONArray shots = item.optJSONArray("screenshots");
        if (shots != null) {
            for (int i = 0; i < shots.length(); i++) ext.screenshots.add(shots.optString(i, ""));
        }
        JSONArray versions = item.optJSONArray("versions");
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.optJSONObject(i);
                if (version != null) ext.versions.add(parseVersion(version));
            }
        }
        if (!ext.versions.isEmpty()) ext.latest = ext.versions.get(0);
        return ext;
    }

    private static StoreVersion parseVersion(JSONObject json) {
        StoreVersion version = new StoreVersion();
        version.version = json.optString("version", "");
        version.versionCode = json.optInt("version_code", 0);
        version.minAppVersion = json.optInt("min_app_version", 0);
        version.changelog = json.optString("changelog", "");
        version.sha256 = json.optString("sha256", "");
        version.fileSize = json.optLong("file_size", 0);
        JSONArray permissions = json.optJSONArray("permissions");
        if (permissions != null) {
            for (int i = 0; i < permissions.length(); i++) {
                version.permissions.add(permissions.optString(i, ""));
            }
        }
        return version;
    }

    /**
     * downloads the newest approved package into {@code target} and verifies its
     * sha256 against the store's announcement. returns the computed hash; throws
     * and deletes the file on mismatch.
     */
    public static String downloadPackage(StoreExtension ext, File target) throws Exception {
        if (ext.latest == null || ext.latest.versionCode <= 0) throw new IOException("no version to download");
        String url = BASE + "/v1/extensions/" + URLEncoder.encode(ext.extId, "UTF-8")
                + "/download?version=" + ext.latest.versionCode;

        HttpURLConnection conn = open(url);
        // the route answers with a 302 to the signed storage url; follow it by hand
        int hops = 0;
        for (;;) {
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400 && hops++ < 5) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("redirect without location");
                conn = open(location);
                continue;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            break;
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_PACKAGE_BYTES) throw new IOException("package too large");
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } finally {
            conn.disconnect();
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        String hash = hex.toString();

        if (ext.latest.sha256 != null && !ext.latest.sha256.isEmpty()
                && !hash.equalsIgnoreCase(ext.latest.sha256)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("sha256 mismatch");
        }
        return hash;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json, application/zip");
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** "1.2 MB" / "640 KB" for the row meta line. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
