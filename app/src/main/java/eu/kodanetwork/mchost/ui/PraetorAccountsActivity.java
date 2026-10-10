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
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.security.PraetorSecurity;
import eu.kodanetwork.mchost.util.HapticUtil;

public class PraetorAccountsActivity extends Activity {

    private String rowId;
    private String mcName;
    private final Map<String, CheckBox> permCheckboxes = new HashMap<>();
    
    // the 8 permissions
    private static final String[] PERMS = {
        "perm_start",
        "perm_stop",
        "perm_restart",
        "perm_settings",
        "perm_players",
        "perm_plugins",
        "perm_console",
        "perm_delete"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_praetor_accounts_m3 : R.layout.activity_praetor_accounts);

        rowId = getIntent().getStringExtra("extra_row_id");
        mcName = getIntent().getStringExtra("extra_mc_name");

        // multicolor PRAETOR title instead of the old single red text
        TextView tvTitle = findViewById(R.id.tv_praetor_title);
        if (tvTitle != null) {
            String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
            tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        TextView tvAccountName = findViewById(R.id.tv_account_name);
        tvAccountName.setText(getString(R.string.pra_user, mcName != null ? mcName : "?"));

        LinearLayout llContainer = findViewById(R.id.ll_permissions_container);
        
        // main / second account toggle (feeds the permission hierarchy)
        android.widget.Switch mainToggle = new android.widget.Switch(this);
        mainToggle.setText(getString(R.string.pra_main_toggle));
        mainToggle.setTextColor(0xFFF0F0F0);
        mainToggle.setTextSize(14);
        int accent = 0xFFFF6B00; // koda accent (orange)
        mainToggle.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        mainToggle.setTrackTintList(android.content.res.ColorStateList.valueOf(accent & 0x66FFFFFF));
        mainToggle.setPadding(0, 24, 0, 8);
        llContainer.addView(mainToggle);

        TextView tvMainDesc = new TextView(this);
        tvMainDesc.setText(getString(R.string.pra_main_desc));
        tvMainDesc.setTextColor(0xFF8A8A9A);
        tvMainDesc.setTextSize(11);
        llContainer.addView(tvMainDesc);

        View mainSep = new View(this);
        mainSep.setLayoutParams(new LinearLayout.LayoutParams(-1, 2));
        mainSep.setBackgroundColor(0xFFFF6B00);
        android.widget.LinearLayout.LayoutParams mainSepParams = (android.widget.LinearLayout.LayoutParams) mainSep.getLayoutParams();
        mainSepParams.setMargins(0, 12, 0, 16);
        llContainer.addView(mainSep);
        this.mainToggle = mainToggle;

        // checkboxes take theme colors, no more hardcoded red and white
        for (String perm : PERMS) {
            CheckBox cb = new CheckBox(this);
            cb.setText(perm.toUpperCase().replace("PERM_", ""));
            cb.setTextColor(0xFFF0F0F0);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            android.graphics.Typeface kodaMono = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk_regular);
            if (kodaMono != null) cb.setTypeface(kodaMono);
            cb.setTextSize(16);
            cb.setPadding(0, 16, 0, 16);

            // thin line between the checkboxes
            View separator = new View(this);
            separator.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            separator.setBackgroundColor(0x1AFFFFFF);

            llContainer.addView(cb);
            llContainer.addView(separator);

            permCheckboxes.put(perm, cb);
        }

        findViewById(R.id.btn_praetor_cancel).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 80);
            finish();
        });

        findViewById(R.id.btn_praetor_save).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 120);
            savePermissions();
        });

        loadPermissions();
    }

    private android.widget.Switch mainToggle;

    private void loadPermissions() {
        if (rowId == null) return;
        new Thread(() -> {
            try {
                // admin access needs the admin rpc with a device_token since the security fix
                String appUuid = eu.kodanetwork.mchost.App.getPrefs(PraetorAccountsActivity.this).getString("app_uuid", "");
                String deviceToken = eu.kodanetwork.mchost.App.getPrefs(PraetorAccountsActivity.this).getString("device_token", "");
                URL url = new URL(PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_admin_list_users");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey());
                String body = "{\"p_admin_app_uuid\":\"" + appUuid + "\", \"p_admin_device_token\":\"" + deviceToken + "\"}";
                conn.getOutputStream().write(body.getBytes());

                if (conn.getResponseCode() == 200) {
                    java.util.Scanner s = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
                    String resp = s.hasNext() ? s.next() : "";
                    org.json.JSONArray arr = new org.json.JSONArray(resp);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject row = arr.getJSONObject(i);
                        if (rowId.equals(row.optString("id"))) {
                            JSONObject permsObj = row.optJSONObject("permissions");
                            if (permsObj != null) {
                                runOnUiThread(() -> {
                                    for (String perm : PERMS) {
                                        if (permCheckboxes.containsKey(perm)) {
                                            permCheckboxes.get(perm).setChecked(permsObj.optBoolean(perm, false));
                                        }
                                    }
                                });
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void savePermissions() {
        if (rowId == null) return;
        
        Button btnSave = findViewById(R.id.btn_praetor_save);
        btnSave.setEnabled(false);
        btnSave.setText(getString(R.string.pra_saving));

        new Thread(() -> {
            try {
                JSONObject permsObj = new JSONObject();
                for (String perm : PERMS) {
                    permsObj.put(perm, permCheckboxes.get(perm).isChecked());
                }
                
                JSONObject payload = new JSONObject();
                payload.put("permissions", permsObj);

                URL url = new URL(PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_admin_patch_user");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey()); // anon key on purpose, skips the auth RLS, the user may not be the owner
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String adminAppUuid = eu.kodanetwork.mchost.App.getPrefs(PraetorAccountsActivity.this).getString("app_uuid", "");
                String adminToken = eu.kodanetwork.mchost.App.getPrefs(PraetorAccountsActivity.this).getString("device_token", "");
                String jsonBody = "{\"p_admin_app_uuid\": \"" + adminAppUuid + "\", \"p_admin_device_token\": \"" + adminToken + "\", \"p_target_id\": \"" + rowId + "\", \"p_payload\": " + payload.toString() + "}";
                conn.getOutputStream().write(jsonBody.getBytes());
                
                int code = conn.getResponseCode();
                runOnUiThread(() -> {
                    if (code == 200 || code == 204) {
                        Toast.makeText(this, getString(R.string.pra_saved), Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, getString(R.string.pra_save_failed) + " (" + code + ")", Toast.LENGTH_LONG).show();
                        btnSave.setEnabled(true);
                        btnSave.setText(getString(R.string.pra_apply));
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.pra_error) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSave.setEnabled(true);
                    btnSave.setText(getString(R.string.pra_apply));
                });
            }
        }).start();
    }
}
