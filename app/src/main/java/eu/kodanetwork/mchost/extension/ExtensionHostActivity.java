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
package eu.kodanetwork.mchost.extension;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.webkit.WebView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.service.KodaServerService;

/**
 * full screen host for one extension page: Koda top bar and the sandboxed
 * WebView from {@link ExtensionWebHost} underneath.
 */
public class ExtensionHostActivity extends AppCompatActivity {

    private ExtensionRepository.InstalledExtension ext;
    private ExtensionWebHost webHost;
    private boolean bound = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            bound = true;
            KodaServerService service = null;
            try {
                service = ((KodaServerService.LocalBinder) b).get();
            } catch (Throwable ignored) {}
            if (webHost != null) webHost.setService(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            bound = false;
            if (webHost != null) webHost.setService(null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_extension_host_m3 : R.layout.activity_extension_host);
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);

        String id = getIntent() != null ? getIntent().getStringExtra("id") : null;
        ExtensionRepository repo = ExtensionRepository.get(this);
        ext = id == null ? null : repo.byId(id);
        if (ext == null || !ext.hasUi()) {
            Toast.makeText(this, R.string.ext_not_openable, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView title = findViewById(R.id.tv_host_title);
        title.setText(ext.uiTitle == null || ext.uiTitle.isEmpty() ? ext.name : ext.uiTitle);

        findViewById(R.id.btn_back_host).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            finish();
        });
        findViewById(R.id.btn_host_menu).setOnClickListener(this::showMenu);

        WebView webView = findViewById(R.id.web_host);
        webHost = new ExtensionWebHost(this, webView, ext);
        final View error = findViewById(R.id.ll_host_error);
        webHost.setErrorListener(new ExtensionWebHost.ErrorListener() {
            @Override public void onLoadError() {
                if (error != null) error.setVisibility(View.VISIBLE);
            }
            @Override public void onLoadOk() {
                if (error != null) error.setVisibility(View.GONE);
            }
        });

        Intent serviceIntent = new Intent(this, KodaServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
        bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE);

        String target = getIntent() != null ? getIntent().getStringExtra("target") : null;
        webHost.load(ext.uiEntry, target);
    }

    private void showMenu(View anchor) {
        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, getString(R.string.ext_menu_reload));
        if (ext.hasContributions()) {
            pm.getMenu().add(0, 3, 1, getString(R.string.ext_menu_actions));
        }
        pm.getMenu().add(0, 2, 2, getString(R.string.ext_menu_remove));
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (webHost != null) {
                    String target = getIntent() != null ? getIntent().getStringExtra("target") : null;
                    webHost.load(ext.uiEntry, target);
                }
                return true;
            }
            if (item.getItemId() == 3) {
                ExtensionActionsSheet.show(this, ext);
                return true;
            }
            if (item.getItemId() == 2) {
                confirmRemove();
                return true;
            }
            return false;
        });
        pm.show();
    }

    private void confirmRemove() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ext_remove_title)
                .setMessage(R.string.ext_remove_message)
                .setPositiveButton(R.string.ext_remove, (d, w) -> {
                    ExtensionRepository.get(this).uninstall(ext.id);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webHost != null && webHost.canGoBack()) webHost.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (bound) unbindService(conn);
        } catch (Throwable ignored) {}
        bound = false;
        if (webHost != null) webHost.destroy();
        webHost = null;
        super.onDestroy();
    }
}
