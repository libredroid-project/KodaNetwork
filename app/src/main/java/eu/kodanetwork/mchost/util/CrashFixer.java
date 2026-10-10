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

import android.content.Context;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import java.io.*;
import java.util.Properties;
import java.util.Random;

/**
 * runs the concrete fix for a known crash cause. each fix changes server files
 * or settings, the restart is then up to the user.
 */
public class CrashFixer {

    public static class FixResult {
        public boolean success;
        public String  message;   // what to tell the user
        public FixResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /** runs a fix by key and reports success plus a message. */
    public static FixResult executeFix(Context ctx, ServerInstance srv, String fixAction) {
        if (fixAction == null || srv == null) return new FixResult(false, "Invalid fix action");
        switch (fixAction) {
            case "ACCEPT_EULA":     return fixEula(srv);
            case "INCREASE_RAM":    return fixRam(ctx, srv);
            case "CHANGE_PORT":     return fixPort(ctx, srv);
            case "FIX_JAVA_VERSION":return fixJavaVersion(ctx, srv);
            case "FIX_PERMISSIONS":  return fixPermissions(srv);
            case "REDOWNLOAD_JAR":  return flagRedownloadJar(srv);
            case "REDOWNLOAD_JRE":  return flagRedownloadJre(ctx, srv);
            case "INSTALL_MISSING_MODS": return installMissingMods(srv);
            default:               return new FixResult(false, "Unknown fix: " + fixAction);
        }
    }

    /** writes eula=true into eula.txt. */
    private static FixResult fixEula(ServerInstance srv) {
        try {
            File eulaFile = new File(srv.getServerDir(), "eula.txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(eulaFile))) {
                pw.println("#Accepted by KodaHosting CrashFixer");
                pw.println("eula=true");
            }
            return new FixResult(true, "EULA accepted");
        } catch (Exception e) {
            return new FixResult(false, "Failed to write eula.txt: " + e.getMessage());
        }
    }

    /** 512 MB more, capped at 8192. */
    private static FixResult fixRam(Context ctx, ServerInstance srv) {
        int oldRam = srv.getRamMB();
        int newRam = Math.min(oldRam + 512, 8192);
        if (newRam == oldRam) return new FixResult(false, "RAM already at maximum (8192 MB)");
        srv.setRamMB(newRam);
        ServerRepo.get(ctx).update(srv);
        return new FixResult(true, "RAM increased: " + oldRam + " → " + newRam + " MB");
    }

    /** picks a random new port and writes it into server.properties. */
    private static FixResult fixPort(Context ctx, ServerInstance srv) {
        int newPort = 25566 + new Random().nextInt(34); // 25566-25599
        srv.setPort(newPort);
        ServerRepo.get(ctx).update(srv);
        // server.properties has to follow, when it is there
        File propsFile = new File(srv.getServerDir(), "server.properties");
        if (propsFile.exists()) {
            try {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(propsFile)) {
                    props.load(fis);
                }
                props.setProperty("server-port", String.valueOf(newPort));
                try (FileOutputStream fos = new FileOutputStream(propsFile)) {
                    props.store(fos, "Updated by KodaHosting CrashFixer");
                }
            } catch (Exception ignored) {}
        }
        return new FixResult(true, "Port changed to " + newPort);
    }

    /** back to java Auto (0), any running instance gets force stopped first. */
    private static FixResult fixJavaVersion(Context ctx, ServerInstance srv) {
        // kill it over an Intent, a stale JVM connection would block the change
        if (srv.state != ServerInstance.State.OFFLINE) {
            try {
                android.content.Intent killIntent = new android.content.Intent(ctx, eu.kodanetwork.mchost.service.KodaServerService.class);
                killIntent.setAction("KILL");
                killIntent.putExtra("id", srv.getId());
                ctx.startService(killIntent);
                // give it up to 5 seconds to reach OFFLINE
                for (int i = 0; i < 10; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    if (srv.state == ServerInstance.State.OFFLINE) break;
                }
            } catch (Exception ignored) {}
        }
        
        srv.setJavaRuntime(0);
        int autoVer = RuntimeManager.resolveAutoVersion(srv);
        ServerRepo.get(ctx).update(srv);
        return new FixResult(true, "Java set to Auto (Java " + autoVer + ")");
    }

