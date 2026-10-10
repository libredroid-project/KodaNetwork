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

        // no payment step, the app is free, so setup carries straight on
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        btnBack.setOnClickListener(v -> finish());
    }
}
