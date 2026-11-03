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

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * every deleted file waits in .koda_trash for a week. one folder per deletion keeps
 * the original path in a small meta file, so restore is exact even when the same
 * name was trashed twice. the trash screen reads this list, nothing else.
 */
public final class FileTrash {

    public static final String DIR = ".koda_trash";
    private static final String META = "meta.txt";
    private static final String PAYLOAD = "payload";
    private static final long KEEP_MS = 7L * 24L * 60L * 60L * 1000L;

    private FileTrash() {}

    public static File trashDir(File serverDir) {
        return new File(serverDir, DIR);
    }

    /** one deleted file or folder. */
    public static class Entry {
        public File dir;          // the entry folder in the trash
        public File payload;      // the deleted file or folder
        public String original;   // path relative to the server dir
        public long deletedAt;
        public boolean isDir;
        public long size;
        public String name;
    }

    /** moves the file or folder into the trash, returns the moved payload or null. */
    public static File moveToTrash(File serverDir, File target) {
        if (serverDir == null || target == null || !target.exists()) return null;
        String rel = relative(serverDir, target);
        if (rel == null || rel.isEmpty()) return null;

        purgeOld(serverDir);
        File trash = trashDir(serverDir);
        if (!trash.mkdirs() && !trash.isDirectory()) return null;

        File entryDir = new File(trash, System.currentTimeMillis() + "-" + target.getName());
        if (!entryDir.mkdirs()) return null;
        File payload = new File(entryDir, PAYLOAD);
        if (!target.renameTo(payload)) {
            entryDir.delete();
            return null;
        }
        try (FileWriter w = new FileWriter(new File(entryDir, META))) {
            w.write(rel);
        } catch (Exception ignored) {}
        entryDir.setLastModified(System.currentTimeMillis());
        return payload;
    }

    /** puts a trashed payload back where it came from. */
    public static boolean restore(File serverDir, File payload) {
        if (serverDir == null || payload == null || !payload.exists()) return false;
        File entryDir = payload.getParentFile();
        String rel = entryDir != null ? readMeta(entryDir) : null;
        if (rel == null || rel.isEmpty()) return false;
        File dest = new File(serverDir, rel);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        if (dest.exists()) {
            // something already sits there, do not overwrite it silently
            return false;
        }
        if (!payload.renameTo(dest)) return false;
        deleteRec(entryDir);
        return true;
    }

    /** every entry currently in the trash, newest first. */
    public static List<Entry> list(File serverDir) {
        List<Entry> out = new ArrayList<>();
        File trash = trashDir(serverDir);
        File[] dirs = trash.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File dir : dirs) {
            File payload = new File(dir, PAYLOAD);
            String rel = readMeta(dir);
            if (!payload.exists() || rel == null || rel.isEmpty()) continue;
            Entry e = new Entry();
            e.dir = dir;
            e.payload = payload;
            e.original = rel;
            e.deletedAt = dir.lastModified();
            e.isDir = payload.isDirectory();
            e.size = sizeOf(payload);
            e.name = payload.getName();
            out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return out;
    }

    public static boolean deleteEntry(Entry entry) {
        if (entry == null || entry.dir == null) return false;
        deleteRec(entry.dir);
        return !entry.dir.exists();
    }

    public static int restoreAll(File serverDir) {
        int restored = 0;
        for (Entry e : list(serverDir)) {
            if (restore(serverDir, e.payload)) restored++;
        }
        return restored;
    }

    public static void empty(File serverDir) {
        File trash = trashDir(serverDir);
        File[] children = trash.listFiles();
        if (children == null) return;
        for (File child : children) deleteRec(child);
    }

    /** drops everything that has been in the trash for more than a week. */
    public static void purgeOld(File serverDir) {
        File trash = trashDir(serverDir);
        File[] children = trash.listFiles();
        if (children == null) return;
        long now = System.currentTimeMillis();
        for (File child : children) {
            if (now - child.lastModified() > KEEP_MS) deleteRec(child);
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f));
    }

    private static String readMeta(File entryDir) {
        File meta = new File(entryDir, META);
        if (!meta.isFile()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(meta))) {
            String line = br.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private static void deleteRec(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRec(child);
        file.delete();
    }

    private static String relative(File root, File target) {
        if (root == null || target == null) return null;
        String rootPath = root.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (!targetPath.startsWith(rootPath)) return null;
        String rel = targetPath.substring(rootPath.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        return rel;
    }
}
