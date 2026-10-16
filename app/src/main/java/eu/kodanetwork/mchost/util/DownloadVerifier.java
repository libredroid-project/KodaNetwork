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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * checksum helpers for every file the app pulls from the internet.
 *
 * the sources publish different algorithms (Paper and GeyserMC sha256, Mojang and
 * the Forge mavens sha1, Modrinth sha512, Purpur md5, GitHub release assets
 * sha256). verification only happens when the source actually published a value,
 * so nothing pretends to be checked that is not.
 */
public final class DownloadVerifier {

    private DownloadVerifier() {}

    /** hex checksum of a file; algorithm is a MessageDigest name like "SHA-256". */
    public static String hashHex(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) digest.update(buf, 0, r);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** true when the expected value is missing (nothing to check) or the checksums match. */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || expectedHex.trim().isEmpty()) return true;
        return expectedHex.trim().toLowerCase(Locale.US).equals(actualHex.toLowerCase(Locale.US));
    }

    /** "1.2 MB" / "640 KB" / "12 B". */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
