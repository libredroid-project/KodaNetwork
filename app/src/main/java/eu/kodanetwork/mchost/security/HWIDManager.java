package eu.kodanetwork.mchost.security;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) — see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW — see LOPL_v1.0_PREVIEW.md
 *   - Commercial License — see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */


import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import java.security.MessageDigest;
import java.util.UUID;

public class HWIDManager {

    private static String cachedHwid;

    /**
     * Device-HWID v2 (2026-09): primaer aus der Widevine-Hardware-ID (MediaDrm)
     * abgeleitet — ueberlebt Werksreset und Reinstall, anders als ANDROID_ID.
     * Fallback: die alte ANDROID_ID+Build-Formel (Emulatoren, ROMs ohne Widevine).
     * Liefert wie bisher den ROHEN SHA-256-Hexstring (64 Zeichen, ohne Praefix);
     * das "KODA-"+Kuerzen macht der app_uuid-Aufrufer, Ban-RPCs nutzen ihn roh.
     */
    public static String getDeviceHWID(Context context) {
        if (cachedHwid != null) return cachedHwid;
        String hwid = null;
        try {
            hwid = widevineHwid();
        } catch (Throwable t) {
            // Kein Widevine verfuegbar (Emulator, exotisches ROM) -> Legacy-Fallback
        }
        if (hwid == null) {
            hwid = legacyHwid(context);
        }
        cachedHwid = hwid;
        return hwid;
    }

    private static String widevineHwid() throws android.media.UnsupportedSchemeException {
        // Standard-Widevine-System-ID: edef8ba9-79d6-4ace-a3c8-27dcd51d21ed
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
