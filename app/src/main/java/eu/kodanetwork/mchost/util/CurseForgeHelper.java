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

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.kodanetwork.mchost.BuildConfig;
import eu.kodanetwork.mchost.model.ServerInstance;

/**
 * CurseForge Core API v1 for mods, Bukkit plugins and modpacks.
 *
 * The key is a client credential and comes from BuildConfig, which is filled from the
 * ignored local.properties at build time. It is never written to this source file.
 */
public final class CurseForgeHelper {

    private static final String API_BASE = "https://api.curseforge.com/v1";
    private static final int GAME_ID = 432;          // Minecraft
    private static final int CLASS_MODS = 6;
    private static final int CLASS_PLUGINS = 5;
    private static final int CLASS_MODPACKS = 4471;

    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private CurseForgeHelper() {}

    public static final class ModpackFile {
        public String downloadUrl;
        public String filename;
        public String sha1;
        public String gameVersion;
        public String loader;
        public long projectId;
        public long fileId;
    }

    public interface FileListCallback {
        void onResult(List<JSONObject> files);
        void onError(String err);
    }

    public static boolean isConfigured() {
        String key = BuildConfig.CURSEFORGE_API_KEY;
        return key != null && !key.trim().isEmpty();
    }

    private static String key() {
        String key = BuildConfig.CURSEFORGE_API_KEY;
        return key == null ? "" : key.trim();
    }

    // ---------------------------------------------------------------- search

    public static void search(String query, ServerInstance server,
                              ModrinthHelper.SearchCallback cb) {
        executor.submit(() -> {
            try {
                List<ModrinthHelper.ModrinthProject> hits = searchSync(query, server);
                main.post(() -> cb.onResult(hits));
            } catch (Exception e) {
                main.post(() -> cb.onError(message(e)));
            }
        });
    }

    private static List<ModrinthHelper.ModrinthProject> searchSync(String query,
                                                                   ServerInstance server) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("gameId=").append(GAME_ID);
        q.append("&classId=").append(ModrinthHelper.isPluginServer(server)
                ? CLASS_PLUGINS : CLASS_MODS);
        q.append("&gameVersion=").append(enc(server.getVersion()));
        int loader = loaderType(server);
        if (loader > 0) q.append("&modLoaderType=").append(loader);
        q.append("&sortField=2&sortOrder=desc&pageSize=20&index=0");
        if (query != null && !query.trim().isEmpty()) {
            q.append("&searchFilter=").append(enc(query.trim()));
        }

