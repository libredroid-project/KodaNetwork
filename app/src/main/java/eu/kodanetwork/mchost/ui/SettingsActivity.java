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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.App;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        if (eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)) {
            setContentView(R.layout.activity_settings_m3);
        } else {
            setContentView(R.layout.activity_settings);
        }

        TextView tvVersion = findViewById(R.id.tv_settings_version);
        if (tvVersion != null) {
            tvVersion.setText(eu.kodanetwork.mchost.BuildConfig.VERSION_NAME);
        }
        // the about card had a hardcoded version that drifted from the real one
        TextView cardVersion = findViewById(R.id.tv_app_version_card);
        if (cardVersion != null) {
            cardVersion.setText(eu.kodanetwork.mchost.BuildConfig.VERSION_NAME);
        }

        prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        boolean isCyber = "cyber".equals(prefs.getString("app_theme", "modern"));
        
        // guarded: the Material 3 layout does not contain every classic card, and an
        // unguarded findViewById crash would make Settings completely unreachable.
        android.view.View cardAppLicense = findViewById(R.id.card_app_license);
        if (cardAppLicense != null) {
            cardAppLicense.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, AppLicenseActivity.class)));
        }
        android.view.View cardLegal = findViewById(R.id.card_legal);
        if (cardLegal != null) {
            cardLegal.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, LicensesActivity.class)));
        }


        if (isCyber) {
            findViewById(android.R.id.content).getRootView().setBackgroundResource(R.drawable.bg_cyber_grid);
        }

        TextView tvUuid = findViewById(R.id.tv_settings_app_uuid);
        if (tvUuid != null) {
            String appUuid = prefs.getString("app_uuid", "Unknown");
            tvUuid.setText(appUuid);
        }
        
        View cardUuid = findViewById(R.id.card_app_uuid);
        if (cardUuid != null) {
            String appUuid = prefs.getString("app_uuid", "Unknown");
            cardUuid.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Koda App UUID", appUuid);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "UUID copied to clipboard", Toast.LENGTH_SHORT).show();
            });
        }

        // light mode for the settings page itself
        applyThemeModeToActivity();

        android.view.View btnBackSettings = findViewById(R.id.btn_back);
        if (btnBackSettings != null) {
            btnBackSettings.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                finish();
            });
        }

        setupAccountLink();
        setupAccountManagement();
        setupThemeMode();
        setupStyleOptions();
        setupTimeoutOptions();
        setupLanguageOptions();
        setupHapticsOptions();
        setupAnimationOptions();
        setupStorageOptions();
        setupDeviceInfo();
        setupLobbyRemote();
        setupBetaJni();
        setup2FA();
        setupBiometrics();
        setupGeminiApi();
        setupNetworkAlarmOptions();
        setupDeveloperOptions();
        setupExtensions();
        setupExtensionEntries();
        setupExtensionThemesEntry();
        
        styleTrack(findViewById(R.id.container_theme));
        styleTrack(findViewById(R.id.container_style));
        styleTrack(findViewById(R.id.container_timeout));
        
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);

        // then the premium button styling
        stylePremiumButtons();

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                
                android.view.View topBar = findViewById(R.id.settings_top_bar);
                if (topBar != null) {
                    topBar.setPadding(topBar.getPaddingLeft(), top, topBar.getPaddingRight(), topBar.getPaddingBottom());
                    topBar.getLayoutParams().height = (int)(56 * getResources().getDisplayMetrics().density) + top;
                    topBar.requestLayout();
                }

                android.view.View scrollView = findViewById(R.id.settings_scroll);
                if (scrollView != null) {
                    scrollView.setPadding(scrollView.getPaddingLeft(), scrollView.getPaddingTop(), scrollView.getPaddingRight(), bottom);
                }
                
                return insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), 0);
            });
        }
    }

    private boolean isLight() {
        return eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this);
    }

    private int devClicks = 0;
    private long lastDevClickTime = 0;

    /**
     * themes entry inside the appearance card: opens the themes screen, which
     * lists the app's standard colours plus every contributed theme with its
     * own switch. inserted below the theme pills at runtime, so both settings
     * layouts get it without touching their XML.
     */
    private void setupExtensionThemesEntry() {
        android.view.View themeContainer = findViewById(R.id.container_theme);
        if (themeContainer == null || !(themeContainer.getParent() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) themeContainer.getParent();
        final float d = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (14 * d), 0, (int) (6 * d));
        row.setBackgroundResource(themeAttrResource(android.R.attr.selectableItemBackground));
        row.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            startActivity(new android.content.Intent(this,
                    eu.kodanetwork.mchost.extension.ExtensionThemesActivity.class));
        });

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(R.string.ext_themes_row);
        label.setTextColor(0xFFE8E2D6);
        label.setTextSize(14);
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        android.widget.TextView chevron = new android.widget.TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(0xFF8A8A99);
        row.addView(chevron);

        parent.addView(row, parent.indexOfChild(themeContainer) + 1);
    }

    /** the app extensions hub (own screens, actions and automations by third parties). */
    private void setupExtensions() {
        android.view.View card = findViewById(R.id.card_extensions);
        if (card == null) return;
        card.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            startActivity(new android.content.Intent(this, eu.kodanetwork.mchost.extension.ExtensionsActivity.class));
        });
    }

    /**
     * rows contributed by extensions (contributes.settings), inserted right below
     * the extension hub card. each row carries a small info button that tells the
     * user the entry comes from an extension.
     */
    private void setupExtensionEntries() {
        final android.view.View hubCard = findViewById(R.id.card_extensions);
        if (hubCard == null || !(hubCard.getParent() instanceof android.view.ViewGroup)) return;

        java.util.List<eu.kodanetwork.mchost.extension.ExtensionRepository.SettingsEntryRef> entries =
                eu.kodanetwork.mchost.extension.ExtensionRepository.get(this).settingsEntries();
        if (entries.isEmpty()) return;

        final float d = getResources().getDisplayMetrics().density;
        final boolean m3 = eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this);
        final int titleColor = m3 ? materialColor(hubCard, com.google.android.material.R.attr.colorOnSurface) : 0xFFFFFFFF;
        final int subColor = m3 ? materialColor(hubCard, com.google.android.material.R.attr.colorOnSurfaceVariant) : 0xFF888899;

        android.widget.LinearLayout inner = new android.widget.LinearLayout(this);
        inner.setOrientation(android.widget.LinearLayout.VERTICAL);
        inner.setPadding((int) (16 * d), (int) (4 * d), (int) (16 * d), (int) (4 * d));
        for (final eu.kodanetwork.mchost.extension.ExtensionRepository.SettingsEntryRef ref : entries) {
            inner.addView(extensionEntryRow(ref, d, titleColor, subColor));
        }

        android.view.View card;
        if (m3) {
            com.google.android.material.card.MaterialCardView mcard =
                    new com.google.android.material.card.MaterialCardView(this);
            mcard.setCardBackgroundColor(materialColor(hubCard, com.google.android.material.R.attr.colorSurfaceContainerHigh));
            mcard.setRadius((int) (16 * d));
            mcard.setCardElevation(0f);
            mcard.setStrokeWidth(0);
            mcard.addView(inner);
            card = mcard;
        } else {
            androidx.cardview.widget.CardView ccard = new androidx.cardview.widget.CardView(this);
            ccard.setCardBackgroundColor(0xFF241C18);
            ccard.setRadius((int) (12 * d));
            ccard.setCardElevation(0f);
            ccard.addView(inner);
            card = ccard;
        }
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (16 * d);
        card.setLayoutParams(lp);

        android.view.ViewGroup parent = (android.view.ViewGroup) hubCard.getParent();
        parent.addView(card, parent.indexOfChild(hubCard) + 1);
    }

    private android.view.View extensionEntryRow(final eu.kodanetwork.mchost.extension.ExtensionRepository.SettingsEntryRef ref,
                                                float d, int titleColor, int subColor) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        row.setBackgroundResource(themeAttrResource(android.R.attr.selectableItemBackground));

        android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        texts.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(ref.entry.title);
        title.setTextSize(14);
        title.setTextColor(titleColor);
        title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda));
        texts.addView(title);

        if (ref.entry.subtitle != null && !ref.entry.subtitle.isEmpty()) {
            android.widget.TextView subtitle = new android.widget.TextView(this);
            subtitle.setText(ref.entry.subtitle);
            subtitle.setTextSize(12);
            subtitle.setTextColor(subColor);
            texts.addView(subtitle);
        }
        row.addView(texts);

        // the info button marks the row as extension-provided and explains which one.
        android.widget.TextView info = new android.widget.TextView(this);
        info.setText("ⓘ");
        info.setTextSize(18);
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(subColor);
        info.setBackgroundResource(themeAttrResource(android.R.attr.selectableItemBackgroundBorderless));
        info.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
        info.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            showExtensionEntryInfo(ref);
        });
        row.addView(info);

        row.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            openExtensionFromSettings(ref);
        });
        return row;
    }

    private int materialColor(android.view.View anchor, int attr) {
        try {
            return com.google.android.material.color.MaterialColors.getColor(anchor, attr);
        } catch (Throwable ignored) {
            return 0xFF888899;
        }
    }

    private int themeAttrResource(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    private void openExtensionFromSettings(eu.kodanetwork.mchost.extension.ExtensionRepository.SettingsEntryRef ref) {
        if (ref.extension.hasUi()) {
            android.content.Intent intent = new android.content.Intent(this,
                    eu.kodanetwork.mchost.extension.ExtensionHostActivity.class);
            intent.putExtra("id", ref.extension.id);
            if (ref.entry.target != null && !ref.entry.target.isEmpty()) {
                intent.putExtra("target", ref.entry.target);
            }
            startActivity(intent);
        } else {
            eu.kodanetwork.mchost.extension.ExtensionActionsSheet.show(this, ref.extension);
        }
    }

    private void showExtensionEntryInfo(final eu.kodanetwork.mchost.extension.ExtensionRepository.SettingsEntryRef ref) {
        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.ext_settings_from, ref.extension.name, ref.extension.version));
        if (ref.extension.author != null && !ref.extension.author.isEmpty()) {
            message.append("\n").append(getString(R.string.ext_settings_author, ref.extension.author));
        }
        if (ref.extension.license != null && !ref.extension.license.isEmpty()) {
            message.append("\n").append(getString(R.string.ext_settings_license, ref.extension.license));
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(ref.entry.title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.ext_settings_open, (dialog, which) -> openExtensionFromSettings(ref))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void setupDeveloperOptions() {
        View cardAppInfo = findViewById(R.id.card_app_info);
        View cardDevOptions = findViewById(R.id.card_developer_options);
        com.google.android.material.switchmaterial.SwitchMaterial switchLiquidGlass = findViewById(R.id.switch_dev_liquid_glass);

        if (cardAppInfo == null || cardDevOptions == null || switchLiquidGlass == null) return;

        // KodaCluster: the USB accessory attach dialog lands here and opens the slave link.
        // the activity is exported so the system can deliver that intent, which means any app could
        // send the action too, so the slave link only opens when an accessory is really attached.
        if (android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(getIntent() != null ? getIntent().getAction() : null)
                && hasAttachedUsbAccessory()) {
            eu.kodanetwork.mchost.cluster.ClusterSlave.get(this).start();
        }

        // the open source button in the app info card
        android.view.View githubButton = findViewById(R.id.btn_github);
        if (githubButton != null) {
            githubButton.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/libredroid-project/KodaNetwork")));
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "https://github.com/libredroid-project/KodaNetwork",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        boolean isDevModeUnlocked = prefs.getBoolean("dev_mode_unlocked", false);
        if (isDevModeUnlocked) {
            cardDevOptions.setVisibility(View.VISIBLE);
        }

 
        // sleek redesign toggle (dev option)
        com.google.android.material.switchmaterial.SwitchMaterial swSleek = findViewById(R.id.switch_dev_sleek);
        if (swSleek != null) {
            swSleek.setChecked(prefs.getBoolean("dev_sleek_enabled", false));
            swSleek.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_sleek_enabled", checked).apply();
                Toast.makeText(this, checked ? "Sleek design enabled. Restart app." : "Sleek design disabled. Restart app.", Toast.LENGTH_LONG).show();
            });
        }

        // old icon toggle (dev option)
        com.google.android.material.switchmaterial.SwitchMaterial swOldIcon = findViewById(R.id.switch_dev_old_icon);
        if (swOldIcon != null) {
            swOldIcon.setChecked(prefs.getBoolean("dev_old_icon_enabled", false));
            swOldIcon.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_old_icon_enabled", checked).apply();
                
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.ComponentName defaultAlias = new android.content.ComponentName(this, "eu.kodanetwork.mchost.ui.MainActivityDefault");
                android.content.ComponentName oldAlias = new android.content.ComponentName(this, "eu.kodanetwork.mchost.ui.MainActivityOldIcon");
                
                if (checked) {
                    pm.setComponentEnabledSetting(oldAlias, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                    pm.setComponentEnabledSetting(defaultAlias, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                    Toast.makeText(this, "Old Icon enabled. It may take a moment to update on launcher.", Toast.LENGTH_LONG).show();
                } else {
                    pm.setComponentEnabledSetting(defaultAlias, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                    pm.setComponentEnabledSetting(oldAlias, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                    Toast.makeText(this, "Standard Icon enabled. It may take a moment to update on launcher.", Toast.LENGTH_LONG).show();
                }
            });
        }

        // KodaCluster roles (USB device cluster)
        com.google.android.material.switchmaterial.SwitchMaterial swClusterMaster = findViewById(R.id.switch_dev_cluster_master);
        if (swClusterMaster != null) {
            swClusterMaster.setChecked(prefs.getBoolean("dev_cluster_master", false));
            swClusterMaster.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_cluster_master", checked).apply();
                if (checked) eu.kodanetwork.mchost.cluster.ClusterMaster.get(this).start();
                else eu.kodanetwork.mchost.cluster.ClusterMaster.get(this).stop();
                Toast.makeText(this, checked ? "Cluster master aktiv" : "Cluster master aus", Toast.LENGTH_SHORT).show();
            });
            if (swClusterMaster.isChecked()) eu.kodanetwork.mchost.cluster.ClusterMaster.get(this).start();
        }
        com.google.android.material.button.MaterialButton btnClusterDash = findViewById(R.id.btn_cluster_dashboard);
        if (btnClusterDash != null) {
            btnClusterDash.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, eu.kodanetwork.mchost.cluster.ClusterActivity.class)));
        }
        com.google.android.material.switchmaterial.SwitchMaterial swClusterSlave = findViewById(R.id.switch_dev_cluster_slave);
        if (swClusterSlave != null) {
            swClusterSlave.setChecked(prefs.getBoolean("dev_cluster_slave", false));
            swClusterSlave.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_cluster_slave", checked).apply();
                if (checked) eu.kodanetwork.mchost.cluster.ClusterSlave.get(this).start();
                else eu.kodanetwork.mchost.cluster.ClusterSlave.get(this).stop();
                Toast.makeText(this, checked ? "Cluster slave aktiv — Kabel verbinden" : "Cluster slave aus", Toast.LENGTH_SHORT).show();
            });
            if (swClusterSlave.isChecked()) eu.kodanetwork.mchost.cluster.ClusterSlave.get(this).start();
        }

        // Redesigned server cards in DARK mode too (light mode uses them by default)
        com.google.android.material.switchmaterial.SwitchMaterial swV2Cards = findViewById(R.id.switch_dev_v2_cards);
        if (swV2Cards != null) {
            swV2Cards.setChecked(prefs.getBoolean("dev_v2_cards_dark", false));
            swV2Cards.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_v2_cards_dark", checked).apply();
                Toast.makeText(this, checked ? "Neue Karten im Dark Mode. Restart app." : "Alte Karten im Dark Mode. Restart app.", Toast.LENGTH_LONG).show();
            });
        }
       switchLiquidGlass.setChecked(prefs.getBoolean("dev_liquid_glass", false));
        switchLiquidGlass.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("dev_liquid_glass", isChecked).apply();
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            Toast.makeText(this, "LiquidGlass Mode requires app restart.", Toast.LENGTH_SHORT).show();
        });

        // dev: force a nickname sync (only useful when it is local and the DB is empty)
        com.google.android.material.button.MaterialButton btnNickSync = findViewById(R.id.btn_dev_nick_sync);
        if (btnNickSync != null) {
            btnNickSync.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
                eu.kodanetwork.mchost.util.NicknameSync.sync(this, true);
            });
        }

        // the practical developer tools
        Button btnExportLogs = findViewById(R.id.btn_dev_export_logs);
        Button btnClearCache = findViewById(R.id.btn_dev_clear_cache);
        Button btnForceCrash = findViewById(R.id.btn_dev_force_crash);
        Button btnResetOnboarding = findViewById(R.id.btn_dev_reset_onboarding);
        Button btnNukeServers = findViewById(R.id.btn_dev_nuke_servers);
        Button btnWipeJvms = findViewById(R.id.btn_dev_wipe_jvms);
        Button btnHwStats = findViewById(R.id.btn_dev_hw_stats);
        Button btnStrictMode = findViewById(R.id.btn_dev_strict_mode);

        if (btnExportLogs != null) {
            btnExportLogs.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
                try {
                    java.io.File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    java.io.File logFile = new java.io.File(downloadsDir, "kodahosting_logcat_" + System.currentTimeMillis() + ".txt");
                    Process process = Runtime.getRuntime().exec("logcat -d");
                    java.io.InputStream is = process.getInputStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                    is.close();
                    Toast.makeText(this, "Logs exported to:\n" + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Log export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 50);
                try {
                    java.io.File cacheDir = getCacheDir();
                    if (cacheDir != null && cacheDir.isDirectory()) {
                        for (java.io.File file : cacheDir.listFiles()) {
                            file.delete();
                        }
                    }
                    Toast.makeText(this, "App Cache cleared successfully.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to clear cache", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnResetOnboarding != null) {
            btnResetOnboarding.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 50);
                prefs.edit()
                        .putBoolean("onboarding_complete", false)
                        .putBoolean("eula_accepted", false)
                        .putBoolean("tutorial_completed_v2", false)
                        .putInt("tutorial_phase", 0)
                        // MainActivity checks tos_accepted_v3, not eula_accepted, so without
                        // resetting these the first-open dialog never comes back
                        .putBoolean("tos_accepted_v3", false)
                        .putLong("accepted_tos_version_ts", 0L)
                        .apply();
                Toast.makeText(this, getString(eu.kodanetwork.mchost.R.string.tutorial_replay_hint), Toast.LENGTH_LONG).show();
            });
        }

        if (btnNukeServers != null) {
            btnNukeServers.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                try {
                    java.io.File serversDir = new java.io.File(getFilesDir(), "servers");
                    if (serversDir.exists() && serversDir.isDirectory()) {
                        Runtime.getRuntime().exec("rm -rf " + serversDir.getAbsolutePath());
                        Toast.makeText(this, "All servers nuked!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No servers directory found.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to nuke: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnWipeJvms != null) {
            btnWipeJvms.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                try {
                    java.io.File jvmDir = new java.io.File(getFilesDir(), "runtimes");
                    if (jvmDir.exists() && jvmDir.isDirectory()) {
                        Runtime.getRuntime().exec("rm -rf " + jvmDir.getAbsolutePath());
                        Toast.makeText(this, "JVM Runtimes wiped! Will redownload on next start.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No JVM directory found.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to wipe JVMs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnHwStats != null) {
            btnHwStats.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
                eu.kodanetwork.mchost.util.DevOverlayManager.getInstance().toggle(this);
            });
        }

        if (btnStrictMode != null) {
            btnStrictMode.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
                android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .penaltyFlashScreen()
                        .build());
                android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build());
                Toast.makeText(this, "StrictMode Enabled! (Check Logcat & Screen flashes on I/O)", Toast.LENGTH_LONG).show();
            });
        }

        if (btnForceCrash != null) {
            btnForceCrash.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 100);
                throw new RuntimeException("Force crash via Developer Options (Testing CrashFixer)");
            });
        }

        cardAppInfo.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastDevClickTime > 500) {
                devClicks = 0;
            }
            lastDevClickTime = now;
            devClicks++;

            if (devClicks >= 10 && !prefs.getBoolean("dev_mode_unlocked", false)) {
                prefs.edit().putBoolean("dev_mode_unlocked", true).apply();
                cardDevOptions.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.dev_unlocked, Toast.LENGTH_LONG).show();
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 100);
            }
        });

        setupMaterial3Developer();
    }

    private void setupMaterial3Developer() {
        com.google.android.material.switchmaterial.SwitchMaterial switchM3 = findViewById(R.id.switch_dev_material3);
        android.widget.LinearLayout m3Options = findViewById(R.id.ll_m3_options);
        
        if (switchM3 == null || m3Options == null) return;
        
        boolean m3Enabled = prefs.getBoolean("dev_material3_enabled", false);
        switchM3.setChecked(m3Enabled);
        m3Options.setVisibility(m3Enabled ? View.VISIBLE : View.GONE);
        
        switchM3.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("dev_material3_enabled", checked).apply();
            m3Options.setVisibility(checked ? View.VISIBLE : View.GONE);
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            Toast.makeText(this, checked ? "Material 3 aktiviert" : "Material 3 deaktiviert", Toast.LENGTH_SHORT).show();
            btn.postDelayed(this::recreate, 300);
        });
        
        com.google.android.material.switchmaterial.SwitchMaterial switchTerminal = findViewById(R.id.switch_dev_terminal);
        if (switchTerminal != null) {
            switchTerminal.setChecked(prefs.getBoolean("dev_terminal_enabled", false));
            switchTerminal.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("dev_terminal_enabled", checked).apply();
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            });
        }
        
        setupM3ColorModeSegmented();
        setupM3ColorPresets();
    }

    private void setupM3ColorModeSegmented() {
        TextView btnDynamic = findViewById(R.id.btn_m3_dynamic);
        TextView btnCustom = findViewById(R.id.btn_m3_custom);
        View pill = findViewById(R.id.pill_m3_color_mode);
        View customColorSection = findViewById(R.id.ll_m3_custom_color);
        android.widget.FrameLayout container = findViewById(R.id.container_m3_color_mode);
        
        if (btnDynamic == null || btnCustom == null || pill == null || customColorSection == null) return;
        
        String mode = prefs.getString("m3_color_mode", "dynamic");
        boolean isDynamic = "dynamic".equals(mode);
        
        customColorSection.setVisibility(isDynamic ? View.GONE : View.VISIBLE);
        
        btnDynamic.setTextColor(isDynamic ? 0xFFFFFFFF : 0xFF888899);
        btnCustom.setTextColor(isDynamic ? 0xFF888899 : 0xFFFFFFFF);
        
        container.post(() -> {
            int width = container.getWidth() / 2;
            pill.getLayoutParams().width = width;
            pill.requestLayout();
            pill.setTranslationX(isDynamic ? 0 : width);
        });
        
        View.OnClickListener clickListener = v -> {
            boolean newIsDynamic = v.getId() == R.id.btn_m3_dynamic;
            if (newIsDynamic == "dynamic".equals(prefs.getString("m3_color_mode", "dynamic"))) return;
            
            prefs.edit().putString("m3_color_mode", newIsDynamic ? "dynamic" : "custom").apply();
            customColorSection.setVisibility(newIsDynamic ? View.GONE : View.VISIBLE);
            
            btnDynamic.setTextColor(newIsDynamic ? 0xFFFFFFFF : 0xFF888899);
            btnCustom.setTextColor(newIsDynamic ? 0xFF888899 : 0xFFFFFFFF);
            
            int width = container.getWidth() / 2;
            pill.animate().translationX(newIsDynamic ? 0 : width).setDuration(250)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 50);
            v.postDelayed(this::recreate, 300);
        };
        
        btnDynamic.setOnClickListener(clickListener);
        btnCustom.setOnClickListener(clickListener);
    }
    
    private void setupM3ColorPresets() {
        android.widget.LinearLayout llPresets = findViewById(R.id.ll_m3_color_presets);
        View preview = findViewById(R.id.view_m3_color_preview);
        
        if (llPresets == null || preview == null) return;
        
        int currentColor = eu.kodanetwork.mchost.util.Material3ThemeHelper.getCustomColor(this);
        
        if (preview.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable)preview.getBackground()).setColor(currentColor);
        } else {
            preview.setBackgroundColor(currentColor);
        }
        
        llPresets.removeAllViews();
        
        int margin = (int)(4 * getResources().getDisplayMetrics().density);
        int size = (int)(36 * getResources().getDisplayMetrics().density);
        
        for (int i = 0; i < eu.kodanetwork.mchost.util.Material3ThemeHelper.COLOR_PRESETS.length; i++) {
            final int color = eu.kodanetwork.mchost.util.Material3ThemeHelper.COLOR_PRESETS[i];
            
            View circle = new View(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            circle.setLayoutParams(lp);
            
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(color);
            if (color == currentColor) {
                gd.setStroke((int)(3 * getResources().getDisplayMetrics().density), 0xFFFFFFFF);
            }
            circle.setBackground(gd);
            
            circle.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 50);
                prefs.edit().putInt("m3_custom_color", color).apply();
                v.postDelayed(this::recreate, 300);
            });
            
            llPresets.addView(circle);
        }
    }

    // colors for light/dark
    private int btnBgNormal()   { return 0x00000000; } // inactive buttons are fully transparent
    private int btnBgActive()   { return 0xFFFF6B00; } // the active button is Tactical Orange
    private int btnTextNormal() { return isLight() ? 0xFF6B7280 : 0xFF7E7E8F; } // gray when off
    private int btnTextActive() { return 0xFFFFFFFF; } // active text is white
    private int cardBg()        { return isLight() ? 0xFFFFFFFF : 0xFF16110D; }
    private int pageBg()        { return isLight() ? 0xFFF3F4F6 : 0xFF0A0807; }
    private int headerBg()      { return isLight() ? 0xFFFFFFFF : 0xFF0A0807; }
    private int textPrimary()   { return isLight() ? 0xFF111827 : 0xFFE6E6FA; } // lavender vs slate
    private int textSecondary() { return isLight() ? 0xFF6B7280 : 0xFF7E7E8F; }

    private void styleTrack(View track) {
        if (track == null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(getResources().getDisplayMetrics().density * 10); // 10dp
        gd.setColor(isLight() ? 0xFFE5E7EB : 0xFF161622); // the track behind the pill
        track.setBackground(gd);
        track.setPadding(
            (int)(2 * getResources().getDisplayMetrics().density),
            (int)(2 * getResources().getDisplayMetrics().density),
            (int)(2 * getResources().getDisplayMetrics().density),
            (int)(2 * getResources().getDisplayMetrics().density)
        );
    }

    private void tintBtn(Button btn, int bgColor, int textColor) {
        if (btn == null) return;
        if (btn instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb = (com.google.android.material.button.MaterialButton) btn;
            mb.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
            mb.setStrokeWidth(0); // no border, the orange fill is enough
            mb.setStrokeColor(android.content.res.ColorStateList.valueOf(0)); // no leftover stroke
            mb.setRippleColor(android.content.res.ColorStateList.valueOf(0x22FF6B00)); // soft orange ripple
            mb.setElevation(bgColor == btnBgActive() ? 4 : 0); // only the active one floats, gives the pill its layer
            mb.setCornerRadius((int)(10 * getResources().getDisplayMetrics().density));
        } else {
            btn.setBackgroundColor(bgColor);
        }
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
    }

    private void stylePremiumButtons() {
        eu.kodanetwork.mchost.util.HapticUtil.applyHaptics(this);
    }

    private void setupGeminiApi() {
        View layoutGemini = findViewById(R.id.layout_gemini_settings);
        String email = prefs.getString("account_email", "");
        if (!"karolbrz11212@gmail.com".equalsIgnoreCase(email)) {
            if (layoutGemini != null) layoutGemini.setVisibility(View.GONE);
            return;
        } else {
            if (layoutGemini != null) layoutGemini.setVisibility(View.VISIBLE);
        }

        android.widget.EditText etApiKey = findViewById(R.id.et_gemini_api_key);
        View btnSave = findViewById(R.id.btn_save_gemini_key);
        if (etApiKey != null) {
            String savedKey = prefs.getString("gemini_api_key", "");
            etApiKey.setText(savedKey);
            
            etApiKey.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    prefs.edit().putString("gemini_api_key", s.toString()).apply();
                }
            });
            
            if (btnSave != null) {
                btnSave.setOnClickListener(v -> {
                    String key = etApiKey.getText().toString().trim();
                    if (key.isEmpty()) return;
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    prefs.edit().putString("gemini_api_key", key).apply();
                    Toast.makeText(this, "Pinging Gemini API...", Toast.LENGTH_SHORT).show();
                    
                    new Thread(() -> {
                        try {
                            String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=" + key;
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);
                            String payload = "{\"contents\":[{\"parts\":[{\"text\":\"Ping! Reply with 'Pong!'\"}]}]}";
                            conn.getOutputStream().write(payload.getBytes());
                            
                            int code = conn.getResponseCode();
                            if (code == 200) {
                                runOnUiThread(() -> {
                                    Toast.makeText(SettingsActivity.this, "✅ Gemini API is working!", Toast.LENGTH_LONG).show();
                                    try {
                                        android.os.Vibrator vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                                        if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                                    } catch (Exception ignored) {}
                                });
                            } else {
                                runOnUiThread(() -> {
                                    Toast.makeText(SettingsActivity.this, "❌ API Error (" + code + "). Invalid Key?", Toast.LENGTH_LONG).show();
                                    try {
                                        android.os.Vibrator vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                                        if (vib != null && vib.hasVibrator()) {
                                            long[] pattern = {0, 50, 100, 50};
                                            vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                                        }
                                    } catch (Exception ignored) {}
                                });
                            }
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(SettingsActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupAccountLink();
        if (prefs.getBoolean("auto_generate_code", false) && eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
            prefs.edit().putBoolean("auto_generate_code", false).apply();
            Button btnGen = findViewById(R.id.btn_generate_code);
            if (btnGen != null) {
                // drop the cooldown, this tap is on purpose
                prefs.edit().putLong("last_code_gen", 0).apply();
                btnGen.performClick();
            }
        }
    }

    private void setupAccountLink() {
        LinearLayout container = findViewById(R.id.ll_linked_accounts_container);
        if (container == null) return;
        
        // keep the instructions and the generate button where they are
        TextView tvInstructions = findViewById(R.id.tv_link_instructions);
        Button btnGen = findViewById(R.id.btn_generate_code);
        
        // old account rows are removed when the answer arrives, not before
        
        TextView tvStatus = findViewById(R.id.tv_link_status);
        String currentCode = prefs.getString("link_code", null);
        String appUuid = prefs.getString("app_uuid", null);

        if (appUuid == null) {
            appUuid = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("app_uuid", appUuid).apply();
        }

        final String finalAppUuid = appUuid;
        
        // generate code button (only generates, unlinking lives elsewhere now)
        btnGen.setText("Link New Account");
        btnGen.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
                Toast.makeText(this, R.string.acc_link_login_first, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.design.LoginPageActivity.class);
                intent.putExtra("from_link_button", true);
                startActivity(intent);
                return;
            }
            long lastGen = prefs.getLong("last_code_gen", 0);
            if (System.currentTimeMillis() - lastGen < 60000) {
                long left = 60 - ((System.currentTimeMillis() - lastGen) / 1000);
                Toast.makeText(this, "Please wait " + left + "s before generating a new code.", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putLong("last_code_gen", System.currentTimeMillis()).apply();

            // the new code comes from rpc_regenerate_link_code: the row exists
            // exactly once per device (unique index on app_uuid), the raw INSERT
            // that used to be here created a DUPLICATE row with its own
            // device_token on every tap
            new Thread(() -> {
                try {
                    org.json.JSONObject body = new org.json.JSONObject()
                            .put("p_app_uuid", finalAppUuid)
                            .put("p_device_token", prefs.getString("device_token", ""));
                    java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/rest/v1/rpc/rpc_regenerate_link_code");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                    conn.setRequestProperty("apikey", anonKey);
                    // anon key as bearer: the session JWT expires after 1h,
                    // the device_token in the payload is what authorizes
                    conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                    conn.setDoOutput(true);
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes());
                    os.flush(); os.close();

                    int responseCode = conn.getResponseCode();
                    java.io.InputStreamReader r = new java.io.InputStreamReader(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    StringBuilder sb = new StringBuilder();
                    int c; while ((c = r.read()) != -1) sb.append((char) c);
                    r.close();
                    final String newCode = sb.toString().replace("\"", "").trim();
                    if (responseCode >= 400 || newCode.length() < 8) {
                        runOnUiThread(() -> android.widget.Toast.makeText(SettingsActivity.this,
                                "Link Error: " + sb.toString(), android.widget.Toast.LENGTH_LONG).show());
                        return;
                    }
                    runOnUiThread(() -> {
                        prefs.edit().putString("link_code", newCode).apply();
                        setupAccountLink();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> android.widget.Toast.makeText(SettingsActivity.this,
                            "Exception: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        if (currentCode != null) {
            tvStatus.setText("Code generated: " + currentCode);
            tvStatus.setTextColor(0xFFFFCC00);
            tvStatus.setVisibility(View.VISIBLE);
            tvInstructions.setText(getString(R.string.link_steps).replace("<code\\>", currentCode).replace("<code>", currentCode).replace("<代码>", currentCode));
            tvInstructions.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setVisibility(View.GONE);
            tvInstructions.setVisibility(View.GONE);
        }

        // fetch all linked accounts for this appUuid/authId
        new Thread(() -> {
            try {
                // also update last_active, one rpc call patches all rows of this app_uuid
                java.net.URL patchUrl = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_patch_user");
                java.net.HttpURLConnection patchConn = (java.net.HttpURLConnection) patchUrl.openConnection();
                patchConn.setRequestMethod("POST");
                patchConn.setRequestProperty("Content-Type", "application/json");
                String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                patchConn.setRequestProperty("apikey", anonKey);
                
                patchConn.setRequestProperty("Authorization", "Bearer " + anonKey);
                
                patchConn.setDoOutput(true);
                // the telemetry patch needs the device_token since the security fix
                String deviceToken = prefs.getString("device_token", "");
                java.io.OutputStream pos = patchConn.getOutputStream();
                pos.write(("{\"p_app_uuid\":\"" + finalAppUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_payload\": {\"app_state\":\"FOREGROUND\"}}").getBytes());
                pos.flush(); pos.close();
                patchConn.getResponseCode();

                // linked accounts go through the token RPC since the security fix
                java.net.URL accUrl = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_get_linked_accounts");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) accUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", anonKey);
                conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                java.io.OutputStream aos = conn.getOutputStream();
                aos.write(("{\"p_app_uuid\":\"" + finalAppUuid + "\", \"p_device_token\":\"" + deviceToken + "\"}").getBytes());
                aos.flush(); aos.close();

                if (conn.getResponseCode() == 200) {
                    java.io.InputStreamReader r = new java.io.InputStreamReader(conn.getInputStream());
                    StringBuilder sb = new StringBuilder();
                    int c; while ((c = r.read()) != -1) sb.append((char) c);
                    r.close();

                    String json = sb.toString();
                    if (json.length() > 5) {
                        org.json.JSONArray arr = new org.json.JSONArray(json);
                        runOnUiThread(() -> {
                            // clear the old rows right before the new ones go in
                            for (int i = container.getChildCount() - 1; i >= 0; i--) {
                                View child = container.getChildAt(i);
                                if (child.getTag() != null && child.getTag().equals("dynamic_account")) {
                                    container.removeViewAt(i);
                                }
                            }
                            renderAccounts(arr, container);
                            // do NOT clear link_code just because accounts exist:
                            // the user may have just generated a NEW code for a SECOND
                            // account. the code is only thrown away in
                            // fetchLinkStatus (consumption seen) and resolvePendingLink
                            // (Allow clicked). here: show a pending request if there is one.
                            checkPendingLinkRequest(finalAppUuid);
                        });
                    } else {
                        runOnUiThread(() -> {
                            for (int i = container.getChildCount() - 1; i >= 0; i--) {
                                View child = container.getChildAt(i);
                                if (child.getTag() != null && child.getTag().equals("dynamic_account")) {
                                    container.removeViewAt(i);
                                }
                            }
                        });
                    }
                    if (currentCode != null) {
                        fetchLinkStatus(finalAppUuid, currentCode); // keep polling
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }
    
    private void renderAccounts(org.json.JSONArray arr, LinearLayout container) {
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String mcName = obj.getString("mc_username");
                String rowId = obj.getString("id");
                boolean isMain = obj.optBoolean("is_main", false);
                
                // one row per account
                LinearLayout accLayout = new LinearLayout(this);
                accLayout.setOrientation(LinearLayout.HORIZONTAL);
                accLayout.setTag("dynamic_account");
                accLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 0, 0, 24);
                accLayout.setLayoutParams(lp);
                
                // the mc head
                android.widget.ImageView ivHead = new android.widget.ImageView(this);
                ivHead.setLayoutParams(new LinearLayout.LayoutParams(96, 96));
                new Thread(() -> {
                    try {
                        java.net.URL url = new java.net.URL("https://mc-heads.net/avatar/" + mcName + "/96");
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(url.openConnection().getInputStream());
                        runOnUiThread(() -> ivHead.setImageBitmap(bmp));
                    } catch (Exception ignored) {}
                }).start();
                accLayout.addView(ivHead);
                
                // name and role
                LinearLayout textLayout = new LinearLayout(this);
                textLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                tLp.setMargins(16, 0, 0, 0);
                textLayout.setLayoutParams(tLp);
                
                TextView tvName = new TextView(this);
                tvName.setText(mcName);
                tvName.setTextColor(0xFF00E676);
                tvName.setTextSize(16);
                if (eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this)) {
                    tvName.setTextColor(0xFF008000);
                }
                textLayout.addView(tvName);
                
                TextView tvRole = new TextView(this);
                tvRole.setText(isMain ? "Main Account" : "Secondary Account");
                tvRole.setTextColor(0xFFAAAAAA);
                tvRole.setTextSize(12);
                if (eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this)) {
                    tvRole.setTextColor(0xFF555555);
                }
                textLayout.addView(tvRole);
                
                accLayout.addView(textLayout);
                
                Button btnManage = new Button(this);
                btnManage.setText("Manage");
                btnManage.setTextSize(10);
                btnManage.setTextColor(0xFF00E676);
                btnManage.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                btnManage.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.PraetorAccountsActivity.class);
                    intent.putExtra("extra_row_id", rowId);
                    intent.putExtra("extra_mc_name", mcName);
                    startActivity(intent);
                });
                accLayout.addView(btnManage);
                
                // unlink button
                Button btnUnlink = new Button(this);
                btnUnlink.setText("Unlink");
                btnUnlink.setTextSize(10);
                btnUnlink.setTextColor(0xFFFF4444);
                btnUnlink.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                btnUnlink.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    new Thread(() -> {
                        try {
                            String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();

                            // unlinking goes through its own token RPC since the security
                            // fix (direct DELETEs and app_uuid/auth_id patches are locked)
                            int patchCode = -1;
                            try {
                                String myAppUuid = eu.kodanetwork.mchost.App.getPrefs(SettingsActivity.this).getString("app_uuid", "");
                                String deviceToken = eu.kodanetwork.mchost.App.getPrefs(SettingsActivity.this).getString("device_token", "");
                                java.net.HttpURLConnection patchConn = (java.net.HttpURLConnection) new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_unlink_account").openConnection();
                                patchConn.setRequestMethod("POST");
                                patchConn.setRequestProperty("apikey", anonKey);
                                patchConn.setRequestProperty("Authorization", "Bearer " + anonKey);
                                patchConn.setRequestProperty("Content-Type", "application/json");
                                patchConn.setDoOutput(true);
                                patchConn.getOutputStream().write(("{\"p_app_uuid\":\"" + myAppUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_target_id\":\"" + rowId + "\"}").getBytes());
                                patchCode = patchConn.getResponseCode();
                            } catch (Exception ignored) {}

                            final int finalPatchCode = patchCode;
                            if (finalPatchCode >= 200 && finalPatchCode < 300) {
                                runOnUiThread(() -> {
                                    android.content.SharedPreferences p = eu.kodanetwork.mchost.App.getPrefs(this);
                                    if (mcName.equals(p.getString("mc_username", ""))) {
                                        p.edit().remove("mc_username").apply();
                                    }
                                    Toast.makeText(this, "Account unlinked", Toast.LENGTH_SHORT).show();
                                    container.removeView(accLayout);
                                });
                            } else {
                                runOnUiThread(() -> Toast.makeText(this, "Unlink failed: HTTP " + finalPatchCode, Toast.LENGTH_LONG).show());
                            }
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                });
                accLayout.addView(btnUnlink);
                
                if (!isMain) {
                    org.json.JSONObject permsObj = obj.optJSONObject("permissions");
                    if (permsObj == null) {
                        try {
                            permsObj = new org.json.JSONObject();
                            // null permissions just stay empty, they are optional
                        } catch (Exception e) {}
                    }
                    final org.json.JSONObject currentPerms = permsObj;
                    accLayout.setClickable(true);
                    
                    android.util.TypedValue outValue = new android.util.TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                    accLayout.setBackgroundResource(outValue.resourceId);
                    
                    accLayout.setOnClickListener(v -> {
                        openPermissionsDialog(rowId, mcName, currentPerms);
                    });
                }

                // sits right above the Generate Code button
                container.addView(accLayout, container.getChildCount() - 1);
                
            } catch (Exception ignored) {}
        }
    }

    private void setupAccountManagement() {
        TextView tvEmail = findViewById(R.id.tv_account_email);
        androidx.cardview.widget.CardView cardAccount = findViewById(R.id.card_account);
        
        if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
            tvEmail.setText("Not logged in (Local Mode)");
            cardAccount.setOnClickListener(v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                startActivity(new Intent(this, eu.kodanetwork.mchost.ui.design.LoginPageActivity.class));
            });
            return;
        }

        String email = prefs.getString("account_email", "Unknown Email");
        tvEmail.setText(email);

        cardAccount.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) {
                Toast.makeText(this, "Keine Internetverbindung. Aktion abgebrochen.", Toast.LENGTH_SHORT).show();
                return;
            }

            android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
            dialog.setContentView(R.layout.dialog_praetor_account);
            dialog.setCancelable(true);

            TextView tvTitle = dialog.findViewById(R.id.tv_dialog_title);
            if (tvTitle != null) {
                String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
                tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
            }

            TextView tvAccEmail = dialog.findViewById(R.id.tv_account_email);
            if (tvAccEmail != null) tvAccEmail.setText(email);

            dialog.findViewById(R.id.btn_dialog_change_password).setOnClickListener(btn -> {
                android.app.Dialog passDialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
                passDialog.setContentView(R.layout.dialog_praetor_change_password);
                passDialog.setCancelable(true);

                TextView tvPassTitle = passDialog.findViewById(R.id.tv_dialog_title);
                if (tvPassTitle != null) {
                    String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
                    tvPassTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
                }

                android.widget.EditText inputOld = passDialog.findViewById(R.id.et_old_password);
                android.widget.EditText inputNew = passDialog.findViewById(R.id.et_new_password);
                android.widget.EditText inputConfirm = passDialog.findViewById(R.id.et_new_password_confirm);
                
                passDialog.findViewById(R.id.btn_dialog_update_password).setOnClickListener(updateBtn -> {
                    String oldPass = inputOld.getText().toString().trim();
                    String newPass = inputNew.getText().toString().trim();
                    String confirmPass = inputConfirm.getText().toString().trim();
                    
                    if (oldPass.isEmpty()) {
                        Toast.makeText(this, "Please enter your old password.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newPass.length() < 6) {
                        Toast.makeText(this, "New password must be at least 6 characters.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!newPass.equals(confirmPass)) {
                        Toast.makeText(this, "New passwords do not match.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // lock the buttons, the verify takes a moment
                    passDialog.findViewById(R.id.btn_dialog_update_password).setEnabled(false);
                    passDialog.findViewById(R.id.btn_dialog_cancel).setEnabled(false);
                    ((android.widget.Button) passDialog.findViewById(R.id.btn_dialog_update_password)).setText("Verifying...");

                    // the old password is checked by signing in with it
                    eu.kodanetwork.mchost.network.supabase.SupabaseAuth.signInWithEmail(this, email, oldPass, new eu.kodanetwork.mchost.network.supabase.SupabaseAuth.AuthCallback() {
                        @Override
                        public void onSuccess() {
                            runOnUiThread(() -> {
                                ((android.widget.Button) passDialog.findViewById(R.id.btn_dialog_update_password)).setText("Updating...");
                            });
                            // sign-in worked, so the old password was right
                            eu.kodanetwork.mchost.network.supabase.SupabaseAuth.updatePassword(SettingsActivity.this, newPass, new eu.kodanetwork.mchost.network.supabase.SupabaseAuth.AuthCallback() {
                                @Override public void onSuccess() {
                                    runOnUiThread(() -> {
                                        Toast.makeText(SettingsActivity.this, "Password updated successfully!", Toast.LENGTH_LONG).show();
                                        passDialog.dismiss();
                                    });
                                }
                                @Override public void onError(String msg) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(SettingsActivity.this, "Error updating password: " + msg, Toast.LENGTH_LONG).show();
                                        passDialog.findViewById(R.id.btn_dialog_update_password).setEnabled(true);
                                        passDialog.findViewById(R.id.btn_dialog_cancel).setEnabled(true);
                                        ((android.widget.Button) passDialog.findViewById(R.id.btn_dialog_update_password)).setText("Update Password");
                                    });
                                }
                            });
                        }
                        
                        @Override
                        public void onError(String msg) {
                            runOnUiThread(() -> {
                                Toast.makeText(SettingsActivity.this, "Old password is incorrect.", Toast.LENGTH_LONG).show();
                                passDialog.findViewById(R.id.btn_dialog_update_password).setEnabled(true);
                                passDialog.findViewById(R.id.btn_dialog_cancel).setEnabled(true);
                                ((android.widget.Button) passDialog.findViewById(R.id.btn_dialog_update_password)).setText("Update Password");
                            });
                        }
                    });
                });
                
                passDialog.findViewById(R.id.btn_dialog_cancel).setOnClickListener(cancelBtn -> {
                    passDialog.dismiss();
                });
                
                passDialog.show();
                dialog.dismiss();
            });

            dialog.findViewById(R.id.btn_dialog_delete_account).setOnClickListener(btn -> {
                Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.PraetorConfirmActivity.class);
                intent.putExtra("action", "delete_account");
                startActivity(intent);
                dialog.dismiss();
            });

            dialog.findViewById(R.id.btn_dialog_logout).setOnClickListener(btn -> {
                // Signing out only by accident was a common complaint, so ask once more
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.logout_confirm_title))
                        .setMessage(getString(R.string.logout_confirm_text))
                        .setPositiveButton(getString(R.string.logout_confirm_yes), (confirm, which) -> {
                            eu.kodanetwork.mchost.network.supabase.SupabaseAuth.logout(this);
                            Toast.makeText(this, "Erfolgreich abgemeldet", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            recreate();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });

            dialog.findViewById(R.id.btn_dialog_cancel).setOnClickListener(btn -> dialog.dismiss());

            // device switch: push servers to a new phone or pull them in here
            android.view.View btnSend = dialog.findViewById(R.id.btn_dialog_transfer_send);
            if (btnSend != null) {
                btnSend.setOnClickListener(btn -> {
                    dialog.dismiss();
                    openTransferPicker();
                });
            }
            android.view.View btnReceive = dialog.findViewById(R.id.btn_dialog_transfer_receive);
            if (btnReceive != null) {
                btnReceive.setOnClickListener(btn -> {
                    dialog.dismiss();
                    startActivity(new android.content.Intent(this,
                            eu.kodanetwork.mchost.transfer.TransferReceiverActivity.class));
                });
            }
            dialog.show();
        });

        setupSupport();
      }

    /**
     * server picker for the outgoing device transfer: one server goes straight
     * to the sender, several get a checkbox sheet first.
     */
    private void openTransferPicker() {
        if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
        java.util.List<eu.kodanetwork.mchost.model.ServerInstance> servers =
                eu.kodanetwork.mchost.model.ServerRepo.get(this).all();
        if (servers.isEmpty()) {
            Toast.makeText(this, getString(R.string.transfer_error_no_servers), Toast.LENGTH_SHORT).show();
            return;
        }
        if (servers.size() == 1) {
            startTransferSender(java.util.Collections.singletonList(servers.get(0).getId()));
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.KodaBottomSheetDialog);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.transfer_picker_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, eu.kodanetwork.mchost.R.font.font_koda));
        box.addView(title);

        java.util.List<android.widget.CheckBox> boxes = new java.util.ArrayList<>();
        for (eu.kodanetwork.mchost.model.ServerInstance s : servers) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(s.getName());
            cb.setTextColor(0xFFF0F0F0);
            cb.setChecked(true);
            boxes.add(cb);
            box.addView(cb);
        }

        com.google.android.material.button.MaterialButton go =
                eu.kodanetwork.mchost.util.KodaButtons.primary(this, getString(R.string.transfer_picker_start));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        go.setOnClickListener(v -> {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isChecked()) ids.add(servers.get(i).getId());
            }
            sheet.dismiss();
            if (!ids.isEmpty()) startTransferSender(ids);
        });
        box.addView(go, lp);

        sheet.setContentView(box);
        sheet.show();
    }

    private void startTransferSender(java.util.List<String> ids) {
        android.content.Intent intent = new android.content.Intent(this,
                eu.kodanetwork.mchost.transfer.TransferSenderActivity.class);
        intent.putStringArrayListExtra(eu.kodanetwork.mchost.transfer.TransferSenderActivity.EXTRA_SERVER_IDS,
                new java.util.ArrayList<>(ids));
        startActivity(intent);
    }

    private void setupSupport() {
        Button btnReportBug = findViewById(R.id.btn_report_bug);
        Button btnReportServer = findViewById(R.id.btn_report_server);
        Button btnMyTickets = findViewById(R.id.btn_my_tickets);
        View tvLoginWarning = findViewById(R.id.tv_support_login_warning);
        View llButtons = findViewById(R.id.ll_support_buttons);

        if (!eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(this)) {
            if (tvLoginWarning != null) tvLoginWarning.setVisibility(View.VISIBLE);
            if (llButtons != null) llButtons.setVisibility(View.GONE);
            return;
        } else {
            if (tvLoginWarning != null) tvLoginWarning.setVisibility(View.GONE);
            if (llButtons != null) llButtons.setVisibility(View.VISIBLE);
        }

        if (btnReportBug != null) {
            btnReportBug.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, CreateSupportTicketActivity.class);
                intent.putExtra("TICKET_TYPE", "BUG");
                startActivity(intent);
            });
        }
        
        if (btnReportServer != null) {
            btnReportServer.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, CreateSupportTicketActivity.class);
                intent.putExtra("TICKET_TYPE", "SERVER_REPORT");
                startActivity(intent);
            });
        }
        
        if (btnMyTickets != null) {
            btnMyTickets.setOnClickListener(v -> {
                startActivity(new Intent(SettingsActivity.this, SupportTicketListActivity.class));
            });
        }
    }


    private void fetchLinkStatus(String appUuid, String expectedCode) {
        new Thread(() -> {
            try {
                String deviceToken = prefs.getString("device_token", "");
                if (deviceToken.isEmpty()) return;
                for (int i = 0; i < 30; i++) {
                    Thread.sleep(2000);
                    try {
                        java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_get_pending_link");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                        conn.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                        String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}";
                        conn.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if (conn.getResponseCode() == 200) {
                            java.util.Scanner sc = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
                            String resp = sc.hasNext() ? sc.next() : "";
                            conn.disconnect();
                            resp = resp.replace("\"", "").trim();
                            if (!resp.isEmpty()) {
                                final String mc = resp;
                                runOnUiThread(() -> showLinkApprovalDialog(appUuid, mc));
                                break;
                            }
                        } else {
                            conn.disconnect();
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void syncServersToSupabase() {
        new Thread(() -> {
            try {
                String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "unknown");
                String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
                for (eu.kodanetwork.mchost.model.ServerInstance s : eu.kodanetwork.mchost.model.ServerRepo.get(this).all()) {
                    if (s.getSubdomain() == null || s.getSubdomain().isEmpty()) continue;
                    if (deviceToken.isEmpty()) break;
                    try {
                        // creating and syncing servers goes through rpc_create_server
                        // since the security fix (owner_app_uuid patches and direct
                        // INSERTs are locked, the server belongs to the token holder)
                        java.net.URL createUrl = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_create_server");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) createUrl.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setRequestProperty("apikey", anonKey);
                        conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                        conn.setDoOutput(true);
                        String json = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_host\":\"" + s.getSubdomain() + "\"}";
                        conn.getOutputStream().write(json.getBytes());
                        conn.getOutputStream().flush();
                        conn.getOutputStream().close();
                        conn.getResponseCode();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void setupStorageOptions() {
        Button btnDeleteServers = findViewById(R.id.btn_delete_servers);
        Button btnRevokeTos = findViewById(R.id.btn_revoke_tos);

        if (btnDeleteServers != null) {
            btnDeleteServers.setOnClickListener(v -> {
                if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                Intent i = new Intent(this, PraetorConfirmActivity.class);
                i.putExtra("action", "delete_servers");
                startActivity(i);
            });
        }

        if (btnRevokeTos != null) {
            btnRevokeTos.setOnClickListener(v -> {
                if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                Intent i = new Intent(this, PraetorConfirmActivity.class);
                i.putExtra("action", "revoke_tos");
                startActivity(i);
            });
        }
    }

    private void setupLobbyRemote() {
        Button btn = findViewById(R.id.btn_dev_termux);
        boolean on = prefs.getBoolean("lobby_remote_control", true);
        updateLobbyRemoteBtn(btn, on);
        
        btn.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            boolean current = prefs.getBoolean("lobby_remote_control", true);
            prefs.edit().putBoolean("lobby_remote_control", !current).apply();
            updateLobbyRemoteBtn(btn, !current);
        });
    }

    private void updateLobbyRemoteBtn(Button btn, boolean on) {
        btn.setAllCaps(false);
        btn.setText(on ? "Lobby Remote: ON" : "Lobby Remote: OFF");
        tintBtn(btn, on ? 0xFF00E676 : btnBgNormal(),
                     on ? 0xFF000000 : btnTextNormal());
    }

    private void setupBetaJni() {
        Button btn = findViewById(R.id.btn_beta_jni);
        if (btn == null) return;
        boolean legacyOn = !prefs.getBoolean("beta_jni_embedded", true); // Default is true (Standard JNI)
        updateLegacyModeBtn(btn, legacyOn);
        
        btn.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            boolean currentLegacy = !prefs.getBoolean("beta_jni_embedded", true);
            if (!currentLegacy) {
                Intent i = new Intent(this, LegacyModeWarningActivity.class);
                startActivityForResult(i, 9002);
            } else {
                prefs.edit().putBoolean("beta_jni_embedded", true).apply();
                updateLegacyModeBtn(btn, false);
            }
        });
    }

    private void updateLegacyModeBtn(Button btn, boolean legacyOn) {
        btn.setAllCaps(false);
        btn.setText(legacyOn ? "Legacy Mode: ON" : "Legacy Mode: OFF");
        tintBtn(btn, legacyOn ? 0xFFFF4444 : btnBgNormal(),
                     legacyOn ? 0xFFFFFFFF : btnTextNormal());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9002 && resultCode == RESULT_OK) {
            prefs.edit().putBoolean("beta_jni_embedded", false).apply();
            Button btn = findViewById(R.id.btn_beta_jni);
            if (btn != null) updateLegacyModeBtn(btn, true);
        }
    }

    private void openPermissionsDialog(String rowId, String mcName, org.json.JSONObject currentPerms) {
        String[] permKeys = {"perm_start", "perm_stop", "perm_hibernate", "perm_delete", "perm_players", "perm_plugins", "perm_console", "perm_settings"};
        String[] permNames = {"Start Server", "Stop Server", "Hibernate Server", "Delete Server", "Manage Players", "Manage Plugins", "Send Console Commands", "Server Settings"};
        boolean[] checkedItems = new boolean[permKeys.length];
        
        for (int i = 0; i < permKeys.length; i++) {
            checkedItems[i] = currentPerms.optBoolean(permKeys[i], false);
        }
        
        new android.app.AlertDialog.Builder(this)
            .setTitle("Permissions: " + mcName)
            .setMultiChoiceItems(permNames, checkedItems, (dialog, which, isChecked) -> {
                checkedItems[which] = isChecked;
            })
            .setPositiveButton("Save", (dialog, which) -> {
                org.json.JSONObject newPerms = new org.json.JSONObject();
                try {
                    for (int i = 0; i < permKeys.length; i++) {
                        newPerms.put(permKeys[i], checkedItems[i]);
                    }
                } catch (Exception ignored) {}
                
                String jsonStr = newPerms.toString();
                // send the permission change up
                new Thread(() -> {
                    try {
                        String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                        java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_patch_user_by_id");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("apikey", anonKey);
                        conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        String myAppUuid = eu.kodanetwork.mchost.App.getPrefs(SettingsActivity.this).getString("app_uuid", "");
                        String deviceToken = eu.kodanetwork.mchost.App.getPrefs(SettingsActivity.this).getString("device_token", "");
                        conn.getOutputStream().write(("{\"p_app_uuid\":\"" + myAppUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_id\":\"" + rowId + "\", \"p_payload\": {\"permissions\": " + jsonStr + "}}").getBytes());
                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) {
                            runOnUiThread(() -> {
                                Toast.makeText(this, "Permissions saved", Toast.LENGTH_SHORT).show();
                                setupAccountLink(); // reload UI to show changes
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(this, "Failed to save: HTTP " + code, Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    

    private void setup2FA() {
        TextView tvStatus = findViewById(R.id.tv_2fa_status);
        Button btnToggle = findViewById(R.id.btn_2fa_toggle);
        Button btnSetPassword = findViewById(R.id.btn_2fa_set_password);

        boolean is2FAEnabled = prefs.getBoolean("2fa_enabled", false);
        String password = prefs.getString("2fa_password", null);

        if (is2FAEnabled && password != null) {
            tvStatus.setText("2FA Status: Enabled");
            tvStatus.setTextColor(0xFF00E676);
            btnToggle.setText("Disable 2FA");
            btnSetPassword.setVisibility(View.VISIBLE);
            btnSetPassword.setText("Change 2FA Password");
        } else {
            tvStatus.setText("2FA Status: Disabled");
            tvStatus.setTextColor(0xFFFF4444);
            btnToggle.setText("Enable 2FA");
            btnSetPassword.setVisibility(View.GONE);
        }

        btnToggle.setOnClickListener(v -> {
            if (is2FAEnabled) {
                prefs.edit().putBoolean("2fa_enabled", false).apply();
                sync2FAToSupabase(false, prefs.getString("2fa_password", null));
                setup2FA();
            } else {
                if (password == null) {
                    promptFor2FAPassword(true);
                } else {
                    prefs.edit().putBoolean("2fa_enabled", true).apply();
                    sync2FAToSupabase(true, password);
                    setup2FA();
                }
            }
        });

        btnSetPassword.setOnClickListener(v -> {
            promptFor2FAPassword(false);
        });
    }

    private void promptFor2FAPassword(boolean enableAfter) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter a secure password");

        new android.app.AlertDialog.Builder(this)
            .setTitle("Set 2FA Password")
            .setMessage("This password will be required in Minecraft when using /myservers.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String newPass = input.getText().toString().trim();
                if (newPass.isEmpty()) {
                    Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("2fa_password", newPass).apply();
                if (enableAfter) {
                    prefs.edit().putBoolean("2fa_enabled", true).apply();
                }
                sync2FAToSupabase(prefs.getBoolean("2fa_enabled", false), newPass);
                setup2FA();
                Toast.makeText(this, "2FA Password saved!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setupBiometrics() {
        com.google.android.material.switchmaterial.SwitchMaterial swMain = findViewById(R.id.switch_bio_main);
        LinearLayout llOptions = findViewById(R.id.ll_bio_options);
        com.google.android.material.checkbox.MaterialCheckBox cbResume = findViewById(R.id.cb_bio_resume);
        com.google.android.material.checkbox.MaterialCheckBox cbCold = findViewById(R.id.cb_bio_cold_start);
        com.google.android.material.checkbox.MaterialCheckBox cbServer = findViewById(R.id.cb_bio_server_click);
        com.google.android.material.checkbox.MaterialCheckBox cbCreate = findViewById(R.id.cb_bio_create_server);
        com.google.android.material.checkbox.MaterialCheckBox cbDelete = findViewById(R.id.cb_bio_delete_server);

        boolean enabled = prefs.getBoolean("bio_enabled", false);
        swMain.setChecked(enabled);
        llOptions.setVisibility(enabled ? View.VISIBLE : View.GONE);

        cbResume.setChecked(prefs.getBoolean("bio_on_resume", false));
        cbCold.setChecked(prefs.getBoolean("bio_on_cold_start", false));
        cbServer.setChecked(prefs.getBoolean("bio_on_server_click", false));
        cbCreate.setChecked(prefs.getBoolean("bio_on_create_server", false));
        if (cbDelete != null) cbDelete.setChecked(prefs.getBoolean("bio_on_delete_server", false));

        swMain.setOnCheckedChangeListener((btn, isChecked) -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            if (isChecked) {
                eu.kodanetwork.mchost.util.BiometricHelper.startAuth(this, false);
                prefs.edit().putBoolean("bio_enabled", true).apply();
                llOptions.setVisibility(View.VISIBLE);
            } else {
                prefs.edit().putBoolean("bio_enabled", false).apply();
                llOptions.setVisibility(View.GONE);
            }
        });

        cbResume.setOnCheckedChangeListener((btn, isChecked) -> prefs.edit().putBoolean("bio_on_resume", isChecked).apply());
        cbCold.setOnCheckedChangeListener((btn, isChecked) -> prefs.edit().putBoolean("bio_on_cold_start", isChecked).apply());
        cbServer.setOnCheckedChangeListener((btn, isChecked) -> prefs.edit().putBoolean("bio_on_server_click", isChecked).apply());
        cbCreate.setOnCheckedChangeListener((btn, isChecked) -> prefs.edit().putBoolean("bio_on_create_server", isChecked).apply());
        if (cbDelete != null) {
            cbDelete.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean("bio_on_delete_server", isChecked).apply());
        }
    }

    private void sync2FAToSupabase(boolean enabled, String password) {
        String appUuid = prefs.getString("app_uuid", null);
        if (appUuid == null) return;
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_patch_user");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                conn.setRequestProperty("apikey", anonKey);
                conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                conn.setDoOutput(true);

                String deviceToken = prefs.getString("device_token", "");
                String json = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_payload\": {\"two_fa_enabled\": " + enabled + ", \"two_fa_password\": " + (password == null ? "null" : "\"" + password + "\"") + "}}";
                java.io.OutputStream os = conn.getOutputStream();
                os.write(json.getBytes());
                os.flush(); os.close();
                conn.getResponseCode();
            } catch (Exception e) {}
        }).start();
    }

    private void setupDeviceInfo() {
        View llHeader = findViewById(R.id.ll_info_header);
        View llContent = findViewById(R.id.ll_info_content);
        TextView tvIcon = findViewById(R.id.tv_info_icon);
        TextView tvText = findViewById(R.id.tv_info_text);

        llHeader.setOnClickListener(v -> {
            boolean isVisible = llContent.getVisibility() == View.VISIBLE;
            llContent.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            tvIcon.setText(isVisible ? "▼" : "▲");
            if (!isVisible) updateDeviceInfoText(tvText);
        });
    }

    private void updateDeviceInfoText(TextView tv) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long totalRam = mi.totalMem / 1048576L;
        long freeRam = mi.availMem / 1048576L;
        int cores = Runtime.getRuntime().availableProcessors();
        
        long baseServerRam = 1536; // 1.5GB
        long playerRam = 100; // 100MB per player
        long usableRam = totalRam - 2048; // 2GB stay for the OS
        if (usableRam < 0) usableRam = 0;
        
        int estimatedServers = (int) (usableRam / baseServerRam);
        int maxPlayers = estimatedServers > 0 ? (int) ((usableRam - baseServerRam) / playerRam) : 0;
        if (maxPlayers < 0) maxPlayers = 0;
        int maxPlugins = estimatedServers > 0 ? (int) (usableRam / 50) : 0; // ~50MB per plugin

        String appVersion = "Unknown";
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pInfo.versionName;
        } catch (Exception ignored) {}

        String info = "App Version: " + appVersion + "\n" +
            "Android OS: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n\n" +
            "Hardware Specs:\n" +
            "- CPU Cores: " + cores + "\n" +
            "- Total RAM: " + totalRam + " MB\n" +
            "- Free RAM: " + freeRam + " MB\n\n" +
            "Capacity Estimates:\n" +
            "- Max Servers: " + Math.max(1, estimatedServers) + "\n" +
            "- Max Players (approx): " + (maxPlayers == 0 ? 10 : maxPlayers) + "\n" +
            "- Max Plugins (approx): " + (maxPlugins == 0 ? 20 : maxPlugins);
            
        tv.setText(info);
    }

    private void deleteRecursive(java.io.File fileOrDirectory, boolean deleteRoot) {
        if (fileOrDirectory.isDirectory()) {
            java.io.File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursive(child, true);
                }
            }
        }
        if (deleteRoot) fileOrDirectory.delete();
    }


    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        App.resetAfkTimer();
        return super.dispatchTouchEvent(ev);
    }

    /** true only when the system really has a USB accessory attached, a forged intent cannot fake that. */
    private boolean hasAttachedUsbAccessory() {
        try {
            android.hardware.usb.UsbManager usb = (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
            android.hardware.usb.UsbAccessory[] accessories = usb != null ? usb.getAccessoryList() : null;
            return accessories != null && accessories.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyThemeModeToActivity() {
        boolean light = isLight();

        // tag the root and activity content so ThemeHelper leaves them alone
        View root = getWindow().getDecorView().getRootView();
        root.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");

        View settingsRoot = findViewById(R.id.settings_root);
        if (settingsRoot != null) {
            settingsRoot.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");
            settingsRoot.setBackgroundColor(pageBg());
        }

        View scrollView = findViewById(R.id.settings_scroll);
        if (scrollView != null) {
            scrollView.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");
            scrollView.setBackgroundColor(pageBg());
        }

        View contentView = findViewById(R.id.settings_content);
        if (contentView != null) {
            contentView.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");
            contentView.setBackgroundColor(pageBg());
        }

        // the top bar gets its own styling
        if (settingsRoot != null) {
            View btnBack = settingsRoot.findViewById(R.id.btn_back);
            if (btnBack != null && btnBack.getParent() instanceof View) {
                View topBar = (View) btnBack.getParent();
                topBar.setBackgroundColor(headerBg());
                topBar.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");
            }
        }

        // status bar and nav bar
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        // then the card backgrounds and text colors, all of them
        applyThemeToAllViews(getWindow().getDecorView());

        // some buttons have hardcoded dark colors, fix them in light mode
        if (light) {
            int btnBg = 0xFFE5E7EB;
            int textCol = textPrimary();
            tintBtn((Button)findViewById(R.id.btn_2fa_toggle), btnBg, textCol);
            tintBtn((Button)findViewById(R.id.btn_2fa_set_password), btnBg, textCol);
            tintBtn((Button)findViewById(R.id.btn_network_alarm), btnBg, textCol);
            tintBtn((Button)findViewById(R.id.btn_dev_termux), btnBg, textCol);
            if (findViewById(R.id.btn_beta_jni) != null) {
                if (!prefs.getBoolean("beta_jni_embedded", true)) tintBtn((Button)findViewById(R.id.btn_beta_jni), btnBg, textCol);
            }
            tintBtn((Button)findViewById(R.id.btn_delete_servers), 0xFFFFE5E5, 0xFFFF4444);
            tintBtn((Button)findViewById(R.id.btn_revoke_tos), 0xFFFFE5E5, 0xFFFF4444);

            View geminiInput = findViewById(R.id.ll_gemini_input_container);
            if (geminiInput != null) geminiInput.setBackgroundColor(btnBg);
            TextView tvGeminiInput = findViewById(R.id.et_gemini_api_key);
            if (tvGeminiInput != null) {
                tvGeminiInput.setTextColor(textCol);
                tvGeminiInput.setHintTextColor(textSecondary());
            }
        }
    }

    private void applyThemeToAllViews(View v) {
        if (v == null) return;
        boolean light = isLight();
        
        // tag it so ThemeHelper keeps its hands off
        v.setTag(eu.kodanetwork.mchost.R.id.tag_themed, "BLOCKED");

        if (v instanceof androidx.cardview.widget.CardView) {
            androidx.cardview.widget.CardView cv = (androidx.cardview.widget.CardView) v;
            cv.setCardBackgroundColor(cardBg());
            cv.setCardElevation(light ? 4f : 0f);
        }

        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeToAllViews(vg.getChildAt(i));
            }
        }

        if (v instanceof TextView && !(v instanceof Button)) {
            TextView tv = (TextView) v;
            int color = tv.getTextColors().getDefaultColor();
            
            // section headers (orange or red) stay
            if (color == 0xFFFF6B00 || color == 0xFFFF4444) {
                // keep the orange/red as it is
                return;
            }
            // status colors (green) stay
            if (color == 0xFF00E676) return;

            // white/light text becomes primary
            if (color == 0xFFFFFFFF || color == 0xFFF0F0F0 || color == 0xFFCCCCDD || color == textPrimary()) {
                tv.setTextColor(textPrimary());
            } else if (color == 0xFF888899 || color == 0xFFAAAAAA || color == 0xFF555566 || color == textSecondary()) {
                tv.setTextColor(textSecondary());
            }
            // grey/secondary text becomes secondary
            if (color == 0xFF555566 || color == 0xFF888899 || color == 0xFF8A8A9A || color == 0xFFAAAABC) {
                tv.setTextColor(textSecondary());
            }
            
            // clean sans-serif instead of monospace, the mono look is for the console
            android.graphics.Typeface koda = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda);
            tv.setTypeface(koda != null ? koda : android.graphics.Typeface.SANS_SERIF, tv.getTypeface() != null ? tv.getTypeface().getStyle() : android.graphics.Typeface.NORMAL);
        }
    }

    private void animatePill(View pill, View target) {
        if (pill == null || target == null) return;
        pill.post(() -> {
            int targetWidth = target.getWidth();
            if (targetWidth == 0) {
                target.post(() -> animatePill(pill, target));
                return;
            }
            if (pill.getWidth() != targetWidth) {
                android.view.ViewGroup.LayoutParams params = pill.getLayoutParams();
                params.width = targetWidth;
                pill.setLayoutParams(params);
            }
            pill.animate()
                .translationX(target.getX())
                .setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        });
    }

    private void setupThemeMode() {
        TextView btnDark = findViewById(R.id.btn_theme_dark);
        TextView btnLight = findViewById(R.id.btn_theme_light);
        TextView btnAuto = findViewById(R.id.btn_theme_auto);

        View.OnClickListener listener = v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            String mode = "dark";
            if (v.getId() == R.id.btn_theme_light) mode = "light";
            else if (v.getId() == R.id.btn_theme_auto) mode = "auto";

            prefs.edit().putString("theme_mode", mode).apply();
            updateThemeModeUI();
            // delay the recreate so the pill animation gets seen
            v.postDelayed(this::recreate, 250);
        };

        btnDark.setOnClickListener(listener);
        btnLight.setOnClickListener(listener);
        btnAuto.setOnClickListener(listener);
        updateThemeModeUI();
    }

    private void updateThemeModeUI() {
        String mode = prefs.getString("theme_mode", "dark");
        TextView btnDark = findViewById(R.id.btn_theme_dark);
        TextView btnLight = findViewById(R.id.btn_theme_light);
        TextView btnAuto = findViewById(R.id.btn_theme_auto);
        View pill = findViewById(R.id.pill_theme);

        TextView active = "dark".equals(mode) ? btnDark : ("light".equals(mode) ? btnLight : btnAuto);
        
        btnDark.setTextColor(btnTextNormal());
        btnLight.setTextColor(btnTextNormal());
        btnAuto.setTextColor(btnTextNormal());
        active.setTextColor(btnTextActive());

        animatePill(pill, active);
    }

    private void setupStyleOptions() {
        TextView btnSquares = findViewById(R.id.btn_style_squares);
        TextView btnBlack = findViewById(R.id.btn_style_black);
        TextView btnOff = findViewById(R.id.btn_style_off);

        View.OnClickListener listener = v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            String style = "squares";
            if (v.getId() == R.id.btn_style_black) style = "black";
            else if (v.getId() == R.id.btn_style_off) style = "off";

            prefs.edit().putString("afk_style", style).apply();
            App.resetAfkTimer();
            updateStyleUI();
        };

        btnSquares.setOnClickListener(listener);
        btnBlack.setOnClickListener(listener);
        btnOff.setOnClickListener(listener);
        updateStyleUI();
    }

    private void updateStyleUI() {
        String style = prefs.getString("afk_style", "squares");
        TextView btnSquares = findViewById(R.id.btn_style_squares);
        TextView btnBlack = findViewById(R.id.btn_style_black);
        TextView btnOff = findViewById(R.id.btn_style_off);
        View pill = findViewById(R.id.pill_style);

        TextView active = "squares".equals(style) ? btnSquares : ("black".equals(style) ? btnBlack : btnOff);

        btnSquares.setTextColor(btnTextNormal());
        btnBlack.setTextColor(btnTextNormal());
        btnOff.setTextColor(btnTextNormal());
        active.setTextColor(btnTextActive());

        animatePill(pill, active);
    }

    private void setupTimeoutOptions() {
        TextView btn1m = findViewById(R.id.btn_timeout_1m);
        TextView btn2m = findViewById(R.id.btn_timeout_2m);
        TextView btn5m = findViewById(R.id.btn_timeout_5m);

        View.OnClickListener listener = v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            int mins = 1;
            if (v.getId() == R.id.btn_timeout_2m) mins = 2;
            else if (v.getId() == R.id.btn_timeout_5m) mins = 5;

            prefs.edit().putInt("afk_timeout", mins).apply();
            App.resetAfkTimer();
            updateTimeoutUI();
        };

        btn1m.setOnClickListener(listener);
        btn2m.setOnClickListener(listener);
        btn5m.setOnClickListener(listener);
        updateTimeoutUI();
    }

    private void updateTimeoutUI() {
        int mins = prefs.getInt("afk_timeout", 1);
        TextView btn1m = findViewById(R.id.btn_timeout_1m);
        TextView btn2m = findViewById(R.id.btn_timeout_2m);
        TextView btn5m = findViewById(R.id.btn_timeout_5m);
        View pill = findViewById(R.id.pill_timeout);

        TextView active = mins == 1 ? btn1m : (mins == 2 ? btn2m : btn5m);

        btn1m.setTextColor(btnTextNormal());
        btn2m.setTextColor(btnTextNormal());
        btn5m.setTextColor(btnTextNormal());
        active.setTextColor(btnTextActive());

        animatePill(pill, active);
    }

    private void setupLanguageOptions() {
        TextView btnSelector = findViewById(R.id.btn_lang_selector);
        if (btnSelector == null) return;
        
        String[] displayLangs = {getString(R.string.system_default), "English", "English (Slang)", "Deutsch", "Polski", "简体中文"};
        final String[] codes = {"system", "en", "en-IE", "de", "pl", "zh"};
        
        String currentLang = prefs.getString("language", "system");
        for (int i=0; i<codes.length; i++) {
            if (codes[i].equals(currentLang)) {
                btnSelector.setText(displayLangs[i]);
                break;
            }
        }
        
        btnSelector.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
            
            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackgroundColor(isLight() ? 0xFFFFFFFF : 0xFF0E0E14);
            container.setPadding(0, 0, 0, 48);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(getString(R.string.select_language));
            tvTitle.setTextColor(0xFFFF6B00);
            tvTitle.setTextSize(13);
            android.graphics.Typeface kodaBold = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda);
            tvTitle.setTypeface(kodaBold != null ? kodaBold : android.graphics.Typeface.DEFAULT_BOLD);
            tvTitle.setLetterSpacing(0.12f);
            tvTitle.setPadding(48, 40, 48, 24);
            container.addView(tvTitle);

            View div = new View(this);
            div.setBackgroundColor(isLight() ? 0xFFE5E7EB : 0xFF222230);
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            container.addView(div);

            for (int i = 0; i < codes.length; i++) {
                final int pos = i;
                TextView tv = new TextView(this);
                tv.setText(displayLangs[i]);
                tv.setTextSize(16);
                tv.setPadding(48, 40, 48, 40);
                
                boolean isSelected = codes[i].equals(prefs.getString("language", "system"));
                if (isSelected) {
                    tv.setTextColor(0xFFFF6B00);
                    android.graphics.Typeface kodaBoldSel = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.font_koda);
                    tv.setTypeface(kodaBoldSel != null ? kodaBoldSel : android.graphics.Typeface.DEFAULT_BOLD);
                } else {
                    tv.setTextColor(isLight() ? 0xFF111827 : 0xFFFFFFFF);
                }
                
                android.util.TypedValue outValue = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                tv.setBackgroundResource(outValue.resourceId);
                tv.setClickable(true);
                
                tv.setOnClickListener(item -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
                    prefs.edit().putString("language", codes[pos]).apply();
                    eu.kodanetwork.mchost.App.applyLanguage(SettingsActivity.this, codes[pos]);
                    btnSelector.setText(displayLangs[pos]);
                    sheet.dismiss();
                    Toast.makeText(SettingsActivity.this, "Language updated.", Toast.LENGTH_SHORT).show();
                    recreate();
                });
                container.addView(tv);
            }

            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(container);
            sheet.setContentView(sv);
            
            android.view.Window w = sheet.getWindow();
            if (w != null) {
                int navColor = isLight() ? 0xFFFFFFFF : 0xFF0D0D14;
                w.setNavigationBarColor(navColor);
                w.setStatusBarColor(navColor);
            }
            eu.kodanetwork.mchost.util.SheetFix.apply(sheet);
            sheet.show();
        });
    }

    private void updateLanguageUI() {
        // the btn_lang_selector handles this
    }

    private void setupHapticsOptions() {
        Button btnToggle = findViewById(R.id.btn_haptics_toggle);
        if (btnToggle == null) return;

        updateHapticsUI(btnToggle);

        btnToggle.setOnClickListener(v -> {
            boolean current = prefs.getBoolean("haptics_enabled", true);
            prefs.edit().putBoolean("haptics_enabled", !current).apply();
            if (!current) eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            updateHapticsUI(btnToggle);
        });
    }

    private void updateHapticsUI(Button btn) {
        boolean enabled = prefs.getBoolean("haptics_enabled", true);
        btn.setText(enabled ? "Haptic Feedback: ON" : "Haptic Feedback: OFF");
        tintBtn(btn, enabled ? 0xFF00E676 : btnBgNormal(),
                     enabled ? 0xFF000000 : btnTextNormal());
    }

    private void setupAnimationOptions() {
        Button btnToggle = findViewById(R.id.btn_animations_toggle);
        if (btnToggle == null) return;

        updateAnimationUI(btnToggle);

        btnToggle.setOnClickListener(v -> {
            boolean current = prefs.getBoolean("animations_enabled", true);
            prefs.edit().putBoolean("animations_enabled", !current).apply();
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            updateAnimationUI(btnToggle);
        });
    }

    private void updateAnimationUI(Button btn) {
        boolean enabled = prefs.getBoolean("animations_enabled", true);
        btn.setText(enabled ? "Animations: ON" : "Animations: OFF");
        tintBtn(btn, enabled ? 0xFF00E676 : btnBgNormal(),
                     enabled ? 0xFF000000 : btnTextNormal());
    }

    private void setupNetworkAlarmOptions() {
        Button btnAlarm = findViewById(R.id.btn_network_alarm);
        if (btnAlarm == null) return;

        boolean on = prefs.getBoolean("network_alarm_enabled", false);
        updateNetworkAlarmUI(btnAlarm, on);

        btnAlarm.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
            boolean current = prefs.getBoolean("network_alarm_enabled", false);
            prefs.edit().putBoolean("network_alarm_enabled", !current).apply();
            updateNetworkAlarmUI(btnAlarm, !current);
        });
    }

    private void updateNetworkAlarmUI(Button btn, boolean on) {
        btn.setAllCaps(false);
        btn.setText(on ? "Network Alarm: ON" : "Network Alarm: OFF");
        tintBtn(btn, on ? 0xFFFF4444 : btnBgNormal(),
                     on ? 0xFFFFFFFF : btnTextNormal());
    }

    /**
     * P.R.A.E.T.O.R. confirmation: the player ran /link in the lobby and
     * waits for approval. the app shows the MC name and asks allow/deny.
     */
    private void checkPendingLinkRequest(String appUuid) {
        new Thread(() -> {
            String mcName = null;
            try {
                String deviceToken = prefs.getString("device_token", "");
                if (deviceToken.isEmpty()) return;
                java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_get_pending_link");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.setDoOutput(true);
                String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}";
                c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (c.getResponseCode() == 200) {
                    java.util.Scanner sc = new java.util.Scanner(c.getInputStream()).useDelimiter("\\A");
                    String resp = sc.hasNext() ? sc.next() : "";
                    resp = resp.replace("\"", "").trim();
                    if (!resp.isEmpty()) mcName = resp;
                }
                c.disconnect();
            } catch (Exception ignored) {}
            if (mcName != null) {
                final String mc = mcName;
                runOnUiThread(() -> showLinkApprovalDialog(appUuid, mc));
            }
        }).start();
    }

    private void showLinkApprovalDialog(String appUuid, String mcName) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.activity_praetor_warning);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(false);

        TextView tvTitle = dialog.findViewById(R.id.tv_praetor_title);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
        tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView tvIcon = dialog.findViewById(R.id.tv_warning_icon);
        tvIcon.setText("\uD83C\uDFAE");
        TextView tvReason = dialog.findViewById(R.id.tv_praetor_reason);
        tvReason.setText(getString(R.string.link_approval_msg, mcName));

        dialog.findViewById(R.id.et_math_answer).setVisibility(View.GONE);
        dialog.findViewById(R.id.tv_praetor_countdown).setVisibility(View.GONE);
        dialog.findViewById(R.id.btn_praetor_action).setVisibility(View.GONE);
        dialog.findViewById(R.id.layout_ram_buttons).setVisibility(View.VISIBLE);

        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 200);

        android.widget.Button btnAllow = dialog.findViewById(R.id.btn_praetor_fix_ram);
        btnAllow.setText(getString(R.string.link_approval_allow));
        android.widget.Button btnDeny = dialog.findViewById(R.id.btn_praetor_proceed);
        btnDeny.setText(getString(R.string.link_approval_deny));

        btnAllow.setOnClickListener(v -> {
            dialog.dismiss();
            resolvePendingLink(appUuid, true);
        });
        btnDeny.setOnClickListener(v -> {
            dialog.dismiss();
            resolvePendingLink(appUuid, false);
        });
        dialog.show();
    }

    private void resolvePendingLink(String appUuid, boolean accept) {
        new Thread(() -> {
            try {
                String deviceToken = prefs.getString("device_token", "");
                java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_resolve_pending_link");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                c.setDoOutput(true);
                String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\",\"p_accept\":" + accept + "}";
                c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                android.widget.Toast.makeText(SettingsActivity.this,
                        accept ? R.string.link_approval_done : R.string.link_approval_denied,
                        android.widget.Toast.LENGTH_SHORT).show();
                prefs.edit().remove("link_code").apply();
                setupAccountLink();
            });
        }).start();
    }

}
