package de.kodahosting.kodadash.managers;

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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.LogRecord;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Captures server console output and manages SSE streaming to dashboard clients.
 *
 * SSE design: every connected browser gets its own bounded queue plus a dedicated
 * writer thread. The logging thread only enqueues (never writes to a socket), so a
 * slow or hanging client can neither stall the server console nor corrupt the
 * stream of other clients. Frames carry an {@code id:} field (the line index) which
 * makes {@code EventSource} resume automatically via the {@code Last-Event-ID}
 * header after a reconnect.
 */
public class ConsoleManager {
    private static final int CLIENT_QUEUE_SIZE = 1000;
    private static final long HEARTBEAT_SECONDS = 15L;

    private final KodaDash plugin;
    private final LinkedList<JsonObject> buffer = new LinkedList<>();
    private final int maxLines;
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();
    /** Line index counter - instance field so a plugin reload starts clean. */
    private final AtomicInteger totalLines = new AtomicInteger(0);
    private static ConsoleManager instance;
    private static boolean appenderAttached = false;
    private static Object log4jAppenderProxy;
    private static Object log4jRootLogger;
    private static Handler logHandler;

    private static class SseClient {
        final OutputStream stream;
        final HttpExchange exchange;
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE_SIZE);
        final Thread writer;
        volatile boolean closed = false;

