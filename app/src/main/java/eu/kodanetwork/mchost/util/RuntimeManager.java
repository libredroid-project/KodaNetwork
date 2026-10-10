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
    private static String runtimeUrl(int version) {
        return eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                + "/storage/v1/object/public/artifacts/jre" + version + "-android-arm64.tar.xz";
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
        String url = runtimeUrl(version);
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
                download(url, archive, listener);
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
            if (msg.contains("archive has no bin/java")) {
                return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_archive_corrupt));
            }
            return Result.fail(ctx.getString(eu.kodanetwork.mchost.R.string.praetor_jre_reason_download_failed, msg));
        } finally {
            downloadLock.set(false);
        }
    }

    private static void download(String urlStr, File dest, ProgressListener listener) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
        try (InputStream is = c.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
            long total = c.getContentLengthLong();
            long done = 0;
            byte[] buf = new byte[16384];
            int r;
            int lastPct = -1;
            while ((r = is.read(buf)) != -1) {
                fos.write(buf, 0, r);
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
