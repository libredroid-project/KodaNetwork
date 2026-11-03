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

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import eu.kodanetwork.mchost.R;

/**
 * the small "that happened, take it back" bar. used after a stop or a rename, where
 * the action is easy to reverse and nobody should have to redo it by hand.
 */
public final class KodaUndo {

    private KodaUndo() {}

    public static void show(View anyViewInHierarchy, CharSequence message, Runnable undo) {
        if (anyViewInHierarchy == null) return;
        Snackbar bar = Snackbar.make(anyViewInHierarchy, message, 6000);
        bar.setBackgroundTint(0xFF241C18);
        bar.setTextColor(0xFFF0F0F0);
        bar.setActionTextColor(0xFFFF6B00);
        bar.setAction(anyViewInHierarchy.getContext().getString(R.string.undo_action), v -> {
            HapticUtil.forceVibrate(anyViewInHierarchy.getContext(), 40);
            undo.run();
        });
        bar.show();
    }
}
