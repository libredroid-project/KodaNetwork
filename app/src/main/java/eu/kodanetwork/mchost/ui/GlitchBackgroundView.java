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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

public class GlitchBackgroundView extends View {

    private Paint paint;
    private Random random;
    private int[] colors = {Color.BLACK, Color.RED, Color.parseColor("#330000"), Color.parseColor("#880000"), Color.parseColor("#111111")};
    private boolean isRunning = false;

    public GlitchBackgroundView(Context context) {
        super(context);
        init();
    }

    public GlitchBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        random = new Random();
    }

    public void startGlitch() {
        isRunning = true;
        invalidate();
    }

    public void stopGlitch() {
        isRunning = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        // mostly black, roughly 1 frame in 10 goes red
        canvas.drawColor(random.nextInt(100) < 10 ? Color.RED : Color.BLACK);

        if (isRunning) {
            // glitch bars, 10 to 39 of them
            int numRects = 10 + random.nextInt(30);
            for (int i = 0; i < numRects; i++) {
                paint.setColor(colors[random.nextInt(colors.length)]);
                
                int rectWidth = random.nextInt(width);
                int rectHeight = random.nextInt(height / 10 + 1);
                int x = random.nextInt(width);
                int y = random.nextInt(height);
                
                // every now and then the bar spans the whole width instead
                if (random.nextBoolean()) {
                    x = 0;
                    rectWidth = width;
                }
                
                canvas.drawRect(x, y, x + rectWidth, y + rectHeight, paint);
            }
            
            // white noise dots on top of the bars
            paint.setColor(Color.WHITE);
            int numDots = random.nextInt(200);
            for (int i = 0; i < numDots; i++) {
                canvas.drawPoint(random.nextInt(width), random.nextInt(height), paint);
            }

            // keep redrawing as fast as possible, the delay lands around 30-60 fps
            postInvalidateDelayed(30);
        }
    }
}
