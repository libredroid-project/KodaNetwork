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
package eu.kodanetwork.mchost.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.res.ResourcesCompat;

import eu.kodanetwork.mchost.R;

/**
 * the server type chooser as a camera style mode dial.
 *
 * a pre-rendered metal dial (koda_type_dial.png) with the type codes engraved on
 * its top face. dragging up or down turns the dial, it snaps to the next code and
 * reports the two numbers the old NumberPicker listener delivered, so the setup
 * screen only needed a one line swap.
 */
public class KodaTypeDial extends View {

    private static final float TILT_COS = 0.788f;      // cos(38 degrees), matches the render
    private static final float CENTER_Y = 0.427f;      // of the bitmap height
    private static final float RADIUS_X = 0.391f;      // of the bitmap width
    private static final float LABEL_RADIUS = 0.80f;   // of the top face radius
    private static final float DEG_PER_DP = 0.55f;

    /** the top view knob, the one the setup screen shipped with */
    public static final int STYLE_TOP = 0;
    /** the side view drum, a shorter object with one visible end cap */
    public static final int STYLE_SIDE = 1;
    private static final float DRUM_ASPECT = 620f / 740f;
    private static final float DRUM_CENTER_Y = 310f / 740f;
    private static final float DRUM_RADIUS = 181.5f / 740f;
    private static final float DRUM_HALF_LENGTH = 286f / 740f;

    private Bitmap dial;
    private Bitmap dialSide;
    private int style = STYLE_TOP;
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extrudePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path markerPath = new Path();
    private final RectF dialRect = new RectF();

    private String[] labels = new String[0];
    private int minValue = 0;
    private int maxValue = 0;
    private boolean wrapSelector = true;
    private OnValueChanged listener;

    /** the same two numbers the old NumberPicker listener delivered. */
    public interface OnValueChanged {
        void onValueChanged(int oldVal, int newVal);
    }

    private float rotation = 0f;
    private float downY = 0f;
    private float downRotation = 0f;
    private float labelRadiusPx = 0f;
    private float centerX = 0f;
    private float centerY = 0f;
    private float radiusX = 0f;
    private float radiusY = 0f;
    private float drumCy = 0f;
    private float drumR = 0f;

    private VelocityTracker velocityTracker;
    private android.animation.ValueAnimator animator;
    private int lastAnnounced = 0;
    private boolean touchActive = false;

    public KodaTypeDial(Context context) {
        super(context);
        init(context);
    }

