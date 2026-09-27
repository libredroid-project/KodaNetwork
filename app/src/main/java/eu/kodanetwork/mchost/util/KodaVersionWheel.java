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
 * The version scroller from the server setup, as a reusable component.
 *
 * A snap wheel with a drag handle, a title, the centred version in Koda orange, a description
 * line below and a confirm button. The setup wizard and the version switch on an existing server
 * both use this class, so both look and feel identical.
 */
public final class KodaVersionWheel {

    /** Called with the version the user confirmed. */
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
        final int wheelHeight = (int) (208 * density);

        final BottomSheetDialog sheet = new BottomSheetDialog(activity, R.style.KodaBottomSheetDialog);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, (int) (48 * density));

        // Drag handle on the rounded top of the sheet
        View handle = new View(activity);
        android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
        handleBg.setColor(0xFF3A3A48);
        handleBg.setCornerRadius(2 * density);
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                (int) (32 * density), (int) (4 * density));
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = (int) (14 * density);
        container.addView(handle, handleLp);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title != null ? title : activity.getString(R.string.version_picker_title));
        tvTitle.setTextColor(0xFFFF6B00);
        tvTitle.setTextSize(13);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setLetterSpacing(0.12f);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = (int) (18 * density);
        container.addView(tvTitle, titleLp);

        // Wheel: snapping list with edge fades
        FrameLayout wheelFrame = new FrameLayout(activity);
        wheelFrame.setClipChildren(false);
        LinearLayout.LayoutParams wfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, wheelHeight);
        wfLp.topMargin = (int) (16 * density);
        container.addView(wheelFrame, wfLp);

        final RecyclerView recyclerView = new RecyclerView(activity);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(activity);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setClipChildren(false);
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

        int initialIdx = 0;
        if (initialVersion != null) {
            int idx = versions.indexOf(initialVersion);
            if (idx >= 0) initialIdx = idx;
        }
        final int startIdx = initialIdx;

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

        final TextView tvDesc = new TextView(activity);
        tvDesc.setTextColor(0x99F0F0F0);
        tvDesc.setTextSize(12);
        tvDesc.setGravity(android.view.Gravity.CENTER);
        tvDesc.setText(describe(descriptions, versions.get(initialIdx), activity));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) (4 * density);
        container.addView(tvDesc, descLp);

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

        sheet.setContentView(container);

        MaterialButton btnConfirm = KodaButtons.primary(activity,
                activity.getString(R.string.version_picker_confirm));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * density));
        btnLp.setMargins((int) (48 * density), (int) (20 * density), (int) (48 * density), (int) (24 * density));
        btnConfirm.setOnClickListener(v -> {
            String picked = versions.get(currentPos[0]);
            sheet.dismiss();
            if (callback != null) callback.picked(picked);
        });
        container.addView(btnConfirm, btnLp);

        sheet.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        SheetFix.apply(sheet);
        sheet.show();
        HapticUtil.applyHapticsToView(container, activity);
    }

    private static String describe(Map<String, String> descriptions, String version, Activity activity) {
        if (descriptions != null && descriptions.containsKey(version)) return descriptions.get(version);
        return activity.getString(R.string.version_desc_generic);
    }

    /** Scales and fades the items by their distance to the centre; the centred one is orange. */
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

    /** Position of the item closest to the centre, or -1. */
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

    /** Row of the wheel: one centred version per item. */
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
