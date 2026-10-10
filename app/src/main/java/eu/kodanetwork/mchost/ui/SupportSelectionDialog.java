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

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;

import eu.kodanetwork.mchost.R;

public class SupportSelectionDialog extends Dialog {

    private SupportDialogListener listener;

    public interface SupportDialogListener {
        void onReportBugClicked();
        void onReportServerClicked();
        void onMyTicketsClicked();
    }

    public SupportSelectionDialog(Context context, SupportDialogListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_support_selection);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                (int)(getContext().getResources().getDisplayMetrics().widthPixels * 0.90),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        LinearLayout llLoginWarning = findViewById(R.id.ll_login_warning);
        LinearLayout llButtons = findViewById(R.id.ll_buttons);

        boolean isLoggedIn = eu.kodanetwork.mchost.network.supabase.SupabaseAuth.isLoggedIn(getContext());

        if (!isLoggedIn) {
            llLoginWarning.setVisibility(View.VISIBLE);
            llButtons.setVisibility(View.GONE);
        } else {
            llLoginWarning.setVisibility(View.GONE);
            llButtons.setVisibility(View.VISIBLE);
        }

        // "mental support <3" only exists while a personal conversation is open, new ones are
        // started from the crisis screen, this entry point continues the existing chat
        android.view.View mentalButton = findViewById(R.id.btn_mental_support);
        mentalButton.setVisibility(View.GONE);
        if (isLoggedIn) {
            eu.kodanetwork.mchost.util.PersonalSupport.hasOpenTicket(getContext(), has -> {
                if (has && isShowing()) {
                    mentalButton.setVisibility(View.VISIBLE);
                }
            });
        }
        mentalButton.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(getContext(), 30);
            dismiss();
            eu.kodanetwork.mchost.util.PersonalSupport.open(getContext(), null);
        });

        findViewById(R.id.btn_report_bug).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onReportBugClicked();
        });

        findViewById(R.id.btn_report_server).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onReportServerClicked();
        });

        findViewById(R.id.btn_my_tickets).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onMyTicketsClicked();
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
    }
}
