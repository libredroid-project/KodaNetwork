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

import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AppLogger {
    public interface Listener {
        void onLogAdded(String line);
    }

    private static final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void log(String tag, String message) {
        String time = sdf.format(new Date());
        String line = "[" + time + "] [" + tag + "] " + message;
        
        android.util.Log.d(tag, message);
        
        synchronized (logs) {
            logs.add(line);
            if (logs.size() > 5000) {
                logs.remove(0);
            }
        }
        
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onLogAdded(line);
            }
        });
    }

    public static void log(String message) {
        log("KodaDebug", message);
    }

    public static String getAllLogs() {
        synchronized (logs) {
            StringBuilder sb = new StringBuilder();
            for (String l : logs) {
                sb.append(l).append("\n");
            }
            return sb.toString();
        }
    }

    public static void addListener(Listener listener) {
        mainHandler.post(() -> listeners.add(listener));
    }

    public static void removeListener(Listener listener) {
        mainHandler.post(() -> listeners.remove(listener));
    }
}
