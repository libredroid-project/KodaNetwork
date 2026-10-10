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

/**
 * shows a world being generated the way minecraft's own loading screen suggests it: a
 * field of chunks that fills up from the spawn in the middle outwards.
 *
 * the cells are symbolic, one cell per chunk slot, colored like terrain from a hash so
 * the same world always looks the same. the frontier keeps a short glow so the eye has
 * something to follow, and the phases differ: waiting breathes, setting up sweeps a
 * scan line, generating fills, done glows once.
 */
public class WorldGenView extends View {

    public enum Phase { WAITING, SETUP, GENERATING, DONE }

    private static final int GRID = 27;              // cells per side, keep it odd for a centre
    private static final int[] TERRAIN = {           // muted koda palette, no flat minecraft green
            0xFF4E5D2A, 0xFF5A6B31, 0xFF3F5023, 0xFF6B7A38,
            0xFF7A6B3A, 0xFF8A7B4A, 0xFF3A5A6B, 0xFF456B7A,
            0xFF5F5F52, 0xFF6B6B5C,
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long seed = (long) (Math.random() * 1_000_000);

    /** the real world from the plugin: one character per cell, '0' is an empty chunk. */
    private String realCells = null;

    private Phase phase = Phase.WAITING;
    private float progress = 0f;        // 0..1, filled up as chunks come in
    private float anim = 0f;            // 0..1 loop for the moving parts
    private float glow = 0f;            // extra shine right after a chunk lands
    private boolean running = true;

    public WorldGenView(Context context) {
        super(context);
    }

    public WorldGenView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPhase(Phase p) {
        phase = p;
        invalidate();
    }

    /**
     * the grid KodaTransfer sampled out of the running world. the same nine families the
     * plugin writes are painted here, so the picture is the actual world.
     */
    public void setRealMap(String cells) {
        if (cells == null || cells.isEmpty()) return;
        realCells = cells;
        invalidate();
    }

    /** the colors for the plugin's palette, index 0 is "chunk does not exist". */
    private static final int[] BIOME_COLORS = {
            0xFF15151A,   // 0 ungenerated
            0xFF34566B,   // 1 ocean, river
            0xFF6B7A38,   // 2 plains, meadow
            0xFF415526,   // 3 forest, taiga
            0xFF8A7B4A,   // 4 desert, savanna, badlands
            0xFFB9C4C9,   // 5 snow, ice
            0xFF6E6B62,   // 6 stone, mountains
            0xFF3F5A45,   // 7 swamp, jungle
            0xFF7A5B3A,   // 8 other
            0xFF5A5460,   // 9 unmapped
    };

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        glow = 1f;                      // a fresh chunk just arrived, let the frontier shine
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        post(step);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(step);
        super.onDetachedFromWindow();
    }

    /** drives the moving parts, roughly 30 frames per second is plenty for this. */
    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            anim += 0.02f;
            if (anim > 1f) anim -= 1f;
            glow = Math.max(0f, glow - 0.06f);
            invalidate();
            postDelayed(this, 33);
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // before anything runs there is no world yet: rings open up from the middle,
        // the same way the generator would start on its own
        if (phase == Phase.WAITING) {
            drawOpeningRings(canvas, w, h);
            return;
        }

        // a real map from the running world beats any drawn one
        if (realCells != null && phase != Phase.WAITING) {
            drawRealMap(canvas, w, h);
            return;
        }

        float cell = Math.min(w, h) / (float) GRID;
        float offsetX = (w - cell * GRID) / 2f;
        float offsetY = (h - cell * GRID) / 2f;
        int centre = GRID / 2;

        // how many slots have to be filled for the current progress, filled by distance
        // from the middle so it grows like a real radius around the spawn
        float maxDist = (float) Math.sqrt(2) * centre;
        float limit = progress * maxDist;
        boolean setup = phase == Phase.SETUP || phase == Phase.WAITING || phase == Phase.DONE;
        float scanX = -0.2f + anim * 1.4f;      // the sweep for the "getting ready" phases

