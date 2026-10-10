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
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;

public class TerminalThemeHelper {

    public static boolean isTerminalEnabled(Context ctx) {
        return App.getPrefs(ctx).getBoolean("dev_terminal_enabled", false);
    }

    public static void applyThemeToView(Context ctx, View root) {
        if (!isTerminalEnabled(ctx)) return;

        // a bottom sheet or dialog root usually carries a white or gray background.
        // the root bg is left alone so shapes do not break, could be changed though.
        
        
        // if the root looks like a container, it goes dark
        if (root instanceof ViewGroup) {
            root.setBackgroundColor(Color.parseColor("#111111"));
        }

        applyToChildren(ctx, root);
    }

    private static void applyToChildren(Context ctx, View view) {
        if (view instanceof Button) {
            Button btn = (Button) view;
            String text = btn.getText() != null ? btn.getText().toString().toLowerCase() : "";
            
            int bgRes = R.drawable.bg_mc_button_dark;
            if (text.contains("start") || text.contains("save") || text.contains("apply") || text.contains("add") || text.contains("yes") || text.contains("create") || text.contains("install")) {
                bgRes = R.drawable.bg_mc_button_green;
            } else if (text.contains("stop") || text.contains("kill") || text.contains("delete") || text.contains("remove") || text.contains("no") || text.contains("ban") || text.contains("cancel")) {
                bgRes = R.drawable.bg_mc_button_red;
            } else if (text.contains("restart") || text.contains("edit") || text.contains("update") || text.contains("change")) {
                bgRes = R.drawable.bg_mc_button_orange;
            }
            
            btn.setBackgroundResource(bgRes);
            
            if (btn instanceof MaterialButton) {
                ((MaterialButton) btn).setBackgroundTintList(null);
                ((MaterialButton) btn).setCornerRadius(0);
            }
            
            btn.setTextColor(Color.WHITE);
            // monospace on top, that is the point of a terminal look
            btn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            
        } else if (view instanceof TextView) {
            // the rest of the text gets monospace too
            
            TextView tv = (TextView) view;
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            // specific colours would be nicer to keep, but the current one cannot be read
            // easily, so everything gets #DDDDDD (light gray), visible on a dark bg.
            tv.setTextColor(Color.parseColor("#DDDDDD"));

        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToChildren(ctx, group.getChildAt(i));
            }
        }
    }
}
