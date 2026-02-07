package com.example.photos.ui.albums;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.animation.ValueAnimator;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.graphics.Bitmap;
import android.widget.OverScroller;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.view.ViewCompat;

/**
 * Simple pinch-to-zoom ImageView for the album viewer.
 * Keeps a base fit-center matrix and applies user scale/pan on top.
 */
public class ZoomableImageView extends AppCompatImageView {

    private final Matrix baseMatrix = new Matrix();
    private final Matrix supportMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final RectF contentRect = new RectF();
    private float filmstripEdgeBiasX = 0f;
    private static final float PAN_THRESHOLD = 1.05f; // allow ViewPager when near base scale
    // Enter filmstrip as you shrink; exit when you almost reach full size; commit decides snap-back.
    public static final float FILMSTRIP_ENTER = 0.95f;  // start revealing neighbors (pinch down)
    public static final float FILMSTRIP_EXIT = 0.985f;  // exit filmstrip when zoomed back (must be > enter)

    /**
     * Filmstrip target scale.
     *
     * - This is also the minimum scale while in the viewer.
     * - When you lift your finger, we only "stick" if you reached this scale.
     */
    public static final float FILMSTRIP_COMMIT = 0.87f;
    public static final float FILMSTRIP_STICKY = FILMSTRIP_COMMIT;
    private static final float EDGE_HANDOFF_TOLERANCE = 24f; // px slack before giving pan to pager

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float desiredScale = 1f;
    private boolean hasDesiredScale = false;
    private float minScale = 1f;
    private float maxScale = 6f;
    private float lastX;
    private float lastY;
    private boolean isDragging = false;
    private ValueAnimator resetAnimator;
    private OnTransformListener transformListener;
    private OnScaleChangeListener scaleChangeListener;
    private boolean scalingInProgress = false;
    private final OverScroller scroller;
    private final FlingRunner flingRunner;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (getCurrentScale() <= PAN_THRESHOLD) {
                    return false;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                flingRunner.start((int) velocityX, (int) velocityY);
                return true;
            }
        });
        scroller = new OverScroller(context);
        flingRunner = new FlingRunner();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && getCurrentScale() < 0.999f
                && !scalingInProgress) {
            // When scaled down (filmstrip), let the pager take the gesture immediately for reliable flings.
            return false;
        }
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (resetAnimator != null && resetAnimator.isRunning()) {
                    resetAnimator.cancel();
                }
                lastX = event.getX();
                lastY = event.getY();
                isDragging = true;
                getParent().requestDisallowInterceptTouchEvent(getCurrentScale() > PAN_THRESHOLD);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // Keep receiving events; snap-back is handled when the last finger leaves.
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1 || scalingInProgress) {
                    lastX = event.getX();
                    lastY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true; // let scale detector handle
                }
                if (!isDragging) break;
                if (getCurrentScale() <= PAN_THRESHOLD) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true; // keep receiving events even if pager intercepts next
                }
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                RectF rect = getDisplayRect();
                if (rect != null) {
                    boolean atLeft = rect.left >= -EDGE_HANDOFF_TOLERANCE;
                    boolean atRight = rect.right <= getWidth() + EDGE_HANDOFF_TOLERANCE;
                    if ((atLeft && dx > 0) || (atRight && dx < 0)) {
                        // Hit image edge: allow parent (ViewPager) to take over for page swipe.
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    }
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                translate(dx, dy);
                if (transformListener != null) transformListener.onTransform();
                break;
            case MotionEvent.ACTION_UP:
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                // Only snap when the last finger lifts to avoid mid-gesture bounce.
                if (event.getPointerCount() == 1 && !scalingInProgress) {
                    maybeSnapBack();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        configureBaseMatrix();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        configureBaseMatrix();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        onDrawableChanged();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        onDrawableChanged();
    }

    @Override
    public void setImageURI(@Nullable Uri uri) {
        super.setImageURI(uri);
        onDrawableChanged();
    }

    private void onDrawableChanged() {
        baseMatrix.reset();
        supportMatrix.reset();
        configureBaseMatrix();
    }

    private void configureBaseMatrix() {
        Drawable d = getDrawable();
        if (d == null) return;
        baseMatrix.reset();
        int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0 || viewWidth == 0 || viewHeight == 0) return;
        float scale = Math.min(viewWidth / (float) dw, viewHeight / (float) dh);
        float dx = (viewWidth - dw * scale) * 0.5f;
        float dy = (viewHeight - dh * scale) * 0.5f;
        baseMatrix.postScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);
        // In album viewer we allow shrinking into "filmstrip" mode.
        // Keep the minimum at FILMSTRIP_COMMIT so the user can pinch down to ~0.87.
        minScale = FILMSTRIP_COMMIT;
        checkBounds(); // keep existing user matrix sane under new size
        applyMatrix();
        applyDesiredScaleIfNeeded();
        notifyScaleChanged(false);
    }

    public void resetZoom() {
        clearDesiredScale();
        animateToScale(1f, true);
    }

    /**
     * If the image is currently shrunk, animate it back to fit. Returns true if a reset started.
     */
    public boolean resetIfShrunk() {
        if (getCurrentScale() < 0.999f) {
            clearDesiredScale();
            animateToScale(1f, true);
            return true;
        }
        return false;
    }

    /**
     * Request the view to maintain a target scale; applied immediately if drawable and size are ready.
     */
    public void setDesiredScale(float targetScale, boolean animate) {
        hasDesiredScale = true;
        desiredScale = targetScale;
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float clamped = clamp(targetScale, minScale, maxScale);
        if (animate) {
            animateToScale(clamped, false);
        } else {
            setRelativeScaleImmediate(clamped);
        }
    }

    public void clearDesiredScale() {
        hasDesiredScale = false;
        desiredScale = 1f;
    }

    public void setFilmstripEdgeBiasX(float bias) {
        float b = clamp(bias, -1f, 1f);
        if (Math.abs(filmstripEdgeBiasX - b) < 0.001f) return;
        filmstripEdgeBiasX = b;
        checkBounds();
        applyMatrix();
    }


    private void applyDesiredScaleIfNeeded() {
        if (!hasDesiredScale) return;
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float target = clamp(desiredScale, minScale, maxScale);
        if (Math.abs(getCurrentScale() - target) < 0.001f) return;
        setRelativeScaleImmediate(target);
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Set absolute scale relative to base matrix without animation.
     */
    private void setRelativeScaleImmediate(float targetScale) {
        supportMatrix.getValues(matrixValues);
        float current = getCurrentScale();
        if (current == 0f) return;
        float delta = targetScale / current;
        supportMatrix.postScale(delta, delta, getWidth() / 2f, getHeight() / 2f);
        checkBounds();
        applyMatrix();
        notifyScaleChanged(false);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void translate(float dx, float dy) {
        supportMatrix.postTranslate(dx, dy);
        checkBounds();
        applyMatrix();
        notifyScaleChanged(false);
    }

    private void scale(float factor, float focusX, float focusY) {
        float current = getCurrentScale();
        float target = current * factor;
        if (target < minScale) {
            factor = minScale / current;
        } else if (target > maxScale) {
            factor = maxScale / current;
        }
        supportMatrix.postScale(factor, factor, focusX, focusY);
        checkBounds();
        applyMatrix();
        notifyScaleChanged(true);
    }

    private void checkBounds() {
        if (getDrawable() == null) return;

        RectF rect = getDisplayRect();
        if (rect == null) return;

        final float viewWidth = getWidth();
        final float viewHeight = getHeight();

        float deltaX = 0f;
        float deltaY = 0f;

        // ---------- X: support filmstripEdgeBiasX for both narrow and wide images ----------
        float bias = filmstripEdgeBiasX;

        if (Math.abs(bias) > 0.0001f) {
            if (rect.width() <= viewWidth) {
                float centerTx = (viewWidth - rect.width()) * 0.5f;
                float leftTx = 0f;
                float rightTx = viewWidth - rect.width();

                float b = clamp(bias, -1f, 1f);
                float targetTx;
                if (b > 0f) {
                    // 0 -> center, +1 -> left
                    targetTx = centerTx + (leftTx - centerTx) * b;
                } else if (b < 0f) {
                    // 0 -> center, -1 -> right
                    targetTx = centerTx + (rightTx - centerTx) * (-b);
                } else {
                    targetTx = centerTx;
                }
                deltaX = targetTx - rect.left;
            } else {
                // Wide image: left can be in [viewWidth - rectW, 0] (all <=0)
                float t = (1f - bias) * 0.5f; // +1=>0, 0=>0.5, -1=>1
                float minLeft = viewWidth - rect.width(); // <=0
                float desiredLeft = 0f + (minLeft - 0f) * t; // lerp(0 -> minLeft)
                deltaX = desiredLeft - rect.left;
            }
        } else {
            // Original default: center narrow images; clamp wide images to edges
            if (rect.width() <= viewWidth) {
                deltaX = (viewWidth - rect.width()) * 0.5f - rect.left;
            } else {
                if (rect.left > 0) deltaX = -rect.left;
                else if (rect.right < viewWidth) deltaX = viewWidth - rect.right;
            }
        }

        // ---------- Y: keep original centering/clamp ----------
        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) * 0.5f - rect.top;
        } else {
            if (rect.top > 0) deltaY = -rect.top;
            else if (rect.bottom < viewHeight) deltaY = viewHeight - rect.bottom;
        }

        if (Math.abs(deltaX) > 0.001f || Math.abs(deltaY) > 0.001f) {
            supportMatrix.postTranslate(deltaX, deltaY);
        }
    }

    private void applyMatrix() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(supportMatrix);
        setImageMatrix(drawMatrix);
    }

    private float getCurrentScale() {
        supportMatrix.getValues(matrixValues);
        float scaleX = matrixValues[Matrix.MSCALE_X];
        float skewY = matrixValues[Matrix.MSKEW_Y];
        return (float) Math.sqrt(scaleX * scaleX + skewY * skewY);
    }

    @Nullable
    private RectF getDisplayRect() {
        Drawable d = getDrawable();
        if (d == null) return null;
        contentRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(supportMatrix);
        drawMatrix.mapRect(contentRect);
        return contentRect;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            scalingInProgress = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            if (transformListener != null) transformListener.onTransform();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            scalingInProgress = false;
            notifyScaleChanged(true);
        }
    }

    private void animateToScale(float targetScale, boolean fromUser) {
        scroller.forceFinished(true);
        if (resetAnimator != null && resetAnimator.isRunning()) {
            resetAnimator.cancel();
        }
        supportMatrix.getValues(matrixValues);
        final float startScale = getCurrentScale();
        final float startTransX = matrixValues[Matrix.MTRANS_X];
        final float startTransY = matrixValues[Matrix.MTRANS_Y];
        final Matrix current = new Matrix();
        current.set(supportMatrix);
        resetAnimator = ValueAnimator.ofFloat(0f, 1f);
        resetAnimator.setDuration(260);
        resetAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            supportMatrix.set(current);
            // Scale back to 1 gradually
            float interpScale = startScale + (targetScale - startScale) * t;
            float deltaScale = interpScale / startScale;
            supportMatrix.postScale(deltaScale, deltaScale, getWidth() / 2f, getHeight() / 2f);
            // Translate back to center using matrix deltas
            supportMatrix.getValues(matrixValues);
            float curTx = matrixValues[Matrix.MTRANS_X];
            float curTy = matrixValues[Matrix.MTRANS_Y];
            float targetTx = startTransX * (1f - t);
            float targetTy = startTransY * (1f - t);
            supportMatrix.postTranslate(targetTx - curTx, targetTy - curTy);
            checkBounds();
            applyMatrix();
            notifyScaleChanged(fromUser);
        });
        resetAnimator.start();
    }

    public void setOnTransformListener(OnTransformListener l) {
        this.transformListener = l;
    }

    public void setOnScaleChangeListener(OnScaleChangeListener l) {
        this.scaleChangeListener = l;
    }

    public interface OnTransformListener {
        void onTransform();
    }

    public interface OnScaleChangeListener {
        void onScale(float scale, boolean fromUser);
    }

    private class FlingRunner implements Runnable {
        private int lastX = 0;
        private int lastY = 0;

        void start(int velocityX, int velocityY) {
            lastX = 0;
            lastY = 0;
            scroller.forceFinished(true);
            scroller.fling(0, 0, velocityX, velocityY, -10000, 10000, -10000, 10000);
            ViewCompat.postOnAnimation(ZoomableImageView.this, this);
        }

        @Override
        public void run() {
            if (scroller.isFinished() || !scroller.computeScrollOffset()) {
                return;
            }
            int currX = scroller.getCurrX();
            int currY = scroller.getCurrY();
            float dx = currX - lastX;
            float dy = currY - lastY;
            lastX = currX;
            lastY = currY;
            translate(dx, dy);
            if (transformListener != null) transformListener.onTransform();
            ViewCompat.postOnAnimation(ZoomableImageView.this, this);
        }
    }

    private void notifyScaleChanged(boolean fromUser) {
        if (scaleChangeListener != null) {
            scaleChangeListener.onScale(getCurrentScale(), fromUser);
        }
    }

    private void maybeSnapBack() {
        // Snap back to fit only if the user did not shrink past the filmstrip commit threshold.
        float scale = getCurrentScale();
        // If near 1x, leave it.
        if (scale >= 1f - 0.001f) return;
        // At or below minScale (commit), stick there.
        if (scale <= minScale + 0.001f) {
            if (Math.abs(scale - minScale) > 0.0005f) {
                animateToScale(minScale, true);
            }
            return;
        }

        clearDesiredScale();

        // Only 2 snap targets:
        // - If you reached filmstrip (<= commit): we already returned above.
        // - Otherwise: bounce back to full size.
        animateToScale(1f, true);
    }

    public boolean isScalingInProgress() {
        return scalingInProgress;
    }

    public boolean onExternalTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !scalingInProgress) {
            maybeSnapBack();
        }

        // Consume only when handling multi-touch / scaling; let single-finger events belong to the pager.
        return event.getPointerCount() > 1 || scalingInProgress;
    }
}
