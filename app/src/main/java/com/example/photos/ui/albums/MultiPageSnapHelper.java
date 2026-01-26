package com.example.photos.ui.albums;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A PagerSnapHelper that supports multi-page fling only when enabled (e.g. filmstrip mode).
 *
 * iOS-ish feel:
 * - Low velocity => mostly 1 page
 * - Higher velocity => gradually more pages (eased curve)
 * - Hard cap on max pages per fling
 */
public class MultiPageSnapHelper extends PagerSnapHelper {

    public interface EnabledProvider {
        boolean isEnabled(); // e.g. filmstripMode
    }

    // ---- Tuning knobs (adjust these to taste) ----

    // One fling can jump at most this many pages.
    private static final int MAX_PAGES_PER_FLING = 8;

    // Below this velocity: always 1 page.
    private static final float V_START_PX_PER_SEC = 2200f;

    // Above this velocity: always MAX pages.
    private static final float V_MAX_PX_PER_SEC = 20000f;

    // Curve power: bigger => more iOS-ish (gentler at low speed, ramps later).
    private static final float VELOCITY_CURVE_POWER = 2.2f;

    // Optional: allow distance estimation to add a small bonus (anti-device variance).
    // Set to 0 to disable distance bonus completely.
    private static final int DISTANCE_BONUS_CAP = 2;

    private final EnabledProvider enabledProvider;

    private OrientationHelper horizontalHelper;
    private OrientationHelper verticalHelper;

    public MultiPageSnapHelper(@NonNull EnabledProvider enabledProvider) {
        this.enabledProvider = enabledProvider;
    }

    @Override
    public int findTargetSnapPosition(@NonNull RecyclerView.LayoutManager layoutManager,
                                      int velocityX, int velocityY) {

        // Not enabled => keep default PagerSnapHelper behavior (1 page).
        if (!enabledProvider.isEnabled()) {
            return super.findTargetSnapPosition(layoutManager, velocityX, velocityY);
        }

        if (!(layoutManager instanceof LinearLayoutManager)) {
            return super.findTargetSnapPosition(layoutManager, velocityX, velocityY);
        }

        final View currentView = findSnapView(layoutManager);
        if (currentView == null) return RecyclerView.NO_POSITION;

        final int currentPosition = layoutManager.getPosition(currentView);
        if (currentPosition == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;

        final int itemCount = layoutManager.getItemCount();
        if (itemCount <= 0) return RecyclerView.NO_POSITION;

        final boolean horizontal = layoutManager.canScrollHorizontally();
        final int v = horizontal ? velocityX : velocityY;

        if (v == 0) return currentPosition;

        final int direction = v > 0 ? 1 : -1;
        final int absV = Math.abs(v);

        int pages = pagesFromVelocity(absV);

        // ---- Optional: small distance-based bonus (capped) ----
        if (DISTANCE_BONUS_CAP > 0) {
            final OrientationHelper helper = horizontal ? getHorizontalHelper(layoutManager)
                    : getVerticalHelper(layoutManager);

            final int[] distances = calculateScrollDistance(velocityX, velocityY);
            final int distancePx = Math.abs(horizontal ? distances[0] : distances[1]);

            final float distancePerChild = computeDistancePerChild(layoutManager, helper);
            if (distancePerChild > 0f && distancePx > 0) {
                final int estimatedPages = Math.max(1, Math.round(distancePx / distancePerChild));
                final int bonus = clampInt(estimatedPages - pages, 0, DISTANCE_BONUS_CAP);
                pages = clampInt(pages + bonus, 1, MAX_PAGES_PER_FLING);
            }
        }

        int targetPos = currentPosition + direction * pages;
        targetPos = clampInt(targetPos, 0, itemCount - 1);
        return targetPos;
    }

    /**
     * Map velocity (px/s-ish) to number of pages using an eased curve.
     */
    private int pagesFromVelocity(int absVelocityPxPerSec) {
        if (absVelocityPxPerSec <= V_START_PX_PER_SEC) return 1;
        if (absVelocityPxPerSec >= V_MAX_PX_PER_SEC) return MAX_PAGES_PER_FLING;

        float t = (absVelocityPxPerSec - V_START_PX_PER_SEC) / (V_MAX_PX_PER_SEC - V_START_PX_PER_SEC);
        t = clampFloat(t, 0f, 1f);

        float eased = (float) Math.pow(t, VELOCITY_CURVE_POWER);

        int pages = 1 + Math.round(eased * (MAX_PAGES_PER_FLING - 1));
        return clampInt(pages, 1, MAX_PAGES_PER_FLING);
    }

    /**
     * Estimate average distance (px) per page based on visible children span.
     * This is more stable than using a single view width when decorations vary.
     */
    private float computeDistancePerChild(@NonNull RecyclerView.LayoutManager layoutManager,
                                          @NonNull OrientationHelper helper) {

        View minPosView = null;
        View maxPosView = null;
        int minPos = Integer.MAX_VALUE;
        int maxPos = Integer.MIN_VALUE;

        final int childCount = layoutManager.getChildCount();
        if (childCount == 0) return 0f;

        for (int i = 0; i < childCount; i++) {
            View child = layoutManager.getChildAt(i);
            if (child == null) continue;

            int pos = layoutManager.getPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;

            if (pos < minPos) {
                minPos = pos;
                minPosView = child;
            }
            if (pos > maxPos) {
                maxPos = pos;
                maxPosView = child;
            }
        }

        if (minPosView == null || maxPosView == null) return 0f;

        final int start = helper.getDecoratedStart(minPosView);
        final int end = helper.getDecoratedEnd(maxPosView);
        final int distance = end - start;

        final int positions = (maxPos - minPos) + 1;
        if (positions <= 0 || distance == 0) return 0f;

        return (float) distance / positions;
    }

    private OrientationHelper getHorizontalHelper(@NonNull RecyclerView.LayoutManager layoutManager) {
        if (horizontalHelper == null || horizontalHelper.getLayoutManager() != layoutManager) {
            horizontalHelper = OrientationHelper.createHorizontalHelper(layoutManager);
        }
        return horizontalHelper;
    }

    private OrientationHelper getVerticalHelper(@NonNull RecyclerView.LayoutManager layoutManager) {
        if (verticalHelper == null || verticalHelper.getLayoutManager() != layoutManager) {
            verticalHelper = OrientationHelper.createVerticalHelper(layoutManager);
        }
        return verticalHelper;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
