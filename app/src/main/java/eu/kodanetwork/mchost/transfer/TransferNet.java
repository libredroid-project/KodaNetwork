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
package eu.kodanetwork.mchost.transfer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * a minimal HTTP GET over a raw socket, for the receiving side of the device
 * transfer.
 *
 * why not OkHttp: the app forbids cleartext HTTP globally (network security
 * config) and that policy bites inside the HTTP stacks. a plain socket is not
 * affected, and everything pulled here is AES-GCM ciphertext anyway. the
 * decryption key only ever arrives over HTTPS from Supabase.
 */
public final class TransferNet {

    /** the response body stream plus the length it announced. */
    public static class Response {
        public final long contentLength;
        public final InputStream body;
        public final int status;

        Response(int status, long contentLength, InputStream body) {
            this.status = status;
            this.contentLength = contentLength;
            this.body = body;
        }
    }

    private TransferNet() {
    }

    /**
     * GETs a path from the sender's mini server. the returned stream owns the
     * socket, so closing it closes the connection too.
     */
    public static Response get(String ip, int port, String path, int connectTimeoutMs,
                               int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            String request = "GET " + path + " HTTP/1.1\r\nHost: koda\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            InputStream raw = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            StringBuilder head = new StringBuilder();
            int prev = -1;
            while (true) {
                int b = raw.read();
                if (b == -1) throw new IOException("connection closed before headers");
                if (prev == '\r' && b == '\n' && head.length() >= 4
                        && head.charAt(head.length() - 2) == '\n'
                        && head.charAt(head.length() - 3) == '\r') {
                    break; // header block ends with \r\n\r\n
                }
                head.append((char) b);
                prev = b;
            }

            String[] lines = head.toString().split("\r\n");
            if (lines.length == 0) throw new IOException("empty response");
            String[] statusParts = lines[0].split(" ");
            int status = statusParts.length > 1 ? Integer.parseInt(statusParts[1]) : 0;
            long length = -1;
            for (int i = 1; i < lines.length; i++) {
                String lower = lines[i].toLowerCase();
                if (lower.startsWith("content-length:")) {
                    length = Long.parseLong(lower.substring(15).trim());
                }
            }
            if (status != 200) {
                socket.close();
                throw new IOException("HTTP " + status);
            }
            return new Response(status, length, new SocketStream(socket, raw));
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** a stream that closes its socket along with itself. */
    private static final class SocketStream extends InputStream {
        private final Socket socket;
        private final InputStream in;

        SocketStream(Socket socket, InputStream in) {
            this.socket = socket;
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return in.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                socket.close();
            }
        }
    }
}
