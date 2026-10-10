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
package eu.kodanetwork.transfer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * turns the paper transfer API on for KodaHosting servers.
 *
 * two jobs:
 *   - keeps {@code accepts-transfers=true} in server.properties, that is what lets the lobby
 *     network send players over here,
 *   - provides {@code /khub}, which sends a player back to the KodaHosting lobby.
 *
 * the lobby address is configurable in config.yml (lobby-host, lobby-port).
 */
public class KodaTransferPlugin extends JavaPlugin implements CommandExecutor {

    private String lobbyHost = "lobby.kodanetwork.eu";
    private int lobbyPort = 25565;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lobbyHost = getConfig().getString("lobby-host", lobbyHost);
        lobbyPort = getConfig().getInt("lobby-port", lobbyPort);

        PluginCommand command = getCommand("khub");
        if (command != null) {
            command.setExecutor(this);
        }

        ensureTransfersEnabled();
        startMapSampler();
        getLogger().info("KodaTransfer enabled (lobby: " + lobbyHost + ":" + lobbyPort + ")");
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        final Player player = (Player) sender;
        player.sendMessage(ChatColor.GREEN + "Sending you to the lobby...");
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override public void run() {
                transfer(player);
            }
        });
        return true;
    }

    /**
     * sends the player to the lobby. the transfer api only exists from 1.20.5 on, so it is
     * called through reflection and older servers get a plain message instead of an error.
     */
    private void transfer(Player player) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("transfer", String.class, int.class);
            m.invoke(player, lobbyHost, lobbyPort);
            return;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            player.sendMessage(ChatColor.RED + "Could not reach the lobby: " + cause.getMessage());
            getLogger().warning("Transfer failed for " + player.getName() + ": " + cause.getMessage());
            return;
        } catch (Throwable ignored) {
            // no transfer method, so this server is older than 1.20.5
        }
        player.sendMessage(ChatColor.YELLOW + "This server is too old for the transfer API, join "
                + lobbyHost + ":" + lobbyPort + " by hand.");
    }

    /**
     * writes a small picture of the world while the app asks for one.
     *
     * the app drops a request into plugins/KodaTransfer/map/map_request.json, this reads it
     * and answers with map.json: a grid of cells, one character per cell, "0" for a chunk
     * that does not exist yet and 1..9 for the biome family that was found there. that is
     * exactly the picture the app draws while chunky is pre-generating.
     *
     * sampling runs in slices so a 96 by 96 grid never costs a whole tick.
     */
    private void startMapSampler() {
        Bukkit.getScheduler().runTaskTimer(this, this::sampleMapSlice, 40L, 5L);
    }

    private static final int PALETTE_OTHER = 9;

    private char[] mapCells;
    private int mapSize, mapRadius;
    private int mapCursor;
    private File mapDir;
    private File mapOut;
    private boolean mapRunning;

    private void sampleMapSlice() {
        try {
            File dir = getDataFolder();
            if (mapDir == null) {
                mapDir = new File(dir, "map");
                mapDir.mkdirs();
                mapOut = new File(mapDir, "map.json");
            }
            File request = new File(mapDir, "map_request.json");

            if (!request.isFile()) {
                mapRunning = false;
                return;
            }
            // tiny hand written parse, the file has exactly three numbers in it
            String json = readFile(request);
            int radius = (int) numberFrom(json, "radius", 1000);
            int size = (int) numberFrom(json, "cells", 96);
            boolean enabled = json.contains("\"enabled\":true");
            if (!enabled || radius <= 0 || size < 8) {
                mapRunning = false;
                return;
            }

            World world = mapWorld();
            if (world == null) return;

            if (!mapRunning || mapSize != size || mapRadius != radius) {
                mapSize = size;
                mapRadius = radius;
                mapCells = new char[size * size];
                java.util.Arrays.fill(mapCells, '0');
                mapCursor = 0;
                mapRunning = true;
            }

            int step = Math.max(1, mapRadius / (mapSize / 2));       // blocks per cell
            int slice = 512;                                         // cells per tick
            int centreX = world.getSpawnLocation().getBlockX();
            int centreZ = world.getSpawnLocation().getBlockZ();

            for (int i = 0; i < slice && mapCursor < mapCells.length; i++, mapCursor++) {
                int cx = mapCursor % mapSize, cz = mapCursor / mapSize;
                int bx = centreX + (cx - mapSize / 2) * step;
                int bz = centreZ + (cz - mapSize / 2) * step;
                mapCells[mapCursor] = cellFor(world, bx, bz);
            }

            if (mapCursor >= mapCells.length) {
                writeMap(world);
                mapCursor = 0;                                       // next pass updates it again
            }
        } catch (Throwable t) {
            getLogger().warning("map sampler: " + t.getMessage());
        }
    }

    /** the overworld, or whatever the server calls its first world. */
    private World mapWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    /**
     * "0" when the chunk is not there, else the biome family as a single digit.
     *
     * the chunk check reads the region file itself instead of asking the server, because
     * World#isChunkGenerated only exists from 1.13 on and this plugin runs from 1.8.
     */
    private char cellFor(World world, int bx, int bz) {
        if (!chunkExists(world, bx >> 4, bz >> 4)) return '0';
        Biome biome = biomeAt(world, bx, bz);
        return biome == null ? '1' : paletteIndex(biome);
    }

    /** true when that chunk was ever written, read from the region header. */
    private boolean chunkExists(World world, int chunkX, int chunkZ) {
        try {
            File regionDir = new File(world.getWorldFolder(), "region");
            File region = new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
            if (!region.isFile()) return false;
            int index = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
            byte[] header = new byte[4];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(region, "r")) {
                raf.seek(index);
                raf.readFully(header);
            }
            return ((header[0] & 0xFF) | (header[1] & 0xFF) | (header[2] & 0xFF)) != 0;
        } catch (Throwable t) {
            return true;   // cannot tell, better to paint it than to leave a hole
        }
    }

    /** the biome there, through reflection because the getter changed its shape over the years. */
    private Biome biomeAt(World world, int bx, int bz) {
        try {
            java.lang.reflect.Method withY = world.getClass().getMethod("getBiome", int.class, int.class, int.class);
            return (Biome) withY.invoke(world, bx, 64, bz);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method flat = world.getClass().getMethod("getBiome", int.class, int.class);
            return (Biome) flat.invoke(world, bx, bz);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * biome to one character, the same nine families the app paints. keep the two lists
     * in step, the app mirrors these numbers in WorldGenView.
     */
    private char paletteIndex(Biome biome) {
        String n = biome.name().toUpperCase(java.util.Locale.ROOT);
        if (n.contains("OCEAN") || n.contains("RIVER")) return '1';
        if (n.contains("DESERT") || n.contains("BADLANDS") || n.contains("MESA")) return '4';
        if (n.contains("SAVANNA")) return '4';
        if (n.contains("BEACH") || n.contains("STONE") || n.contains("PEAK") || n.contains("MOUNTAIN")) return '6';
        if (n.contains("MOUNTAIN") || n.contains("WINDSWEPT") || n.contains("HILLS")) return '6';
        if (n.contains("SNOW") || n.contains("FROZEN") || n.contains("ICE")) return '5';
        if (n.contains("SWAMP") || n.contains("MANGROVE") || n.contains("JUNGLE")) return '7';
        if (n.contains("FOREST") || n.contains("TAIGA") || n.contains("GROVE")) return '3';
        if (n.contains("PLAINS") || n.contains("MEADOW") || n.contains("FIELD")) return '2';
        return (char) ('0' + PALETTE_OTHER);
    }

    private void writeMap(World world) {
        try (FileWriter w = new FileWriter(mapOut)) {
            w.write("{\"size\":" + mapSize + ",\"radius\":" + mapRadius
                    + ",\"centerX\":" + world.getSpawnLocation().getBlockX()
                    + ",\"centerZ\":" + world.getSpawnLocation().getBlockZ()
                    + ",\"world\":\"" + world.getName() + "\",\"cells\":\""
                    + new String(mapCells) + "\"}");
        } catch (Exception e) {
            getLogger().warning("map write failed: " + e.getMessage());
        }
    }

    private double numberFrom(String json, String key, double fallback) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return fallback;
        int colon = json.indexOf(':', i);
        if (colon < 0) return fallback;
        int end = colon + 1;
        while (end < json.length() && "-0123456789.".indexOf(json.charAt(end)) >= 0) end++;
        try {
            return Double.parseDouble(json.substring(colon + 1, end));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String readFile(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /**
     * forces {@code accepts-transfers=true} into server.properties. the app writes that value
     * too, so this is the safety net for servers set up before the flag existed.
     */
    private void ensureTransfersEnabled() {
        File file = new File(".", "server.properties");
        if (!file.exists()) return;
        List<String> lines = new ArrayList<>();
        boolean found = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("accepts-transfers=")) {
                    lines.add("accepts-transfers=true");
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            getLogger().warning("Could not read server.properties: " + e.getMessage());
            return;
        }
        if (!found) {
            lines.add("accepts-transfers=true");
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (String line : lines) {
                writer.println(line);
            }
        } catch (IOException e) {
            getLogger().warning("Could not write server.properties: " + e.getMessage());
            return;
        }
        getLogger().info("accepts-transfers=true is set.");
    }
}
