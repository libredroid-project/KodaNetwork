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
package eu.kodanetwork.mchost.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * photo viewer that behaves like the one everybody knows from whatsapp:
 * pinch to zoom, double tap to zoom in and out, drag to pan while zoomed and a pull
 * down or up to close while not zoomed. the container hears about the pull so it can
 * fade out with the finger.
 */
public class ZoomablePhotoView extends androidx.appcompat.widget.AppCompatImageView {

    public interface Listener {
        /** pull offset in pixels plus 0..1 progress while dragging to close. */
        void onPull(float offsetY, float progress);
        /** finger up and far enough: close me. */
        void onPullDismiss();
        /** finger up but not far enough: snap back. */
        void onPullCancel();
    }

    private final android.view.ScaleGestureDetector scaleDetector;
    private final android.view.GestureDetector gestureDetector;
    private float baseScale = 1f;
    private float downX, downY, lastX, lastY;
    private boolean pulling;
    private Listener listener;

    public ZoomablePhotoView(Context context) {
        this(context, null);
    }

    public ZoomablePhotoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.FIT_CENTER);
        setClickable(true);
        scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        baseScale = Math.max(1f, Math.min(6f, baseScale * detector.getScaleFactor()));
                        applyScale();
                        return true;
                    }
                });
        gestureDetector = new android.view.GestureDetector(context,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        baseScale = baseScale > 1.1f ? 1f : 2.5f;
                        applyScale();
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void applyScale() {
        setScaleX(baseScale);
        setScaleY(baseScale);
        if (baseScale <= 1.05f) {
            setTranslationX(0f);
            setTranslationY(0f);
        } else {
            clampPan();
        }
    }

    /** resets the zoom and the position, used when a pull-up did not close the viewer. */
    public void resetView() {
        baseScale = 1f;
        applyScale();
        setTranslationX(0f);
        setTranslationY(0f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                pulling = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (baseScale <= 1.05f) {
                    float offsetY = event.getY() - downY;
                    float offsetX = event.getX() - downX;
                    if (pulling || Math.abs(offsetY) > Math.abs(offsetX)) {
                        pulling = true;
                        setTranslationY(offsetY);
                        if (listener != null) {
                            float progress = Math.min(1f, Math.abs(offsetY) / (getHeight() * 0.35f));
                            listener.onPull(offsetY, progress);
                        }
                    }
                } else {
                    setTranslationX(getTranslationX() + (event.getX() - lastX));
                    setTranslationY(getTranslationY() + (event.getY() - lastY));
                    clampPan();
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling && listener != null) {
                    float threshold = getHeight() * 0.16f;
                    if (Math.abs(getTranslationY()) > threshold) {
                        listener.onPullDismiss();
                    } else {
                        listener.onPullCancel();
                    }
                }
                pulling = false;
                return true;
            default:
                return true;
        }
    }

    /** keeps the zoomed image inside its own bounds. */
    private void clampPan() {
        float maxX = Math.max(0f, (getWidth() * (baseScale - 1f)) / 2f);
        float maxY = Math.max(0f, (getHeight() * (baseScale - 1f)) / 2f);
        setTranslationX(Math.max(-maxX, Math.min(maxX, getTranslationX())));
        setTranslationY(Math.max(-maxY, Math.min(maxY, getTranslationY())));
    }
}
