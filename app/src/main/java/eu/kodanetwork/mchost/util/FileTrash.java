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

/**
 * a deleted file does not really disappear. it moves into .koda_trash, keeping its
 * original folder structure, so the undo bar and the trash folder can bring it back.
 * entries older than a week are cleaned up on the next delete.
 */
public final class FileTrash {

    public static final String DIR = ".koda_trash";
    private static final long KEEP_MS = 7L * 24L * 60L * 60L * 1000L;

    private FileTrash() {}

    public static File trashDir(File serverDir) {
        return new File(serverDir, DIR);
    }

    /** moves the file or folder into the trash, returns its new place or null. */
    public static File moveToTrash(File serverDir, File target) {
        if (serverDir == null || target == null || !target.exists()) return null;
        purgeOld(serverDir);
        File trash = trashDir(serverDir);
        String rel = relative(serverDir, target);
        if (rel == null || rel.isEmpty()) return null;

        File dest = new File(trash, rel);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        if (dest.exists()) {
            dest = new File(parent, dest.getName() + "." + System.currentTimeMillis());
        }
        return target.renameTo(dest) ? dest : null;
    }

    /** puts a trashed file back where it came from. */
    public static boolean restore(File serverDir, File trashed) {
        String rel = relative(trashDir(serverDir), trashed);
        if (rel == null || rel.isEmpty()) return false;
        File dest = new File(serverDir, rel);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        return trashed.renameTo(dest);
    }

    /** drops everything that has been in the trash for more than a week. */
    public static void purgeOld(File serverDir) {
        File trash = trashDir(serverDir);
        if (!trash.isDirectory()) return;
        File[] children = trash.listFiles();
        if (children == null) return;
        long now = System.currentTimeMillis();
        for (File child : children) {
            if (now - child.lastModified() > KEEP_MS) delete(child);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
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
