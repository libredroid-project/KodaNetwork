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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * the owner account check, by hash so the address itself never ships in the source.
 */
public final class OwnerCheck {

    private static final byte[] OWNER_EMAIL_SHA256 =
            hexToBytes("0fe5b821035b481d392857e9f6b9a7442e79da0ac62c8650a26aa758fc7ad41d");

    private OwnerCheck() {
    }

    public static boolean isOwnerEmail(String email) {
        if (email == null) return false;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hash, OWNER_EMAIL_SHA256);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
