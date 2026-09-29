package de.kodahosting.kodadash.managers;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scheduled server automation: restarts, stops, commands, announcements and saves.
 *
 * Entries live in {@code plugins/KodaDash/schedule.json} and are executed by a task that runs
 * once per second. Restarts and stops are handed to the {@link de.kodahosting.kodadash.routes.ServerActionRoute}
 * countdown, so players always get a warning; commands go through the console manager and
 * therefore respect the blocked-command list.
 *
 * Two modes:
 *   daily    - runs at a fixed local time ("03:30")
 *   interval - runs every N minutes, counted from the last run
 *
 * The "only when empty" flag skips a run while players are online (useful for maintenance
 * commands and for restarts that should not interrupt anybody).
 */
public class ScheduleManager {
    private final KodaDash plugin;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final File file;
    private BukkitTask tickTask;
    /** True when the file came from the app (then the app executes, not this plugin). */
    private boolean mirrorFromApp = false;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    /** One scheduled job. */
    public static class Entry {
        public String id = UUID.randomUUID().toString().substring(0, 8);
        public String type = "announce";          // restart | stop | announce | command | save
        public String value = "";                 // message or command (ignored for restart/stop/save)
        public String mode = "interval";          // daily | interval
        public String time = "04:00";             // used when mode == daily
        public int intervalMinutes = 180;         // used when mode == interval
        public boolean onlyWhenEmpty = false;
        public boolean enabled = true;
        public long lastRun = 0L;
        public long nextRun = 0L;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("type", type);
            json.addProperty("value", value);
            json.addProperty("mode", mode);
            json.addProperty("time", time);
            json.addProperty("intervalMinutes", intervalMinutes);
            json.addProperty("onlyWhenEmpty", onlyWhenEmpty);
            json.addProperty("enabled", enabled);
            json.addProperty("lastRun", lastRun);
            json.addProperty("nextRun", nextRun);
            return json;
        }

