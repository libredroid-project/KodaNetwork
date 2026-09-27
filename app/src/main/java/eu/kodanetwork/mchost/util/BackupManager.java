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
 * Backups of a whole server folder: world, configs, plugins and datapacks in one ZIP.
 *
 * Files are stored in {@code filesDir/backups/<serverId>/<timestamp>.zip}, outside the server
 * directory: that keeps exports of the server folder small and stops the server from serving
 * its own backups through the tunnel. After every backup an index is written into
 * {@code plugins/KodaDash/backups.json} so the dashboard can show the list.
 *
 * Rotation keeps the newest N files (see keepCount) and removes the rest.
 */
public final class BackupManager {

    private static final String TAG = "KodaBackup";
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US);
    /** Folders and files that never belong in a backup. */
    private static final List<String> SKIP_NAMES = Arrays.asList(
            "logs", "backups", "crash-reports", "session.lock", ".frpc.toml", "frpc.pid", "server.pid"
    );

    private BackupManager() {
    }

    /** One backup file on disk. */
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

    /** Directory that holds the backups of one server. */
    public static File dirFor(Context ctx, String serverId) {
        File dir = new File(new File(ctx.getFilesDir(), "backups"), serverId);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create backup directory " + dir);
        }
        return dir;
    }

    /** All backups of a server, newest first. */
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

    /** Total size of all backups of a server in bytes. */
    public static long totalSize(Context ctx, String serverId) {
        long total = 0;
        for (Backup backup : list(ctx, serverId)) total += backup.size;
        return total;
    }

    /**
     * Creates a backup of the whole server folder.
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

    private static void addDirectory(File root, File current, ZipOutputStream zip) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        byte[] buffer = new byte[16384];
        for (File child : children) {
            if (SKIP_NAMES.contains(child.getName())) continue;
            String relative = root.toURI().relativize(child.toURI()).getPath();
            if (child.isDirectory()) {
                addDirectory(root, child, zip);
            } else if (child.isFile()) {
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    zip.putNextEntry(new ZipEntry(relative));
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, read);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    /**
     * Restores a backup over the server folder.
     *
     * The current folder is moved aside first and deleted only after the unpack succeeded, so a
     * broken archive cannot leave a server without files. The server must be stopped by the caller.
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
                if (!out.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
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
            // Put the original folder back, the archive was unusable
            deleteRecursively(serverDir);
            safety.renameTo(serverDir);
        }
        return ok;
    }

    /** Deletes the oldest backups until only {@code keep} files are left. */
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
     * Writes the list of backups where the dashboard can read it
     * ({@code plugins/KodaDash/backups.json}). The plugin only reads this file; creating,
     * restoring and deleting backups stays in the app, which owns the binary.
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
