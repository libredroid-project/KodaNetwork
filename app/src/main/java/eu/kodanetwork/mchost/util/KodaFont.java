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

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import eu.kodanetwork.mchost.R;

/**
 * puts the normal Koda font back on a screen after ThemeHelper replaced it with
 * monospace, without dropping bold/italic styles.
 */
public final class KodaFont {

    private KodaFont() {
    }

    public static void apply(Context context, View view) {
        if (view == null) return;
        android.graphics.Typeface koda = ResourcesCompat.getFont(context, R.font.font_koda);
        if (koda == null) return;
        walk(view, koda);
    }

    private static void walk(View view, android.graphics.Typeface koda) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            int style = tv.getTypeface() == null
                    ? android.graphics.Typeface.NORMAL : tv.getTypeface().getStyle();
            tv.setTypeface(koda, style);
            // keep the delayed theme pass from painting it back to monospace
            tv.setTag(R.id.tag_themed, "STYLED");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), koda);
            }
        }
    }
}
