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

import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

/**
 * serves the files of one installed extension to the host WebView, for its
 * https://appassets.androidplatform.net/ext/&lt;id&gt;/... URLs. every HTML file
 * gets the window.koda shim injected as its first script. paths are
 * canonicalized before every read, so a "../" style escape never gets out of
 * the extension folder.
 *
 * this used to go through androidx WebViewAssetLoader, but the loader's
 * PathMatcher did not match our URLs on the device (the request never reached
 * the handler and every file came back as an error), so the host intercepts
 * the requests itself now - one indirection less, and everything is logged.
 */
public class ExtensionAssetHandler {

    private static final String TAG = "KodaExtensions";
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

    private final File root;
    private final String prefix;
    private final String fullPrefix;

    public ExtensionAssetHandler(File root, String extensionId) {
        this.root = root;
        this.prefix = "/" + extensionId + "/";
        this.fullPrefix = "/ext/" + extensionId + "/";
    }

    /** takes the path of a request URL, e.g. "/ext/&lt;id&gt;/ui/index.html". */
    public WebResourceResponse handle(String path) {
        try {
            if (path == null) return notFound(path, null);
            String rel;
            if (path.startsWith(fullPrefix)) rel = path.substring(fullPrefix.length());
            else if (path.startsWith(prefix)) rel = path.substring(prefix.length());
            else if (!path.startsWith("/")) rel = path;
            else return notFound(path, null);
            if (rel.contains("..") || rel.contains("\\") || rel.contains(":")) return notFound(path, rel);

            File f = new File(root, rel);
            String canonicalRoot = root.getCanonicalPath();
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(canonicalRoot + File.separator)) return notFound(path, rel);
            if (!f.isFile()) return notFound(path, rel);

            String mime = mimeOf(rel);
            if ("text/html".equals(mime)) {
                byte[] raw = readAll(new FileInputStream(f), MAX_HTML_BYTES);
                String html = new String(raw, StandardCharsets.UTF_8);
                byte[] out = ExtensionShim.inject(html).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("text/html", "utf-8", 200, "OK",
                        new HashMap<>(), new ByteArrayInputStream(out));
            }
            return new WebResourceResponse(mime, null, new FileInputStream(f));
        } catch (Throwable t) {
            Log.w(TAG, "asset failed: " + path, t);
            return notFound(path, null);
        }
    }

    private static WebResourceResponse notFound(String path, String rel) {
        Log.w(TAG, "asset 404: path=" + path + " rel=" + rel);
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) throw new IllegalStateException("file too large");
            }
            return bos.toByteArray();
        }
    }

    private static String mimeOf(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }
}