        JSONObject json = getJson("/mods/search", q.toString());
        JSONArray data = json.optJSONArray("data");
        List<ModrinthHelper.ModrinthProject> out = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o != null) out.add(projectFrom(o));
            }
        }
        return out;
    }

    /** all jar files of a project for this server, newest first. */
    public static void listFiles(String projectId, ServerInstance server,
                                 FileListCallback cb) {
        executor.submit(() -> {
            try {
                long id = Long.parseLong(projectId);
                JSONArray files = listFilesSync(id, server);
                List<JSONObject> out = new ArrayList<>();
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.optJSONObject(i);
                        if (f == null) continue;
                        if (!f.optString("fileName", "").toLowerCase().endsWith(".jar")) continue;
                        if (f.optString("downloadUrl", "").isEmpty()) continue;
                        if (!hasGameVersion(f, server.getVersion())) continue;
                        out.add(f);
                    }
                }
                main.post(() -> cb.onResult(out));
            } catch (Exception e) {
                main.post(() -> cb.onError(message(e)));
            }
        });
    }

    private static JSONArray listFilesSync(long modId, ServerInstance server) throws Exception {
        StringBuilder q = new StringBuilder();
        if (server.getVersion() != null && !server.getVersion().trim().isEmpty()) {
            q.append("gameVersion=").append(enc(server.getVersion()));
        }
        int loader = loaderType(server);
        if (loader > 0) {
            if (q.length() > 0) q.append('&');
            q.append("modLoaderType=").append(loader);
        }
        if (q.length() > 0) q.append('&');
        q.append("pageSize=50");
        return getJson("/mods/" + modId + "/files", q.toString()).optJSONArray("data");
    }

    /** installs one specific file (the version the user picked) plus required dependencies. */
    public static void installFile(String projectId, JSONObject file, ServerInstance server,
                                   ModrinthHelper.DownloadCallback cb) {
        executor.submit(() -> {
            try {
                long id = Long.parseLong(projectId);
                JSONObject mod = getJson("/mods/" + id, null).optJSONObject("data");
                if (mod == null) throw new IllegalStateException("Project not found");
                if (!mod.optBoolean("allowModDistribution", true)) {
                    main.post(() -> cb.onError(
                            "This project does not allow third-party downloads. Open it on CurseForge."));
                    return;
                }
                if (!file.optBoolean("isAvailable", true)) {
                    throw new IllegalStateException("This file is no longer available.");
                }

                List<String> dependencies = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                seen.add(String.valueOf(id));
                File target = downloadOne(id, mod, file, server, cb, dependencies, seen, 0);
                if (!dependencies.isEmpty()) {
                    final List<String> names = new ArrayList<>(dependencies);
                    main.post(() -> cb.onDependencies(names));
                }
                main.post(() -> cb.onSuccess(target));
            } catch (Exception e) {
                main.post(() -> cb.onError(message(e)));
            }
        });
    }

    // ---------------------------------------------------------------- install

    public static void autoDownload(String projectId, ServerInstance server,
                                    ModrinthHelper.DownloadCallback cb) {
        executor.submit(() -> {
            try {
                long id = Long.parseLong(projectId);
                JSONObject mod = getJson("/mods/" + id, null).optJSONObject("data");
                if (mod == null) throw new IllegalStateException("Project not found");
                if (!mod.optBoolean("allowModDistribution", true)) {
                    main.post(() -> cb.onError(
                            "This project does not allow third-party downloads. Open it on CurseForge."));
                    return;
                }

                JSONObject file = pickFile(id, server.getVersion(), loaderType(server), false);
                if (file == null) {
                    main.post(() -> cb.onError("No compatible version found."));
                    return;
                }

                List<String> dependencies = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                seen.add(String.valueOf(id));
                File target = downloadOne(id, mod, file, server, cb, dependencies, seen, 0);

                if (!dependencies.isEmpty()) {
                    final List<String> names = new ArrayList<>(dependencies);
                    main.post(() -> cb.onDependencies(names));
                }
                main.post(() -> cb.onSuccess(target));
            } catch (Exception e) {
                main.post(() -> cb.onError(message(e)));
            }
        });
    }

    private static File downloadOne(long modId, JSONObject mod, JSONObject file,
                                    ServerInstance server, ModrinthHelper.DownloadCallback cb,
                                    List<String> dependencies, Set<String> seen,
                                    int depth) throws Exception {
        String url = file.optString("downloadUrl", "");
        if (url.isEmpty()) {
            throw new IllegalStateException("This file has no download URL.");
        }

        String fileName = sanitizeFileName(file.optString("fileName", "download.jar"));
        File dir = new File(server.getServerDir(),
                ModrinthHelper.isPluginServer(server) ? "plugins" : "mods");
        dir.mkdirs();
        File target = new File(dir, fileName);
        downloadTo(target, url, file.optJSONArray("hashes"), cb);
        server.pluginSources.put(fileName, "CurseForge \u00b7 " + modId);

        if (depth < 4 && dependencies.size() < 20) {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    JSONObject dep = deps.optJSONObject(i);
                    if (dep == null || dep.optInt("relationType", 0) != 3) continue;
                    long depId = dep.optLong("modId", 0);
                    if (depId <= 0 || !seen.add(String.valueOf(depId))) continue;
                    try {
                        JSONObject depMod = getJson("/mods/" + depId, null)
                                .optJSONObject("data");
                        if (depMod == null
                                || !depMod.optBoolean("allowModDistribution", true)) {
                            continue;
                        }
                        JSONObject depFile = pickFile(depId, server.getVersion(),
                                loaderType(server), false);
                        if (depFile == null) continue;
                        downloadOne(depId, depMod, depFile, server, null,
                                dependencies, seen, depth + 1);
                        dependencies.add(stripHtml(depMod.optString("name",
                                String.valueOf(depId))));
                    } catch (Exception ignored) {
                        // one broken dependency must not kill the whole install
                    }
                }
            }
        }
        return target;
    }

    // ---------------------------------------------------------------- modpacks

    public static void searchModpacks(String query, String gameVersion, String loader,
                                      int index, ModrinthHelper.ModpackSearchCallback cb) {
        executor.submit(() -> {
            try {
                StringBuilder q = new StringBuilder();
                q.append("gameId=").append(GAME_ID);
                q.append("&classId=").append(CLASS_MODPACKS);
                q.append("&sortField=2&sortOrder=desc&pageSize=20&index=")
                        .append(Math.max(0, index));
                int loaderType = loaderType(loader);
                if (loaderType > 0) q.append("&modLoaderType=").append(loaderType);
                if (gameVersion != null && !gameVersion.trim().isEmpty()) {
                    q.append("&gameVersion=").append(enc(gameVersion.trim()));
                }
                if (query != null && !query.trim().isEmpty()) {
                    q.append("&searchFilter=").append(enc(query.trim()));
                }

                JSONObject json = getJson("/mods/search", q.toString());
                JSONArray data = json.optJSONArray("data");
                List<ModrinthHelper.ModpackHit> hits = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.optJSONObject(i);
                        if (o == null) continue;
                        ModrinthHelper.ModpackHit hit = new ModrinthHelper.ModpackHit();
                        hit.id = String.valueOf(o.optLong("id"));
                        hit.title = o.optString("name", "Unknown");
                        hit.description = stripHtml(o.optString("summary", ""));
                        hit.author = authorOf(o);
                        hit.iconUrl = logoOf(o);
                        hit.downloads = o.optLong("downloadCount", 0);
                        hit.serverSide = "required";
                        hit.source = "curseforge";
                        hits.add(hit);
                    }
                }
                JSONObject pagination = json.optJSONObject("pagination");
                int total = pagination != null
                        ? pagination.optInt("totalCount", hits.size()) : hits.size();
                final int totalHits = total;
                main.post(() -> cb.onResult(hits, totalHits));
            } catch (Exception e) {
                main.post(() -> cb.onError(message(e)));
            }
        });
    }

    public static ModpackFile getLatestModpackSync(String projectId, String gameVersion,
                                                   String loader) {
        try {
            long id = Long.parseLong(projectId);
            JSONObject file = pickFile(id, gameVersion, loaderType(loader), true);
            if (file == null) return null;
            String url = file.optString("downloadUrl", "");
            if (url.isEmpty()) return null;

            ModpackFile out = new ModpackFile();
            out.downloadUrl = url;
            out.filename = sanitizeFileName(file.optString("fileName", "modpack.zip"));
            out.sha1 = hashFrom(file.optJSONArray("hashes"), 1);
            out.projectId = id;
            out.fileId = file.optLong("id", 0);
            out.loader = loader == null ? "" : loader;
            out.gameVersion = firstMcVersion(file.optJSONArray("gameVersions"), gameVersion);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** one file of a modpack manifest, used by the installer. */
    public static JSONObject getFileSync(long projectId, long fileId) throws Exception {
        JSONObject data = getJson("/mods/" + projectId + "/files/" + fileId, null)
                .optJSONObject("data");
        if (data == null) throw new IllegalStateException("CurseForge file not found");
        return data;
    }

    // ---------------------------------------------------------------- files

    private static JSONObject pickFile(long modId, String gameVersion, int loader,
                                       boolean zip) throws Exception {
        StringBuilder q = new StringBuilder();
        if (gameVersion != null && !gameVersion.trim().isEmpty()) {
            q.append("gameVersion=").append(enc(gameVersion));
        }
        if (loader > 0) {
            if (q.length() > 0) q.append('&');
            q.append("modLoaderType=").append(loader);
        }
        if (q.length() > 0) q.append('&');
        q.append("pageSize=50");

        JSONObject json = getJson("/mods/" + modId + "/files", q.toString());
        JSONArray files = json.optJSONArray("data");
        if (files == null) return null;

        JSONObject fallback = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("fileName", "").toLowerCase();
            if (zip ? !name.endsWith(".zip") : !name.endsWith(".jar")) continue;
            if (f.optString("downloadUrl", "").isEmpty()) continue;
            if (!hasGameVersion(f, gameVersion)) continue;

            int releaseType = f.optInt("releaseType", 3);
            if (releaseType == 1) return f;
            if (fallback == null && releaseType == 2) fallback = f;
            if (fallback == null) fallback = f;
        }
        return fallback;
    }

    private static boolean hasGameVersion(JSONObject file, String gameVersion) {
        if (gameVersion == null || gameVersion.trim().isEmpty()) return true;
        JSONArray versions = file.optJSONArray("gameVersions");
        if (versions == null) return true;
        for (int i = 0; i < versions.length(); i++) {
            if (gameVersion.equals(versions.optString(i))) return true;
        }
        return false;
    }

    /**
     * Downloads one file after checking the host, the SHA-1 (or MD5) and writing to a
     * temporary file first. Only the CurseForge CDN is accepted.
     */
    public static void downloadTo(File target, String urlStr, JSONArray hashes,
                                  ModrinthHelper.DownloadCallback cb) throws Exception {
        URL url = new URL(urlStr);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalStateException("Only https downloads are allowed.");
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (!allowedDownloadHost(host)) {
            throw new IllegalStateException("Unexpected download host: " + host);
        }

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "KodaHosting/" + BuildConfig.VERSION_NAME);

        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IllegalStateException("Download failed (HTTP " + code + ")");
        }
        String finalHost = c.getURL().getHost() == null
                ? "" : c.getURL().getHost().toLowerCase();
        if (!allowedDownloadHost(finalHost)) {
            c.disconnect();
            throw new IllegalStateException("Unexpected redirect host: " + finalHost);
        }

        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        long total = c.getContentLengthLong();
        long done = 0, lastCb = 0;
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (cb != null && total > 0 && now - lastCb > 500) {
                    final int pct = (int) ((done * 100) / total);
                    main.post(() -> cb.onProgress(pct));
                    lastCb = now;
                }
            }
        } finally {
            c.disconnect();
        }

        String sha1 = hashFrom(hashes, 1);
        String md5 = hashFrom(hashes, 2);
        if (!sha1.isEmpty()) {
            String actual = DownloadVerifier.hashHex(tmp, "SHA-1");
            if (!DownloadVerifier.matches(sha1, actual)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException(
                        "Checksum mismatch (SHA-1), the file was discarded");
            }
        } else if (!md5.isEmpty()) {
            String actual = DownloadVerifier.hashHex(tmp, "MD5");
            if (!DownloadVerifier.matches(md5, actual)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException(
                        "Checksum mismatch (MD5), the file was discarded");
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!tmp.renameTo(target)) {
            throw new IllegalStateException("Could not move the download into place");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static JSONObject getJson(String path, String query) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("missing_key");
        String url = API_BASE + path
                + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("x-api-key", key());
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent",
                "KodaHosting/" + BuildConfig.VERSION_NAME + " (support@kodaserv.eu)");
        int code = c.getResponseCode();
        if (code == 403) {
            c.disconnect();
            throw new IllegalStateException("cf_403");
        }
        if (code != 200) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream is = c.getInputStream()) {
            Scanner sc = new Scanner(is).useDelimiter("\\A");
            return new JSONObject(sc.hasNext() ? sc.next() : "{}");
        } finally {
            c.disconnect();
        }
    }

    private static ModrinthHelper.ModrinthProject projectFrom(JSONObject o) {
        ModrinthHelper.ModrinthProject p = new ModrinthHelper.ModrinthProject();
        p.id = String.valueOf(o.optLong("id"));
        p.title = o.optString("name", "Unknown");
        p.description = stripHtml(o.optString("summary", ""));
        p.author = authorOf(o);
        p.iconUrl = logoOf(o);
        p.source = "curseforge";
        p.allowDistribution = o.optBoolean("allowModDistribution", true);
        return p;
    }

    private static String authorOf(JSONObject o) {
        JSONArray authors = o.optJSONArray("authors");
        if (authors == null || authors.length() == 0) return "";
        JSONObject first = authors.optJSONObject(0);
        return first == null ? "" : first.optString("name", "");
    }

    private static String logoOf(JSONObject o) {
        JSONObject logo = o.optJSONObject("logo");
        if (logo == null) return "";
        return logo.optString("thumbnailUrl", logo.optString("url", ""));
    }

    private static boolean allowedDownloadHost(String host) {
        return host.equals("forgecdn.net") || host.endsWith(".forgecdn.net")
                || host.equals("curseforge.com") || host.endsWith(".curseforge.com");
    }

    private static int loaderType(ServerInstance server) {
        return loaderType(server.getType().name().toLowerCase());
    }

    private static int loaderType(String loader) {
        if (loader == null) return 0;
        switch (loader.toLowerCase()) {
            case "forge": return 1;
            case "fabric": return 4;
            case "quilt": return 5;
            case "neoforge": return 6;
            default: return 0;
        }
    }

    private static String firstMcVersion(JSONArray gameVersions, String fallback) {
        if (gameVersions != null) {
            for (int i = 0; i < gameVersions.length(); i++) {
                String v = gameVersions.optString(i, "");
                if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) return v;
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String hashFrom(JSONArray hashes, int algo) {
        if (hashes == null) return "";
        for (int i = 0; i < hashes.length(); i++) {
            JSONObject h = hashes.optJSONObject(i);
            if (h != null && h.optInt("algo", -1) == algo) {
                return h.optString("value", "");
            }
        }
        return "";
    }

    public static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replace("..", "").trim();
        return name.isEmpty() ? "download.jar" : name;
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "error" : m;
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }
}
