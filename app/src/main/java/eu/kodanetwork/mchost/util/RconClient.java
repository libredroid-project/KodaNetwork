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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * the tiny source rcon client the console uses for forge and neoforge. their
 * embedded server does not act on commands written into stdin, so the same
 * commands go over rcon on localhost instead. one connection per command, the
 * server drops it afterwards anyway.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    private RconClient() {}

    /**
     * authenticates and runs one console command.
     *
     * @return true when the server accepted the command
     */
    public static boolean runCommand(String host, int port, String password, String command) {
        if (port <= 0) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            send(out, 1, TYPE_AUTH, password == null ? "" : password);
            int[] auth = read(in);
            if (auth == null || auth[0] == -1) return false;

            send(out, 2, TYPE_COMMAND, command);
            // the response text is not shown in the console (the server logs it anyway),
            // but reading it keeps the connection clean before it is closed
            read(in);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + payload.length + 2;
        ByteBuffer buf = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payload);
        buf.put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    /** @return {id, type} of one packet, null when the stream ended or the size is bogus. */
    private static int[] read(InputStream in) throws IOException {
        byte[] sizeBytes = readFully(in, 4);
        if (sizeBytes == null) return null;
        int length = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 4 * 1024 * 1024) return null;
        byte[] data = readFully(in, length);
        if (data == null) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{buf.getInt(), buf.getInt()};
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(out, offset, length - offset);
            if (read < 0) return null;
            offset += read;
        }
        return out;
    }
}
