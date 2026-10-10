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
package eu.kodanetwork.mchost.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/**
 * layout wrapper that draws a continuously rotating, glowing gradient border around its
 * children, that is where the premium high-tech look comes from.
 * also supports a one-shot diagonal light sweep (shimmer/glass shine) animation.
 */
public class AnimatedBorderLayout extends LinearLayout {
    private Paint paint;
    private RectF rect;
    private LinearGradient gradient;
    private Matrix matrix;
    private float animationProgress = 0f;
    private ValueAnimator animator;

    // shimmer/sweep variables
    private float shimmerProgress = -1f;
    private Paint shimmerPaint;
    private boolean drawBorder = true;

    public void setDrawBorder(boolean drawBorder) {
        this.drawBorder = drawBorder;
        invalidate();
    }

    public AnimatedBorderLayout(Context context) {
        super(context);
        init();
    }

    public AnimatedBorderLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedBorderLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f); // 1.5dp border
        rect = new RectF();
        matrix = new Matrix();

        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerPaint.setStyle(Paint.Style.FILL);

        // looping animator for the rotating gradient
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(3000); // 3 seconds for a full rotation
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> {
            animationProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    public void triggerShimmerSweep(Runnable onComplete) {
        boolean animsEnabled = eu.kodanetwork.mchost.App.getPrefs(getContext()).getBoolean("animations_enabled", true);
        if (!animsEnabled) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        ValueAnimator shimmerAnimator = ValueAnimator.ofFloat(-0.6f, 1.6f);
        shimmerAnimator.setDuration(600); // 600ms for the sweep
        shimmerAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        shimmerAnimator.addUpdateListener(animation -> {
            shimmerProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shimmerProgress = -1f;
                invalidate();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        shimmerAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float halfStroke = paint.getStrokeWidth() / 2f;
        rect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

        // linear gradient in koda orange tones fading to transparent
        gradient = new LinearGradient(0, 0, w, h,
                new int[]{0xFF6B00, 0xFFFF8C38, 0x11FF6B00, 0xFFCC5500, 0xFF6B00},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(gradient);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        boolean animsEnabled = eu.kodanetwork.mchost.App.getPrefs(getContext()).getBoolean("animations_enabled", true);
        if (animsEnabled && animator != null && !animator.isRunning()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean animsEnabled = eu.kodanetwork.mchost.App.getPrefs(getContext()).getBoolean("animations_enabled", true);
        if (gradient != null) {
            float rotation = animsEnabled ? animationProgress : 0f;
            matrix.setRotate(rotation, rect.centerX(), rect.centerY());
            gradient.setLocalMatrix(matrix);
        }
        float cornerRadius = 12f * getResources().getDisplayMetrics().density; // 12dp corner, matches bg_input
        if (drawBorder) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        
        // shimmer sweep on top of the children
        if (shimmerProgress > -1f) {
            float cornerRadius = 12f * getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float xOffset = w * shimmerProgress;

            // diagonal light sheen
            LinearGradient sheen = new LinearGradient(
                    xOffset - w * 0.3f, 0,
                    xOffset + w * 0.3f, h,
                    new int[]{Color.TRANSPARENT, 0x44FFFFFF, 0xAAFFFFFF, 0x44FFFFFF, Color.TRANSPARENT},
                    new float[]{0f, 0.35f, 0.5f, 0.65f, 1f},
                    Shader.TileMode.CLAMP
            );
            shimmerPaint.setShader(sheen);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shimmerPaint);
        }
    }
}
