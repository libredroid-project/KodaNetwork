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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.LocaleHelper;
import eu.kodanetwork.mchost.util.ThemeHelper;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KodaNetwork";
    private static boolean updateDialogShown = false;

    private RecyclerView rv;
    private View tvEmpty;
    private ServerCardAdapter adapter;
    private ServerRepo repo;
    private KodaServerService svc;
    private boolean bound = false;
    private boolean tutorialRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((KodaServerService.LocalBinder) b).get();
            bound = true;
            adapter.setService(svc);
            svc.addStateCb((id, state) -> runOnUiThread(() -> adapter.notifyDataSetChanged()));
            refresh();
        }
        @Override
        public void onServiceDisconnected(ComponentName n) {
            bound = false;
            svc = null;
        }
    };

    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<Intent> storageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.SleekThemeHelper.applyTheme(this);
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        this.lastTheme = prefs.getString("app_theme", "modern");
        this.lastThemeMode = prefs.getString("theme_mode", "dark");
        this.lastM3Enabled = prefs.getBoolean("dev_material3_enabled", false);
        this.lastM3ColorMode = prefs.getString("m3_color_mode", "dynamic");
        this.lastM3CustomColor = prefs.getInt("m3_custom_color", 0xFF6750A4);
        boolean isCyber = "cyber".equals(this.lastTheme);

        String currentAppUuid = prefs.getString("app_uuid", null);
        String hwidUuid = null;
        try {
            // HWID v2: Widevine-Hardware-Hash (stabil ueber Werksreset/Reinstall),
            // Fallback ANDROID_ID. Roh-Hash -> "KODA-"+16 Zeichen wie bisher.
            String fullHwid = eu.kodanetwork.mchost.security.HWIDManager.getDeviceHWID(this);
            hwidUuid = "KODA-" + fullHwid.substring(0, 16).toUpperCase();
        } catch (Exception e) {
            String androidId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            hwidUuid = "KODA-" + androidId.substring(0, 8).toUpperCase();
        }

        if (currentAppUuid == null || !currentAppUuid.equals(hwidUuid)) {
            // Token des ALTEN Geraets sichern, bevor die UUID umgestellt wird
            // (rpc_migrate_servers verlangt seit dem Security-Fix den Token des alten Geraets)
            final String oldDeviceToken = prefs.getString("device_token", "");
            prefs.edit().putString("app_uuid", hwidUuid).apply();
            // Identitaets-Mini-Backup (Baustein 3): UUID+Token synchron halten
            writeIdentityBackup(prefs);

            // Automatic migration of servers for existing users
            if (currentAppUuid != null && !currentAppUuid.isEmpty() && !oldDeviceToken.isEmpty()) {
                String finalOld = currentAppUuid;
                String finalNew = hwidUuid;
                String finalOldToken = oldDeviceToken;
                // Nach dem Bootstrap des NEUEN Tokens einmalig die Konto-Familie
                // nachziehen (rpc_sync_auth_id), damit Adoption/Recovery greifen
                prefs.edit().putBoolean("auth_resync_pending", true).apply();
                new Thread(() -> {
                    try {
                        java.net.URL patchUrl = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_migrate_servers");
                        java.net.HttpURLConnection patchConn = (java.net.HttpURLConnection) patchUrl.openConnection();
                        patchConn.setRequestMethod("POST");
                        patchConn.setRequestProperty("Content-Type", "application/json");
                        patchConn.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                        patchConn.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                        patchConn.setRequestProperty("Prefer", "return=minimal");
                        patchConn.setDoOutput(true);
                        org.json.JSONObject pJson = new org.json.JSONObject()
                                .put("p_old_app_uuid", finalOld)
                                .put("p_old_device_token", finalOldToken)
                                .put("p_new_app_uuid", finalNew);
                        java.io.OutputStream os = patchConn.getOutputStream();
                        os.write(pJson.toString().getBytes());
                        os.flush(); os.close();
                        patchConn.getResponseCode();
                    } catch (Exception ignored) {}
                }).start();
            }
        }
        
        // Prevent screenshots & screen recording
        // Screen protection removed

        // Initialize Google Play Integrity API check
        eu.kodanetwork.mchost.security.KodaIntegrityHelper.checkIntegrity(this);

        if (eu.kodanetwork.mchost.security.AntiTamperSystem.isScaryBannedLocally(this)) {
            android.content.Intent intent = new android.content.Intent(this, eu.kodanetwork.mchost.ui.ScaryBannedActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return;
        } else if (eu.kodanetwork.mchost.security.AntiTamperSystem.isBannedLocally(this)) {
            android.content.Intent intent = new android.content.Intent(this, eu.kodanetwork.mchost.ui.BannedActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return;
        }

        // Check for Android 17+ Memory Limiter process terminations
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    java.util.List<android.app.ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
                    if (exits != null && !exits.isEmpty()) {
                        android.app.ApplicationExitInfo lastExit = exits.get(0);
                        if (lastExit.getReason() == android.app.ApplicationExitInfo.REASON_OTHER) {
                            String desc = lastExit.getDescription();
                            if (desc != null && desc.contains("MemoryLimiter")) {
                                long lastExitTime = lastExit.getTimestamp();
                                long lastShownTime = prefs.getLong("last_shown_memory_limiter_warning", 0);
                                if (lastExitTime > lastShownTime) {
                                    prefs.edit().putLong("last_shown_memory_limiter_warning", lastExitTime).apply();
                                    Intent memoryIntent = new Intent(this, eu.kodanetwork.mchost.ui.PraetorMemoryLimitActivity.class);
                                    startActivity(memoryIntent);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        eu.kodanetwork.mchost.security.AntiTamperSystem.check(this);
        eu.kodanetwork.mchost.security.TripwireObserver.startWatching(this);

        if (eu.kodanetwork.mchost.util.SleekThemeHelper.isSleekEnabled(this)) {
            setContentView(R.layout.activity_main_sleek);
        } else if (this.lastM3Enabled) {
            setContentView(R.layout.activity_main_m3);
        } else {
            setContentView(R.layout.activity_main);
        }

        showWelcomeSplash();
        maybeAskNickname();
        // KodaCluster slave runs whenever its toggle is on — not only after visiting Settings
        if (eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("dev_cluster_slave", false)) {
            eu.kodanetwork.mchost.cluster.ClusterSlave.get(this).start();
        }

        eu.kodanetwork.mchost.orchestration.DatabaseOrchestrator.ensureDatabasesExtracted(this);

        eu.kodanetwork.mchost.util.NetworkMonitorManager.init(this);
        View rootLayout = findViewById(R.id.main_root_layout);
        View topBar = findViewById(R.id.main_top_bar);
        View bottomBar = findViewById(R.id.main_bottom_bar);
        
        if (prefs.getBoolean("dev_liquid_glass", false)) {
            // Overdrive Mode LiquidGlass Background
            if (rootLayout != null) rootLayout.setBackgroundResource(R.drawable.bg_liquid_glass);
            if (topBar != null) topBar.setBackgroundColor(0x33000000);
            if (bottomBar != null) bottomBar.setBackgroundColor(0x33000000);
        } else if (ThemeHelper.isLightMode(this)
                && !eu.kodanetwork.mchost.util.SleekThemeHelper.isSleekEnabled(this)) {
            // Sleek is always warm-dark; the light-mode overrides would wash out the nav pill
            if (rootLayout != null) rootLayout.setBackgroundColor(0xFFF4EFE7);
            if (topBar != null) topBar.setBackgroundColor(0xFFF4EFE7);
            if (bottomBar != null) bottomBar.setBackgroundColor(0xFFF4EFE7);
        }
        
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {});
        storageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {});

        try {
            repo = ServerRepo.get(this);
            rv = findViewById(R.id.rv);
            tvEmpty = findViewById(R.id.tv_empty);

            adapter = new ServerCardAdapter(this, this::openServer);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);

            // Sleek uses a plain FloatingActionButton, legacy/M3 an ExtendedFloatingActionButton.
            // A hard cast here throws ClassCastException in sleek mode and silently kills
            // every wiring below (caught by the surrounding try/catch) — hence View + instanceof.
            View fabAdd = findViewById(R.id.fab_add);
            if (fabAdd instanceof ExtendedFloatingActionButton) {
                ExtendedFloatingActionButton fab = (ExtendedFloatingActionButton) fabAdd;
                if (this.lastM3Enabled) eu.kodanetwork.mchost.util.M3AnimationHelper.applySpringTouch(fab);
                fab.setText(R.string.new_server_btn);
                // In-app coach phase 1: explain the NEW SERVER button on first launch
                eu.kodanetwork.mchost.util.TutorialCoach.maybeShowNewServerHint(this, fab);
            }
            if (fabAdd != null) {
                if (eu.kodanetwork.mchost.util.SleekThemeHelper.isSleekEnabled(this)) {
                    eu.kodanetwork.mchost.util.SleekTouch.apply(fabAdd, () ->
                        startActivity(new Intent(MainActivity.this, CreateServerActivity.class)), 80);
                } else {
                    fabAdd.setOnClickListener(v -> {
                        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                        startActivity(new Intent(MainActivity.this, CreateServerActivity.class));
                    });
                }
            }

            // ── Sleek layout: nav bar buttons + header icon ──
            if (eu.kodanetwork.mchost.util.SleekThemeHelper.isSleekEnabled(this)) {
                // Custom Sleek touch feedback (no Android ripple)
                java.util.function.BiConsumer<View, Runnable> wire = (view, action) -> {
                    if (view == null) return;
                    eu.kodanetwork.mchost.util.SleekTouch.button(view, action);
                };

                wire.accept(findViewById(R.id.nav_log), () ->
                        startActivity(new android.content.Intent(MainActivity.this, eu.kodanetwork.mchost.ui.DebugLogActivity.class)));

                // Right nav button: Files — pick a server, jump straight to its Files tab
                wire.accept(findViewById(R.id.nav_files), this::showSleekFilesPicker);

                // Header settings button
                View btnSleekSettings = findViewById(R.id.btn_settings);
                if (btnSleekSettings != null) {
                    btnSleekSettings.setOnClickListener(v -> {
                        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
                        startActivity(new android.content.Intent(MainActivity.this, eu.kodanetwork.mchost.ui.SettingsActivity.class));
                    });
                }

                // Quick stats update
                updateSleekStats();
            }

            View fabDb = findViewById(R.id.fab_add_db);
            if (fabDb != null) {
                if (this.lastM3Enabled) eu.kodanetwork.mchost.util.M3AnimationHelper.applySpringTouch(fabDb);
                fabDb.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    startActivity(new Intent(this, CreateDatabaseActivity.class));
                });
            }

            
            View btnDonateMain = findViewById(R.id.btn_donate_main);
            if (btnDonateMain != null) {
                btnDonateMain.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    showSupportDialog();
                });
            }

            View btnSupportMain = findViewById(R.id.btn_support_main);
            if (btnSupportMain != null) {
                btnSupportMain.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    SupportSelectionDialog dialog = new SupportSelectionDialog(this, new SupportSelectionDialog.SupportDialogListener() {
                        @Override
                        public void onReportBugClicked() {
                            // Open create bug ticket UI
                            Intent intent = new Intent(MainActivity.this, CreateSupportTicketActivity.class);
                            intent.putExtra("TICKET_TYPE", "BUG");
                            startActivity(intent);
                        }

                        @Override
                        public void onReportServerClicked() {
                            // Open create server ticket UI
                            Intent intent = new Intent(MainActivity.this, CreateSupportTicketActivity.class);
                            intent.putExtra("TICKET_TYPE", "SERVER_REPORT");
                            startActivity(intent);
                        }

                        @Override
                        public void onMyTicketsClicked() {
                            // Open ticket list
                            startActivity(new Intent(MainActivity.this, SupportTicketListActivity.class));
                        }
                    });
                    dialog.show();
                });
            }

            View btnDebug = findViewById(R.id.btn_debug_log);
            if (btnDebug != null) {
                btnDebug.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    startActivity(new Intent(this, DebugLogActivity.class));
                });
            }

            View btnSettings = findViewById(R.id.btn_settings);
            if (btnSettings != null) {
                btnSettings.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                    startActivity(new Intent(this, SettingsActivity.class));
                });
            }



            repo.addListener(this::refresh);
            requestPerms();
            startAndBind();
            refresh();
            startPeriodicRefresh();

            String autoStartId = getIntent().getStringExtra("auto_start_server");
            if (autoStartId != null && !autoStartId.isEmpty()) {
                Intent startSvc = new Intent(this, KodaServerService.class);
                startSvc.setAction(KodaServerService.ACTION_START);
                startSvc.putExtra("id", autoStartId);
                startService(startSvc);
            }

            ThemeHelper.apply(this);
            eu.kodanetwork.mchost.util.HapticUtil.applyHaptics(this);
            // First app open: cinematic tutorial takes over (includes the ToS step);
            // the plain ToS dialog below stays as fallback for skipped tutorials
            tutorialRunning = eu.kodanetwork.mchost.util.TutorialCoach.maybeStartTutorial(this);
            if (!tutorialRunning) {
                checkToS(0);
            }
            Log.d(TAG, "MainActivity created");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    private void checkToS(long newTs) {
        if (tutorialRunning) return;
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        if (!prefs.getBoolean("tos_accepted_v3", false) || (newTs > 0 && newTs > prefs.getLong("accepted_tos_version_ts", 0))) {
            android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.setContentView(R.layout.dialog_tos);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
            dialog.setCancelable(false);

            dialog.findViewById(R.id.btn_accept_tos).setOnClickListener(v -> {
                prefs.edit().putBoolean("tos_accepted_v3", true).putLong("accepted_tos_version_ts", newTs > 0 ? newTs : System.currentTimeMillis()).apply();
                dialog.dismiss();
            });

            dialog.findViewById(R.id.btn_reject_tos).setOnClickListener(v -> {
                finishAffinity();
                System.exit(0);
            });

            TextView tvTosContent = dialog.findViewById(R.id.tv_tos_content);
            String divider = "<br><br><font color='#FF6B00'>&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;&#9644;</font><br><br>";
            String legalHtml = legalDocToHtml("tos.txt") + divider + legalDocToHtml("privacy.txt") + divider + legalDocToHtml("impressum.txt");
            tvTosContent.setText(android.text.Html.fromHtml(legalHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 200);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 300);
            }, 300);

            dialog.show();
        }
    }

    private String legalDocToHtml(String assetName) {
        String raw = readAssetText("licenses/" + assetName);
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            boolean followedByBlank = i + 1 >= lines.length || lines[i + 1].trim().isEmpty();
            boolean heading = i == 0
                    || line.matches("\\d+\\.\\s.+")
                    || (line.length() < 64 && !line.endsWith(".") && !line.startsWith("-") && followedByBlank);
            sb.append(heading ? "<b>" : "")
              .append(android.text.TextUtils.htmlEncode(line))
              .append(heading ? "</b>" : "")
              .append("<br>");
            if (followedByBlank) sb.append("<br>");
        }
        return sb.toString();
    }

    private String readAssetText(String path) {
        // Deutsche Rechtstexte bevorzugen, wenn die Geraetesprache Deutsch ist
        if (path.startsWith("licenses/") && path.endsWith(".txt") && !path.endsWith("_de.txt")) {
            java.util.Locale loc = getResources().getConfiguration().locale;
            if (loc != null && loc.getLanguage().startsWith("de")) {
                String de = readAssetTextDirect(path.replace(".txt", "_de.txt"));
                if (de != null) return de;
            }
        }
        return readAssetTextDirect(path);
    }

    private String readAssetTextDirect(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        eu.kodanetwork.mchost.App.resetAfkTimer();
    }

    private ServerInstance pendingServerOpen;

    private void openServer(ServerInstance s) {
        if (eu.kodanetwork.mchost.util.BiometricHelper.isBioEnabledFor(this, "bio_on_server_click")) {
            pendingServerOpen = s;
            Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.BiometricAuthActivity.class);
            startActivityForResult(intent, eu.kodanetwork.mchost.util.BiometricHelper.REQ_BIO_AUTH);
        } else {
            proceedOpenServer(s);
        }
    }

    private void proceedOpenServer(ServerInstance s) {
        Intent i = new Intent(this, ServerDetailActivity.class);
        i.putExtra("id", s.getId());
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == eu.kodanetwork.mchost.util.BiometricHelper.REQ_BIO_AUTH) {
            if (resultCode == RESULT_OK && pendingServerOpen != null) {
                proceedOpenServer(pendingServerOpen);
            }
            pendingServerOpen = null;
        }
        if (requestCode == REQ_RECOVERY_ZIP) {
            handleRecoveryZipResult(resultCode, data);
        }
    }

    private void refresh() {
        runOnUiThread(() -> {
            try {
                List<ServerInstance> list = repo.all();
                adapter.setData(list);
                if (tvEmpty != null) tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                
                TextView tvCount = findViewById(R.id.tv_server_count);
                if (tvCount != null) {
                    String label = list.size() == 1 ? getString(R.string.server_singular) : getString(R.string.server_plural);
                    tvCount.setText(list.size() + label);
                }
            } catch (Exception ignored) {}
        });
    }

    private void startAndBind() {
        try {
            Intent si = new Intent(this, KodaServerService.class);
            bindService(si, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {}
    }

    private void startPeriodicRefresh() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (bound) adapter.notifyDataSetChanged();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void requestPerms() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) permLauncher.launch(perms.toArray(new String[0]));

        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                String lang = java.util.Locale.getDefault().getLanguage();
                String title = lang.equals("de") ? "Akku-Optimierung deaktivieren" : "Disable Battery Optimization";
                String msg = lang.equals("de") ? 
                    "Damit dein Server mit maximaler CPU-Leistung läuft (z. B. auf Xiaomi-Geräten 20 TPS hält) und nicht im Hintergrund gedrosselt wird, musst du die Akku-Beschränkungen für diese App aufheben.\n\nBitte wähle im nächsten Menü 'Keine Beschränkungen' (No restrictions)." :
                    "To ensure your server runs at maximum CPU performance and maintains 20 TPS without background throttling, you need to remove battery restrictions for this app.\n\nPlease select 'No restrictions' in the next menu.";
                String btn = lang.equals("de") ? "Einstellungen öffnen" : "Open Settings";

                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(btn, (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .show();
            }
        } catch (Exception ignored) {}
    }

    private String lastTheme;
    private String lastThemeMode;
    private boolean lastM3Enabled;
    private String lastM3ColorMode;
    private int lastM3CustomColor;

    @Override
    protected void onResume() {
        super.onResume();
        // Coach phase 1 fires here — the tutorial may have finished while
        // MainActivity was paused behind it
        eu.kodanetwork.mchost.util.TutorialCoach.maybeShowNewServerHint(this, findViewById(R.id.fab_add));
        // Re-theme dynamically created views (server cards) so light mode
        // works here like it does in Settings
        eu.kodanetwork.mchost.util.ThemeHelper.reapply(this);
        updateSleekStats();
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        String currentTheme = prefs.getString("app_theme", "modern");
        String currentMode = prefs.getString("theme_mode", "dark");
        boolean currentM3Enabled = prefs.getBoolean("dev_material3_enabled", false);
        String currentM3ColorMode = prefs.getString("m3_color_mode", "dynamic");
        int currentM3CustomColor = prefs.getInt("m3_custom_color", 0xFF6750A4);
        
        if (!currentTheme.equals(lastTheme) || !currentMode.equals(lastThemeMode) ||
            currentM3Enabled != lastM3Enabled || !currentM3ColorMode.equals(lastM3ColorMode) ||
            currentM3CustomColor != lastM3CustomColor) {
            
            lastTheme = currentTheme;
            lastThemeMode = currentMode;
            lastM3Enabled = currentM3Enabled;
            lastM3ColorMode = currentM3ColorMode;
            lastM3CustomColor = currentM3CustomColor;
            recreate();
            return;
        }
        refresh();
        checkAppStatus();
        checkOfflineHibernations();
    }

    private void checkAppStatus() {
        new Thread(() -> {
            try {
                if (eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("is_banned_user", false)) {
                    runOnUiThread(() -> {
                        startActivity(new Intent(MainActivity.this, BannedActivity.class));
                        finish();
                    });
                    return;
                }
                String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
                String apiKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                String baseUrl = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

                // Check Ban Status
                if (!uuid.isEmpty()) {
                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                            "{\"p_app_uuid\":\"" + uuid + "\"}",
                            okhttp3.MediaType.parse("application/json")
                    );
                    okhttp3.Request userReq = new okhttp3.Request.Builder()
                        .url(baseUrl + "/rest/v1/rpc/rpc_get_is_banned")
                        .post(body)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .build();
                    try (okhttp3.Response response = client.newCall(userReq).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String json = response.body().string();
                            // Antwort ist jetzt JSON: {"banned": bool, "device_token": "..."}
                            // Der device_token ist die Berechtigung fuer alle schreibenden RPCs.
                            try {
                                org.json.JSONObject banObj = new org.json.JSONObject(json);
                                boolean banned = banObj.optBoolean("banned", false);
                                String deviceToken = banObj.optString("device_token", "");
                                if (!deviceToken.isEmpty()) {
                                    eu.kodanetwork.mchost.App.getPrefs(MainActivity.this)
                                            .edit().putString("device_token", deviceToken).apply();
                                }
                                if (banned) {
                                    eu.kodanetwork.mchost.App.getPrefs(MainActivity.this).edit().putBoolean("is_banned_user", true).apply();
                                    runOnUiThread(() -> {
                                        startActivity(new Intent(MainActivity.this, BannedActivity.class));
                                        finish();
                                    });
                                    return;
                                } else {
                                    eu.kodanetwork.mchost.App.getPrefs(MainActivity.this).edit().putBoolean("is_banned_user", false).apply();
                                }
                            } catch (org.json.JSONException ignored) {}
                        }
                    } catch (Exception ignored) {}
                }

                // HWID-Kontinuitaet: Identity-Backup schreiben, Auth-Resync nach
                // Migration, P.R.A.E.T.O.R.-Recovery-Screen (Safety-Net)
                try {
                    runRecoveryExtras(baseUrl, apiKey);
                } catch (Exception ignored) {}

                // Check Maintenance Mode
                okhttp3.Request maintReq = new okhttp3.Request.Builder()
                    .url(baseUrl + "/rest/v1/app_settings?key=eq.maintenance_mode&select=value")
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();
                try (okhttp3.Response response = client.newCall(maintReq).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        if (json.contains("\"active\":true") || json.contains("\"active\": true")) {
                            // extract reason and duration via simple string search or JSON parsing
                            String reason = "System Maintenance";
                            int duration = 60;
                            try {
                                org.json.JSONArray arr = new org.json.JSONArray(json);
                                if (arr.length() > 0) {
                                    org.json.JSONObject val = arr.getJSONObject(0).getJSONObject("value");
                                    reason = val.optString("reason", reason);
                                    duration = val.optInt("duration_minutes", duration);
                                }
                            } catch (Exception ignored) {}

                            final String finalReason = reason;
                            final int finalDuration = duration;
                            runOnUiThread(() -> {
                                Intent intent = new Intent(MainActivity.this, MaintenanceActivity.class);
                                intent.putExtra("reason", finalReason);
                                intent.putExtra("duration", finalDuration);
                                startActivity(intent);
                                finish();
                            });
                        }
                    }
                } catch (Exception ignored) {}

                // Check latest ToS
                okhttp3.Request tosReq = new okhttp3.Request.Builder()
                    .url(baseUrl + "/rest/v1/app_settings?key=eq.latest_tos_version&select=value")
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();
                try (okhttp3.Response response = client.newCall(tosReq).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        long latestTs = 0;
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(json);
                            if (arr.length() > 0) {
                                String valStr = arr.getJSONObject(0).optString("value", "0");
                                latestTs = Long.parseLong(valStr);
                            }
                        } catch (Exception ignored) {}
                        
                        long acceptedTs = eu.kodanetwork.mchost.App.getPrefs(MainActivity.this).getLong("accepted_tos_version_ts", 0);
                        if (latestTs > acceptedTs) {
                            final long fTs = latestTs;
                            runOnUiThread(() -> {
                                checkToS(fTs);
                            });
                        }
                    }
                } catch (Exception ignored) {}

                // Check latest app version
                okhttp3.Request versionReq = new okhttp3.Request.Builder()
                    .url(baseUrl + "/rest/v1/app_settings?key=eq.latest_app_version&select=value")
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();
                try (okhttp3.Response response = client.newCall(versionReq).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        int latestVer = 0;
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(json);
                            if (arr.length() > 0) {
                                String valStr = arr.getJSONObject(0).optString("value", "0");
                                latestVer = Integer.parseInt(valStr);
                            }
                        } catch (Exception ignored) {}
                        
                        if (latestVer > eu.kodanetwork.mchost.BuildConfig.VERSION_CODE) {
                            if (!updateDialogShown) {
                                updateDialogShown = true;
                                runOnUiThread(() -> showUpdateRequiredDialog());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }).start();
    }

    private void showUpdateRequiredDialog() {
        if (isFinishing() || isDestroyed()) return;
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.KodaBottomSheetDialog);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_praetor_update, null);
        dialog.setContentView(view);

        android.widget.TextView tvTitle = view.findViewById(R.id.tvUpdateTitle);
        String praetorHtml = "<font color='#555555'>P.R.</font><font color='#AAAAAA'>A.E.T.</font><font color='#FFFFFF'>O.R.</font>";
        if (tvTitle != null) tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        com.google.android.material.button.MaterialButton btnUpdate = view.findViewById(R.id.btnUpdateNow);
        com.google.android.material.button.MaterialButton btnSkip = view.findViewById(R.id.btnUpdateSkip);

        btnUpdate.setOnClickListener(v -> {
            try {
                com.google.android.play.core.appupdate.AppUpdateManager appUpdateManager = com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this);
                com.google.android.gms.tasks.Task<com.google.android.play.core.appupdate.AppUpdateInfo> appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();
                appUpdateInfoTask.addOnSuccessListener(appUpdateInfo -> {
                    if (appUpdateInfo.updateAvailability() == com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
                          && appUpdateInfo.isUpdateTypeAllowed(com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)) {
                          try {
                              appUpdateManager.startUpdateFlowForResult(appUpdateInfo, com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE, this, 123);
                          } catch (Exception e) {
                              fallbackUpdate();
                          }
                    } else {
                          fallbackUpdate();
                    }
                }).addOnFailureListener(e -> {
                    fallbackUpdate();
                });
            } catch (Exception e) {
                fallbackUpdate();
            }
        });

        new android.os.CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnSkip.setText(String.format(getString(R.string.praetor_update_skip_timer), millisUntilFinished / 1000));
            }
            @Override
            public void onFinish() {
                btnSkip.setEnabled(true);
                btnSkip.setText(getString(R.string.praetor_update_skip));
            }
        }.start();

        btnSkip.setOnClickListener(v -> dialog.dismiss());
        
        dialog.setOnShowListener(d -> {
            // Expand the bottom sheet
            com.google.android.material.bottomsheet.BottomSheetDialog d1 = (com.google.android.material.bottomsheet.BottomSheetDialog) d;
            android.widget.FrameLayout bottomSheet = d1.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet).setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        android.view.Window w = dialog.getWindow();
        if (w != null && android.os.Build.VERSION.SDK_INT >= 23) {
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
            }
            int flags = w.getDecorView().getSystemUiVisibility();
            flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; // Enforce white icons for dark Praetor background
            w.getDecorView().setSystemUiVisibility(flags);
        }

        eu.kodanetwork.mchost.util.SheetFix.apply(dialog);
        dialog.show();
    }

    private void fallbackUpdate() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + getPackageName()));
            intent.setPackage("com.android.vending");
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }

    private void checkOfflineHibernations() {
        new Thread(() -> {
            try {
                List<ServerInstance> list = repo.all();
                for (ServerInstance srv : list) {
                    if (srv.state == ServerInstance.State.OFFLINE && srv.getSubdomain() != null && !srv.getSubdomain().isEmpty()) {
                        okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/v_servers_public?host=eq." + srv.getSubdomain() + "&select=server_version")
                            .get()
                            .addHeader("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey())
                            .addHeader("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey())
                            .build();
                        try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                            if (response.isSuccessful() && response.body() != null) {
                                String json = response.body().string();
                                org.json.JSONArray arr = new org.json.JSONArray(json);
                                if (arr.length() > 0 && "HIBERNATED".equals(arr.getJSONObject(0).optString("server_version"))) {
                                    Log.d(TAG, "Server " + srv.getName() + " is offline but hibernated remotely. Zipping...");
                                    eu.kodanetwork.mchost.utils.HibernationManager.hibernateServer(this, srv, repo);
                                    refresh();
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            } catch (Exception e) {}
        }).start();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        eu.kodanetwork.mchost.App.resetAfkTimer();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            repo.removeListener(this::refresh);
            if (bound) unbindService(conn);
        } catch (Exception ignored) {}
    }

    // ── Welcome splash: covers startup for ~3s every launch ────────────────

    private android.widget.FrameLayout splashOverlay;
    // once per app process: returning from settings must not replay the splash
    private static boolean splashShownThisProcess = false;

    private void showWelcomeSplash() {
        if (splashOverlay != null || splashShownThisProcess) return;
        splashShownThisProcess = true;
        android.view.ViewGroup content = findViewById(android.R.id.content);
        float d = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(0xFF000000);
        // the real AFK screensaver background (floating Koda squares)
        overlay.addView(new eu.kodanetwork.mchost.ui.FloatingSquaresView(this), new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // ── left block at VERTICAL CENTER: logo + typed title ──
        // soft radial scrim so logo/text are gently separated from the squares
        android.view.View scrim = new android.view.View(this);
        android.graphics.drawable.GradientDrawable scrimBg = new android.graphics.drawable.GradientDrawable();
        scrimBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.BL_TR);
        scrimBg.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        scrimBg.setColors(new int[]{0x88000000, 0x22000000, 0x00000000});
        scrim.setBackground(scrimBg);
        android.widget.FrameLayout.LayoutParams scrimLp = new android.widget.FrameLayout.LayoutParams(
                (int)(420 * d), (int)(420 * d), android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        scrimLp.leftMargin = (int)(-90 * d);
        overlay.addView(scrim, scrimLp);

        android.widget.LinearLayout topLeft = new android.widget.LinearLayout(this);
        topLeft.setOrientation(android.widget.LinearLayout.VERTICAL);
        topLeft.setGravity(android.view.Gravity.START);
        android.widget.FrameLayout.LayoutParams tlLp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        tlLp.leftMargin = (int)(40 * d);
        tlLp.rightMargin = (int)(40 * d);
        overlay.addView(topLeft, tlLp);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(eu.kodanetwork.mchost.R.mipmap.ic_launcher);
        android.widget.LinearLayout.LayoutParams lLp = new android.widget.LinearLayout.LayoutParams(
                (int)(88 * d), (int)(88 * d));
        logo.setLayoutParams(lLp);
        logo.setAlpha(0f);
        logo.animate().alpha(1f).setDuration(400).start(); // no pulse at start
        topLeft.addView(logo);

        // KodaHosting types itself live in the app's pixel font, left-aligned
        android.widget.TextView typed = new android.widget.TextView(this);
        typed.setTextColor(0xFFFF8C00);
        typed.setTextSize(22);
        typed.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, eu.kodanetwork.mchost.R.font.press_start_2p));
        android.widget.LinearLayout.LayoutParams tLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = (int)(20 * d);
        typed.setLayoutParams(tLp);
        topLeft.addView(typed);

        // ── bottom-center block: three dots, below them version + source ──
        android.widget.LinearLayout bottom = new android.widget.LinearLayout(this);
        bottom.setOrientation(android.widget.LinearLayout.VERTICAL);
        bottom.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.FrameLayout.LayoutParams btLp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM);
        btLp.bottomMargin = (int)(34 * d);
        overlay.addView(bottom, btLp);

        com.airbnb.lottie.LottieAnimationView boot = new com.airbnb.lottie.LottieAnimationView(this);
        android.widget.LinearLayout.LayoutParams bLp = new android.widget.LinearLayout.LayoutParams(
                (int)(140 * d), (int)(48 * d));
        boot.setLayoutParams(bLp);
        boot.setAnimation(eu.kodanetwork.mchost.R.raw.koda_boot);
        boot.loop(true);
        boot.playAnimation();
        bottom.addView(boot);

        android.widget.TextView version = new android.widget.TextView(this);
        String vname = eu.kodanetwork.mchost.BuildConfig.VERSION_NAME;
        if (vname != null && !vname.startsWith("v")) vname = "v" + vname;
        version.setText(vname);
        version.setTextColor(0xFF8A8A9A);
        version.setTextSize(12);
        android.widget.LinearLayout.LayoutParams vLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = (int)(8 * d);
        bottom.addView(version, vLp);

        android.widget.TextView source = new android.widget.TextView(this);
        source.setText("kodanetwork.eu");
        source.setTextColor(0xFF555566);
        source.setTextSize(10);
        android.widget.LinearLayout.LayoutParams sLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = (int)(3 * d);
        bottom.addView(source, sLp);

        content.addView(overlay, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        splashOverlay = overlay;

        // type "KodaHosting" live with a blinking terminal cursor
        final String word = "KodaHosting";
        final int[] i = {0};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            @Override public void run() {
                if (splashOverlay == null) return;
                if (i[0] <= word.length()) {
                    typed.setText(word.substring(0, i[0]++));
                    h.postDelayed(tick[0], 90);
                }
            }
        };
        h.postDelayed(tick[0], 350);
        final Runnable[] blink = new Runnable[1];
        blink[0] = () -> {
            if (splashOverlay == null) return;
            CharSequence cur = typed.getText();
            boolean hasCursor = cur != null && cur.toString().endsWith("▌");
            String base = hasCursor && cur.length() > 0
                    ? cur.toString().substring(0, cur.length() - 1)
                    : (cur == null ? "" : cur.toString());
            if (base.length() <= word.length()) {
                typed.setText(base + (hasCursor ? "" : "▌"));
            }
            h.postDelayed(blink[0], 420);
        };
        h.postDelayed(blink[0], 350);

        // fade out after 3s: the typed word glides onto the real corner title
        h.postDelayed(() -> {
            if (splashOverlay == null) return;
            TextView title = findTitleView();
            if (title != null) {
                int[] tLoc = new int[2];
                int[] sLoc = new int[2];
                title.getLocationOnScreen(tLoc);
                typed.getLocationOnScreen(sLoc);
                float dx = tLoc[0] - sLoc[0];
                float dy = (tLoc[1] + title.getHeight() / 2f) - (sLoc[1] + typed.getHeight() / 2f);
                overlay.setBackgroundColor(0x000A0807);
                logo.animate().alpha(0f).setDuration(350).start();
                boot.animate().alpha(0f).setDuration(350).start();
                bottom.animate().alpha(0f).setDuration(300).start();
                typed.animate()
                        .translationX(dx).translationY(dy)
                        .scaleX(0.55f).scaleY(0.55f)
                        .setDuration(500)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                        .withEndAction(() -> {
                            if (splashOverlay != null) {
                                content.removeView(splashOverlay);
                                splashOverlay = null;
                            }
                        }).start();
            } else {
                overlay.animate().alpha(0f).setDuration(400)
                        .withEndAction(() -> {
                            if (splashOverlay != null) {
                                content.removeView(splashOverlay);
                                splashOverlay = null;
                            }
                        }).start();
            }
        }, 3000);
    }

    /** The app title is the borderless TextView showing app_name at the top left. */
    private TextView findTitleView() {
        return findTitleViewTraverse(findViewById(android.R.id.content));
    }

    private TextView findTitleViewTraverse(android.view.View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence t = tv.getText();
            if (t != null && t.toString().equals(getString(eu.kodanetwork.mchost.R.string.app_name))) {
                return tv;
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findTitleViewTraverse(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private void showSupportDialog() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_praetor_support);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        String praetorHtml = "<font color=\"#4CAF50\">DONATE</font>";
        ((android.widget.TextView) dialog.findViewById(R.id.tv_dialog_title)).setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        android.widget.TextView subtitle = dialog.findViewById(R.id.tv_dialog_subtitle);
        subtitle.setVisibility(android.view.View.GONE);
        ((android.widget.TextView) dialog.findViewById(R.id.tv_dialog_message)).setText(android.text.Html.fromHtml("KodaHosting is built and maintained by a <font color=\"#FF8C00\">single developer</font>.<br><br>By supporting me on Ko-fi, you directly help me pay for the <font color=\"#FFFFFF\">expensive server costs</font> and keep this project <font color=\"#AAAAAA\">completely ad-free</font>.<br><br>Every coffee means the world to me and keeps KodaHosting alive!", android.text.Html.FROM_HTML_MODE_LEGACY));

        android.widget.Button btnKofi = dialog.findViewById(R.id.btn_open_kofi);
        if (btnKofi != null) {
            btnKofi.setOnClickListener(v -> {
                dialog.dismiss();
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://ko-fi.com/kodahosting")));
            });
        }
        
        android.widget.Button btnCancel = dialog.findViewById(R.id.btn_dialog_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }


    /**
     * Sleek nav "Files": bottom sheet listing all servers; picking one opens the
     * server detail straight on its Files tab.
     */
    private void showSleekFilesPicker() {
        java.util.List<ServerInstance> all = repo != null ? repo.all() : java.util.Collections.emptyList();
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this, "Create a server first", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int)(20 * d);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Files");
        title.setTextColor(0xFFF2EFE9);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, (int)(14 * d));
        content.addView(title);

        for (ServerInstance s : all) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), (int)(12 * d));
            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
            rowBg.setCornerRadius(16 * d);
            rowBg.setColor(0xFF2E2A26);
            row.setBackground(rowBg);

            View dotView = new View(this);
            int dotCol;
            switch (s.state) {
                case ONLINE: dotCol = 0xFF69781D; break;
                case STARTING: case RESTARTING: dotCol = 0xFFF5A623; break;
                case CRASHED: dotCol = 0xFFE8442E; break;
                default: dotCol = 0xFF5C5852; break;
            }
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(dotCol);
            dotView.setBackground(dot);
            android.widget.LinearLayout.LayoutParams dotLp = new android.widget.LinearLayout.LayoutParams((int)(10 * d), (int)(10 * d));
            row.addView(dotView, dotLp);

            TextView name = new TextView(this);
            name.setText(s.getName());
            name.setTextColor(0xFFF2EFE9);
            name.setTextSize(16);
            android.widget.LinearLayout.LayoutParams nameLp = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = (int)(12 * d);
            row.addView(name, nameLp);

            TextView addr = new TextView(this);
            addr.setText(s.getJoinAddress());
            addr.setTextColor(0xFFF0762B);
            addr.setTextSize(12);
            row.addView(addr);

            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int)(10 * d);
            content.addView(row, rowLp);

            eu.kodanetwork.mchost.util.SleekTouch.apply(row, () -> {
                sheet.dismiss();
                android.content.Intent i = new android.content.Intent(MainActivity.this, ServerDetailActivity.class);
                i.putExtra("id", s.getId());
                i.putExtra("OPEN_TAB", "files");
                startActivity(i);
            }, 50);
        }

        sheet.setContentView(content);
        sheet.getBehavior().setPeekHeight((int)(380 * d));
        eu.kodanetwork.mchost.util.SheetFix.apply(sheet);
        sheet.show();
    }

    private void updateSleekStats() {
        // Custom (sleek) mode only — the legacy list keeps its own adapter/cards
        if (!eu.kodanetwork.mchost.util.SleekThemeHelper.isSleekEnabled(this)) return;
        try {
            java.util.List<ServerInstance> all = repo != null ? repo.all() : java.util.Collections.emptyList();

            // Sleek adapter: set once, update list on changes
            View rvView = findViewById(R.id.rv);
            if (rvView instanceof androidx.recyclerview.widget.RecyclerView && rvView.getVisibility() == View.VISIBLE) {
                androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) rvView;
                if (rv.getAdapter() == null || !(rv.getAdapter() instanceof eu.kodanetwork.mchost.ui.SleekServerAdapter)) {
                    rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
                    rv.setAdapter(new eu.kodanetwork.mchost.ui.SleekServerAdapter(all, server -> {
                        android.content.Intent i = new android.content.Intent(MainActivity.this, ServerDetailActivity.class);
                        i.putExtra("id", server.getId());
                        startActivity(i);
                    }));
                } else {
                    ((eu.kodanetwork.mchost.ui.SleekServerAdapter) rv.getAdapter()).update(all);
                }
            }
            int online = 0;
            long totalRam = 0;
            for (ServerInstance s : all) {
                if (s.state == ServerInstance.State.ONLINE) online++;
                totalRam += s.getRamMB();
            }
            TextView tvTotal = findViewById(R.id.tv_stat_total);
            TextView tvOnline = findViewById(R.id.tv_stat_online);
            TextView tvRam = findViewById(R.id.tv_stat_ram);
            if (tvTotal != null) tvTotal.setText(String.valueOf(all.size()));
            if (tvOnline != null) tvOnline.setText(String.valueOf(online));
            if (tvRam != null) tvRam.setText((totalRam / 1024) + "GB");

            TextView tvCount = findViewById(R.id.tv_server_count);
            if (tvCount != null) tvCount.setText(all.size() + " SERVERS");

            View empty = findViewById(R.id.tv_empty);
            View rv = findViewById(R.id.rv);
            if (empty != null && rv != null) {
                boolean hasServers = !all.isEmpty();
                empty.setVisibility(hasServers ? View.GONE : View.VISIBLE);
                rv.setVisibility(hasServers ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Ask for the user's nickname: at first setup, or once per day while the
     * koda_users.nickname column is still empty. Answer is PATCHed to Supabase
     * with the session JWT and cached locally.
     */
    private void maybeAskNickname() {
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        String localNick = prefs.getString("nickname", "");
        if (!localNick.isEmpty()) {
            // backfill: nickname was saved locally with a broken build that never
            // reached the DB — push it once per day until the column holds it
            if (today.equals(prefs.getString("nickname_sync_day", ""))) return;
            String appUuid2 = prefs.getString("app_uuid", "");
            if (appUuid2.isEmpty()) return;
            String token2 = prefs.getString("koda_session_token", null);
            String auth2 = token2 != null ? token2 : eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
            String base2 = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();
            eu.kodanetwork.mchost.util.NicknameSync.sync(this, false);
            prefs.edit().putString("nickname_sync_day", today).apply();
            return;
        }
        if (today.equals(prefs.getString("nickname_asked_day", ""))) return; // once per day

        String appUuid = prefs.getString("app_uuid", "");
        if (appUuid.isEmpty()) return;
        String deviceToken = prefs.getString("device_token", "");
        String baseUrl = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();

        new Thread(() -> {
            String rowNickname = null;
            // Eigenes Profil geht seit dem Security-Fix nur noch ueber die Token-gepruefte RPC
            if (!deviceToken.isEmpty()) {
                try {
                    java.net.URL url = new java.net.URL(baseUrl + "/rest/v1/rpc/rpc_get_user_profile");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                    c.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                    String body = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\"}";
                    c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    java.util.Scanner sc = new java.util.Scanner(c.getInputStream()).useDelimiter("\\A");
                    String resp = sc.hasNext() ? sc.next() : "{}";
                    org.json.JSONObject obj = new org.json.JSONObject(resp);
                    if (!obj.isNull("nickname")) rowNickname = obj.optString("nickname", "");
                    c.disconnect();
                } catch (Exception e) { rowNickname = null; }
            }

            final String existing = rowNickname;
            runOnUiThread(() -> {
                if (existing != null && !existing.isEmpty()) {
                    prefs.edit().putString("nickname", existing).apply();
                    return; // already set in DB
                }
                prefs.edit().putString("nickname_asked_day", today).apply();
                showNicknameDialog(appUuid, deviceToken, baseUrl, prefs);
            });
        }).start();
    }

    private void showNicknameDialog(String appUuid, String deviceToken, String baseUrl, android.content.SharedPreferences prefs) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_praetor_input);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView tvTitle = dialog.findViewById(R.id.tv_dialog_title);
        if (tvTitle != null) tvTitle.setText(R.string.nickname_question);
        TextView tvSub = dialog.findViewById(R.id.tv_dialog_subtitle);
        if (tvSub != null) tvSub.setVisibility(android.view.View.GONE);
        TextView tvMsg = dialog.findViewById(R.id.tv_dialog_message);
        if (tvMsg != null) tvMsg.setVisibility(android.view.View.GONE);
        android.widget.EditText input = dialog.findViewById(R.id.et_dialog_input);
        if (input != null) input.setHint("Koda");

        android.widget.Button btnCancel = dialog.findViewById(R.id.btn_dialog_cancel);
        if (btnCancel != null) btnCancel.setText(R.string.nickname_ask_later);
        dialog.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            String nick = input != null ? input.getText().toString().trim() : "";
            if (nick.isEmpty() || nick.length() > 24) {
                android.widget.Toast.makeText(this, "1-24 Zeichen", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("nickname", nick).apply();
            dialog.dismiss();
            new Thread(() -> {
                if (deviceToken == null || deviceToken.isEmpty()) return;
                try {
                    // Schreibzugriffe laufen seit dem Security-Fix ueber die Token-gepruefte RPC
                    java.net.URL url = new java.net.URL(baseUrl + "/rest/v1/rpc/rpc_patch_user");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                    c.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
                    String body = "{\"p_app_uuid\":\"" + appUuid + "\", \"p_device_token\":\"" + deviceToken + "\", \"p_payload\":{\"nickname\":\"" + nick + "\"}}";
                    c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    android.util.Log.d("Nickname", "rpc response " + c.getResponseCode());
                    c.disconnect();
                } catch (Exception e) {
                    android.util.Log.e("Nickname", "rpc failed", e);
                }
            }).start();
        });
        dialog.show();
    }

    // ─────────────────────────────────────────────────────────────────
    // HWID-Kontinuitaet + P.R.A.E.T.O.R.-Recovery (2026-09-18)

    private static final int REQ_RECOVERY_ZIP = 9104;
    private final java.util.ArrayDeque<String[]> pendingZipImports = new java.util.ArrayDeque<>();
    private String zipImportTargetId = null;

    /** Identity-Backup schreiben + Auth-Resync + Recovery-Check (aus checkAppStatus, Hintergrund-Thread). */
    private void runRecoveryExtras(String baseUrl, String apiKey) {
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        writeIdentityBackup(prefs);

        // Nach HWID-Migration einmalig die Konto-Familie nachziehen
        if (prefs.getBoolean("auth_resync_pending", false)) {
            prefs.edit().putBoolean("auth_resync_pending", false).apply();
            String authUuid = prefs.getString("auth_uuid", null);
            String appUuid = prefs.getString("app_uuid", "");
            String deviceToken = prefs.getString("device_token", "");
            if (authUuid != null && !authUuid.isEmpty() && !appUuid.isEmpty() && !deviceToken.isEmpty()) {
                try {
                    java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_sync_auth_id");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", apiKey);
                    c.setRequestProperty("Authorization", "Bearer " + apiKey);
                    c.setDoOutput(true);
                    String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_auth_id\":\"" + authUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}";
                    c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) {}
            }
        }

        // Ausstehende MC-Link-Anfrage (Lobby /link) anzeigen
        checkPendingLinkOnMain(baseUrl, apiKey);

        // Recovery-Screen nur als Safety-Net: Repo leer oder nur dateilose Hibernated-Reste
        if (prefs.getBoolean("recovery_flow_done_v1", false)) return;
        if (!prefs.getBoolean("tos_accepted_v3", false)) return;
        if (!looksLikeFreshOrPlaceholder()) return;

        java.util.List<org.json.JSONObject> candidates = fetchCloudServerCandidates(baseUrl, apiKey);
        if (candidates.isEmpty()) return;

        final java.util.List<org.json.JSONObject> finalCandidates = candidates;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            showPraetorRecoveryDialog(finalCandidates, baseUrl, apiKey);
        });
    }

    /** Klartext-Identitaetsdatei (koda_identity) fuer das System-Backup fortschreiben. */
    private void writeIdentityBackup(android.content.SharedPreferences prefs) {
        try {
            String uuid = prefs.getString("app_uuid", "");
            String token = prefs.getString("device_token", "");
            if (uuid.isEmpty()) return;
            android.content.SharedPreferences id = getSharedPreferences("koda_identity", MODE_PRIVATE);
            if (!uuid.equals(id.getString("app_uuid", null)) || !token.equals(id.getString("device_token", null))) {
                id.edit().putString("app_uuid", uuid).putString("device_token", token).apply();
            }
        } catch (Exception ignored) {}
    }

    /** True wenn die lokale Repo leer ist oder nur hibernierte Server OHNE Dateien/Zip enthaelt. */
    private boolean looksLikeFreshOrPlaceholder() {
        java.util.List<ServerInstance> all = repo.all();
        if (all.isEmpty()) return true;
        for (ServerInstance s : all) {
            if (s.state != ServerInstance.State.HIBERNATED) return false;
            java.io.File dir = s.getServerDir() == null ? null : new java.io.File(s.getServerDir());
            if (dir != null && dir.exists() && dir.isDirectory()) {
                java.io.File[] children = dir.listFiles();
                if (children != null && children.length > 0) return false;
            }
            // Hibernate-Zip daneben? Dann hat der User seine Dateien ja noch.
            if (dir != null && dir.getParentFile() != null) {
                java.io.File zip = new java.io.File(dir.getParentFile(), s.getId() + "_hibernated.zip");
                if (zip.exists()) return false;
            }
        }
        return true;
    }

    /** Eigene + Familien-Server aus der Cloud (nicht-deleted), dedupe nach host. */
    private java.util.List<org.json.JSONObject> fetchCloudServerCandidates(String baseUrl, String apiKey) {
        java.util.List<org.json.JSONObject> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
        if (appUuid.isEmpty() || deviceToken.isEmpty()) return out;

        String[][] calls = {
            {"/rest/v1/rpc/rpc_get_my_servers", "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}"},
            {"/rest/v1/rpc/rpc_get_servers_by_auth_id", "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}"},
        };
        for (String[] call : calls) {
            try {
                java.net.URL url = new java.net.URL(baseUrl + call[0]);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apiKey);
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                c.setDoOutput(true);
                c.getOutputStream().write(call[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (c.getResponseCode() == 200) {
                    java.util.Scanner sc = new java.util.Scanner(c.getInputStream()).useDelimiter("\\A");
                    String resp = sc.hasNext() ? sc.next() : "[]";
                    org.json.JSONArray arr = new org.json.JSONArray(resp);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject row = arr.getJSONObject(i);
                        String host = row.optString("host", "");
                        String ver = row.optString("server_version", "");
                        if (host.isEmpty() || host.startsWith("deleted_") || "DELETED".equals(ver)) continue;
                        if (seen.add(host)) out.add(row);
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Der P.R.A.E.T.O.R.-Recovery-Screen (praetor_warning-Layout, Import/Loeschen/Spaeter). */
    private void showPraetorRecoveryDialog(java.util.List<org.json.JSONObject> servers, String baseUrl, String apiKey) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < servers.size() && i < 5; i++) {
            if (i > 0) names.append(", ");
            names.append(servers.get(i).optString("host", "?"));
        }
        if (servers.size() > 5) names.append(", +").append(servers.size() - 5);

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.activity_praetor_warning);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(true);

        TextView tvTitle = dialog.findViewById(R.id.tv_praetor_title);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
        tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView tvIcon = dialog.findViewById(R.id.tv_warning_icon);
        tvIcon.setText("\u2744"); // Schneeflocke
        tvIcon.setTextColor(0xFF44AAFF);
        android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0.2f);
        blink.setDuration(300);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tvIcon.startAnimation(blink);

        TextView tvReason = dialog.findViewById(R.id.tv_praetor_reason);
        tvReason.setText(getString(R.string.praetor_recovery_message, servers.size(), names.toString()));

        dialog.findViewById(R.id.et_math_answer).setVisibility(View.GONE);
        dialog.findViewById(R.id.tv_praetor_countdown).setVisibility(View.GONE);
        dialog.findViewById(R.id.btn_praetor_action).setVisibility(View.GONE);
        android.view.View ramGroup = dialog.findViewById(R.id.layout_ram_buttons);
        ramGroup.setVisibility(View.VISIBLE);

        eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 200);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 300), 300);

        android.widget.Button btnImport = dialog.findViewById(R.id.btn_praetor_fix_ram);
        btnImport.setText(getString(R.string.praetor_recovery_import));
        android.widget.Button btnDelete = dialog.findViewById(R.id.btn_praetor_proceed);
        btnDelete.setText(getString(R.string.praetor_recovery_delete));
        android.widget.Button btnLater = dialog.findViewById(R.id.btn_praetor_cancel);
        btnLater.setText(getString(R.string.praetor_recovery_later));

        btnImport.setOnClickListener(v -> {
            dialog.dismiss();
            executeRecoveryImport(servers, baseUrl, apiKey);
        });
        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            executeRecoveryDelete(servers, baseUrl, apiKey);
        });
        btnLater.setOnClickListener(v -> dialog.dismiss()); // fragt beim naechsten Start wieder

        dialog.show();
    }

    /** IMPORT: adoptieren, Platzhalter MIT serverDir anlegen, je Server Zip-Picker anbieten. */
    private void executeRecoveryImport(java.util.List<org.json.JSONObject> servers, String baseUrl, String apiKey) {
        new Thread(() -> {
            callAdoptServers(baseUrl, apiKey);
            runOnUiThread(() -> {
                pendingZipImports.clear();
                for (org.json.JSONObject row : servers) {
                    String host = row.optString("host", "");
                    String id = row.optString("id", "");
                    if (host.isEmpty() || id.isEmpty()) continue;

                    ServerInstance s = new ServerInstance();
                    s.setId(id);
                    s.setName(host.startsWith("db_") ? host.substring(3) : host);
                    s.setSubdomain(host);
                    s.setType(host.startsWith("db_")
                            ? ServerInstance.Type.MARIADB
                            : ServerInstance.Type.VANILLA);
                    String ver = row.optString("server_version", "");
                    if (ver.startsWith("OFFLINE|")) ver = ver.substring(8);
                    int sp = ver.indexOf(" | ");
                    if (sp > 0) ver = ver.substring(0, sp);
                    if (ver.isEmpty() || "HIBERNATED".equals(ver)) ver = "1.21.11";
                    s.setVersion(ver);
                    s.setRamMB(row.optInt("ram_mb", 1024));
                    java.io.File dir = new java.io.File(new java.io.File(getFilesDir(), "servers"), id);
                    dir.mkdirs();
                    s.setServerDir(dir.getAbsolutePath());
                    s.state = ServerInstance.State.HIBERNATED;

                    // nur anlegen, wenn lokal noch nicht vorhanden
                    boolean exists = false;
                    for (ServerInstance other : repo.all()) {
                        if (id.equals(other.getId()) || host.equals(other.getSubdomain())) { exists = true; break; }
                    }
                    if (!exists) repo.add(s);
                    pendingZipImports.add(new String[]{id, host});
                }
                eu.kodanetwork.mchost.App.getPrefs(MainActivity.this)
                        .edit().putBoolean("recovery_flow_done_v1", true).apply();
                refresh();
                promptNextZipImport();
            });
        }).start();
    }

    /** Je Server fragen: Backup-ZIP auswaehlen oder ueberspringen. */
    private void promptNextZipImport() {
        final String[] next = pendingZipImports.pollFirst();
        if (next == null) {
            android.widget.Toast.makeText(this, R.string.praetor_recovery_imported_hint, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.activity_praetor_warning);
        eu.kodanetwork.mchost.util.DialogLandFix.apply(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(true);

        TextView tvTitle = dialog.findViewById(R.id.tv_praetor_title);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
        tvTitle.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView tvIcon = dialog.findViewById(R.id.tv_warning_icon);
        tvIcon.setText("\uD83D\uDCE6"); // Paket
        TextView tvReason = dialog.findViewById(R.id.tv_praetor_reason);
        tvReason.setText(getString(R.string.praetor_recovery_pick_zip, next[1]));

        dialog.findViewById(R.id.et_math_answer).setVisibility(View.GONE);
        dialog.findViewById(R.id.tv_praetor_countdown).setVisibility(View.GONE);
        dialog.findViewById(R.id.layout_ram_buttons).setVisibility(View.GONE);
        android.widget.Button btnAction = dialog.findViewById(R.id.btn_praetor_action);
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setText(getString(R.string.praetor_recovery_pick_zip_btn));

        btnAction.setOnClickListener(v -> {
            dialog.dismiss();
            zipImportTargetId = next[0];
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            startActivityForResult(intent, REQ_RECOVERY_ZIP);
        });
        dialog.setOnCancelListener(v -> promptNextZipImport());
        dialog.show();
    }

    private void handleRecoveryZipResult(int resultCode, android.content.Intent data) {
        String targetId = zipImportTargetId;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || targetId == null) {
            promptNextZipImport(); // uebersprungen
            return;
        }
        zipImportTargetId = null;
        final android.net.Uri uri = data.getData();
        final java.io.File targetDir = new java.io.File(new java.io.File(getFilesDir(), "servers"), targetId);
        new Thread(() -> {
            boolean ok = extractRecoveryZip(uri, targetDir);
            runOnUiThread(() -> {
                if (ok) {
                    android.widget.Toast.makeText(this,
                            getString(R.string.praetor_recovery_imported_one, targetDir.getName()),
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, R.string.praetor_recovery_zip_failed, android.widget.Toast.LENGTH_LONG).show();
                }
                promptNextZipImport();
            });
        }).start();
    }

    /** ZIP entpacken mit Zip-Slip-Guard (Muster aus CreateServerActivity.extractZip). */
    private boolean extractRecoveryZip(android.net.Uri uri, java.io.File targetDir) {
        try {
            targetDir.mkdirs();
            String canonicalBase = targetDir.getCanonicalPath() + java.io.File.separator;
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    getContentResolver().openInputStream(uri));
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                java.io.File out = new java.io.File(targetDir, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(canonicalBase) && !canonical.equals(targetDir.getCanonicalPath())) {
                    continue; // Zip-Slip vereiteln
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
            zis.close();
            return true;
        } catch (Exception e) {
            android.util.Log.e("Recovery", "zip extract failed", e);
            return false;
        }
    }

    /** DELETE: adoptieren, DNS + DB-Tombstone je Server, lokale Platzhalter entfernen. */
    private void executeRecoveryDelete(java.util.List<org.json.JSONObject> servers, String baseUrl, String apiKey) {
        final android.app.Dialog progress = android.app.ProgressDialog.show(this, "", getString(R.string.praetor_recovery_deleting), true);
        new Thread(() -> {
            callAdoptServers(baseUrl, apiKey);
            String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
            String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
            for (org.json.JSONObject row : servers) {
                String host = row.optString("host", "");
                String baseDomain = row.optString("base_domain", "kodanetwork.eu");
                if (host.isEmpty()) continue;
                try {
                    new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(MainActivity.this)
                            .deleteDnsLink("", host, baseDomain);
                } catch (Exception ignored) {}
                try {
                    org.json.JSONObject payload = new org.json.JSONObject()
                            .put("host", "deleted_" + host)
                            .put("server_version", "DELETED");
                    String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken
                            + "\",\"p_host\":\"" + host + "\",\"p_payload\":" + payload.toString() + "}";
                    java.net.URL url = new java.net.URL(eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_patch_server");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", apiKey);
                    c.setRequestProperty("Authorization", "Bearer " + apiKey);
                    c.setDoOutput(true);
                    c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) {}
                // lokale Platzhalter entfernen
                for (ServerInstance local : new java.util.ArrayList<>(repo.all())) {
                    if (host.equals(local.getSubdomain())) repo.delete(local.getId());
                }
            }
            eu.kodanetwork.mchost.App.getPrefs(MainActivity.this)
                    .edit().putBoolean("recovery_flow_done_v1", true).apply();
            runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Exception ignored) {}
                android.widget.Toast.makeText(MainActivity.this, R.string.praetor_recovery_deleted_done, android.widget.Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void callAdoptServers(String baseUrl, String apiKey) {
        try {
            String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
            String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
            if (appUuid.isEmpty() || deviceToken.isEmpty()) return;
            java.net.URL url = new java.net.URL(baseUrl + "/rest/v1/rpc/rpc_adopt_servers");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("apikey", apiKey);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setDoOutput(true);
            String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}";
            c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ─── MC-Link-Bestätigung (Lobby /link wartet auf App-Freigabe) ────

    private static boolean pendingLinkCheckedThisProcess;

    /** Prueft beim App-Start/resume auf ausstehende Lobby-Link-Anfragen. */
    private void checkPendingLinkOnMain(String baseUrl, String apiKey) {
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        String appUuid = prefs.getString("app_uuid", "");
        String deviceToken = prefs.getString("device_token", "");
        if (appUuid.isEmpty() || deviceToken.isEmpty()) return;
        if (pendingLinkCheckedThisProcess) return;
        pendingLinkCheckedThisProcess = true;

        new Thread(() -> {
            String mcName = null;
            try {
                java.net.URL url = new java.net.URL(baseUrl + "/rest/v1/rpc/rpc_get_pending_link");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apiKey);
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showLinkApprovalOnMain(mc, baseUrl, apiKey);
                });
            }
        }).start();
    }

    private void showLinkApprovalOnMain(String mcName, String baseUrl, String apiKey) {
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
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 300), 300);

        android.widget.Button btnAllow = dialog.findViewById(R.id.btn_praetor_fix_ram);
        btnAllow.setText(getString(R.string.link_approval_allow));
        android.widget.Button btnDeny = dialog.findViewById(R.id.btn_praetor_proceed);
        btnDeny.setText(getString(R.string.link_approval_deny));

        final String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        final String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");

        btnAllow.setOnClickListener(v -> {
            dialog.dismiss();
            resolveLinkOnMain(appUuid, deviceToken, true, baseUrl, apiKey);
        });
        btnDeny.setOnClickListener(v -> {
            dialog.dismiss();
            resolveLinkOnMain(appUuid, deviceToken, false, baseUrl, apiKey);
        });
        dialog.show();
    }

    private void resolveLinkOnMain(String appUuid, String deviceToken, boolean accept, String baseUrl, String apiKey) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(baseUrl + "/rest/v1/rpc/rpc_resolve_pending_link");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apiKey);
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                c.setDoOutput(true);
                String body = "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\",\"p_accept\":" + accept + "}";
                c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                android.widget.Toast.makeText(MainActivity.this,
                        accept ? R.string.link_approval_done : R.string.link_approval_denied,
                        android.widget.Toast.LENGTH_SHORT).show();
                if (accept) {
                    eu.kodanetwork.mchost.App.getPrefs(MainActivity.this)
                            .edit().remove("link_code").apply();
                }
            });
        }).start();
    }

}
