package eu.kodanetwork.mchost.service;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) — see LICENSE
 */


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent si = new Intent(ctx, KodaServerService.class);
            try {
                ctx.startService(si);
            } catch (Exception e) {
                // Ignore background start restrictions if no server is running
            }
        }
    }
}
