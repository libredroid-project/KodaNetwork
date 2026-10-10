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
package eu.kodanetwork.mchost.ui.design;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.ui.MainActivity;

public class DashboardPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_design_dashboard);

        Button btnDownload = findViewById(R.id.btn_download_external);
        Button btnAdmin = findViewById(R.id.btn_admin_dashboard);
        Button btnServerPanel = findViewById(R.id.btn_open_server_panel);

        btnDownload.setOnClickListener(v -> {
            // hook for the supabase signed artifact download, still empty
        });
        btnAdmin.setOnClickListener(v -> startActivity(new Intent(this, AdminPageActivity.class)));
        btnServerPanel.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
    }
}
