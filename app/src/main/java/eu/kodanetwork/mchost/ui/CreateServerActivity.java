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

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.util.HapticUtil;

public class CreateServerActivity extends AppCompatActivity {

    private static final String[] VANILLA_VERSIONS = {"1.21.4","1.21.3","1.21.1","1.20.6","1.20.4","1.20.1","1.19.4","1.18.2","1.17.1","1.16.5","1.15.2","1.14.4","1.13.2","1.12.2","1.11.2","1.10.2","1.9.4","1.8.9"};
    private static final String[] PAPER_VERSIONS   = {"1.21.4","1.21.3","1.21.1","1.20.6","1.20.4","1.20.1","1.19.4","1.18.2","1.17.1","1.16.5","1.15.2","1.14.4","1.13.2","1.12.2","1.11.2","1.10.2","1.9.4","1.8.9"};
    private static final String[] PURPUR_VERSIONS  = {"1.21.4","1.21.3","1.21.1","1.20.6","1.20.4","1.20.1","1.19.4","1.18.2","1.17.1","1.16.5","1.15.2","1.14.4","1.8.9"};
    private static final String[] FABRIC_VERSIONS  = {"1.21.4","1.21.3","1.21.1","1.20.6","1.20.4","1.20.1","1.19.4","1.18.2","1.17.1","1.16.5","1.15.2","1.14.4","1.8.9"};
    private static final String[] FOLIA_VERSIONS   = {"1.21.4","1.21.1","1.20.6","1.20.4","1.19.4","1.18.2"};
    private static final String[] FORGE_VERSIONS   = {"1.21.1","1.20.1","1.19.2","1.18.2","1.16.5","1.15.2","1.14.4","1.13.2","1.12.2","1.11.2","1.10.2","1.9.4","1.8.9"};
    private static final String[] NEOFORGE_VERSIONS= {"1.21.4","1.21.3","1.21.1","1.20.6","1.20.4","1.20.1"};
    private static final String[] VELOCITY_VERSIONS = {"3.4.0","3.3.0","3.2.0","3.1.2","3.1.1","3.1.0"};
    // PumpkinMC (Rust): a binary, no jar to download
    private static final String[] PUMPKIN_VERSIONS = {"0.1.0"};

