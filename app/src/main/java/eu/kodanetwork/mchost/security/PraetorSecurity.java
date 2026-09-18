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


public class PraetorSecurity {
    static {
        System.loadLibrary("embeddedjvm");
    }

    public static native String getSupabaseUrl();
    public static native String getSupabaseKey();
    public static native String getOpenRouterKey();
    public static native String getBoreHost();
    public static native String stringFromJNI();
    public static native void startInotifyWatcher(String[] filesToWatch);

    /** VPS-IP zur Laufzeit aus der Build-Konfiguration aufloesen (keine hartcodierte IP im Source). */
    public static String getBoreIp() {
        try {
            return java.net.InetAddress.getByName(getBoreHost()).getHostAddress();
        } catch (Exception e) {
            return getBoreHost();
        }
    }
}