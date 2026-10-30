/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
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
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

/**
 * the legal notice a device in mainland China gets. It uses the same P.R.A.E.T.O.R.
 * ban screen the rest of the app uses, because this is not a normal error page.
 * mainactivity starts this and finishes right away, so none of the normal startup
 * work runs.
 */
public class RegionBlockActivity extends AppCompatActivity {

    private boolean simulation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        if (!eu.kodanetwork.mchost.util.RegionGuard.isBlocked(this)) {
            finish();
            return;
        }
        long until = eu.kodanetwork.mchost.App.getPrefs(this).getLong("dev_region_simulate_until", 0L);
        simulation = until > System.currentTimeMillis();
        if (simulation) {
            long left = Math.max(1000L, Math.min(60000L, until - System.currentTimeMillis()));
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, left);
        }
        setContentView(R.layout.activity_banned);

        TextView mark = findViewById(R.id.bannedWordmark);
        if (mark != null) {
            mark.setText(android.text.Html.fromHtml(
                    "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>",
                    android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        TextView subtitle = findViewById(R.id.bannedSubtitleText);
        if (subtitle != null) {
            subtitle.setText(R.string.region_blocked_title);
        }

        TextView reason = findViewById(R.id.bannedReasonText);
        if (reason != null) {
            reason.setText(R.string.region_blocked_body);
        }

        // no hwid on a region notice, it is not a device ban
        View hwid = findViewById(R.id.bannedHwidText);
        if (hwid != null) hwid.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        // the real notice stays until the app is closed, the dev simulation can be left
        if (simulation) finish();
    }
}
