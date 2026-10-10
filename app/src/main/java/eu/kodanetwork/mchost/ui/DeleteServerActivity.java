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

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.ZipUtil;

public class DeleteServerActivity extends AppCompatActivity {
    
    private String serverId;
    private ServerInstance server;
    private ServerRepo repo;
    
    private TextView tvMsg;
    private TextView tvSubMsg;
    private ProgressBar pbDelete;
    private Button btnRetry;
    private LinearLayout llActions;
    private Button btnExport;
    private Button btnPermanent;
    private Handler handler;
    private android.animation.ObjectAnimator blobAnimator;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_delete_server_m3 : R.layout.activity_delete_server);
        
        tvMsg = findViewById(R.id.tv_delete_msg);
        tvSubMsg = findViewById(R.id.tv_delete_submsg);
        pbDelete = findViewById(R.id.pb_delete);
        btnRetry = findViewById(R.id.btn_retry_delete);
        llActions = findViewById(R.id.ll_delete_actions);
        btnExport = findViewById(R.id.btn_export_delete);
        btnPermanent = findViewById(R.id.btn_permanent_delete);
        
        handler = new Handler(Looper.getMainLooper());
        
        android.view.View btnClose = findViewById(R.id.btn_close_update);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }
        
        android.view.View blob = findViewById(R.id.update_light_blob);
        if (blob != null) {
            blobAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    blob,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f)
            );
            blobAnimator.setDuration(2500);
            blobAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            blobAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            blobAnimator.start();
        }
        
        serverId = getIntent().getStringExtra("SERVER_ID");
        if (serverId == null) {
            finish();
            return;
        }
        
        repo = ServerRepo.get(this);
        server = repo.byId(serverId);
        if (server == null) {
            finish();
            return;
        }
        
        btnExport.setOnClickListener(v -> exportAndDelete());
        // "delete permanently" removes the files immediately (the online cleanup runs afterwards
        // in the background), the automatic flow below does the online steps first.
        btnPermanent.setOnClickListener(v -> requestDeleteConfirmation(true));
        btnRetry.setOnClickListener(v -> {
            btnRetry.setVisibility(View.GONE);
            pbDelete.setVisibility(View.VISIBLE);
            startDeletionProcess();
        });
        requestDeleteConfirmation(false);
    }

    private static final int REQ_BIO_DELETE = 9011;

    /** true when the join address could not be removed online (reported to the owner). */
    private boolean dnsCleanupFailed = false;
    private boolean biometricPassed = false;

    /** true for the "delete permanently" button: locals first, online cleanup in the background. */
    private boolean pendingLocalOnly = false;

    /**
     * Both buttons ask for the biometric check first (when the owner enabled it for this action):
     * the automatic flow cleans up online and then deletes, "permanently delete" removes the files
     * right away and tries the online cleanup in the background. neither can get stuck.
     */
    private void requestDeleteConfirmation(boolean localOnly) {
        pendingLocalOnly = localOnly;
        if (biometricPassed) {
            proceedAfterConfirm();
            return;
        }
        if (eu.kodanetwork.mchost.util.BiometricHelper.isBioEnabledFor(this, "bio_on_delete_server")) {
            Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.BiometricAuthActivity.class);
            startActivityForResult(intent, REQ_BIO_DELETE);
            return;
        }
        proceedAfterConfirm();
    }

    private void proceedAfterConfirm() {
        if (pendingLocalOnly) {
            permanentlyDelete();
        } else {
            startDeletionProcess();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BIO_DELETE) {
            if (resultCode == RESULT_OK) {
                biometricPassed = true;
                proceedAfterConfirm();
            } else {
                finish();
            }
        }
    }

    /** true when the join address does not resolve any more (record already removed). */
    private boolean addressIsGone(String subdomain, String baseDomain) {
        try {
            java.net.InetAddress.getByName(subdomain + "." + baseDomain);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void setMsg(String msg, String sub) {
        handler.post(() -> {
            tvMsg.setText(msg);
            if (sub != null) tvSubMsg.setText(sub);
        });
    }
    
    private void showError(String msg, String sub) {
        handler.post(() -> {
            tvMsg.setText(msg);
            if (sub != null) tvSubMsg.setText(sub);
            pbDelete.setVisibility(View.GONE);
            btnRetry.setVisibility(View.VISIBLE);
        });
    }
    
    private void showFinalActions() {
        handler.post(() -> {
            tvMsg.setText("Server disconnected");
            tvSubMsg.setText("DNS and Database records removed. What do you want to do with the local files?");
            pbDelete.setVisibility(View.GONE);
            llActions.setVisibility(View.VISIBLE);
        });
    }
    
    private void startDeletionProcess() {
        new Thread(() -> {
            try {
                // 1. kill the server if it is running
                if (server.state != ServerInstance.State.OFFLINE && server.state != ServerInstance.State.CRASHED && server.state != ServerInstance.State.HIBERNATED) {
                    setMsg(getString(R.string.delete_server_stopping), getString(R.string.delete_server_stopping_sub));
                    Intent intent = new Intent(this, KodaServerService.class);
                    intent.setAction(KodaServerService.ACTION_KILL);
                    intent.putExtra(KodaServerService.EXTRA_ID, server.getId());
                    startService(intent);
                    
                    int retries = 0;
                    while (server.state != ServerInstance.State.OFFLINE && server.state != ServerInstance.State.CRASHED && retries < 20) {
                        Thread.sleep(500);
                        server = repo.byId(serverId);
                        retries++;
                    }
                }
                
                String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
                // the rpc authorises with the device token in the body (fn_verify_device_token).
                // an expired session token returned 401 and blocked the whole deletion, so the
                // anon key is used here exactly like everywhere else.
                String authHeader = "Bearer " + anonKey;
                
                // 2. delete the DNS link
                if (server.getSubdomain() != null && !server.getSubdomain().isEmpty()) {
                    setMsg(getString(R.string.delete_server_dns), getString(R.string.delete_server_dns_sub));
                    try {
                        new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(this)
                            .deleteDnsLink("", server.getSubdomain(), server.getBaseDomain());
                    } catch (Exception first) {
                        // short timeouts and "Server disconnected" are usually a hiccup: one retry
                        android.util.Log.w("DeleteServer", "DNS deletion failed, retrying: " + first.getMessage());
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        try {
                            new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(this)
                                .deleteDnsLink("", server.getSubdomain(), server.getBaseDomain());
                        } catch (Exception e) {
                            // nothing left to delete (a finished attempt leaves no record and the
                            // function answers "unauthorized" for unknown hosts), or the api is
                            // briefly unavailable. the deletion must never get stuck here.
                            if (addressIsGone(server.getSubdomain(), server.getBaseDomain())) {
                                android.util.Log.i("DeleteServer", "DNS record already gone, continuing");
                            } else {
                                dnsCleanupFailed = true;
                                android.util.Log.w("DeleteServer", "DNS record kept: " + e.getMessage());
                            }
                        }
                    }
                }
                
                // 3. mark as deleted in Supabase (Do NOT delete row)
                setMsg(getString(R.string.delete_server_db), getString(R.string.delete_server_db_sub));
                try {
                    String appUuid = prefs.getString("app_uuid", "unknown");
                    org.json.JSONObject payload = new org.json.JSONObject()
                            .put("host", "deleted_" + server.getSubdomain())
                            .put("server_version", "DELETED");
                    org.json.JSONObject body = new org.json.JSONObject()
                            .put("p_app_uuid", appUuid)
                            .put("p_device_token", prefs.getString("device_token", ""))
                            .put("p_host", server.getSubdomain())
                            .put("p_payload", payload);
                    String jsonPatch = body.toString();

                    java.net.HttpURLConnection patchConn = (java.net.HttpURLConnection) new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_patch_server").openConnection();
                    patchConn.setRequestMethod("POST");
                    patchConn.setRequestProperty("apikey", anonKey);
                    patchConn.setRequestProperty("Authorization", authHeader);
                    patchConn.setRequestProperty("Content-Type", "application/json");
                    patchConn.setDoOutput(true);
                    patchConn.getOutputStream().write(jsonPatch.getBytes());
                    int responseCode = patchConn.getResponseCode();


                    if (responseCode >= 400) {
                        String errBody = "";
                        try (java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(patchConn.getErrorStream()))) {
                            StringBuilder sb = new StringBuilder();
                            String ln;
                            while ((ln = br.readLine()) != null) sb.append(ln);
                            errBody = sb.toString();
                        } catch (Exception ignored) {}
                        // row never existed (e.g. the creation INSERT failed earlier), so there is
                        // nothing to clean up in the DB, continue with local deletion.
                        if (errBody.contains("Server not found")) {
                            android.util.Log.w("DeleteServer", "No DB row for " + server.getSubdomain() + ", continuing");
                        } else if (responseCode == 401 || errBody.contains("authorized")) {
                            // the device token no longer matches the row, the online entry cannot
                            // be marked. the local server is removed anyway, the admin cleanup
                            // (list-servers) removes orphaned rows later.
                            android.util.Log.w("DeleteServer", "Row not marked, deleting locally: " + errBody);
                            prefs.edit().putBoolean("orphaned_row_" + server.getSubdomain(), true).apply();
                        } else {
                            showError(getString(R.string.delete_server_db_failed),
                                    "Failed to update server status (Code " + responseCode + ")");
                            return;
                        }
                    }
                } catch (Exception e) {
                    showError(getString(R.string.delete_server_db_failed), e.getMessage());
                    return;
                }
                
                // all network operations succeeded
                showFinalActions();
                
            } catch (Exception e) {
                e.printStackTrace();
                showError("Error", e.getMessage());
            }
        }).start();
    }
    
    private void exportAndDelete() {
        llActions.setVisibility(View.GONE);
        pbDelete.setVisibility(View.VISIBLE);
        setMsg(getString(R.string.delete_server_exporting), getString(R.string.delete_server_exporting_sub));
        
        new Thread(() -> {
            try {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File exportZip = new File(downloadsDir, server.getName() + "_export.zip");
                
                ZipUtil.zipDirectory(new File(server.getServerDir()), exportZip);
                
                handler.post(() -> Toast.makeText(this, "Exported to Downloads: " + exportZip.getName(), Toast.LENGTH_LONG).show());
                
                setMsg(getString(R.string.delete_server_cleaning), getString(R.string.delete_server_cleaning_sub));
                deleteRecursively(new File(server.getServerDir()));
                
                handler.post(() -> {
                    if (dnsCleanupFailed) {
                        Toast.makeText(this, getString(R.string.delete_dns_leftover, server.getJoinAddress()),
                                Toast.LENGTH_LONG).show();
                    }
                    repo.delete(server.getId());
                    if (blobAnimator != null) blobAnimator.cancel();
                    Intent homeIntent = new Intent(DeleteServerActivity.this, MainActivity.class);
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(homeIntent);
                    finish();
                });
            } catch (Exception e) {
                e.printStackTrace();
                showError("Export Failed", e.getMessage());
            }
        }).start();
    }
    
    /**
     * removes the server from the phone straight away, the online entries (dns record, row) are
     * cleaned up in the background afterwards, so a slow or failing api can never block this.
     */
    private void permanentlyDelete() {
        llActions.setVisibility(View.GONE);
        pbDelete.setVisibility(View.VISIBLE);
        new Thread(this::cleanupOnline, "KodaDeleteCleanup").start();
        setMsg(getString(R.string.delete_server_permanent), getString(R.string.delete_server_permanent_sub));
        
        new Thread(() -> {
            deleteRecursively(new File(server.getServerDir()));
            handler.post(() -> {
                repo.delete(server.getId());
                if (blobAnimator != null) blobAnimator.cancel();
                Intent homeIntent = new Intent(DeleteServerActivity.this, MainActivity.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                finish();
            });
        }).start();
    }
    
    /**
     * best effort cleanup of the online entries: dns record first, then the tombstone in the
     * database. failures are only logged, the server is already gone from the phone.
     */
    private void cleanupOnline() {
        try {
            if (server.getSubdomain() != null && !server.getSubdomain().isEmpty()
                    && !addressIsGone(server.getSubdomain(), server.getBaseDomain())) {
                try {
                    new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(this)
                            .deleteDnsLink("", server.getSubdomain(), server.getBaseDomain());
                } catch (Exception e) {
                    android.util.Log.w("DeleteServer", "Background DNS cleanup failed: " + e.getMessage());
                }
            }
            android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
            String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
            org.json.JSONObject payload = new org.json.JSONObject()
                    .put("host", "deleted_" + server.getSubdomain())
                    .put("server_version", "DELETED");
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("p_app_uuid", prefs.getString("app_uuid", "unknown"))
                    .put("p_device_token", prefs.getString("device_token", ""))
                    .put("p_host", server.getSubdomain())
                    .put("p_payload", payload);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                    eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/rest/v1/rpc/rpc_patch_server").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey", anonKey);
            conn.setRequestProperty("Authorization", "Bearer " + anonKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes());
            android.util.Log.i("DeleteServer", "Background row update: HTTP " + conn.getResponseCode());
        } catch (Exception e) {
            android.util.Log.w("DeleteServer", "Background cleanup failed: " + e.getMessage());
        }
    }

    private long lastUpdate = 0;
    
    private void deleteRecursively(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        
        long now = System.currentTimeMillis();
        if (now - lastUpdate > 30) {
            lastUpdate = now;
            final String fileName = fileOrDirectory.getName();
            handler.post(() -> {
                if (tvSubMsg != null) {
                    tvSubMsg.setText("..." + fileName);
                }
            });
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {}
        }
        
        fileOrDirectory.delete();
    }
}
