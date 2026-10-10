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

/**
 * compares minecraft version strings like "1.21.11", "26.2" or "1.21.11-rc3".
 *
 * the numbers decide, and a suffix (rc, pre) makes a version older than the plain one
 * with the same numbers, which is exactly how paper ships its pre-releases.
 */
public final class MinecraftVersion {

    private MinecraftVersion() {
    }

    /** @return negative when a is older, 0 when equal, positive when a is newer than b. */
    public static int compare(String a, String b) {
        int[] left = numbers(a);
        int[] right = numbers(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) return l - r;
        }
        boolean aSuffix = hasSuffix(a);
        boolean bSuffix = hasSuffix(b);
        if (aSuffix == bSuffix) return 0;
        return aSuffix ? -1 : 1;
    }

    private static int[] numbers(String version) {
        if (version == null) return new int[0];
        String[] parts = version.trim().split("\\.");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (char c : parts[i].toCharArray()) {
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            try {
                values[i] = digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
            } catch (NumberFormatException e) {
                values[i] = 0;
            }
        }
        return values;
    }

    private static boolean hasSuffix(String version) {
        return version != null && version.contains("-");
    }
}
