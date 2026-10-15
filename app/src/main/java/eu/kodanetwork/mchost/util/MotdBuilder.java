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

import android.app.Activity;
import android.widget.EditText;

/**
 * the motd colour builder: the codes sit side by side, tapping one inserts it at the
 * cursor, and the preview under the field renders the result with real minecraft colours.
 * shared by the server settings and the new server wizard so both build motds the same way.
 */
public class MotdBuilder {

    private MotdBuilder() {}

    /** wires a codes row and a preview to the given motd field. */
    public static void attach(final Activity activity, final EditText etMotd, int codesRowId, int previewId) {
        if (activity == null || etMotd == null) return;
        final android.widget.LinearLayout codeRow = activity.findViewById(codesRowId);
        final android.widget.TextView preview = activity.findViewById(previewId);
        if (codeRow == null) return;
        float d = activity.getResources().getDisplayMetrics().density;

        codeRow.removeAllViews();
        // every code the game understands: 16 colours + all five formats + reset
        String[] codes = {"&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9",
                "&a", "&b", "&c", "&d", "&e", "&f", "&l", "&o", "&n", "&m", "&k", "&r"};
        for (final String code : codes) {
            android.widget.TextView item = new android.widget.TextView(activity);
            item.setText(code);
            item.setTextSize(13);
            item.setPadding((int) (7 * d), (int) (4 * d), (int) (7 * d), (int) (4 * d));
            item.setTypeface(android.graphics.Typeface.MONOSPACE);
            char c = code.charAt(1);
            int color = MinecraftColors.colorFor(c);
            if (color != -1) {
                item.setTextColor(color);
            } else {
                item.setTextColor(0xFFE8E2D6);
                if (c == 'm') item.setPaintFlags(item.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                if (c == 'n') item.setPaintFlags(item.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                if (c == 'l' || c == 'k') item.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                if (c == 'o') item.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.ITALIC);
            }
            item.setClickable(true);
            item.setOnClickListener(v -> {
                int at = Math.max(0, etMotd.getSelectionStart());
                etMotd.getText().insert(at, code);
            });
            codeRow.addView(item);
        }

        // hex colour: inserts the template and puts the cursor on the digits
        android.widget.TextView hexItem = new android.widget.TextView(activity);
        hexItem.setText("&#…");
        hexItem.setTextSize(13);
        hexItem.setPadding((int) (7 * d), (int) (4 * d), (int) (7 * d), (int) (4 * d));
        hexItem.setTypeface(android.graphics.Typeface.MONOSPACE);
        hexItem.setTextColor(0xFFFF7EB9);
        hexItem.setClickable(true);
        hexItem.setOnClickListener(v -> {
            int at = Math.max(0, etMotd.getSelectionStart());
            etMotd.getText().insert(at, "&#RRGGBB");
            // select the placeholder so typing replaces it right away
            etMotd.setSelection(at + 2, at + 8);
        });
        codeRow.addView(hexItem);

        final Runnable refresh = () -> {
            if (preview != null) {
                preview.setText(MinecraftColors.toSpannable(etMotd.getText().toString()));
            }
        };
        etMotd.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) { refresh.run(); }
        });
        refresh.run();
    }
}
