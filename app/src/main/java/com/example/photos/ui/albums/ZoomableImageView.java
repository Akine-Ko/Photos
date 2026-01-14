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
    private static final float PAN_THRESHOLD = 1.05f; // allow ViewPager when near base scale

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float minScale = 1f;
    private float maxScale = 6f;
    private float lastX;
    private float lastY;
    private boolean isDragging = false;
    private ValueAnimator resetAnimator;
    private OnTransformListener transformListener;
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
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                lastX = event.getX();
                lastY = event.getY();
                isDragging = true;
                getParent().requestDisallowInterceptTouchEvent(getCurrentScale() > PAN_THRESHOLD);
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
                    return false; // let parent (ViewPager) handle swipe when not zoomed
                }
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                RectF rect = getDisplayRect();
                if (rect != null) {
                    boolean atLeft = rect.left >= -1f;
                    boolean atRight = rect.right <= getWidth() + 1f;
                    if ((atLeft && dx > 0) || (atRight && dx < 0)) {
                        // Hit image edge: allow parent (ViewPager) to take over for page swipe.
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                }
                translate(dx, dy);
                if (transformListener != null) transformListener.onTransform();
                break;
            case MotionEvent.ACTION_UP:
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
        supportMatrix.reset();
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
        minScale = 0.7f;
        applyMatrix();
    }

    public void resetZoom() {
        animateToIdentity();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void translate(float dx, float dy) {
        supportMatrix.postTranslate(dx, dy);
        checkBounds();
        applyMatrix();
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
    }

    private void checkBounds() {
        RectF rect = getDisplayRect();
        if (rect == null) return;
        float deltaX = 0, deltaY = 0;
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }
        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }
        supportMatrix.postTranslate(deltaX, deltaY);
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
        }
    }

    private void animateToIdentity() {
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
        resetAnimator.setDuration(200);
        resetAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            supportMatrix.set(current);
            // Scale back to 1 gradually
            float targetScale = 1f + (startScale - 1f) * (1f - t);
            float deltaScale = targetScale / getCurrentScale();
            supportMatrix.postScale(deltaScale, deltaScale, getWidth() / 2f, getHeight() / 2f);
            // Translate back to center
            float tx = startTransX * (1f - t);
            float ty = startTransY * (1f - t);
            supportMatrix.postTranslate(tx - getTranslationX(), ty - getTranslationY());
            checkBounds();
            applyMatrix();
        });
        resetAnimator.start();
    }

    public void setOnTransformListener(OnTransformListener l) {
        this.transformListener = l;
    }

    public interface OnTransformListener {
        void onTransform();
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
}
