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
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import eu.kodanetwork.mchost.R;

public class BannedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_banned);

        TextView mark = findViewById(R.id.bannedWordmark);
        if (mark != null) {
            mark.setText(android.text.Html.fromHtml(
                    "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font><font color=\"#FFFFFF\">O.R.</font>",
                    android.text.Html.FROM_HTML_MODE_LEGACY));
        }

        TextView hwidText = findViewById(R.id.bannedHwidText);
        String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
        hwidText.setText("HWID: " + uuid);
        
        TextView reasonText = findViewById(R.id.bannedReasonText);
        String reason = getIntent().getStringExtra("reason");
        if (reason == null) {
            reason = eu.kodanetwork.mchost.App.getPrefs(this).getString("BANNED_REASON", null);
        }
        if (reason != null && !reason.isEmpty()) {
            reasonText.setText(reason);
        }
    }

    @Override
    public void onBackPressed() {
        // a ban is meant to stick, so back leads nowhere
    }
}
