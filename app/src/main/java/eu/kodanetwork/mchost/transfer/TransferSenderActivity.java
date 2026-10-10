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
package eu.kodanetwork.mchost.transfer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.util.BackupManager;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * sender side of the device transfer (the OLD device).
 *
 * flow: stop the running servers -> zip each server (backup skip rules) -> hash
 * -> AES-GCM encrypt into the cache -> serve the blobs over the token gated mini
 * HTTP server in the local network -> publish the pairing row through
 * rpc_create_transfer -> show the 8 character code until the receiver calls
 * rpc_finish_transfer, then delete the local copies.
 *
 * Supabase only ever sees the pairing metadata. the server data stays on the
 * local wireless link, encrypted with a key that travels through the RPC row.
 */
public class TransferSenderActivity extends AppCompatActivity {

    public static final String EXTRA_SERVER_IDS = "serverIds";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerRepo repo;
    private TransferHttpServer httpServer;
    private TransferSession session;
    private String chosenTarget;   // family device the user picked, null = any device via code
    private String lastTargetLabel; // display name of the picked device, for the hint text
    private List<ServerInstance> transferServers = new ArrayList<>();
    private File cacheDir;
    private volatile boolean polling;
    private final List<ProgressBar> rowBars = new ArrayList<>();
    private final List<TextView> rowTexts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_transfer_sender);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        repo = ServerRepo.get(this);
        cacheDir = new File(getCacheDir(), "transfer");

        // packing card: the dark rounded card, its cancel ends the whole transfer
        android.view.View card = findViewById(R.id.card_packing);
        if (card != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
            bg.setColor(0xFF241C18);
            card.setBackground(bg);
        }
        android.view.View packCancel = findViewById(R.id.tv_pack_cancel);
        if (packCancel != null) {
            packCancel.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 30);
                finish();
            });
        }

        findViewById(R.id.btn_transfer_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_transfer_done).setOnClickListener(v -> finish());
        findViewById(R.id.btn_transfer_restart).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            if (session == null) return;
            status(getString(R.string.transfer_status_publishing));
            new Thread(() -> {
                if (publishTransferRow(chosenTarget)) {
                    runOnUiThread(() -> {
                        findViewById(R.id.btn_transfer_restart).setVisibility(View.GONE);
                        status(getString(R.string.transfer_status_waiting));
                    });
                } else {
                    status(getString(R.string.transfer_status_no_row));
                }
            }, "KodaTransferRepublish").start();
        });

        ArrayList<String> ids = getIntent().getStringArrayListExtra(EXTRA_SERVER_IDS);
        startPreparation(ids != null ? ids : new ArrayList<>());
    }

    @Override
    protected void onDestroy() {
        polling = false;
        if (httpServer != null) httpServer.stop();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- prepare

    private void startPreparation(List<String> ids) {
        List<ServerInstance> servers = new ArrayList<>();
        for (String id : ids) {
            ServerInstance s = repo.byId(id);
            if (s != null) servers.add(s);
        }
        if (servers.isEmpty()) {
            status(getString(R.string.transfer_error_no_servers));
            hidePackingCard();
            return;
        }
        transferServers = servers;

        new Thread(() -> {
            BackupManager.deleteRecursively(cacheDir);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            session = new TransferSession();
            // the session key has to exist before the blobs get encrypted, a later
            // re-publish (expired code) keeps the same key and the same blobs.
            session.aesKeyHex = TransferCrypto.toHex(TransferCrypto.randomBytes(32));

            // running servers have to stop first, the ZIP must not catch a world
            // mid-write. the stop is graceful, so this can take a moment.
            for (ServerInstance s : servers) {
                if (s.isRunning() || s.state == ServerInstance.State.STOPPING
                        || s.state == ServerInstance.State.RESTARTING) {
                    status(getString(R.string.transfer_status_stopping, s.getName()));
                    stopServerAndWait(s);
                }
            }

            for (int i = 0; i < servers.size(); i++) {                ServerInstance s = servers.get(i);
                TransferSession.Pack pack = preparePack(s, i, servers.size());
                if (pack == null) {
                    status(getString(R.string.transfer_error_prepare, s.getName()));
                    return;
                }
                session.packs.add(pack);
            }

            List<String> ips = TransferHttpServer.lanIps();
            if (ips.isEmpty()) {
                status(getString(R.string.transfer_status_no_lan));
                return;
            }
            session.lanIp = String.join(",", ips);

            if (!startHttpServer()) {
                status(getString(R.string.transfer_status_no_port));
                return;
            }

            // target picker when the account family has more devices, otherwise the
            // classic path starts right away with the pairing code
            java.util.List<org.json.JSONObject> family = fetchFamilyDevices();
            if (family.isEmpty()) {
                if (!publishTransferRow(null)) {
                    status(getString(R.string.transfer_status_no_row));
                    return;
                }
                runOnUiThread(this::showCodeScreen);
                startPolling();
            } else {
                runOnUiThread(() -> showTargetSelection(family));
            }
        }, "KodaTransferSend").start();
    }

    /** the devices of the same account, for the target picker. */
    private java.util.List<org.json.JSONObject> fetchFamilyDevices() {
        java.util.List<org.json.JSONObject> out = new java.util.ArrayList<>();
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("p_app_uuid", App.getPrefs(this).getString("app_uuid", ""))
                    .put("p_device_token", App.getPrefs(this).getString("device_token", ""));
            String resp = TransferSession.callRpc("/rest/v1/rpc/rpc_list_family_devices", body.toString());
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** lets the user pick which family device gets the servers. */
    private void showTargetSelection(java.util.List<org.json.JSONObject> family) {
        status(getString(R.string.transfer_status_pick_target));
        LinearLayout box = findViewById(R.id.ll_transfer_targets);
        box.removeAllViews();
        float density = getResources().getDisplayMetrics().density;

        for (org.json.JSONObject device : family) {
            final String uuid = device.optString("app_uuid", "");
            String nickname = device.optString("nickname", "");
            String model = device.optString("device_model", "");
            String label = TransferSession.deviceLabel(nickname, model, uuid);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * density);
            card.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(12 * density);
            bg.setColor(0xFF241C18);
            card.setBackground(bg);

            TextView title = new TextView(this);
            title.setText(label);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(15);
            title.setLetterSpacing(0.03f);
            title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
            card.addView(title);

            TextView sub = new TextView(this);
            String subText = !model.isEmpty() && !model.equals(label) ? model
                    : getString(R.string.transfer_target_uuid,
                            uuid.length() > 12 ? uuid.substring(uuid.length() - 12) : uuid);
            sub.setText(subText);
            sub.setTextColor(0xFF8A7F77);
            sub.setTextSize(12);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = (int) (3 * density);
            card.addView(sub, sp);

            card.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 40);
                chosenTarget = uuid;
                lastTargetLabel = label;
                findViewById(R.id.section_transfer_target).setVisibility(View.GONE);
                finishTargetSelection();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * density);
            box.addView(card, lp);
        }

        MaterialButton anyDevice = findViewById(R.id.btn_transfer_any_device);
        if (anyDevice != null) {
            anyDevice.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 30);
                chosenTarget = null;
                findViewById(R.id.section_transfer_target).setVisibility(View.GONE);
                finishTargetSelection();
            });
        }
        slideUpAndHidePacking(findViewById(R.id.section_transfer_target));
    }

    /** registers the row for the chosen target and then shows the code screen. */
    private void finishTargetSelection() {
        status(getString(R.string.transfer_status_publishing));
        final String target = chosenTarget;
        new Thread(() -> {
            if (!publishTransferRow(target)) {
                status(getString(R.string.transfer_status_no_row));
                return;
            }
            runOnUiThread(this::showCodeScreen);
            startPolling();
        }, "KodaTransferPublish").start();
    }

    private TransferSession.Pack preparePack(ServerInstance s, int index, int total) {
        try {
            status(getString(R.string.transfer_status_zipping, s.getName(), index + 1, total));
            TransferSession.Pack p = new TransferSession.Pack();
            p.serverId = s.getId();
            p.host = s.getSubdomain().isEmpty() ? s.getName() : s.getSubdomain();
            p.name = s.getName();
            p.version = s.getVersion();
            p.instance = s.toJson();
            p.ivHex = TransferCrypto.toHex(TransferCrypto.randomBytes(12));

            // one single pass: folder -> zip -> frame encryption. the plaintext zip
            // never lands on disk and memory stays flat. with plain GCM Conscrypt
            // buffers the WHOLE stream and doFinal() at close() died with
            // OutOfMemoryError on a server of about 235 MB.
            File enc = new File(cacheDir, s.getId() + ".enc");
            TransferCrypto.FramedEncryptOutputStream feos = new TransferCrypto.FramedEncryptOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(enc)),
                    TransferCrypto.fromHex(session.aesKeyHex), TransferCrypto.fromHex(p.ivHex));
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(feos);
            final String serverName = s.getName();
            BackupManager.zipServerTo(new File(s.getServerDir()), zos, (path, done, filesTotal) ->
                    packTick(serverName, path, done, filesTotal));
            zos.close(); // closes feos too: last frame + end marker
            p.sha256 = feos.digestHex();
            p.encSize = enc.length();
            return p;
        } catch (Exception e) {
            android.util.Log.e("KodaTransfer", "prepare failed for " + s.getName(), e);
            return null;
        }
    }

    private boolean startHttpServer() {
        session.httpToken = TransferCrypto.toHex(TransferCrypto.randomBytes(32));
        byte[] manifest = session.manifestJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.util.Map<String, File> blobs = new java.util.HashMap<>();
        for (TransferSession.Pack p : session.packs) {
            blobs.put(p.serverId, new File(cacheDir, p.serverId + ".enc"));
        }

        Random random = new Random();
        for (int attempt = 0; attempt < 6; attempt++) {
            int port = 41000 + random.nextInt(5000);
            try {
                httpServer = TransferHttpServer.start(port, session.httpToken, manifest, blobs,
                        (serverId, bytes, totalBytes) -> runOnUiThread(
                                () -> updateServeRow(serverId, bytes, totalBytes)));
                session.lanPort = port;
                return true;
            } catch (Exception e) {
                android.util.Log.w("KodaTransfer", "port " + port + " busy, retrying");
            }
        }
        return false;
    }

    /** registers the pairing row, the AES key was generated before the blobs. */
    private boolean publishTransferRow(String targetAppUuid) {
        if (session == null || session.packs.isEmpty()) return false;
        runOnUiThread(() -> findViewById(R.id.btn_transfer_restart).setVisibility(View.GONE));
        try {
            String appUuid = App.getPrefs(this).getString("app_uuid", "");
            String token = App.getPrefs(this).getString("device_token", "");
            JSONObject body = new JSONObject();
            body.put("p_app_uuid", appUuid);
            body.put("p_device_token", token);
            body.put("p_lan_ip", session.lanIp);
            body.put("p_lan_port", session.lanPort);
            body.put("p_http_token", session.httpToken);
            body.put("p_aes_key", session.aesKeyHex);
            body.put("p_servers", session.hostsJson());
            if (targetAppUuid != null) body.put("p_target_app_uuid", targetAppUuid);
            String resp = TransferSession.callRpc("/rest/v1/rpc/rpc_create_transfer", body.toString());
            JSONObject row = new JSONObject(resp);
            session.code = row.optString("code", "");
            if (session.code.isEmpty()) return false;
            App.getPrefs(this).edit().putString("transfer_handled_code", "").apply();
            return true;
        } catch (Exception e) {
            android.util.Log.e("KodaTransfer", "create transfer failed", e);
            return false;
        }
    }

    private void stopServerAndWait(ServerInstance s) {
        try {
            android.content.Intent i = new android.content.Intent(this,
                    eu.kodanetwork.mchost.service.KodaServerService.class);
            i.setAction(eu.kodanetwork.mchost.service.KodaServerService.ACTION_STOP);
            i.putExtra(eu.kodanetwork.mchost.service.KodaServerService.EXTRA_ID, s.getId());
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);

            long deadline = System.currentTimeMillis() + 90000;
            while (System.currentTimeMillis() < deadline) {
                ServerInstance now = repo.byId(s.getId());
                if (now == null || !now.isRunning()) return;
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- polling

    private void startPolling() {
        polling = true;
        new Thread(() -> {
            while (polling && !isFinishing()) {
                try {
                    Thread.sleep(3000);
                    if (!polling || session == null || session.code == null) continue;
                    String appUuid = App.getPrefs(this).getString("app_uuid", "");
                    String token = App.getPrefs(this).getString("device_token", "");
                    JSONObject body = new JSONObject();
                    body.put("p_app_uuid", appUuid);
                    body.put("p_device_token", token);
                    String resp = TransferSession.callRpc("/rest/v1/rpc/rpc_my_transfers", body.toString());
                    JSONArray rows = new JSONArray(resp);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        if (!session.code.equals(row.optString("code", ""))) continue;
                        String state = row.optString("state", "");
                        if ("done".equals(state)) {
                            polling = false;
                            if (httpServer != null) httpServer.stop();
                            String names = joinedNames();
                            cleanupLocalServers();
                            runOnUiThread(() -> showDone(names));
                            return;
                        }
                        if ("expired".equals(state)) {
                            runOnUiThread(() -> {
                                status(getString(R.string.transfer_status_expired));
                                findViewById(R.id.btn_transfer_restart).setVisibility(View.VISIBLE);
                            });
                        }
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }, "KodaTransferPoll").start();
    }

    /** deletes the transferred servers locally, the cloud rows belong to the new device now. */
    private void cleanupLocalServers() {
        for (TransferSession.Pack p : session.packs) {
            ServerInstance s = repo.byId(p.serverId);
            if (s == null) continue;
            File dir = new File(s.getServerDir());
            BackupManager.deleteRecursively(dir);
            // hibernate leftovers and backups of the old id go away with it too
            if (dir.getParentFile() != null) {
                new File(dir.getParentFile(), s.getId() + "_hibernated.zip").delete();
            }
            BackupManager.deleteRecursively(new File(new File(getFilesDir(), "backups"), s.getId()));
            repo.delete(s.getId());
        }
        App.getPrefs(this).edit().putString("transfer_handled_code", session.code).apply();
        BackupManager.deleteRecursively(cacheDir);
    }

    // ---------------------------------------------------------------- ui

    private void buildRows() {
        LinearLayout box = findViewById(R.id.ll_transfer_rows);
        box.removeAllViews();
        rowBars.clear();
        rowTexts.clear();
        for (ServerInstance s : transferServers) {
            TextView name = new TextView(this);
            name.setText(s.getName());
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(13);
            name.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
            name.setLetterSpacing(0.04f);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = 14;
            box.addView(name, np);

            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B00));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FF6B00));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 10);
            bp.topMargin = 8;
            box.addView(bar, bp);
            rowBars.add(bar);
            rowTexts.add(name);

            TextView size = new TextView(this);
            size.setTextColor(0xFF8A7F77);
            size.setTextSize(11);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = 4;
            box.addView(size, sp);
        }
    }

    private void updateServeRow(String serverId, long bytes, long total) {
        if (session == null) return;
        for (int i = 0; i < session.packs.size(); i++) {
            if (!session.packs.get(i).serverId.equals(serverId)) continue;
            if (i < rowBars.size()) {
                int percent = total <= 0 ? 100 : (int) (bytes * 100 / total);
                rowBars.get(i).setProgress(percent);
            }
            if (i < rowTexts.size()) {
                rowTexts.get(i).setText(getString(R.string.transfer_status_serving,
                        session.packs.get(i).name,
                        BackupManager.humanSize(bytes),
                        BackupManager.humanSize(total)));
            }
        }
    }

    private void showCodeScreen() {
        HapticUtil.forceVibrate(this, 60);
        status(getString(R.string.transfer_status_waiting));
        buildRows();
        TextView code = findViewById(R.id.tv_transfer_code);
        if (code != null && session != null) code.setText(session.code);
        TextView lan = findViewById(R.id.tv_transfer_lan);
        if (lan != null && session != null) {
            lan.setText(getString(R.string.transfer_lan_fmt,
                    session.lanIp.replace(",", ", ")));
        }
        // with a target device the prompt showed up over there already, so the code
        // is only a fallback. without a target the classic code instructions stay.
        TextView hint = findViewById(R.id.tv_transfer_code_hint);
        if (hint != null) {
            hint.setText(chosenTarget != null && lastTargetLabel != null
                    ? getString(R.string.transfer_target_sent, lastTargetLabel)
                    : getString(R.string.transfer_code_hint));
        }
        slideUpAndHidePacking(findViewById(R.id.section_transfer_code));
        startCountdown();
    }

    // ---------------------------------------------------------------- packing card

    private long lastPackTick = 0;

    /** live tick from the zip loop, throttled so the UI thread stays calm. */
    private void packTick(String serverName, String path, int done, int total) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPackTick < 90 && done != total) return;
        lastPackTick = now;
        runOnUiThread(() -> {
            TextView name = findViewById(R.id.tv_pack_server);
            TextView file = findViewById(R.id.tv_pack_file);
            TextView count = findViewById(R.id.tv_pack_count);
            ProgressBar bar = findViewById(R.id.bar_pack);
            if (name != null) name.setText(getString(R.string.transfer_pack_server, serverName));
            if (file != null) file.setText(path);
            if (count != null) count.setText(getString(R.string.transfer_pack_count_fmt, done + 1, total));
            if (bar != null && total > 0) bar.setProgress(done * 100 / total);
        });
    }

    /** the section slides up and the packing card goes away. */
    private void slideUpAndHidePacking(android.view.View section) {
        if (section == null) return;
        section.setVisibility(View.VISIBLE);
        section.post(() -> {
            section.setTranslationY(Math.max(240, section.getHeight() * 1.15f));
            section.animate()
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f))
                    .start();
        });
        hidePackingCard();
    }

    private void hidePackingCard() {
        android.view.View card = findViewById(R.id.card_packing);
        if (card == null || card.getVisibility() != View.VISIBLE) return;
        card.animate()
                .translationY(card.getHeight() + 40)
                .alpha(0f)
                .setDuration(320L)
                .withEndAction(() -> card.setVisibility(View.GONE))
                .start();
    }

    private void startCountdown() {
        final long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
        new Thread(() -> {
            while (!isFinishing()) {
                long left = Math.max(0, deadline - System.currentTimeMillis());
                long min = left / 60000;
                long sec = (left % 60000) / 1000;
                runOnUiThread(() -> {
                    TextView tv = findViewById(R.id.tv_transfer_countdown);
                    if (tv != null) {
                        tv.setText(getString(R.string.transfer_countdown_fmt,
                                String.format(Locale.US, "%d:%02d", min, sec)));
                    }
                });
                if (left <= 0) return;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "KodaTransferClock").start();
    }

    private void showDone(String names) {
        HapticUtil.forceVibrate(this, 80);
        status("");
        TextView done = findViewById(R.id.tv_transfer_done);
        if (done != null) done.setText(getString(R.string.transfer_done_text, names));
        findViewById(R.id.section_transfer_code).setVisibility(View.GONE);
        findViewById(R.id.ll_transfer_rows).setVisibility(View.GONE);
        findViewById(R.id.btn_transfer_restart).setVisibility(View.GONE);
        findViewById(R.id.btn_transfer_cancel).setVisibility(View.GONE);
        findViewById(R.id.section_transfer_done).setVisibility(View.VISIBLE);
        Toast.makeText(this, getString(R.string.transfer_done_title), Toast.LENGTH_SHORT).show();
    }

    private String joinedNames() {
        StringBuilder sb = new StringBuilder();
        for (TransferSession.Pack p : session.packs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name);
        }
        return sb.toString();
    }

    private void status(String text) {
        runOnUiThread(() -> {
            TextView tv = findViewById(R.id.tv_transfer_status);
            if (tv != null) tv.setText(text);
        });
    }
}
