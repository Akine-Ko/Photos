package com.example.photos.ui.common;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Applies evenly distributed spacing around grid items so two-column cards stay balanced.
 */
public class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {

    private final int spanCount;
    private final int spacing;
    private final boolean includeEdge;

    public GridSpacingItemDecoration(int spanCount, int spacing, boolean includeEdge) {
        this.spanCount = Math.max(1, spanCount);
        this.spacing = Math.max(0, spacing);
        this.includeEdge = includeEdge;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) {
            outRect.set(0, 0, 0, 0);
            return;
        }

        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.GridLayoutManager) {
            androidx.recyclerview.widget.GridLayoutManager glm = (androidx.recyclerview.widget.GridLayoutManager) lm;
            int spanCnt = glm.getSpanCount();
            androidx.recyclerview.widget.GridLayoutManager.LayoutParams params =
                    (androidx.recyclerview.widget.GridLayoutManager.LayoutParams) view.getLayoutParams();
            int spanSize = params.getSpanSize();
            int column = params.getSpanIndex();
            int groupIndex = glm.getSpanSizeLookup().getSpanGroupIndex(params.getViewLayoutPosition(), spanCnt);

            if (spanSize == spanCnt) { // full-span header/overview
                if (includeEdge) {
                    outRect.left = spacing;
                    outRect.right = spacing;
                    outRect.top = spacing;
                    outRect.bottom = spacing;
                } else {
                    outRect.set(0, spacing, 0, spacing);
                }
                return;
            }

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCnt;
                outRect.right = (column + 1) * spacing / spanCnt;
                // 平铺模式下每行统一添加顶部间距，避免首行漏加导致错位
                outRect.top = spacing;
                outRect.bottom = spacing;
            } else {
                outRect.left = column * spacing / spanCnt;
                outRect.right = spacing - (column + 1) * spacing / spanCnt;
                if (groupIndex > 0) {
                    outRect.top = spacing;
                }
            }
        } else {
            int column = position % spanCount;
            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount;
                outRect.right = (column + 1) * spacing / spanCount;
                if (position < spanCount) {
                    outRect.top = spacing;
                }
                outRect.bottom = spacing;
            } else {
                outRect.left = column * spacing / spanCount;
                outRect.right = spacing - (column + 1) * spacing / spanCount;
                if (position >= spanCount) {
                    outRect.top = spacing;
                }
            }
        }
    }
}