    public KodaTypeDial(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public KodaTypeDial(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        dial = BitmapFactory.decodeResource(getResources(), R.drawable.koda_type_dial);
        Typeface font = ResourcesCompat.getFont(context, R.font.font_koda);
        if (font != null) {
            labelPaint.setTypeface(font);
            selectedPaint.setTypeface(font);
        }
        labelPaint.setTextAlign(Paint.Align.CENTER);
        selectedPaint.setTextAlign(Paint.Align.CENTER);
        extrudePaint.setTextAlign(Paint.Align.CENTER);
        highlightPaint.setTextAlign(Paint.Align.CENTER);
        if (font != null) {
            extrudePaint.setTypeface(font);
            highlightPaint.setTypeface(font);
        }
        extrudePaint.setColor(0xFF000000);
        highlightPaint.setColor(0xFFFFFFFF);
        labelPaint.setColor(0xFF8E8E9B);
        selectedPaint.setColor(0xFFF5F5F7);
        labelPaint.setShadowLayer(2f, 0f, 1f, 0x99000000);
        selectedPaint.setShadowLayer(2f, 0f, 1f, 0x99000000);
        markerPaint.setColor(0xFFFF6B00);
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setShadowLayer(6f, 0f, 2f, 0x66000000);
        setFocusable(true);
        setFocusableInTouchMode(false);
    }

    // ------------------------------------------------------------------ api
    // the same surface the NumberPicker calls in the setup screen

    public void setMinValue(int min) {
        minValue = min;
    }

    public void setMaxValue(int max) {
        maxValue = max;
    }

    public void setWrapSelectorWheel(boolean wrap) {
        wrapSelector = wrap;
    }

    public void setDisplayedValues(String[] values) {
        labels = values == null ? new String[0] : values.clone();
        lastAnnounced = selectedIndex();
        updateContentDescription();
        invalidate();
    }

    public void setValue(int value) {
        int n = count();
        if (n <= 0) return;
        int idx = Math.max(0, Math.min(n - 1, value - minValue));
        float step = 360f / n;
        float target = -idx * step;
        if (isLaidOut() && n > 1) {
            while (target - rotation > 180f) target -= 360f;
            while (target - rotation < -180f) target += 360f;
        }
        rotation = target;
        lastAnnounced = idx;
        updateContentDescription();
        invalidate();
    }

    public int getValue() {
        return selectedIndex() + minValue;
    }

    public String getDisplayedValue() {
        String[] l = labels;
        int idx = selectedIndex();
        if (l != null && idx >= 0 && idx < l.length) return l[idx];
        return "";
    }

    public void setOnValueChangedListener(OnValueChanged l) {
        listener = l;
    }

    /** switches between the top view knob and the side view drum. */
    public void setStyle(int style) {
        this.style = style == STYLE_SIDE ? STYLE_SIDE : STYLE_TOP;
        if (this.style == STYLE_SIDE && dialSide == null) {
            dialSide = BitmapFactory.decodeResource(getResources(), R.drawable.koda_type_drum);
        }
        requestLayout();
        invalidate();
    }

    public int getStyle() {
        return style;
    }

    // ------------------------------------------------------------------ measure + draw

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) {
            setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
        } else {
            setMeasuredDimension(width, style == STYLE_SIDE ? (int) (width * DRUM_ASPECT) : width);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w * 0.5f;
        if (style == STYLE_SIDE) {
            drumCy = w * DRUM_CENTER_Y;
            drumR = w * DRUM_RADIUS;
            labelPaint.setTextSize(w * 0.082f);
            selectedPaint.setTextSize(w * 0.082f);
            extrudePaint.setTextSize(w * 0.082f);
            highlightPaint.setTextSize(w * 0.082f);
            labelPaint.setLetterSpacing(0.06f);
            selectedPaint.setLetterSpacing(0.06f);
            extrudePaint.setLetterSpacing(0.06f);
            highlightPaint.setLetterSpacing(0.06f);
            dialRect.set(0f, 0f, w, w * DRUM_ASPECT);
        } else {
            centerY = w * CENTER_Y;
            radiusX = w * RADIUS_X;
            radiusY = radiusX * TILT_COS;
            labelRadiusPx = radiusX * LABEL_RADIUS;
            labelPaint.setTextSize(w * 0.044f);
            selectedPaint.setTextSize(w * 0.044f);
            labelPaint.setLetterSpacing(0f);
            selectedPaint.setLetterSpacing(0f);
            dialRect.set(0f, 0f, w, w);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap active = style == STYLE_SIDE ? dialSide : dial;
        if (active != null) canvas.drawBitmap(active, null, dialRect, null);
        if (labels.length == 0) return;
        if (style == STYLE_SIDE) drawSideLabels(canvas);
        else drawTopLabels(canvas);
    }

    private void drawTopLabels(Canvas canvas) {
        float step = 360f / count();
        float textOffset = labelPaint.getTextSize() * 0.34f;
        int selected = selectedIndex();

        for (int i = 0; i < count(); i++) {
            float theta = i * step + rotation;
            // bring the angle into -180..180 so the label under the marker is 0
            theta = ((theta + 180f) % 360f + 360f) % 360f - 180f;
            float distance = Math.abs(theta) / 180f;
            int alpha = (int) (255f - 95f * distance);
            boolean isSelected = i == selected;
            Paint paint = isSelected ? selectedPaint : labelPaint;
            paint.setAlpha(isSelected ? 255 : alpha);

            canvas.save();
            canvas.translate(centerX, centerY);
            canvas.scale(1f, TILT_COS);
            canvas.rotate(theta);
            canvas.translate(0f, -labelRadiusPx);
            canvas.drawText(code(labels[i]), 0f, textOffset, paint);
            canvas.restore();
        }
        labelPaint.setAlpha(255);
        selectedPaint.setAlpha(255);

        // fixed index marker above the selected code
        float density = getResources().getDisplayMetrics().density;
        float top = centerY - radiusY;
        markerPath.reset();
        markerPath.moveTo(centerX, top + 2f * density);
        markerPath.lineTo(centerX - 5f * density, top - 7f * density);
        markerPath.lineTo(centerX + 5f * density, top - 7f * density);
        markerPath.close();
        canvas.drawPath(markerPath, markerPaint);
    }

    /**
     * draws the full type names around the drum. The surface turns away to the top
     * and bottom, so every label is squeezed by cos(angle); the side of the letters
     * is drawn a few times below the face to give the engraved text its depth.
     */
    private void drawSideLabels(Canvas canvas) {
        float step = 360f / count();
        float density = getResources().getDisplayMetrics().density;
        float textOffset = labelPaint.getTextSize() * 0.34f;
        int selected = selectedIndex();
        float w = getWidth();

        canvas.save();
        canvas.clipRect(w * (0.5f - DRUM_HALF_LENGTH), drumCy - drumR * 0.99f, w * (0.5f + DRUM_HALF_LENGTH), drumCy + drumR * 0.99f);

        for (int i = 0; i < count(); i++) {
            float theta = i * step + rotation;
            theta = ((theta + 180f) % 360f + 360f) % 360f - 180f;
            float radians = (float) Math.toRadians(theta);
            float cos = (float) Math.cos(radians);
            if (cos < 0.12f) continue;
            float sin = (float) Math.sin(radians);
            boolean isSelected = i == selected;
            Paint face = isSelected ? selectedPaint : labelPaint;
            face.setAlpha(isSelected ? 255 : (int) (255f - 95f * Math.min(1f, Math.abs(theta) / 180f)));

            canvas.save();
            canvas.translate(centerX, drumCy + drumR * sin);
            canvas.scale(0.95f, Math.max(0.12f, cos));
            String name = fullName(labels[i]);
            extrudePaint.setAlpha(isSelected ? 150 : 110);
            for (int k = 3; k >= 1; k--) {
                canvas.drawText(name, k * 0.9f * density, k * 1.5f * density + textOffset, extrudePaint);
            }
            canvas.drawText(name, 0f, textOffset, face);
            if (isSelected) {
                highlightPaint.setAlpha(70);
                canvas.drawText(name, -0.4f * density, textOffset - 0.8f * density, highlightPaint);
            }
            canvas.restore();
        }
        labelPaint.setAlpha(255);
        selectedPaint.setAlpha(255);
        canvas.restore();

        // two marks at the edges of the reading window
        float tickH = 15f * density;
        float tickW = 2.5f * density;
        float half = dialRect.width() * DRUM_HALF_LENGTH;
        float top = drumCy - tickH * 0.5f;
        float bottom = drumCy + tickH * 0.5f;
        canvas.drawRoundRect(centerX - half, top, centerX - half + tickW, bottom,
                tickW * 0.5f, tickW * 0.5f, markerPaint);
        canvas.drawRoundRect(centerX + half - tickW, top, centerX + half, bottom,
                tickW * 0.5f, tickW * 0.5f, markerPaint);
    }

    private String fullName(String label) {
        if (label == null) return "";
        int cut = label.indexOf(" (");
        String base = cut > 0 ? label.substring(0, cut) : label;
        return base.toUpperCase();
    }

    private String code(String label) {
        if (label == null) return "";
        int cut = label.indexOf(" (");
        String base = cut > 0 ? label.substring(0, cut) : label;
        return base.length() <= 3 ? base.toUpperCase() : base.substring(0, 3).toUpperCase();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || labels.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null) animator.cancel();
                // a vertical drag on the dial must not scroll the wizard page
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downY = event.getY();
                downRotation = rotation;
                touchActive = true;
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                velocityTracker.clear();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touchActive) return false;
                float density = getResources().getDisplayMetrics().density;
                rotation = downRotation - (downY - event.getY()) / density * DEG_PER_DP;
                announceIfChanged();
                invalidate();
                if (velocityTracker != null) velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!touchActive) return false;
                touchActive = false;
                float velocity = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velocity = velocityTracker.getYVelocity() / getResources().getDisplayMetrics().density * DEG_PER_DP;
                }
                snap(velocity);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void snap(float velocityDegPerSecond) {
        float step = 360f / count();
        float projected = rotation + velocityDegPerSecond * 0.12f;
        float target = Math.round(projected / step) * step;
        if (!wrapSelector && count() > 1) {
            float minRotation = -(count() - 1) * step;
            target = Math.max(minRotation, Math.min(0f, target));
        }
        if (animator != null) animator.cancel();
        animator = android.animation.ValueAnimator.ofFloat(rotation, target);
        long duration = (long) Math.max(120, Math.min(360, Math.abs(target - rotation) * 4f));
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        final float start = rotation;
        final float end = target;
        animator.addUpdateListener(a -> {
            rotation = (float) a.getAnimatedValue();
            announceIfChanged();
            invalidate();
        });
        animator.start();
    }

    private void announceIfChanged() {
        int idx = selectedIndex();
        if (idx == lastAnnounced) return;
        int old = lastAnnounced;
        lastAnnounced = idx;
        updateContentDescription();
        if (listener != null) listener.onValueChanged(old + minValue, idx + minValue);
        if (eu.kodanetwork.mchost.App.getPrefs(getContext()).getBoolean("haptics_enabled", true)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private int count() {
        if (labels.length > 0) return labels.length;
        return Math.max(1, maxValue - minValue + 1);
    }

    private int selectedIndex() {
        int n = count();
        if (n <= 1) return 0;
        float step = 360f / n;
        int idx = Math.round(-rotation / step);
        return ((idx % n) + n) % n;
    }

    private void updateContentDescription() {
        String label = getDisplayedValue();
        setContentDescription(label.isEmpty() ? null : getContext().getString(R.string.server_type_label) + ", " + label);
    }

    // ------------------------------------------------------------------ accessibility + keys

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (count() > 1) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            stepBy(1);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            stepBy(-1);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            stepBy(1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            stepBy(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void stepBy(int delta) {
        int n = count();
        if (n <= 0) return;
        float step = 360f / n;
        float target = rotation - delta * step;
        if (animator != null) animator.cancel();
        animator = android.animation.ValueAnimator.ofFloat(rotation, target);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            rotation = (float) a.getAnimatedValue();
            announceIfChanged();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }
}
