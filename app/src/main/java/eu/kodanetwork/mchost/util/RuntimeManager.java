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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.kodanetwork.mchost.model.ServerInstance;

/**
 * picks the java runtime a server needs and fetches it when it is missing.
 *
 * - java 25 rides along in the APK (assets/jre25-android-arm64.tar.xz), StartOrchestrator
 *   extracts it to filesDir/jre25 and that is the proven default. the bundle comes from the
 *   same AngelAuraMC openjdk build family the other versions are downloaded from.
 * - java 8/17/21 are downloaded on demand as android-arm64 tar.xz from
 *   AngelAuraMC/angelauramc-openjdk-build (verified: ELF aarch64, /system/bin/linker64,
 *   NDK r27d). fabric servers default to 21 because a lot of mods only run there.
 */
public class RuntimeManager {

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    /**
     * fallback checksums for the artifacts that ship today; the bucket's
     * artifacts/runtimes.json overrides them, so an updated runtime does not need
     * an app release. every download is hashed while it streams and only unpacked
     * when it matches the expected value.
     */
    private static final java.util.Map<String, String> BUILTIN_SHA256 = new java.util.HashMap<>();
    static {
        BUILTIN_SHA256.put("jre8-android-arm64.tar.xz", "9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d");
        BUILTIN_SHA256.put("jre17-android-arm64.tar.xz", "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091");
        BUILTIN_SHA256.put("jre21-android-arm64.tar.xz", "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe");
    }

    private static volatile org.json.JSONObject manifestCache = null;
    private static volatile long manifestFetchedAt = 0L;

    /** name of the archive for this runtime version. */
    public static String runtimeFileName(int version) {
        return "jre" + version + "-android-arm64.tar.xz";
    }

    /** public download url of the archive (null for the bundled java 25). */
    public static String archiveUrl(int version) {
        if (version == 25) return null;
        return eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                + "/storage/v1/object/public/artifacts/" + runtimeFileName(version);
    }

    /** host shown in the "more infos" dialog. */
    public static String archiveHost(int version) {
        String url = archiveUrl(version);
        if (url == null) return null;
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** the checksum manifest in the bucket, cached for six hours; null when unreachable. */
    private static synchronized org.json.JSONObject fetchManifest() {
        if (manifestCache != null && System.currentTimeMillis() - manifestFetchedAt < 6L * 60 * 60 * 1000) {
            return manifestCache;
        }
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/storage/v1/object/public/artifacts/runtimes.json").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            try (InputStream is = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) out.write(buf, 0, r);
                manifestCache = new org.json.JSONObject(out.toString("UTF-8"));
                manifestFetchedAt = System.currentTimeMillis();
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("RuntimeManager", "checksum manifest unreachable: " + e.getMessage());
        }
        return manifestCache;
    }

    /** expected sha256 of this runtime's archive, null when unknown. */
    public static String expectedSha256(int version) {
        String name = runtimeFileName(version);
        org.json.JSONObject manifest = fetchManifest();
        if (manifest != null) {
            org.json.JSONObject entry = manifest.optJSONObject(name);
            if (entry != null) {
                String sha = entry.optString("sha256", "").toLowerCase();
                if (sha.matches("[0-9a-f]{64}")) return sha;
            }
        }
        return BUILTIN_SHA256.get(name);
    }

    /** expected archive size in bytes (0 when unknown). */
    public static long expectedSize(int version) {
        org.json.JSONObject manifest = fetchManifest();
        if (manifest != null) {
            org.json.JSONObject entry = manifest.optJSONObject(runtimeFileName(version));
            if (entry != null) return entry.optLong("size", 0L);
        }
        return 0L;
    }

