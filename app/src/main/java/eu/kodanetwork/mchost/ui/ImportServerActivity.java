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
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.KodaVersionWheel;
import eu.kodanetwork.mchost.util.ServerImportInspector;
import eu.kodanetwork.mchost.util.VersionCatalog;

/**
 * the screen an imported server lands on. it shows what the app found in the folder and
 * lets the user set everything that matters right there: software, minecraft version,
 * the file that starts the server and the memory. nothing of this has to be hunted down
 * in the server settings afterwards.
 */
public class ImportServerActivity extends Activity {

    /** id of the server that was just imported. */
    public static final String EXTRA_SERVER_ID = "serverId";
    /** label the import flow used for this folder, for the title when the server has no name yet. */
    public static final String EXTRA_LABEL = "label";

    private ServerRepo repo;
    private ServerInstance server;
    private ServerImportInspector.Inspection inspection;
    private final List<ServerImportInspector.Candidate> candidates = new ArrayList<>();
    private String pickedLauncher = "";

    private TextView tvSummary, tvWarnings, tvType, tvVersion, tvFile, tvRam;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_server);

        repo = ServerRepo.get(this);
        String id = getIntent() != null ? getIntent().getStringExtra(EXTRA_SERVER_ID) : null;
        server = id != null ? repo.byId(id) : null;
        if (server == null) {
            finish();
            return;
        }

        tvSummary = findViewById(R.id.tv_import_screen_summary);
        tvWarnings = findViewById(R.id.tv_import_screen_warnings);
        tvType = findViewById(R.id.tv_import_type_value);
        tvVersion = findViewById(R.id.tv_import_version_value);
        tvFile = findViewById(R.id.tv_import_file_value);
        tvRam = findViewById(R.id.tv_import_ram_value);

        String label = getIntent().getStringExtra(EXTRA_LABEL);
        TextView title = findViewById(R.id.tv_import_screen_title);
        title.setText(label == null || label.isEmpty() ? server.getName() : label);

        findViewById(R.id.btn_import_screen_back).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            finish();
        });

        inspectNow();
        wireRows();
        updateValues();

        // this screen is built at runtime, so it has to ask for the app theme explicitly
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);
    }

    /** looks at the folder once and prefills everything from what it found. */
    private void inspectNow() {
        File dir = new File(server.getServerDir());
        inspection = ServerImportInspector.inspect(dir);
        candidates.clear();
        candidates.addAll(inspection.candidates);

        // only overwrite what the user has not chosen yet, an import should never
        // silently throw away a software or version that is already set
        if (inspection.detectedType != null) server.setType(inspection.detectedType);
        if (!inspection.detectedVersion.isEmpty()) server.setVersion(inspection.detectedVersion);
        pickedLauncher = server.getLauncherJar();
        if (pickedLauncher.isEmpty() && inspection.best() != null) pickedLauncher = inspection.best().name;

        List<String> bits = new ArrayList<>();
        if (inspection.best() != null) bits.add(inspection.best().name);
        if (!inspection.worldFolders.isEmpty()) bits.add(getString(R.string.import_pick_world, inspection.worldFolders.get(0)));
        if (inspection.modCount > 0) bits.add(inspection.modCount + " mods");
        if (inspection.pluginCount > 0) bits.add(inspection.pluginCount + " plugins");
        tvSummary.setText(bits.isEmpty() ? getString(R.string.import_pick_nothing)
                : android.text.TextUtils.join(", ", bits));

        if (inspection.warnings.isEmpty()) {
            tvWarnings.setVisibility(View.GONE);
        } else {
            tvWarnings.setVisibility(View.VISIBLE);
            tvWarnings.setText("\u2022 " + android.text.TextUtils.join("\n\u2022 ", inspection.warnings));
        }
    }

    private void wireRows() {
        findViewById(R.id.row_import_type).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            showTypePicker();
        });
        findViewById(R.id.row_import_version).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            showVersionPicker();
        });
        findViewById(R.id.row_import_file).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            showLauncherPicker();
        });
        findViewById(R.id.row_import_ram).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 30);
            showRamPicker();
        });
        findViewById(R.id.btn_import_screen_save).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            save();
        });
    }

    private void updateValues() {
        tvType.setText(server.getType().name());
        tvVersion.setText(server.getVersion().isEmpty() ? "-" : server.getVersion());
        tvFile.setText(pickedLauncher.isEmpty() ? getString(R.string.sd_launcher_auto) : pickedLauncher);
        tvRam.setText(getString(R.string.import_screen_ram_format, server.getRamMB()));
    }

    private void save() {
        server.setLauncherJar(pickedLauncher);
        repo.update(server);
        Toast.makeText(this, getString(R.string.import_pick_applied,
                server.getType().name() + (server.getVersion().isEmpty() ? "" : " " + server.getVersion())),
                Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    /** software picker: every type the app can host. */
    private void showTypePicker() {
        final ServerInstance.Type[] types = VersionCatalog.types();
        List<String> names = new ArrayList<>();
        for (ServerInstance.Type t : types) {
            names.add(t == ServerInstance.Type.PUMPKIN
                    ? t.name() + "  (" + getString(R.string.pumpkin_coming_soon_short) + ")"
                    : t.name());
        }

        showListDialog(getString(R.string.import_screen_type_label), names, index -> {
            if (types[index] == ServerInstance.Type.PUMPKIN) {
                // not ready yet, so it stays unselectable here as well
                Toast.makeText(this, R.string.pumpkin_coming_soon, Toast.LENGTH_LONG).show();
                return;
            }
            server.setType(types[index]);
            updateValues();
            // a different software usually means a different version list
            showVersionPicker();
        });
    }

    /** version picker: the same snap wheel the setup and the version switch use. */
    private void showVersionPicker() {
        final ServerInstance.Type type = server.getType();
        final TextView row = tvVersion;
        row.setText(R.string.import_screen_loading);
        new Thread(() -> {
            final List<String> versions = VersionCatalog.forType(type);
            runOnUiThread(() -> {
                row.setText(server.getVersion().isEmpty() ? "-" : server.getVersion());
                if (versions.isEmpty()) {
                    Toast.makeText(this, R.string.import_screen_no_versions, Toast.LENGTH_LONG).show();
                    return;
                }
                KodaVersionWheel.show(this, versions, new HashMap<>(), server.getVersion(),
                        getString(R.string.import_screen_version_label), version -> {
                            server.setVersion(version);
                            updateValues();
                        });
            });
        }, "KodaVersionList").start();
    }

    /** server file picker: the jars in the folder plus automatic. */
    private void showLauncherPicker() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.sd_launcher_auto));
        final List<String> files = new ArrayList<>();
        files.add("");
        for (ServerImportInspector.Candidate c : candidates) {
            names.add(c.name);
            files.add(c.name);
        }
        for (String installer : inspection.installers) {
            names.add(installer + "  (" + getString(R.string.import_screen_installer) + ")");
            files.add(installer);
        }
        showListDialog(getString(R.string.import_screen_file_label), names, index -> {
            pickedLauncher = files.get(index);
            updateValues();
        });
    }

    /** memory picker, 512 MB steps, the wheel is good at picking one value. */
    private void showRamPicker() {
        // keep the current value in the list even when it is an odd number
        List<Integer> steps = new ArrayList<>();
        for (int mb = 512; mb <= 8192; mb += 512) steps.add(mb);
        if (server.getRamMB() > 0 && !steps.contains(server.getRamMB())) {
            steps.add(server.getRamMB());
            java.util.Collections.sort(steps);
        }
        List<String> sizes = new ArrayList<>();
        for (int mb : steps) sizes.add(getString(R.string.import_screen_ram_format, mb));
        KodaVersionWheel.show(this, sizes, new HashMap<>(),
                getString(R.string.import_screen_ram_format, server.getRamMB()),
                getString(R.string.import_screen_ram_label), chosen -> {
                    try {
                        server.setRamMB(Integer.parseInt(chosen.replaceAll("[^0-9]", "")));
                        updateValues();
                    } catch (NumberFormatException ignored) {}
                });
    }

    private interface OnPicked {
        void picked(int index);
    }

    /** one dialog for every "pick a line from a list" job, in the app design. */
    private void showListDialog(String hint, List<String> entries, OnPicked callback) {
        final float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_import_pick);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) dialog.findViewById(R.id.tv_import_title)).setText(server.getName());
        TextView tvSummary = dialog.findViewById(R.id.tv_import_summary);
        tvSummary.setVisibility(View.GONE);
        ((TextView) dialog.findViewById(R.id.tv_import_launcher_hint)).setText(hint);
        ((TextView) dialog.findViewById(R.id.tv_import_warnings)).setVisibility(View.GONE);
        android.widget.LinearLayout list = dialog.findViewById(R.id.ll_import_candidates);

        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            android.widget.TextView row = new android.widget.TextView(this);
            row.setText(entries.get(i));
            row.setTextSize(13);
            row.setTextColor(0xFFF2EFE9);
            row.setBackgroundResource(R.drawable.cell_bg);
            row.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
            row.setOnClickListener(v -> {
                callback.picked(index);
                dialog.dismiss();
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * d);
            list.addView(row, lp);
        }

        dialog.findViewById(R.id.btn_import_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btn_import_confirm).setVisibility(View.GONE);
        dialog.show();
        eu.kodanetwork.mchost.util.ThemeHelper.apply(dialog);
    }
}
