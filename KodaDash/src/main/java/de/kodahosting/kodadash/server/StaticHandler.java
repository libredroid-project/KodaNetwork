package de.kodahosting.kodadash.server;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.kodahosting.kodadash.KodaDash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for serving static web UI files (including the locally bundled Monaco editor).
 *
 * Every response carries an ETag so browsers revalidate instead of re-downloading the
 * ~2 MB editor on each page load, while updates still take effect after a plugin restart.
 */
public class StaticHandler implements HttpHandler {
    private final KodaDash plugin;
    private final ConcurrentHashMap<String, byte[]> fileCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> etagCache = new ConcurrentHashMap<>();

    public StaticHandler(KodaDash plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }

        byte[] data = fileCache.get(path);
        if (data == null) {
            data = loadFile("webui" + path);
            if (data == null) {
                // SPA fallback: unknown page -> index.html (but never for asset paths)
                if (!path.startsWith("/monaco/") && path.indexOf('.') < 0) {
                    data = loadFile("webui/index.html");
                    path = "/index.html";
                }
            }
            if (data != null) {
                fileCache.put(path, data);
            }
        }

        if (data == null) {
            String response = "404 Not Found";
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        String etag = etagCache.get(path);
        if (etag == null) {
            etag = computeEtag(data);
            etagCache.put(path, etag);
        }

        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", cacheControlFor(path));
            exchange.sendResponseHeaders(304, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", getMimeType(path));
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", cacheControlFor(path));
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** Big immutable assets (fonts, editor) may be cached; app code revalidates. */
    private String cacheControlFor(String path) {
        if (path.startsWith("/monaco/") || path.endsWith(".woff2") || path.endsWith(".ttf")) {
            return "public, max-age=86400";
        }
        return "no-cache";
    }

    private String computeEtag(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < 8 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.append('"').toString();
        } catch (Exception e) {
            return "\"" + Integer.toHexString(data.length) + "\"";
        }
    }

    private byte[] loadFile(String resourcePath) {
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }
}