    /** read and write for everyone under the server directory. */
    private static FixResult fixPermissions(ServerInstance srv) {
        File dir = new File(srv.getServerDir());
        if (!dir.exists()) return new FixResult(false, "Server directory not found");
        int fixed = fixPermsRecursive(dir, 0);
        return new FixResult(true, "Permissions fixed on " + fixed + " files");
    }

    private static int fixPermsRecursive(File f, int count) {
        if (f.isDirectory()) {
            f.setReadable(true, false);
            f.setWritable(true, false);
            f.setExecutable(true, false);
            count++;
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) count = fixPermsRecursive(c, count);
            }
        } else {
            f.setReadable(true, false);
            f.setWritable(true, false);
            count++;
        }
        return count;
    }

    /** the jar goes away, the next start pulls it again. */
    private static FixResult flagRedownloadJar(ServerInstance srv) {
        File dir = new File(srv.getServerDir());
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        int deleted = 0;
        if (jars != null) {
            for (File j : jars) {
                if (j.delete()) deleted++;
            }
        }
        // versions/ holds jars too
        File versionsDir = new File(dir, "versions");
        if (versionsDir.isDirectory()) {
            File[] vJars = versionsDir.listFiles((d, name) -> name.endsWith(".jar"));
            if (vJars != null) {
                for (File j : vJars) {
                    if (j.delete()) deleted++;
                }
            }
        }
        return deleted > 0
            ? new FixResult(true, "Deleted " + deleted + " JAR(s). Server will re-download on next start.")
            : new FixResult(false, "No JAR files found to delete.");
    }

    /**
     * reads the server log, collects the mod ids the loader complained about and
     * installs them from modrinth. the restart stays the user's call like every other fix.
     */
    private static FixResult installMissingMods(ServerInstance srv) {
        String log = readLogTail(srv, 500_000);
        if (log.isEmpty()) return new FixResult(false, "No server log found");

        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher forge = java.util.regex.Pattern
                .compile("(?i)Mod ID:\\s*'?([A-Za-z0-9_.-]+)'?").matcher(log);
        while (forge.find()) ids.add(forge.group(1));
        java.util.regex.Matcher fabric = java.util.regex.Pattern
                .compile("(?i)of mod\\s+\"?([A-Za-z0-9_.-]+)\"?\\s+which is missing").matcher(log);
        while (fabric.find()) ids.add(fabric.group(1));
        java.util.regex.Matcher fabricShort = java.util.regex.Pattern
                .compile("(?i)mod\\s+\"?([A-Za-z0-9_.-]+)\"?\\s+is missing").matcher(log);
        while (fabricShort.find()) ids.add(fabricShort.group(1));
        if (ids.isEmpty()) return new FixResult(false, "No missing mod id found in the log");

        java.util.List<String> installed = new java.util.ArrayList<>();
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String id : ids) {
            if (installed.size() >= 8) break;             // keep the repair short
            String projectId = ModrinthHelper.searchProjectIdForMod(id, srv);
            if (projectId == null) { unknown.add(id); continue; }
            File jar = ModrinthHelper.autoDownloadSync(projectId, srv);
            if (jar != null) installed.add(id); else unknown.add(id);
        }
        if (installed.isEmpty()) {
            return new FixResult(false, unknown.isEmpty()
                    ? "Nothing to install"
                    : "Not found on Modrinth: " + String.join(", ", unknown));
        }
        String msg = "Installed: " + String.join(", ", installed);
        if (!unknown.isEmpty()) msg += " (not found: " + String.join(", ", unknown) + ")";
        return new FixResult(true, msg);
    }

    /** the tail of the server log, empty when there is none. */
    private static String readLogTail(ServerInstance srv, int maxChars) {
        File log = new File(srv.getServerDir(), "server.log");
        if (!log.exists()) {
            File latest = new File(srv.getServerDir(), "logs/latest.log");
            if (latest.exists()) log = latest;
        }
        if (!log.exists()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxChars);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** deletes the jre directory so the next start downloads it again. */
    private static FixResult flagRedownloadJre(Context ctx, ServerInstance srv) {
        int ver = srv.getJavaRuntime() == 0 ? RuntimeManager.resolveAutoVersion(srv) : srv.getJavaRuntime();
        File jreDir = new File(ctx.getFilesDir(), "jre" + ver);
        if (jreDir.exists()) {
            deleteRecursive(jreDir);
            return new FixResult(true, "JRE " + ver + " deleted. Will re-download on next start.");
        }
        return new FixResult(false, "JRE directory not found.");
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
