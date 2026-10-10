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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class NbtParser {

    public static class NbtTag {
        public byte type;
        public String name;
        public Object value;

        public NbtTag(byte type, String name, Object value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }
    }

    public static Map<String, Object> parsePlayerDat(File file) {
        // vanilla writes gzipped NBT, so the raw file is only a fallback
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            return readRoot(in);
        } catch (Exception gzipFailure) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                return readRoot(in);
            } catch (Exception plainFailure) {
                eu.kodanetwork.mchost.util.AppLogger.log("KodaHosting",
                        "[PlayerManager] playerdata parse failed for " + file.getName()
                                + ": " + gzipFailure.getMessage() + " / " + plainFailure.getMessage());
                return null;
            }
        }
    }

    private static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        byte rootType = in.readByte();
        if (rootType == 0) return null;
        readString(in); // the root name, usually empty
        return (Map<String, Object>) readTagValue(in, rootType);
    }

    private static Object readTagValue(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1: return in.readByte(); // Byte
            case 2: return in.readShort(); // Short
            case 3: return in.readInt(); // Int
            case 4: return in.readLong(); // Long
            case 5: return in.readFloat(); // Float
            case 6: return in.readDouble(); // Double
            case 7: { // ByteArray
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return bytes;
            }
            case 8: return readString(in); // String
            case 9: { // List
                byte listType = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readTagValue(in, listType));
                }
                return list; // the listType is dropped here, the callers know what to expect
            }
            case 10: { // Compound
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == 0) break; // End
                    String name = readString(in);
                    map.put(name, readTagValue(in, childType));
                }
                return map;
            }
            case 11: { // IntArray
                int len = in.readInt();
                int[] ints = new int[len];
                for (int i = 0; i < len; i++) ints[i] = in.readInt();
                return ints;
            }
            case 12: { // LongArray
                int len = in.readInt();
                long[] longs = new long[len];
                for (int i = 0; i < len; i++) longs[i] = in.readLong();
                return longs;
            }
            default:
                throw new IOException("Unknown NBT type: " + type);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
}
