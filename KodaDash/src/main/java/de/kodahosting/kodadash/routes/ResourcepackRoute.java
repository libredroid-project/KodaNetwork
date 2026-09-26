package de.kodahosting.kodadash.routes;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ResourcepackRoute implements HttpHandler {
    private final KodaDash plugin;

    public ResourcepackRoute(KodaDash plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        File rpFile = new File(plugin.getServer().getWorldContainer(), "resourcepack.zip");
        if (!rpFile.exists() || !rpFile.isFile()) {
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, rpFile.length());
        
        OutputStream os = exchange.getResponseBody();
        FileInputStream fs = new FileInputStream(rpFile);
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = fs.read(buffer)) >= 0) {
            os.write(buffer, 0, count);
        }
        fs.close();
        os.close();
    }
}
