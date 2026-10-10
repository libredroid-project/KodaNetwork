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
import android.graphics.drawable.GradientDrawable;

/** buttons built in code, 12dp rounded like every other button in the app. */
public class KodaButtons {

    public static com.google.android.material.button.MaterialButton make(Context c, String text, int bgColor, int textColor) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setStateListAnimator(null);
        b.setLetterSpacing(0.04f);
        b.setTextSize(13);
        b.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, eu.kodanetwork.mchost.R.font.font_koda),
                android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * c.getResources().getDisplayMetrics().density);
        bg.setColor(bgColor);
        b.setBackground(bg);
        return b;
    }

    /** the orange primary button with black text, same shape as the DANGER ZONE ones. */
    public static com.google.android.material.button.MaterialButton primary(Context c, String text) {
        return make(c, text, 0xFFFF6B00, 0xFF000000);
    }

    /** dark card button, white text. */
    public static com.google.android.material.button.MaterialButton dark(Context c, String text) {
        return make(c, text, 0xFF241C18, 0xFFF0F0F0);
    }
}
