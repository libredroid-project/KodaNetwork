package eu.kodanetwork.mchost.ui.design;

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
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

public class CheckoutPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_design_checkout);

        RadioGroup rgBilling = findViewById(R.id.rg_billing);
        RadioButton rbMonthly = findViewById(R.id.rb_monthly);
        TextView tvPrice = findViewById(R.id.tv_price);
        TextView tvSub = findViewById(R.id.tv_price_sub);
        Button btnStripe = findViewById(R.id.btn_continue_stripe);
        Button btnBack = findViewById(R.id.btn_back_home);

        rgBilling.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_lifetime) {
                tvPrice.setText("149 EUR");
                tvSub.setText("one-time");
            } else {
                tvPrice.setText("9.99 EUR");
                tvSub.setText("monthly");
            }
        });
        rbMonthly.setChecked(true);

        btnStripe.setOnClickListener(v -> startActivity(new Intent(this, CompleteSetupPageActivity.class)));
        btnBack.setOnClickListener(v -> finish());
    }
}
