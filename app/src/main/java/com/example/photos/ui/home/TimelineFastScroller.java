package com.example.photos.ui.home;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.photos.R;

/**
 * 主页时间线快速滚动条：支持手势拖拽并实时显示目标日期。
 */
public class TimelineFastScroller extends FrameLayout {

    public interface DateLabelProvider {
        int getItemCount();

        @Nullable
        String getLabelForPosition(int position);
    }

    private View trackView;
    private View thumbView;
    private View bubbleView;
    private TextView bubbleTextView;
    private RecyclerView recyclerView;
    private DateLabelProvider labelProvider;
    private boolean dragging = false;
    private static final long HIDE_DELAY_MS = 800L;

    private final Runnable hideBubbleRunnable = this::hideBubble;
    private final Runnable hideChromeRunnable = this::hideChrome;
    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
            if (!dragging) {
                syncThumbToRecycler();
                if (dy != 0) {
                    showChrome();
                    scheduleHideChrome();
                }
            }
        }
    };

    public TimelineFastScroller(@NonNull Context context) {
        super(context);
        init();
    }

    public TimelineFastScroller(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimelineFastScroller(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_timeline_fast_scroller, this, true);
        setClipToPadding(false);
        setClickable(true);
        trackView = findViewById(R.id.fastScrollTrack);
        thumbView = findViewById(R.id.fastScrollThumb);
        bubbleView = findViewById(R.id.fastScrollBubble);
        bubbleTextView = findViewById(R.id.fastScrollBubbleText);
        hideChromeInstant();
    }

    public void attachRecyclerView(@NonNull RecyclerView recyclerView, @Nullable DateLabelProvider provider) {
        detachRecyclerView();
        this.recyclerView = recyclerView;
        this.labelProvider = provider;
        recyclerView.addOnScrollListener(scrollListener);
        updateVisibility();
        post(this::syncThumbToRecycler);
    }

    public void detachRecyclerView() {
        if (recyclerView != null) {
            recyclerView.removeOnScrollListener(scrollListener);
        }
        recyclerView = null;
        labelProvider = null;
        setVisibility(GONE);
    }

    public void updateLabelProvider(@Nullable DateLabelProvider provider) {
        this.labelProvider = provider;
        updateVisibility();
        post(this::syncThumbToRecycler);
    }

    public void requestSync() {
        post(this::syncThumbToRecycler);
    }

    public void updateVisibility() {
        boolean show = labelProvider != null
                && recyclerView != null
                && labelProvider.getItemCount() > 0;
        setVisibility(show ? VISIBLE : GONE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (recyclerView == null || labelProvider == null || labelProvider.getItemCount() == 0) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                showChrome();
                showBubble();
                handleTouch(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    handleTouch(event.getY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    scheduleHideBubble();
                    scheduleHideChrome();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void handleTouch(float y) {
        float proportion = convertYToProportion(y);
        updateThumbAndBubble(proportion);
        scrollToProportion(proportion);
        updateBubbleLabel(proportion);
    }

    private void scrollToProportion(float proportion) {
        if (recyclerView == null || labelProvider == null) return;
        if (recyclerView.getAdapter() == null) return;
        int itemCount = labelProvider.getItemCount();
        if (itemCount <= 0) return;

        int targetPosition = Math.round(proportion * (itemCount - 1));
        targetPosition = Math.max(0, Math.min(targetPosition, itemCount - 1));

        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).scrollToPositionWithOffset(targetPosition, 0);
        } else {
            recyclerView.scrollToPosition(targetPosition);
        }
    }

    private void syncThumbToRecycler() {
        if (recyclerView == null || !isShown()) return;
        int range = recyclerView.computeVerticalScrollRange();
        int extent = recyclerView.computeVerticalScrollExtent();
        int offset = recyclerView.computeVerticalScrollOffset();
        int scrollableRange = range - extent;
        float proportion = scrollableRange <= 0 ? 0f : (float) offset / (float) scrollableRange;
        proportion = clamp(proportion, 0f, 1f);
        updateThumbAndBubble(proportion);
        updateBubbleLabel(proportion);
    }

    private void updateThumbAndBubble(float proportion) {
        if (thumbView == null) return;
        float availableHeight = getAvailableScrollHeight();
        float maxThumbY = getHeight() - getPaddingBottom() - thumbView.getHeight();
        float thumbY = getPaddingTop() + (availableHeight * proportion);
        thumbY = clamp(thumbY, getPaddingTop(), maxThumbY);
        thumbView.setY(thumbY);

        if (bubbleView != null) {
            float bubbleOffset = thumbView.getHeight() - bubbleView.getHeight();
            float bubbleY = thumbY + (bubbleOffset / 2f);
            float maxBubbleY = getHeight() - getPaddingBottom() - bubbleView.getHeight();
            bubbleY = clamp(bubbleY, getPaddingTop(), maxBubbleY);
            bubbleView.setY(bubbleY);
        }
    }

    private float getAvailableScrollHeight() {
        if (thumbView == null) return 0f;
        float height = getHeight() - getPaddingTop() - getPaddingBottom() - thumbView.getHeight();
        return Math.max(0f, height);
    }

    private float convertYToProportion(float touchY) {
        float availableHeight = getAvailableScrollHeight();
        float clampedY = clamp(touchY - getPaddingTop() - (thumbView.getHeight() / 2f), 0f, availableHeight);
        if (availableHeight == 0) return 0f;
        return clampedY / availableHeight;
    }

    private void updateBubbleLabel(float proportion) {
        if (bubbleTextView == null || labelProvider == null) return;
        int count = labelProvider.getItemCount();
        if (count <= 0) return;
        int target = Math.round(proportion * (count - 1));
        target = Math.max(0, Math.min(target, count - 1));
        String label = labelProvider.getLabelForPosition(target);
        if (TextUtils.isEmpty(label)) {
            bubbleTextView.setText("");
        } else {
            bubbleTextView.setText(label);
        }
    }

    private void showBubble() {
        if (bubbleView == null) return;
        bubbleView.removeCallbacks(hideBubbleRunnable);
        bubbleView.setVisibility(VISIBLE);
        bubbleView.animate().alpha(1f).setDuration(120).start();
        showChrome();
    }

    private void hideBubble() {
        if (bubbleView == null) return;
        bubbleView.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!dragging) {
                bubbleView.setVisibility(GONE);
            }
        }).start();
    }

    private void scheduleHideBubble() {
        if (bubbleView == null) return;
        bubbleView.removeCallbacks(hideBubbleRunnable);
        bubbleView.postDelayed(hideBubbleRunnable, 600);
    }

    private void showChrome() {
        if (thumbView != null) {
            thumbView.setVisibility(VISIBLE);
            thumbView.setAlpha(1f);
        }
        if (trackView != null) {
            trackView.setVisibility(VISIBLE);
            trackView.setAlpha(1f);
        }
        removeCallbacks(hideChromeRunnable);
    }

    private void hideChrome() {
        if (thumbView != null) {
            thumbView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                if (!dragging) thumbView.setVisibility(INVISIBLE);
            }).start();
        }
        if (trackView != null) {
            trackView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                if (!dragging) trackView.setVisibility(INVISIBLE);
            }).start();
        }
    }

    private void hideChromeInstant() {
        if (thumbView != null) {
            thumbView.setAlpha(0f);
            thumbView.setVisibility(INVISIBLE);
        }
        if (trackView != null) {
            trackView.setAlpha(0f);
            trackView.setVisibility(INVISIBLE);
        }
    }

    private void scheduleHideChrome() {
        removeCallbacks(hideChromeRunnable);
        postDelayed(hideChromeRunnable, HIDE_DELAY_MS);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
