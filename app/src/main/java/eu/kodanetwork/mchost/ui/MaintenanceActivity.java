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

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import eu.kodanetwork.mchost.R;

public class MaintenanceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_maintenance_m3 : R.layout.activity_maintenance);

        TextView reasonText = findViewById(R.id.maintenanceReasonText);
        TextView durationText = findViewById(R.id.maintenanceDurationText);

        Intent intent = getIntent();
        String reason = intent.getStringExtra("reason");
        int duration = intent.getIntExtra("duration", 60);

        if (reason != null && !reason.isEmpty()) {
            reasonText.setText(reason);
        }
        durationText.setText("Estimated duration: " + duration + " minutes");
    }

    @Override
    public void onBackPressed() {
        // Prevent users from exiting maintenance mode by pressing back
        // moveTaskToBack(true);
    }
}
