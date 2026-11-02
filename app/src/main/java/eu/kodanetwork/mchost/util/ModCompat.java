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
import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.model.ServerInstance;

/**
 * mods that ship their own native library for desktop platforms and therefore cannot
 * run on an Android phone server. they are caught before the JVM starts, so the user
 * gets a warning screen instead of a crash thirty seconds into world loading.
 *
 * keep the list small and only add mods that are confirmed to have no Android build.
 */
public final class ModCompat {

    /** { file name fragment, display name } */
    private static final String[][] BLOCKED = {
            {"valkyrienskies", "Valkyrien Skies"},
            {"krunch", "Krunch (Valkyrien Skies)"},
    };

    private ModCompat() {}

    /** every jar in mods/ that cannot run here, empty when the server is fine. */
    public static List<File> findUnsupportedMods(ServerInstance srv) {
        List<File> found = new ArrayList<>();
        if (srv == null || srv.getServerDir() == null) return found;
        File mods = new File(srv.getServerDir(), "mods");
        File[] jars = mods.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) return found;
        for (File jar : jars) {
            String n = jar.getName().toLowerCase();
            for (String[] entry : BLOCKED) {
                if (n.contains(entry[0])) {
                    found.add(jar);
                    break;
                }
            }
        }
        return found;
    }

    public static String displayName(File jar) {
        String n = jar.getName().toLowerCase();
        for (String[] entry : BLOCKED) {
            if (n.contains(entry[0])) return entry[1];
        }
        return jar.getName();
    }

    /** deletes the blocked jars, returns how many were removed. */
    public static int removeUnsupported(ServerInstance srv) {
        int removed = 0;
        for (File jar : findUnsupportedMods(srv)) {
            if (jar.delete()) removed++;
        }
        return removed;
    }
}
