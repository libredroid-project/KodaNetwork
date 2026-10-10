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
package eu.kodanetwork.transfer.neoforge;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * the neoforge twin of the KodaTransfer bukkit plugin, for the modded servers where no
 * bukkit plugin can run. the sampler and the map file format are identical to the bukkit
 * and fabric builds, only the glue around them differs.
 *
 * jobs: keep accepts-transfers=true, answer /khub, and write the world map the app draws
 * while chunky pre-generates.
 */
@Mod("koda_transfer")
public class KodaTransferNeoForge {

    private static final String LOBBY_HOST = "lobby.kodanetwork.eu";
    private static final int LOBBY_PORT = 25565;

    // sampler state, one pass at a time
    private char[] mapCells;
    private int mapSize, mapRadius, mapCursor;
    private File mapDir;
    private File mapOut;
    private boolean mapRunning;

    public KodaTransferNeoForge() {
        ensureTransfersEnabled();
    }

    /** once the server is up, make sure the map folder exists. */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        File serverDir = FMLPaths.GAMEDIR.get().toFile();
        mapDir = new File(serverDir, "koda_transfer/map");
        mapDir.mkdirs();
        mapOut = new File(mapDir, "map.json");
    }

    /** /khub sends the player back to the lobby, the transfer packet exists since 1.20.5. */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("khub").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            try {
                player.connection.send(new ClientboundTransferPacket(LOBBY_HOST, LOBBY_PORT));
            } catch (Throwable t) {
                player.sendSystemMessage(Component.literal("Could not reach the lobby: " + t.getMessage()));
            }
            return 1;
        }));
    }

    /** sampling happens in slices at the end of a tick, so the world never waits. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (mapDir == null) {
                mapDir = new File(server.getServerDirectory().toFile(), "koda_transfer/map");
                mapDir.mkdirs();
                mapOut = new File(mapDir, "map.json");
            }
            File request = new File(mapDir, "map_request.json");
            if (!request.isFile()) {
                mapRunning = false;
                return;
            }
            String json = readFile(request);
            if (!json.contains("\"enabled\":true")) {
                mapRunning = false;
                return;
            }
            int radius = (int) numberFrom(json, "radius", 1000);
            int size = (int) numberFrom(json, "cells", 96);
            if (radius <= 0 || size < 8) return;

            ServerLevel level = server.overworld();
            if (level == null) return;

            if (!mapRunning || mapSize != size || mapRadius != radius) {
                mapSize = size;
                mapRadius = radius;
                mapCells = new char[size * size];
                Arrays.fill(mapCells, '0');
                mapCursor = 0;
                mapRunning = true;
            }

            int step = Math.max(1, mapRadius / (mapSize / 2));
            int slice = 384;
            BlockPos spawn = level.getSharedSpawnPos();
            File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();

            for (int i = 0; i < slice && mapCursor < mapCells.length; i++, mapCursor++) {
                int cx = mapCursor % mapSize, cz = mapCursor / mapSize;
                int bx = spawn.getX() + (cx - mapSize / 2) * step;
                int bz = spawn.getZ() + (cz - mapSize / 2) * step;
                mapCells[mapCursor] = cellFor(level, worldDir, bx, bz);
            }

            if (mapCursor >= mapCells.length) {
                writeMap(level.dimension().location().getPath(), spawn);
                mapCursor = 0;
            }
        } catch (Throwable t) {
            // a broken sampler must never take the server down
        }
    }

    /** "0" when the chunk was never written, else the biome family as a digit. */
    private char cellFor(ServerLevel level, File worldDir, int bx, int bz) {
        if (!chunkExists(worldDir, bx >> 4, bz >> 4)) return '0';
        return paletteIndex(biomeName(level, bx, bz));
    }

    /** the region header, it works on every minecraft version and never loads a chunk. */
    private boolean chunkExists(File worldDir, int chunkX, int chunkZ) {
        try {
            File region = new File(new File(worldDir, "region"),
                    "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
            if (!region.isFile()) return false;
            int index = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
            byte[] header = new byte[4];
            try (RandomAccessFile raf = new RandomAccessFile(region, "r")) {
                raf.seek(index);
                raf.readFully(header);
            }
            return ((header[0] & 0xFF) | (header[1] & 0xFF) | (header[2] & 0xFF)) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** the biome name minecraft uses, upper case for the palette below. */
    private String biomeName(ServerLevel level, int bx, int bz) {
        try {
            Biome biome = level.getBiome(new BlockPos(bx, 64, bz)).value();
            ResourceKey<Biome> key = level.registryAccess().registryOrThrow(Registries.BIOME)
                    .getResourceKey(biome).orElse(null);
            return key == null ? "" : key.location().getPath().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    /** the same nine families the app paints. keep in step with WorldGenView. */
    private char paletteIndex(String biome) {
        if (biome.isEmpty()) return '1';
        if (biome.contains("OCEAN") || biome.contains("RIVER")) return '1';
        if (biome.contains("DESERT") || biome.contains("BADLANDS") || biome.contains("SAVANNA")) return '4';
        if (biome.contains("BEACH") || biome.contains("STONE") || biome.contains("PEAK")
                || biome.contains("MOUNTAIN") || biome.contains("WINDSWEPT") || biome.contains("HILLS")) return '6';
        if (biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("ICE")) return '5';
        if (biome.contains("SWAMP") || biome.contains("MANGROVE") || biome.contains("JUNGLE")) return '7';
        if (biome.contains("FOREST") || biome.contains("TAIGA") || biome.contains("GROVE")) return '3';
        if (biome.contains("PLAINS") || biome.contains("MEADOW") || biome.contains("FIELD")) return '2';
        return '9';
    }

    private void writeMap(String worldName, BlockPos spawn) {
        try (FileWriter w = new FileWriter(mapOut)) {
            w.write("{\"size\":" + mapSize + ",\"radius\":" + mapRadius
                    + ",\"centerX\":" + spawn.getX() + ",\"centerZ\":" + spawn.getZ()
                    + ",\"world\":\"" + worldName + "\",\"cells\":\"" + new String(mapCells) + "\"}");
        } catch (Exception ignored) {
        }
    }

    /** the app writes that value as well, this is the safety net for older servers. */
    private void ensureTransfersEnabled() {
        try {
            File props = new File(".", "server.properties");
            if (!props.isFile()) return;
            List<String> lines = new ArrayList<>();
            boolean found = false;
            try (BufferedReader r = new BufferedReader(new FileReader(props))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("accepts-transfers=")) {
                        lines.add("accepts-transfers=true");
                        found = true;
                    } else {
                        lines.add(line);
                    }
                }
            }
            if (!found) lines.add("accepts-transfers=true");
            try (FileWriter w = new FileWriter(props)) {
                for (String line : lines) w.write(line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private String readFile(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {
        }
        return sb.toString();
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
}
