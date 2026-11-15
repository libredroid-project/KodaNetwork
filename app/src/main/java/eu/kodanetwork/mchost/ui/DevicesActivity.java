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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.KodaSheet;
import eu.kodanetwork.mchost.util.ThemeHelper;

/**
 * the account's devices, natively on the phone instead of a link to the web
 * dashboard. mirrors GET /v1/me/devices and POST /v1/me/devices/{id}/unlink.
 */
public class DevicesActivity extends Activity {

    private static final String API = "https://api.kodanetwork.eu";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devices);
        ThemeHelper.apply(this);

        View back = findViewById(R.id.btn_back);
        if (back != null) back.setOnClickListener(v -> finish());
        loadDevices();
    }

    private void loadDevices() {
        LinearLayout container = findViewById(R.id.devices_container);
        if (container == null) return;
        container.removeAllViews();

        TextView loading = label(getString(R.string.devices_loading), secondary(), 13, false);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(32), 0, dp(32));
        container.addView(loading);

        String token = App.getPrefs(this).getString("koda_session_token", "");
        if (token.isEmpty()) {
            showError();
            return;
        }

        new Thread(() -> {
            try {
                JSONObject result = request("GET", API + "/v1/me/devices", token, null);
                JSONArray items = result.optJSONArray("items");
                runOnUiThread(() -> render(items));
            } catch (Exception e) {
                runOnUiThread(this::showError);
            }
        }, "KodaDevices").start();
    }

    private void render(JSONArray items) {
        LinearLayout container = findViewById(R.id.devices_container);
        if (container == null) return;
        container.removeAllViews();

        if (items == null || items.length() == 0) {
            TextView empty = label(getString(R.string.devices_empty), secondary(), 13, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(32), 0, dp(32));
            container.addView(empty);
            return;
        }

        String currentUuid = App.getPrefs(this).getString("app_uuid", "");
        for (int i = 0; i < items.length(); i++) {
            JSONObject device = items.optJSONObject(i);
            if (device != null) container.addView(deviceCard(device, currentUuid));
        }
    }

    private View deviceCard(JSONObject device, String currentUuid) {
        boolean light = ThemeHelper.isLightMode(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setColor(light ? 0xFFFFFFFF : 0xFF241C18);
        background.setCornerRadius(dp(12));
        background.setStroke(Math.max(1, dp(1)), light ? 0xFFE0E0E0 : 0xFF2A2226);
        card.setBackground(background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        String uuid = device.optString("app_uuid", "");
        boolean current = !uuid.isEmpty() && uuid.equals(currentUuid);
        boolean main = false;
        JSONArray accounts = device.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.optJSONObject(i);
                if (account != null
                        && account.optBoolean("is_main", false)
                        && !account.optString("mc_username", "").isEmpty()) {
                    main = true;
                    break;
                }
            }
        }

        String model = device.optString("model", "");
        if (model.isEmpty()) model = "Android device";

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(model, light ? 0xFF1A1A1A : 0xFFF5F5F7, 16, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (current) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            header.addView(badge(getString(R.string.devices_this_device), 0xFFFF8C38, light), lp);
        } else if (main) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            header.addView(badge(getString(R.string.devices_main_device), 0xFF69781D, light), lp);
        }
        card.addView(header);

        String os = device.optString("os", "");
        String appVersion = device.optString("app_version", "");
        String meta = os;
        if (!appVersion.isEmpty()) {
            meta = meta.isEmpty() ? "App " + appVersion : meta + " · App " + appVersion;
        }
        addLine(card, meta, secondary());

        String lastPing = device.optString("last_ping", "");
        addLine(card, lastPing.isEmpty()
                ? getString(R.string.devices_never_seen)
                : getString(R.string.devices_last_seen, formatDate(lastPing)), secondary());

        StringBuilder stats = new StringBuilder();
        int battery = device.optInt("battery", -1);
        if (battery >= 0) {
            stats.append(getString(R.string.devices_battery, battery));
            if (device.optBoolean("charging", false)) {
                stats.append(" (").append(getString(R.string.devices_charging)).append(")");
            }
        }
        int totalRam = device.optInt("total_ram_mb", 0);
        int freeRam = device.optInt("free_ram_mb", 0);
        if (totalRam > 0) {
            if (stats.length() > 0) stats.append(" · ");
            stats.append(getString(R.string.devices_ram, gb(freeRam), gb(totalRam)));
        }
        String network = device.optString("network", "");
        if (!network.isEmpty()) {
            if (stats.length() > 0) stats.append(" · ");
            stats.append(getString(R.string.devices_network, network));
        }
        addLine(card, stats.toString(), secondary());

        String names = accountNames(accounts);
        addLine(card, names.isEmpty()
                ? getString(R.string.devices_no_accounts)
                : getString(R.string.devices_accounts, names), secondary());

        if (!current) {
            final String deviceName = model;
            TextView unlink = label(getString(R.string.devices_unlink), 0xFFFF5252, 12, true);
            unlink.setPadding(0, dp(12), 0, 0);
            unlink.setClickable(true);
            unlink.setFocusable(true);
            unlink.setOnClickListener(v -> confirmUnlink(uuid, deviceName));
            card.addView(unlink);
        }
        return card;
    }

    private void confirmUnlink(String uuid, String name) {
        final KodaSheet sheet = KodaSheet.create(this,
                getString(R.string.devices_unlink_title), name);
        sheet.primary(getString(R.string.devices_unlink), v -> {
            sheet.sheet.dismiss();
            unlink(uuid);
        });
        sheet.secondary(getString(R.string.praetor_action_cancel), v -> sheet.sheet.dismiss());
        sheet.show();
    }

    private void unlink(String uuid) {
        String token = App.getPrefs(this).getString("koda_session_token", "");
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.devices_unlink_failed, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                request("POST", API + "/v1/me/devices/"
                        + java.net.URLEncoder.encode(uuid, "UTF-8") + "/unlink", token, "{}");
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.devices_unlink_done, Toast.LENGTH_SHORT).show();
                    loadDevices();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.devices_unlink_failed,
                        Toast.LENGTH_LONG).show());
            }
        }, "KodaUnlinkDevice").start();
    }

    private void showError() {
        LinearLayout container = findViewById(R.id.devices_container);
        if (container == null) return;
        container.removeAllViews();

        TextView error = label(getString(R.string.devices_error), secondary(), 13, false);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, dp(32), 0, dp(16));
        container.addView(error);

        TextView retry = label(getString(R.string.devices_retry), 0xFFFF8C38, 13, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(0, dp(12), 0, dp(12));
        retry.setClickable(true);
        retry.setFocusable(true);
        retry.setOnClickListener(v -> loadDevices());
        container.addView(retry);
    }

    private TextView badge(String text, int color, boolean light) {
        TextView badge = label(text, color, 10, true);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable background = new GradientDrawable();
        background.setColor(light ? 0xFFF0F0F0 : 0xFF2A2226);
        background.setCornerRadius(dp(20));
        badge.setBackground(background);
        return badge;
    }

    private void addLine(LinearLayout card, String text, int color) {
        if (text == null || text.isEmpty()) return;
        TextView line = label(text, color, 12, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        card.addView(line, lp);
    }

    private TextView label(String text, int color, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(0f, 1.25f);
        android.graphics.Typeface font = ResourcesCompat.getFont(this, R.font.font_koda);
        if (font != null) view.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        else if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int secondary() {
        return ThemeHelper.isLightMode(this) ? 0xFF6B6B6B : 0xFF8A8A9A;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatDate(String raw) {
        String value = raw.replace("T", " ");
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private String gb(int mb) {
        if (mb <= 0) return "0 GB";
        return String.format(Locale.US, "%.1f GB", mb / 1024f);
    }

    private String accountNames(JSONArray accounts) {
        if (accounts == null) return "";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null) continue;
            // only linked Minecraft accounts belong here. the nickname is the
            // KodaHosting username and was showing up as a Minecraft account.
            String name = account.optString("mc_username", "");
            if (!name.isEmpty()) names.add(name);
        }
        return android.text.TextUtils.join(", ", names);
    }

    private JSONObject request(String method, String url, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String text = "";
        if (input != null) {
            Scanner scanner = new Scanner(input).useDelimiter("\\A");
            text = scanner.hasNext() ? scanner.next() : "";
        }
        connection.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return new JSONObject(text);
    }
}
