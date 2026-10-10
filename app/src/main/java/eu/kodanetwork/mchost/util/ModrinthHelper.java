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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.kodanetwork.mchost.model.ServerInstance;

public class ModrinthHelper {

    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class ModrinthProject {
        public String id;
        public String title;
        public String description;
        public String author;
        public String iconUrl;
    }

    public interface SearchCallback {
        void onResult(List<ModrinthProject> results);
        void onError(String err);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onSuccess(File file);
        void onError(String err);
        /** every required dependency that came along with the install, called before onSuccess. */
        default void onDependencies(java.util.List<String> names) {}
    }

    // ---- modpack support, .mrpack files via modrinth ----

    public static class ModpackHit {
        public String id;
        public String title;
        public String description;
        public String author;
        public String iconUrl;
        public long downloads;
        public String serverSide = "";
    }

    public static class MrpackInfo {
        public String downloadUrl;
        public String filename;
        public String sha1;
        public String gameVersion;
    }

    public interface ModpackSearchCallback {
        void onResult(List<ModpackHit> hits, int totalHits);
        void onError(String err);
    }

    /** modpack search, 20 per page (offset = page*20). no query = top downloads, no gameVersion = all. */
    public static void searchModpacks(String query, String gameVersion, String modloader, int offset, ModpackSearchCallback cb) {
        executor.submit(() -> {
            try {
                StringBuilder url = new StringBuilder(API_BASE + "/search?limit=20&offset=" + Math.max(0, offset));
                String facets = "[[\"project_type:modpack\"]";
                if (modloader != null && !modloader.isEmpty()) {
                    facets += ",[\"categories:" + modloader + "\"]";
                }
                if (gameVersion != null && !gameVersion.trim().isEmpty()) {
                    facets += ",[\"versions:" + gameVersion.trim() + "\"]";
                }
                facets += "]";
                url.append("&facets=").append(URLEncoder.encode(facets, "UTF-8"));
                if (query != null && !query.trim().isEmpty()) {
                    url.append("&query=").append(URLEncoder.encode(query.trim(), "UTF-8"));
                } else {
                    url.append("&index=downloads");
                }
                HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                try (InputStream is = c.getInputStream()) {
                    StringBuilder sb = new StringBuilder();
                    byte[] b = new byte[8192];
                    int r;
                    while ((r = is.read(b)) != -1) sb.append(new String(b, 0, r));
                    JSONObject obj = new JSONObject(sb.toString());
                    int total = obj.optInt("total_hits", 0);
                    JSONArray hits = obj.getJSONArray("hits");
                    List<ModpackHit> res = new ArrayList<>();
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject h = hits.getJSONObject(i);
                        ModpackHit m = new ModpackHit();
                        m.id = h.optString("project_id");
                        m.title = h.optString("title");
                        m.description = h.optString("description");
                        m.author = h.optString("author");
                        m.iconUrl = h.optString("icon_url");
                        m.downloads = h.optLong("downloads", 0);
                        m.serverSide = h.optString("server_side", "");
                        res.add(m);
                    }
                    mainHandler.post(() -> cb.onResult(res, total));
                } finally { c.disconnect(); }
            } catch (Exception e) {
                android.util.Log.e("ModrinthModpack", "search failed: " + e, e);
                mainHandler.post(() -> cb.onError(e.getMessage() == null ? "error" : e.getMessage()));
            }
        });
    }

    /** the latest fabric .mrpack of a project (primary file), null when there is none. blocking. */
    public static MrpackInfo getLatestMrpackSync(String projectId) {
        try {
            String loaders = URLEncoder.encode("[\"fabric\"]", "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(
                    API_BASE + "/project/" + projectId + "/version?loaders=" + loaders + "&include_changelog=false").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            try (InputStream is = c.getInputStream()) {
                StringBuilder sb = new StringBuilder();
                byte[] b = new byte[8192];
                int r;
                while ((r = is.read(b)) != -1) sb.append(new String(b, 0, r));
                JSONArray versions = new JSONArray(sb.toString());
                if (versions.length() == 0) return null;
                JSONObject v = versions.getJSONObject(0);
                JSONArray files = v.getJSONArray("files");
                JSONObject file = null;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String fn = f.optString("filename", "");
                    if (f.optBoolean("primary", false) && fn.endsWith(".mrpack")) { file = f; break; }
                    if (file == null && fn.endsWith(".mrpack")) file = f;
                }
                if (file == null) return null;
                MrpackInfo info = new MrpackInfo();
                info.downloadUrl = file.getString("url");
                info.filename = file.optString("filename", "modpack.mrpack");
                info.sha1 = file.optJSONObject("hashes") != null
                        ? file.getJSONObject("hashes").optString("sha1", "") : "";
                JSONArray gv = v.optJSONArray("game_versions");
                info.gameVersion = gv != null && gv.length() > 0 ? gv.getString(0) : "";
                return info;
            } finally { c.disconnect(); }
        } catch (Exception e) {
            return null;
        }
    }

    public static void search(String query, ServerInstance.Type type, SearchCallback cb) {
        executor.submit(() -> {
            try {
                String projectType = (type == ServerInstance.Type.PAPER || type == ServerInstance.Type.PURPUR) ? "plugin" : "mod";
                String loader = type.name().toLowerCase();
                if (type == ServerInstance.Type.PURPUR) loader = "paper"; // purpur runs paper plugins
                
                String facets = "[[\"project_type:" + projectType + "\"],[\"categories:" + loader + "\"]]";
                String encodedFacets = URLEncoder.encode(facets, "UTF-8");
                String encodedQuery = URLEncoder.encode(query, "UTF-8");
                
                String urlStr = API_BASE + "/search?query=" + encodedQuery + "&facets=" + encodedFacets + "&limit=15";
                
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    mainHandler.post(() -> cb.onError("HTTP " + responseCode));
                    return;
                }
                
                InputStream is = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) sb.append(new String(buf, 0, r));
                is.close();
                
                JSONObject res = new JSONObject(sb.toString());
                JSONArray hits = res.getJSONArray("hits");
                
                List<ModrinthProject> projects = new ArrayList<>();
                for (int i = 0; i < hits.length(); i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    ModrinthProject p = new ModrinthProject();
                    p.id = hit.getString("project_id");
                    p.title = hit.getString("title");
                    p.description = hit.getString("description");
                    p.author = hit.getString("author");
                    p.iconUrl = hit.optString("icon_url", "");
                    projects.add(p);
                }
                
                mainHandler.post(() -> cb.onResult(projects));
                
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public static void loadIcon(String url, ImageView iv) {
        if (url == null || url.isEmpty()) return;
        iv.setTag(url);
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                if (bmp != null) {
                    mainHandler.post(() -> {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    public static void autoDownload(String projectId, ServerInstance server, DownloadCallback cb) {
        executor.submit(() -> {
            try {
                JSONObject version = resolveNewestVersion(projectId, server);
                if (version == null) {
                    final String mcVer = server.getVersion();
                    final String loader = loaderOrder(server).get(0);
                    mainHandler.post(() -> cb.onError("No compatible version found for " + mcVer + " (" + loader + ")"));
                    return;
                }

                String versionId = version.getString("id");
                if (versionId.equals(server.pluginVersions.get(projectId))) {
                    mainHandler.post(() -> cb.onError("Already up-to-date."));
                    return;
                }

                File targetFile = downloadVersionFile(projectId, server, version, cb);
                if (targetFile == null) {
                    mainHandler.post(() -> cb.onError("Download failed."));
                    return;
                }

                // whatever the version marks as required comes along, or the server
                // refuses to start with a wall of missing dependency errors
                java.util.List<String> installedDeps = new java.util.ArrayList<>();
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                seen.add(projectId);
                installDependencies(version, server, installedDeps, seen, 1, cb);
                if (!installedDeps.isEmpty()) {
                    final java.util.List<String> names = new java.util.ArrayList<>(installedDeps);
                    mainHandler.post(() -> cb.onDependencies(names));
                }

                mainHandler.post(() -> cb.onSuccess(targetFile));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError("Download exception: " + e.getMessage()));
            }
        });
    }

    /** newest version of a project that fits the server loader and minecraft version, null when there is none. */
    private static JSONObject resolveNewestVersion(String projectId, ServerInstance server) throws Exception {
        String mcVer = server.getVersion();
        for (String loader : loaderOrder(server)) {
            String qL = URLEncoder.encode("[\"" + loader + "\"]", "UTF-8");
            String qG = URLEncoder.encode("[\"" + mcVer + "\"]", "UTF-8");
            String u = API_BASE + "/project/" + projectId + "/version?loaders=" + qL + "&game_versions=" + qG;
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() == 200) {
                JSONArray versions = new JSONArray(readAll(conn));
                if (versions.length() > 0) return versions.getJSONObject(0);   // newest first
            }
        }
        return null;
    }

    /** downloads the primary file of one version into plugins/ or mods/ and remembers the version. */
    private static File downloadVersionFile(String projectId, ServerInstance server, JSONObject version,
                                            DownloadCallback cb) throws Exception {
        String versionId = version.getString("id");
        JSONArray files = version.getJSONArray("files");
        if (files.length() == 0) return null;

        JSONObject fileObj = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) {
            if (files.getJSONObject(i).optBoolean("primary", false)) {
                fileObj = files.getJSONObject(i);
                break;
            }
        }
        String downloadUrl = fileObj.getString("url");
        String fileName = fileObj.getString("filename");

        String folderName = isPluginServer(server) ? "plugins" : "mods";
        File targetDir = new File(server.getServerDir(), folderName);
        targetDir.mkdirs();
        File targetFile = new File(targetDir, fileName);

        HttpURLConnection dlConn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        dlConn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
        dlConn.setInstanceFollowRedirects(true);
        if (dlConn.getResponseCode() >= 300) return null;

        deleteOldVersion(server.pluginVersions.get(projectId), targetDir, fileName);

        long total = dlConn.getContentLengthLong();
        long downloaded = 0, lastCb = 0;
        try (InputStream in = dlConn.getInputStream();
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                downloaded += r;
                long now = System.currentTimeMillis();
                if (cb != null && total > 0 && now - lastCb > 500) {
                    final int pct = (int) ((downloaded * 100) / total);
                    mainHandler.post(() -> cb.onProgress(pct));
                    lastCb = now;
                }
            }
        }
        server.pluginVersions.put(projectId, versionId);
        return targetFile;
    }

    /**
     * walks the "required" dependencies of a version and installs the ones that are missing.
     * optional and incompatible entries are ignored, already installed projects are skipped,
     * and depth plus count are capped so a weird dependency web cannot loop forever.
     */
    private static void installDependencies(JSONObject version, ServerInstance server,
                                            java.util.List<String> installed, java.util.Set<String> seen,
                                            int depth, DownloadCallback cb) {
        if (depth > 4 || installed.size() >= 20) return;
        JSONArray deps = version.optJSONArray("dependencies");
        if (deps == null) return;

        for (int i = 0; i < deps.length(); i++) {
            JSONObject dep = deps.optJSONObject(i);
            if (dep == null) continue;
            if (!"required".equals(dep.optString("dependency_type"))) continue;
            String depProject = dep.optString("project_id", "");
            String depVersionId = dep.optString("version_id", "");
            try {
                if (depProject.isEmpty() && !depVersionId.isEmpty()) depProject = projectOfVersion(depVersionId);
                if (depProject.isEmpty() || !seen.add(depProject)) continue;
                if (server.pluginVersions.containsKey(depProject)) continue;   // already on the server

                JSONObject depVersion = resolveNewestVersion(depProject, server);
                if (depVersion == null && !depVersionId.isEmpty()) depVersion = versionById(depVersionId);
                if (depVersion == null) continue;
                File file = downloadVersionFile(depProject, server, depVersion, null);
                if (file == null) continue;
                installed.add(projectTitle(depProject, file.getName()));
                installDependencies(depVersion, server, installed, seen, depth + 1, cb);
            } catch (Exception ignored) {
                // one broken dependency must not kill the whole install
            }
        }
    }

    /** the project a modrinth version belongs to, empty when it cannot be read. */
    private static String projectOfVersion(String versionId) {
        JSONObject v = versionById(versionId);
        return v == null ? "" : v.optString("project_id", "");
    }

    private static JSONObject versionById(String versionId) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + "/version/" + versionId).openConnection();
            conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != 200) return null;
            return new JSONObject(readAll(conn));
        } catch (Exception e) {
            return null;
        }
    }

    /** display name of a project, falling back to the file name when modrinth cannot be asked. */
    private static String projectTitle(String projectId, String fallback) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + "/project/" + projectId).openConnection();
            conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() == 200) {
                String title = new JSONObject(readAll(conn)).optString("title", "");
                if (!title.isEmpty()) return title;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        try (InputStream is = conn.getInputStream()) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) sb.append(new String(buf, 0, r, "UTF-8"));
            return sb.toString();
        }
    }

    public static void deleteOldVersion(String oldVersionId, File targetDir, String newFileName) {
        if (oldVersionId == null || oldVersionId.isEmpty()) return;
        try {
            String oldU = API_BASE + "/version/" + oldVersionId;
            HttpURLConnection oldConn = (HttpURLConnection) new URL(oldU).openConnection();
            oldConn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            if (oldConn.getResponseCode() == 200) {
                InputStream ois = oldConn.getInputStream();
                java.util.Scanner os = new java.util.Scanner(ois).useDelimiter("\\A");
                String oldRes = os.hasNext() ? os.next() : "";
                ois.close();
                JSONObject oldObj = new JSONObject(oldRes);
                JSONArray oldFiles = oldObj.getJSONArray("files");
                for (int i = 0; i < oldFiles.length(); i++) {
                    String oldFn = oldFiles.getJSONObject(i).getString("filename");
                    File oldF = new File(targetDir, oldFn);
                    if (oldF.exists() && !oldF.getName().equals(newFileName)) {
                        oldF.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static String getLatestVersionIdSync(String projectId, ServerInstance server) {
        try {
            String loader = server.getType().name().toLowerCase();
            if (server.getType() == ServerInstance.Type.PURPUR) loader = "paper";
            
            String mcVer = server.getVersion();
            
            java.util.List<String> compLoaders = loaderOrder(server);
            
            JSONArray versions = null;
            for (String l : compLoaders) {
                String qL = URLEncoder.encode("[\"" + l + "\"]", "UTF-8");
                String qG = URLEncoder.encode("[\"" + mcVer + "\"]", "UTF-8");
                
                String u = API_BASE + "/project/" + projectId + "/version?loaders=" + qL + "&game_versions=" + qG;
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096]; int r;
                    while ((r = is.read(buf)) != -1) sb.append(new String(buf, 0, r));
                    is.close();
                    versions = new JSONArray(sb.toString());
                    if (versions.length() > 0) break;
                }
            }
            
            if (versions == null || versions.length() == 0) return null;
            return versions.getJSONObject(0).getString("id");
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * servers that load their addons from plugins/. everything else (Fabric, Forge,
     * NeoForge) uses mods/, which changes the folder and the loader name that
     * modrinth gets asked for.
     */
    public static boolean isPluginServer(ServerInstance server) {
        switch (server.getType()) {
            case PAPER:
            case PURPUR:
            case FOLIA:
            case VELOCITY:
                return true;
            default:
                return false;
        }
    }

    /** loader names to try on modrinth for this server type, best match first. */
    private static java.util.List<String> loaderOrder(ServerInstance server) {
        switch (server.getType()) {
            case PAPER:    return java.util.Arrays.asList("paper", "spigot", "bukkit");
            case PURPUR:   return java.util.Arrays.asList("purpur", "paper", "spigot", "bukkit");
            case FOLIA:    return java.util.Arrays.asList("folia", "paper", "spigot", "bukkit");
            case VELOCITY: return java.util.Arrays.asList("velocity", "bungeecord", "waterfall");
            case NEOFORGE: return java.util.Arrays.asList("neoforge", "forge");
            default:       return java.util.Arrays.asList(server.getType().name().toLowerCase());
        }
    }

    public static File autoDownloadSync(String projectId, ServerInstance server) {
        try {
            String loader = loaderOrder(server).get(0);
            String mcVer = server.getVersion();
            
            String urlStr = API_BASE + "/project/" + projectId;
            HttpURLConnection checkConn = (HttpURLConnection) new URL(urlStr).openConnection();
            checkConn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            checkConn.setRequestMethod("GET");
            if (checkConn.getResponseCode() != 200) {
                // no such project id, so search for it instead
                String projectType = isPluginServer(server) ? "plugin" : "mod";
                String catFacets;
                if (isPluginServer(server)) {
                    catFacets = "[\"categories:paper\",\"categories:spigot\",\"categories:bukkit\"]";
                } else {
                    catFacets = "[\"categories:" + loader + "\"]";
                }
                String facetsStr = "[[\"project_type:" + projectType + "\"]," + catFacets + "]";
                String encodedFacets = URLEncoder.encode(facetsStr, "UTF-8");
                String searchUrl = API_BASE + "/search?query=" + URLEncoder.encode(projectId, "UTF-8") + "&facets=" + encodedFacets + "&limit=10";
                HttpURLConnection searchConn = (HttpURLConnection) new URL(searchUrl).openConnection();
                searchConn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                if (searchConn.getResponseCode() == 200) {
                    InputStream sis = searchConn.getInputStream();
                    StringBuilder ssb = new StringBuilder();
                    byte[] sbuf = new byte[4096];
                    int sr;
                    while ((sr = sis.read(sbuf)) != -1) ssb.append(new String(sbuf, 0, sr));
                    sis.close();
                    JSONObject res = new JSONObject(ssb.toString());
                    JSONArray hits = res.getJSONArray("hits");
                    if (hits.length() > 0) {
                        projectId = hits.getJSONObject(0).getString("project_id");
                    } else {
                        return null; // the search found nothing at all
                    }
                } else {
                    return null;
                }
            }
            
            java.util.List<String> compLoaders = loaderOrder(server);
            
            JSONArray versions = null;
            for (String l : compLoaders) {
                String qL = URLEncoder.encode("[\"" + l + "\"]", "UTF-8");
                String qG = URLEncoder.encode("[\"" + mcVer + "\"]", "UTF-8");
                
                String u = API_BASE + "/project/" + projectId + "/version?loaders=" + qL + "&game_versions=" + qG;
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096]; int r;
                    while ((r = is.read(buf)) != -1) sb.append(new String(buf, 0, r));
                    is.close();
                    versions = new JSONArray(sb.toString());
                    if (versions.length() > 0) break;
                }

            }
            
            if (versions == null || versions.length() == 0) return null;
            
            JSONObject latest = versions.getJSONObject(0);
            String latestVersionId = latest.getString("id");
            JSONArray files = latest.getJSONArray("files");
            if (files.length() == 0) return null;
            
            JSONObject fileObj = files.getJSONObject(0);
            for (int i = 0; i < files.length(); i++) {
                if (files.getJSONObject(i).optBoolean("primary", false)) {
                    fileObj = files.getJSONObject(i);
                    break;
                }
            }
            
            String downloadUrl = fileObj.getString("url");
            String fileName = fileObj.getString("filename");
            
            String folderName = isPluginServer(server) ? "plugins" : "mods";
            File targetDir = new File(server.getServerDir(), folderName);
            targetDir.mkdirs();
            File targetFile = new File(targetDir, fileName);
            
            if (latestVersionId.equals(server.pluginVersions.get(projectId)) && targetFile.exists()) {
                return targetFile; // already up to date
            }
            
            HttpURLConnection dlConn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            dlConn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            dlConn.setInstanceFollowRedirects(true);
            
            if (dlConn.getResponseCode() >= 300) return null;
            
            
            String oldVersionId = server.pluginVersions.get(projectId);
            deleteOldVersion(oldVersionId, targetDir, fileName);
            
            InputStream dlIs = dlConn.getInputStream();
            FileOutputStream fos = new FileOutputStream(targetFile);
            byte[] dlBuf = new byte[8192];
            int dlR;
            while ((dlR = dlIs.read(dlBuf)) != -1) fos.write(dlBuf, 0, dlR);
            fos.close();
            dlIs.close();
            
            server.pluginVersions.put(projectId, latestVersionId);

            // required dependencies (fabric api and friends) come along automatically
            try {
                java.util.List<String> deps = new java.util.ArrayList<>();
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                seen.add(projectId);
                installDependencies(latest, server, deps, seen, 1, null);
            } catch (Exception ignored) {
                // the main jar is in, a dependency that failed is not fatal here
            }

            return targetFile;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Finds the modrinth project for a mod id that a crash log complains about.
     * Searches with the server loader and minecraft version, and prefers an exact
     * slug match: the top hit for a short id like "cloth-config" is not always right.
     */
    public static String searchProjectIdForMod(String modId, ServerInstance server) {
        try {
            boolean pluginServer = isPluginServer(server);
            String facetLoader = pluginServer ? "paper" : server.getType().name().toLowerCase();
            String facets = "[[\"project_type:" + (pluginServer ? "plugin" : "mod") + "\"]"
                    + ",[\"versions:" + server.getVersion() + "\"]"
                    + ",[\"categories:" + facetLoader + "\"]]";
            String url = API_BASE + "/search?limit=8"
                    + "&query=" + URLEncoder.encode(modId, "UTF-8")
                    + "&facets=" + URLEncoder.encode(facets, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) return null;

            JSONArray hits = new JSONObject(readAll(conn)).optJSONArray("hits");
            if (hits == null || hits.length() == 0) return null;

            String wanted = modId.toLowerCase().replace(" ", "-");
            String firstHit = null;
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                String slug = hit.optString("slug", "").toLowerCase();
                String title = hit.optString("title", "").toLowerCase().replace(" ", "-");
                if (firstHit == null) firstHit = hit.optString("project_id", "");
                if (slug.equals(wanted) || title.equals(wanted)) {
                    return hit.optString("project_id", firstHit);
                }
            }
            return firstHit.isEmpty() ? null : firstHit;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getSha1Hash(File file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            InputStream is = new java.io.FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            is.close();
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void scanAndUpdateImportedPluginSync(File jarFile, ServerInstance server, Handler handler, android.widget.TextView loadingText) {
        try {
            String hash = getSha1Hash(jarFile);
            if (hash == null) return;

            String urlStr = API_BASE + "/version_file/" + hash + "?algorithm=sha1";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
            
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String res = s.hasNext() ? s.next() : "";
                is.close();
                
                JSONObject versionObj = new JSONObject(res);
                String projectId = versionObj.getString("project_id");
                String versionId = versionObj.getString("id");
                
                server.pluginVersions.put(projectId, versionId);
                
                if (handler != null && loadingText != null) {
                    handler.post(() -> loadingText.setText("Update: " + jarFile.getName() + " ..."));
                }
                
                File newJar = autoDownloadSync(projectId, server);
                if (newJar != null && newJar.exists()) {
                    if (!jarFile.getAbsolutePath().equals(newJar.getAbsolutePath())) {
                        jarFile.delete();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
