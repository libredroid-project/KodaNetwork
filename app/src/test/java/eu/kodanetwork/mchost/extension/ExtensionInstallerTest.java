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
package eu.kodanetwork.mchost.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Unpacking rules of .kodaext packages, especially the path safety. */
public class ExtensionInstallerTest {

    private static final int APP_VERSION = 8515;

    private static final String MANIFEST = "{\n"
            + "  \"schema\": 1,\n"
            + "  \"id\": \"eu.example.test\",\n"
            + "  \"name\": \"Test\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"versionCode\": 1,\n"
            + "  \"license\": \"MIT\",\n"
            + "  \"permissions\": [\"storage\"],\n"
            + "  \"ui\": { \"entry\": \"ui/index.html\" }\n"
            + "}";

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("kodaext-test").toFile();
    }

    private static File zip(File dir, String name, String... entries) throws IOException {
        File zip = new File(dir, name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    public void installsFlatPackage() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "flat.kodaext",
                "manifest.json", MANIFEST,
                "ui/index.html", "<html></html>",
                "ui/app.js", "console.log(1)");

        ExtensionManifest m = ExtensionInstaller.readManifest(pkg, APP_VERSION);
        assertEquals("eu.example.test", m.id);

        File target = new File(dir, "out");
        ExtensionInstaller.extract(pkg, target);
        assertTrue(new File(target, "manifest.json").isFile());
        assertTrue(new File(target, "ui/index.html").isFile());
        assertTrue(new File(target, "ui/app.js").isFile());
    }

    @Test
    public void installsPackageWithWrapperFolder() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "wrapped.kodaext",
                "my-ext/manifest.json", MANIFEST,
                "my-ext/ui/index.html", "<html></html>");
        pkg.renameTo(pkg); // no-op, just keeping the shape obvious

        ExtensionManifest m = ExtensionInstaller.readManifest(pkg, APP_VERSION);
        assertEquals("eu.example.test", m.id);

        File target = new File(dir, "out");
        ExtensionInstaller.extract(pkg, target);
        assertTrue(new File(target, "manifest.json").isFile());
        assertTrue(new File(target, "ui/index.html").isFile());
    }

    @Test
    public void rejectsPathTraversalInEntries() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "evil.kodaext",
                "manifest.json", MANIFEST,
                "../evil.txt", "gotcha");
        try {
            ExtensionInstaller.extract(pkg, new File(dir, "out"));
            fail("expected InstallException");
        } catch (ExtensionInstaller.InstallException expected) {
            // fine
        }
    }

    @Test
    public void rejectsAbsolutePathsAndDriveLetters() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "abs.kodaext",
                "manifest.json", MANIFEST,
                "/etc/passwd", "gotcha");
        try {
            ExtensionInstaller.extract(pkg, new File(dir, "out"));
            fail("expected InstallException");
        } catch (ExtensionInstaller.InstallException expected) {
            // fine
        }
    }

    @Test
    public void rejectsMissingManifest() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "nomanifest.kodaext", "ui/index.html", "<html></html>");
        try {
            ExtensionInstaller.readManifest(pkg, APP_VERSION);
            fail("expected InstallException");
        } catch (ExtensionInstaller.InstallException expected) {
            // fine
        }
    }

    @Test
    public void computesSha256() throws Exception {
        File dir = tempDir();
        File pkg = zip(dir, "hash.kodaext", "manifest.json", MANIFEST);
        String digest = ExtensionInstaller.sha256(pkg);
        assertEquals(64, digest.length());
        assertEquals(digest, ExtensionInstaller.sha256(pkg));
    }
}
