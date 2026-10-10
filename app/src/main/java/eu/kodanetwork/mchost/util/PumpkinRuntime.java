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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * fetches the PumpkinMC server binary (rust, native aarch64-linux-android). the
 * primary source is the OFFICIAL PumpkinMC nightly release (raw ELF, ~100 MB), an
 * optional supabase tar.xz mirror is tried first, same pattern as the JRE runtimes.
 */
public class PumpkinRuntime {

    private static final AtomicBoolean downloadLock = new AtomicBoolean(false);

    // the official upstream nightly, always fresh, aarch64-android build (NDK, PIE)
    private static final String GITHUB_NIGHTLY =
            "https://github.com/Pumpkin-MC/Pumpkin/releases/download/nightly/pumpkin-aarch64-android";

    // optional compressed mirror in the artifacts bucket, the user manages it
    private static final String SUPABASE_URL =
            eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/storage/v1/object/public/artifacts/pumpkin/pumpkin-android-arm64.tar.xz";

    public static File getBinaryFile(Context ctx) {
        return new File(ctx.getFilesDir(), "runtimes/pumpkin/pumpkin-android-arm64");
    }

    public static boolean isInstalled(Context ctx) {
        File f = getBinaryFile(ctx);
        return f.exists() && f.canExecute() && f.length() > 10_000_000;
    }

    public static interface Progress {
        void onProgress(int percent, String message);
    }

    /** blocking, so call it off the main thread. true when the binary is ready. */
    public static boolean ensureBinarySync(Context ctx, Progress progress) {
        if (isInstalled(ctx)) return true;

        if (!downloadLock.compareAndSet(false, true)) {
            while (downloadLock.get()) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return false; }
            }
            return isInstalled(ctx);
        }
        try {
            File target = getBinaryFile(ctx);
            target.getParentFile().mkdirs();

            // the compressed supabase mirror wins when the user uploaded one, else
            // the official nightly raw ELF comes straight from github.
            if (resolveUrl(SUPABASE_URL) != null) {
                File tmp = new File(target.getParentFile(), "pumpkin-android-arm64.tar.xz");
                download(SUPABASE_URL, tmp, progress);
                extractTarXz(tmp, target.getParentFile());
                tmp.delete();
            } else {
                download(GITHUB_NIGHTLY, target, progress);
            }

            if (!isElf(target)) {
                target.delete();
                android.util.Log.e("PumpkinRuntime", "downloaded file is not an ELF binary");
                return false;
            }
            target.setExecutable(true, false);
            return isInstalled(ctx);
        } catch (Exception e) {
            android.util.Log.e("PumpkinRuntime", "binary provisioning failed", e);
            return false;
        } finally {
            downloadLock.set(false);
        }
    }

    /** sanity check: the file starts with the ELF magic bytes. */
    private static boolean isElf(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            return fis.read() == 0x7F && fis.read() == 'E' && fis.read() == 'L' && fis.read() == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /** the url when it answers with a 2xx, null otherwise. */
    private static String resolveUrl(String urlStr) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            int code = c.getResponseCode();
            c.disconnect();
            return (code >= 200 && code < 300) ? urlStr : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void download(String urlStr, File dest, Progress progress) throws Exception {
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
                if (total > 0 && progress != null) {
                    int pct = (int)(done * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        progress.onProgress(pct, done / 1048576 + "/" + total / 1048576 + " MB");
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void extractTarXz(File archive, File destDir) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(archive);
             XZCompressorInputStream xzIn = new XZCompressorInputStream(fis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {
            TarArchiveEntry entry;
            String canonicalDest = destDir.getCanonicalPath() + File.separator;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (!newFile.getCanonicalPath().startsWith(canonicalDest)) {
                    throw new IllegalStateException("tar slip blocked: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = tarIn.read(buf)) != -1) fos.write(buf, 0, r);
                    }
                }
            }
        }
    }
}
