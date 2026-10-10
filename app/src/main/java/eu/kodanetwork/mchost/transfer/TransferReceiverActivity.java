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

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.util.BackupManager;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * receiver side of the device transfer (the NEW device).
 *
 * the user types the 8 character code shown on the old device. that code unlocks
 * the pairing row (LAN endpoint, HTTP token, AES key) and the manifest comes
 * straight from the old device. downloads are decrypted on the fly and hashed,
 * and only when every server arrived intact are the cloud rows adopted
 * (rpc_adopt_hosts), the local instances rebuilt 1:1 from the sender's
 * ServerInstance JSON and the ZIPs unpacked. rpc_finish_transfer then tells the
 * old device it may delete its copies.
 */
public class TransferReceiverActivity extends AppCompatActivity {

    /** opened by the MainActivity poll, so no code entry is needed. */
    public static final String EXTRA_AUTO = "auto";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerRepo repo;
    private TransferSession session;
    private File tempDir;

    private final List<ProgressBar> rowBars = new ArrayList<>();
    private final List<TextView> rowTexts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_transfer_receiver);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        repo = ServerRepo.get(this);
        tempDir = new File(getCacheDir(), "transfer_rx");

        MaterialButton search = findViewById(R.id.btn_transfer_search);
        search.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 20);
            lookupCode();
        });
        findViewById(R.id.btn_transfer_start).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            startTransfer();
        });
        findViewById(R.id.btn_transfer_rx_done).setOnClickListener(v -> finish());

        // auto mode (opened by the MainActivity poll): the question comes without a
        // code. when nothing is pending after all, fall back to the code entry.
        if (getIntent().getBooleanExtra(EXTRA_AUTO, false)) {
            findViewById(R.id.section_rx_code).setVisibility(View.GONE);
        }

        // auto prompt: an open transfer that is addressed to exactly this device
        checkPendingTransfer();
    }

    @Override
    protected void onDestroy() {
        // temp ZIPs are disposable, on failure the user retries anyway
        BackupManager.deleteRecursively(tempDir);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- lookup

    /** is there an open transfer addressed to THIS device, then ask right away. */
    private void checkPendingTransfer() {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("p_app_uuid", App.getPrefs(this).getString("app_uuid", ""))
                        .put("p_device_token", App.getPrefs(this).getString("device_token", ""));
                String resp = TransferSession.callRpc("/rest/v1/rpc/rpc_get_pending_transfer_for", body.toString());
                if (resp == null || resp.trim().isEmpty() || "null".equals(resp.trim())) {
                    // nothing waiting. in auto mode that means expired or already
                    // handled, so the manual code entry shows up as a fallback
                    if (getIntent().getBooleanExtra(EXTRA_AUTO, false)) {
                        runOnUiThread(() -> findViewById(R.id.section_rx_code).setVisibility(View.VISIBLE));
                    }
                    return;
                }
                JSONObject row = new JSONObject(resp);
                if (!"open".equals(row.optString("state", ""))) return;
                TransferSession partial = fromRow(row);
                runOnUiThread(() -> {
                    status(getString(R.string.transfer_rx_auto,
                            TransferSession.deviceLabel(partial.fromNickname, partial.fromDeviceModel,
                                    partial.fromAppUuid)));
                    fetchManifestAndConfirm(partial);
                });
            } catch (Exception ignored) {
            }
        }, "KodaTransferPending").start();
    }

    private void lookupCode() {
        TextInputEditText input = findViewById(R.id.et_transfer_code);
        String code = input.getText() == null ? "" : input.getText().toString().trim().toUpperCase();
        if (code.length() != 8) {
            status(getString(R.string.transfer_rx_invalid));
            return;
        }
        status(getString(R.string.transfer_rx_searching));
        setBusy(true);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("p_code", code)
                        .put("p_app_uuid", App.getPrefs(this).getString("app_uuid", ""))
                        .put("p_device_token", App.getPrefs(this).getString("device_token", ""));
                String resp = TransferSession.callRpc("/rest/v1/rpc/rpc_get_transfer", body.toString());
                JSONObject row = new JSONObject(resp);
                String state = row.optString("state", "");
                if (!"open".equals(state)) {
                    String message = "forbidden".equals(state)
                            ? getString(R.string.transfer_rx_forbidden)
                            : "done".equals(state)
                                    ? getString(R.string.transfer_rx_already_done)
                                    : getString(R.string.transfer_rx_expired);
                    runOnUiThread(() -> {
                        status(message);
                        setBusy(false);
                    });
                    return;
                }
                fetchManifestAndConfirm(fromRow(row));
            } catch (Exception e) {
                android.util.Log.w("KodaTransfer", "lookup failed: " + e.getMessage());
                runOnUiThread(() -> {
                    status(getString(R.string.transfer_rx_offline_hint));
                    setBusy(false);
                });
            }
        }, "KodaTransferLookup").start();
    }

    /** builds a session skeleton from the RPC row, without any packs. */
    private TransferSession fromRow(JSONObject row) {
        TransferSession t = new TransferSession();
        t.code = row.optString("code", "");
        t.lanIp = row.optString("lan_ip", "");
        t.lanPort = row.optInt("lan_port", 0);
        t.httpToken = row.optString("http_token", "");
        t.aesKeyHex = row.optString("aes_key", "");
        t.targetAppUuid = row.isNull("target_app_uuid") ? null : row.optString("target_app_uuid");
        t.fromAppUuid = row.optString("from_app_uuid", "");
        t.fromNickname = row.optString("from_nickname", "");
        t.fromDeviceModel = row.optString("from_device_model", "");
        return t;
    }

    /**
     * pulls the manifest from the sender, trying EVERY announced address in order
     * (phones often sit on several networks at once), then shows the P.R.A.E.T.O.R.
     * confirmation with the sender's identity.
     */
    private void fetchManifestAndConfirm(TransferSession partial) {
        status(getString(R.string.transfer_rx_connecting));
        setBusy(true);
        new Thread(() -> {
            try {
                byte[] raw = null;
                String workingIp = null;
                for (String ip : partial.lanIpList()) {
                    try {
                        TransferNet.Response response = TransferNet.get(
                                ip, partial.lanPort, "/" + partial.httpToken + "/m", 2500, 10000);
                        raw = readAll(response.body, 512 * 1024);
                        response.body.close();
                        workingIp = ip;
                        break;
                    } catch (Exception ignored) {
                    }
                }
                if (raw == null) throw new java.io.IOException("no route to sender");

                TransferSession merged = TransferSession.fromManifest(
                        new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8)));
                if (merged.packs.isEmpty()) throw new java.io.IOException("empty manifest");
                merged.code = partial.code;
                merged.lanIp = workingIp; // downloads go straight to the address that answered
                merged.lanPort = partial.lanPort;
                merged.httpToken = partial.httpToken;
                merged.aesKeyHex = partial.aesKeyHex;
                merged.targetAppUuid = partial.targetAppUuid;
                merged.fromAppUuid = partial.fromAppUuid;
                merged.fromNickname = partial.fromNickname;
                merged.fromDeviceModel = partial.fromDeviceModel;
                session = merged;

                runOnUiThread(() -> {
                    setBusy(false);
                    showPraetorConfirm();
                });
            } catch (Exception e) {
                android.util.Log.w("KodaTransfer", "manifest fetch failed: " + e.getMessage());
                runOnUiThread(() -> {
                    status(getString(R.string.transfer_rx_offline_hint));
                    setBusy(false);
                });
            }
        }, "KodaTransferManifest").start();
    }

    /** the P.R.A.E.T.O.R. acceptance screen: who sends, what, and how much. */
    private void showPraetorConfirm() {
        if (session == null || isFinishing()) return;
        String sender = TransferSession.deviceLabel(session.fromNickname, session.fromDeviceModel,
                session.fromAppUuid);
        String model = session.fromDeviceModel == null ? "" : session.fromDeviceModel;

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.activity_praetor_warning);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(false);

        TextView tvTitle = dialog.findViewById(R.id.tv_praetor_title);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
        tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView tvIcon = dialog.findViewById(R.id.tv_warning_icon);
        tvIcon.setText("\u21C4"); // the transfer arrows
        tvIcon.setTextColor(0xFFFF6B00);
        android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0.2f);
        blink.setDuration(300);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tvIcon.startAnimation(blink);

        TextView tvReason = dialog.findViewById(R.id.tv_praetor_reason);
        tvReason.setText(getString(R.string.transfer_rx_praetor_text, sender, session.packs.size(),
                BackupManager.humanSize(session.totalEncSize())));
        if (!model.isEmpty() && !model.equals(sender)) {
            tvReason.append("\n" + getString(R.string.transfer_rx_praetor_device, model));
        }
        if (!session.fromNickname.isEmpty()) {
            tvReason.append("\n" + getString(R.string.transfer_rx_praetor_uuid, session.fromAppUuid));
        }
        // the code authorizes the preview only. the servers are adopted after a login
        // with the same account, so this hint goes here or the screen feels creepy
        if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
            tvReason.append("\n\n" + getString(R.string.transfer_rx_praetor_login_note));
        }

        dialog.findViewById(R.id.et_math_answer).setVisibility(View.GONE);
        dialog.findViewById(R.id.tv_praetor_countdown).setVisibility(View.GONE);
        dialog.findViewById(R.id.btn_praetor_action).setVisibility(View.GONE);
        dialog.findViewById(R.id.btn_praetor_cancel).setVisibility(View.GONE);
        android.view.View ramGroup = dialog.findViewById(R.id.layout_ram_buttons);
        ramGroup.setVisibility(View.VISIBLE);

        HapticUtil.forceVibrate(this, 200);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> HapticUtil.forceVibrate(this, 300), 300);

        android.widget.Button btnAccept = dialog.findViewById(R.id.btn_praetor_fix_ram);
        btnAccept.setText(getString(R.string.transfer_rx_praetor_accept));
        android.widget.Button btnDecline = dialog.findViewById(R.id.btn_praetor_proceed);
        btnDecline.setText(getString(R.string.transfer_rx_praetor_decline));

        btnAccept.setOnClickListener(v -> {
            dialog.dismiss();
            showOverview();
        });
        btnDecline.setOnClickListener(v -> {
            dialog.dismiss();
            if (session != null) {
                // remember the decline so the 5-second poll does not re-ask
                App.getPrefs(this).edit().putString("transfer_declined_code", session.code).apply();
                session = null;
            }
            status(getString(R.string.transfer_rx_declined));
            if (getIntent().getBooleanExtra(EXTRA_AUTO, false)) {
                finish();
            } else {
                findViewById(R.id.section_rx_code).setVisibility(View.VISIBLE);
            }
        });

        dialog.show();
    }

    private void showOverview() {
        StringBuilder list = new StringBuilder();
        for (TransferSession.Pack p : session.packs) {
            if (list.length() > 0) list.append("\n");
            list.append(p.name).append("  (").append(p.version).append(", ")
                    .append(BackupManager.humanSize(p.encSize)).append(")");
        }
        TextView tv = findViewById(R.id.tv_transfer_rx_servers);
        tv.setText(list.toString());
        buildRows();
        findViewById(R.id.section_rx_code).setVisibility(View.GONE);
        findViewById(R.id.section_rx_overview).setVisibility(View.VISIBLE);
        status(getString(R.string.transfer_rx_ready));
    }

    private void buildRows() {
        LinearLayout box = findViewById(R.id.ll_transfer_rx_rows);
        box.removeAllViews();
        rowBars.clear();
        rowTexts.clear();
        for (TransferSession.Pack p : session.packs) {
            TextView name = new TextView(this);
            name.setText(p.name);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(13);
            name.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
            name.setLetterSpacing(0.04f);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = 14;
            box.addView(name, np);
            rowTexts.add(name);

            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B00));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FF6B00));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 10);
            bp.topMargin = 8;
            box.addView(bar, bp);
            rowBars.add(bar);
        }
    }

    // ---------------------------------------------------------------- transfer

    private void startTransfer() {
        if (session == null) return;
        if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
            status(getString(R.string.transfer_rx_login_required));
            startActivity(new android.content.Intent(this,
                    eu.kodanetwork.mchost.ui.design.LoginPageActivity.class));
            return;
        }

        setBusy(true);
        findViewById(R.id.btn_transfer_start).setVisibility(View.GONE);
        new Thread(() -> {
            try {
                BackupManager.deleteRecursively(tempDir);
                if (!tempDir.exists()) tempDir.mkdirs();

                // Phase 1: download + verify every server before anything moves
                for (int i = 0; i < session.packs.size(); i++) {
                    TransferSession.Pack p = session.packs.get(i);
                    status(getString(R.string.transfer_rx_downloading, p.name));
                    if (!downloadPack(p, i)) {
                        status(getString(R.string.transfer_rx_corrupt, p.name));
                        runOnUiThread(() -> findViewById(R.id.btn_transfer_start).setVisibility(View.VISIBLE));
                        setBusy(false);
                        return;
                    }
                }

                // Phase 2: move the cloud rows of exactly these hosts
                status(getString(R.string.transfer_rx_adopting));
                JSONObject body = new JSONObject();
                body.put("p_app_uuid", App.getPrefs(this).getString("app_uuid", ""));
                body.put("p_device_token", App.getPrefs(this).getString("device_token", ""));
                body.put("p_hosts", session.hostsJson());
                TransferSession.callRpc("/rest/v1/rpc/rpc_adopt_hosts", body.toString());

                // Phase 3: local instances + unpack
                for (int i = 0; i < session.packs.size(); i++) {
                    TransferSession.Pack p = session.packs.get(i);
                    status(getString(R.string.transfer_rx_importing, p.name));
                    if (!importPack(p)) {
                        status(getString(R.string.transfer_rx_import_fail, p.name));
                        setBusy(false);
                        return;
                    }
                }

                // Phase 4: tell the old device it can clean up
                JSONObject finish = new JSONObject();
                finish.put("p_code", session.code);
                finish.put("p_app_uuid", App.getPrefs(this).getString("app_uuid", ""));
                finish.put("p_device_token", App.getPrefs(this).getString("device_token", ""));
                TransferSession.callRpc("/rest/v1/rpc/rpc_finish_transfer", finish.toString());

                App.getPrefs(this).edit().putBoolean("recovery_flow_done_v1", true).apply();
                BackupManager.deleteRecursively(tempDir);

                String names = joinedNames();
                runOnUiThread(() -> showDone(names));
            } catch (Exception e) {
                android.util.Log.e("KodaTransfer", "receive failed", e);
                status(getString(R.string.transfer_rx_offline_hint));
                runOnUiThread(() -> findViewById(R.id.btn_transfer_start).setVisibility(View.VISIBLE));
                setBusy(false);
            }
        }, "KodaTransferRx").start();
    }

    /**
     * downloads one encrypted blob and decrypts it frame by frame on the fly.
     * memory stays flat whatever the server size and the plaintext hash is
     * computed while streaming, compared straight against the manifest.
     */
    private boolean downloadPack(TransferSession.Pack p, int index) {
        TransferNet.Response response = null;
        try {
            response = TransferNet.get(session.lanIp, session.lanPort,
                    "/" + session.httpToken + "/s/" + p.serverId, 5000, 60000);
            final long total = response.contentLength > 0 ? response.contentLength : p.encSize;

            File zip = new File(tempDir, p.serverId + ".zip");
            String hash;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
                final int idx = index;
                hash = TransferCrypto.decryptFramed(response.body, out,
                        TransferCrypto.fromHex(session.aesKeyHex), TransferCrypto.fromHex(p.ivHex),
                        (done, ignoredTotal) -> runOnUiThread(() -> updateRow(idx, done, total)));
            }
            return hash != null && hash.equalsIgnoreCase(p.sha256);
        } catch (Exception e) {
            android.util.Log.e("KodaTransfer", "download failed for " + p.name, e);
            return false;
        } finally {
            if (response != null) {
                try {
                    response.body.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** rebuilds the ServerInstance 1:1 from the sender's JSON, then unpacks the ZIP. */
    private boolean importPack(TransferSession.Pack p) {
        try {
            ServerInstance s = ServerInstance.fromJson(p.instance);
            // the sender controls this json, and the id becomes a folder name and lands
            // in a shell script, so anything odd in it gets swapped for a fresh uuid
            if (s.getId() == null || !s.getId().matches("[A-Za-z0-9._-]{1,64}")) {
                s.setId(UUID.randomUUID().toString());
            }
            String launcherJar = s.getLauncherJar();
            if (launcherJar.contains("/") || launcherJar.contains("\\") || launcherJar.contains("..")) {
                s.setLauncherJar(null);
            }
            // fresh id when this device somehow already knows that uuid
            if (repo.byId(s.getId()) != null) s.setId(UUID.randomUUID().toString());
            // same for the port, a server already living here may sit on it
            for (ServerInstance other : repo.all()) {
                if (!other.getId().equals(s.getId()) && other.getPort() == s.getPort()) {
                    s.setPort(30000 + new java.util.Random().nextInt(9999));
                    break;
                }
            }
            File dir = new File(new File(getFilesDir(), "servers"), s.getId());
            if (dir.exists()) BackupManager.deleteRecursively(dir);
            if (!dir.exists()) dir.mkdirs();
            s.setServerDir(dir.getAbsolutePath());
            s.state = ServerInstance.State.OFFLINE;

            File zip = new File(tempDir, p.serverId + ".zip");
            unzipInto(zip, dir);
            repo.add(s);
            return true;
        } catch (Exception e) {
            android.util.Log.e("KodaTransfer", "import failed for " + p.name, e);
            return false;
        }
    }

    private static void unzipInto(File zip, File targetDir) throws Exception {
        String canonicalBase = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File out = new File(targetDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(canonicalBase)) continue; // Zip-Slip guard
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream fos = new FileOutputStream(out)) {
                    int read;
                    while ((read = zis.read(buf)) > 0) fos.write(buf, 0, read);
                }
                zis.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- ui helpers

    private void updateRow(int index, long bytes, long total) {
        if (index < rowBars.size()) {
            rowBars.get(index).setProgress(total <= 0 ? 100 : (int) (bytes * 100 / total));
        }
        if (index < rowTexts.size() && session != null && index < session.packs.size()) {
            rowTexts.get(index).setText(getString(R.string.transfer_status_serving,
                    session.packs.get(index).name,
                    BackupManager.humanSize(bytes), BackupManager.humanSize(total)));
        }
    }

    private void showDone(String names) {
        HapticUtil.forceVibrate(this, 80);
        TextView done = findViewById(R.id.tv_transfer_rx_done);
        if (done != null) done.setText(getString(R.string.transfer_rx_success, names));
        findViewById(R.id.section_rx_overview).setVisibility(View.GONE);
        findViewById(R.id.section_rx_done).setVisibility(View.VISIBLE);
        status("");
        setBusy(false);
    }

    private String joinedNames() {
        StringBuilder sb = new StringBuilder();
        for (TransferSession.Pack p : session.packs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name);
        }
        return sb.toString();
    }

    private byte[] readAll(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) > 0 && bos.size() < limit) {
            bos.write(buf, 0, read);
        }
        return bos.toByteArray();
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            MaterialButton search = findViewById(R.id.btn_transfer_search);
            MaterialButton start = findViewById(R.id.btn_transfer_start);
            search.setEnabled(!busy);
            search.setAlpha(busy ? 0.5f : 1f);
            start.setEnabled(!busy);
            start.setAlpha(busy ? 0.5f : 1f);
        });
    }

    private void status(String text) {
        runOnUiThread(() -> {
            TextView tv = findViewById(R.id.tv_transfer_rx_status);
            if (tv != null) tv.setText(text);
        });
    }
}
