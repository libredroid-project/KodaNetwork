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
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.ModrinthHelper;
import eu.kodanetwork.mchost.util.PaperMCDownloader;

public class UpdateServerActivity extends AppCompatActivity {
    
    private String serverId;
    private ServerInstance server;
    private ServerRepo repo;
    
    private TextView tvMsg;
    private TextView tvSubMsg;
    private Handler handler;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_update_server_m3 : R.layout.activity_update_server);
        
        tvMsg = findViewById(R.id.tv_update_msg);
        tvSubMsg = findViewById(R.id.tv_update_submsg);
        handler = new Handler(Looper.getMainLooper());
        
        android.view.View btnClose = findViewById(R.id.btn_close_update);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
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
        
        startUpdateProcess();
    }
    
    private void setMsg(String msg, String sub) {
        handler.post(() -> {
            tvMsg.setText(msg);
            if (sub != null) tvSubMsg.setText(sub);
        });
    }
    
    private void startUpdateProcess() {
        new Thread(() -> {
            try {
                boolean wasRunning = server.state != ServerInstance.State.OFFLINE && server.state != ServerInstance.State.CRASHED && server.state != ServerInstance.State.HIBERNATED;
                
                server.setUpdating(true);
                repo.update(server);
                
                // 1. running? then wait until it stopped
                if (wasRunning) {
                    setMsg("Stopping Server...", "Waiting for server to go offline.");
                    Intent intent = new Intent(this, KodaServerService.class);
                    intent.setAction(KodaServerService.ACTION_STOP);
                    intent.putExtra(KodaServerService.EXTRA_ID, server.getId());
                    startService(intent);
                    
                    while (server.state != ServerInstance.State.OFFLINE && server.state != ServerInstance.State.CRASHED && server.state != ServerInstance.State.HIBERNATED) {
                        Thread.sleep(1000);
                        server = repo.byId(serverId);
                    }
                }
                
                // 2. plugins
                List<String> projectIds = new ArrayList<>(server.pluginVersions.keySet());
                int updatedPlugins = 0;
                
                for (int i = 0; i < projectIds.size(); i++) {
                    String pid = projectIds.get(i);
                    setMsg("Updating Plugins...", "Checking " + pid + " (" + (i+1) + "/" + projectIds.size() + ")");
                    
                    try {
                        String apiUrl = "https://api.modrinth.com/v2/project/" + pid + "/version";
                        if (server.getType() == ServerInstance.Type.PAPER || server.getType() == ServerInstance.Type.PURPUR) {
                            apiUrl += "?loaders=[\"paper\",\"purpur\",\"spigot\"]&game_versions=[\"" + server.getVersion() + "\"]";
                        } else if (server.getType() == ServerInstance.Type.FABRIC) {
                            apiUrl += "?loaders=[\"fabric\"]&game_versions=[\"" + server.getVersion() + "\"]";
                        } else if (server.getType() == ServerInstance.Type.FORGE || server.getType() == ServerInstance.Type.NEOFORGE) {
                            apiUrl += "?loaders=[\"forge\",\"neoforge\"]&game_versions=[\"" + server.getVersion() + "\"]";
                        } else if (server.getType() == ServerInstance.Type.VELOCITY) {
                            apiUrl += "?loaders=[\"velocity\"]";
                        } else {
                            continue;
                        }
                        
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
                        conn.setRequestProperty("User-Agent", "KodaNetwork/3.0");
                        
                        if (conn.getResponseCode() == 200) {
                            java.io.InputStream is = conn.getInputStream();
                            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                            String result = s.hasNext() ? s.next() : "";
                            is.close();
                            
                            JSONArray versions = new JSONArray(result);
                            if (versions.length() > 0) {
                                JSONObject latest = versions.getJSONObject(0);
                                String latestVersionId = latest.getString("id");
                                
                                if (!latestVersionId.equals(server.pluginVersions.get(pid))) {
                                    setMsg("Updating Plugins...", "Downloading " + pid + " (" + (i+1) + "/" + projectIds.size() + ")");
                                    // deleting the old jar is fiddly, the filename can change, so let ModrinthHelper
                                    // sync it. wiping the whole plugins folder except configs would be dangerous.
                                    // autoDownloadSync overwrites when the filename matches, otherwise duplicates pile
                                    // up. modrinth's files array carries the filename, so old versions could be found by
                                    // matching .jar and project id, but for now the new one is just downloaded.
                                    File newFile = ModrinthHelper.autoDownloadSync(pid, server);
                                    if (newFile != null) {
                                        updatedPlugins++;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                // 3. the server software itself, paper only for now
                if (server.getType() == ServerInstance.Type.PAPER) {
                    try {
                        PaperMCDownloader.downloadLatestPaperSync(server.getVersion(), new File(server.getServerDir()), new PaperMCDownloader.DownloadCallback() {
                            @Override
                            public void onProgress(String message) {
                                setMsg("Updating Server Software...", message);
                            }

                            @Override
                            public void onSuccess(File jarFile) {
                                setMsg("Updating Server Software...", "Done!");
                            }

                            @Override
                            public void onError(String error) {
                                setMsg("Server Update Error", error);
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                server.setUpdating(false);
                repo.update(server);
                
                if (wasRunning) {
                    setMsg("Update complete, starting server...", "Done.");
                    // bring it back up, it was running before the update
                    Intent startIntent = new Intent(this, KodaServerService.class);
                    startIntent.setAction(KodaServerService.ACTION_START);
                    startIntent.putExtra(KodaServerService.EXTRA_ID, server.getId());
                    startService(startIntent);
                } else {
                    setMsg("Update complete.", "Server was offline, left offline.");
                }
                
                handler.postDelayed(this::finish, 2000);
                
            } catch (Exception e) {
                e.printStackTrace();
                setMsg("Error", e.getMessage());
                server.setUpdating(false);
                repo.update(server);
                handler.postDelayed(this::finish, 3000);
            }
        }).start();
    }
}
