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
package eu.kodanetwork.mchost.security;

import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import java.security.MessageDigest;
import java.util.UUID;

public class HWIDManager {

    private static String cachedHwid;

    /**
     * device HWID v2 (2026-09): derived mostly from the Widevine hardware id (MediaDrm),
     * that one survives a factory reset and a reinstall, unlike ANDROID_ID.
     * fallback is the old ANDROID_ID+Build formula (emulators, ROMs without Widevine).
     * returns the raw SHA-256 hex string (64 chars, no prefix) just like before;
     * the "KODA-" shortening is done by the app_uuid caller, ban RPCs take it raw.
     */
    public static String getDeviceHWID(Context context) {
        if (cachedHwid != null) return cachedHwid;
        String hwid = null;
        try {
            hwid = widevineHwid();
        } catch (Throwable t) {
            // no Widevine here (emulator, exotic ROM), use the legacy id instead
        }
        if (hwid == null) {
            hwid = legacyHwid(context);
        }
        cachedHwid = hwid;
        return hwid;
    }

    private static String widevineHwid() throws android.media.UnsupportedSchemeException {
        // the standard widevine system id: edef8ba9-79d6-4ace-a3c8-27dcd51d21ed
        UUID wv = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);
        MediaDrm drm = new MediaDrm(wv);
        byte[] id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
        drm.close();
        if (id == null || id.length < 8) return null;
        return sha256Hex(id);
    }

    private static String legacyHwid(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String buildInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.HARDWARE
                + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT;
        return sha256Hex((androidId + buildInfo).getBytes());
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
