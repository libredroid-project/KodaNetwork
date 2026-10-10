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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The examples shipped in the repository must install on the current app build.
 *
 * This is the guard that catches a stale minAppVersion or a rule change before
 * it reaches a device: the app would only report "not a valid package" there.
 */
public class ExtensionExamplesTest {

    @Test
    public void exampleManifestsInstallOnThisBuild() throws Exception {
        File dir = findExamplesDir();
        Assume.assumeTrue("extensions/examples not found, skipping", dir != null);

        File[] folders = dir.listFiles(File::isDirectory);
        assertTrue("no examples found in " + dir, folders != null && folders.length > 0);

        for (File folder : folders) {
            File manifest = new File(folder, "manifest.json");
            assertTrue("missing manifest: " + manifest, manifest.isFile());
            String json = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            try {
                ExtensionManifest.parse(json, eu.kodanetwork.mchost.BuildConfig.VERSION_CODE);
            } catch (ExtensionManifest.InvalidManifestException e) {
                fail("example \"" + folder.getName() + "\" does not install on app build "
                        + eu.kodanetwork.mchost.BuildConfig.VERSION_CODE + ": " + e.getMessage());
            }
        }
    }

    /** Unit tests run with the module folder as working directory, tolerate both layouts. */
    private static File findExamplesDir() {
        File dir = new File("../extensions/examples");
        if (dir.isDirectory()) return dir;
        dir = new File("extensions/examples");
        if (dir.isDirectory()) return dir;
        return null;
    }
}
