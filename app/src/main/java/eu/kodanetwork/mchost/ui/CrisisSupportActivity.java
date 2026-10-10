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
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * support screen for moments when someone is not okay.
 *
 * opens when a server name, a rename or the motd contains phrases like "kms" or "selbstmord".
 * shows helplines, ways to reach people (phone, chat, discord, support ticket) and a breathing
 * exercise. the continue button only unlocks after a short wait so the page cannot be tapped
 * away reflexively, and back stays blocked for the same time, home and recents stay free,
 * nobody is ever trapped inside the app.
 *
 * everything here is local: nothing about this screen is logged, sent or stored.
 */
public class CrisisSupportActivity extends AppCompatActivity {

    public static final int REQ_CRISIS = 9021;
    /** discord invite of the KodaHosting community. */
    private static final String DISCORD_INVITE = "https://discord.gg/e5axQpq3xp";
    /** seconds until the continue button unlocks. */
    private static final long UNLOCK_SECONDS = 40;

    private CountDownTimer unlockTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_crisis_support);

        wireHelpButtons();
        startUnlockCountdown();
    }

    private void wireHelpButtons() {
        // direct call, the number depends on the app language (the resource holds digits only)
        MaterialButton call = findViewById(R.id.btn_crisis_call);
        String callNumber = getString(R.string.crisis_call_number);
        if (callNumber == null || callNumber.trim().isEmpty()) {
            // no local number for this language, the directory button takes its place
            call.setVisibility(View.GONE);
        } else {
            call.setOnClickListener(v -> open("tel:" + callNumber.trim()));
        }

        findViewById(R.id.btn_crisis_krisenchat).setOnClickListener(v -> open("https://krisenchat.de"));
        findViewById(R.id.btn_crisis_findhelp).setOnClickListener(v -> open("https://findahelpline.com"));
        findViewById(R.id.btn_crisis_discord).setOnClickListener(v -> open(DISCORD_INVITE));
        // one conversation at a time, reopens the open personal ticket or creates it quietly
        MaterialButton support = findViewById(R.id.btn_crisis_support);
        support.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            support.setEnabled(false);
            support.setText(getString(R.string.personal_chat_opening));
            eu.kodanetwork.mchost.util.PersonalSupport.open(this, new eu.kodanetwork.mchost.util.PersonalSupport.OpenCallback() {
                @Override
                public void onBusy(boolean busy) {
                    if (!busy) {
                        support.setEnabled(true);
                        support.setText(getString(R.string.crisis_support));
                    }
                }
            });
        });
        findViewById(R.id.btn_crisis_discord).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            open(DISCORD_INVITE);
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    /** counts the continue button free, until then back stays blocked too. */
    private void startUnlockCountdown() {
        final MaterialButton cont = findViewById(R.id.btn_crisis_continue);
        final TextView countdown = findViewById(R.id.crisis_countdown);
        cont.setEnabled(false);
        cont.setAlpha(0.4f);
        countdown.setText(getString(R.string.crisis_countdown, UNLOCK_SECONDS));

        unlockTimer = new CountDownTimer(UNLOCK_SECONDS * 1000, 1000) {
            @Override
            public void onTick(long untilFinished) {
                countdown.setText(getString(R.string.crisis_countdown, untilFinished / 1000));
            }

            @Override
            public void onFinish() {
                countdown.setVisibility(View.GONE);
                cont.setEnabled(true);
                cont.setAlpha(1f);
                cont.setOnClickListener(v -> {
                    eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(CrisisSupportActivity.this, 40);
                    setResult(RESULT_OK);
                    finish();
                });
            }
        }.start();
    }

    @Override
    public void onBackPressed() {
        // deliberately empty while the countdown runs, the page should not be dismissed
        // reflexively. home and recents stay available, nobody is trapped in the app.
    }

    @Override
    protected void onDestroy() {
        if (unlockTimer != null) unlockTimer.cancel();
        super.onDestroy();
    }
}
