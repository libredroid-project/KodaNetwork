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
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

public class TripwireObserver extends FileObserver {

    private static final String TAG = "TripwireObserver";
    private static TripwireObserver instance;
    private final Context context;

    private TripwireObserver(Context context, String path) {
        // watch for anything that opens or reads the file
        super(path, FileObserver.OPEN | FileObserver.ACCESS);
        this.context = context;
    }

    public static void startWatching(Context context) {
        if (instance != null) return;

        try {
            File honeypot = new File(context.getFilesDir(), "koda_sys_auth_cache.json");
            if (!honeypot.exists()) {
                FileOutputStream fos = new FileOutputStream(honeypot);
                fos.write("{\"warning\":\"do_not_open_tripwire\"}".getBytes());
                fos.close();
            }

            instance = new TripwireObserver(context, honeypot.getAbsolutePath());
            instance.startWatching();
            Log.i(TAG, "Tripwire armed.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to arm tripwire", e);
        }
    }

    @Override
    public void onEvent(int event, String path) {
        if (event == FileObserver.OPEN || event == FileObserver.ACCESS) {
            Log.w(TAG, "Tripwire triggered! Unauthorized local file access detected.");
            // this is a permanent ban, someone poked the honeypot
            AntiTamperSystem.executePermanentBan(context, "FILE_TRIPWIRE_TRIGGERED");
        }
    }
}
