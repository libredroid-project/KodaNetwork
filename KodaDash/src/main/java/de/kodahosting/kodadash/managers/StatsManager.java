package de.kodahosting.kodadash.managers;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * Tracks server performance metrics: TPS, RAM, CPU, threads, uptime.
 *
 * Besides the live values it keeps a rolling history (default one hour, one sample every ten
 * seconds) so the dashboard can draw a real chart instead of a single sparkline. The history
 * lives in memory only: a server restart starts a new series, which is what a chart should show.
 */
public class StatsManager {
    private final KodaDash plugin;
    private final long[] tickHistory = new long[20];
    private int tickIndex = 0;
    private final long startTime;

    private final int historySize;
    private final int historyIntervalSeconds;
    private final double[] histTps;
    private final int[] histPlayers;
    private final long[] histRamUsed;
    private final long[] histRamMax;
    private final double[] histCpu;
    private final long[] histTime;
    private int historyIndex = 0;
    private int historyCount = 0;
    private BukkitTask historyTask;

    public StatsManager(KodaDash plugin) {
        this.plugin = plugin;
        this.startTime = System.currentTimeMillis();

        // Track tick timestamps for TPS calculation
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickHistory[tickIndex % 20] = System.currentTimeMillis();
            tickIndex++;
        }, 0, 20L);

        this.historyIntervalSeconds = Math.max(5, plugin.getConfig().getInt("stats-history.interval-seconds", 10));
        int minutes = Math.max(5, plugin.getConfig().getInt("stats-history.minutes", 60));
        this.historySize = Math.max(12, (minutes * 60) / historyIntervalSeconds);
        histTps = new double[historySize];
        histPlayers = new int[historySize];
        histRamUsed = new long[historySize];
        histRamMax = new long[historySize];
        histCpu = new double[historySize];
        histTime = new long[historySize];
        startHistoryTask();
    }

    private void startHistoryTask() {
        if (!plugin.getConfig().getBoolean("stats-history.enabled", true)) return;
        long ticks = historyIntervalSeconds * 20L;
        historyTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    recordSample();
                } catch (Exception ignored) {
                    // Metrics are best effort
                }
            }
        }, ticks, ticks);
    }

    public void shutdown() {
        if (historyTask != null) {
            try { historyTask.cancel(); } catch (Exception ignored) {}
            historyTask = null;
        }
    }

    private synchronized void recordSample() {
        histTps[historyIndex] = getTps();
        histPlayers[historyIndex] = Bukkit.getOnlinePlayers().size();
        histRamUsed[historyIndex] = getUsedRam();
        histRamMax[historyIndex] = getMaxRam();
        histCpu[historyIndex] = getProcessCpuLoad();
        histTime[historyIndex] = System.currentTimeMillis();
        historyIndex = (historyIndex + 1) % historySize;
        if (historyCount < historySize) historyCount++;
    }

    /** @return the recorded samples in chronological order. */
    public synchronized JsonArray getHistory() {
        JsonArray array = new JsonArray();
        int start = historyCount < historySize ? 0 : historyIndex;
        for (int i = 0; i < historyCount; i++) {
            int slot = (start + i) % historySize;
            JsonObject sample = new JsonObject();
            sample.addProperty("t", histTime[slot]);
            sample.addProperty("tps", Math.round(histTps[slot] * 100.0) / 100.0);
            sample.addProperty("players", histPlayers[slot]);
            sample.addProperty("ramUsedMb", histRamUsed[slot]);
            sample.addProperty("ramMaxMb", histRamMax[slot]);
            sample.addProperty("cpu", histCpu[slot]);
            array.add(sample);
        }
        return array;
    }

    /**
     * Get all stats as a JSON object.
     */
    public JsonObject getStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("tps", getTps());
        stats.addProperty("players", Bukkit.getOnlinePlayers().size());
        stats.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        stats.addProperty("usedRam", getUsedRam());
        stats.addProperty("maxRam", getMaxRam());
        stats.addProperty("uptime", getUptime());
        stats.addProperty("cpu", getProcessCpuLoad());
        stats.addProperty("threads", getThreadCount());
        stats.addProperty("historySamples", historyCount);
        stats.addProperty("historyIntervalSeconds", historyIntervalSeconds);
        return stats;
    }

    /**
     * Get current TPS. Uses Paper's getTPS() if available, falls back to manual calculation.
     */
    public double getTps() {
        try {
            // Paper servers expose getTPS() - use reflection for Spigot API compatibility
            java.lang.reflect.Method getTPS = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] tps = (double[]) getTPS.invoke(Bukkit.getServer());
            return Math.min(20.0, Math.round(tps[0] * 100.0) / 100.0);
        } catch (Exception e) {
            // Fallback for non-Paper servers: calculate from tick history
            if (tickIndex < 20) return 20.0;
            long timeSpent = System.currentTimeMillis() - tickHistory[tickIndex % 20];
            if (timeSpent == 0) return 20.0;
            double calculatedTps = 20.0 / (timeSpent / 1000.0);
            return Math.min(20.0, Math.round(calculatedTps * 100.0) / 100.0);
        }
    }

    /**
     * Get used RAM in MB.
     */
    public long getUsedRam() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /**
     * Get max RAM in MB.
     */
    public long getMaxRam() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /**
     * CPU load of the server process in percent, or -1 when the runtime does not report it
     * (Android's runtime usually does not, which is why the app writes its own device metrics).
     */
    public double getProcessCpuLoad() {
        try {
            Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            Method method = bean.getClass().getMethod("getProcessCpuLoad");
            Object value = method.invoke(bean);
            if (value instanceof Double) {
                double load = (Double) value;
                if (load < 0) return -1.0;
                return Math.round(load * 1000.0) / 10.0;
            }
        } catch (Throwable ignored) {
            // Not available on this runtime
        }
        return -1.0;
    }

    /** Number of live threads in the server JVM, or -1 when unavailable. */
    public int getThreadCount() {
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Get server uptime in milliseconds since plugin was enabled.
     */
    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }
}
