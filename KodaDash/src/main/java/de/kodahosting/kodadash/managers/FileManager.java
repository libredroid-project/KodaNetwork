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
package de.kodahosting.kodadash.managers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kodahosting.kodadash.KodaDash;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * does the file work, but only inside the server directory.
 * every path is validated first, that is what stops path traversal from walking out.
 */
public class FileManager {
    private final KodaDash plugin;
    private final File rootDir;

    public FileManager(KodaDash plugin) {
        this.plugin = plugin;
        // the working directory is the server root
        this.rootDir = new File(".").getAbsoluteFile();
    }

    /**
     * is that path allowed: inside the server dir and not on the blocked list.
     */
    public boolean isPathSafe(String path) {
        try {
            File file = new File(rootDir, path);
            String canonicalRoot = rootDir.getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot)) return false;

            List<String> blocked = plugin.getConfig().getStringList("blocked-paths");
            for (String b : blocked) {
                File blockedFile = new File(rootDir, b);
                try {
                    if (canonicalFile.equals(blockedFile.getCanonicalPath())) return false;
                    // a file inside a blocked directory is blocked too
                    if (canonicalFile.startsWith(blockedFile.getCanonicalPath() + File.separator)) return false;
                } catch (IOException ignored) {}
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * info about a file or a directory, as whichever JSON fits the case.
     * FilesRoute uses this for its single GET handler.
     */
    public JsonObject getFileOrDirectory(String path) throws IOException, SecurityException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (!file.exists()) return null;

        JsonObject result = new JsonObject();

        if (file.isDirectory()) {
            result.addProperty("type", "directory");
            result.addProperty("path", path.isEmpty() ? "/" : path);
            result.add("entries", listDirectory(path));
        } else {
            result.addProperty("type", "file");
            result.addProperty("path", path);
            result.addProperty("size", file.length());

            if (file.length() > 1024 * 1024) {
                result.addProperty("tooLarge", true);
            } else {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    result.addProperty("content", content);
                    result.addProperty("tooLarge", false);
                } catch (Exception e) {
                    // binary file or broken encoding, so the browser gets no content
                    result.addProperty("tooLarge", true);
                    result.addProperty("binary", true);
                }
            }
        }

        return result;
    }

    /**
     * the directory contents as a JSON array.
     */
    public JsonArray listDirectory(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File dir = new File(rootDir, path);
        if (!dir.exists() || !dir.isDirectory()) throw new IOException("Not a directory");

        JsonArray arr = new JsonArray();
        File[] files = dir.listFiles();
        if (files != null) {
            // directories first, then by name, same order as any file browser
            java.util.Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (File f : files) {
                // dotfiles stay hidden, they are none of the dashboard's business
                if (f.getName().startsWith(".")) continue;
                
                JsonObject obj = new JsonObject();
                obj.addProperty("name", f.getName());
                obj.addProperty("isDirectory", f.isDirectory());
                obj.addProperty("size", f.isFile() ? f.length() : 0);
                obj.addProperty("lastModified", f.lastModified());
                arr.add(obj);
            }
        }
        return arr;
    }

    /**
     * the file as text.
     */
    public String readFile(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (!file.exists() || !file.isFile()) throw new IOException("Not a file");
        if (file.length() > 1024 * 1024) throw new IOException("File too large (max 1MB)");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * writes text to a file and creates missing parent directories on the way.
     *
     * @return true if successful
     */
    public boolean writeFile(String path, String content) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * writes decoded base64 to a file, parent directories included.
     */
    public boolean writeBase64File(String path, String base64) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        byte[] decoded = java.util.Base64.getDecoder().decode(base64);
        Files.write(file.toPath(), decoded);
        return true;
    }

    /**
     * deletes a file or an empty directory, non-empty ones are refused.
     */
    public void deleteFile(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (!file.exists()) throw new IOException("File not found");
        if (file.isDirectory() && file.list() != null && file.list().length > 0) {
            throw new IOException("Directory not empty");
        }
        Files.delete(file.toPath());
    }

    /**
     * same as deleteFile but a boolean comes back, the route wants it that way.
     *
     * @return true if deleted successfully
     */
    public boolean deleteFileOrDirectory(String path) {
        try {
            deleteFile(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * makes a directory, parents included.
     *
     * @return true if created successfully
     */
    public boolean createDirectory(String path) {
        try {
            if (!isPathSafe(path)) return false;
            File file = new File(rootDir, path);
            Files.createDirectories(file.toPath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * file size in bytes.
     */
    public long getFileSize(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        return file.length();
    }

    /**
     * renames a file or directory. the new name is a plain name without separators, so the thing
     * stays in its parent directory and only loses its old name.
     *
     * @return the new relative path
     */
    public String rename(String path, String newName) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        if (newName == null) throw new IOException("Missing new name");
        String clean = newName.trim();
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\") || clean.equals(".") || clean.equals("..")) {
            throw new IOException("Invalid name");
        }
        File source = new File(rootDir, path);
        if (!source.exists()) throw new IOException("File not found");
        File target = new File(source.getParentFile(), clean);
        if (!isPathSafe(target.getPath())) throw new SecurityException("Access denied: blocked path");
        if (target.exists()) throw new IOException("A file with that name already exists");
        Files.move(source.toPath(), target.toPath());
        String root = rootDir.getCanonicalPath();
        String absolute = target.getCanonicalPath();
        String relative = absolute.startsWith(root) ? absolute.substring(root.length()) : absolute;
        return relative.replace('\\', '/').replaceFirst("^/", "");
    }

    /**
     * raw bytes for a download, hard limit at 200 MB.
     */
    public byte[] readBytes(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        File file = new File(rootDir, path);
        if (!file.exists() || !file.isFile()) throw new IOException("Not a file");
        if (file.length() > 200L * 1024 * 1024) throw new IOException("File too large to download");
        return Files.readAllBytes(file.toPath());
    }

    /**
     * @return the absolute File behind a safe relative path, the log route needs this
     */
    public File resolve(String path) throws IOException {
        if (!isPathSafe(path)) throw new SecurityException("Access denied: blocked path");
        return new File(rootDir, path);
    }
}
