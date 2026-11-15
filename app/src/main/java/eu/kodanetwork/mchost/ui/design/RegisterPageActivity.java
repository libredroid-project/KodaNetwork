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
package eu.kodanetwork.mchost.ui.design;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

public class RegisterPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        setContentView(R.layout.activity_design_register);

        EditText etUsername = findViewById(R.id.et_username);
        EditText etEmail = findViewById(R.id.et_email);
        EditText etPassword = findViewById(R.id.et_password);
        EditText etInvite = findViewById(R.id.et_invite);
        Button btnCreate = findViewById(R.id.btn_create_account);
        TextView tvSignIn = findViewById(R.id.tv_sign_in);
        TextView tvTitle = findViewById(R.id.tv_praetor_title);

        // the welcome setup already asked for it; the login/register path can still open this page
        String wantedName = getIntent().getStringExtra("username");
        if (wantedName == null || wantedName.trim().isEmpty()) {
            wantedName = eu.kodanetwork.mchost.App.getPrefs(this).getString("nickname", "");
        }
        if (etUsername != null) etUsername.setText(wantedName);

        if (tvTitle != null) {
            String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
            tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        tvTitle.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk_bold));
        }

        android.widget.CheckBox cbLegal = findViewById(R.id.cb_legal);
        if (cbLegal != null) {
            // age confirmation and acceptance of the legal texts in one step
            cbLegal.setText(android.text.Html.fromHtml(
                    "I am at least 16 years old (or have the consent of my legal guardian) and I accept the "
                    + "<a href='https://host.kodanetwork.eu/tos.html'>Terms of Service</a> and the "
                    + "<a href='https://host.kodanetwork.eu/privacy.html'>Privacy Policy</a>.",
                    android.text.Html.FROM_HTML_MODE_LEGACY));
            cbLegal.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }

        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);
        // the theme forces monospace, these pages want the normal Koda font
        eu.kodanetwork.mchost.util.KodaFont.apply(this, findViewById(android.R.id.content));

        btnCreate.setOnClickListener(v -> {
            if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
            String email = etEmail.getText().toString().trim();
            String pwd = etPassword.getText().toString().trim();
            String username = etUsername == null ? "" : etUsername.getText().toString().trim();
            if (email.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Bitte Pflichtfelder ausfüllen", Toast.LENGTH_SHORT).show();
                return;
            }
            if (username.length() < 2 || username.length() > 20) {
                Toast.makeText(this, R.string.register_username_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (cbLegal != null && !cbLegal.isChecked()) {
                Toast.makeText(this, "Please confirm that you are 16 or older (or have your guardian's consent) and accept the Terms and Privacy Policy.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            
            btnCreate.setEnabled(false);
            btnCreate.setText(getString(R.string.register_loading));

            // code is generated server side (security fix: no client content in the mails anymore)
            eu.kodanetwork.mchost.network.ResendHelper.sendOTP(email, new eu.kodanetwork.mchost.network.ResendHelper.Callback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        btnCreate.setEnabled(true);
                        btnCreate.setText(getString(R.string.register_create_account));

                        android.app.Dialog dialog = new android.app.Dialog(RegisterPageActivity.this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
                        dialog.setContentView(R.layout.dialog_praetor_verify);
                        dialog.setCancelable(false);

                        TextView tvTitle = dialog.findViewById(R.id.tv_dialog_title);
                        if (tvTitle != null) {
                            String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
                            tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        tvTitle.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(RegisterPageActivity.this, R.font.space_grotesk_bold));
                        }

                        TextView tvSubtitle = dialog.findViewById(R.id.tv_dialog_subtitle);
                        if (tvSubtitle != null) tvSubtitle.setText(getString(R.string.email_verification) + " - " + email);

                        EditText input = dialog.findViewById(R.id.et_verification_code);

                        dialog.findViewById(R.id.btn_dialog_verify).setOnClickListener(v -> {
                            String code = input.getText().toString().trim();
                            // checked server side instead of comparing locally
                            eu.kodanetwork.mchost.network.ResendHelper.verifyOtp(email, code, (valid, error) -> runOnUiThread(() -> {
                                if (valid) {
                                    Toast.makeText(RegisterPageActivity.this, getString(R.string.register_code_accepted), Toast.LENGTH_SHORT).show();
                                    btnCreate.setEnabled(false);
                                    btnCreate.setText(getString(R.string.register_loading));
                                    dialog.dismiss();
                                    // the account is only created after the code was accepted,
                                    // so the verified flag is written before the auth user exists
                                    eu.kodanetwork.mchost.util.EmailVerification.markVerified(RegisterPageActivity.this, email, ok ->
                                            createAccount(email, pwd, username, btnCreate));
                                } else {
                                    Toast.makeText(RegisterPageActivity.this, "Falscher Code! " + (error == null ? "" : error), Toast.LENGTH_SHORT).show();
                                }
                            }));
                        });
                        
                        android.widget.TextView skip = dialog.findViewById(R.id.btn_dialog_cancel);
                        skip.setText(R.string.verify_email_skip);
                        skip.setOnClickListener(v -> {
                            // skipping is allowed, the account just stays unverified until
                            // the account settings verify it
                            dialog.dismiss();
                            btnCreate.setEnabled(false);
                            btnCreate.setText(getString(R.string.register_loading));
                            createAccount(email, pwd, username, btnCreate);
                        });
                        
                        dialog.show();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        btnCreate.setEnabled(true);
                        btnCreate.setText(getString(R.string.register_create_account));
                        Toast.makeText(RegisterPageActivity.this, "Fehler beim Senden des Codes: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        tvSignIn.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.AnimHelper.startSlideVertical(this, new Intent(this, LoginPageActivity.class));
            finish();
        });
    }

    /** creates the auth account, for verified and skipped signups alike. */
    private void createAccount(String email, String pwd, String username, Button btnCreate) {
        eu.kodanetwork.mchost.network.supabase.SupabaseAuth.signUp(this, email, pwd,
                new eu.kodanetwork.mchost.network.supabase.SupabaseAuth.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            eu.kodanetwork.mchost.App.getPrefs(RegisterPageActivity.this)
                                    .edit().putString("nickname", username).apply();
                            pushNickname(username);
                            Toast.makeText(RegisterPageActivity.this, R.string.register_success,
                                    Toast.LENGTH_SHORT).show();
                            // came from the welcome setup, hand control back instead of restarting
                            if (getIntent().getBooleanExtra("RETURN_TO_WELCOME", false)) {
                                setResult(RESULT_OK);
                                finish();
                                return;
                            }
                            startActivity(new Intent(RegisterPageActivity.this,
                                    eu.kodanetwork.mchost.ui.MainActivity.class));
                            finishAffinity();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            btnCreate.setEnabled(true);
                            btnCreate.setText(getString(R.string.register_create_account));
                            Toast.makeText(RegisterPageActivity.this, "Fehler: " + message,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /** writes the chosen username to the device row, even before the login is fully linked. */
    private void pushNickname(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) return;
        final String name = nickname.trim();
        new Thread(() -> {
            try {
                String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
                String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
                if (appUuid.isEmpty() || token.isEmpty()) return;
                org.json.JSONObject payload = new org.json.JSONObject().put("nickname", name);
                org.json.JSONObject body = new org.json.JSONObject()
                        .put("p_app_uuid", appUuid)
                        .put("p_device_token", token)
                        .put("p_payload", payload);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                                + "/rest/v1/rpc/rpc_patch_user").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.getOutputStream().write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {
            }
        }, "KodaRegisterNick").start();
    }
}