        float brea = phase == Phase.WAITING ? 0.55f + 0.45f * (float) Math.sin(anim * Math.PI * 2) : 1f;

        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                float dx = gx - centre, dy = gy - centre;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                boolean filled = phase == Phase.DONE || dist <= limit;
                int color;

                if (filled) {
                    long hash = seed + gx * 73856093L + gy * 19349663L;
                    color = TERRAIN[(int) Math.floorMod(hash, TERRAIN.length)];
                    // the frontier ring shines a bit, that is where the work happens
                    if (!setup && Math.abs(dist - limit) < 0.9f) {
                        color = blend(color, 0xFFFFFFFF, 0.25f + 0.35f * glow);
                    }
                } else {
                    color = 0xFF101014;            // ungenerated chunk
                    if (phase == Phase.SETUP) {
                        // a scan line walks over the empty world while chunky installs
                        float cx = (gx + 0.5f) / GRID;
                        float d = Math.abs(cx - scanX);
                        if (d < 0.06f) color = blend(color, 0xFF6B7A38, (0.06f - d) / 0.06f * 0.5f);
                    }
                }

                if (phase == Phase.WAITING) color = blend(color, 0xFF000000, 1f - brea);

                paint.setColor(color);
                canvas.drawRect(offsetX + gx * cell, offsetY + gy * cell,
                        offsetX + (gx + 1) * cell - 1f, offsetY + (gy + 1) * cell - 1f, paint);
            }
        }

        // when everything is done the whole field takes a slow breath
        if (phase == Phase.DONE) {
            paint.setColor(blend(0x00000000, 0xFFFF6B00, 0.06f + 0.05f * (float) Math.sin(anim * Math.PI * 2)));
            canvas.drawRect(offsetX, offsetY, offsetX + cell * GRID, offsetY + cell * GRID, paint);
        }
    }

    /** one cell per sampled chunk, straight from the plugin. */
    private void drawRealMap(Canvas canvas, int w, int h) {
        int side = (int) Math.sqrt(realCells.length());
        if (side <= 0) return;
        float cell = Math.min(w, h) / (float) side;
        float offsetX = (w - cell * side) / 2f;
        float offsetY = (h - cell * side) / 2f;

        for (int i = 0; i < realCells.length(); i++) {
            int c = realCells.charAt(i) - '0';
            int color = (c >= 0 && c < BIOME_COLORS.length) ? BIOME_COLORS[c] : BIOME_COLORS[9];
            paint.setColor(color);
            int cx = i % side, cy = i / side;
            canvas.drawRect(offsetX + cx * cell, offsetY + cy * cell,
                    offsetX + (cx + 1) * cell - 0.5f, offsetY + (cy + 1) * cell - 0.5f, paint);
        }

        if (phase == Phase.DONE) {
            paint.setColor(blend(0x00000000, 0xFFFF6B00, 0.08f + 0.05f * (float) Math.sin(anim * Math.PI * 2)));
            canvas.drawRect(offsetX, offsetY, offsetX + cell * side, offsetY + cell * side, paint);
        }
    }

    /** three rings that grow and fade, one after the other, so it never looks stuck. */
    private void drawOpeningRings(Canvas canvas, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float max = Math.min(w, h) * 0.46f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, max * 0.02f));
        for (int i = 0; i < 3; i++) {
            float t = (anim + i / 3f) % 1f;
            float radius = max * 0.18f + t * max * 0.9f;
            int alpha = (int) (170 * (1f - t));
            paint.setColor(Color.argb(Math.max(0, alpha), 0xFF, 0x6B, 0x00));
            canvas.drawCircle(cx, cy, radius, paint);
        }
        // a filled dot in the middle, the spawn that everything grows around
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(220, 0xE8, 0xE2, 0xD6));
        canvas.drawCircle(cx, cy, Math.max(3f, max * 0.03f), paint);
    }

    private int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}
