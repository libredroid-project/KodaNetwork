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
package de.kodahosting.kodadash;

import de.kodahosting.kodadash.auth.AuthManager;
import de.kodahosting.kodadash.commands.DashCommand;
import de.kodahosting.kodadash.managers.ConsoleManager;
import de.kodahosting.kodadash.managers.EfficiencyManager;
import de.kodahosting.kodadash.managers.FileManager;
import de.kodahosting.kodadash.managers.ScheduleManager;
import de.kodahosting.kodadash.managers.StatsManager;
import de.kodahosting.kodadash.server.DashServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;

/**
 * the plugin main class: creates the managers and starts the dashboard server.
 */
public class KodaDash extends JavaPlugin {
    private static KodaDash instance;
    private AuthManager authManager;
    private ConsoleManager consoleManager;
    private StatsManager statsManager;
    private FileManager fileManager;
    private ScheduleManager scheduleManager;
    private EfficiencyManager efficiencyManager;
    private DashServer dashServer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        generateInitialToken();
        
        authManager = new AuthManager(this);
        consoleManager = new ConsoleManager(this);
        statsManager = new StatsManager(this);
        fileManager = new FileManager(this);
        scheduleManager = new ScheduleManager(this);
        efficiencyManager = new EfficiencyManager(this);
        dashServer = new DashServer(this);
        
        getCommand("kodadash").setExecutor(new DashCommand(this));
        
        dashServer.start();
        startStatsFileTask();
        getLogger().info("KodaDash enabled.");
    }

    /**
     * writes the live stats into plugins/KodaDash/stats.json.
     *
     * the companion app reads this file for its server card (TPS, players, RAM). that keeps the
     * values out of the console: the "tps" command prints a line every ten seconds and that
     * cluttered the log and the dashboard console.
     */
    private void startStatsFileTask() {
        if (!getConfig().getBoolean("stats-file.enabled", true)) return;
        long ticks = Math.max(20L, getConfig().getLong("stats-file.interval-seconds", 10) * 20L);

        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                try {
                    com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                    json.addProperty("tps", statsManager.getTps());
                    json.addProperty("players", org.bukkit.Bukkit.getOnlinePlayers().size());
                    json.addProperty("maxPlayers", org.bukkit.Bukkit.getMaxPlayers());
                    json.addProperty("ramUsedMb", statsManager.getUsedRam());
                    json.addProperty("ramMaxMb", statsManager.getMaxRam());
                    json.addProperty("uptimeMs", statsManager.getUptime());
                    json.addProperty("updatedAt", System.currentTimeMillis());

                    java.io.File target = new java.io.File(getDataFolder(), "stats.json");
                    if (!getDataFolder().exists()) getDataFolder().mkdirs();
                    try (java.io.FileWriter writer = new java.io.FileWriter(target)) {
                        writer.write(json.toString());
                    }
                } catch (Exception ignored) {
                    // best effort, the dashboard falls back to its own API values
                }
            }
        }, ticks, ticks);
    }

    @Override
    public void onDisable() {
        if (dashServer != null) {
            dashServer.stop();
        }
        if (scheduleManager != null) {
            scheduleManager.shutdown();
        }
        if (efficiencyManager != null) {
            efficiencyManager.shutdown();
        }
        if (statsManager != null) {
            statsManager.shutdown();
        }
        if (consoleManager != null) {
            consoleManager.cleanup();
        }
        getLogger().info("KodaDash disabled.");
    }

    private void generateInitialToken() {
        if (!getConfig().contains("api-token") || getConfig().getString("api-token").isEmpty()) {
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            getConfig().set("api-token", sb.toString());
            saveConfig();
            getLogger().info("Generated initial API token.");
        }
    }

    public static KodaDash getInstance() { return instance; }
    public AuthManager getAuthManager() { return authManager; }
    public ConsoleManager getConsoleManager() { return consoleManager; }
    public StatsManager getStatsManager() { return statsManager; }
    public FileManager getFileManager() { return fileManager; }
    public ScheduleManager getScheduleManager() { return scheduleManager; }
    public EfficiencyManager getEfficiencyManager() { return efficiencyManager; }
    public DashServer getDashServer() { return dashServer; }
}
