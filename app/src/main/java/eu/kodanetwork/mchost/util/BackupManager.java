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
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import eu.kodanetwork.mchost.model.ServerInstance;

/**
 * backups of a whole server folder: world, configs, plugins and datapacks in one zip.
 *
 * the files sit in {@code filesDir/backups/<serverId>/<timestamp>.zip}, outside the server
 * directory. that keeps exports of the server folder small and stops the server from
 * serving its own backups through the tunnel. after every backup an index is written into
 * {@code plugins/KodaDash/backups.json} so the dashboard can show the list.
 *
 * rotation keeps the newest N files (see keepCount) and deletes the rest.
 */
public final class BackupManager {

    private static final String TAG = "KodaBackup";
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US);
    /** names that never belong in a backup. */
    private static final List<String> SKIP_NAMES = Arrays.asList(
            "logs", "backups", "crash-reports", "session.lock", ".frpc.toml", "frpc.pid", "server.pid"
    );

    private BackupManager() {
    }

    /** one backup file on disk. */
    public static class Backup {
        public final File file;
        public final long size;
        public final long time;

        Backup(File file) {
            this.file = file;
            this.size = file.length();
            this.time = file.lastModified();
        }

        public String name() {
            return file.getName();
        }
    }

    /** the directory holding the backups of one server. */
    public static File dirFor(Context ctx, String serverId) {
        File dir = new File(new File(ctx.getFilesDir(), "backups"), serverId);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create backup directory " + dir);
        }
        return dir;
    }

    /** every backup of a server, newest first. */
    public static List<Backup> list(Context ctx, String serverId) {
        List<Backup> result = new ArrayList<>();
        File[] files = dirFor(ctx, serverId).listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".zip")) result.add(new Backup(file));
        }
        result.sort(new Comparator<Backup>() {
            @Override
            public int compare(Backup a, Backup b) {
                return Long.compare(b.time, a.time);
            }
        });
        return result;
    }

    /** all backups of a server added up, in bytes. */
    public static long totalSize(Context ctx, String serverId) {
        long total = 0;
        for (Backup backup : list(ctx, serverId)) total += backup.size;
        return total;
    }

    /**
     * zips the whole server folder into a fresh backup.
     *
     * @return the created file, or null when nothing could be written
     */
    public static File createBackup(Context ctx, ServerInstance srv, String reason) {
        File serverDir = new File(srv.getServerDir());
        if (!serverDir.isDirectory()) {
            Log.w(TAG, "Server directory missing, skipping backup: " + serverDir);
            return null;
        }
        String name = STAMP.format(new Date()) + (reason == null || reason.isEmpty() ? "" : "_" + reason) + ".zip";
        File target = new File(dirFor(ctx, srv.getId()), name);
        long started = System.currentTimeMillis();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            addDirectory(serverDir, serverDir, zip);
        } catch (Exception e) {
            Log.e(TAG, "Backup failed: " + e.getMessage());
            if (target.exists() && !target.delete()) Log.w(TAG, "Could not remove partial backup");
            return null;
        }
        Log.i(TAG, "Backup written in " + (System.currentTimeMillis() - started) + " ms: " + target);
        writeDashboardIndex(ctx, srv);
        return target;
    }

    /**
     * zips a whole server folder into any target file, with the same skip rules
     * as a backup (the device transfer uses this).
     */
    public static void zipServerTo(File serverDir, File targetZip) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(targetZip)))) {
            zipServerTo(serverDir, zip);
        }
    }

    /**
     * live progress while a server folder is packed: the file being written
     * right now and how many are done (device transfer ui).
     */
    public interface ZipProgress {
        void onFile(String relativePath, int done, int total);
    }

    /**
     * zips a whole server folder into a zip stream someone else owns, with the
     * same skip rules as a backup (the device transfer uses this). closing the
     * zip stays with the caller.
     */
    public static void zipServerTo(File serverDir, ZipOutputStream zip) throws IOException {
        zipServerTo(serverDir, zip, null);
    }

    public static void zipServerTo(File serverDir, ZipOutputStream zip, ZipProgress progress) throws IOException {
        int total = progress == null ? 0 : countFiles(serverDir);
        addDirectory(serverDir, serverDir, zip, new int[]{0}, total, progress);
    }

    /** how many files would end up in the zip, same skip rules. */
    public static int countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) {
            if (SKIP_NAMES.contains(child.getName())) continue;
            if (child.isDirectory()) count += countFiles(child);
            else if (child.isFile()) count++;
        }
        return count;
    }

    private static void addDirectory(File root, File current, ZipOutputStream zip) throws IOException {
        addDirectory(root, current, zip, new int[]{0}, 0, null);
    }

    private static void addDirectory(File root, File current, ZipOutputStream zip,
                                     int[] counter, int total, ZipProgress progress) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        byte[] buffer = new byte[16384];
        for (File child : children) {
            if (SKIP_NAMES.contains(child.getName())) continue;
            String relative = root.toURI().relativize(child.toURI()).getPath();
            if (child.isDirectory()) {
                addDirectory(root, child, zip, counter, total, progress);
            } else if (child.isFile()) {
                if (progress != null) {
                    final int done = counter[0];
                    progress.onFile(relative, done, total);
                }
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    zip.putNextEntry(new ZipEntry(relative));
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, read);
                    }
                    zip.closeEntry();
                    counter[0]++;
                }
            }
        }
    }

    /**
     * unpacks a backup over the server folder.
     *
     * the current folder moves aside first, deleted only after the unpack succeeded, so a
     * broken archive cannot leave a server without files. the caller stops the server.
     *
     * @return true when the backup was unpacked
     */
    public static boolean restore(Context ctx, ServerInstance srv, File zipFile) {
        File serverDir = new File(srv.getServerDir());
        if (!zipFile.isFile()) return false;
        File parent = serverDir.getParentFile();
        if (parent == null) return false;

        File safety = new File(parent, serverDir.getName() + "_before_restore_" + System.currentTimeMillis());
        boolean moved = serverDir.renameTo(safety);
        if (!moved) {
            Log.e(TAG, "Could not move the server folder aside for the restore");
            return false;
        }
        if (!serverDir.mkdirs() && !serverDir.isDirectory()) {
            safety.renameTo(serverDir);
            return false;
        }

        boolean ok = true;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(serverDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(serverDir.getCanonicalPath() + File.separator)) {
                    Log.w(TAG, "Skipping entry outside the server folder: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parentDir = out.getParentFile();
                if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        bos.write(buffer, 0, read);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Restore failed: " + e.getMessage());
            ok = false;
        }

        if (ok) {
            deleteRecursively(safety);
            Log.i(TAG, "Backup restored: " + zipFile.getName());
        } else {
            // the archive was unusable, so the original folder goes back
            deleteRecursively(serverDir);
            safety.renameTo(serverDir);
        }
        return ok;
    }

    /** deletes the oldest backups until {@code keep} files are left. */
    public static void rotate(Context ctx, String serverId, int keep) {
        int limit = Math.max(1, keep);
        List<Backup> backups = list(ctx, serverId);
        for (int i = limit; i < backups.size(); i++) {
            if (!backups.get(i).file.delete()) {
                Log.w(TAG, "Could not delete old backup " + backups.get(i).name());
            }
        }
    }

    public static boolean delete(Context ctx, String serverId, String fileName) {
        File file = new File(dirFor(ctx, serverId), fileName);
        if (!file.isFile()) return false;
        boolean deleted = file.delete();
        if (deleted) Log.i(TAG, "Backup deleted: " + fileName);
        return deleted;
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Could not delete " + file.getAbsolutePath());
    }

    /**
     * writes the backup list to where the dashboard reads it
     * ({@code plugins/KodaDash/backups.json}). the plugin only reads this file,
     * creating, restoring and deleting stays in the app, which owns the binary.
     */
    public static void writeDashboardIndex(Context ctx, ServerInstance srv) {
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            for (Backup backup : list(ctx, srv.getId())) {
                org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("name", backup.name());
                entry.put("sizeMb", backup.size / (1024 * 1024));
                entry.put("time", backup.time);
                items.put(entry);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("keep", srv.getBackupKeep());
            root.put("mode", srv.getBackupMode());
            root.put("backups", items);

            File dashDir = new File(srv.getServerDir(), "plugins/KodaDash");
            if (!dashDir.exists() && !dashDir.mkdirs()) return;
            File target = new File(dashDir, "backups.json");
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the dashboard backup index: " + e.getMessage());
        }
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    public static String humanDate(long time) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(time));
    }
}
