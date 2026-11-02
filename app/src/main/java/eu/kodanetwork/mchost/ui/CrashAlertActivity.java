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

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.CrashFixer;
import eu.kodanetwork.mchost.util.HapticUtil;

import java.io.*;
import java.util.LinkedList;

/**
  * full-screen PRAETOR-style crash alert with the cause analysis, a fix button and the crash log.
  * replaces the old alarm-based crash screen.
 */
public class CrashAlertActivity extends androidx.appcompat.app.AppCompatActivity {
    private ServerInstance srv;
    private String name, crashReason, crashCategory;
    private String crashStack;
    private Typeface kodaFont, kodaBold;
    private ObjectAnimator anim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        // the crash screen sits on the same warm surface as the sheets, nav bar included.
        // android 15+ ignores setNavigationBarColor, so the window has to go edge to edge
        // and the surface behind the bar has to be our own background.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF241C18);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        // ── intent data ──────────────────────────────────────────────────────
        String id            = getIntent().getStringExtra("id");
        name          = getIntent().getStringExtra("name");
        crashReason   = getIntent().getStringExtra("crashReason");
        crashCategory = getIntent().getStringExtra("crashCategory");
        String crashFix      = getIntent().getStringExtra("crashFix");
        String crashFixAction= getIntent().getStringExtra("crashFixAction");
crashStack = getIntent().getStringExtra("crashStackTrace");
        int    crashExitCode = getIntent().getIntExtra("crashExitCode", 0);
        String serverType    = getIntent().getStringExtra("serverType");
        String serverVersion = getIntent().getStringExtra("serverVersion");
        int    serverRam     = getIntent().getIntExtra("serverRam", 0);
        int    serverPort    = getIntent().getIntExtra("serverPort", 0);
        srv = ServerRepo.get(this).byId(id);
        
        if (srv != null) {
            if (crashReason == null) crashReason = srv.crashReason;
            if (crashCategory == null) crashCategory = srv.crashCategory;
            if (crashFix == null) crashFix = srv.crashFix;
            if (crashFixAction == null) crashFixAction = srv.crashFixAction;
            if (crashStack == null) crashStack = srv.crashStackTrace;
            if (crashExitCode == 0) crashExitCode = srv.crashExitCode;
            if (serverType == null) serverType = srv.getType().name();
            if (serverVersion == null) serverVersion = srv.getVersion();
            if (serverRam == 0) serverRam = srv.getRamMB();
            if (serverPort == 0) serverPort = srv.getPort();
        }

        // ── vibration feedback (the alarm sound is gone) ─────────────────────
        HapticUtil.forceVibrate(this, 200);
        new Handler(Looper.getMainLooper()).postDelayed(() -> HapticUtil.forceVibrate(this, 300), 300);
        new Handler(Looper.getMainLooper()).postDelayed(() -> HapticUtil.forceVibrate(this, 150), 700);

