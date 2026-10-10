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

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * a minimal single purpose HTTP server for the device transfer, hand rolled on a
 * plain ServerSocket (the app ships no web server library).
 *
 * two routes only, both gated by the one-time random URL token:
 *   GET /&lt;token&gt;/m         the manifest (server list with sizes and hashes)
 *   GET /&lt;token&gt;/s/&lt;id&gt;   one encrypted server blob
 *
 * it serves one connection at a time, since exactly one receiving device is
 * expected. everything it hands out is ciphertext and the AES key never comes
 * through here.
 */
public final class TransferHttpServer {

    public interface Listener {
        void onServeProgress(String serverId, long bytes, long total);
    }

    private static final String TAG = "KodaTransferHttp";
    private static final int CHUNK = 64 * 1024;

    private final String token;
    private final byte[] manifest;
    private final Map<String, File> blobs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private volatile Socket current;

    private TransferHttpServer(String token, byte[] manifest, Map<String, File> blobs, Listener listener) {
        this.token = token;
        this.manifest = manifest;
        this.blobs = new HashMap<>(blobs);
        this.listener = listener;
    }

    /** binds on all interfaces and starts the accept loop. */
    public static TransferHttpServer start(int port, String token, byte[] manifest,
                                           Map<String, File> blobs, Listener listener) throws IOException {
        TransferHttpServer server = new TransferHttpServer(token, manifest, blobs, listener);
        server.serverSocket = new ServerSocket(port, 4);
        server.running = true;
        Thread t = new Thread(server::acceptLoop, "KodaTransferHttp");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "Serving transfer on port " + port);
        return server;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                current = socket;
                handle(socket);
            } catch (Exception e) {
                if (running) Log.w(TAG, "Accept failed: " + e.getMessage());
            } finally {
                closeQuietly(current);
                current = null;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            // drain the headers, a GET carries no body and only the path matters
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                // nothing in here is ever used
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                respond(socket, "405 Method Not Allowed", "text/plain", "method not allowed".getBytes());
                return;
            }
            String path = parts[1];

            if (!path.startsWith("/" + token + "/")) {
                respond(socket, "403 Forbidden", "text/plain", "forbidden".getBytes());
                return;
            }
            String route = path.substring(token.length() + 2); // after "/<token>/"

            if ("m".equals(route)) {
                respond(socket, "200 OK", "application/json", manifest);
                return;
            }
            if (route.startsWith("s/")) {
                String id = route.substring(2);
                File blob = blobs.get(id);
                if (blob == null || !blob.isFile()) {
                    respond(socket, "404 Not Found", "text/plain", "unknown server".getBytes());
                    return;
                }
                streamBlob(socket, id, blob);
                return;
            }
            respond(socket, "404 Not Found", "text/plain", "not found".getBytes());
        } catch (Exception e) {
            Log.w(TAG, "Request failed: " + e.getMessage());
        }
    }

    private void streamBlob(Socket socket, String serverId, File blob) throws IOException {
        OutputStream out = socket.getOutputStream();
        byte[] head = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + blob.length() + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        out.write(head);
        long total = blob.length();
        long sent = 0;
        try (java.io.InputStream in = new BufferedInputStream(new java.io.FileInputStream(blob))) {
            byte[] buf = new byte[CHUNK];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                sent += read;
                if (listener != null && (sent % (512 * 1024) == 0 || sent == total)) {
                    final long done = sent, all = total;
                    main.post(() -> listener.onServeProgress(serverId, done, all));
                }
            }
        }
        out.flush();
        Log.i(TAG, String.format(Locale.US, "Served %s: %d bytes", serverId, sent));
    }

    private void respond(Socket socket, String status, String type, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** stops the accept loop and cuts off a running download. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        closeQuietly(current);
    }

    public boolean isRunning() {
        return running;
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * every local IPv4 address the receiver should try, Wi-Fi interfaces first.
     * a phone often sits on several networks at once (VPN, hotspot, dual band),
     * and announcing only the first one made receivers fail with "not reachable"
     * although both devices were on the same network.
     */
    public static java.util.List<String> lanIps() {
        java.util.List<String> wlan = new java.util.ArrayList<>();
        java.util.List<String> others = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface net = nets.nextElement();
                if (!net.isUp() || net.isLoopback() || net.isVirtual()) continue;
                String name = net.getName().toLowerCase(Locale.US);
                boolean isWifi = name.contains("wlan") || name.contains("wifi") || name.contains("ap");
                java.util.Enumeration<java.net.InetAddress> addrs = net.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet4Address) || addr.isLoopbackAddress()) continue;
                    if (!addr.isSiteLocalAddress()) continue;
                    String text = addr.getHostAddress();
                    if (isWifi) {
                        if (!wlan.contains(text)) wlan.add(text);
                    } else if (!others.contains(text)) {
                        others.add(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        wlan.addAll(others);
        return wlan;
    }
}
