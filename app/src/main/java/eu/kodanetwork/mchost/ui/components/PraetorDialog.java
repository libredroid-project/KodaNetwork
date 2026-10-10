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
package eu.kodanetwork.mchost.ui.components;

import android.app.Activity;
import android.app.Dialog;
import android.text.Html;
import android.widget.TextView;
import eu.kodanetwork.mchost.R;

public class PraetorDialog {

    public static void showApology(Activity activity, String title, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
            dialog.setContentView(R.layout.dialog_praetor_account);
            dialog.setCancelable(true);

            TextView tvTitle = dialog.findViewById(R.id.tv_dialog_title);
            if (tvTitle != null) {
                String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A</font><font color=\"#555555\">.</font><font color=\"#AAAAAA\">E</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">T</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">O</font><font color=\"#555555\">.</font><font color=\"#FFFFFF\">R.</font>";
                tvTitle.setText(Html.fromHtml(praetorHtml, Html.FROM_HTML_MODE_LEGACY));
            }

            TextView tvAccEmail = dialog.findViewById(R.id.tv_account_email);
            if (tvAccEmail != null) tvAccEmail.setText(message);

            android.view.View btnChangePass = dialog.findViewById(R.id.btn_dialog_change_password);
            if (btnChangePass != null) {
                btnChangePass.setVisibility(android.view.View.GONE);
            }

            android.view.View btnClose = dialog.findViewById(R.id.btn_dialog_cancel);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }

            dialog.show();
        });
    }
}
