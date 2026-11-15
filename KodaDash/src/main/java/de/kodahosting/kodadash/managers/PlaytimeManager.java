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
package de.kodahosting.kodadash.managers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Total playtime per player, persisted next to the dashboard config.
 *
 * The live statistic only covers players that are online. This manager listens to join/quit,
 * keeps a session start per online player and flushes the elapsed seconds into playtime.json,
 * so the leaderboard also shows players that left hours ago.
 */
public class PlaytimeManager {
    private static final class Entry {
        String name;
        long seconds;
        long lastSeen;
        int sessions;
    }

    private final KodaDash plugin;
    private final File file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public PlaytimeManager(KodaDash plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playtime.json");
        load();
        startFlushTask();
    }

    private void startFlushTask() {
        long seconds = Math.max(60L, plugin.getConfig().getLong("playtime.flush-seconds", 300L));
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                flushOnline(false);
            }
        }, seconds * 20L, seconds * 20L);
    }

    public synchronized void onJoin(Player player) {
        String id = player.getUniqueId().toString();
        Entry entry = entries.get(id);
        if (entry == null) {
            entry = new Entry();
            entries.put(id, entry);
        }
        entry.name = player.getName();
        entry.sessions++;
        entry.lastSeen = System.currentTimeMillis();
        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
        save();
    }

    public synchronized void onQuit(Player player) {
        addSession(player.getUniqueId(), System.currentTimeMillis());
        sessionStart.remove(player.getUniqueId());
        save();
    }

    /** adds the current session and starts a new one; used by the periodic flush too. */
    private void addSession(UUID uuid, long now) {
        Long start = sessionStart.get(uuid);
        if (start == null) return;
        long seconds = Math.max(0L, (now - start) / 1000L);
        if (seconds > 0) {
            Entry entry = entries.get(uuid.toString());
            if (entry != null) {
                entry.seconds += seconds;
                entry.lastSeen = now;
            }
        }
        sessionStart.put(uuid, now);
    }

    private synchronized void flushOnline(boolean removeSessions) {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (removeSessions) {
                addSession(player.getUniqueId(), now);
                sessionStart.remove(player.getUniqueId());
            } else {
                addSession(player.getUniqueId(), now);
            }
        }
        if (!removeSessions) save();
    }

    public synchronized JsonArray leaderboard() {
        flushOnline(false);
        List<Map.Entry<String, Entry>> list = new ArrayList<>(entries.entrySet());
        list.sort(new Comparator<Map.Entry<String, Entry>>() {
            @Override
            public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                return Long.compare(b.getValue().seconds, a.getValue().seconds);
            }
        });
        JsonArray out = new JsonArray();
        int count = 0;
        for (Map.Entry<String, Entry> mapEntry : list) {
            Entry entry = mapEntry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("uuid", mapEntry.getKey());
            row.addProperty("name", entry.name == null ? "?" : entry.name);
            row.addProperty("seconds", entry.seconds);
            row.addProperty("sessions", entry.sessions);
            row.addProperty("lastSeen", entry.lastSeen);
            out.add(row);
            if (++count >= 100) break;
        }
        return out;
    }

    public synchronized void shutdown() {
        if (flushTask != null) {
            try { flushTask.cancel(); } catch (Exception ignored) {}
            flushTask = null;
        }
        flushOnline(true);
        save();
    }

    private synchronized void load() {
        if (!file.isFile()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) return;
            for (Map.Entry<String, JsonElement> mapEntry : players.entrySet()) {
                JsonObject row = mapEntry.getValue().getAsJsonObject();
                Entry entry = new Entry();
                entry.name = row.has("name") ? row.get("name").getAsString() : "?";
                entry.seconds = row.has("seconds") ? row.get("seconds").getAsLong() : 0L;
                entry.sessions = row.has("sessions") ? row.get("sessions").getAsInt() : 0;
                entry.lastSeen = row.has("lastSeen") ? row.get("lastSeen").getAsLong() : 0L;
                entries.put(mapEntry.getKey(), entry);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read playtime.json: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            JsonObject players = new JsonObject();
            for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
                Entry entry = mapEntry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("name", entry.name);
                row.addProperty("seconds", entry.seconds);
                row.addProperty("sessions", entry.sessions);
                row.addProperty("lastSeen", entry.lastSeen);
                players.add(mapEntry.getKey(), row);
            }
            JsonObject root = new JsonObject();
            root.add("players", players);
            root.addProperty("updatedAt", System.currentTimeMillis());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(root.toString());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not write playtime.json: " + e.getMessage());
        }
    }
}
