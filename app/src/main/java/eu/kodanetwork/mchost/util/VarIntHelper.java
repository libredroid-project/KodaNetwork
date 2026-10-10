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
import java.io.DataOutputStream;

public class VarIntHelper {
    public static void writeVarInt(DataOutputStream out, int v) throws Exception {
        while (true) {
            if ((v & 0xFFFFFF80) == 0) { out.writeByte(v); return; }
            out.writeByte(v & 0x7F | 0x80);
            v >>>= 7;
        }
    }

    public static void writeString(DataOutputStream out, String s) throws Exception {
        byte[] bytes = s.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static int readVarInt(DataInputStream in) throws Exception {
        int i = 0, j = 0;
        while (true) {
            int k = in.readByte();
            i |= (k & 0x7F) << j++ * 7;
            if (j > 5) throw new RuntimeException("VarInt too big");
            if ((k & 0x80) != 128) break;
        }
        return i;
    }
}
