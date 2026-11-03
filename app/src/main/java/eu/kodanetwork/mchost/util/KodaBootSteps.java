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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * the boot story on the loading screen. real phases only, one line at a time: the
 * lines scroll up like a small ticker and the new one gets a short glossy sweep.
 * nothing is simulated, the lines come from the server log.
 */
public final class KodaBootSteps {

    private static final int MAX_ROWS = 3;

    private final LinearLayout container;
    private final float density;
    private final int rowHeight;
    private final ArrayDeque<TextView> rows = new ArrayDeque<>();
    private String lastLabel;

    public KodaBootSteps(LinearLayout container) {
        this.container = container;
        this.density = container.getResources().getDisplayMetrics().density;
        this.rowHeight = (int) (26 * density);
        container.setClipChildren(true);
        container.setClipToPadding(true);
    }

    /** drops everything, used when the loading screen opens again */
    public void reset() {
        container.removeAllViews();
        rows.clear();
        lastLabel = null;
    }

    public void push(String label) {
        if (label == null || label.isEmpty() || label.equals(lastLabel)) return;
        lastLabel = label;

        final TextView tv = new TextView(container.getContext());
        tv.setText("\u203A  " + label);
        tv.setTextSize(11f);
        tv.setTextColor(0xFF8A8A9A);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setAlpha(0f);
        tv.setTranslationY(rowHeight);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        container.addView(tv, lp);
        rows.addLast(tv);

        // every existing line moves one row up and dims a little, the new one slides
        // in from the bottom. fixed height + clip makes it read as scrolling away.
        for (TextView row : rows) {
            if (row == tv) {
                row.animate().alpha(1f).translationY(0f)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
            } else {
                row.animate().translationYBy(-rowHeight).alpha(0.4f)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
            }
        }

        if (rows.size() > MAX_ROWS) {
            final TextView old = rows.removeFirst();
            container.postDelayed(() -> container.removeView(old), 320);
        }

        gloss(tv);
    }

    /** a soft white sweep across the new line, the glossy bit */
    private void gloss(final TextView tv) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(680);
        anim.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            float w = tv.getWidth() > 0 ? tv.getWidth() : 240 * density;
            float x = -w + p * (w * 2f);
            LinearGradient gradient = new LinearGradient(
                    x, 0f, x + w * 0.45f, 0f,
                    new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP);
            tv.getPaint().setShader(gradient);
            tv.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                tv.getPaint().setShader(null);
                tv.invalidate();
            }
        });
        anim.start();
    }
}
