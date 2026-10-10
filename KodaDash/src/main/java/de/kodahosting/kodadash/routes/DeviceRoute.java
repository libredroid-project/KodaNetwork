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
package de.kodahosting.kodadash.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import de.kodahosting.kodadash.server.RouteHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * host info for the dashboard: the android device (the KodaHosting app writes it into
 * {@code plugins/KodaDash/device-info.json}), the system the JVM runs on, memory, disk and runtime.
 *
 * GET /api/device
 */
public class DeviceRoute extends RouteHandler {

    public DeviceRoute(KodaDash plugin) {
        super(plugin);
    }

    @Override
    protected void handleGet(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();

        response.add("device", readDeviceInfo());
        response.add("system", systemInfo());
        response.add("memory", memoryInfo());
        response.add("disk", diskInfo());
        response.add("runtime", runtimeInfo());

        JsonObject pluginInfo = new JsonObject();
        pluginInfo.addProperty("version", plugin.getDescription().getVersion());
        pluginInfo.addProperty("port", plugin.getConfig().getInt("port", 7867));
        response.add("plugin", pluginInfo);

        sendJson(exchange, 200, response);
    }

    /** android device details, written by the app. null when the app never wrote them. */
    private JsonObject readDeviceInfo() {
        File file = new File(plugin.getDataFolder(), "device-info.json");
        if (!file.isFile()) return null;
        try {
            String json = readText(file);
            return new com.google.gson.JsonParser().parse(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject systemInfo() {
        JsonObject system = new JsonObject();
        system.addProperty("os", System.getProperty("os.name", "unknown"));
        system.addProperty("arch", System.getProperty("os.arch", "unknown"));
        // android blocks /proc/version for apps, but the JVM has the kernel as os.version anyway
        String kernel = firstLine("/proc/version");
        if (kernel == null) kernel = System.getProperty("os.version", null);
        if (kernel != null) system.addProperty("kernel", kernel);
        system.addProperty("cpuModel", cpuModel());
        system.addProperty("cores", Runtime.getRuntime().availableProcessors());

        String load = firstLine("/proc/loadavg");
        if (load != null && load.contains(" ")) {
            JsonArray loads = new JsonArray();
            String[] parts = load.split("\\s+");
            for (int i = 0; i < 3 && i < parts.length; i++) {
                try {
                    loads.add(new com.google.gson.JsonPrimitive(Double.parseDouble(parts[i])));
                } catch (NumberFormatException ignored) {}
            }
            system.add("loadAvg", loads);
        }
        return system;
    }

    private JsonObject memoryInfo() {
        JsonObject memory = new JsonObject();
        long totalKb = 0L, availableKb = 0L;

        for (String line : readLines("/proc/meminfo")) {
            if (line.startsWith("MemTotal:")) totalKb = parseKb(line);
            else if (line.startsWith("MemAvailable:")) availableKb = parseKb(line);
        }

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long heapMax = runtime.maxMemory() / (1024L * 1024L);

        if (totalKb > 0) memory.addProperty("totalMb", totalKb / 1024L);
        if (availableKb > 0) memory.addProperty("availableMb", availableKb / 1024L);
        memory.addProperty("jvmUsedMb", heapUsed);
        memory.addProperty("jvmMaxMb", heapMax);
        return memory;
    }

    private JsonObject diskInfo() {
        JsonObject disk = new JsonObject();
        File dataFolder = plugin.getDataFolder();
        File serverDir = dataFolder.getParentFile() != null ? dataFolder.getParentFile().getParentFile() : dataFolder;
        if (serverDir == null) serverDir = dataFolder;

        long gb = 1024L * 1024L * 1024L;
        try {
            disk.addProperty("totalGb", round(serverDir.getTotalSpace() / (double) gb));
            disk.addProperty("freeGb", round(serverDir.getUsableSpace() / (double) gb));
            disk.addProperty("serverDirGb", round(folderSize(serverDir) / (double) gb));
        } catch (Exception ignored) {}
        return disk;
    }

    private JsonObject runtimeInfo() {
        JsonObject runtime = new JsonObject();
        runtime.addProperty("java", System.getProperty("java.version", "unknown"));
        runtime.addProperty("jvm", System.getProperty("java.vm.name", "unknown"));
        runtime.addProperty("vendor", System.getProperty("java.vendor", "unknown"));
        runtime.addProperty("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        runtime.addProperty("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        return runtime;
    }

    // --- helpers ------------------------------------------------------------

    private String readText(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(file.length(), 65536L)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(bytes);
            return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        }
    }

    private String firstLine(String path) {
        for (String line : readLines(path)) {
            if (!line.trim().isEmpty()) return line.trim();
        }
        return null;
    }

    private java.util.List<String> readLines(String path) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        File file = new File(path);
        if (!file.isFile()) return lines;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            String line;
            int guard = 0;
            while ((line = raf.readLine()) != null && guard++ < 200) {
                lines.add(line);
            }
        } catch (Exception ignored) {}
        return lines;
    }

    private String cpuModel() {
        for (String line : readLines("/proc/cpuinfo")) {
            if (line.startsWith("model name") || line.startsWith("Hardware") || line.startsWith("Processor")) {
                int colon = line.indexOf(':');
                if (colon > 0) return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private long parseKb(String line) {
        try {
            String digits = line.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0L : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long folderSize(File folder) {
        long size = 0L;
        File[] children = folder.listFiles();
        if (children == null) return 0L;
        int guard = 0;
        for (File child : children) {
            if (guard++ > 5000) break;
            size += child.isDirectory() ? folderSize(child) : child.length();
        }
        return size;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
