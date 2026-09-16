package eu.kodanetwork.mchost.ui.design;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

public class AdminPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_design_admin);

        Button btnCreateSub = findViewById(R.id.btn_create_subscription);
        Button btnGenerateInvite = findViewById(R.id.btn_generate_invite);
        Button btnGeneratePromo = findViewById(R.id.btn_generate_promo);
        Button btnGenerateRedeem = findViewById(R.id.btn_generate_redeem);

        btnCreateSub.setOnClickListener(v -> toast("Subscription action via Supabase Admin Function"));
        btnGenerateInvite.setOnClickListener(v -> toast("Invite generated"));
        btnGeneratePromo.setOnClickListener(v -> toast("Promo generated"));
        btnGenerateRedeem.setOnClickListener(v -> toast("Redeem code generated"));

        Button btnAnnounceTos = findViewById(R.id.btn_announce_tos);
        if (btnAnnounceTos != null) {
            btnAnnounceTos.setOnClickListener(v -> {
                btnAnnounceTos.setEnabled(false);
                btnAnnounceTos.setText("UPDATING...");
                new Thread(() -> {
                    try {
                        String baseUrl = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();
                        String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                        
                        // Create JSON body
                        String ts = String.valueOf(System.currentTimeMillis());
                        String json = "{\"key\":\"latest_tos_version\",\"value\":\"" + ts + "\"}";
                        
                        okhttp3.RequestBody body = okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json"));
                        okhttp3.Request request = new okhttp3.Request.Builder()
                                .url(baseUrl + "/rest/v1/app_settings")
                                .post(body)
                                .addHeader("apikey", anonKey)
                                .addHeader("Authorization", "Bearer " + anonKey)
                                .addHeader("Prefer", "resolution=merge-duplicates")
                                .addHeader("Content-Type", "application/json")
                                .build();
                                
                        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                        try (okhttp3.Response response = client.newCall(request).execute()) {
                            if (response.isSuccessful()) {
                                runOnUiThread(() -> {
                                    toast("ToS Update Announced!");
                                    btnAnnounceTos.setEnabled(true);
                                    btnAnnounceTos.setText("ANNOUNCE TOS UPDATE");
                                });
                            } else {
                                String err = response.body() != null ? response.body().string() : "";
                                runOnUiThread(() -> {
                                    toast("Failed: HTTP " + response.code() + " " + err);
                                    btnAnnounceTos.setEnabled(true);
                                    btnAnnounceTos.setText("ANNOUNCE TOS UPDATE");
                                });
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            toast("Error: " + e.getMessage());
                            btnAnnounceTos.setEnabled(true);
                            btnAnnounceTos.setText("ANNOUNCE TOS UPDATE");
                        });
                    }
                }).start();
            });
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
