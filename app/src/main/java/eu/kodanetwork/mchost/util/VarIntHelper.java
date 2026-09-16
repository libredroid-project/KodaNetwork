package eu.kodanetwork.mchost.util;

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
