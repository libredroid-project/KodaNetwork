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
import android.net.Uri;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP extraction that reads the central directory instead of streaming entries.
 * ZipInputStream failed on some otherwise valid backups with
 * "Unexpected end of ZLIB input stream" when the archive used data descriptors
 * or ZIP64; ZipFile handles both.
 */
public final class ZipExtract {

    private ZipExtract() {}

    public static void extract(File zipFile, File destDir) throws IOException {
        extract(zipFile, destDir, false, false);
    }

    /** for archives that wrap everything in one top folder, e.g. a zipped server dir. */
    public static void extractStrippingRoot(File zipFile, File destDir) throws IOException {
        extract(zipFile, destDir, true, false);
    }

    /** keeps the files read before a truncated tail instead of failing the import. */
    public static void extractTolerant(File zipFile, File destDir) throws IOException {
        extract(zipFile, destDir, false, true);
    }

    public static void extractStrippingRootTolerant(File zipFile, File destDir) throws IOException {
        extract(zipFile, destDir, true, true);
    }

    private static void extract(File zipFile, File destDir, boolean stripRoot,
                                boolean tolerant) throws IOException {
        try {
            extractCentral(zipFile, destDir, stripRoot);
        } catch (Exception centralError) {
            // some valid backups have a damaged or missing end record, so the
            // central directory cannot be found. fall back to the local headers.
            try {
                extractStreaming(zipFile, destDir, stripRoot, tolerant);
            } catch (Exception streamError) {
                throw new IOException("ZIP central: " + centralError.getMessage()
                        + "; stream: " + streamError.getMessage(), streamError);
            }
        }
    }

    private static void extractCentral(File zipFile, File destDir, boolean stripRoot) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("Could not create " + destDir);
        }
        String base = destDir.getCanonicalPath() + File.separator;

        try (ZipFile zip = new ZipFile(zipFile)) {
            String prefix = stripRoot ? commonRoot(zip) : "";
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!prefix.isEmpty() && name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                }
                if (name.isEmpty()) continue;

                File out = new File(destDir, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(base) && !canonical.equals(destDir.getCanonicalPath())) {
                    continue; // zip slip
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
                }
            }
        }
    }

    /**
     * sequential fallback that does not need the end-of-central-directory record.
     * allowStoredEntriesWithDataDescriptor is what makes the truncated-record
     * backups readable; the plain ZipInputStream rejected them.
     */
    private static int extractStreaming(File zipFile, File destDir, boolean stripRoot,
                                        boolean tolerant) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("Could not create " + destDir);
        }
        String base = destDir.getCanonicalPath() + File.separator;
        int extracted = 0;
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)), "UTF-8", true, true)) {
            String prefix = "";
            ZipArchiveEntry first = zip.getNextEntry();
            if (first != null) {
                if (stripRoot) {
                    String name = first.getName();
                    int slash = name.indexOf('/');
                    if (slash > 0) prefix = name.substring(0, slash) + "/";
                }
                writeStreamingEntry(zip, first, destDir, base, prefix);
                extracted++;
            }
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                writeStreamingEntry(zip, entry, destDir, base, prefix);
                extracted++;
            }
        } catch (IOException e) {
            // a backup whose tail is cut off still has all entries before the cut.
            if (!tolerant || extracted == 0) throw e;
        }
        return extracted;
    }

    private static void writeStreamingEntry(ZipArchiveInputStream zip, ZipArchiveEntry entry,
                                            File destDir, String base, String prefix) throws IOException {
        String name = entry.getName();
        if (!prefix.isEmpty() && name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        }
        if (name.isEmpty()) return;
        File out = new File(destDir, name);
        String canonical = out.getCanonicalPath();
        if (!canonical.startsWith(base) && !canonical.equals(destDir.getCanonicalPath())) {
            return; // zip slip
        }
        if (entry.isDirectory()) {
            out.mkdirs();
            return;
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = zip.read(buffer)) > 0) os.write(buffer, 0, read);
        }
    }

    /** "" when the archive has files at its root or more than one top folder. */
    private static String commonRoot(ZipFile zip) {
        String root = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.isEmpty()) continue;
            int slash = name.indexOf('/');
            if (slash <= 0) return "";
            String top = name.substring(0, slash);
            if (root == null) root = top;
            else if (!root.equals(top)) return "";
        }
        return root == null ? "" : root + "/";
    }

    /** entry names for the import wizard, with the same central/stream fallback. */
    public static List<String> listNames(File zipFile) throws IOException {
        try {
            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(zipFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
            }
            return names;
        } catch (Exception centralError) {
            List<String> names = new ArrayList<>();
            try (ZipArchiveInputStream zip = new ZipArchiveInputStream(
                    new BufferedInputStream(new FileInputStream(zipFile)), "UTF-8", true, true)) {
                ZipArchiveEntry entry;
                while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            } catch (IOException ignored) {
                // truncated tail: keep the names we already saw, the forge jar is early
            }
            return names;
        }
    }

    /** content URIs are copied first, so the extractor sees a complete local file. */
    public static File copyToCache(Context context, Uri uri, String prefix) throws IOException {
        File temp = File.createTempFile(prefix, ".zip", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("No input stream");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (temp.length() == 0) {
            temp.delete();
            throw new IOException("Empty zip");
        }
        return temp;
    }
}