        kodaFont = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk_regular);
        kodaBold = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk_bold);

        // ── root layout: the same language as the praetor sheets ────────────
        getWindow().getDecorView().setBackgroundColor(0xFF241C18);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF241C18);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.START);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));

        // the root itself is the card here, there is no wrapper
        LinearLayout card = root;

        // ── wordmark row, small letters plus the red triangle like the sheets ──
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvPraetor = new TextView(this);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>";
        tvPraetor.setText(Html.fromHtml(praetorHtml, Html.FROM_HTML_MODE_LEGACY));
        tvPraetor.setId(R.id.tv_praetor_wordmark);
        tvPraetor.setTextSize(11f);
        tvPraetor.setTypeface(kodaFont != null ? kodaFont : Typeface.DEFAULT);
        tvPraetor.setLetterSpacing(0.18f);
        headerRow.addView(tvPraetor);

        View headerSpacer = new View(this);
        headerRow.addView(headerSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tvWarningTriangle = new TextView(this);
        tvWarningTriangle.setText("\u26A0");
        tvWarningTriangle.setTextColor(0xFFFF5449);
        tvWarningTriangle.setTextSize(15f);
        headerRow.addView(tvWarningTriangle);

        card.addView(headerRow);

        // ── title, left aligned like every sheet ─────────────────────────────
        TextView tvTitle = new TextView(this);
        tvTitle.setText(getString(R.string.crash_title));
        tvTitle.setTextColor(0xFFF5F5F7);
        tvTitle.setTextSize(21f);
        tvTitle.setTypeface(kodaBold != null ? kodaBold : Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(0, dp(10), 0, 0);
        card.addView(tvTitle);

        // ── server info line ─────────────────────────────────────────────────
        String typeDisplay = serverType != null ? serverType : "Unknown";
        String verDisplay = serverVersion != null ? serverVersion : "";
        TextView tvServerInfo = new TextView(this);
        tvServerInfo.setText(getString(R.string.crash_server_info, name != null ? name : "Unknown", typeDisplay, verDisplay));
        tvServerInfo.setTextColor(0xFF8A8A9A);
        tvServerInfo.setTextSize(13f);
        tvServerInfo.setPadding(0, dp(6), 0, 0);
        card.addView(tvServerInfo);

        // ── exit code ────────────────────────────────────────────────────────
        if (crashExitCode != 0) {
            TextView tvExitCode = new TextView(this);
            tvExitCode.setText(getString(R.string.crash_exit_code, crashExitCode));
            tvExitCode.setTextColor(0xFFFF8A80);
            tvExitCode.setTextSize(11f);
            tvExitCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvExitCode.setLetterSpacing(0.08f);
            tvExitCode.setPadding(0, dp(6), 0, 0);
            card.addView(tvExitCode);
        }

        // ══════════════════════════════════════════════════════════════════════
        //  cause card
        // ══════════════════════════════════════════════════════════════════════
        if (crashCategory != null || crashReason != null) {
            LinearLayout causeCard = new LinearLayout(this);
            causeCard.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable causeBg = new GradientDrawable();
            causeBg.setColor(0xFF1C1C22);
            causeBg.setCornerRadius(dp(10));
            causeCard.setBackground(causeBg);
            causeCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams causeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            causeParams.setMargins(0, dp(20), 0, 0);
            causeCard.setLayoutParams(causeParams);

            // the "cause" label
            TextView tvCauseLabel = new TextView(this);
            tvCauseLabel.setText(getString(R.string.crash_cause_label).toUpperCase());
            tvCauseLabel.setTextColor(0xFFFF8A80);
            tvCauseLabel.setTextSize(11f);
            tvCauseLabel.setTypeface(kodaFont, Typeface.BOLD);
            tvCauseLabel.setLetterSpacing(0.12f);
            causeCard.addView(tvCauseLabel);

            // category in big type
            String categoryDisplay = getCategoryDisplayName(crashCategory);
            TextView tvCategory = new TextView(this);
            tvCategory.setText(categoryDisplay);
            tvCategory.setTextColor(0xFFF5F5F7);
            tvCategory.setTextSize(18f);
            tvCategory.setTypeface(kodaBold != null ? kodaBold : Typeface.DEFAULT_BOLD);
            tvCategory.setPadding(0, dp(8), 0, 0);
            causeCard.addView(tvCategory);

            // what it actually means
            if (crashReason != null) {
                String reasonDisplay = getReasonDisplayText(crashCategory, crashReason, serverRam, serverPort, crashExitCode);
                TextView tvReason = new TextView(this);
                tvReason.setText(reasonDisplay);
                tvReason.setTextColor(0xFFBCBCC6);
                tvReason.setTextSize(14f);
                tvReason.setLineSpacing(dp(2), 1f);
                tvReason.setPadding(0, dp(8), 0, 0);
                causeCard.addView(tvReason);
            }

            // ── fix button ───────────────────────────────────────────────────
            if (crashFixAction != null && crashFix != null) {
                com.google.android.material.button.MaterialButton btnFix = new com.google.android.material.button.MaterialButton(this);
                btnFix.setText(getString(R.string.crash_btn_fix, crashFix));
                btnFix.setTextColor(0xFFFFFFFF);
                btnFix.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3B30));
                btnFix.setCornerRadius(dp(8));
                btnFix.setTypeface(kodaBold != null ? kodaBold : Typeface.DEFAULT_BOLD);
                
                LinearLayout.LayoutParams fixParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                fixParams.setMargins(0, dp(14), 0, 0);
                btnFix.setLayoutParams(fixParams);
                btnFix.setTextSize(12f);
                final String fixActionFinal = crashFixAction;
                btnFix.setOnClickListener(v -> {
                    HapticUtil.forceVibrate(this, 80);
                    if (srv != null) {
                        CrashFixer.FixResult result = CrashFixer.executeFix(this, srv, fixActionFinal);
                        if (result.success) {
                            Toast.makeText(this, getString(R.string.crash_fix_success, result.message), Toast.LENGTH_LONG).show();
                            btnFix.setEnabled(false);
                            btnFix.setText(result.message.toUpperCase());
                            btnFix.setTextColor(0xFF00E676);
                            btnFix.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0A2E15));
                            new Handler(Looper.getMainLooper()).postDelayed(() -> finish(), 1200);
                        } else {
                            Toast.makeText(this, getString(R.string.crash_fix_failed, result.message), Toast.LENGTH_LONG).show();
                        }
                    }
                });
                causeCard.addView(btnFix);
            }

            card.addView(causeCard);
        }

        // ══════════════════════════════════════════════════════════════════════
        //  crash log (collapsible)
        // ══════════════════════════════════════════════════════════════════════
        TextView tvLogLabel = new TextView(this);
        tvLogLabel.setText(getString(R.string.crash_log_label).toUpperCase() + " (TAP TO TOGGLE)");
        tvLogLabel.setTextColor(0xFF8A8A9A);
        tvLogLabel.setTextSize(10f);
        tvLogLabel.setTypeface(kodaFont, Typeface.BOLD);
        tvLogLabel.setLetterSpacing(0.08f);
        tvLogLabel.setPadding(0, dp(20), 0, dp(8));
        card.addView(tvLogLabel);

        ScrollView scrollLog = new ScrollView(this);
        scrollLog.setBackgroundColor(0xFF1C1C22);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setCornerRadius(dp(10));
        logBg.setColor(0xFF1C1C22);
        scrollLog.setBackground(logBg);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.setMargins(0, 0, 0, 0);
        scrollLog.setLayoutParams(scrollParams);

        TextView tvLog = new TextView(this);
        tvLog.setTextColor(0xFFAAAAAA);
        tvLog.setTextSize(9.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setLineSpacing(3f, 1f);
        tvLog.setPadding(dp(10), dp(10), dp(10), dp(10));
        // the stack trace when there is one, otherwise the log file
        if (crashStack != null && !crashStack.isEmpty()) {
            tvLog.setText(crashStack);
        } else {
            tvLog.setText(getLastLogs(srv));
        }
        scrollLog.addView(tvLog);

        // tapping the label opens and closes the log
        tvLogLabel.setOnClickListener(v -> {
            if (scrollLog.getVisibility() == View.VISIBLE) {
                scrollLog.setVisibility(View.GONE);
            } else {
                scrollLog.setVisibility(View.VISIBLE);
                scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
            }
        });
        card.addView(scrollLog);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));

        // ══════════════════════════════════════════════════════════════════════
        //  buttons
        // ══════════════════════════════════════════════════════════════════════
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnBarParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnBarParams.setMargins(0, dp(20), 0, 0);
        btnBar.setLayoutParams(btnBarParams);

        // dismiss
        com.google.android.material.button.MaterialButton btnDismiss = new com.google.android.material.button.MaterialButton(this);
        btnDismiss.setText(getString(R.string.crash_btn_dismiss));
        btnDismiss.setTextColor(0xFFBCBCC6);
        btnDismiss.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        btnDismiss.setCornerRadius(dp(10));
        btnDismiss.setTypeface(kodaFont != null ? kodaFont : Typeface.DEFAULT);
        btnDismiss.setTextSize(13f);
        btnDismiss.setAllCaps(false);
        btnDismiss.setInsetTop(0);
        btnDismiss.setInsetBottom(0);
        LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        dismissParams.setMargins(0, 0, dp(8), 0);
        btnDismiss.setLayoutParams(dismissParams);
        btnDismiss.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            finish();
        });
        btnBar.addView(btnDismiss);
        
        // straight to the whole log file
        com.google.android.material.button.MaterialButton btnFullLogs = new com.google.android.material.button.MaterialButton(this);
        btnFullLogs.setText(getString(R.string.crash_btn_full_logs));
        btnFullLogs.setTextColor(0xFFF0F0F0);
        btnFullLogs.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2E2A26));
        btnFullLogs.setCornerRadius(dp(10));
        btnFullLogs.setTypeface(kodaFont != null ? kodaFont : Typeface.DEFAULT);
        btnFullLogs.setTextSize(13f);
        btnFullLogs.setAllCaps(false);
        btnFullLogs.setInsetTop(0);
        btnFullLogs.setInsetBottom(0);
        LinearLayout.LayoutParams fullLogsParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        fullLogsParams.setMargins(dp(8), 0, 0, 0);
        btnFullLogs.setLayoutParams(fullLogsParams);
        btnFullLogs.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            if (srv == null) return;
            // FileEditorActivity reads "path", not "filePath", and a startup crash often
            // has no logs/latest.log yet, so pick whatever really exists
            File dir = new File(srv.getServerDir());
            File log = new File(dir, "logs/latest.log");
            if (!log.exists() || log.length() == 0) log = new File(dir, "server.log");
            if (!log.exists() || log.length() == 0) {
                File reports = new File(dir, "crash-reports");
                File[] files = reports.listFiles((d, n) -> n.endsWith(".txt"));
                if (files != null && files.length > 0) {
                    log = files[0];
                    for (File f : files) if (f.lastModified() > log.lastModified()) log = f;
                }
            }
            if (!log.exists()) {
                Toast.makeText(this, getString(R.string.crash_log_unavailable), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent logIntent = new Intent(this, eu.kodanetwork.mchost.ui.FileEditorActivity.class);
            logIntent.putExtra("path", log.getAbsolutePath());
            startActivity(logIntent);
        });
        btnBar.addView(btnFullLogs);

        card.addView(btnBar);

        // ask AI: opens the answer as a praetor bottom sheet
        com.google.android.material.button.MaterialButton btnAskAi = new com.google.android.material.button.MaterialButton(this);
        btnAskAi.setText(getString(R.string.ai_ask_button));
        btnAskAi.setTextColor(0xFF1A0F00);
        btnAskAi.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B00));
        btnAskAi.setCornerRadius(dp(10));
        btnAskAi.setTypeface(kodaBold != null ? kodaBold : Typeface.DEFAULT_BOLD);
        btnAskAi.setTextSize(13f);
        btnAskAi.setAllCaps(false);
        btnAskAi.setInsetTop(0);
        btnAskAi.setInsetBottom(0);
        LinearLayout.LayoutParams askAiParams = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        askAiParams.setMargins(0, dp(10), 0, 0);
        btnAskAi.setLayoutParams(askAiParams);
        btnAskAi.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            showAiDialog();
        });
        card.addView(btnAskAi);

        if (getIntent() != null && getIntent().getBooleanExtra("start_ai", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::showAiDialog, 400);
        }

        scroll.addView(root);
        setContentView(scroll);
        // edge to edge: keep the content out of the bars, the warm surface fills them
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        eu.kodanetwork.mchost.util.ThemeHelper.apply(this);

        // the theme treats every text above 26sp as a header and puts an orange glow
        // behind it. the praetor wordmark has its own letter colours and stays flat.
        android.widget.TextView wordmark = findViewById(R.id.tv_praetor_wordmark);
        if (wordmark != null) wordmark.setShadowLayer(0f, 0f, 0f, 0);

        // no background pulse: the calmer sheet design keeps the surface still
        // no auto-dismiss: the AI analysis can take a while; the screen stays until dismissed
    }

    /** turns the crash category key into a localized display name. */
    private String getCategoryDisplayName(String category) {
        if (category == null) return getString(R.string.crash_category_unknown);
        switch (category) {
            case "EULA":           return getString(R.string.crash_category_eula);
            case "OOM":            return getString(R.string.crash_category_oom);
            case "PORT":           return getString(R.string.crash_category_port);
            case "MOD_CRASH":      return getString(R.string.crash_category_mod_crash);
            case "MOD_UNSUPPORTED": return getString(R.string.crash_category_mod_unsupported);
            case "MISSING_JAR":    return getString(R.string.crash_category_missing_jar);
            case "JAVA_VERSION":   return getString(R.string.crash_category_java_version);
            case "PERMISSION":     return getString(R.string.crash_category_permission);
            case "STACK_OVERFLOW": return getString(R.string.crash_category_stack_overflow);
            case "WORLD_CORRUPT":  return getString(R.string.crash_category_world_corrupt);
            case "CONFIG_INVALID": return getString(R.string.crash_category_config_invalid);
            case "OS_KILLED":      return getString(R.string.crash_category_os_killed);
            case "NATIVE_LIB":     return getString(R.string.crash_category_native_lib);
            case "CLASS_NOT_FOUND": return getString(R.string.crash_category_class_not_found);
            case "PLUGIN_CONFIG":  return getString(R.string.crash_category_plugin_config);
            case "STORAGE":        return getString(R.string.crash_category_storage);
            default:               return getString(R.string.crash_category_unknown);
        }
    }

    /** builds the full localized reason text, numbers come from the string resources. */
    private String getReasonDisplayText(String category, String rawReason, int ram, int port, int exitCode) {
        if (category == null) return rawReason != null ? rawReason : "";
        switch (category) {
            case "EULA":           return getString(R.string.crash_reason_eula);
            case "OOM":            return getString(R.string.crash_reason_oom, ram);
            case "PORT":           return getString(R.string.crash_reason_port, port);
            case "MOD_CRASH":      return getString(R.string.crash_reason_mod_crash);
            case "MISSING_JAR":    return getString(R.string.crash_reason_missing_jar);
            case "JAVA_VERSION":   return getString(R.string.crash_reason_java_version);
            case "PERMISSION":     return getString(R.string.crash_reason_permission);
            case "STACK_OVERFLOW": return getString(R.string.crash_reason_stack_overflow);
            case "WORLD_CORRUPT":  return getString(R.string.crash_reason_world_corrupt);
            case "CONFIG_INVALID": return getString(R.string.crash_reason_config_invalid);
            case "OS_KILLED":      return getString(R.string.crash_reason_os_killed);
            case "NATIVE_LIB":     return getString(R.string.crash_reason_native_lib);
            case "CLASS_NOT_FOUND": return getString(R.string.crash_reason_class_not_found);
            case "PLUGIN_CONFIG":  return getString(R.string.crash_reason_plugin_config);
            case "STORAGE":        return getString(R.string.crash_reason_storage);
            case "UNKNOWN":
                if (exitCode != 0) return getString(R.string.crash_reason_unknown, exitCode);
                return getString(R.string.crash_reason_startup_exit);
            default: return rawReason != null ? rawReason : "";
        }
    }


    private com.google.android.material.bottomsheet.BottomSheetDialog aiSheet;
    private TextView tvPraetorReason;
    private com.airbnb.lottie.LottieAnimationView aiDots;
    private com.google.android.material.button.MaterialButton btnPraetorAction;
    private eu.kodanetwork.mchost.util.AiHelper.AiResult aiResult;

    /** the AI answer as a praetor bottom sheet, same language as the other sheets. */
    private void showAiDialog() {
        if (!eu.kodanetwork.mchost.util.AiHelper.hasConsent(this)) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.ai_consent_title))
                    .setMessage(getString(R.string.ai_consent_message))
                    .setPositiveButton(getString(R.string.ai_consent_agree), (d, w) -> {
                        eu.kodanetwork.mchost.util.AiHelper.setConsent(this, true);
                        showAiDialog();
                    })
                    .setNegativeButton(getString(R.string.sd_action_cancel), null)
                    .show();
            return;
        }
        if (aiSheet != null && aiSheet.isShowing()) return;

        aiSheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.KodaBottomSheetDialog);
        final View sheetView = buildAiSheetContent();
        aiSheet.setContentView(sheetView);
        aiSheet.setCancelable(true);
        aiSheet.show();
        if (aiSheet.getWindow() != null) {
            aiSheet.getWindow().setNavigationBarColor(0xFF241C18);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                aiSheet.getWindow().setNavigationBarContrastEnforced(false);
            }
        }
        // the same staggered entrance every other sheet has
        eu.kodanetwork.mchost.util.KodaSheetMotion.reveal(sheetView);
        showAiThinking();
        runAiRequest();
    }

    private View buildAiSheetContent() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_koda_sheet);
        sheet.setPadding(dp(24), dp(12), dp(24), dp(24));

        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0xFF3A3A44);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(18);
        sheet.addView(handle, handleParams);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView wordmark = new TextView(this);
        wordmark.setText(Html.fromHtml(
                "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>",
                Html.FROM_HTML_MODE_LEGACY));
        wordmark.setTextSize(11f);
        wordmark.setTypeface(kodaFont != null ? kodaFont : Typeface.DEFAULT);
        wordmark.setLetterSpacing(0.18f);
        headerRow.addView(wordmark);
        headerRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView triangle = new TextView(this);
        triangle.setText("\u26A0");
        triangle.setTextColor(0xFFFF5449);
        triangle.setTextSize(15f);
        headerRow.addView(triangle);
        sheet.addView(headerRow);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(getString(R.string.ai_ask_button));
        tvTitle.setTextColor(0xFFF5F5F7);
        tvTitle.setTextSize(21f);
        tvTitle.setTypeface(kodaBold != null ? kodaBold : Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(0, dp(10), 0, 0);
        sheet.addView(tvTitle);

        aiDots = new com.airbnb.lottie.LottieAnimationView(this);
        aiDots.setAnimation(R.raw.koda_boot);
        aiDots.loop(true);
        aiDots.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        dotsParams.gravity = Gravity.CENTER_HORIZONTAL;
        dotsParams.topMargin = dp(6);
        sheet.addView(aiDots, dotsParams);

        tvPraetorReason = new TextView(this);
        tvPraetorReason.setTextSize(14f);
        tvPraetorReason.setTextColor(0xFFBCBCC6);
        tvPraetorReason.setLineSpacing(dp(2), 1f);
        // long answers scroll inside the sheet, the action button stays at the bottom
        ScrollView reasonScroll = new ScrollView(this);
        reasonScroll.setFillViewport(false);
        reasonScroll.addView(tvPraetorReason);
        LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reasonParams.topMargin = dp(10);
        sheet.addView(reasonScroll, reasonParams);

        btnPraetorAction = new com.google.android.material.button.MaterialButton(this);
        btnPraetorAction.setTextColor(0xFF1A0F00);
        btnPraetorAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B00));
        btnPraetorAction.setCornerRadius(dp(10));
        btnPraetorAction.setTypeface(kodaFont != null ? kodaFont : Typeface.DEFAULT);
        btnPraetorAction.setTextSize(13f);
        btnPraetorAction.setAllCaps(false);
        btnPraetorAction.setInsetTop(0);
        btnPraetorAction.setInsetBottom(0);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        actionParams.topMargin = dp(18);
        btnPraetorAction.setLayoutParams(actionParams);
        btnPraetorAction.setOnClickListener(v -> {
            if (aiResult != null && aiResult.autoFixAction != null) {
                confirmAutoFix();
            } else if (aiSheet != null) {
                aiSheet.dismiss();
            }
        });
        sheet.addView(btnPraetorAction);

        return sheet;
    }

    private void showAiThinking() {
        if (aiDots != null) {
            aiDots.setVisibility(View.VISIBLE);
            if (!aiDots.isAnimating()) aiDots.playAnimation();
        }
        tvPraetorReason.setText(getString(R.string.ai_thinking));
        tvPraetorReason.setTextColor(0xFF8A8A9A);
        tvPraetorReason.setTextSize(14f);
        btnPraetorAction.setEnabled(false);
        btnPraetorAction.setAlpha(0.5f);
        btnPraetorAction.setText(getString(R.string.ai_thinking));
    }

    private void showAiError(String msg) {
        stopAiDots();
        tvPraetorReason.setText(getString(R.string.ai_error_prefix) + " " + msg);
        tvPraetorReason.setTextColor(0xFFFF8A80);
        tvPraetorReason.setTextSize(14f);
        btnPraetorAction.setEnabled(true);
        btnPraetorAction.setAlpha(1f);
        btnPraetorAction.setText(getString(R.string.ai_retry));
        btnPraetorAction.setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            showAiThinking();
            runAiRequest();
        });
    }

    /** console buffer (app lines + crash) and the log files, so the AI sees everything. */
    private String buildAiLogTail() {
        String base = srv != null ? srv.getId() : "";
        java.util.List<String> buffer = eu.kodanetwork.mchost.service.KodaServerService.getRecentLog(base);
        String crashPart = (crashStack != null && !crashStack.isEmpty()) ? crashStack + "\n" : "";
        return crashPart + eu.kodanetwork.mchost.util.AiHelper.gatherLog(this, srv, buffer);
    }

    private void runAiRequest() {
        new Thread(() -> {
            final String tail = buildAiLogTail();
            try {
                eu.kodanetwork.mchost.util.AiHelper.AiResult res =
                        eu.kodanetwork.mchost.util.AiHelper.askAiSync(this, srv, tail, crashCategory);
                runOnUiThread(() -> showAiResult(res));
            } catch (eu.kodanetwork.mchost.util.AiHelper.RateLimitException e) {
                runOnUiThread(() -> showAiError(getString(R.string.ai_rate_limited, e.used)));
            } catch (Exception e) {
                runOnUiThread(() -> showAiError(e.getMessage() != null ? e.getMessage() : "unknown"));
            }
        }).start();
    }

    private void stopAiDots() {
        if (aiDots != null) {
            aiDots.pauseAnimation();
            aiDots.setVisibility(View.GONE);
        }
    }

    private String esc(String t) {
        return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showAiResult(eu.kodanetwork.mchost.util.AiHelper.AiResult res) {
        aiResult = res;
        stopAiDots();

        String col; String label;
        switch (res.confidence) {
            case "CERTAIN":         col = "#4FC3F7"; label = getString(R.string.confidence_certain); break;
            case "HIGH_CONFIDENCE": col = "#00E676"; label = getString(R.string.confidence_high); break;
            case "CONFIDENT":       col = "#FFCC00"; label = getString(R.string.confidence_confident); break;
            default:                col = "#FF4444"; label = getString(R.string.confidence_not_confident); break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<font color=\"").append(col).append("\"><b>CONFIDENCE: ").append(label).append("</b></font><br>");
        if (res.appRecommendation != null) {
            sb.append("<br><font color=\"#00E676\"><b>APP RECOMMENDATION: ").append(res.appRecommendation)
              .append("</b></font> <font color=\"#8A8A9A\">(authoritative, from the runtime database)</font><br>");
        }
        sb.append("<br>");
        sb.append("<font color=\"#FF6B00\"><b>WHY IT CRASHED</b></font><br>").append(esc(res.cause));
        if (res.fix != null && !res.fix.isEmpty()) {
            sb.append("<br><br><font color=\"#FF6B00\"><b>HOW TO FIX IT</b></font><br>").append(esc(res.fix));
        }
        tvPraetorReason.setText(android.text.Html.fromHtml(sb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY));
        tvPraetorReason.setTextColor(0xFFBCBCC6);
        tvPraetorReason.setTextSize(14f);

        btnPraetorAction.setEnabled(true);
        btnPraetorAction.setAlpha(1f);
        if (res.autoFixAction != null && srv != null) {
            btnPraetorAction.setText(getString(R.string.ai_try_fix));
            btnPraetorAction.setOnClickListener(v -> confirmAutoFix());
        } else if (srv != null && srv.crashFixAction != null) {
            // the analyzer knows a one-tap fix (e.g. re-download the JRE when libjvm is missing)
            btnPraetorAction.setText(getString(R.string.ai_try_fix));
            btnPraetorAction.setOnClickListener(v -> confirmCrashFixerFix());
        } else {
            btnPraetorAction.setText(getString(R.string.crash_btn_dismiss));
            btnPraetorAction.setOnClickListener(v -> { if (aiSheet != null) aiSheet.dismiss(); });
        }
    }


    /** one-tap fix from the analyzer (CrashFixer), used when the AI has no app-setting fix. */
    private void confirmCrashFixerFix() {
        HapticUtil.forceVibrate(this, 50);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ai_try_fix))
                .setMessage(srv.crashFix != null ? srv.crashFix : getString(R.string.ai_try_fix))
                .setPositiveButton(getString(R.string.ai_apply), (d, w) -> {
                    eu.kodanetwork.mchost.util.CrashFixer.FixResult r =
                            eu.kodanetwork.mchost.util.CrashFixer.executeFix(this, srv, srv.crashFixAction);
                    Toast.makeText(this, r.success ? getString(R.string.ai_fix_applied) : getString(R.string.ai_fix_failed),
                            Toast.LENGTH_LONG).show();
                    if (r.success && aiSheet != null) aiSheet.dismiss();
                })
                .setNegativeButton(getString(R.string.sd_action_cancel), null)
                .show();
    }

    private void confirmAutoFix() {
        HapticUtil.forceVibrate(this, 50);
        String msg;
        if ("set_ram".equals(aiResult.autoFixAction)) {
            msg = getString(R.string.ai_fix_confirm_ram, srv.getRamMB(), (int) aiResult.autoFixValue);
        } else {
            msg = getString(R.string.ai_fix_confirm_java, (int) aiResult.autoFixValue);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ai_try_fix))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.ai_apply), (d, w) -> applyAutoFix())
                .setNegativeButton(getString(R.string.sd_action_cancel), null)
                .show();
    }

    private void applyAutoFix() {
        try {
            if ("set_ram".equals(aiResult.autoFixAction)) srv.setRamMB((int) aiResult.autoFixValue);
            else if ("set_java".equals(aiResult.autoFixAction)) srv.setJavaRuntime((int) aiResult.autoFixValue);
            ServerRepo.get(this).update(srv);
            Toast.makeText(this, getString(R.string.ai_fix_applied), Toast.LENGTH_LONG).show();
            HapticUtil.forceVibrate(this, 60);
            if (aiSheet != null) aiSheet.dismiss();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ai_fix_failed), Toast.LENGTH_LONG).show();
        }
    }

    /** reads the last 50 log lines. */    /** reads the last 50 log lines. */
    private String getLastLogs(ServerInstance srv) {
        if (srv == null) return getString(R.string.crash_log_unavailable);
        File serverDir = new File(srv.getServerDir());
        File[] candidates = {
            new File(serverDir, "server.log"),
            new File(serverDir, "logs/latest.log"),
        };
        File logFile = null;
        for (File f : candidates) {
            if (f.exists() && f.length() > 0) { logFile = f; break; }
        }
        if (logFile == null) return getString(R.string.crash_log_unavailable);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            LinkedList<String> lines = new LinkedList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replaceAll("(?:\\\\x1B|\\\\u001B)\\[[;\\\\d]*[a-zA-Z]", "");
                if (line.trim().isEmpty()) continue;
                lines.add(line);
                if (lines.size() > 50) lines.removeFirst();
            }
            for (String l : lines) sb.append(l).append("\n");
        } catch (Exception e) {
            return "Failed to read log: " + e.getMessage();
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? getString(R.string.crash_log_empty) : result;
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (anim != null) anim.cancel();
        super.onDestroy();
    }
}
