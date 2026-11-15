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
package de.kodahosting.kodadash.util;

import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * downloads a plugin jar into plugins/ without letting a caller reach the
 * phone's own network.
 *
 * only https, every host and every redirect target is resolved and checked for
 * loopback/private/link-local/reserved addresses, the file must be a plain
 * .jar name, and the download is capped before it is moved into place.
 */
public final class PluginInstaller {

    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private PluginInstaller() {
    }

    public static File install(KodaDash plugin, String urlStr, String filename) throws IOException {
        validateFilename(filename);
        File pluginsDir = new File(Bukkit.getServer().getWorldContainer(), "plugins");
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            throw new IOException("plugins folder could not be created");
        }
        File target = new File(pluginsDir, filename);
        String canonicalPlugins = pluginsDir.getCanonicalPath();
        String canonicalTarget = target.getCanonicalPath();
        if (!canonicalTarget.equals(canonicalPlugins)
                && !canonicalTarget.startsWith(canonicalPlugins + File.separator)) {
            throw new IOException("target is outside plugins/");
        }

        File temp = File.createTempFile("kodadash-mcp-", ".part", pluginsDir);
        HttpURLConnection connection = null;
        try {
            connection = open(urlStr);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("download returned HTTP " + code);
            }

            long total = 0L;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) {
                        throw new IOException("plugin is larger than 100 MB");
                    }
                    out.write(buffer, 0, read);
                }
            }

            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.getLogger().info("MCP installed plugin " + filename + " (" + total + " bytes)");
            return target;
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    /** resolves the URL and every redirect, rejecting anything that is not a public https host. */
    private static HttpURLConnection open(String urlStr) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateUrl(current);
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "KodaDash-MCP/1.1");

            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("redirect without Location");
                }
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("too many redirects");
    }

    public static void validateUrl(String urlStr) throws IOException {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            throw new IOException("missing url");
        }
        URL url;
        try {
            url = new URL(urlStr.trim());
        } catch (Exception e) {
            throw new IOException("invalid url");
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("only https is allowed");
        }
        String host = url.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("url has no host");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("host did not resolve");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IOException("url points to a local, private or reserved address");
            }
        }
    }

    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 0xff;
            int c = b[1] & 0xff;
            int d = b[2] & 0xff;
            if (a == 0 || a == 10 || a == 127) return true;
            if (a == 169 && c == 254) return true;
            if (a == 172 && c >= 16 && c <= 31) return true;
            if (a == 192 && c == 168) return true;
            if (a == 100 && c >= 64 && c <= 127) return true;
            if (a == 192 && c == 0 && d == 0) return true;
            if (a == 192 && c == 0 && d == 2) return true;
            if (a == 198 && (c == 18 || c == 19)) return true;
            if (a == 198 && c == 51 && d == 100) return true;
            if (a == 203 && c == 0 && d == 113) return true;
            if (a >= 224) return true;
        } else if (b.length == 16) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
            if (first == 0xfe && (b[1] & 0xc0) == 0x80) return true;
            if (first == 0x20 && (b[1] & 0xff) == 0x01
                    && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return true;
        }
        return false;
    }

    private static void validateFilename(String filename) throws IOException {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IOException("missing filename");
        }
        String name = filename.trim();
        if (name.length() > 120 || name.contains("..") || name.contains("/")
                || name.contains("\\") || !name.toLowerCase().endsWith(".jar")) {
            throw new IOException("filename must be a plain .jar name");
        }
        if (!new File(name).getName().equals(name)) {
            throw new IOException("filename must not contain a path");
        }
    }
}
