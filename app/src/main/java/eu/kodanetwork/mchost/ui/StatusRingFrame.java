/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * the round status ring around a server icon. while the server is between states an
 * arc rotates quickly; when it settles the arc slows down, grows into the full circle
 * and the colour fades into the new state colour.
 */
public class StatusRingFrame extends FrameLayout {

    private static final float BUSY_SWEEP = 280f;
    private static final float FULL_SWEEP = 360f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float rotation = 0f;
    private float sweep = FULL_SWEEP;
    private int color = 0xFFA1A1AA;
    private boolean busy;

    private ValueAnimator spin;
    private ValueAnimator colorAnim;
    private ValueAnimator settleAnim;

    public StatusRingFrame(Context context) {
        super(context);
        init();
    }

    public StatusRingFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StatusRingFrame(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        paint.setColor(color);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = paint.getStrokeWidth() / 2f;
        oval.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sweep > 0f && oval.width() > 0f) {
            canvas.drawArc(oval, rotation, sweep, false, paint);
        }
    }

    /** colour is always animated, busy starts the fast spin, settled slows into a circle. */
    public void setState(int newColor, boolean isBusy) {
        if (newColor != color) {
            if (colorAnim != null) colorAnim.cancel();
            colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), color, newColor);
            colorAnim.setDuration(480);
            colorAnim.addUpdateListener(a -> {
                color = (Integer) a.getAnimatedValue();
                paint.setColor(color);
                invalidate();
            });
            colorAnim.start();
        }

        if (isBusy) {
            busy = true;
            if (settleAnim != null) settleAnim.cancel();
            if (sweep != BUSY_SWEEP) {
                sweep = BUSY_SWEEP;
                invalidate();
            }
            startSpin();
        } else if (busy) {
            busy = false;
            settle();
        } else {
            sweep = FULL_SWEEP;
            invalidate();
        }
    }

    private void startSpin() {
        if (spin != null && spin.isRunning()) return;
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(1200);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            rotation = (Float) a.getAnimatedValue();
            invalidate();
        });
        spin.start();
    }

    /** slow down over the last turn and grow the arc back into the full circle. */
    private void settle() {
        if (spin != null) spin.cancel();
        if (settleAnim != null) settleAnim.cancel();

        final float startRotation = rotation;
        final float startSweep = sweep;
        settleAnim = ValueAnimator.ofFloat(0f, 1f);
        settleAnim.setDuration(680);
        settleAnim.setInterpolator(new DecelerateInterpolator());
        settleAnim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            rotation = startRotation + 120f * t;
            sweep = startSweep + (FULL_SWEEP - startSweep) * t;
            invalidate();
        });
        settleAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (spin != null) spin.cancel();
        if (colorAnim != null) colorAnim.cancel();
        if (settleAnim != null) settleAnim.cancel();
    }
}
