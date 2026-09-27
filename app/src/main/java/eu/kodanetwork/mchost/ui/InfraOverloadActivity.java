package eu.kodanetwork.mchost.ui;

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
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.security.PraetorSecurity;

/**
 * Blocking screen shown while the shared tunnel server is overloaded.
 *
 * The service stops all hosted servers in that case (see KodaServerService.checkInfraLimits) and sets
 * the "infra_overload" flag; this screen explains why and cannot be dismissed on purpose - it closes
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

    /** Blocking on purpose: back must not lead back into the app while the network is overloaded. */
    @Override
    public void onBackPressed() {
        // intentionally empty
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

        // show the current values next to the reason, so it is clear what has to recover
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
