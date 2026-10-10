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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * read-only pretty printer for minecraft NBT data (level.dat, level.dat_old,
 * playerdata/*.dat, entities and friends). the format is a gzipped tree of
 * typed tags, region files (.mca) are a different container and not NBT at the
 * top level, the root-tag check throws them out.
 *
 * pure java.io, no dependency. the output is capped so a hostile file cannot
 * blow the editor up.
 */
public final class NbtReader {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_OUTPUT = 400_000;

    private NbtReader() {
    }

    public static boolean isNbtFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".dat") || n.endsWith(".dat_old") || n.endsWith(".nbt");
    }

    /** parses the file (gzip or raw NBT) and returns an indented text tree. */
    public static String prettyPrint(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        ByteArrayInputStream bytes = new ByteArrayInputStream(data);
        InputStream stream;
        try {
            stream = new java.util.zip.GZIPInputStream(bytes);
        } catch (IOException notGzip) {
            bytes.reset();
            stream = bytes;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 64 * 1024))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("not an NBT compound (root tag " + rootType + ")");
            }
            StringBuilder out = new StringBuilder();
            String rootName = in.readUTF();
            readCompound(in, out, rootName, 0);
            return out.toString();
        }
    }

    private static void readCompound(DataInputStream in, StringBuilder out, String name, int depth)
            throws IOException {
        if (depth > MAX_DEPTH || out.length() > MAX_OUTPUT) return;
        indent(out, depth).append(name == null || name.isEmpty() ? "(root)" : name).append(":\n");
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return; // TAG_End
            if (out.length() > MAX_OUTPUT) {
                indent(out, depth + 1).append("... (output capped)\n");
                return;
            }
            String childName = in.readUTF();
            readPayload(in, out, childName, type, depth + 1);
        }
    }

    private static void readPayload(DataInputStream in, StringBuilder out, String name,
                                     int type, int depth) throws IOException {
        switch (type) {
            case 1: // Byte
                indent(out, depth).append(name).append(" = ").append(in.readByte()).append('\n');
                break;
            case 2: // Short
                indent(out, depth).append(name).append(" = ").append(in.readShort()).append('\n');
                break;
            case 3: // Int
                indent(out, depth).append(name).append(" = ").append(in.readInt()).append('\n');
                break;
            case 4: // Long
                indent(out, depth).append(name).append(" = ").append(in.readLong()).append('L').append('\n');
                break;
            case 5: // Float
                indent(out, depth).append(name).append(" = ").append(in.readFloat()).append('f').append('\n');
                break;
            case 6: // Double
                indent(out, depth).append(name).append(" = ").append(in.readDouble()).append('\n');
                break;
            case 7: { // byte array, the length first and then a small preview
                int len = in.readInt();
                byte[] buf = new byte[len < 0 ? 0 : Math.min(len, 16)];
                in.readFully(buf);
                in.skipBytes(len - buf.length);
                indent(out, depth).append(name).append(" = byte[").append(len).append(']');
                appendPreview(out, buf);
                out.append('\n');
                break;
            }
            case 8: // String
                indent(out, depth).append(name).append(" = \"").append(in.readUTF()).append("\"\n");
                break;
            case 9: { // List
                int itemType = in.readUnsignedByte();
                int len = in.readInt();
                indent(out, depth).append(name).append(" = list[").append(len).append("]\n");
                for (int i = 0; i < len; i++) {
                    if (out.length() > MAX_OUTPUT || i >= 64) {
                        indent(out, depth + 1).append("... (").append(len - i).append(" more)\n");
                        // the rest of the list still has to be parsed, just not printed
                        for (int r = i; r < len; r++) readPayload(in, new StringBuilder(0), "", itemType, 0);
                        return;
                    }
                    readPayload(in, out, "#"+(i+1), itemType, depth + 1);
                }
                break;
            }
            case 10: // Compound
                readCompound(in, out, name, depth);
                break;
            case 11: { // Int array
                int len = in.readInt();
                indent(out, depth).append(name).append(" = int[").append(len).append(']');
                for (int i = 0; i < Math.min(len, 8); i++) {
                    out.append(i == 0 ? " " : ", ").append(in.readInt());
                }
                for (int i = 8; i < len; i++) in.readInt();
                if (len > 8) out.append(", ...");
                out.append('\n');
                break;
            }
            case 12: { // Long array
                int len = in.readInt();
                indent(out, depth).append(name).append(" = long[").append(len).append(']');
                for (int i = 0; i < Math.min(len, 8); i++) {
                    out.append(i == 0 ? " " : ", ").append(in.readLong());
                }
                for (int i = 8; i < len; i++) in.readLong();
                if (len > 8) out.append(", ...");
                out.append('\n');
                break;
            }
            default:
                throw new IOException("unknown tag " + type);
        }
    }

    private static void appendPreview(StringBuilder out, byte[] buf) {
        if (buf.length == 0) return;
        out.append(" { ");
        for (int i = 0; i < buf.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02x", buf[i]));
        }
        out.append(" }");
    }

    private static StringBuilder indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) out.append("  ");
        return out;
    }
}
