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

        EditText etEmail = findViewById(R.id.et_email);
        EditText etPassword = findViewById(R.id.et_password);
        EditText etInvite = findViewById(R.id.et_invite);
        Button btnCreate = findViewById(R.id.btn_create_account);
        TextView tvSignIn = findViewById(R.id.tv_sign_in);
        TextView tvTitle = findViewById(R.id.tv_praetor_title);

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

        btnCreate.setOnClickListener(v -> {
            if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
            String email = etEmail.getText().toString().trim();
            String pwd = etPassword.getText().toString().trim();
            if (email.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Bitte Pflichtfelder ausfüllen", Toast.LENGTH_SHORT).show();
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

                                    eu.kodanetwork.mchost.network.supabase.SupabaseAuth.signUp(RegisterPageActivity.this, email, pwd, new eu.kodanetwork.mchost.network.supabase.SupabaseAuth.AuthCallback() {
                                        @Override
                                        public void onSuccess() {
                                            runOnUiThread(() -> {
                                                Toast.makeText(RegisterPageActivity.this, "Account erfolgreich erstellt!", Toast.LENGTH_SHORT).show();
                                                // came from the welcome setup, hand control back instead of restarting
                    if (getIntent().getBooleanExtra("RETURN_TO_WELCOME", false)) {
                        setResult(RESULT_OK);
                        finish();
                        return;
                    }
                    startActivity(new Intent(RegisterPageActivity.this, eu.kodanetwork.mchost.ui.MainActivity.class));
                                                finishAffinity();
                                            });
                                        }

                                        @Override
                                        public void onError(String message) {
                                            runOnUiThread(() -> {
                                                btnCreate.setEnabled(true);
                                                btnCreate.setText(getString(R.string.register_create_account));
                                                Toast.makeText(RegisterPageActivity.this, "Fehler: " + message, Toast.LENGTH_LONG).show();
                                            });
                                        }
                                    });
                                } else {
                                    Toast.makeText(RegisterPageActivity.this, "Falscher Code! " + (error == null ? "" : error), Toast.LENGTH_SHORT).show();
                                }
                            }));
                        });
                        
                        dialog.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> {
                            dialog.cancel();
                            btnCreate.setEnabled(true);
                            btnCreate.setText(getString(R.string.register_create_account));
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
}
