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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class TarXzUtil {

    public static void extract(String tarXzPath, String destDir) throws IOException {
        File destFolder = new File(destDir);
        if (!destFolder.exists()) {
            destFolder.mkdirs();
        }

        try (FileInputStream fis = new FileInputStream(tarXzPath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             XZInputStream xzIn = new XZInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {

            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());
                
                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    outputFile.getParentFile().mkdirs();
                    try {
                        java.nio.file.Path link = outputFile.toPath();
                        java.nio.file.Path target = java.nio.file.Paths.get(entry.getLinkName());
                        if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            java.nio.file.Files.delete(link);
                        }
                        java.nio.file.Files.createSymbolicLink(link, target);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    outputFile.getParentFile().mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = tarIn.read(buffer)) != -1) {
                            bos.write(buffer, 0, length);
                        }
                    }
                    
                    // the executable bits have to survive the unpack
                    if (entry.getMode() == 0100755 || entry.getMode() == 0755 || entry.getName().endsWith(".so") || entry.getName().contains("/bin/")) {
                        outputFile.setExecutable(true, false);
                    }
                }
            }
        }
    }
}
