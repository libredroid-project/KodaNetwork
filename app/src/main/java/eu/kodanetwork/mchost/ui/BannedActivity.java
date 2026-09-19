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

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import eu.kodanetwork.mchost.R;

public class BannedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_banned_m3 : R.layout.activity_banned);

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
        // Prevent users from exiting banned screen
    }
}