        SseClient(OutputStream stream, HttpExchange exchange, final String name) {
            this.stream = stream;
            this.exchange = exchange;
            this.writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!closed) {
                            String frame = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                            if (frame == null) {
                                // Idle: keep-alive comment so the tunnel/browser does not drop us
                                stream.write(": hb\n\n".getBytes(StandardCharsets.UTF_8));
                            } else {
                                stream.write(frame.getBytes(StandardCharsets.UTF_8));
                            }
                            stream.flush();
                        }
                    } catch (Exception e) {
                        // Socket died (browser closed, tunnel dropped) - client is removed below
                    } finally {
                        closed = true;
                        try { stream.close(); } catch (Exception ignored) {}
                    }
                }
            }, name);
            writer.setDaemon(true);
        }

        /** Non-blocking: drops the oldest frame when the client cannot keep up. */
        void offer(String frame) {
            if (closed) return;
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }
    }

    public ConsoleManager(KodaDash plugin) {
        this.plugin = plugin;
        instance = this;
        this.maxLines = plugin.getConfig().getInt("console-buffer-size", 500);

        if (appenderAttached) return;
        appenderAttached = true;

        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || record.getMessage() == null) return;
                if (instance != null) instance.appendLine(record.getMillis(), record.getLevel().getName(), formatMessage(record));
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };

        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            log4jRootLogger = logManagerClass.getMethod("getRootLogger").invoke(null);
            Class<?> appenderInterface = Class.forName("org.apache.logging.log4j.core.Appender");

            log4jAppenderProxy = Proxy.newProxyInstance(
                appenderInterface.getClassLoader(),
                new Class<?>[]{appenderInterface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if (name.equals("append") && args != null && args.length == 1) {
                            Object event = args[0];
                            try {
                                Object messageObj = event.getClass().getMethod("getMessage").invoke(event);
                                String formatted = (String) messageObj.getClass().getMethod("getFormattedMessage").invoke(messageObj);
                                String level = event.getClass().getMethod("getLevel").invoke(event).toString();
                                long time = (long) event.getClass().getMethod("getTimeMillis").invoke(event);
                                if (instance != null) instance.appendLine(time, level, formatted);
                            } catch (Exception ignored) {}
                            return null;
                        } else if (name.equals("getName")) {
                            return "KodaDashAppender";
                        } else if (name.equals("isStarted")) {
                            return true;
                        } else if (name.equals("isStopped")) {
                            return false;
                        } else if (name.equals("getState")) {
                            try {
                                Class<?> stateClass = Class.forName("org.apache.logging.log4j.core.LifeCycle$State");
                                return Enum.valueOf((Class<Enum>) stateClass, "STARTED");
                            } catch (Exception e) {
                                return null;
                            }
                        } else if (name.equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        } else if (name.equals("equals")) {
                            return proxy == args[0];
                        }
                        return null;
                    }
                }
            );

            log4jRootLogger.getClass().getMethod("addAppender", appenderInterface).invoke(log4jRootLogger, log4jAppenderProxy);
        } catch (Throwable t) {
            // Fallback to java.util.logging if Log4j2 is not available
            Logger.getLogger("").addHandler(logHandler);
        }
    }

    private void appendLine(long time, String level, String message) {
        int index = totalLines.getAndIncrement();
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("timestamp", time);
        json.addProperty("level", level);
        json.addProperty("message", message);

        synchronized (buffer) {
            buffer.add(json);
            if (buffer.size() > maxLines) {
                buffer.removeFirst();
            }
        }
        broadcastSse(json);
    }

    /**
     * Clean up resources on plugin disable: detach the appender so a reload does not
     * attach a second one (which would duplicate every console line), stop all writers
     * and close every client socket.
     */
    public void cleanup() {
        instance = null;

        if (log4jAppenderProxy != null && log4jRootLogger != null) {
            try {
                Class<?> appenderInterface = Class.forName("org.apache.logging.log4j.core.Appender");
                log4jRootLogger.getClass().getMethod("removeAppender", appenderInterface)
                        .invoke(log4jRootLogger, log4jAppenderProxy);
            } catch (Throwable ignored) {}
            log4jAppenderProxy = null;
            log4jRootLogger = null;
        }
        if (logHandler != null) {
            try { Logger.getLogger("").removeHandler(logHandler); } catch (Exception ignored) {}
            logHandler = null;
        }
        appenderAttached = false;

        for (SseClient client : sseClients) {
            client.closed = true;
            try { client.stream.close(); } catch (IOException ignored) {}
            client.writer.interrupt();
        }
        sseClients.clear();
    }

    /**
     * Get all buffered console lines.
     */
    public List<JsonObject> getRecentLines() {
        synchronized (buffer) {
            return new LinkedList<>(buffer);
        }
    }

    /**
     * Get the last N console lines.
     */
    public List<JsonObject> getRecentLines(int count) {
        synchronized (buffer) {
            int size = buffer.size();
            int start = Math.max(0, size - count);
            return new LinkedList<>(buffer.subList(start, size));
        }
    }

    /**
     * Get console lines since a given index (used for reconnect backfill).
     */
    public List<JsonObject> getLinesSince(int startIndex) {
        synchronized (buffer) {
            if (buffer.isEmpty()) return new LinkedList<>();
            // The buffer is a sliding window - find the offset
            int firstIndex = totalLines.get() - buffer.size();
            int offset = startIndex - firstIndex;
            if (offset < 0) offset = 0;
            if (offset >= buffer.size()) return new LinkedList<>();
            return new LinkedList<>(buffer.subList(offset, buffer.size()));
        }
    }

    /**
     * Get total number of lines captured since plugin start.
     */
    public int getTotalLines() {
        return totalLines.get();
    }

    /**
     * Register an SSE client. The stream starts receiving frames immediately from its
     * own writer thread; {@code initialFrames} (a backfill snapshot) is queued first.
     */
    public SseClientView registerSseListener(OutputStream stream, HttpExchange exchange, List<String> initialFrames) {
        SseClient client = new SseClient(stream, exchange, "KodaDash-SSE-" + sseClients.size());
        for (String frame : initialFrames) client.offer(frame);
        sseClients.add(client);
        client.writer.start();
        return new SseClientView(client);
    }

    /** Handle returned to routes so they can drop a client explicitly. */
    public static class SseClientView {
        private final SseClient client;
        SseClientView(SseClient client) { this.client = client; }
        public void close() {
            client.closed = true;
            client.writer.interrupt();
        }
    }

    /**
     * Remove dead clients (writer threads that noticed a broken socket).
     */
    public void pruneClients() {
        List<SseClient> dead = new ArrayList<>();
        for (SseClient client : sseClients) {
            if (client.closed && !client.writer.isAlive()) dead.add(client);
        }
        sseClients.removeAll(dead);
    }

    /**
     * Build an SSE frame with the event id so browsers can resume after a reconnect.
     */
    public String buildFrame(JsonObject json) {
        return "id: " + json.get("index").getAsInt() + "\ndata: " + gson.toJson(json) + "\n\n";
    }

    /**
     * Get the number of connected SSE clients (after pruning dead ones).
     */
    public int getConnectedClients() {
        pruneClients();
        return sseClients.size();
    }

    /**
     * Broadcast a console line to all connected SSE clients (non-blocking).
     */
    private void broadcastSse(JsonObject json) {
        if (sseClients.isEmpty()) return;
        String frame = buildFrame(json);
        for (SseClient client : sseClients) {
            client.offer(frame);
        }
    }

    /**
     * Check whether a command is on the blocked list. Leading slashes are stripped so
     * {@code /stop} cannot bypass the entry {@code stop}.
     */
    public boolean isCommandBlocked(String command) {
        if (command == null) return false;
        String cleaned = command.trim();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1).trim();
        if (cleaned.isEmpty()) return false;
        String cmdBase = cleaned.split("\\s+")[0].toLowerCase();
        for (String b : plugin.getConfig().getStringList("blocked-commands")) {
            if (cmdBase.equalsIgnoreCase(b.trim().toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Execute a server command on the main thread.
     * Checks against the blocked commands list.
     *
     * @return true when the command was dispatched, false when it was blocked
     */
    public boolean executeCommand(String command) {
        if (command == null || command.trim().isEmpty()) return false;
        if (isCommandBlocked(command)) {
            plugin.getLogger().warning("Blocked command attempt via dashboard: " + command);
            return false;
        }
        final String toRun = command.trim();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun);
            }
        });
        return true;
    }

    /**
     * Format a log record message, handling parameter substitution.
     */
    private String formatMessage(LogRecord record) {
        String message = record.getMessage();
        if (message == null) return "";
        if (record.getParameters() != null && record.getParameters().length > 0) {
            try {
                return String.format(message, record.getParameters());
            } catch (Exception e) {
                return message;
            }
        }
        return message;
    }
}
