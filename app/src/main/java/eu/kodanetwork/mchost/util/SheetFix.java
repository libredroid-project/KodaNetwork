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
import android.view.Window;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * bottom sheet helper for tablets: on wide screens (>= 900dp) the sheet width is
 * capped at 640dp and centred, so it does not stretch over the whole display.
 * on phones, and for the sheet HEIGHT, this changes NOTHING, sheets keep their
 * natural half height and collapse behaviour.
 */
public final class SheetFix {

    private SheetFix() {}

    public static void apply(Dialog dialog) {
        if (!(dialog instanceof BottomSheetDialog)) return;
        Window w = dialog.getWindow();
        if (w == null) return;

        android.util.DisplayMetrics dm = w.getContext().getResources().getDisplayMetrics();
        float density = dm.density;
        if (dm.widthPixels / density < 900) return; // phones stay completely untouched

        int maxWidth = (int) (640 * density);
        w.setLayout(Math.min(maxWidth, dm.widthPixels), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(android.view.Gravity.CENTER);
    }
}
