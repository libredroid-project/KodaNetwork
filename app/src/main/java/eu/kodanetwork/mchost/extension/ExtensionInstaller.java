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

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;

/**
 * reads and unpacks .kodaext packages, that is a ZIP with a manifest.json.
 *
 * safety rules: hard size caps, an entry count cap, no symlinks, no absolute
 * paths, no ".." anywhere, and a canonical path check before every write.
 * a package zipped together with its enclosing folder (the usual "zipped the
 * wrapper directory too" mistake) is unpacked flat automatically.
 */
public final class ExtensionInstaller {

    public static final long MAX_PACKAGE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;
    public static final int MAX_ENTRIES = 800;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;

    public static final class InstallException extends Exception {
        public InstallException(String reason) { super(reason); }
    }

    private ExtensionInstaller() {}

    /** reads and checks manifest.json from the package, unpacking nothing yet. */
    public static ExtensionManifest readManifest(File zip, int appVersionCode) throws InstallException {
        if (!zip.isFile()) throw new InstallException("file not found");
        if (zip.length() > MAX_PACKAGE_BYTES) throw new InstallException("package too large");
        try (ZipFile zf = open(zip)) {
            String entryName = findManifestEntry(zf);
            if (entryName == null) throw new InstallException("manifest.json missing");
            ZipArchiveEntry entry = zf.getEntry(entryName);
            if (entry == null) throw new InstallException("manifest.json missing");
            byte[] data = readEntry(zf, entry, MAX_MANIFEST_BYTES);
            try {
                return ExtensionManifest.parse(new String(data, StandardCharsets.UTF_8), appVersionCode);
            } catch (ExtensionManifest.InvalidManifestException e) {
                // the user needs the manifest reason, not a generic "not a valid package"
                String reason = e.getMessage();
                throw new InstallException(reason == null || reason.isEmpty() ? "invalid manifest" : reason);
            }
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            throw new InstallException("not a valid package (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** unpacks into targetDir and drops the wrapper folder when the zip has one. */
    public static void extract(File zip, File targetDir) throws InstallException {
        try (ZipFile zf = open(zip)) {
            String prefix = wrapperPrefix(zf);
            File canonicalRoot = targetDir.getCanonicalFile();
            if (!canonicalRoot.isDirectory() && !canonicalRoot.mkdirs()) {
                throw new InstallException("could not create extension folder");
            }
            long total = 0;
            int count = 0;
            Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new InstallException("too many files");
                if (e.isUnixSymlink()) throw new InstallException("symlinks are not allowed");

                String name = e.getName().replace('\\', '/');
                if (!prefix.isEmpty()) {
                    if (!name.startsWith(prefix)) continue;
                    name = name.substring(prefix.length());
                }
                if (name.isEmpty()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw new InstallException("illegal path: " + name);
                }

                File out = new File(canonicalRoot, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.equals(canonicalRoot.getPath()) && !canonical.startsWith(canonicalRoot.getPath() + File.separator)) {
                    throw new InstallException("illegal path: " + name);
                }

                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = zf.getInputStream(e); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) throw new InstallException("package too large after unpacking");
                        fos.write(buf, 0, n);
                    }
                }
            }
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            throw new InstallException("could not unpack package (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("sha256 failed");
        }
    }

    private static ZipFile open(File zip) throws IOException {
        return ZipFile.builder().setFile(zip).get();
    }

    private static String findManifestEntry(ZipFile zf) {
        Enumeration<ZipArchiveEntry> entries = zf.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry e = entries.nextElement();
            String name = e.getName().replace('\\', '/');
            if (name.equals("manifest.json") || name.equals("./manifest.json")) return e.getName();
        }
        // a single wrapper folder (e.g. "my-extension/manifest.json") is fine too
        entries = zf.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry e = entries.nextElement();
            String name = e.getName().replace('\\', '/');
            if (name.endsWith("/manifest.json") && name.chars().filter(c -> c == '/').count() == 1) {
                return e.getName();
            }
        }
        return null;
    }

    private static String wrapperPrefix(ZipFile zf) {
        Enumeration<ZipArchiveEntry> entries = zf.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry e = entries.nextElement();
            String name = e.getName().replace('\\', '/');
            if (name.equals("manifest.json") || name.equals("./manifest.json")) return "";
        }
        entries = zf.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry e = entries.nextElement();
            String name = e.getName().replace('\\', '/');
            if (name.endsWith("/manifest.json") && name.chars().filter(c -> c == '/').count() == 1) {
                return name.substring(0, name.indexOf('/') + 1);
            }
        }
        return "";
    }

    private static byte[] readEntry(ZipFile zf, ZipArchiveEntry e, int maxBytes) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) throw new IOException("entry too large");
            }
            return bos.toByteArray();
        }
    }
}
