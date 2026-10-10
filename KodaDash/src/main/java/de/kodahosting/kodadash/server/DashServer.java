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
package de.kodahosting.kodadash.server;

import com.sun.net.httpserver.HttpServer;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.routes.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * the little HTTP server behind the KodaDash dashboard.
 * it uses the JDK's own HttpServer, so there is nothing extra to ship.
 */
public class DashServer {
    private final KodaDash plugin;
    private HttpServer server;
    private ExecutorService executor;
    private ServerActionRoute serverActionRoute;

    public DashServer(KodaDash plugin) {
        this.plugin = plugin;
    }

    /** @return the route that runs the restart/stop countdowns, the scheduler uses it too. */
    public ServerActionRoute getServerActionRoute() {
        return serverActionRoute;
    }

    /**
     * brings the dashboard up together with all its API routes.
     */
    public void start() {
        int port = plugin.getConfig().getInt("port", 7867);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // every API route, the path is what gets matched first
            server.createContext("/api/auth", new AuthRoute(plugin));
            server.createContext("/api/server", new ServerRoute(plugin));
            server.createContext("/api/console", new ConsoleRoute(plugin));
            server.createContext("/api/players", new PlayersRoute(plugin));
            server.createContext("/api/files", new FilesRoute(plugin));
            server.createContext("/api/settings", new SettingsRoute(plugin));
            server.createContext("/api/plugins", new PluginsRoute(plugin));
            server.createContext("/api/device", new DeviceRoute(plugin));
            server.createContext("/api/logs", new LogsRoute(plugin));
            server.createContext("/api/backups", new BackupsRoute(plugin));
            serverActionRoute = new ServerActionRoute(plugin);
            server.createContext("/api/server-action", serverActionRoute);
            server.createContext("/api/schedule", new ScheduleRoute(plugin));
            server.createContext("/api/stats", new StatsRoute(plugin));
            server.createContext("/api/efficiency", new EfficiencyRoute(plugin));

            // the web UI itself, with the SPA fallback behind it
            server.createContext("/", new StaticHandler(plugin));
            server.createContext("/resourcepack.zip", new ResourcepackRoute(plugin));

            executor = Executors.newFixedThreadPool(10);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("KodaDash dashboard started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start dashboard server: " + e.getMessage());
        }
    }

    /**
     * stops the HTTP server and gives open requests a moment to finish.
     */
    public void stop() {
        if (server != null) {
            server.stop(3);
            plugin.getLogger().info("Dashboard server stopped.");
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * @return the port the dashboard listens on.
     */
    public int getPort() {
        return plugin.getConfig().getInt("port", 7867);
    }
}
