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

    /** resolve the VPS ip at runtime from the build config, no ip hardcoded in the source */
    public static String getBoreIp() {
        try {
            return java.net.InetAddress.getByName(getBoreHost()).getHostAddress();
        } catch (Exception e) {
            return getBoreHost();
        }
    }
}