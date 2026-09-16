package eu.kodanetwork.mchost.integration;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
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
