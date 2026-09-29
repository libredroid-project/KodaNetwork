package eu.kodanetwork.mchost.security;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) — see LICENSE
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