    private static final int[] RAM_STEPS = {512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192};
    private static final String[] TYPE_NAMES = {"Paper", "Purpur", "Folia", "Forge", "Fabric", "Vanilla", "NeoForge", "Velocity", "Pumpkin"};
    private static final ServerInstance.Type[] TYPE_VALS = {
        ServerInstance.Type.PAPER, ServerInstance.Type.PURPUR, ServerInstance.Type.FOLIA,
        ServerInstance.Type.FORGE, ServerInstance.Type.FABRIC, ServerInstance.Type.VANILLA,
        ServerInstance.Type.NEOFORGE, ServerInstance.Type.VELOCITY, ServerInstance.Type.PUMPKIN
    };
    // the types that support Auto Design / KodaHosting Setup
    private static final java.util.Set<ServerInstance.Type> AUTO_DESIGN_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
        ServerInstance.Type.PAPER, ServerInstance.Type.PURPUR, ServerInstance.Type.FOLIA
    ));

    private EditText etName;
    /** set once the crisis support screen was shown for the current name (in memory only). */
    private boolean crisisConfirmed = false;
    private SeekBar seekRam;
    private TextView tvRamValue, tvAddressPreview, tvVersionLoading;
    private TextView tvVersionSelected;
    private TextView tvTypeUnsupported;
    private View layoutVersionPickerRow;
    private String selectedVersion = "1.21.4";
    private List<String> currentVersions = new ArrayList<>();
    private LinearLayout layoutThemeColor;
    private android.widget.NumberPicker npServerType;
    private View layoutNameSection, layoutTypeSection, layoutVersionSection, layoutSetupSection;
    private MaterialButton btnCreate, btnImport, btnImportZip;

    // import scanning: what the picked ZIP or folder holds, and which file should start
    // the imported server. the app guesses, the user confirms right here in this form.
    private String pickedLauncherJar = "";
    private final java.util.List<String> importJars = new java.util.ArrayList<>();
    private final java.util.List<String> importWarnings = new java.util.ArrayList<>();
    private boolean importHasWorld = false;
    /** true once the import PRAETOR check has been answered. */
    private boolean importConfirmed = false;
    private static final int REQ_IMPORT_CONFIRM = 9110;
    /** the inline radius block under the pre-generation switch. */
    private android.view.View pregenOptions;
    private android.widget.SeekBar pregenSlider;
    private android.widget.TextView pregenRadiusText, pregenEstimateText;
    private static final int PREGEN_STEP = 250;
    private static final int PREGEN_STEPS = 20;      // 250 .. 5000 blocks
    private int importModCount = 0;
    private int importPluginCount = 0;
    private android.widget.TextView tvImportWarn;
    private android.widget.ImageButton btnBack;
    private com.google.android.material.switchmaterial.SwitchMaterial swUseNative;
    private android.widget.RadioGroup rgSetupType;
    private String selectedColor = "#FF6B00";
    private android.net.Uri sourceUri = null;
    private boolean isZipImport = false;
    // modpack selection (Fabric only)
    private eu.kodanetwork.mchost.util.ModrinthHelper.MrpackInfo selectedModpack;
    private String selectedModpackTitle = null;
    private android.widget.RadioButton rbSetupModpack, rbSetupManual, rbSetupKoda, rbSetupAi;
    private TextView tvModpackSelected;
    private boolean modpackSheetOpen = false;
    private int createJavaRuntime = 0; // 0 = auto, this is a dev-only preselection
    private Runnable updateDevJava;

    private int ramMB = 1024;
    private int selectedTypeIndex = 0;
    
    // AI chat state
    private org.json.JSONArray aiChatHistory = new org.json.JSONArray();
    private org.json.JSONArray aiSelectedPlugins = new org.json.JSONArray();
    private String aiSelectedTheme = "#FF6B00";
    private String aiSuggestedName = null;
    private String selectedBaseDomain = "kodanetwork.eu";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<android.net.Uri> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    isZipImport = false;
                    handleImportUri(uri);
                }
            }
    );

    private final ActivityResultLauncher<String[]> zipLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    isZipImport = true;
                    handleImportZipUri(uri);
                }
            }
    );

    private String lastTheme = "modern";
    private String lastThemeMode = "dark";

    private void animateLightBlob(android.view.View blob) {
        if (blob == null || blob.getVisibility() != android.view.View.VISIBLE) return;
        
        float randomX = (float) (Math.random() * 600 - 300);
        float randomY = (float) (Math.random() * 600 - 300);
        
        blob.animate()
            .translationX(randomX)
            .translationY(randomY)
            .setDuration(4000)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction(() -> animateLightBlob(blob))
            .start();
    }

    private boolean isLight() {
        return eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this);
    }

    private int pageBg() {
        return isLight() ? 0xFFF3F4F6 : 0xFF0A0807;
    }

    private int headerBg() {
        return isLight() ? 0xFFFFFFFF : 0xFF1B1613;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        lastTheme = prefs.getString("app_theme", "modern");
        boolean isCyber = "cyber".equals(lastTheme);

        if (eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)) {
            setContentView(R.layout.activity_create_server_m3);
        } else {
            setContentView(R.layout.activity_create_server);
        }
        if (isCyber) {
            findViewById(android.R.id.content).getRootView().setBackgroundResource(R.drawable.bg_cyber_grid);
        }

        lastThemeMode = prefs.getString("theme_mode", "dark");
        boolean isLight = eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this);

        // background plus status and nav bar colors
        int pageColor = pageBg();
        int headerColor = headerBg();

        findViewById(android.R.id.content).setBackgroundColor(pageColor);
        findViewById(R.id.main_scroll).setBackgroundColor(pageColor);
        
        android.view.View mainScroll = findViewById(R.id.main_scroll);
        if (mainScroll instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) mainScroll).setClipToPadding(false);
        }

        findViewById(R.id.top_bar_container).setBackgroundColor(headerColor);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            
            // real edge-to-edge, bypassing the global hack
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }
            
            // padding comes from the insets, so route them properly
            findViewById(R.id.root_layout).setOnApplyWindowInsetsListener((v, insets) -> {
                android.view.View topBar = findViewById(R.id.top_bar_container);
                if (topBar != null) topBar.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                
                android.view.View scroll = findViewById(R.id.main_scroll);
                if (scroll != null) scroll.setPadding(scroll.getPaddingLeft(), 0, scroll.getPaddingRight(), insets.getSystemWindowInsetBottom() + 120);
                
                return insets.consumeSystemWindowInsets();
            });

            if (isLight) {
                int flags = getWindow().getDecorView().getSystemUiVisibility();
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
            
            // ThemeHelper overrides this later, so set it again after
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }, 500);
        }

        etName          = findViewById(R.id.et_name);
        seekRam         = findViewById(R.id.seek_ram);
        tvRamValue      = findViewById(R.id.tv_ram_value);
        tvAddressPreview= findViewById(R.id.tv_address_preview);
        tvVersionLoading= findViewById(R.id.tv_version_loading);
        tvVersionSelected = findViewById(R.id.tv_version_selected);
        tvTypeUnsupported = findViewById(R.id.tv_type_unsupported);
        layoutVersionPickerRow = findViewById(R.id.layout_version_picker);
        // java runtime preselection, developer options only
        TextView tvDevJava = findViewById(R.id.tv_dev_java_runtime);
        if (tvDevJava != null && eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("dev_mode_unlocked", false)) {
            tvDevJava.setVisibility(View.VISIBLE);
            final TextView tvDev = tvDevJava;
            this.updateDevJava = () -> {
                boolean isFabricType = TYPE_VALS[selectedTypeIndex] == eu.kodanetwork.mchost.model.ServerInstance.Type.FABRIC;
                tvDev.setText("Java: " + (createJavaRuntime == 0
                    ? getString(R.string.java_runtime_auto, eu.kodanetwork.mchost.util.RuntimeManager.resolveAutoVersion(selectedVersion, isFabricType)) 
                    : getString(R.string.java_runtime_manual, createJavaRuntime)));
            };
            this.updateDevJava.run();
            tvDevJava.setOnClickListener(v -> {
                boolean isFabricType = TYPE_VALS[selectedTypeIndex] == eu.kodanetwork.mchost.model.ServerInstance.Type.FABRIC;
                final int autoVer = eu.kodanetwork.mchost.util.RuntimeManager.resolveAutoVersion(selectedVersion, isFabricType);
                final int[] opts = {0, 8, 17, 21, 25};
                String[] labels = {
                        getString(R.string.java_runtime_auto, autoVer),
                        getString(R.string.java_runtime_manual, 8),
                        getString(R.string.java_runtime_manual, 17),
                        getString(R.string.java_runtime_manual, 21),
                        getString(R.string.java_runtime_manual, 25)};
                int cur = 0;
                for (int i = 0; i < opts.length; i++) if (opts[i] == createJavaRuntime) cur = i;
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.java_runtime_label)
                        .setSingleChoiceItems(labels, cur, (d, which) -> {
                            createJavaRuntime = opts[which];
                            if (this.updateDevJava != null) this.updateDevJava.run();
                            d.dismiss();
                        })
                        .setNegativeButton(R.string.modpack_cancel, null)
                        .show();
            });
        }
        // the version row opens the picker
        View layoutVersionPicker = findViewById(R.id.layout_version_picker);
        if (layoutVersionPicker != null) layoutVersionPicker.setOnClickListener(v -> showVersionPicker());
        npServerType    = findViewById(R.id.np_server_type);
        tvModpackSelected = findViewById(R.id.tv_modpack_selected);
        rbSetupModpack = findViewById(R.id.rb_setup_modpack);
        if (rbSetupModpack != null) {
            // re-tapping the already checked Modpack option reopens the picker
            rbSetupModpack.setOnClickListener(v -> {
                HapticUtil.forceVibrate(this, 60);
                openModpackBrowser();
            });
        }
        rbSetupManual = findViewById(R.id.rb_setup_manual);
        rbSetupKoda = findViewById(R.id.rb_setup_koda);
        rbSetupAi = findViewById(R.id.rb_setup_ai);
        btnCreate       = findViewById(R.id.btn_create);
        btnImport       = findViewById(R.id.btn_import);
        btnImportZip    = findViewById(R.id.btn_import_zip);
        btnBack         = findViewById(R.id.btn_back);
        swUseNative = findViewById(R.id.sw_use_native);
        swUseNative.setChecked(true);
        swUseNative.setVisibility(android.view.View.GONE);
        rgSetupType     = findViewById(R.id.rg_setup_type);
        layoutThemeColor= findViewById(R.id.layout_theme_color);

        layoutNameSection    = findViewById(R.id.layout_name_section);
        layoutTypeSection    = findViewById(R.id.layout_type_section);
        layoutVersionSection = findViewById(R.id.layout_version_section);
        layoutSetupSection   = findViewById(R.id.layout_setup_section);

        btnBack.setOnClickListener(v -> finish());
        btnImport.setOnClickListener(v -> importLauncher.launch(null));
        btnImportZip.setOnClickListener(v -> zipLauncher.launch(new String[]{"application/zip"}));

        // the import card and the file picker are gone on purpose: the PRAETOR check before
        // the import reports what was found, and the launcher picks the jar itself
        
        boolean devTermux = eu.kodanetwork.mchost.App.getPrefs(this).getBoolean("dev_termux_fallback", false);
        if (!devTermux) {
            swUseNative.setVisibility(View.GONE);
            swUseNative.setChecked(true); // native is the only thing left here
        }
        
        setupNumberPicker();
        setupRam();
        setupWorldType();
        
        setupNameWatcher();

        android.widget.TextView btnKodaNetwork = findViewById(R.id.btn_domain_kodanetwork);
        android.widget.TextView btnKodaServ = findViewById(R.id.btn_domain_kodaserv);
        android.view.View wrapperKodaNetwork = findViewById(R.id.wrapper_domain_kodanetwork);
        android.view.View wrapperKodaServ = findViewById(R.id.wrapper_domain_kodaserv);

        if (btnKodaNetwork != null && btnKodaServ != null && wrapperKodaNetwork != null && wrapperKodaServ != null) {
            android.view.View.OnClickListener listener = v -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
                selectedBaseDomain = (v.getId() == R.id.wrapper_domain_kodaserv || v.getId() == R.id.btn_domain_kodaserv) ? "kodaserv.eu" : "kodanetwork.eu";
                updateDomainUI();
                updatePreview();
            };
            btnKodaNetwork.setOnClickListener(listener);
            btnKodaServ.setOnClickListener(listener);
            wrapperKodaNetwork.setOnClickListener(listener);
            wrapperKodaServ.setOnClickListener(listener);
            
            // initial state
            btnKodaNetwork.setTextColor(android.graphics.Color.parseColor("#888899"));
            btnKodaServ.setTextColor(android.graphics.Color.parseColor("#888899"));
            android.widget.TextView activeText = "kodanetwork.eu".equals(selectedBaseDomain) ? btnKodaNetwork : btnKodaServ;
            android.view.View activeWrapper = "kodanetwork.eu".equals(selectedBaseDomain) ? wrapperKodaNetwork : wrapperKodaServ;
            
            activeText.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));
            android.view.View pill = findViewById(R.id.pill_domain);
            if (pill != null) {
                activeWrapper.post(() -> {
                    pill.setTranslationX(activeWrapper.getX());
                    android.view.ViewGroup.LayoutParams params = pill.getLayoutParams();
                    params.width = activeWrapper.getWidth();
                    pill.requestLayout();
                });
            }
        }

        setupThemeColors();
        
        String email = eu.kodanetwork.mchost.App.getPrefs(this).getString("account_email", "");
        if (eu.kodanetwork.mchost.util.OwnerCheck.isOwnerEmail(email)) {
            android.widget.RadioButton rbAi = findViewById(R.id.rb_setup_ai);
            if (rbAi != null) rbAi.setVisibility(View.VISIBLE);
        }

        rgSetupType.setOnCheckedChangeListener((g, id) -> {
            layoutThemeColor.setVisibility((id == R.id.rb_setup_koda || id == R.id.rb_setup_ai) ? View.VISIBLE : View.GONE);
            View aiPrompt = findViewById(R.id.layout_ai_prompt);
            if (aiPrompt != null) {
                aiPrompt.setVisibility(id == R.id.rb_setup_ai ? View.VISIBLE : View.GONE);
            }
            // Fabric setup options: Modpack opens the picker, Standard drops the selection
            if (id == R.id.rb_setup_modpack) {
                HapticUtil.forceVibrate(this, 60);
                if (selectedModpack == null) openModpackBrowser();
            } else if (id == R.id.rb_setup_manual && selectedTypeIndex >= 0
                    && TYPE_VALS[selectedTypeIndex] == ServerInstance.Type.FABRIC && selectedModpack != null) {
                selectedModpack = null;
                selectedModpackTitle = null;
                if (tvModpackSelected != null) tvModpackSelected.setVisibility(View.GONE);
            }
        });

        loadVersionsForType(0);
        setupAiChat();
        btnCreate.setOnClickListener(v -> {
            if (eu.kodanetwork.mchost.util.BiometricHelper.isBioEnabledFor(this, "bio_on_create_server")) {
                Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.BiometricAuthActivity.class);
                startActivityForResult(intent, eu.kodanetwork.mchost.util.BiometricHelper.REQ_BIO_AUTH);
            } else {
                createServer();
            }
        });
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this, selectedColor);
        
        // unpack the bundled Java on first run, with an overlay so it is not silent
        File javaBin = new File(getFilesDir(), "jre25/bin/java");
        if (!javaBin.exists()) {
            View overlay = findViewById(R.id.layout_java_extract);
            TextView tvMsg = findViewById(R.id.tv_extract_msg);
            if (overlay != null) {
                overlay.setAlpha(0f);
                overlay.setVisibility(View.VISIBLE);
                overlay.animate().alpha(1f).setDuration(400).start();
            }
            
            new Thread(() -> {
                try {
                    eu.kodanetwork.mchost.orchestration.StartOrchestrator.extractJavaIfMissing(CreateServerActivity.this, new eu.kodanetwork.mchost.orchestration.StartOrchestrator.Callback() {
                        @Override
                        public void onStep(eu.kodanetwork.mchost.orchestration.StartOrchestrator.Step step, String message) {
                            runOnUiThread(() -> {
                                if (tvMsg != null) tvMsg.setText(message);
                            });
                        }
                        @Override public void onCompleted(String playitAddress, String domainLink) {}
                        @Override public void requestServerStartIntent() {}
                        @Override
                        public void onError(eu.kodanetwork.mchost.orchestration.StartOrchestrator.Step step, String message) {
                            runOnUiThread(() -> {
                                if (tvMsg != null) tvMsg.setText("Error: " + message);
                            });
                        }
                    });
                } catch (Exception e) {
                    eu.kodanetwork.mchost.util.AppLogger.log("CreateServer", "Pre-extraction failed: " + e.getMessage());
                } finally {
                    runOnUiThread(() -> {
                        if (overlay != null) {
                            overlay.animate().alpha(0f).setDuration(400).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
                        }
                    });
                }
            }).start();
        }
    }

    private void enterImportMode() {
        layoutTypeSection.setVisibility(View.VISIBLE);
        layoutSetupSection.setVisibility(View.GONE);
        layoutThemeColor.setVisibility(View.GONE);
        findViewById(R.id.tv_address_preview_label).setVisibility(View.GONE);
        findViewById(R.id.tv_address_preview).setVisibility(View.GONE);
        findViewById(R.id.tv_velocity_label).setVisibility(View.GONE);
        btnCreate.setText("[  FINISH IMPORT  ]");

        if (layoutTypeSection instanceof LinearLayout) {
            View label = ((LinearLayout)layoutTypeSection).getChildAt(0);
            if (label instanceof TextView) ((TextView)label).setText("DETECTED TYPE (CONFIRM OR CHANGE)");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == eu.kodanetwork.mchost.util.BiometricHelper.REQ_BIO_AUTH) {
            if (resultCode == RESULT_OK) {
                createServer();
            }
        } else if (requestCode == eu.kodanetwork.mchost.ui.CrisisSupportActivity.REQ_CRISIS) {
            if (resultCode == RESULT_OK) {
                createServer();
            }
        } else if (requestCode == REQ_IMPORT_CONFIRM) {
            // only the PRAETOR "yes" continues the import, backing out leaves the form as is
            if (resultCode == RESULT_OK) {
                createServer();
            } else {
                importConfirmed = false;
            }
        }
    }

    /**
     * hides the whole pre-generation row when chunky has no build for the chosen software
     * and minecraft version, and switches it off if it was already on.
     */
    private void updatePregenerationAvailability() {
        View row = findViewById(R.id.row_pregenerate);
        android.widget.CompoundButton sw = findViewById(R.id.switch_pregenerate);
        if (row == null) return;
        boolean supported = ServerInstance.Type.class != null
                && eu.kodanetwork.mchost.util.VersionCatalog.chunkySupports(TYPE_VALS[selectedTypeIndex], selectedVersion);
        row.setVisibility(supported ? View.VISIBLE : View.GONE);
        if (!supported) {
            if (sw != null && sw.isChecked()) sw.setChecked(false);
            if (pregenOptions != null) pregenOptions.setVisibility(View.GONE);
        }
    }

    /**
     * the radius slider that unfolds under the pre-generation switch, with the same estimate
     * the PRAETOR screen used to show: chunks, storage and a rough runtime.
     */
    private void setupPregenSlider() {
        android.widget.CompoundButton sw = findViewById(R.id.switch_pregenerate);
        pregenOptions = findViewById(R.id.layout_pregen_options);
        pregenSlider = findViewById(R.id.sb_pregen_inline);
        pregenRadiusText = findViewById(R.id.tv_pregen_inline_radius);
        pregenEstimateText = findViewById(R.id.tv_pregen_inline_estimate);
        if (sw == null || pregenOptions == null || pregenSlider == null) return;

        int saved = eu.kodanetwork.mchost.App.getPrefs(this).getInt("pregenerate_radius", 1000);
        pregenSlider.setMax(PREGEN_STEPS - 1);
        pregenSlider.setProgress(Math.max(0, Math.min(PREGEN_STEPS - 1, saved / PREGEN_STEP - 1)));
        pregenSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                updatePregenEstimate((progress + 1) * PREGEN_STEP);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                HapticUtil.forceVibrate(CreateServerActivity.this, 30);
                eu.kodanetwork.mchost.App.getPrefs(CreateServerActivity.this).edit()
                        .putInt("pregenerate_radius", (bar.getProgress() + 1) * PREGEN_STEP).apply();
            }
        });

        sw.setOnCheckedChangeListener((button, checked) -> {
            HapticUtil.forceVibrate(this, 40);
            pregenOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) updatePregenEstimate((pregenSlider.getProgress() + 1) * PREGEN_STEP);
        });
        pregenOptions.setVisibility(sw.isChecked() ? View.VISIBLE : View.GONE);
        updatePregenEstimate((pregenSlider.getProgress() + 1) * PREGEN_STEP);
    }

    private void updatePregenEstimate(int radius) {
        if (pregenRadiusText != null) {
            pregenRadiusText.setText(getString(R.string.pregen_radius_value, radius));
        }
        if (pregenEstimateText == null) return;
        long perSide = Math.max(1, (2L * radius) / 16);
        long chunks = perSide * perSide;
        double diskGb = chunks * 0.20d / 1024d;
        double hours = chunks / 3600d / 4d;
        String time = hours < 1
                ? getString(R.string.pregen_minutes, Math.max(1, (int) Math.round(hours * 60)))
                : getString(R.string.pregen_hours, hours);
        pregenEstimateText.setText(getString(R.string.pregen_estimate_inline,
                chunks, String.format(java.util.Locale.US, "%.1f", diskGb), time));
    }

    /** is the pre-generate switch on in the form right now. */
    private boolean pregenWanted() {
        android.widget.CompoundButton sw = findViewById(R.id.switch_pregenerate);
        return sw != null && sw.isChecked();
    }

    /** true when the import brought a jar the app could start. */
    private boolean importHasServerFile() {
        for (String jar : importJars) {
            if (!jar.contains("installer")) return true;
        }
        return false;
    }

    /**
     * the text of the PRAETOR check before an import runs: what was recognized, and when
     * nothing launchable came along, that the software gets downloaded on the first start.
     */
    private String buildImportConfirmText() {
        String software = getSelectedTypeName() + (selectedVersion.isEmpty() ? "" : " " + selectedVersion);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(software);
        if (importHasServerFile()) {
            for (String jar : importJars) {
                if (!jar.contains("installer")) { lines.add(jar); break; }
            }
            if (importHasWorld) lines.add(getString(R.string.import_warn_world_present));
            if (importModCount > 0) lines.add(importModCount + " mods");
            if (importPluginCount > 0) lines.add(importPluginCount + " plugins");
            // whatever the scan noticed missing belongs in front of the user too
            updateImportScanUi();
            for (String warning : importWarnings) lines.add("\u26a0 " + warning);
            return android.text.TextUtils.join("\n", lines) + "\n\n" + getString(R.string.import_confirm_body);
        }
        return getString(R.string.import_confirm_body_missing, software);
    }

    /** clears everything the previous pick left behind. */
    private void resetImportScan() {
        importConfirmed = false;
        importJars.clear();
        importWarnings.clear();
        importHasWorld = false;
        importModCount = 0;
        importPluginCount = 0;
        pickedLauncherJar = "";
    }

    /**
     * reads one name out of the imported ZIP or folder. entry names are all we have
     * before the files are copied, so this only believes the names.
     */
    private void noteImportName(String name, boolean directory) {
        if (name == null || name.isEmpty()) return;
        String n = name.toLowerCase();
        if (directory) {
            if (n.endsWith("mods")) importModCount++;
            if (n.endsWith("plugins")) importPluginCount++;
            return;
        }
        if (n.endsWith("level.dat")) importHasWorld = true;
        if (!n.endsWith(".jar")) return;
        String base = n.substring(n.lastIndexOf('/') + 1);
        if (base.contains("installer")) {
            importJars.add(base);
            return;
        }
        if (eu.kodanetwork.mchost.util.ServerImportInspector.typeForJarName(base) == null) return;
        if (!importJars.contains(base)) importJars.add(base);
    }

    /** keeps the warnings of the scan, the summary lives in the PRAETOR check now. */
    private void updateImportScanUi() {
        importWarnings.clear();
        boolean onlyInstaller = !importJars.isEmpty() && importJars.stream().allMatch(j -> j.contains("installer"));
        if (importJars.isEmpty()) importWarnings.add(getString(R.string.import_warn_no_file));
        else if (onlyInstaller) importWarnings.add(getString(R.string.import_warn_installer_only));
        if (!importHasWorld) importWarnings.add(getString(R.string.import_warn_no_world));
    }

    /** the software name of the current pick, for the summary line. */
    private String getSelectedTypeName() {
        try {
            int idx = npServerType.getValue();
            if (idx >= 0 && idx < TYPE_VALS.length) return TYPE_VALS[idx].name();
        } catch (Exception ignored) {}
        return "";
    }

    private void handleImportZipUri(android.net.Uri uri) {
        sourceUri = uri;
        getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        isZipImport = true;
        resetImportScan();
        
        executor.submit(() -> {
            String detectedName = "ImportedServer";
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIdx != -1) {
                        detectedName = cursor.getString(nameIdx).replace(".zip", "");
                    }
                }
            } catch (Exception ignored) {}

            String finalDetectedName = detectedName;
            mainHandler.post(() -> {
                etName.setText(finalDetectedName);
                rgSetupType.check(R.id.rb_setup_manual);
                enterImportMode();
                Toast.makeText(this, "Zip selected: " + finalDetectedName, Toast.LENGTH_SHORT).show();
            });
            
            try (InputStream is = getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                String detectedVersion = null;
                ServerInstance.Type detectedType = ServerInstance.Type.PAPER;
                
                while ((entry = zis.getNextEntry()) != null) {
                    String n = entry.getName().toLowerCase();
                    noteImportName(entry.getName(), entry.isDirectory());
                    if (n.contains("purpur.yml")) detectedType = ServerInstance.Type.PURPUR;

                    if (n.endsWith(".jar")) {
                        if (n.contains("paper")) detectedType = ServerInstance.Type.PAPER;
                        else if (n.contains("purpur")) detectedType = ServerInstance.Type.PURPUR;
                        else if (n.contains("forge") && !n.contains("neoforge")) detectedType = ServerInstance.Type.FORGE;
                        else if (n.contains("neoforge")) detectedType = ServerInstance.Type.NEOFORGE;
                        else if (n.contains("fabric")) detectedType = ServerInstance.Type.FABRIC;
                        else if (n.contains("server") || n.contains("vanilla")) detectedType = ServerInstance.Type.VANILLA;

                        Pattern p = Pattern.compile("(\\d+\\.\\d+(\\.\\d+)?)");
                        Matcher m = p.matcher(n);
                        if (m.find()) detectedVersion = m.group(1);
                    }
                }
                
                final String fVersion = detectedVersion;
                final ServerInstance.Type fType = detectedType;
                mainHandler.post(() -> {
                    int typeIdx = 0;
                    for(int i=0; i<TYPE_VALS.length; i++) if (TYPE_VALS[i] == fType) { typeIdx = i; break; }
                    npServerType.setValue(typeIdx);
                    selectType(typeIdx);
                    if (fVersion != null) {
                        mainHandler.postDelayed(() -> {
                            for (String ver : currentVersions) {
                                if (ver.contains(fVersion)) {
                                    selectedVersion = ver;
                                    if (tvVersionSelected != null) tvVersionSelected.setText(ver);
                                    if (updateDevJava != null) updateDevJava.run();
                                    break;
                                }
                            }
                        }, 500);
                    }
                });
            } catch (IOException ignored) {}
        });
    }

    private void handleImportUri(android.net.Uri uri) {
        sourceUri = uri;
        getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        isZipImport = false;
        resetImportScan();
        DocumentFile root = DocumentFile.fromTreeUri(this, uri);
        if (root == null || !root.isDirectory()) return;

        executor.submit(() -> {
            String detectedName = root.getName();
            String detectedVersion = null;
            ServerInstance.Type detectedType = ServerInstance.Type.PAPER;
            int detectedPort = 25565;

            DocumentFile propFile = root.findFile("server.properties");
            if (propFile != null) {
                try (InputStream is = getContentResolver().openInputStream(propFile.getUri());
                     BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("server-port=")) {
                            try { detectedPort = Integer.parseInt(line.substring(12).trim()); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }

            DocumentFile[] files = root.listFiles();
            for (DocumentFile f : files) {
                if (f.getName() == null) continue;
                String n = f.getName().toLowerCase();
                noteImportName(f.getName(), f.isDirectory());
                if (n.contains("purpur.yml")) detectedType = ServerInstance.Type.PURPUR;

                if (n.endsWith(".jar")) {
                    if (n.contains("paper")) detectedType = ServerInstance.Type.PAPER;
                    else if (n.contains("purpur")) detectedType = ServerInstance.Type.PURPUR;
                    else if (n.contains("forge") && !n.contains("neoforge")) detectedType = ServerInstance.Type.FORGE;
                    else if (n.contains("neoforge")) detectedType = ServerInstance.Type.NEOFORGE;
                    else if (n.contains("fabric")) detectedType = ServerInstance.Type.FABRIC;
                    else if (n.contains("server") || n.contains("vanilla")) detectedType = ServerInstance.Type.VANILLA;

                    Pattern p = Pattern.compile("(\\d+\\.\\d+(\\.\\d+)?)");
                    Matcher m = p.matcher(n);
                    if (m.find()) {
                        detectedVersion = m.group(1);
                    }
                }
            }

            final String fName = detectedName;
            final String fVersion = detectedVersion;
            final ServerInstance.Type fType = detectedType;
            final int fPort = detectedPort;

            mainHandler.post(() -> {
                if (fName != null) etName.setText(fName);
                // the port is always auto-assigned, no UI input for it
                
                int typeIdx = 0;
                for(int i=0; i<TYPE_VALS.length; i++) {
                    if (TYPE_VALS[i] == fType) { typeIdx = i; break; }
                }
                npServerType.setValue(typeIdx);
                selectType(typeIdx);
                
                if (fVersion != null) {
                    mainHandler.postDelayed(() -> {
                        for (String ver : currentVersions) {
                            if (ver.contains(fVersion)) {
                                selectedVersion = ver;
                                if (tvVersionSelected != null) tvVersionSelected.setText(ver);
                                if (updateDevJava != null) updateDevJava.run();
                                break;
                            }
                        }
                    }, 500);
                }
                
                rgSetupType.check(R.id.rb_setup_manual);
                enterImportMode();
                // the type and version land in the form with a delay, so the summary follows after
                mainHandler.postDelayed(this::updateImportScanUi, 900);
                Toast.makeText(this, "Detected: " + fType + " " + (fVersion!=null?fVersion:"?"), Toast.LENGTH_LONG).show();
            });
        });
    }

    private void setupThemeColors() {
        View btnColorPicker = findViewById(R.id.btn_color_picker);
        View colorPreview = findViewById(R.id.color_preview);
        TextView tvColorHex = findViewById(R.id.tv_color_hex);

        selectedColor = "#FF6B00"; // default Koda orange

        btnColorPicker.setOnClickListener(v -> {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("Pick Theme Color");

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 40, 50, 40);

            final View preview = new View(this);
            preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150));
            preview.setBackgroundColor(android.graphics.Color.parseColor(selectedColor));
            layout.addView(preview);

            final android.widget.EditText hexInput = new android.widget.EditText(this);
            hexInput.setText(selectedColor);
            hexInput.setTypeface(android.graphics.Typeface.MONOSPACE);
            hexInput.setGravity(android.view.Gravity.CENTER);
            layout.addView(hexInput);

            android.widget.SeekBar[] bars = new android.widget.SeekBar[3];
            int[] currentRgb = {
                Integer.valueOf(selectedColor.substring(1, 3), 16),
                Integer.valueOf(selectedColor.substring(3, 5), 16),
                Integer.valueOf(selectedColor.substring(5, 7), 16)
            };

            String[] labels = {"Red", "Green", "Blue"};
            for (int i = 0; i < 3; i++) {
                TextView tv = new TextView(this); tv.setText(labels[i]);
                layout.addView(tv);
                bars[i] = new android.widget.SeekBar(this);
                bars[i].setMax(255);
                bars[i].setProgress(currentRgb[i]);
                final int idx = i;
                bars[i].setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                        if (fromUser) {
                            currentRgb[idx] = p;
                            String hex = String.format("#%02X%02X%02X", currentRgb[0], currentRgb[1], currentRgb[2]);
                            preview.setBackgroundColor(android.graphics.Color.parseColor(hex));
                            hexInput.setText(hex);
                        }
                    }
                    @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                    @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
                });
                layout.addView(bars[i]);
            }

            hexInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (s.length() == 7 && s.toString().startsWith("#")) {
                        try {
                            int c = android.graphics.Color.parseColor(s.toString());
                            preview.setBackgroundColor(c);
                            bars[0].setProgress((c >> 16) & 0xFF);
                            bars[1].setProgress((c >> 8) & 0xFF);
                            bars[2].setProgress(c & 0xFF);
                        } catch (Exception ignored) {}
                    }
                }
            });

            builder.setView(layout);
            builder.setPositiveButton("OK", (dialog, which) -> {
                try {
                    String hex = hexInput.getText().toString();
                    android.graphics.Color.parseColor(hex); // throws if the hex is broken
                    selectedColor = hex;
                    colorPreview.setBackgroundColor(android.graphics.Color.parseColor(hex));
                    tvColorHex.setText(hex);
                } catch (Exception ignored) {}
            });
            builder.setNegativeButton("Cancel", null);
            builder.show();
        });
    }
    private void setupNumberPicker() {
        if (npServerType != null) {
            npServerType.setMinValue(0);
            npServerType.setMaxValue(TYPE_NAMES.length - 1);
            String[] wheelNames = TYPE_NAMES.clone();
            for (int i = 0; i < TYPE_VALS.length; i++) {
                if (TYPE_VALS[i] == ServerInstance.Type.PUMPKIN) {
                    wheelNames[i] = TYPE_NAMES[i] + " (" + getString(R.string.pumpkin_coming_soon_short) + ")";
                }
            }
            npServerType.setDisplayedValues(wheelNames);

        // turning pre-generation on unfolds the radius slider right below the switch,
        // like the RAM slider, no extra screen for it
        setupPregenSlider();
            npServerType.setValue(0);
            npServerType.setWrapSelectorWheel(true);
            npServerType.setOnValueChangedListener((picker, oldVal, newVal) -> {
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 80);
                selectType(newVal);
            });
        }
    }

    private void selectType(int idx) {
        selectedTypeIndex = idx;
        // auto design only exists for some types, so show the option only there
        ServerInstance.Type type = TYPE_VALS[idx];
        boolean supportsAutoDesign = AUTO_DESIGN_TYPES.contains(type);
        boolean isModpackSupported = type == ServerInstance.Type.FABRIC || type == ServerInstance.Type.FORGE || type == ServerInstance.Type.NEOFORGE;
        if (layoutSetupSection != null) {
            if (sourceUri != null) {
                layoutSetupSection.setVisibility(View.GONE);
                if (rgSetupType != null) rgSetupType.check(R.id.rb_setup_manual);
            } else {
                layoutSetupSection.setVisibility(supportsAutoDesign || isModpackSupported ? View.VISIBLE : View.GONE);
                if (!supportsAutoDesign && !isModpackSupported && rgSetupType != null) rgSetupType.check(R.id.rb_setup_manual);
            }
        }
        // modpack servers get Standard + Modpack instead of the design radios
        if (rbSetupKoda != null) rbSetupKoda.setVisibility(isModpackSupported ? View.GONE : View.VISIBLE);
        if (rbSetupAi != null) rbSetupAi.setVisibility(isModpackSupported ? View.GONE : rbSetupAi.getVisibility());
        if (rbSetupModpack != null) rbSetupModpack.setVisibility(isModpackSupported ? View.VISIBLE : View.GONE);
        if (rbSetupManual != null) {
            rbSetupManual.setText(isModpackSupported ? R.string.setup_standard : R.string.setup_manual);
        }
        if (isModpackSupported && selectedModpack == null && rgSetupType != null) {
            rgSetupType.check(R.id.rb_setup_manual);
        }
        if (layoutThemeColor != null && !supportsAutoDesign) {
            layoutThemeColor.setVisibility(View.GONE);
        }
        // pumpkin is bundled but not working yet, tell that instead of failing later on start
        boolean pumpkin = type == ServerInstance.Type.PUMPKIN;
        if (tvTypeUnsupported != null) {
            tvTypeUnsupported.setVisibility(pumpkin ? View.VISIBLE : View.GONE);
            if (pumpkin) tvTypeUnsupported.setText(R.string.pumpkin_coming_soon);
        }
        if (btnCreate != null) {
            btnCreate.setEnabled(!pumpkin);
            btnCreate.setAlpha(pumpkin ? 0.5f : 1f);
        }
        // pre-generation runs chunky, and chunky does not exist for every software or version.
        // where it cannot run, the option is not there at all.
        updatePregenerationAvailability();

        // Forge / NeoForge work through the embedded JVM installer now, nothing is unsupported
        boolean unsupported = false;
        if (tvTypeUnsupported != null && !pumpkin) {
            tvTypeUnsupported.setVisibility(View.GONE);
        }
        if (layoutVersionSection != null) layoutVersionSection.setVisibility(View.VISIBLE);
        loadVersionsForType(idx);
    }

    private void loadVersionsForType(int typeIdx) {
        ServerInstance.Type type = TYPE_VALS[typeIdx];
        if (tvVersionLoading != null) { tvVersionLoading.setText("Loading…"); tvVersionLoading.setVisibility(View.VISIBLE); }
        if (type == ServerInstance.Type.PAPER || type == ServerInstance.Type.PURPUR || type == ServerInstance.Type.FOLIA) {
            executor.submit(() -> {
                List<String> versions;
                if (type == ServerInstance.Type.PAPER) versions = fetchPaperMcVersions("paper");
                else if (type == ServerInstance.Type.FOLIA) versions = fetchPaperMcVersions("folia");
                else versions = fetchPurpurVersions();
                mainHandler.post(() -> {
                    if (tvVersionLoading != null) tvVersionLoading.setVisibility(View.GONE);
                    if (versions.isEmpty()) { versions.add("26.2"); versions.add("26.1.2"); versions.add("No internet"); }
                    setVersionList(versions);
                });
            });
        } else if (type == ServerInstance.Type.FABRIC) {
            // live list from the official Fabric meta API (1.16 up to the newest release)
            executor.submit(() -> {
                final List<String> fetched = fetchFabricVersions();
                mainHandler.post(() -> {
                    if (tvVersionLoading != null) tvVersionLoading.setVisibility(View.GONE);
                    setVersionList(fetched.isEmpty() ? listOf(FABRIC_VERSIONS) : fetched);
                });
            });
        } else {
            List<String> versions;
            if (type == ServerInstance.Type.NEOFORGE) versions = listOf(NEOFORGE_VERSIONS);
            else if (type == ServerInstance.Type.FORGE) versions = listOf(FORGE_VERSIONS);
            else if (type == ServerInstance.Type.VELOCITY) versions = listOf(VELOCITY_VERSIONS);
            else if (type == ServerInstance.Type.PUMPKIN) versions = listOf(PUMPKIN_VERSIONS);
            else versions = listOf(VANILLA_VERSIONS);
            if (tvVersionLoading != null) tvVersionLoading.setVisibility(View.GONE);
            setVersionList(versions);
        }
    }

    /** all stable Fabric game versions from 1.16 up, newest first. */
    private List<String> fetchFabricVersions() {
        try {
            String json = get("https://meta.fabricmc.net/v2/versions/game");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<String> res = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (!o.optBoolean("stable", false)) continue;
                String v = o.optString("version", "");
                if (isFabricSupportedVersion(v)) res.add(v);
            }
            return res;
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    /** 1.x counts from 1.8 up; the new year-based scheme (26.x, …) is always supported. */
    private boolean isFabricSupportedVersion(String v) {
        if (v == null || v.isEmpty()) return false;
        String[] parts = v.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            if (major > 1) return true;
            if (major == 1 && parts.length >= 2) return Integer.parseInt(parts[1]) >= 8;
        } catch (NumberFormatException ignored) {}
        return false;
    }

    /** fullscreen modpack browser that expands out of the Modpack button. */
    /** modpack picker styled like the resource pack downloader (dialog_modrinth_search sheet). */
    private void openModpackBrowser() {
        if (selectedTypeIndex < 0) return;
        ServerInstance.Type stype = TYPE_VALS[selectedTypeIndex];
        if (stype != ServerInstance.Type.FABRIC && stype != ServerInstance.Type.FORGE && stype != ServerInstance.Type.NEOFORGE) return;
        final String loaderName = stype == ServerInstance.Type.FABRIC ? "fabric" : (stype == ServerInstance.Type.FORGE ? "forge" : "neoforge");
        // guard: never stack two sheets, a rapid double tap looked like the sheet "reopening"
        if (modpackSheetOpen) return;
        modpackSheetOpen = true;

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.KodaBottomSheetDialog);
        sheet.setContentView(R.layout.dialog_modrinth_search);

        android.widget.TextView tvTitle = sheet.findViewById(R.id.tv_modrinth_title);
        if (tvTitle != null) tvTitle.setText(getString(R.string.modpack_browser_title));

        android.widget.EditText etQuery = sheet.findViewById(R.id.et_modrinth_search);
        android.view.View btnSearch = sheet.findViewById(R.id.btn_modrinth_search_submit);
        android.widget.ProgressBar pb = sheet.findViewById(R.id.pb_modrinth_search);
        RecyclerView rv = sheet.findViewById(R.id.rv_modrinth_results);
        if (etQuery != null) etQuery.setHint(getString(R.string.modpack_search_hint));
        rv.setLayoutManager(new LinearLayoutManager(this));


        // --- state + adapter (item_modrinth_project rows, same as the resource pack list) ---
        final String[] filterVersion = {selectedVersion != null ? selectedVersion : ""};
        final boolean[] filterToSelected = {false};
        final java.util.List<eu.kodanetwork.mchost.util.ModrinthHelper.ModpackHit> hits = new ArrayList<>();
        final int[] totalHits = {0};
        final int[] fetched = {0};
        final boolean[] selecting = {false};
        final Runnable[] loadPageRef = new Runnable[1];
        final Runnable[] resetAndLoadRef = new Runnable[1];

        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private static final int TYPE_ITEM = 0;
            private static final int TYPE_FOOTER = 1;

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                if (viewType == TYPE_FOOTER) {
                    MaterialButton more = eu.kodanetwork.mchost.util.KodaButtons.primary(
                            CreateServerActivity.this, getString(R.string.modpack_load_more));
                    more.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (int)(56 * getResources().getDisplayMetrics().density)));
                    return new RecyclerView.ViewHolder(more) {};
                }
                android.view.View row = getLayoutInflater().inflate(R.layout.item_modrinth_project, parent, false);
                return new RecyclerView.ViewHolder(row) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                if (getItemViewType(position) == TYPE_FOOTER) {
                    holder.itemView.setOnClickListener(v -> { if (loadPageRef[0] != null) loadPageRef[0].run(); });
                    return;
                }
                final eu.kodanetwork.mchost.util.ModrinthHelper.ModpackHit hit = hits.get(position);
                android.widget.ImageView icon = holder.itemView.findViewById(R.id.iv_project_icon);
                TextView title = holder.itemView.findViewById(R.id.tv_project_title);
                TextView author = holder.itemView.findViewById(R.id.tv_project_author);
                TextView desc = holder.itemView.findViewById(R.id.tv_project_desc);
                View btnDownload = holder.itemView.findViewById(R.id.btn_project_download);
                View pbDl = holder.itemView.findViewById(R.id.pb_project_download);
                if (btnDownload != null) btnDownload.setVisibility(View.GONE);
                if (pbDl != null) pbDl.setVisibility(View.GONE);
                title.setText(hit.title);
                author.setText(hit.author + "  ·  ⬇ " + android.text.format.Formatter.formatShortFileSize(
                        CreateServerActivity.this, Math.max(0, hit.downloads)));
                desc.setText(hit.description);
                if (icon != null) {
                    icon.setImageResource(android.R.drawable.ic_menu_gallery);
                    if (hit.iconUrl != null && !hit.iconUrl.isEmpty()) {
                        eu.kodanetwork.mchost.util.ModrinthHelper.loadIcon(hit.iconUrl, icon);
                    }
                }
                android.view.View.OnClickListener select = v -> {
                    if (selecting[0]) return;
                    selecting[0] = true;
                    HapticUtil.forceVibrate(CreateServerActivity.this, 60);
                    if (pb != null) pb.setVisibility(View.VISIBLE);
                    executor.submit(() -> {
                        final eu.kodanetwork.mchost.util.ModrinthHelper.MrpackInfo info =
                                eu.kodanetwork.mchost.util.ModrinthHelper.getLatestMrpackSync(hit.id);
                        mainHandler.post(() -> {
                            selecting[0] = false;
                            if (pb != null) pb.setVisibility(View.GONE);
                            if (info == null) {
                                Toast.makeText(CreateServerActivity.this,
                                        getString(R.string.modpack_failed, "no server version"), Toast.LENGTH_LONG).show();
                                return;
                            }
                            selectedModpack = info;
                            selectedModpackTitle = hit.title;
                            if (!info.gameVersion.isEmpty()) {
                                filterVersion[0] = info.gameVersion;
                                selectedVersion = info.gameVersion;
                                if (tvVersionSelected != null) tvVersionSelected.setText(selectedVersion);
                                if (updateDevJava != null) updateDevJava.run();
                                Toast.makeText(CreateServerActivity.this,
                                        getString(R.string.modpack_version_applied, info.gameVersion),
                                        Toast.LENGTH_SHORT).show();
                            }
                            if (tvModpackSelected != null) {
                                tvModpackSelected.setVisibility(View.VISIBLE);
                                tvModpackSelected.setText(getString(R.string.modpack_selected, hit.title));
                            }
                            sheet.dismiss();
                        });
                    });
                };
                holder.itemView.setOnClickListener(select);
            }

            @Override
            public int getItemCount() {
                boolean more = fetched[0] < totalHits[0];
                return hits.size() + (more ? 1 : 0);
            }

            @Override
            public int getItemViewType(int position) {
                return position >= hits.size() ? TYPE_FOOTER : TYPE_ITEM;
            }
        };
        rv.setAdapter(adapter);

        loadPageRef[0] = () -> {
            if (selecting[0]) return;
            if (pb != null) pb.setVisibility(View.VISIBLE);
            String versionFilter = filterToSelected[0] ? filterVersion[0] : null;
            eu.kodanetwork.mchost.util.ModrinthHelper.searchModpacks(
                    etQuery.getText().toString().trim(), versionFilter, loaderName, fetched[0],
                    new eu.kodanetwork.mchost.util.ModrinthHelper.ModpackSearchCallback() {
                        @Override
                        public void onResult(java.util.List<eu.kodanetwork.mchost.util.ModrinthHelper.ModpackHit> results, int total) {
                            mainHandler.post(() -> {
                                fetched[0] += results.size();
                                // server-compatible packs only, client-only ones are useless here
                                for (eu.kodanetwork.mchost.util.ModrinthHelper.ModpackHit h : results) {
                                    if (!"unsupported".equals(h.serverSide)) hits.add(h);
                                }
                                totalHits[0] = total;
                                adapter.notifyDataSetChanged();
                                if (pb != null) pb.setVisibility(View.GONE);
                                if (hits.isEmpty()) {
                                    Toast.makeText(CreateServerActivity.this,
                                            getString(R.string.modpack_none_found), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }

                        @Override
                        public void onError(String err) {
                            mainHandler.post(() -> {
                                if (pb != null) pb.setVisibility(View.GONE);
                                Toast.makeText(CreateServerActivity.this,
                                        getString(R.string.modpack_failed, err), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        };
        resetAndLoadRef[0] = () -> {
            hits.clear();
            totalHits[0] = 0;
            fetched[0] = 0;
            adapter.notifyDataSetChanged();
            loadPageRef[0].run();
        };

        // version filter above the list: the selected version or all of them
        android.widget.LinearLayout root = sheet.findViewById(R.id.ll_modrinth_root);
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(40 * getResources().getDisplayMetrics().density));
        tLp.bottomMargin = (int)(10 * getResources().getDisplayMetrics().density);
        TextView tSel = new TextView(this);
        TextView tAll = new TextView(this);
        tSel.setGravity(android.view.Gravity.CENTER);
        tAll.setGravity(android.view.Gravity.CENTER);
        tSel.setTextSize(12); tAll.setTextSize(12);
        tSel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams halfLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        toggleRow.addView(tSel, halfLp);
        toggleRow.addView(tAll, new LinearLayout.LayoutParams(halfLp));
        final Runnable[] updateToggleUi = {null};
        updateToggleUi[0] = () -> {
            boolean sel = filterToSelected[0];
            tSel.setTextColor(sel ? 0xFFFF6B00 : 0xFF8A8A9A);
            tAll.setTextColor(sel ? 0xFF8A8A9A : 0xFFFF6B00);
            tSel.setText(getString(R.string.modpack_filter_version, filterVersion[0].isEmpty() ? "-" : filterVersion[0]));
            tAll.setText(getString(R.string.modpack_filter_all));
        };
        tSel.setOnClickListener(v -> {
            if (!filterToSelected[0]) { filterToSelected[0] = true; HapticUtil.forceVibrate(this, 40); updateToggleUi[0].run(); resetAndLoadRef[0].run(); }
        });
        tAll.setOnClickListener(v -> {
            if (filterToSelected[0]) { filterToSelected[0] = false; HapticUtil.forceVibrate(this, 40); updateToggleUi[0].run(); resetAndLoadRef[0].run(); }
        });
        int rvIndex = root.indexOfChild(rv);
        root.addView(toggleRow, rvIndex);
        updateToggleUi[0].run();
        if (btnSearch != null) btnSearch.setOnClickListener(v -> resetAndLoadRef[0].run());
        if (etQuery != null) etQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                resetAndLoadRef[0].run();
                return true;
            }
            return false;
        });

        // closing the sheet without a pack falls back to Standard
        sheet.setOnDismissListener(d -> {
            modpackSheetOpen = false;
            if (selectedModpack == null && rgSetupType != null) {
                rgSetupType.check(R.id.rb_setup_manual);
            }
        });

        // full-height expanded sheet, exactly like the resource pack browser
        // (peek = full height hides the half->full "double open" and keeps swipe-down dismiss working)
        sheet.setOnShowListener(d -> {
            com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                    (com.google.android.material.bottomsheet.BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<android.view.View> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                bottomSheet.getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
                bottomSheet.requestLayout();
                behavior.setPeekHeight(android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels);
                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        // edge-to-edge decor, same as ServerDetailActivity.setupWindowDecor (transparent nav bar)
        android.view.Window win = sheet.getWindow();
        if (win != null) {
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false);
                boolean isLight = eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this);
                int uiFlags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (isLight) {
                    uiFlags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                win.getDecorView().setSystemUiVisibility(uiFlags);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    win.setNavigationBarContrastEnforced(false);
                }
            }
        }

        sheet.show();
        HapticUtil.applyHapticsToView(sheet.findViewById(R.id.ll_modrinth_root), this);
        loadPageRef[0].run();
    }

    private void setVersionList(List<String> versions) {
        currentVersions = versions;
        if (!versions.isEmpty()) {
            selectedVersion = versions.get(0);
            if (tvVersionSelected != null) tvVersionSelected.setText(selectedVersion);
            if (updateDevJava != null) updateDevJava.run();
            updatePregenerationAvailability();
        }
    }

    private List<String> fetchPaperMcVersions(String project) {
        // shared with the helper so the version switch on a server shows the same list
        List<String> versions = eu.kodanetwork.mchost.util.PaperMCDownloader.fetchPaperVersions();
        if (!versions.isEmpty()) return versions;
        // fallback for other Paper projects (folia) the helper does not cover
        try {
            String json = get("https://fill.papermc.io/v3/projects/" + project);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject versionsObj = obj.getAsJsonObject("versions");
            List<String> res = new ArrayList<>();
            for (String key : versionsObj.keySet()) {
                com.google.gson.JsonArray arr = versionsObj.getAsJsonArray(key);
                for (int i = 0; i < arr.size(); i++) {
                    String ver = arr.get(i).getAsString().trim();
                    if (ver.isEmpty() || ver.contains("-")) continue;
                    res.add(ver);
                }
            }
            return res;
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    private void showVersionPicker() {
        if (currentVersions.isEmpty()) {
            Toast.makeText(this, getString(R.string.version_loading), Toast.LENGTH_SHORT).show();
            return;
        }

        // descriptions come from string resources, so they are localized
        ServerInstance.Type type = TYPE_VALS[selectedTypeIndex];
        Map<String, String> descriptions = new LinkedHashMap<>();

        if (type == ServerInstance.Type.PAPER || type == ServerInstance.Type.PURPUR || type == ServerInstance.Type.FOLIA) {
            if (!currentVersions.isEmpty()) descriptions.put(currentVersions.get(0), getString(R.string.version_desc_latest));
            if (currentVersions.size() > 1) descriptions.put(currentVersions.get(1), getString(R.string.version_desc_stable));
        }
        descriptions.put("1.21.4", getString(R.string.version_desc_1_21_4));
        descriptions.put("1.21.1", getString(R.string.version_desc_1_21_1));
        descriptions.put("1.20.1", getString(R.string.version_desc_1_20_1));
        descriptions.put("1.19.4", getString(R.string.version_desc_1_19_4));
        descriptions.put("1.18.2", getString(R.string.version_desc_1_18_2));
        descriptions.put("1.16.5", getString(R.string.version_desc_1_16_5));
        descriptions.put("1.12.2", getString(R.string.version_desc_1_12_2));
        descriptions.put("3.4.0", getString(R.string.version_desc_velocity_latest));
        descriptions.put("3.3.0", getString(R.string.version_desc_velocity_stable));
        String genericDesc = getString(R.string.version_desc_generic);

        // the wheel lives in KodaVersionWheel so the version switch on an existing
        // server shows exactly the same scroller
        eu.kodanetwork.mchost.util.KodaVersionWheel.show(this, currentVersions, descriptions,
                selectedVersion, getString(R.string.version_picker_title), picked -> {
                    selectedVersion = picked;
                    if (tvVersionSelected != null) tvVersionSelected.setText(picked);
                    if (updateDevJava != null) updateDevJava.run();
                    updatePregenerationAvailability();
                });
    }

    // scales and fades items by distance to the centre; the centre one is Koda orange
    private void applyWheelTransform(RecyclerView rv, int itemHeight) {
        int center = rv.getHeight() / 2;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tv = (TextView) child;
            float childCenter = child.getY() + child.getHeight() / 2f;
            float dist = Math.abs(center - childCenter);
            float t = Math.min(1f, dist / (rv.getHeight() * 0.6f));
            tv.setScaleX(1.25f - 0.35f * t);
            tv.setScaleY(1.25f - 0.35f * t);
            tv.setAlpha(1f - 0.72f * t);
            tv.setTextColor(dist < itemHeight * 0.5f ? 0xFFFF6B00 : 0xFFF0F0F0);
        }
    }

    private int centeredWheelPosition(RecyclerView rv) {
        int center = rv.getHeight() / 2;
        View best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            float d = Math.abs(c.getY() + c.getHeight() / 2f - center);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best != null ? rv.getChildAdapterPosition(best) : -1;
    }

    // wheel items: just the version number, full-width and centered
    private static class VersionWheelAdapter extends RecyclerView.Adapter<VersionWheelAdapter.VH> {
        interface OnItemClickListener {
            void onClick(int position);
        }

        private final List<String> versions;
        private final int itemHeightPx;
        private final OnItemClickListener clickListener;

        VersionWheelAdapter(List<String> versions, int itemHeightPx, OnItemClickListener clickListener) {
            this.versions = versions;
            this.itemHeightPx = itemHeightPx;
            this.clickListener = clickListener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFF0F0F0);
            tv.setTextSize(22);
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(parent.getContext(), R.font.font_koda));
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            holder.tv.setText(versions.get(position));
            holder.tv.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(position);
            });
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tv;

            VH(TextView tv) {
                super(tv);
                this.tv = tv;
            }
        }
    }

    private List<String> fetchPurpurVersions() {
        try {
            String json = get("https://api.purpurmc.org/v2/purpur");
            org.json.JSONObject obj = new org.json.JSONObject(json);
            org.json.JSONArray arr = obj.getJSONArray("versions");
            List<String> res = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String ver = arr.getString(i).trim();
                if (!ver.isEmpty()) res.add(ver);
            }
            Collections.reverse(res); return res;
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    private String get(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000); c.setRequestProperty("User-Agent", "KodaNetwork/3.0");
        try (InputStream is = c.getInputStream(); BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null) sb.append(l); return sb.toString();
        } finally { c.disconnect(); }
    }

    // legacy setVersions, kept so import detection still compiles
    private void setVersions(List<String> versions) { setVersionList(versions); }

    private List<String> listOf(String[] arr) { List<String> l = new ArrayList<>(); for (String s : arr) l.add(s); return l; }

    /** world types offered in the wizard, the values match server.properties */
    private static final String[] WORLD_TYPES = {"normal", "flat", "large_biomes", "amplified"};

    /**
     * fills the world type spinner when the screen is built. the items have to be there before the
     * user taps the spinner, not when the server is created.
     */
    private void setupWorldType() {
        android.widget.Spinner spinner = findViewById(R.id.spinner_world_type);
        if (spinner == null) return;
        final int textColor = eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this) ? 0xFF1B1613 : 0xFFF0F0F0;
        final int rowColor = eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(this) ? 0xFFF5F0EC : 0xFF241C18;
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, WORLD_TYPES) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setTextColor(textColor);
                }
                return view;
            }

            @Override
            public android.view.View getDropDownView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setTextColor(textColor);
                    view.setBackgroundColor(rowColor);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
    }

    private void setupRam() {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long totalMegs = mi.totalMem / 1048576L;
        long freeMegs = mi.availMem / 1048576L;

        // the ceiling comes from the device (total memory minus a reserve for Android), not from the
        // momentary "available" value, that one is measured while the app, its JVM and every other
        // app already hold memory, and it capped the slider at a few hundred MB on a 12 GB phone.
        long androidReserve = Math.min(1024, Math.round(totalMegs * 0.25));
        long runningOthers = 0;
        for (ServerInstance other : eu.kodanetwork.mchost.model.ServerRepo.get(this).all()) {
            if (other.state == ServerInstance.State.ONLINE || other.state == ServerInstance.State.STARTING
                    || other.state == ServerInstance.State.SETTING_UP) {
                runningOthers += other.getRamMB();
            }
        }
        long deviceCeiling = totalMegs - androidReserve;              // absolute limit for one server
        long safeNow = Math.max(512, deviceCeiling - runningOthers);   // what fits right now
        safeRamNow = safeNow;

        int maxIndex = 0;
        for (int i = 0; i < RAM_STEPS.length; i++) {
            if (RAM_STEPS[i] <= deviceCeiling) maxIndex = i;
        }
        if (maxIndex == 0 && deviceCeiling < RAM_STEPS[0]) maxIndex = 0; // minimum is allowed too

        long targetRam = Math.min(Math.max(1024, (long) (totalMegs * 0.25)), safeNow);
        int targetIndex = 0;
        for (int i = 0; i <= maxIndex; i++) {
            if (RAM_STEPS[i] <= targetRam) targetIndex = i;
        }

        seekRam.setMax(maxIndex);
        seekRam.setProgress(targetIndex);
        updateRam(RAM_STEPS[targetIndex]);

        // tell the user what the numbers mean instead of silently hiding options
        ramBudgetText = getString(R.string.ram_budget_hint, totalMegs / 1024, runningOthers, safeNow);
        updateRam(RAM_STEPS[targetIndex]);

        seekRam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { updateRam(RAM_STEPS[p]); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private String ramBudgetText = "";

    private void updateRam(int mb) {
        ramMB = mb;
        String value = mb >= 1024 ? (mb / 1024) + "GB" : mb + "MB";
        boolean aboveSafe = safeRamNow > 0 && mb > safeRamNow;
        tvRamValue.setText(aboveSafe ? value + " (above the recommended " + (safeRamNow / 1024) + "GB)" : value);
        tvRamValue.setTextColor(aboveSafe ? 0xFFFFCC00 : 0xFFFFFFFF);
        android.widget.TextView tvBudget = findViewById(R.id.tv_ram_budget);
        if (tvBudget != null) {
            tvBudget.setText(ramBudgetText);
            tvBudget.setVisibility(ramBudgetText.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        }
    }

    private long safeRamNow = 0;

    private void setupNameWatcher() {
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){
                updatePreview();
                // editing the name resets the gate so a new concerning name gets checked again
                crisisConfirmed = false;
            }
        };
        etName.addTextChangedListener(w); updatePreview();
    }

    
    private void updateDomainUI() {
        android.widget.TextView btnKodaNetwork = findViewById(R.id.btn_domain_kodanetwork);
        android.widget.TextView btnKodaServ = findViewById(R.id.btn_domain_kodaserv);
        android.view.View wrapperKodaNetwork = findViewById(R.id.wrapper_domain_kodanetwork);
        android.view.View wrapperKodaServ = findViewById(R.id.wrapper_domain_kodaserv);
        android.view.View pill = findViewById(R.id.pill_domain);
        
        if (btnKodaNetwork == null || btnKodaServ == null || wrapperKodaNetwork == null || wrapperKodaServ == null || pill == null) return;
        
        android.widget.TextView activeText = "kodanetwork.eu".equals(selectedBaseDomain) ? btnKodaNetwork : btnKodaServ;
        android.view.View activeWrapper = "kodanetwork.eu".equals(selectedBaseDomain) ? wrapperKodaNetwork : wrapperKodaServ;
        
        btnKodaNetwork.setTextColor(android.graphics.Color.parseColor("#888899"));
        btnKodaServ.setTextColor(android.graphics.Color.parseColor("#888899"));
        activeText.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));

        activeWrapper.post(() -> {
            pill.animate()
                .translationX(activeWrapper.getX())
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
            android.view.ViewGroup.LayoutParams params = pill.getLayoutParams();
            params.width = activeWrapper.getWidth();
            pill.requestLayout();
        });
    }

    private void updatePreview() {
        String name = etName.getText().toString(); String sub = name.toLowerCase().trim().replaceAll("[^a-z0-9]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (sub.isEmpty()) sub = "yourserver";
        tvAddressPreview.setText(sub + "." + getSelectedBaseDomain());
    }
    
    private void setupAiChat() {
        android.widget.EditText etPrompt = findViewById(R.id.et_ai_prompt);
        View btnSend = findViewById(R.id.btn_ai_send);
        android.widget.LinearLayout llChat = findViewById(R.id.ll_ai_chat);
        android.widget.ScrollView svChat = findViewById(R.id.sv_ai_chat);
        if (etPrompt == null || btnSend == null || llChat == null) return;
        
        View btnFullscreen = findViewById(R.id.btn_ai_fullscreen);
        View btnApprove = findViewById(R.id.btn_ai_approve);
        
        if (btnApprove != null) {
            btnApprove.setOnClickListener(v -> {
                android.widget.RadioGroup rg = findViewById(R.id.rg_setup_type);
                if (rg != null) rg.check(R.id.rb_setup_ai);
                
                String currentName = etName.getText().toString().trim();
                if (currentName.isEmpty() || currentName.startsWith("AI-Server-")) {
                    if (aiSuggestedName != null && !aiSuggestedName.isEmpty()) {
                        etName.setText(aiSuggestedName);
                    } else if (currentName.isEmpty()) {
                        etName.setText("AI-Server-" + new java.util.Random().nextInt(1000));
                    }
                }
                
                if (svChat.getLayoutParams().height == 0) {
                    btnFullscreen.performClick(); // leave fullscreen on its own
                }
                
                createServer();
            });
        }
        
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> {
                boolean isFs = (svChat.getLayoutParams().height == android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
                android.widget.LinearLayout rootLayout = findViewById(R.id.root_layout);
                android.widget.ScrollView mainScroll = findViewById(R.id.main_scroll);
                View aiLayout = findViewById(R.id.layout_ai_prompt);
                
                if (!isFs) {
                    // fullscreen: hide everything except the chat
                    if (rootLayout != null) {
                        for (int i=0; i<rootLayout.getChildCount(); i++) {
                            View c = rootLayout.getChildAt(i);
                            if (c != mainScroll) c.setVisibility(View.GONE);
                        }
                    }
                    android.widget.LinearLayout scrollContent = (android.widget.LinearLayout) mainScroll.getChildAt(0);
                    for (int i=0; i<scrollContent.getChildCount(); i++) {
                        View c = scrollContent.getChildAt(i);
                        if (c != aiLayout) c.setVisibility(View.GONE);
                    }
                    mainScroll.setFillViewport(true);
                    
                    android.widget.LinearLayout.LayoutParams aiLp = (android.widget.LinearLayout.LayoutParams) aiLayout.getLayoutParams();
                    aiLp.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
                    aiLayout.setLayoutParams(aiLp);
                    
                    android.widget.LinearLayout.LayoutParams chatLp = (android.widget.LinearLayout.LayoutParams) svChat.getLayoutParams();
                    chatLp.height = 0;
                    chatLp.weight = 1.0f;
                    svChat.setLayoutParams(chatLp);
                } else {
                    // back out of fullscreen
                    if (rootLayout != null) {
                        for (int i=0; i<rootLayout.getChildCount(); i++) {
                            rootLayout.getChildAt(i).setVisibility(View.VISIBLE);
                        }
                    }
                    android.widget.LinearLayout scrollContent = (android.widget.LinearLayout) mainScroll.getChildAt(0);
                    for (int i=0; i<scrollContent.getChildCount(); i++) {
                        scrollContent.getChildAt(i).setVisibility(View.VISIBLE);
                    }
                    mainScroll.setFillViewport(false);
                    
                    android.widget.LinearLayout.LayoutParams aiLp = (android.widget.LinearLayout.LayoutParams) aiLayout.getLayoutParams();
                    aiLp.height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;
                    aiLayout.setLayoutParams(aiLp);
                    
                    android.widget.LinearLayout.LayoutParams chatLp = (android.widget.LinearLayout.LayoutParams) svChat.getLayoutParams();
                    chatLp.height = (int)(200 * getResources().getDisplayMetrics().density);
                    chatLp.weight = 0.0f;
                    svChat.setLayoutParams(chatLp);
                }
                svChat.requestLayout();
                aiLayout.requestLayout();
            });
        }
        
        btnSend.setOnClickListener(v -> {
            String text = etPrompt.getText().toString().trim();
            if (text.isEmpty()) return;
            etPrompt.setText("");
            
            try {
                org.json.JSONObject userMsg = new org.json.JSONObject();
                userMsg.put("role", "user");
                userMsg.put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", text)));
                aiChatHistory.put(userMsg);
            } catch (Exception ignored) {}
            
            addChatBubble(llChat, "You: " + text, true);
            svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
            btnSend.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            
            TextView typingLabel = addChatBubble(llChat, "Gemini is typing...", false);
            svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
            
            String apiKey = eu.kodanetwork.mchost.App.getPrefs(this).getString("gemini_api_key", "");
            if (apiKey.isEmpty()) {
                llChat.removeView(typingLabel);
                addChatBubble(llChat, "Error: No Gemini API Key set in Settings.", false);
                return;
            }
            
            new Thread(() -> {
                try {
                    String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=" + apiKey;
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    org.json.JSONObject payload = new org.json.JSONObject();
                    
                    org.json.JSONObject sysInst = new org.json.JSONObject();
                    String serverType = TYPE_VALS[selectedTypeIndex].name();
                    String generatedIp = ((EditText) findViewById(R.id.et_name)).getText().toString().trim().replaceAll("[^a-zA-Z0-9-]", "").toLowerCase() + "." + getSelectedBaseDomain();
                    if (generatedIp.equals("." + getSelectedBaseDomain())) generatedIp = "play." + getSelectedBaseDomain();

                    String promptText = "You are a Minecraft server expert building a comprehensive server. " +
                        "The server is running " + serverType + " version " + selectedVersion + ". The server IP is " + generatedIp + ". " +
                        "Phase 1: Discuss the server idea with the user. DO NOT output plugins yet. Keep plugins array empty. Set current_phase to 1. " +
                        "Phase 2: When the user is happy, list all the necessary plugins for a REAL, full production server (30 to 90 plugins!). Set current_phase to 2. " +
                        "CRITICAL: When choosing plugins in Phase 2, you MUST explicitly include ALL required dependencies in the 'plugins' array (e.g. ProtocolLib, Vault, PlaceholderAPI, LuckPerms), otherwise the server will crash! " +
                        "ONLY suggest Modrinth project IDs that are strictly compatible with " + serverType + " " + selectedVersion + ". " +
                        "You must ALWAYS output valid JSON matching this schema exactly: " +
                        "{\"current_phase\": 1 or 2, \"server_name\": \"Cool Name for the Server\", \"chat_reply\": \"your message to the user\", \"theme_color\": \"#HEXCODE\", \"plugins\": [\"modrinth_project_id_1\", ...]}";
                    sysInst.put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", promptText)));
                    payload.put("systemInstruction", sysInst);
                    
                    payload.put("contents", aiChatHistory);
                    
                    org.json.JSONObject genConfig = new org.json.JSONObject();
                    genConfig.put("responseMimeType", "application/json");
                    payload.put("generationConfig", genConfig);
                    
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(payload.toString().getBytes());
                    os.flush(); os.close();
                    
                    final int code = conn.getResponseCode();
                    if (code == 200) {
                        java.io.InputStreamReader r = new java.io.InputStreamReader(conn.getInputStream());
                        StringBuilder sb = new StringBuilder();
                        int c; while ((c = r.read()) != -1) sb.append((char) c);
                        r.close();
                        
                        org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                        String resText = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                        
                        try {
                            org.json.JSONObject modelMsg = new org.json.JSONObject();
                            modelMsg.put("role", "model");
                            modelMsg.put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", resText)));
                            aiChatHistory.put(modelMsg);
                        } catch (Exception ignored) {}
                        
                        org.json.JSONObject resJson = new org.json.JSONObject(resText);
                        String reply = resJson.optString("chat_reply", "I have updated the design!");
                        String theme = resJson.optString("theme_color", "#FF6B00");
                        String serverName = resJson.optString("server_name", "");
                        org.json.JSONArray plugins = resJson.optJSONArray("plugins");
                        if (plugins == null) plugins = new org.json.JSONArray();
                        int phase = resJson.optInt("current_phase", 1);
                        
                        aiSelectedTheme = theme;
                        aiSelectedPlugins = plugins;
                        if (!serverName.isEmpty()) {
                            aiSuggestedName = serverName;
                        }
                        
                        runOnUiThread(() -> {
                            llChat.removeView(typingLabel);
                            addChatBubble(llChat, "Gemini: " + reply, false);
                            svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
                            try {
                                android.os.Vibrator vib = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
                                if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                            } catch (Exception ignored) {}
                            
                            if (btnApprove != null) {
                                btnApprove.setVisibility(phase == 2 ? View.VISIBLE : View.GONE);
                                TextView hint = findViewById(R.id.tv_ai_chat_hint);
                                if (hint != null) hint.setVisibility(phase == 2 ? View.VISIBLE : View.GONE);
                            }
                        });
                    } else {
                        runOnUiThread(() -> {
                            llChat.removeView(typingLabel);
                            addChatBubble(llChat, "Error communicating with Gemini (Code: " + code + ")", false);
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        llChat.removeView(typingLabel);
                        addChatBubble(llChat, "Error: " + e.getMessage(), false);
                    });
                }
            }).start();
        });
    }

    private TextView addChatBubble(LinearLayout parent, String text, boolean isUser) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setTextSize(14f);
        tv.setPadding(32, 24, 32, 24);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 16);
        lp.gravity = isUser ? android.view.Gravity.END : android.view.Gravity.START;
        tv.setLayoutParams(lp);
        
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(24f);
        gd.setColor(isUser ? android.graphics.Color.parseColor("#FF6B00") : android.graphics.Color.parseColor("#2A2A35"));
        tv.setBackground(gd);
        
        parent.addView(tv);
        return tv;
    }

    
    private String getSelectedBaseDomain() {
        return selectedBaseDomain != null ? selectedBaseDomain : "kodanetwork.eu";
    }

    private void createServer() {
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        long lastCreateTime = prefs.getLong("last_server_create_time", 0);
        if (System.currentTimeMillis() - lastCreateTime < 120000) {
            long remaining = 120 - ((System.currentTimeMillis() - lastCreateTime) / 1000);
            android.widget.Toast.makeText(this, getString(R.string.err_server_create_cooldown, remaining), android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        int maxServers = prefs.getInt("max_servers_limit", 5);
        int activeCount = 0;
        for (eu.kodanetwork.mchost.model.ServerInstance srv : eu.kodanetwork.mchost.model.ServerRepo.get(this).all()) {
            if (srv.state != eu.kodanetwork.mchost.model.ServerInstance.State.HIBERNATED) {
                activeCount++;
            }
        }
        if (activeCount >= maxServers) {
            android.widget.Toast.makeText(this, getString(R.string.err_server_limit_reached, maxServers), android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        if (!eu.kodanetwork.mchost.security.PraetorSystem.checkNetwork(this)) return;
        if (!eu.kodanetwork.mchost.security.PraetorSystem.checkConcurrentServer(this)) return;

        String name = etName.getText().toString().trim(); if (name.isEmpty()) { etName.setError("Required"); return; }

        // a name that suggests the person is not okay opens the support screen first.
        // like the biometric gate: creation continues after the screen comes back.
        if (!crisisConfirmed && eu.kodanetwork.mchost.util.CrisisTextDetector.matches(name)) {
            crisisConfirmed = true;
            startActivityForResult(new android.content.Intent(this,
                    eu.kodanetwork.mchost.ui.CrisisSupportActivity.class),
                    eu.kodanetwork.mchost.ui.CrisisSupportActivity.REQ_CRISIS);
            return;
        }
        // an import gets one PRAETOR check of its own: does the app look at the right
        // software, and when the import brought no server file, is downloading one okay
        if (sourceUri != null && !importConfirmed) {
            importConfirmed = true;
            android.content.Intent w = new android.content.Intent(this,
                    eu.kodanetwork.mchost.ui.PraetorWarningActivity.class);
            w.putExtra(eu.kodanetwork.mchost.ui.PraetorWarningActivity.EXTRA_REASON, buildImportConfirmText());
            w.putExtra(eu.kodanetwork.mchost.ui.PraetorWarningActivity.EXTRA_ACTION, getString(
                    importHasServerFile() ? R.string.import_confirm_action : R.string.import_confirm_action_download));
            startActivityForResult(w, REQ_IMPORT_CONFIRM);
            return;
        }

        int port = 30000 + new java.util.Random().nextInt(10000);
        String version = selectedVersion != null && !selectedVersion.isEmpty() ? selectedVersion : "1.21.4";
        ServerInstance.Type type = TYPE_VALS[selectedTypeIndex];
        // safety net: don't block Forge/NeoForge any more, they are supported now
        String id  = UUID.randomUUID().toString();
        boolean useNative = swUseNative.isChecked();
        String dir = useNative ? new File(getFilesDir(), "servers/" + id).getAbsolutePath() : android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS) + "/KodaNetwork/servers/" + id;

        ServerInstance s = new ServerInstance(id, name, type, version, ramMB, port, dir);
                    s.setBaseDomain(getSelectedBaseDomain());
        int checkedId = rgSetupType.getCheckedRadioButtonId();
        s.setUseNative(useNative);
        s.setAutoSetup(checkedId == R.id.rb_setup_koda || checkedId == R.id.rb_setup_ai);
        // modpack server: remember the pack, skip the auto-design plugins; the fabric jar
        // download and start are triggered through the auto_setup extra below
        boolean modpackServer = selectedModpack != null && (type == ServerInstance.Type.FABRIC || type == ServerInstance.Type.FORGE || type == ServerInstance.Type.NEOFORGE);
        if (modpackServer) {
            s.setAutoSetup(false);
            s.setModpackName(selectedModpackTitle != null ? selectedModpackTitle : "");
        }
        s.setJavaRuntime(createJavaRuntime);
        // world type and seed were picked in the wizard (see setupWorldType)
        android.widget.Spinner spinnerWorldType = findViewById(R.id.spinner_world_type);
        android.widget.EditText etWorldSeed = findViewById(R.id.et_world_seed);
        if (spinnerWorldType != null) {
            s.setLevelType(WORLD_TYPES[spinnerWorldType.getSelectedItemPosition()]);
        }
        if (etWorldSeed != null) {
            s.setLevelSeed(etWorldSeed.getText().toString().trim());
        }

        // world pre-generation is asked here, once, while the server is being created
        android.widget.CompoundButton swPregen = findViewById(R.id.switch_pregenerate);
        s.setPregenerate(swPregen != null && swPregen.isChecked());
        if (checkedId == R.id.rb_setup_ai) {
            s.setThemeColor(aiSelectedTheme);
            s.setAiPrompt(aiSelectedPlugins.toString());
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        } else {
            s.setThemeColor(selectedColor);
        }
        
        android.widget.FrameLayout loadingOverlay = findViewById(R.id.layout_java_extract);
        android.widget.TextView loadingText = findViewById(R.id.tv_extract_msg);

        if (loadingOverlay != null && loadingText != null) {
            loadingText.setText(getString(R.string.loading_check_servername));
            loadingOverlay.setVisibility(android.view.View.VISIBLE);
            animateLightBlob(findViewById(R.id.java_extract_light_blob));
        }

        executor.submit(() -> {
            boolean portAllocated = false;
            try {
                boolean isTaken = false;
                try {
                    isTaken = new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(this).checkServerName("", s.getSubdomain(), s.getBaseDomain());
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (loadingOverlay != null) loadingOverlay.setVisibility(android.view.View.GONE);
                        android.widget.Toast.makeText(CreateServerActivity.this, "Fehler bei der Namensprüfung: " + getFriendlyErrorMsg(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                if (isTaken) {
                    mainHandler.post(() -> {
                        if (loadingOverlay != null) loadingOverlay.setVisibility(android.view.View.GONE);
                        android.widget.Toast.makeText(CreateServerActivity.this, "Fehler: Servername / Subdomain ist bereits vergeben!", android.widget.Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                try {
                    int allocatedPort = new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(CreateServerActivity.this).allocatePort("", s.getSubdomain(), "main");
                    s.setPort(allocatedPort);
                    portAllocated = true;
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (loadingOverlay != null) loadingOverlay.setVisibility(android.view.View.GONE);
                        if (e.getMessage() != null && e.getMessage().contains("ports_exhausted")) {
                            eu.kodanetwork.mchost.ui.components.PraetorDialog.showApology(CreateServerActivity.this, "P.R.A.E.T.O.R.", "Alle KodaNetwork Proxy-Ports sind derzeit belegt. Bitte versuche es später erneut.");
                        } else {
                            android.widget.Toast.makeText(CreateServerActivity.this, "Fehler bei der Port-Zuweisung: " + getFriendlyErrorMsg(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }


                if (sourceUri != null) {
                    // whatever the user picked in the import form is what the launcher uses later
                    s.setLauncherJar(pickedLauncherJar);
                    mainHandler.post(() -> {
                        if (loadingText != null) loadingText.setText(isZipImport ? "Extracting ZIP..." : "Importing files...");
                    });
                    if (isZipImport) {
                        extractZip(sourceUri, new File(dir));
                    } else {
                        DocumentFile sourceDir = DocumentFile.fromTreeUri(this, sourceUri);
                        if (sourceDir != null) copyRecursive(sourceDir, new File(dir));
                    }

                    File pluginsDir = new File(dir, "plugins");
                    if (pluginsDir.exists() && pluginsDir.isDirectory()) {
                        File[] jarFiles = pluginsDir.listFiles((d, fileName) -> fileName.endsWith(".jar"));
                        if (jarFiles != null && jarFiles.length > 0) {
                            for (int i = 0; i < jarFiles.length; i++) {
                                File jar = jarFiles[i];
                                int step = i + 1;
                                mainHandler.post(() -> {
                                    if (loadingText != null) loadingText.setText("Scanning Plugins (" + step + "/" + jarFiles.length + "): " + jar.getName());
                                });
                                eu.kodanetwork.mchost.util.ModrinthHelper.scanAndUpdateImportedPluginSync(jar, s, mainHandler, loadingText);
                            }
                        }
                    }
                }

                // modpack install: mods + overrides first, before the server row even exists
                if (selectedModpack != null && type == ServerInstance.Type.FABRIC) {
                    final String packTitle = selectedModpackTitle != null ? selectedModpackTitle : "Modpack";
                    mainHandler.post(() -> {
                        if (loadingText != null) loadingText.setText(getString(R.string.modpack_downloading, packTitle));
                    });
                    try {
                        eu.kodanetwork.mchost.util.ModpackInstaller.installSync(
                                selectedModpack.downloadUrl, selectedModpack.sha1, new File(dir),
                                new eu.kodanetwork.mchost.util.ModpackInstaller.InstallListener() {
                                    @Override public void onPhaseDownload() {}
                                    @Override public void onDone() {}
                                    @Override public void onError(String m) {}
                                    @Override public void onPhaseFiles(int done, int total) {
                                        mainHandler.post(() -> {
                                            if (loadingText != null) {
                                                loadingText.setText(getString(R.string.modpack_installing_files, done, total));
                                            }
                                        });
                                    }
                                });
                    } catch (Exception mex) {
                        throw new RuntimeException(getString(R.string.modpack_failed,
                                mex.getMessage() == null ? "unknown" : mex.getMessage()));
                    }
                }

                mainHandler.post(() -> {
                    ServerRepo.get(this).add(s);
                    // the server row MUST exist before the DNS link: create-dns-link
                    // checks the host owner against the DB row since the security fix
                    new Thread(() -> {
                        boolean inserted = false;
                        try {
                            String appUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "unknown");
                            String anonKey = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
                            String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
                            org.json.JSONObject json = new org.json.JSONObject()
                                    .put("p_app_uuid", appUuid)
                                    .put("p_device_token", deviceToken)
                                    .put("p_host", s.getSubdomain())
                                    .put("p_base_domain", s.getBaseDomain() != null ? s.getBaseDomain() : "kodanetwork.eu");
                            for (int attempt = 0; attempt < 3 && !inserted; attempt++) {
                                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                                        eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl() + "/rest/v1/rpc/rpc_create_server").openConnection();
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty("Content-Type", "application/json");
                                conn.setRequestProperty("apikey", anonKey);
                                conn.setRequestProperty("Authorization", "Bearer " + anonKey);
                                conn.setConnectTimeout(10000);
                                conn.setReadTimeout(15000);
                                conn.setDoOutput(true);
                                java.io.OutputStream os = conn.getOutputStream();
                                os.write(json.toString().getBytes());
                                os.flush(); os.close();
                                int code = conn.getResponseCode();
                                conn.disconnect();
                                if (code >= 200 && code < 300) { inserted = true; break; }
                                android.util.Log.w("CreateServer", "rpc_create_server HTTP " + code + " (attempt " + (attempt + 1) + ")");
                                Thread.sleep(1000L * (attempt + 1));
                            }
                        } catch (Exception e) {
                            android.util.Log.e("CreateServer", "rpc_create_server failed", e);
                        }

                        if (inserted) {
                            try {
                                new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(CreateServerActivity.this).createDnsLink("", s.getSubdomain(), s.getBaseDomain(), vpsIp(), s.getPort(), "tcp");
                            } catch (Exception ignored) {}
                        } else {
                            // row missing, so status reports and delete would 400 later.
                            // flag it for self-heal through the Settings sync / heartbeat.
                            eu.kodanetwork.mchost.App.getPrefs(this).edit().putBoolean("pending_row_sync", true).apply();
                            mainHandler.post(() -> android.widget.Toast.makeText(CreateServerActivity.this,
                                    getString(R.string.server_row_sync_failed), android.widget.Toast.LENGTH_LONG).show());
                        }
                    }).start();
                    eu.kodanetwork.mchost.App.getPrefs(this).edit().putLong("last_server_create_time", System.currentTimeMillis()).apply();
                    if (loadingOverlay != null) loadingOverlay.setVisibility(android.view.View.GONE);
                    android.content.Intent i;
                    if (s.isPregenerate()) {
                        // pre-generation has its own screen: it shows chunky live while the
                        // server runs, so the owner watches the world grow instead of a dashboard
                        i = new android.content.Intent(this, eu.kodanetwork.mchost.ui.PregenerationActivity.class);
                        i.putExtra(eu.kodanetwork.mchost.ui.PregenerationActivity.EXTRA_SERVER_ID, id);
                    } else {
                        i = new android.content.Intent(this, ServerDetailActivity.class);
                    }
                    // modpack: auto_setup fetches the fabric jar and starts the server afterwards
                    i.putExtra("id", id);
                    i.putExtra("auto_setup", sourceUri == null && (s.isAutoSetup() || modpackServer));
                    startActivity(i);
                    finish();
                });
            } catch (Exception e) {
                if (portAllocated) {
                    // rollback: give the port back if the local setup failed
                    try {
                        new eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient(this).deleteDnsLink("", s.getSubdomain(), s.getBaseDomain());
                    } catch (Exception ignored) {}
                }
                mainHandler.post(() -> {
                    if (loadingOverlay != null) loadingOverlay.setVisibility(android.view.View.GONE);
                    android.widget.Toast.makeText(CreateServerActivity.this, "Fehler: " + getFriendlyErrorMsg(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String getFriendlyErrorMsg(String rawMsg) {
        if (rawMsg == null) return getString(R.string.error_unknown);
        if (rawMsg.contains("profanity_detected")) {
            return getString(R.string.error_profanity_detected);
        }
        if (rawMsg.contains("invalid_characters")) {
            return getString(R.string.error_invalid_characters);
        }
        if (rawMsg.contains("length_invalid")) {
            return getString(R.string.error_length_invalid);
        }
        if (rawMsg.contains("ports_exhausted")) {
            return getString(R.string.error_ports_exhausted);
        }
        return rawMsg;
    }

    private void extractZip(android.net.Uri zipUri, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) throw new IOException("Could not create " + destDir);
        try (InputStream is = getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!newFile.exists() && !newFile.mkdirs()) throw new IOException("Could not create dir " + newFile);
                } else {
                    File parent = newFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create dir " + parent);
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void copyRecursive(DocumentFile source, File dest) throws IOException {
        if (source.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("Could not create " + dest);
            for (DocumentFile f : source.listFiles()) {
                copyRecursive(f, new File(dest, f.getName()));
            }
        } else {
            try (InputStream is = getContentResolver().openInputStream(source.getUri());
                 OutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        // re-theme for light mode (the form is static XML but the pickers are dynamic)
        eu.kodanetwork.mchost.util.ThemeHelper.reapply(this);
        android.content.SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(this);
        String currentTheme = prefs.getString("app_theme", "modern");
        String currentMode = prefs.getString("theme_mode", "dark");
        if (lastThemeMode.equals("dark") && lastTheme.equals("modern")) {
            // first run check
        }
        if (!currentTheme.equals(lastTheme) || !currentMode.equals(lastThemeMode)) {
            lastTheme = currentTheme;
            lastThemeMode = currentMode;
            recreate();
        }
        eu.kodanetwork.mchost.util.HapticUtil.applyHaptics(this);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        eu.kodanetwork.mchost.App.resetAfkTimer();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() { super.onDestroy(); executor.shutdownNow(); }

    /** resolves the VPS ip at runtime from the build config, no hardcoded ip in the source. */
    private static String vpsIp() {
        try {
            return java.net.InetAddress.getByName(
                    eu.kodanetwork.mchost.security.PraetorSecurity.getBoreHost()).getHostAddress();
        } catch (Exception e) {
            return eu.kodanetwork.mchost.security.PraetorSecurity.getBoreHost();
        }
    }
}
