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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

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
import eu.kodanetwork.mchost.util.SheetFix;

/**
 * devices & sessions as a bottom sheet. starts half expanded, can be dragged
 * up to a large screen when the account has many devices. closing it returns
 * to the main account sheet.
 */
public final class DevicesSheet {

    private static final String API = "https://api.kodanetwork.eu";

    private final Activity activity;
    private final BottomSheetDialog sheet;
    private final LinearLayout container;
    private final Runnable onClose;

    private DevicesSheet(Activity activity, BottomSheetDialog sheet,
                         View content, Runnable onClose) {
        this.activity = activity;
        this.sheet = sheet;
        this.container = content.findViewById(R.id.devices_container);
        this.onClose = onClose;
    }

    public static void show(Activity activity, Runnable onClose) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);
        View content = activity.getLayoutInflater().inflate(R.layout.sheet_devices, null);
        sheet.setContentView(content);
        KodaSheet.styleWindow(sheet.getWindow());
        KodaSheet.applyInsets(content, 24);
        SheetFix.apply(sheet);
        sheet.show();
        if (sheet.getWindow() != null) {
            sheet.getWindow().setNavigationBarColor(0xFF241C18);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                sheet.getWindow().setNavigationBarContrastEnforced(false);
            }
        }

        View bottom = (View) content.getParent();
        // the dialog wraps its sheet around the content by default, which left a
        // strip of the activity visible below the Cancel row. force the sheet
        // container to fill the screen, then the peek/expanded states work.
        ViewGroup.LayoutParams sheetParams = bottom.getLayoutParams();
        sheetParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        bottom.setLayoutParams(sheetParams);
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottom);
        // a normal bottom sheet: starts with a 70% peek and expands to full
        // screen, stopping under the status bar instead of covering it.
        behavior.setFitToContents(false);
        behavior.setSkipCollapsed(false);
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        behavior.setPeekHeight((int) (screenHeight * 0.70f));
        int statusBar = 0;
        int statusBarId = activity.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        if (statusBarId > 0) statusBar = activity.getResources().getDimensionPixelSize(statusBarId);
        behavior.setExpandedOffset(statusBar);
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

        DevicesSheet devices = new DevicesSheet(activity, sheet, content, onClose);
        devices.bind();
    }

    private void bind() {
        View close = sheet.findViewById(R.id.btn_devices_close);
        if (close != null) close.setOnClickListener(v -> closeToAccount());
        loadDevices();
    }

    private void closeToAccount() {
        sheet.dismiss();
        if (onClose != null) onClose.run();
    }

    private void loadDevices() {
        if (container == null) return;
        container.removeAllViews();

        TextView loading = label(activity.getString(R.string.devices_loading), secondary(), 13, false);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(activity, 32), 0, dp(activity, 32));
        container.addView(loading);

        String token = App.getPrefs(activity).getString("koda_session_token", "");
        if (token.isEmpty()) {
            showError();
            return;
        }

        new Thread(() -> {
            try {
                JSONObject result;
                try {
                    result = request("GET", API + "/v1/me/devices", token, null);
                } catch (Exception first) {
                    String message = first.getMessage() == null ? "" : first.getMessage();
                    // the stored supabase access token expires after an hour
                    if ((message.contains("HTTP 401") || message.contains("HTTP 403"))
                            && eu.kodanetwork.mchost.network.supabase.SupabaseAuth
                            .refreshTokenSync(activity)) {
                        String fresh = App.getPrefs(activity).getString("koda_session_token", "");
                        result = request("GET", API + "/v1/me/devices", fresh, null);
                    } else {
                        throw first;
                    }
                }
                JSONArray items = result.optJSONArray("items");
                activity.runOnUiThread(() -> render(items));
            } catch (Exception e) {
                activity.runOnUiThread(this::showError);
            }
        }, "KodaDevicesSheet").start();
    }

    private void render(JSONArray items) {
        if (container == null) return;
        container.removeAllViews();

        if (items == null || items.length() == 0) {
            TextView empty = label(activity.getString(R.string.devices_empty), secondary(), 13, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(activity, 32), 0, dp(activity, 32));
            container.addView(empty);
            return;
        }

        String currentUuid = App.getPrefs(activity).getString("app_uuid", "");
        for (int i = 0; i < items.length(); i++) {
            JSONObject device = items.optJSONObject(i);
            if (device != null) container.addView(deviceCard(device, currentUuid));
        }
    }

    private View deviceCard(JSONObject device, String currentUuid) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 16),
                dp(activity, 16), dp(activity, 16));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF241C18);
        background.setCornerRadius(dp(activity, 12));
        background.setStroke(Math.max(1, dp(activity, 1)), 0xFF2A2226);
        card.setBackground(background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(activity, 10);
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
        final String deviceName = model;
        final String kodaUser = kodaUserName(accounts);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(model, 0xFFF5F5F7, 16, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (current) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(activity, 8));
            header.addView(badge(activity.getString(R.string.devices_this_device), 0xFFFF8C38), lp);
        } else if (main) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(activity, 8));
            header.addView(badge(activity.getString(R.string.devices_main_device), 0xFF69781D), lp);
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
                ? activity.getString(R.string.devices_never_seen)
                : activity.getString(R.string.devices_last_seen, formatDate(lastPing)), secondary());

        StringBuilder stats = new StringBuilder();
        int battery = device.optInt("battery", -1);
        if (battery >= 0) {
            stats.append(activity.getString(R.string.devices_battery, battery));
            if (device.optBoolean("charging", false)) {
                stats.append(" (").append(activity.getString(R.string.devices_charging)).append(")");
            }
        }
        int totalRam = device.optInt("total_ram_mb", 0);
        int freeRam = device.optInt("free_ram_mb", 0);
        if (totalRam > 0) {
            if (stats.length() > 0) stats.append(" · ");
            stats.append(activity.getString(R.string.devices_ram, gb(freeRam), gb(totalRam)));
        }
        String network = device.optString("network", "");
        if (!network.isEmpty()) {
            if (stats.length() > 0) stats.append(" · ");
            stats.append(activity.getString(R.string.devices_network, network));
        }
        addLine(card, stats.toString(), secondary());

        if (!kodaUser.isEmpty()) {
            addLine(card, activity.getString(R.string.devices_user, kodaUser), secondary());
        }
        String names = accountNames(accounts);
        addLine(card, names.isEmpty()
                ? activity.getString(R.string.devices_no_accounts)
                : activity.getString(R.string.devices_accounts, names), secondary());

        TextView optionsHint = label(activity.getString(R.string.devices_options_hint),
                0xFFFF8C38, 12, true);
        optionsHint.setGravity(Gravity.END);
        optionsHint.setPadding(0, dp(activity, 10), 0, 0);
        card.addView(optionsHint);

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showDeviceOptions(device, current, deviceName, kodaUser));
        return card;
    }

    /** options for one device; the list sheet stays behind and the user comes back to it. */
    private void showDeviceOptions(JSONObject device, boolean current,
                                   String name, String kodaUser) {
        final String uuid = device.optString("app_uuid", "");
        final KodaSheet options = KodaSheet.create(activity, name,
                kodaUser.isEmpty() ? null
                        : activity.getString(R.string.devices_user, kodaUser));
        if (current) {
            options.row(activity.getString(R.string.devices_send_elsewhere), null, false, x -> {
                options.sheet.dismiss();
                sendServers(null);
            });
        } else {
            options.row(activity.getString(R.string.devices_send_servers), null, false, x -> {
                options.sheet.dismiss();
                sendServers(uuid);
            });
            options.divider();
            options.row(activity.getString(R.string.devices_unlink), null, true, x -> {
                options.sheet.dismiss();
                confirmUnlink(uuid, name);
            });
        }
        options.divider();
        options.row(activity.getString(R.string.devices_copy_id), null, false, x -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) activity.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Koda device ID", uuid));
            }
            Toast.makeText(activity, R.string.devices_id_copied, Toast.LENGTH_SHORT).show();
        });
        options.divider();
        options.row(activity.getString(R.string.devices_refresh), null, false, x -> {
            options.sheet.dismiss();
            loadDevices();
        });
        options.secondary(activity.getString(R.string.praetor_action_cancel),
                x -> options.sheet.dismiss());
        options.show();
    }

    /** opens the existing server picker; null target keeps the normal target picker. */
    private void sendServers(String targetAppUuid) {
        sheet.dismiss();
        if (activity instanceof SettingsActivity) {
            ((SettingsActivity) activity).openTransferPicker(targetAppUuid);
        }
    }

    private void confirmUnlink(String uuid, String name) {
        final KodaSheet sheet = KodaSheet.create(activity,
                activity.getString(R.string.devices_unlink_title), name);
        sheet.primary(activity.getString(R.string.devices_unlink), v -> {
            sheet.sheet.dismiss();
            unlink(uuid);
        });
        sheet.secondary(activity.getString(R.string.praetor_action_cancel),
                v -> sheet.sheet.dismiss());
        sheet.show();
    }

    private void unlink(String uuid) {
        String token = App.getPrefs(activity).getString("koda_session_token", "");
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.devices_unlink_failed, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                request("POST", API + "/v1/me/devices/"
                        + java.net.URLEncoder.encode(uuid, "UTF-8") + "/unlink", token, "{}");
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, R.string.devices_unlink_done, Toast.LENGTH_SHORT).show();
                    loadDevices();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        R.string.devices_unlink_failed, Toast.LENGTH_LONG).show());
            }
        }, "KodaUnlinkDevice").start();
    }

    private void showError() {
        if (container == null) return;
        container.removeAllViews();

        TextView error = label(activity.getString(R.string.devices_error), secondary(), 13, false);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, dp(activity, 32), 0, dp(activity, 16));
        container.addView(error);

        TextView retry = label(activity.getString(R.string.devices_retry), 0xFFFF8C38, 13, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        retry.setClickable(true);
        retry.setFocusable(true);
        retry.setOnClickListener(v -> loadDevices());
        container.addView(retry);
    }

    private TextView badge(String text, int color) {
        TextView badge = label(text, color, 10, true);
        badge.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF2A2226);
        background.setCornerRadius(dp(activity, 20));
        badge.setBackground(background);
        return badge;
    }

    private void addLine(LinearLayout card, String text, int color) {
        if (text == null || text.isEmpty()) return;
        TextView line = label(text, color, 12, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 6);
        card.addView(line, lp);
    }

    private TextView label(String text, int color, float size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(0f, 1.25f);
        Typeface font = ResourcesCompat.getFont(activity, R.font.font_koda);
        if (font != null) view.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        else if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int secondary() {
        return 0xFF8A8A9A;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String formatDate(String raw) {
        String value = raw.replace("T", " ");
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private static String gb(int mb) {
        if (mb <= 0) return "0 GB";
        return String.format(Locale.US, "%.1f GB", mb / 1024f);
    }

    private static String kodaUserName(JSONArray accounts) {
        if (accounts == null) return "";
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null) continue;
            String name = account.optString("nickname", "");
            if (!name.isEmpty()) return name;
        }
        return "";
    }

    private static String accountNames(JSONArray accounts) {
        if (accounts == null) return "";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null) continue;
            // only linked Minecraft accounts. the nickname is the KodaHosting
            // username and is displayed on its own line above.
            String name = account.optString("mc_username", "");
            if (!name.isEmpty()) names.add(name);
        }
        return android.text.TextUtils.join(", ", names);
    }

    private static JSONObject request(String method, String url, String token, String body) throws Exception {
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
