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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.security.PraetorSecurity;

/**
 * blocking screen shown while the shared tunnel server is overloaded.
 *
 * the service stops all hosted servers in that case (see KodaServerService.checkInfraLimits) and sets
 * the "infra_overload" flag; this screen explains why and cannot be dismissed on purpose, it closes
 * itself as soon as the tunnel server has recovered, so nobody can start a server into an overloaded
 * network.
 */
public class InfraOverloadActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checking = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_infra_overload);

        // same wordmark as the other P.R.A.E.T.O.R. screens, letter colours and font included
        TextView title = findViewById(R.id.praetorTitle);
        if (title != null) {
            String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font>"
                    + "<font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font>"
                    + "<font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font>"
                    + "<font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font>"
                    + "<font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
            title.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        // brand below the divider: "Koda" white, "hosting" in the koda orange
        TextView brand = findViewById(R.id.brandName);
        if (brand != null) {
            brand.setText(android.text.Html.fromHtml(
                    "Koda<font color=\"#FF6B00\">Hosting</font>", android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    /** blocked on purpose: back must not lead back into the app while the network is overloaded. */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }

    /** reads one threshold from app_settings.vps_limits (cached across the screen's lifetime). */
    private static org.json.JSONObject cachedLimits = null;

    private double pluginLimit(String key, double fallback) {
        if (cachedLimits == null) {
            cachedLimits = new org.json.JSONObject();
            try {
                String baseUrl = PraetorSecurity.getSupabaseUrl();
                String apiKey = PraetorSecurity.getSupabaseKey();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(baseUrl + "/rest/v1/app_settings?key=eq.vps_limits&select=value")
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .build();
                try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        org.json.JSONArray rows = new org.json.JSONArray(response.body().string());
                        if (rows.length() > 0) cachedLimits = rows.getJSONObject(0).optJSONObject("value");
                    }
                }
            } catch (Exception ignored) {}
        }
        return cachedLimits.optDouble(key, fallback);
    }

    private void refresh() {
        boolean overloaded = eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("infra_overload", false);
        if (!overloaded) {
            finish();
            return;
        }

        String reason = eu.kodanetwork.mchost.App.getPrefs(this).getString("infra_overload_reason", "");
        long since = eu.kodanetwork.mchost.App.getPrefs(this).getLong("infra_overload_since", 0);

        TextView reasonText = findViewById(R.id.overloadReasonText);
        if (reasonText != null) {
            reasonText.setText(reason.isEmpty() ? getString(R.string.overload_reason_unknown) : reason);
        }

        TextView sinceText = findViewById(R.id.overloadSinceText);
        if (sinceText != null && since > 0) {
            long minutes = Math.max(1, (System.currentTimeMillis() - since) / 60000);
            sinceText.setText(getResources().getQuantityString(R.plurals.overload_since, (int) minutes, (int) minutes));
        }

        // which limits are being enforced, those are what has to recover
        TextView limits = findViewById(R.id.overloadLimitsText);
        if (limits != null) {
            double maxRam = pluginLimit("ram_pct", 80);
            int maxConnections = (int) pluginLimit("connections", 300);
            int maxTunnels = (int) pluginLimit("tunnels", 200);
            limits.setText(getString(R.string.overload_limits,
                    Math.round(maxRam), maxConnections, maxTunnels));
        }

        // current values sit next to the reason, so it is obvious what has to recover
        if (!checking) {
            checking = true;
            new Thread(() -> {
                String line = null;
                try {
                    String baseUrl = PraetorSecurity.getSupabaseUrl();
                    String apiKey = PraetorSecurity.getSupabaseKey();
                    okhttp3.RequestBody body = okhttp3.RequestBody.create("{}",
                            okhttp3.MediaType.parse("application/json; charset=utf-8"));
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(baseUrl + "/rest/v1/rpc/rpc_get_vps_stats")
                            .post(body)
                            .addHeader("apikey", apiKey)
                            .addHeader("Authorization", "Bearer " + apiKey)
                            .build();
                    try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            org.json.JSONArray rows = new org.json.JSONArray(response.body().string());
                            if (rows.length() > 0) {
                                org.json.JSONObject stats = rows.getJSONObject(0);
                                long total = Math.max(1, stats.optLong("ram_total_mb", 1));
                                long used = stats.optLong("ram_used_mb", 0);
                                int cores = Math.max(1, stats.optInt("cpu_cores", 1));
                                line = getString(R.string.overload_values,
                                        Math.round(used * 100.0 / total),
                                        String.format(java.util.Locale.US, "%.2f", stats.optDouble("load1", 0) / cores),
                                        stats.optInt("players_connected", 0),
                                        stats.optInt("open_tunnels", 0));
                            }
                        }
                    }
                } catch (Exception ignored) {}
                final String shown = line;
                runOnUiThread(() -> {
                    checking = false;
                    TextView values = findViewById(R.id.overloadValuesText);
                    if (values != null && shown != null) values.setText(shown);
                });
            }).start();
        }
    }
}