    /** size of an installed runtime on disk, in bytes (walks the tree, call off the main thread). */
    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long total = 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            total += child.isDirectory() ? dirSize(child) : child.length();
        }
        return total;
    }

    // primary source, our own Supabase artifacts bucket (public).
    public static class Result {
        public final boolean success;
        public final String failReason;
        public Result(boolean success, String failReason) {
            this.success = success;
            this.failReason = failReason;
        }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String reason) { return new Result(false, reason); }
    }


    private static final AtomicBoolean downloadLock = new AtomicBoolean(false);

    /** picks the version from the minecraft version and the server type. */
    public static int resolveAutoVersion(ServerInstance srv) {
        return resolveAutoVersion(srv.getVersion(), srv.getType() == ServerInstance.Type.FABRIC);
    }
    
    public static int resolveAutoVersion(String ver, boolean isFabric) {
        if (ver == null || !ver.startsWith("1.")) return isFabric ? 21 : 25;
        try {
            String[] parts = ver.split("\\.");
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            
            if (minor >= 22) return 25;
            if (minor == 21 && patch >= 11) return 25; // 1.21.11 and up want 25
            if (minor == 21) return 21;
            if (minor == 20 && patch >= 5) return 21;
            if (minor >= 17) return 17; // everything from 1.17 to 1.20.4
            if (minor <= 16) return 8;  // 1.16.5 and older
        } catch (Exception ignored) {}
        
        return isFabric ? 21 : 25;
    }

    public static boolean isRuntimeInstalled(Context ctx, int version) {
        return getJavaBin(ctx, version) != null;
    }

    /** path to bin/java of that runtime, null when it is not installed. */
    public static String getJavaBin(Context ctx, int version) {
        File bin = new File(ctx.getFilesDir(), "jre" + version + "/bin/java");
        if (bin.exists()) {
            bin.setExecutable(true, false);
            return bin.getAbsolutePath();
        }
        return null;
    }

    /**
     * makes sure the runtime is there, downloads and extracts it on first use.
     * blocking, so call it off the main thread. the Result says whether it is ready.
     */
    public static Result ensureRuntimeSync(Context ctx, int version, ProgressListener listener) {
        if (isRuntimeInstalled(ctx, version)) return Result.ok();
        if (version == 25) return Result.ok(); // 25 is bundled, StartOrchestrator deals with it
        String url = archiveUrl(version);
        if (url == null) return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_unknown));

        if (!downloadLock.compareAndSet(false, true)) {
            // someone else is downloading, wait for that to end
            while (downloadLock.get()) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_unknown)); }
            }
            return isRuntimeInstalled(ctx, version) ? Result.ok() : Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_install_failed));
        }
        try {
            File targetDir = new File(ctx.getFilesDir(), "jre" + version);
            File tmpDir = new File(ctx.getFilesDir(), "jre" + version + "_tmp");
            deleteRecursive(tmpDir);
            tmpDir.mkdirs();

            File archive = new File(ctx.getCacheDir(), "jre" + version + ".tar.xz");
            try {
                download(url, archive, listener, expectedSha256(version));
                extractTarXzSafe(archive, tmpDir);
            } finally {
                archive.delete();
            }

            // the archive may unpack flat (./bin, ./lib) or inside one folder
            File contentDir = tmpDir;
            File[] children = tmpDir.listFiles();
            if (children != null && children.length == 1 && children[0].isDirectory()
                    && !new File(tmpDir, "bin/java").exists()) {
                contentDir = children[0];
            }
            if (!new File(contentDir, "bin/java").exists()) {
                throw new IllegalStateException("archive has no bin/java");
            }

            deleteRecursive(targetDir);
            if (!contentDir.renameTo(targetDir)) {
                throw new IllegalStateException("rename to jre" + version + " failed");
            }
            deleteRecursive(tmpDir);
            makeExecutableRecursive(targetDir);
            return Result.ok();
        } catch (java.net.UnknownHostException | java.net.ConnectException e) {
            android.util.Log.e("RuntimeManager", "jre" + version + " network failed", e);
            return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_download_unreachable));
        } catch (Exception e) {
            android.util.Log.e("RuntimeManager", "jre" + version + " provisioning failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (msg.contains("checksum mismatch")) {
                return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_checksum));
            }
            if (msg.contains("archive has no bin/java")) {
                return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_archive_corrupt));
            }
            return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_download_failed, msg));
        } finally {
            downloadLock.set(false);
        }
    }

    /**
     * streams the archive to disk and hashes it on the way; when an expected
     * sha256 is known, the file is only handed to the extractor when it matches.
     */
    private static void download(String urlStr, File dest, ProgressListener listener, String expectedSha) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            digest = null; // never happens on android, keeps the compiler happy
        }
        try (InputStream is = c.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
            long total = c.getContentLengthLong();
            long done = 0;
            byte[] buf = new byte[16384];
            int r;
            int lastPct = -1;
            while ((r = is.read(buf)) != -1) {
                fos.write(buf, 0, r);
                if (digest != null) digest.update(buf, 0, r);
                done += r;
                if (total > 0 && listener != null) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        listener.onProgress(pct, done / 1048576 + "/" + total / 1048576 + " MB");
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        if (expectedSha != null && digest != null) {
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            String actual = hex.toString();
            if (!actual.equalsIgnoreCase(expectedSha)) {
                // pointless to keep or unpack a file that is not what it should be
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new IllegalStateException("checksum mismatch: expected " + expectedSha + ", got " + actual);
            }
        }
    }

    /** tar.xz unpacking with canonical zip-slip protection and symlinks. */
    private static void extractTarXzSafe(File archive, File destDir) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(archive);
             org.apache.commons.compress.compressors.xz.XZCompressorInputStream xzIn =
                     new org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream tarIn =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xzIn)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            String canonicalDest = destDir.getCanonicalPath();
            if (!canonicalDest.endsWith(File.separator)) canonicalDest += File.separator;

            while ((entry = tarIn.getNextTarEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                String canonicalNewFile = newFile.getCanonicalPath();
                if (!canonicalNewFile.startsWith(canonicalDest) && !canonicalNewFile.equals(destDir.getCanonicalPath())) {
                    throw new IllegalStateException("archive slip blocked: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    newFile.getParentFile().mkdirs();
                    try {
                        java.nio.file.Path link = newFile.toPath();
                        java.nio.file.Path target = java.nio.file.Paths.get(entry.getLinkName());
                        if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            java.nio.file.Files.delete(link);
                        }
                        java.nio.file.Files.createSymbolicLink(link, target);
                    } catch (Exception e) {
                        android.util.Log.e("RuntimeManager", "Symlink failed: " + e.getMessage());
                    }
                } else {
                    newFile.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(newFile)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = tarIn.read(buf)) != -1) fos.write(buf, 0, r);
                    }
                }
            }
        }
    }

    private static void makeExecutableRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) makeExecutableRecursive(f);
            else f.setExecutable(true, false);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
