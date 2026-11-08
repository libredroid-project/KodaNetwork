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
package eu.kodanetwork.mchost.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import java.io.File;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;

public class PraetorConfirmActivity extends Activity {

    private String action;
    private Button btnConfirm;
    private TextView tvNetworkStatus;
    private TextView tvCountdown;
    private CountDownTimer timer;
    private boolean isConnected = false;
    private com.google.android.material.bottomsheet.BottomSheetDialog sheet;
    private boolean executing = false;
    private TextView tvNetworkLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(0xFF241C18);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF241C18));
        sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.KodaBottomSheetDialog);
        android.view.Window sheetWindow = sheet.getWindow();
        if (sheetWindow != null) {
            sheetWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            sheetWindow.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            sheetWindow.setNavigationBarColor(0xFF241C18);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(sheetWindow, false);
            sheetWindow.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                sheetWindow.setNavigationBarContrastEnforced(false);
            }
        }
        sheet.setContentView(R.layout.sheet_praetor_account_confirm);
        sheet.setCancelable(true);
        sheet.setOnDismissListener(d -> {
            if (!executing) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        action = getIntent().getStringExtra("action");
        if (action == null) action = "";

        TextView tvExplanation = sheet.findViewById(R.id.tv_explanation);
        if ("revoke_tos".equals(action)) {
            tvExplanation.setText("You are about to revoke your acceptance of the Terms of Service. This will permanently delete all your servers and require you to accept the terms again to continue using KodaHosting. This action cannot be undone.");
        } else if ("delete_account".equals(action)) {
            tvExplanation.setText(getString(R.string.praetor_delete_account_explanation));
        } else {
            tvExplanation.setText("You are about to permanently delete all servers from this device. All world data, plugins, and configurations will be completely erased. This action cannot be undone.");
        }

        TextView tvTitle = sheet.findViewById(R.id.tv_praetor_title);
        if (tvTitle != null) {
            String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>";
            tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        TextView tvConfirmTitle = sheet.findViewById(R.id.tv_confirm_title);
        if (tvConfirmTitle != null) {
            if ("revoke_tos".equals(action)) {
                tvConfirmTitle.setText("Revoke the Terms?");
            } else if ("delete_account".equals(action)) {
                tvConfirmTitle.setText("Delete your account?");
            } else {
                tvConfirmTitle.setText("Delete all servers?");
            }
        }

        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 200);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 300);
        }, 300);

        btnConfirm = sheet.findViewById(R.id.btn_confirm);
        android.view.View btnCancel = sheet.findViewById(R.id.btn_cancel);
        tvNetworkStatus = sheet.findViewById(R.id.tv_network_status);
        tvCountdown = sheet.findViewById(R.id.tv_countdown);
        tvNetworkLabel = sheet.findViewById(R.id.tv_network_label);

        btnCancel.setOnClickListener(v -> sheet.dismiss());

        btnConfirm.setOnClickListener(v -> {
            if (timer != null) timer.cancel();

            if (!isConnected) {
                Toast.makeText(this, "Internet connection required to proceed.", Toast.LENGTH_SHORT).show();
                return;
            }

            // spinner up, buttons and countdown out of the way
            sheet.findViewById(R.id.progress_loading).setVisibility(View.VISIBLE);
            sheet.findViewById(R.id.layout_buttons).setVisibility(View.GONE);
            tvCountdown.setVisibility(View.GONE);

            executing = true;
            executeDestructiveAction();
        });

        checkNetworkAndStartTimer();

        sheet.show();
        if (sheet.getWindow() != null) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(sheet.getWindow(), false);
            sheet.getWindow().setNavigationBarColor(0xFF241C18);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                sheet.getWindow().setNavigationBarContrastEnforced(false);
            }
        }
        if (getWindow() != null) {
            getWindow().setNavigationBarColor(0xFF241C18);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                getWindow().setNavigationBarContrastEnforced(false);
            }
        }
        eu.kodanetwork.mchost.util.KodaSheetMotion.reveal(
                sheet.findViewById(R.id.sheet_account_confirm_root));
        android.view.View triangle = sheet.findViewById(R.id.tv_warning_triangle);
        if (triangle != null) triangle.setVisibility(android.view.View.GONE);
    }

    /** the network line only shows up when the connection is actually missing. */
    private void applyNetworkUi(boolean connected) {
        if (tvNetworkLabel != null) {
            tvNetworkLabel.setVisibility(connected ? View.GONE : View.VISIBLE);
        }
        if (tvNetworkStatus != null) {
            tvNetworkStatus.setVisibility(connected ? View.GONE : View.VISIBLE);
            if (!connected) {
                tvNetworkStatus.setText(R.string.praetor_network_missing);
                tvNetworkStatus.setTextColor(0xFFFF5449);
            }
        }
    }

    private void checkNetworkAndStartTimer() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        applyNetworkUi(isConnected);
        tvCountdown.setVisibility(View.GONE);
        btnConfirm.setEnabled(false);

        timer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnConfirm.setText(getString(R.string.praetor_execute_waiting,
                        (int) (millisUntilFinished / 1000)));
                // re-check internet
                NetworkInfo net = cm.getActiveNetworkInfo();
                isConnected = net != null && net.isConnectedOrConnecting();
                applyNetworkUi(isConnected);
            }

            @Override
            public void onFinish() {
                if (isConnected) {
                    btnConfirm.setEnabled(true);
                    btnConfirm.setText("EXECUTE");
                    applyNetworkUi(true);
                    eu.kodanetwork.mchost.util.KodaSheetMotion.pop(btnConfirm);
                } else {
                    applyNetworkUi(false);
                    // check again in 1 second if still waiting
                    new android.os.Handler().postDelayed(() -> checkNetworkAndStartTimer(), 1000);
                }
            }
        }.start();
    }

    /** removes the login only, servers and files are kept on purpose. */
    private void deleteAccountOnly() {
        btnConfirm.setEnabled(false);
        eu.kodanetwork.mchost.network.supabase.SupabaseAuth.deleteUser(this,
                new eu.kodanetwork.mchost.network.supabase.SupabaseAuth.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            eu.kodanetwork.mchost.network.supabase.SupabaseAuth.logout(PraetorConfirmActivity.this);
                            Toast.makeText(PraetorConfirmActivity.this,
                                    R.string.account_deleted, Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(PraetorConfirmActivity.this, MainActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String msg) {
                        runOnUiThread(() -> {
                            btnConfirm.setEnabled(true);
                            Toast.makeText(PraetorConfirmActivity.this,
                                    getString(R.string.account_delete_failed, msg), Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void executeDestructiveAction() {
        // the account alone: no server is touched, they stay on the phone
        if ("delete_account".equals(action)) {
            deleteAccountOnly();
            return;
        }

        ServerRepo repo = ServerRepo.get(this);
        List<ServerInstance> allServers = repo.all();
        
        new Thread(() -> {
            String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
            android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(PraetorConfirmActivity.this);
            String token = prefs.getString("koda_session_token", null);
            String authHeader = token != null ? "Bearer " + token : "Bearer " + anonKey;

            for (ServerInstance srv : new java.util.ArrayList<>(allServers)) {
                // 1. kill the service first, the process has to die while it is still in the repo
                if (srv.state != ServerInstance.State.OFFLINE) {
                    android.content.Intent intent = new android.content.Intent(PraetorConfirmActivity.this, eu.kodanetwork.mchost.service.KodaServerService.class);
                    intent.setAction(eu.kodanetwork.mchost.service.KodaServerService.ACTION_KILL);
                    intent.putExtra(eu.kodanetwork.mchost.service.KodaServerService.EXTRA_ID, srv.getId());
                    startService(intent);
                    int retries = 0;
                    while (srv.state != ServerInstance.State.OFFLINE && retries < 20) {
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                        retries++;
                    }
                }

                // 2. delete the DNS link
                try {
                    if (srv.getSubdomain() != null && !srv.getSubdomain().isEmpty()) {
                        new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(PraetorConfirmActivity.this)
                            .deleteDnsLink("", srv.getSubdomain(), srv.getBaseDomain());
                    }
                } catch (Exception e) {
                    android.util.Log.e("PraetorConfirm", "Failed to delete DNS link", e);
                }
                
                // 3. PATCH host and server_version so the server stops showing up in the lobby
                try {
                    java.net.HttpURLConnection patchConn = (java.net.HttpURLConnection) new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_admin_patch_server").openConnection();
                    patchConn.setRequestMethod("POST");
                    patchConn.setRequestProperty("apikey", anonKey);
                    patchConn.setRequestProperty("Authorization", authHeader);
                    patchConn.setRequestProperty("Content-Type", "application/json");
                    patchConn.setDoOutput(true);
                    String adminAppUuid = eu.kodanetwork.mchost.App.getPrefs(PraetorConfirmActivity.this).getString("app_uuid", "");
                    String adminToken = eu.kodanetwork.mchost.App.getPrefs(PraetorConfirmActivity.this).getString("device_token", "");
                    String jsonPatch = "{\"p_admin_app_uuid\": \"" + adminAppUuid + "\", \"p_admin_device_token\": \"" + adminToken + "\", \"p_target_host\": \"" + srv.getSubdomain() + "\", \"p_payload\": {\"host\": \"deleted_" + srv.getSubdomain() + "\", \"server_version\": \"DELETED\"}}";
                    patchConn.getOutputStream().write(jsonPatch.getBytes());
                    patchConn.getResponseCode();
                } catch (Exception e) {
                    android.util.Log.e("PraetorConfirm", "Failed to patch server", e);
                }

                // 4. Delete Local Files (the tombstone row DELETE is locked since the security fix
                //    and not needed: deleted_ + DELETED hide the server everywhere)

                // 5. delete local files
                File dir = new File(srv.getServerDir());
                if (dir.exists()) {
                    deleteRecursive(dir, true);
                }
                repo.delete(srv.getId());
            }

            runOnUiThread(() -> {
                Toast.makeText(PraetorConfirmActivity.this, "All servers deleted.", Toast.LENGTH_SHORT).show();

                if ("revoke_tos".equals(action)) {
                    eu.kodanetwork.mchost.App.getPrefs(PraetorConfirmActivity.this).edit()
                        .putBoolean("tos_accepted", false)
                        .putBoolean("tos_accepted_v2", false)
                        .putBoolean("tos_accepted_v3", false).apply();
                    
                    Intent intent = new Intent(PraetorConfirmActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                } else {
                    Intent intent = new Intent(PraetorConfirmActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                }
                
                finish();
            });
        }).start();
    }

    private void deleteRecursive(File fileOrDirectory, boolean deleteRoot) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child, true);
                }
            }
        }
        if (deleteRoot) {
            fileOrDirectory.delete();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
        }
    }
}
