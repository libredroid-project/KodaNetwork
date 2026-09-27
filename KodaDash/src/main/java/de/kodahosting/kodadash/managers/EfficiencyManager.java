package de.kodahosting.kodadash.managers;

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

import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Power saving: when nobody is on the server for a while, the view and simulation distance of
 * every world are lowered, and they are put back the moment a player joins.
 *
 * A phone hosting a server pays for every loaded chunk with battery and heat, and an idle
 * server is the normal state for most of the day. The distances are changed at runtime through
 * the world API (reflection keeps this compatible with plain Spigot), so no restart is needed.
 */
public class EfficiencyManager {
    private final KodaDash plugin;
    private final Map<String, int[]> savedDistances = new HashMap<>();
    private BukkitTask task;
    private long idleSince = 0L;
    private volatile boolean throttled = false;

    public EfficiencyManager(KodaDash plugin) {
        this.plugin = plugin;
        startTask();
    }

    public void reload() {
        stopTask();
        savedDistances.clear();
        throttled = false;
        idleSince = 0L;
        startTask();
    }

    private void startTask() {
        if (!isEnabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    check();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }, 200L, 1200L); // every minute
    }

    public void shutdown() {
        stopTask();
        if (throttled) restore();
    }

    private void stopTask() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) {}
            task = null;
        }
    }

    private void check() {
        int players = Bukkit.getOnlinePlayers().size();
        long now = System.currentTimeMillis();

        if (players > 0) {
            idleSince = 0L;
            if (throttled) restore();
            return;
        }

        if (idleSince == 0L) {
            idleSince = now;
            return;
        }
        if (!throttled && now - idleSince >= idleMinutes() * 60_000L) {
            throttle();
        }
    }

    /** Lower the distances in every world and remember the previous values. */
    private void throttle() {
        savedDistances.clear();
        int view = plugin.getConfig().getInt("efficiency.view-distance", 4);
        int simulation = plugin.getConfig().getInt("efficiency.simulation-distance", 4);
        for (World world : Bukkit.getWorlds()) {
            int currentView = readDistance(world, "getViewDistance", 10);
            int currentSimulation = readDistance(world, "getSimulationDistance", 10);
            savedDistances.put(world.getName(), new int[]{currentView, currentSimulation});
            applyDistance(world, view, simulation);
        }
        throttled = true;
        plugin.getLogger().info("Efficiency mode active: distances lowered while the server is empty.");
    }

    /** Put the original distances back. */
    private void restore() {
        for (World world : Bukkit.getWorlds()) {
            int[] previous = savedDistances.get(world.getName());
            if (previous == null) continue;
            applyDistance(world, previous[0], previous[1]);
        }
        savedDistances.clear();
        throttled = false;
        plugin.getLogger().info("Efficiency mode released: original distances restored.");
    }

    private int readDistance(World world, String method, int fallback) {
        try {
            Method m = world.getClass().getMethod(method);
            Object value = m.invoke(world);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
            // API not available on this server software
        }
        return fallback;
    }

    private void applyDistance(World world, int view, int simulation) {
        invokeInt(world, "setViewDistance", view);
        invokeInt(world, "setSimulationDistance", simulation);
    }

    private void invokeInt(World world, String method, int value) {
        try {
            Method m = world.getClass().getMethod(method, int.class);
            m.invoke(world, value);
        } catch (Throwable ignored) {
            // Paper-only method on a Spigot server: silently skipped
        }
    }

    // ------------------------------------------------------------------ configuration

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("efficiency.enabled", false);
    }

    public int idleMinutes() {
        return Math.max(1, plugin.getConfig().getInt("efficiency.idle-minutes", 10));
    }

    public boolean isThrottled() {
        return throttled;
    }

    public long getIdleSince() {
        return idleSince;
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("efficiency.enabled", enabled);
        plugin.saveConfig();
    }

    public void setOptions(int idleMinutes, int viewDistance, int simulationDistance) {
        plugin.getConfig().set("efficiency.idle-minutes", Math.max(1, idleMinutes));
        plugin.getConfig().set("efficiency.view-distance", Math.max(2, viewDistance));
        plugin.getConfig().set("efficiency.simulation-distance", Math.max(2, simulationDistance));
        plugin.saveConfig();
    }
}
