package eu.kodanetwork.mchost.ui.design;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

public class CompleteSetupPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_design_complete_setup);

        TextView tvLicense = findViewById(R.id.tv_license_key);
        Button btnFinish = findViewById(R.id.btn_finish_setup);
        Button btnLogin = findViewById(R.id.btn_go_login);

        String demoLicense = "KODA-" + System.currentTimeMillis();
        tvLicense.setText(demoLicense);

        btnFinish.setOnClickListener(v -> startActivity(new Intent(this, DashboardPageActivity.class)));
        btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginPageActivity.class)));
    }
}
