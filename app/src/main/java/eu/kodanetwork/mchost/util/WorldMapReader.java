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
package eu.kodanetwork.mchost.util;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * reads which chunks of a world already exist, straight out of the region files.
 *
 * the app sits on the same device as the server, so it can look at the world itself and
 * does not need a plugin for it. that makes the pre-generation picture work on every
 * loader, fabric and forge included.
 *
 * a region file starts with a 4 KB table, 1024 entries of four bytes, one per chunk in
 * that 32 by 32 region. the first three bytes hold the file offset of the chunk, all
 * zero means the chunk was never written.
 */
public final class WorldMapReader {

    private static final int REGION_CHUNKS = 32;      // chunks per side inside one region file

    private WorldMapReader() {
    }

    /**
     * a grid of cells around the world spawn: "0" for a chunk that does not exist yet,
     * "1".."5" for one that does. shading is derived from the chunk position so a
     * generated area does not look like one flat blob.
     *
     * @return the grid, or null when there is no world to read
     */
    public static String read(File serverDir, int radius, int cells) {
        if (serverDir == null || cells < 8 || radius <= 0) return null;
        String levelName = levelName(serverDir);
        File regionDir = new File(new File(serverDir, levelName), "region");
        if (!regionDir.isDirectory()) return null;

        Map<String, byte[]> headers = new HashMap<>();
        StringBuilder out = new StringBuilder(cells * cells);
        int step = Math.max(1, radius / (cells / 2));      // blocks per cell

        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                int bx = (cx - cells / 2) * step;
                int bz = (cz - cells / 2) * step;
                int chunkX = bx >> 4, chunkZ = bz >> 4;
                out.append(cellFor(regionDir, headers, chunkX, chunkZ));
            }
        }
        return out.toString();
    }

    /** one character for one chunk: "0" empty, else a shade. */
    private static char cellFor(File regionDir, Map<String, byte[]> headers, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5, regionZ = chunkZ >> 5;
        String key = regionX + "," + regionZ;

        byte[] header = headers.get(key);
        if (header == null) {
            header = readHeader(new File(regionDir, "r." + regionX + "." + regionZ + ".mca"));
            headers.put(key, header);
        }
        if (header == null) return '0';

        int index = ((chunkX & 31) + (chunkZ & 31) * REGION_CHUNKS) * 4;
        if (index + 3 >= header.length) return '0';
        int offset = ((header[index] & 0xFF) << 16) | ((header[index + 1] & 0xFF) << 8) | (header[index + 2] & 0xFF);
        if (offset == 0) return '0';

        // stable shade between 1 and 5 so real terrain looks like terrain
        int shade = Math.floorMod(chunkX * 73856093 ^ chunkZ * 19349663, 5) + 1;
        return (char) ('0' + shade);
    }

    /** the first 4 KB of a region file, null when it does not exist or is too short. */
    private static byte[] readHeader(File file) {
        if (!file.isFile()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            return header;
        } catch (Exception e) {
            return null;
        }
    }

    /** the world folder name, server.properties knows it. */
    public static String levelName(File serverDir) {
        File props = new File(serverDir, "server.properties");
        if (props.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(props))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("level-name=")) {
                        String name = line.substring(11).trim();
                        if (!name.isEmpty()) return name;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "world";
    }
}
