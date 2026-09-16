package eu.kodanetwork.mchost.util;

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

import android.app.Dialog;
import android.view.Window;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Tablet-only bottom sheet helper: on wide screens (>= 900dp) it caps the sheet
 * width at 640dp and centers it so it does not stretch across the whole display.
 * On phones and for the sheet HEIGHT it changes NOTHING — sheets keep their
 * natural half-height/collapse behavior.
 */
public final class SheetFix {

    private SheetFix() {}

    public static void apply(Dialog dialog) {
        if (!(dialog instanceof BottomSheetDialog)) return;
        Window w = dialog.getWindow();
        if (w == null) return;

        android.util.DisplayMetrics dm = w.getContext().getResources().getDisplayMetrics();
        float density = dm.density;
        if (dm.widthPixels / density < 900) return; // phones: completely untouched

        int maxWidth = (int) (640 * density);
        w.setLayout(Math.min(maxWidth, dm.widthPixels), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(android.view.Gravity.CENTER);
    }
}
