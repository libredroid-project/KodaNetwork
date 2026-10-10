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
package eu.kodanetwork.mchost.util;

import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * landscape fix for the tall custom dialogs (Praetor / ToS / join address):
 * the content is wrapped in a ScrollView so nothing clips, and in landscape
 * the dialog moves to the LEFT side (62% width) instead of overflowing the
 * short screen height in the middle.
 */
public class DialogLandFix {

    public static void apply(Dialog d) {
        if (d == null || d.getWindow() == null) return;
        try {
            ViewGroup content = (ViewGroup) d.findViewById(android.R.id.content);
            if (content == null || content.getChildCount() == 0) return;

            // tall dialogs need to scroll or they clip, hence the ScrollView
            View child = content.getChildAt(0);
            if (!(child instanceof ScrollView)) {
                android.view.ViewGroup.LayoutParams oldLp = child.getLayoutParams();
                content.removeView(child);
                ScrollView scroller = new ScrollView(d.getContext());
                scroller.setFillViewport(true);
                scroller.addView(child, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                content.addView(scroller, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            boolean land = d.getContext().getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if (land) {
                int w = (int)(d.getContext().getResources().getDisplayMetrics().widthPixels * 0.62f);
                d.getWindow().setLayout(w, ViewGroup.LayoutParams.MATCH_PARENT);
                d.getWindow().setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
        } catch (Exception ignored) {}
    }
}
