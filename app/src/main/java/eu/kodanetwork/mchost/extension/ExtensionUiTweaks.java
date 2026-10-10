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
package eu.kodanetwork.mchost.extension;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * the UI tweaks extensions may ask for: hiding app elements by view id and
 * adding console quick commands. applied centrally on every activity resume,
 * so no single screen has to know extensions exist.
 */
public final class ExtensionUiTweaks {

    private ExtensionUiTweaks() {}

    /** console quick commands that all enabled extensions bring along. */
    public static List<String> consoleChips(Context c) {
        try {
            return ExtensionRepository.get(c).consoleChips();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /** hides the view ids extensions contributed, in the running activity. */
    public static void applyToActivity(Activity activity) {
        try {
            if (activity == null) return;
            // never touch our own screens, that is where extensions get configured
            if (activity instanceof ExtensionsActivity || activity instanceof ExtensionHostActivity) return;
            Set<String> hidden = ExtensionRepository.get(activity).hiddenViewIds();
            if (hidden.isEmpty()) return;
            hideIn(activity.getWindow().getDecorView(), hidden);
        } catch (Throwable ignored) {
        }
    }

    private static void hideIn(View root, Set<String> ids) {
        if (root == null) return;
        Deque<View> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v == null) continue;
            if (v.getId() != View.NO_ID) {
                try {
                    String name = v.getResources().getResourceEntryName(v.getId());
                    if (ids.contains(name) && v.getVisibility() != View.GONE) {
                        v.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {}
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            }
        }
    }
}