        public static Entry fromJson(JsonObject json) {
            Entry entry = new Entry();
            if (json.has("id")) entry.id = json.get("id").getAsString();
            if (json.has("type")) entry.type = json.get("type").getAsString();
            if (json.has("value")) entry.value = json.get("value").getAsString();
            if (json.has("mode")) entry.mode = json.get("mode").getAsString();
            if (json.has("time")) entry.time = json.get("time").getAsString();
            if (json.has("intervalMinutes")) entry.intervalMinutes = json.get("intervalMinutes").getAsInt();
            if (json.has("onlyWhenEmpty")) entry.onlyWhenEmpty = json.get("onlyWhenEmpty").getAsBoolean();
            if (json.has("enabled")) entry.enabled = json.get("enabled").getAsBoolean();
            if (json.has("lastRun")) entry.lastRun = json.get("lastRun").getAsLong();
            if (json.has("nextRun")) entry.nextRun = json.get("nextRun").getAsLong();
            return entry;
        }
    }

    public ScheduleManager(KodaDash plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "schedule.json");
        load();
        startTicking();
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Reads schedule.json. Two shapes are accepted: the plain array this manager writes and the
     * object the KodaHosting app writes ({"executor":"app","jobs":[...]}), because the app is the
     * normal executor and only mirrors its list here for display.
     */
    private void load() {
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root == null) return;
            JsonArray array;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
                mirrorFromApp = false;
            } else if (root.isJsonObject() && root.getAsJsonObject().has("jobs")) {
                JsonObject object = root.getAsJsonObject();
                array = object.getAsJsonArray("jobs");
                mirrorFromApp = "app".equals(object.has("executor") ? object.get("executor").getAsString() : "");
            } else {
                return;
            }
            entries.clear();
            for (JsonElement element : array) {
                if (element.isJsonObject()) entries.add(Entry.fromJson(element.getAsJsonObject()));
            }
            plugin.getLogger().info("Loaded " + entries.size() + " scheduled job(s)"
                    + (mirrorFromApp ? " (managed by the app)" : "") + ".");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read schedule.json: " + e.getMessage());
        }
    }

    private void save() {
        if (mirrorFromApp) return; // the app owns this file
        JsonArray array = new JsonArray();
        for (Entry entry : entries) array.add(entry.toJson());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(array.toString());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write schedule.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ API for the route

    /**
     * True when the list shown here was written by the KodaHosting app. Re-reads the file first,
     * because the app changes it while the server runs.
     */
    public boolean isManagedByApp() {
        reloadFromDisk();
        return mirrorFromApp;
    }

    /** Re-reads the file when the app changed it (called by the route). */
    public boolean reloadFromDisk() {
        if (!file.exists()) return mirrorFromApp;
        long modified = file.lastModified();
        if (modified <= lastLoad) return mirrorFromApp;
        lastLoad = modified;
        load();
        return mirrorFromApp;
    }

    private long lastLoad = 0L;

    public List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    public Entry find(String id) {
        for (Entry entry : entries) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    /** Adds or replaces an entry (matched by id) and returns it. */
    public Entry upsert(Entry incoming) {
        if (incoming.id == null || incoming.id.isEmpty()) {
            incoming.id = UUID.randomUUID().toString().substring(0, 8);
        }
        Entry existing = find(incoming.id);
        if (existing != null) {
            existing.type = incoming.type;
            existing.value = incoming.value;
            existing.mode = incoming.mode;
            existing.time = incoming.time;
            existing.intervalMinutes = incoming.intervalMinutes;
            existing.onlyWhenEmpty = incoming.onlyWhenEmpty;
            existing.enabled = incoming.enabled;
        } else {
            entries.add(incoming);
            existing = incoming;
        }
        existing.nextRun = computeNextRun(existing);
        save();
        return existing;
    }

    public boolean remove(String id) {
        Entry entry = find(id);
        if (entry == null) return false;
        entries.remove(entry);
        save();
        return true;
    }

    /** Runs an entry right away (used by the "run now" button). */
    public String runNow(String id) {
        Entry entry = find(id);
        if (entry == null) return "Unknown job";
        return execute(entry, true);
    }

    // ------------------------------------------------------------------ scheduling

    private void startTicking() {
        if (!plugin.getConfig().getBoolean("schedule.enabled", true)) return;
        // The KodaHosting app executes the jobs; the dashboard only shows them. Set
        // schedule.execute to true when the dashboard should run them itself instead.
        if (!plugin.getConfig().getBoolean("schedule.execute", false)) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Exception e) {
                    plugin.getLogger().warning("Schedule tick failed: " + e.getMessage());
                }
            }
        }, 40L, 20L);
    }

    public void shutdown() {
        if (tickTask != null) {
            try { tickTask.cancel(); } catch (Exception ignored) {}
            tickTask = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int players = Bukkit.getOnlinePlayers().size();
        int warnSeconds = Math.max(60, plugin.getConfig().getInt("schedule.warn-seconds", 300));

        for (Entry entry : entries) {
            if (!entry.enabled) continue;
            if (entry.nextRun <= 0L) {
                entry.nextRun = computeNextRun(entry);
                save();
                continue;
            }
            if (now < entry.nextRun) {
                // Announce the upcoming restart/stop once, five minutes and one minute ahead
                if (("restart".equals(entry.type) || "stop".equals(entry.type)) && players > 0) {
                    long secondsLeft = (entry.nextRun - now) / 1000L;
                    if (secondsLeft <= warnSeconds && secondsLeft > warnSeconds - 60
                            && !Boolean.TRUE.equals(warned5.get(entry.id))) {
                        warned5.put(entry.id, Boolean.TRUE);
                        Bukkit.broadcastMessage("\u00a7c[KodaDash] \u00a7eScheduled " + label(entry.type)
                                + " in " + (warnSeconds / 60) + " minutes!");
                    }
                    if (secondsLeft <= 60L && !Boolean.TRUE.equals(warned1.get(entry.id))) {
                        warned1.put(entry.id, Boolean.TRUE);
                        Bukkit.broadcastMessage("\u00a7c[KodaDash] \u00a7eScheduled " + label(entry.type)
                                + " in 1 minute!");
                    }
                }
                continue;
            }

            if (entry.onlyWhenEmpty && players > 0) {
                // Postpone instead of interrupting players
                entry.nextRun = now + 60_000L;
                save();
                continue;
            }

            String result = execute(entry, false);
            entry.lastRun = now;
            entry.nextRun = computeNextRun(entry);
            warned5.remove(entry.id);
            warned1.remove(entry.id);
            save();
            plugin.getLogger().info("Scheduled job " + entry.id + " (" + entry.type + "): " + result);
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> warned5 =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> warned1 =
            new java.util.concurrent.ConcurrentHashMap<>();

    private String label(String type) {
        return "stop".equals(type) ? "shutdown" : type;
    }

    /** Next execution timestamp for an entry. */
    public long computeNextRun(Entry entry) {
        long now = System.currentTimeMillis();
        if ("daily".equals(entry.mode)) {
            try {
                Calendar target = Calendar.getInstance();
                String[] parts = entry.time.split(":");
                target.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0].trim()));
                target.set(Calendar.MINUTE, Integer.parseInt(parts[1].trim()));
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);
                if (target.getTimeInMillis() <= now) {
                    target.add(Calendar.DAY_OF_MONTH, 1);
                }
                return target.getTimeInMillis();
            } catch (Exception e) {
                return now + 60 * 60 * 1000L;
            }
        }
        int minutes = Math.max(1, entry.intervalMinutes);
        long base = entry.lastRun > 0 ? entry.lastRun : now;
        return base + minutes * 60_000L;
    }

    /**
     * Human readable next run time for the UI. The zone is included on purpose: daily jobs use
     * the server's time zone, which is not necessarily the one the owner is sitting in.
     */
    public String describeNextRun(Entry entry) {
        if (entry.nextRun <= 0L) return "";
        SimpleDateFormat format = new SimpleDateFormat("EEE HH:mm z");
        return format.format(new java.util.Date(entry.nextRun));
    }

    // ------------------------------------------------------------------ execution

    private String execute(Entry entry, boolean manual) {
        String type = entry.type == null ? "" : entry.type.toLowerCase();
        switch (type) {
            case "restart":
            case "stop": {
                int seconds = plugin.getConfig().getInt("schedule.restart-countdown-seconds", 15);
                boolean queued = plugin.getDashServer() != null
                        && plugin.getDashServer().getServerActionRoute() != null
                        && plugin.getDashServer().getServerActionRoute().request(type, seconds);
                return queued ? type + " queued with a " + seconds + " second countdown" : "a server action is already pending";
            }
            case "command": {
                if (entry.value == null || entry.value.trim().isEmpty()) return "no command configured";
                boolean ok = plugin.getConsoleManager().executeCommand(entry.value);
                return ok ? "command dispatched" : "command blocked";
            }
            case "save": {
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                    }
                });
                return "world saved";
            }
            case "announce":
            default: {
                if (entry.value == null || entry.value.trim().isEmpty()) return "no message configured";
                final String message = "\u00a7e[Server] \u00a7f"
                        + org.bukkit.ChatColor.translateAlternateColorCodes('&', entry.value);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.broadcastMessage(message);
                    }
                });
                return "announced";
            }
        }
    }

    /** Short text for the dashboard list. */
    public String describe(Entry entry) {
        StringBuilder builder = new StringBuilder();
        if ("daily".equals(entry.mode)) {
            builder.append("daily at ").append(entry.time);
        } else {
            builder.append("every ").append(entry.intervalMinutes).append(" min");
        }
        if (entry.onlyWhenEmpty) builder.append(", only when empty");
        return builder.toString();
    }
}
