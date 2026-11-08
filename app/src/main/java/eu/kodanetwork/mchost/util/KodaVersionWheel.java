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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Map;

import eu.kodanetwork.mchost.R;

/**
 * the version scroller from the server setup, as a reusable component.
 *
 * a snap wheel inside the normal P.R.A.E.T.O.R. sheet chrome: handle, wordmark, title, the
 * centred version in koda orange, a description line and a confirm button. the setup wizard
 * and the version switch on an existing server both use this, so both look and feel the same.
 */
public final class KodaVersionWheel {

    /** gets the version the user confirmed. */
    public interface OnVersionPicked {
        void picked(String version);
    }

    private KodaVersionWheel() {
    }

    public static void show(final Activity activity, final List<String> versions,
                            final Map<String, String> descriptions, final String initialVersion,
                            final String title, final OnVersionPicked callback) {
        if (activity == null || versions == null || versions.isEmpty()) return;

        final float density = activity.getResources().getDisplayMetrics().density;
        final int itemHeight = (int) (56 * density);
        // must match the fixed height of vw_wheel_frame in sheet_version_wheel.xml
        final int wheelHeight = (int) (208 * density);

        final BottomSheetDialog sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);
        View content = activity.getLayoutInflater().inflate(R.layout.sheet_version_wheel, null);
        final FrameLayout wheelFrame = content.findViewById(R.id.vw_wheel_frame);

        // wordmark in the shared colours, the title stays plain
        TextView wordmark = content.findViewById(R.id.tv_vw_wordmark);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font>"
                + "<font color=\"#FFFFFF\">O.R.</font>";
        wordmark.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView tvTitle = content.findViewById(R.id.tv_vw_title);
        tvTitle.setText(title != null ? title : activity.getString(R.string.version_picker_title));

        int initialIdx = 0;
        if (initialVersion != null) {
            int idx = versions.indexOf(initialVersion);
            if (idx >= 0) initialIdx = idx;
        }
        final int startIdx = initialIdx;

        // the wheel itself: a snapping list with fades at both edges
        final RecyclerView recyclerView = new RecyclerView(activity);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(activity);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setItemAnimator(null);
        recyclerView.setClipChildren(false);
        recyclerView.setClipToPadding(false);
        new LinearSnapHelper().attachToRecyclerView(recyclerView);

        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent,
                                       RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos == RecyclerView.NO_POSITION) return;
                int spacer = (wheelHeight - itemHeight) / 2;
                if (pos == 0) outRect.top = spacer;
                if (pos == versions.size() - 1) outRect.bottom = spacer;
            }
        });

        WheelAdapter adapter = new WheelAdapter(versions, itemHeight, recyclerView::smoothScrollToPosition);
        recyclerView.setAdapter(adapter);
        layoutManager.scrollToPositionWithOffset(initialIdx, (wheelHeight - itemHeight) / 2);
        wheelFrame.addView(recyclerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View fadeTop = new View(activity);
        fadeTop.setBackgroundResource(R.drawable.picker_fade_top);
        wheelFrame.addView(fadeTop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) (32 * density), android.view.Gravity.TOP));

        View fadeBottom = new View(activity);
        fadeBottom.setBackgroundResource(R.drawable.picker_fade_bottom);
        wheelFrame.addView(fadeBottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) (32 * density), android.view.Gravity.BOTTOM));

        final TextView tvDesc = content.findViewById(R.id.tv_vw_desc);
        tvDesc.setText(describe(descriptions, versions.get(initialIdx), activity));

        final int[] currentPos = {initialIdx};
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                applyWheelTransform(rv, itemHeight);
            }

            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = centeredPosition(rv);
                    if (pos >= 0 && pos != currentPos[0]) {
                        currentPos[0] = pos;
                        HapticUtil.forceVibrate(activity, 60);
                        tvDesc.setText(describe(descriptions, versions.get(pos), activity));
                    }
                }
            }
        });
        recyclerView.post(() -> {
            View target = layoutManager.findViewByPosition(startIdx);
            if (target != null) {
                float delta = target.getY() + target.getHeight() / 2f - recyclerView.getHeight() / 2f;
                recyclerView.scrollBy(0, Math.round(delta));
            }
            applyWheelTransform(recyclerView, itemHeight);
        });

        sheet.setContentView(content);

        LinearLayout root = (LinearLayout) content;
        MaterialButton btnConfirm = KodaButtons.primary(activity,
                activity.getString(R.string.version_picker_confirm));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * density));
        int side = (int) (24 * density);
        btnLp.setMargins(side, (int) (20 * density), side, 0);
        btnConfirm.setOnClickListener(v -> {
            String picked = versions.get(currentPos[0]);
            sheet.dismiss();
            if (callback != null) callback.picked(picked);
        });
        root.addView(btnConfirm, btnLp);

        sheet.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        SheetFix.apply(sheet);
        sheet.show();
        HapticUtil.applyHapticsToView(content, activity);
        KodaSheetMotion.reveal(content);
    }

    private static String describe(Map<String, String> descriptions, String version, Activity activity) {
        if (descriptions != null && descriptions.containsKey(version)) return descriptions.get(version);
        return activity.getString(R.string.version_desc_generic);
    }

    /** items scale and fade with their distance to the centre, the middle one is orange. */
    private static void applyWheelTransform(RecyclerView rv, int itemHeight) {
        int center = rv.getHeight() / 2;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tv = (TextView) child;
            float childCenter = child.getY() + child.getHeight() / 2f;
            float dist = Math.abs(center - childCenter);
            float t = Math.min(1f, dist / (rv.getHeight() * 0.6f));
            tv.setScaleX(1.25f - 0.35f * t);
            tv.setScaleY(1.25f - 0.35f * t);
            tv.setAlpha(1f - 0.72f * t);
            tv.setTextColor(dist < itemHeight * 0.5f ? 0xFFFF6B00 : 0xFFF0F0F0);
        }
    }

    /** position of the item closest to the centre, -1 when there is none. */
    private static int centeredPosition(RecyclerView rv) {
        int center = rv.getHeight() / 2;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            float dist = Math.abs(child.getY() + child.getHeight() / 2f - center);
            if (dist < bestDist) {
                bestDist = dist;
                best = rv.getChildAdapterPosition(child);
            }
        }
        return best;
    }

    /** one row of the wheel, one version per row. */
    private static class WheelAdapter extends RecyclerView.Adapter<WheelAdapter.VH> {
        interface OnItemClickListener {
            void onClick(int position);
        }

        private final List<String> versions;
        private final int itemHeightPx;
        private final OnItemClickListener clickListener;

        WheelAdapter(List<String> versions, int itemHeightPx, OnItemClickListener clickListener) {
            this.versions = versions;
            this.itemHeightPx = itemHeightPx;
            this.clickListener = clickListener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFF0F0F0);
            tv.setTextSize(22);
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                    parent.getContext(), R.font.font_koda));
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            holder.tv.setText(versions.get(position));
            holder.tv.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(position);
            });
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tv;

            VH(TextView tv) {
                super(tv);
                this.tv = tv;
            }
        }
    }
}
