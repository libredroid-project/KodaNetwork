package eu.kodanetwork.mchost.integration;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import android.content.Context;

import java.io.File;

public final class TermuxServerLauncher {
    private TermuxServerLauncher() {}

    public static boolean launch(Context context, String serverDir, int ramMb) {
        try {
            File script = TermuxScriptInstaller.ensureServerStartScript(context);
            return TermuxBridge.runScript(context, script, serverDir, String.valueOf(ramMb));
        } catch (Exception e) {
            return false;
        }
    }
}
