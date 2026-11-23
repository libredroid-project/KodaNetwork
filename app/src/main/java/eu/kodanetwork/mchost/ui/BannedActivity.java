/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
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
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.KodaSheet;
import eu.kodanetwork.mchost.utils.HibernationManager;
import eu.kodanetwork.mchost.utils.ZipUtils;

/**
 * the ban screen. reason and appeal state come from rpc_get_ban_appeal, so the screen
 * shows exactly what staff set: normal ban with one appeal, a 7 day cooldown after a
 * rejection, or a locked account where appeals are closed completely.
 */
public class BannedActivity extends AppCompatActivity {

    private TextView reasonText;
    private TextView appealText;
    private TextView serversNote;
    private MaterialButton appealButton;
    private ImageButton chatButton;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private boolean appealLoading;
    private boolean hibernateTriggered;
    private String appealTicketId = "";
    private String appealStatus = "";
    private String appealTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_banned);

        TextView mark = findViewById(R.id.bannedWordmark);
        if (mark != null) {
            mark.setText(android.text.Html.fromHtml(
                    "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>",
                    android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        TextView hwidText = findViewById(R.id.bannedHwidText);
        hwidText.setText(getString(R.string.ban_hwid_label) + " · " + uuid);

        reasonText = findViewById(R.id.bannedReasonText);
        appealText = findViewById(R.id.bannedAppealText);
        serversNote = findViewById(R.id.bannedServersNote);
        appealButton = findViewById(R.id.bannedAppealButton);
        appealButton.setOnClickListener(v -> showAppealSheet());
        chatButton = findViewById(R.id.bannedChatButton);
        chatButton.setOnClickListener(v -> openAppealChat());
        findViewById(R.id.bannedServersButton).setOnClickListener(v -> showServersSheet());

        String reason = getIntent().getStringExtra("reason");
        if (reason == null) {
            reason = eu.kodanetwork.mchost.App.getPrefs(this).getString("BANNED_REASON", null);
        }
        if (reason != null && !reason.isEmpty()) {
            reasonText.setText(reason);
        }

        // the section stays empty until the server answered. a banned device always
        // has its device token, so this is the same identity that owns the ticket.
        appealText.setText("");
        appealButton.setVisibility(View.GONE);
        loadAppealState();
    }

    private void loadAppealState() {
        if (appealLoading) return;
        final String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        final String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
        if (uuid == null || uuid.isEmpty() || token == null || token.isEmpty()) {
            // a local-only ban (emulator) has no server ticket to appeal against
            showFallback();
            return;
        }

        appealLoading = true;
        new Thread(() -> {
            JSONObject info = null;
            try {
                JSONObject body = new JSONObject();
                body.put("p_app_uuid", uuid);
                body.put("p_device_token", token);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_ban_appeal", "POST", body.toString(), null);
                info = new JSONObject(response);
            } catch (Exception ignored) {
                // offline or RPC unavailable, fall back to what the device already knows
            }
            final JSONObject result = info;
            runOnUiThread(() -> {
                appealLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (result != null) {
                    applyAppealState(result);
                } else {
                    showFallback();
                }
            });
        }, "KodaBanAppealState").start();
    }

    /** while an appeal is open, check again so an approval lets the device back in. */
    private void schedulePoll() {
        if (pollRunnable != null) pollHandler.removeCallbacks(pollRunnable);
        pollRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) loadAppealState();
        };
        pollHandler.postDelayed(pollRunnable, 30000);
    }

    private void showFallback() {
        boolean knownServerBan = eu.kodanetwork.mchost.App.getPrefs(this)
                .getBoolean("is_banned_user", false);
        if (knownServerBan) {
            appealText.setVisibility(View.VISIBLE);
            appealText.setText(R.string.ban_appeal_default);
            appealButton.setVisibility(View.VISIBLE);
        } else {
            appealText.setVisibility(View.GONE);
            appealButton.setVisibility(View.GONE);
        }
    }

    private void applyAppealState(JSONObject info) {
        if (!info.optBoolean("banned", false)) {
            // a server ban that just disappeared means staff approved the appeal. clear
            // the local flag and go back in instead of leaving the user on this screen.
            if (eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("is_banned_user", false)) {
                eu.kodanetwork.mchost.App.getPrefs(this).edit()
                        .putBoolean("is_banned_user", false)
                        .remove("BANNED_REASON")
                        .apply();
                Toast.makeText(this, R.string.ban_lifted, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
            // a local anti-tamper ban has no support case; keep the reason and hide it
            appealText.setVisibility(View.GONE);
            appealButton.setVisibility(View.GONE);
            return;
        }

        String serverReason = info.optString("ban_reason", "");
        if (!serverReason.isEmpty() && !"null".equals(serverReason)) {
            reasonText.setText(serverReason);
        }
        appealText.setVisibility(View.VISIBLE);

        appealTicketId = clean(info.optString("appeal_ticket_id", ""));
        appealStatus = clean(info.optString("appeal_status", ""));
        appealTitle = clean(info.optString("appeal_title", ""));
        chatButton.setVisibility(appealTicketId.isEmpty() ? View.GONE : View.VISIBLE);

        if (info.optBoolean("hibernate_servers", false)) {
            serversNote.setVisibility(View.VISIBLE);
            hibernateLocalServers();
        }

        if (info.optBoolean("tickets_disabled", false) || info.optBoolean("appeals_disabled", false)) {
            appealText.setText(R.string.ban_appeal_locked);
            appealButton.setVisibility(View.GONE);
            return;
        }
        if (info.optBoolean("appeal_open", false)) {
            appealText.setText(R.string.ban_appeal_open);
            appealButton.setVisibility(View.GONE);
            schedulePoll();
            return;
        }

        long availableAt = parseTime(info.optString("appeal_available_at", ""));
        if (availableAt > System.currentTimeMillis()) {
            appealText.setText(getString(R.string.ban_appeal_rejected, formatDate(availableAt)));
            appealButton.setVisibility(View.GONE);
            return;
        }

        appealText.setText(R.string.ban_appeal_default);
        appealButton.setVisibility(View.VISIBLE);
    }

    private void showAppealSheet() {
        KodaSheet sheet = KodaSheet.create(this,
                getString(R.string.ban_appeal_title),
                getString(R.string.ban_appeal_subtitle)).lockSheet();

        EditText input = sheet.multiLineField("", getString(R.string.ban_appeal_hint), 5);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        sheet.add(input, 150);

        sheet.secondary(getString(android.R.string.cancel), v -> sheet.sheet.dismiss());
        final MaterialButton[] sendHolder = new MaterialButton[1];
        MaterialButton send = sheet.primary(getString(R.string.ban_appeal_send), v -> {
            String message = input.getText().toString().trim();
            if (message.length() < 10) {
                Toast.makeText(this, R.string.ban_appeal_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            sendHolder[0].setEnabled(false);
            submitAppeal(sheet, sendHolder[0], message);
        });
        sendHolder[0] = send;
        sheet.show();
    }

    private void submitAppeal(KodaSheet sheet, MaterialButton send, String message) {
        final String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        final String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");

        new Thread(() -> {
            String error = null;
            try {
                JSONObject ticket = new JSONObject();
                ticket.put("p_reporter_uuid", uuid);
                ticket.put("p_device_token", token);
                ticket.put("p_ticket_type", "UNBAN");
                ticket.put("p_reference_id", "");
                // the first 150 chars ride in the title, so the team sees the appeal
                // even if the message call fails afterwards
                String title = message.length() > 150 ? message.substring(0, 150) : message;
                ticket.put("p_title", title);

                String created = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_create_ticket", "POST", ticket.toString(), null);
                String ticketId = created == null ? "" : created.replace("\"", "").trim();
                if (ticketId.isEmpty() || ticketId.startsWith("{")) {
                    error = created == null ? "" : created;
                } else {
                    JSONObject msg = new JSONObject();
                    msg.put("p_ticket_id", ticketId);
                    msg.put("p_sender_uuid", uuid);
                    msg.put("p_device_token", token);
                    msg.put("p_message", message);
                    String msgResponse = SupportApi.makeSupabaseRequest(
                            "rest/v1/rpc/rpc_create_ticket_message", "POST", msg.toString(), null);
                    // a void rpc answers with an empty body, an error is a json object
                    if (msgResponse != null && msgResponse.trim().startsWith("{")) {
                        error = msgResponse;
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? "failed" : e.getMessage();
            }

            final String failure = error;
            runOnUiThread(() -> {
                if (failure == null) {
                    sheet.sheet.dismiss();
                    Toast.makeText(this, R.string.ban_appeal_sent, Toast.LENGTH_LONG).show();
                    appealText.setText(R.string.ban_appeal_open);
                    appealButton.setVisibility(View.GONE);
                    // the decision can arrive while this screen is open
                    schedulePoll();
                    return;
                }
                send.setEnabled(true);
                if (failure.contains("tickets_disabled") || failure.contains("appeals_disabled")) {
                    sheet.sheet.dismiss();
                    appealText.setText(R.string.ban_appeal_locked);
                    appealButton.setVisibility(View.GONE);
                } else if (failure.contains("appeal_already_open")) {
                    sheet.sheet.dismiss();
                    appealText.setText(R.string.ban_appeal_open);
                    appealButton.setVisibility(View.GONE);
                } else if (failure.contains("appeal_cooldown")) {
                    sheet.sheet.dismiss();
                    Toast.makeText(this, R.string.ban_appeal_cooldown_short, Toast.LENGTH_LONG).show();
                    loadAppealState();
                } else if (failure.contains("not_banned")) {
                    sheet.sheet.dismiss();
                    Toast.makeText(this, R.string.ban_appeal_not_banned, Toast.LENGTH_LONG).show();
                    appealText.setVisibility(View.GONE);
                    appealButton.setVisibility(View.GONE);
                } else {
                    Toast.makeText(this, R.string.ban_appeal_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "KodaBanAppeal").start();
    }

    private void openAppealChat() {
        if (appealTicketId == null || appealTicketId.isEmpty()) return;
        Intent intent = new Intent(this, SupportChatActivity.class);
        intent.putExtra("TICKET_ID", appealTicketId);
        intent.putExtra("TICKET_TITLE", appealTitle.isEmpty()
                ? getString(R.string.ban_appeal_title) : appealTitle);
        intent.putExtra("TICKET_STATUS", appealStatus);
        intent.putExtra("TICKET_TYPE", "UNBAN");
        startActivity(intent);
    }

    /**
     * the servers have their own menu on this screen. while a ban is active a server
     * can only be downloaded, never started; hibernated ones are exported from their
     * existing zip.
     */
    private void showServersSheet() {
        List<ServerInstance> servers = ServerRepo.get(this).all();
        KodaSheet sheet = KodaSheet.create(this,
                getString(R.string.ban_servers_title),
                getString(R.string.ban_servers_subtitle)).tall();

        if (servers.isEmpty()) {
            sheet.text(getString(R.string.ban_servers_empty), 0xFF8A8A9A);
        } else {
            for (ServerInstance server : servers) {
                String meta = server.state == ServerInstance.State.HIBERNATED
                        ? getString(R.string.ban_servers_hibernated)
                        : (server.getSubdomain().isEmpty()
                            ? server.state.name() : server.getSubdomain());
                sheet.row(server.getName(), meta, false, v -> {
                    sheet.sheet.dismiss();
                    exportServer(server);
                });
            }
        }
        sheet.show();
    }

    private void exportServer(ServerInstance server) {
        Toast.makeText(this, getString(R.string.ban_servers_zipping, server.getName()),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists()) downloads.mkdirs();

                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                String safeName = server.getName() == null ? "server"
                        : server.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
                File target = new File(downloads, safeName + "_" + stamp + ".zip");

                File dir = server.getServerDir() == null ? null : new File(server.getServerDir());
                File hibernated = dir == null ? null
                        : new File(dir.getParentFile(), server.getId() + "_hibernated.zip");

                if (server.state == ServerInstance.State.HIBERNATED
                        && hibernated != null && hibernated.exists()) {
                    java.nio.file.Files.copy(hibernated.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (dir != null && dir.exists()) {
                    ZipUtils.zipFolderFlat(dir.getAbsolutePath(), target.getAbsolutePath());
                } else {
                    throw new Exception("server files are missing");
                }

                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.ban_servers_exported, target.getName()),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "unknown" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.ban_servers_export_failed, message),
                        Toast.LENGTH_LONG).show());
            }
        }, "KodaBanExport").start();
    }

    /**
     * bans of 14 days or more hibernate the local servers. running ones are stopped
     * first; the stop intent lets the service finish its shutdown before the normal
     * hibernate path zips the folder away.
     */
    private void hibernateLocalServers() {
        if (hibernateTriggered) return;
        hibernateTriggered = true;
        new Thread(() -> {
            ServerRepo repo = ServerRepo.get(this);
            for (ServerInstance server : repo.all()) {
                if (server.state == ServerInstance.State.HIBERNATED) continue;
                if (server.state == ServerInstance.State.ONLINE
                        || server.state == ServerInstance.State.STARTING
                        || server.state == ServerInstance.State.STOPPING
                        || server.state == ServerInstance.State.RESTARTING) {
                    Intent stop = new Intent(this, KodaServerService.class);
                    stop.setAction(KodaServerService.ACTION_STOP);
                    stop.putExtra(KodaServerService.EXTRA_ID, server.getId());
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(stop);
                    else startService(stop);
                    continue;
                }
                try {
                    HibernationManager.hibernateServer(this, server, repo);
                } catch (Exception e) {
                    android.util.Log.w("KodaBan", "hibernate failed for "
                            + server.getName() + ": " + e.getMessage());
                }
            }
        }, "KodaBanHibernate").start();
    }

    private static String clean(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    /** parses the ISO timestamp from postgres; 0 when it is empty or unreadable. */
    private long parseTime(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String formatDate(long millis) {
        return android.text.format.DateFormat.getMediumDateFormat(this)
                .format(new java.util.Date(millis));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pollRunnable != null) pollHandler.removeCallbacks(pollRunnable);
    }

    @Override
    public void onBackPressed() {
        // a ban is meant to stick, so back leads nowhere
    }
}
