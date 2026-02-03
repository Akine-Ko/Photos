package com.example.photos.ui.albums;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.graphics.Typeface;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.exifinterface.media.ExifInterface;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.example.photos.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.io.File;
import java.io.InputStream;

public class AlbumViewerActivity extends AppCompatActivity {

    public static final String EXTRA_URLS = "extra_urls";
    public static final String EXTRA_IDS = "extra_ids";
    public static final String EXTRA_START_INDEX = "extra_start_index";
    public static final String EXTRA_DELETED_IDS = "extra_deleted_ids";
    public static final String EXTRA_DATES = "extra_dates";
    private static final float FILMSTRIP_PREVIEW_END = ZoomableImageView.FILMSTRIP_STICKY;

    private static final RequestOptions VIEWER_OPTIONS = new RequestOptions()
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .dontTransform();

    private final List<PhotoEntry> entries = new ArrayList<>();
    private final ArrayList<String> deletedIds = new ArrayList<>();
    private boolean chromeVisible = true;
    private TextView counterTextView;
    private TextView titleTextView;
    private ViewPager2 pager;
    private AlbumPagerAdapter adapter;
    private ThumbnailAdapter thumbAdapter;
    private View topBar;
    private View bottomPanel;
    private int topBasePaddingTop;
    private int bottomBasePaddingBottom;
    private int lightBarColor = Color.WHITE;
    private int darkBarColor = Color.BLACK;
    private boolean filmstripMode = false;
    private int pagerPadStart = 0;
    private int pagerPadEnd = 0;
    private int pagerPadTop = 0;
    private int pagerPadBottom = 0;
    private boolean pagerPadCaptured = false;
    private float filmstripPreviewProgress = 0f;
    private float filmstripScale = 1f;
    private int touchSlop;
    private float tapDownX;
    private float tapDownY;
    private long tapDownTime;
    private boolean tapCandidate;
    private MultiPageSnapHelper multiPageSnapHelper;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;
    private ActivityResultLauncher<Intent> manageMediaPermissionLauncher;
    private PhotoEntry pendingDeleteEntry;
    private int pendingDeletePosition = RecyclerView.NO_POSITION;
    private PhotoEntry pendingManageEntry;
    private int pendingManagePosition = RecyclerView.NO_POSITION;
    private Uri pendingManageUri;
    private final java.util.concurrent.ExecutorService dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_viewer);
        pager = findViewById(R.id.albumViewPager);
        counterTextView = findViewById(R.id.albumViewerCounterTextView);
        titleTextView = findViewById(R.id.albumViewerTitleTextView);
        topBar = findViewById(R.id.albumViewerTopBar);
        bottomPanel = findViewById(R.id.albumViewerBottomPanel);
        topBasePaddingTop = topBar.getPaddingTop();
        bottomBasePaddingBottom = bottomPanel.getPaddingBottom();
        ImageButton backButton = findViewById(R.id.albumViewerBackButton);
        ImageButton shareButton = findViewById(R.id.albumViewerShareButton);
        View shareButtonContainer = findViewById(R.id.albumViewerShareButtonContainer);
        ImageButton deleteButton = findViewById(R.id.albumViewerDeleteButton);
        View deleteButtonContainer = findViewById(R.id.albumViewerDeleteButtonContainer);
        ImageButton addToButton = findViewById(R.id.albumViewerAddToButton);
        View addToButtonContainer = findViewById(R.id.albumViewerAddToButtonContainer);
        ImageButton infoButton = findViewById(R.id.albumViewerInfoButton);
        RecyclerView thumbRecyclerView = findViewById(R.id.albumViewerThumbRecyclerView);
        View root = findViewById(R.id.albumViewerRoot);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        // Ensure status/navigation icons use dark mode on light background initially
        updateSystemBars(true, root);
        applyWindowInsets(root);
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (pendingDeleteEntry == null || pendingDeletePosition == RecyclerView.NO_POSITION) {
                        clearPendingDelete();
                        return;
                    }
                    if (result.getResultCode() == RESULT_OK) {
                        onDeleteSuccess(pendingDeletePosition, pendingDeleteEntry.id);
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                    }
                    clearPendingDelete();
                });
        manageMediaPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (hasManageMediaPermission()) {
                        if (pendingManageEntry != null && pendingManageUri != null && pendingManagePosition != RecyclerView.NO_POSITION) {
                            performDeleteWithPermission(pendingManageEntry, pendingManageUri, pendingManagePosition);
                        }
                    } else {
                        Toast.makeText(this, R.string.manage_media_permission_needed, Toast.LENGTH_SHORT).show();
                    }
                    clearPendingManage();
                });
        backButton.setOnClickListener(v -> finishWithResult());
        View.OnClickListener shareClickListener = v -> shareCurrent();
        View.OnClickListener deleteClickListener = v -> deleteCurrent();
        View.OnClickListener addToClickListener = v -> showAddToAlbum();
        if (shareButtonContainer != null) {
            shareButtonContainer.setOnClickListener(shareClickListener);
        } else {
            shareButton.setOnClickListener(shareClickListener);
        }
        if (deleteButtonContainer != null) {
            deleteButtonContainer.setOnClickListener(deleteClickListener);
        } else {
            deleteButton.setOnClickListener(deleteClickListener);
        }
        if (addToButtonContainer != null) {
            addToButtonContainer.setOnClickListener(addToClickListener);
        } else {
            addToButton.setOnClickListener(addToClickListener);
        }
        infoButton.setOnClickListener(v -> showInfoForCurrent());

        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        ArrayList<String> ids = getIntent().getStringArrayListExtra(EXTRA_IDS);
        ArrayList<String> dates = getIntent().getStringArrayListExtra(EXTRA_DATES);
        int start = getIntent().getIntExtra(EXTRA_START_INDEX, 0);
        if (urls != null) {
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                String id = (ids != null && i < ids.size()) ? ids.get(i) : null;
                String date = (dates != null && i < dates.size()) ? dates.get(i) : null;
                entries.add(new PhotoEntry(id, url, date));
            }
        }
        adapter = new AlbumPagerAdapter(entries, pager, this::toggleChrome, this::hideChrome, this::onScaleChanged,
                position -> filmstripMode ? filmstripScale : 1f,
                () -> filmstripMode,
                () -> (double) filmstripPreviewProgress);
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        installMultiPageSnapHelper();
        pager.setOnTouchListener((v, ev) -> {
            ZoomableImageView current = findCurrentZoomView();

            // Forward multi-touch (pinch) to the current image view.
            boolean consumePinch = false;
            if (current != null) {
                consumePinch = current.onExternalTouchEvent(ev);
            }

            boolean filmstripActive = filmstripMode || filmstripPreviewProgress > 0f;
            if (filmstripActive && ev.getPointerCount() == 1) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tapDownX = ev.getX();
                        tapDownY = ev.getY();
                        tapDownTime = ev.getEventTime();
                        tapCandidate = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (tapCandidate) {
                            float dx = Math.abs(ev.getX() - tapDownX);
                            float dy = Math.abs(ev.getY() - tapDownY);
                            if (dx > touchSlop || dy > touchSlop) {
                                tapCandidate = false;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (tapCandidate && (ev.getEventTime() - tapDownTime) < 220) {
                            handleFilmstripTap(ev.getX());
                        }
                        tapCandidate = false;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        tapCandidate = false;
                        break;
                }
            }

            // Only consume when scaling; single-finger gestures keep ViewPager2 behavior.
            return consumePinch;
        });
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter();
                if (filmstripMode) {
                    pager.post(() -> syncFilmstripScaleToVisiblePages(false, true));
                }
            }
        });
        pager.setCurrentItem(Math.max(0, Math.min(start, entries.size() - 1)), false);
        updateCounter();
        thumbAdapter = new ThumbnailAdapter(entries, this::scrollToPosition);
        thumbRecyclerView.setAdapter(thumbAdapter);
        updateThumbSelection();
        pager.setOnClickListener(v -> {
            if (filmstripMode || filmstripPreviewProgress > 0f) return;
            toggleChrome();
        });

        // Predictive back support: route system back gesture to our finish logic.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishWithResult();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        chromeVisible = true;
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        if (bottomPanel != null) bottomPanel.setVisibility(View.VISIBLE);
        updateSystemBars(true, findViewById(R.id.albumViewerRoot));
    }

    private void updateCounter() {
        if (counterTextView == null) return;
        int pos = pager == null ? 0 : pager.getCurrentItem();
        PhotoEntry entry = (pos >= 0 && pos < entries.size()) ? entries.get(pos) : null;
        String date = entry == null ? "" : entry.date;
        if (titleTextView != null) {
            titleTextView.setText(formatDate(date));
        }
        if (counterTextView != null) {
            counterTextView.setVisibility(View.GONE);
        }
        updateThumbSelection();
    }

    private void toggleChrome() {
        if (topBar == null || bottomPanel == null) return;
        chromeVisible = !chromeVisible;
        int newVisibility = chromeVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(newVisibility);
        bottomPanel.setVisibility(newVisibility);
        updateSystemBars(chromeVisible, findViewById(R.id.albumViewerRoot));
    }

    private void hideChrome() {
        if (topBar == null || bottomPanel == null) return;
        if (!chromeVisible) return;
        chromeVisible = false;
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        updateSystemBars(false, findViewById(R.id.albumViewerRoot));
    }

    private void onScaleChanged(int position, float scale, boolean fromUser) {
        if (pager == null) return;
        if (position != pager.getCurrentItem()) return;
        final float enter = ZoomableImageView.FILMSTRIP_ENTER;
        final float exit = ZoomableImageView.FILMSTRIP_EXIT;
        applyFilmstripPreviewProgress(scale);
        if (fromUser && scale < 1f) {
            filmstripScale = Math.max(ZoomableImageView.FILMSTRIP_STICKY, Math.min(scale, 1f));
            if (filmstripMode) {
                syncFilmstripScaleToVisiblePages(false, true);
            }
        }
        if (fromUser && !filmstripMode && scale <= enter) {
            filmstripScale = Math.max(ZoomableImageView.FILMSTRIP_STICKY, Math.min(scale, 1f));
            enterFilmstripMode();
            syncFilmstripScaleToVisiblePages(false, true);
        } else if (fromUser && filmstripMode && scale >= exit) {
            exitFilmstripMode();
            syncFilmstripScaleToVisiblePages(true, true);
        }
    }

    private void enterFilmstripMode() {
        if (pager == null || filmstripMode) return;
        filmstripMode = true;
        applyFilmstripPreview(1f);
        pager.setOffscreenPageLimit(3);
    }

    private void exitFilmstripMode() {
        if (pager == null || !filmstripMode) return;
        filmstripMode = false;
        applyFilmstripPreview(0f);
    }

    private void applyFilmstripPreviewProgress(float scale) {
        float start = 1f;
        float end = FILMSTRIP_PREVIEW_END;
        float progress = 0f;
        if (scale < start && start != end) {
            progress = Math.min(1f, Math.max(0f, (start - scale) / (start - end)));
        }
        applyFilmstripPreview(progress);
    }

    private void installMultiPageSnapHelper() {
        if (multiPageSnapHelper != null) return;
        multiPageSnapHelper = new MultiPageSnapHelper(() -> filmstripMode);
        pager.post(() -> {
            View child = pager.getChildAt(0);
            if (!(child instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) child;
            if (rv.getOnFlingListener() != null) {
                rv.setOnFlingListener(null);
            }
            multiPageSnapHelper.attachToRecyclerView(rv);
        });
    }

    @Nullable
    private ZoomableImageView findCurrentZoomView() {
        if (pager == null) return null;
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView rv = (RecyclerView) child;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pager.getCurrentItem());
        if (vh == null) return null;
        return vh.itemView.findViewById(R.id.albumViewerImageView);
    }

    private void capturePagerPaddingIfNeeded() {
        if (pager == null || pagerPadCaptured) return;
        pagerPadCaptured = true;
        pagerPadStart = pager.getPaddingStart();
        pagerPadEnd = pager.getPaddingEnd();
        pagerPadTop = pager.getPaddingTop();
        pagerPadBottom = pager.getPaddingBottom();
    }

    private void applyFilmstripPreview(float progress) {
        if (pager == null) return;
        capturePagerPaddingIfNeeded();

        float clamped = Math.min(1f, Math.max(0f, progress));
        filmstripPreviewProgress = clamped;

        boolean enable = clamped > 0f;
        pager.setClipToPadding(!enable);
        pager.setClipChildren(!enable);

        ViewGroup rv = (ViewGroup) pager.getChildAt(0);
        if (rv != null) {
            rv.setClipToPadding(!enable);
            rv.setClipChildren(!enable);
        }

        // Window that reveals side pages; bigger = more peek, smaller = tighter center.
        float sidePx = dpToPx(24f) * clamped; // Tweak within ~18–32 for taste.

        pager.setPadding(
                (int) (pagerPadStart + sidePx),
                pagerPadTop,
                (int) (pagerPadEnd + sidePx),
                pagerPadBottom
        );

        // Nudge side pages inward for a tighter feel (too large may overlap).
        final float pullInPx = dpToPx(14f) * clamped;

        pager.setPageTransformer((page, position) -> {
            float p = Math.max(-1f, Math.min(1f, position));

            // Gentle side-page scale to avoid a loose feel.
            float scale = 1f - 0.035f * clamped * Math.abs(p);
            page.setScaleX(scale);
            page.setScaleY(scale);

            // Pull pages inward instead of pushing them apart.
            page.setTranslationX(-p * pullInPx);

            // Keep opacity to avoid a hollow look.
            page.setAlpha(1f);

            // Lift center page slightly for iOS-like focus.
            ViewCompat.setTranslationZ(page, (1f - Math.abs(p)) * clamped);
        });

        pager.setOffscreenPageLimit(enable ? 3 : 1);
    }

    private void handleFilmstripTap(float x) {
        if (pager == null || adapter == null) return;

        int cur = pager.getCurrentItem();
        int count = adapter.getItemCount();
        if (count <= 1) return;

        float w = pager.getWidth();

        float edge = Math.max(pager.getPaddingLeft(), pager.getPaddingRight());
        float zone = Math.max(edge + dpToPx(24f), w * 0.32f);

        if (x < zone) {
            if (cur > 0) pager.setCurrentItem(cur - 1, true);
        } else if (x > (w - zone)) {
            if (cur < count - 1) pager.setCurrentItem(cur + 1, true);
        }
    }

    private void syncFilmstripScaleToVisiblePages(boolean resetToFit, boolean includeCurrent) {
        if (pager == null) return;
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) child;
        int current = pager.getCurrentItem();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View page = rv.getChildAt(i);
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(page);
            int pos = vh == null ? RecyclerView.NO_POSITION : vh.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) continue;
            if (!includeCurrent && pos == current) continue;
            ZoomableImageView iv = page.findViewById(R.id.albumViewerImageView);
            if (iv == null) continue;
            if (resetToFit) {
                iv.clearDesiredScale();
                iv.setDesiredScale(1f, false);
            } else {
                float target = Math.min(filmstripScale, 1f);
                iv.setDesiredScale(target, false);
            }
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void showInfoForCurrent() {
        int position = pager == null ? -1 : pager.getCurrentItem();
        if (position < 0 || position >= entries.size()) return;
        PhotoEntry entry = entries.get(position);
        if (entry == null || entry.url == null) return;
        MediaInfo info = loadMediaInfo(entry);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_photo_info, null, false);
        TextView nameView = dialogView.findViewById(R.id.infoName);
        TextView dateView = dialogView.findViewById(R.id.infoDate);
        TextView sizeView = dialogView.findViewById(R.id.infoSize);
        TextView cameraView = dialogView.findViewById(R.id.infoCamera);
        TextView pathView = dialogView.findViewById(R.id.infoPath);
        TextView locationView = dialogView.findViewById(R.id.infoLocation);

        if (nameView != null) nameView.setText(nonNull(info.displayName, getString(R.string.app_name)));
        if (dateView != null) dateView.setText(info.displayDate());
        if (sizeView != null) sizeView.setText(info.sizeAndResolution());
        if (cameraView != null) cameraView.setText(info.cameraText());
        if (pathView != null) pathView.setText(nonNull(info.path, entry.url));
        if (locationView != null) locationView.setText(info.locationDisplay(this));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private MediaInfo loadMediaInfo(@NonNull PhotoEntry entry) {
        MediaInfo info = new MediaInfo();
        Uri uri = Uri.parse(entry.url);
        info.path = uri.getPath();
        info.displayName = lastSegment(uri);
        info.dateText = entry.date;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.WIDTH,
                        MediaStore.MediaColumns.HEIGHT,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.Images.ImageColumns.LATITUDE,
                        MediaStore.Images.ImageColumns.LONGITUDE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idxName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int idxSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
                int idxW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
                int idxH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
                int idxDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
                int idxLat = c.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE);
                int idxLong = c.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE);
                if (idxName >= 0 && !c.isNull(idxName)) info.displayName = c.getString(idxName);
                if (idxSize >= 0 && !c.isNull(idxSize)) info.sizeBytes = c.getLong(idxSize);
                if (idxW >= 0 && !c.isNull(idxW)) info.width = c.getInt(idxW);
                if (idxH >= 0 && !c.isNull(idxH)) info.height = c.getInt(idxH);
                if (idxDate >= 0 && !c.isNull(idxDate)) {
                    long ts = c.getLong(idxDate) * 1000L;
                    info.dateText = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(ts));
                }
                if (idxLat >= 0 && !c.isNull(idxLat)) info.latitude = (float) c.getDouble(idxLat);
                if (idxLong >= 0 && !c.isNull(idxLong)) info.longitude = (float) c.getDouble(idxLong);
            }
        } catch (Exception ignored) {
        }
        if (info.path == null) {
            info.path = entry.url;
        }
        readExif(info, uri);
        maybeFillAddress(info);
        return info;
    }

    private void readExif(@NonNull MediaInfo info, @NonNull Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return;
            ExifInterface exif = new ExifInterface(inputStream);
            info.cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE);
            info.cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL);
            info.lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL);
            info.focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN);
            info.aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN);
            info.exposureTimeSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN);
            info.iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1);
            info.exposureBiasEv = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN);
            String exifDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (exifDate == null || exifDate.isEmpty()) {
                exifDate = exif.getAttribute(ExifInterface.TAG_DATETIME);
            }
            if (exifDate != null && !exifDate.isEmpty()) {
                info.exifDateText = exifDate;
            }
            float[] latLong = new float[2];
            if (exif.getLatLong(latLong)) {
                info.latitude = latLong[0];
                info.longitude = latLong[1];
            }
        } catch (Exception ignored) {
        }
    }

    private void maybeFillAddress(@NonNull MediaInfo info) {
        if (!info.hasLatLong()) return;
        try {
            Geocoder geocoder = new Geocoder(getApplicationContext(), Locale.getDefault());
            List<Address> res = geocoder.getFromLocation(info.latitude, info.longitude, 1);
            if (res != null && !res.isEmpty()) {
                Address addr = res.get(0);
                StringBuilder sb = new StringBuilder();
                if (addr.getCountryName() != null) sb.append(addr.getCountryName()).append(" 路 ");
                if (addr.getAdminArea() != null) sb.append(addr.getAdminArea()).append(" 路 ");
                if (addr.getSubAdminArea() != null) sb.append(addr.getSubAdminArea()).append(" 路 ");
                if (addr.getLocality() != null) sb.append(addr.getLocality()).append(" 路 ");
                if (addr.getSubLocality() != null) sb.append(addr.getSubLocality()).append(" 路 ");
                if (addr.getThoroughfare() != null) sb.append(addr.getThoroughfare()).append(" 路 ");
                if (addr.getSubThoroughfare() != null) sb.append(addr.getSubThoroughfare());
                String text = sb.toString().replaceAll("\\s*路\\s*$", "");
                if (!text.isEmpty()) {
                    info.address = text;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String lastSegment(Uri uri) {
        if (uri == null) return null;
        String seg = uri.getLastPathSegment();
        if (seg != null) return seg;
        String path = uri.getPath();
        if (path == null) return null;
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }

    private String nonNull(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private void updateSystemBars(boolean showChrome, View root) {
        WindowInsetsControllerCompat controller = root == null ? null : ViewCompat.getWindowInsetsController(root);
        if (showChrome) {
            getWindow().setStatusBarColor(lightBarColor);
            getWindow().setNavigationBarColor(lightBarColor);
            if (controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars());
                controller.setAppearanceLightStatusBars(true);
                controller.setAppearanceLightNavigationBars(true);
            }
        } else {
            getWindow().setStatusBarColor(darkBarColor);
            getWindow().setNavigationBarColor(darkBarColor);
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars());
                controller.setAppearanceLightStatusBars(false);
                controller.setAppearanceLightNavigationBars(false);
            }
        }
    }

    private void shareCurrent() {
        int position = pager == null ? -1 : pager.getCurrentItem();
        if (position < 0 || position >= entries.size()) return;
        PhotoEntry entry = entries.get(position);
        if (entry == null || entry.url == null) return;
        Uri uri = Uri.parse(entry.url);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCurrent() {
        int position = pager == null ? -1 : pager.getCurrentItem();
        if (position < 0 || position >= entries.size()) return;
        PhotoEntry entry = entries.get(position);
        if (entry == null || entry.url == null) return;
        Uri uri = Uri.parse(entry.url);
        showDeleteConfirmDialog(entry, uri, position);
    }

    @Nullable
    private PhotoEntry currentEntry() {
        int position = pager == null ? -1 : pager.getCurrentItem();
        if (position < 0 || position >= entries.size()) return null;
        return entries.get(position);
    }

    private void showAddToAlbum() {
        PhotoEntry entry = currentEntry();
        if (entry == null || entry.url == null) {
            Toast.makeText(this, R.string.album_add_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        dbExecutor.execute(() -> {
            List<AlbumOption> albumOptions = loadAlbumOptions();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (albumOptions.isEmpty()) {
                    promptCreateAlbum(entry);
                } else {
                    showAlbumPicker(entry, albumOptions);
                }
            });
        });
    }

    @NonNull
    private List<AlbumOption> loadAlbumOptions() {
        Set<String> names = new LinkedHashSet<>();
        try {
            List<com.example.photos.db.CategoryDao.CategoryCount> counts =
                    com.example.photos.db.PhotosDb.get(getApplicationContext())
                            .categoryDao()
                            .countsByCategory();
            for (com.example.photos.db.CategoryDao.CategoryCount c : counts) {
                if (c == null || c.category == null) continue;
                String trimmed = c.category.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            List<String> custom = CustomAlbumsStore.loadAll(getApplicationContext());
            if (custom != null) {
                for (String n : custom) {
                    if (n == null) continue;
                    String trimmed = n.trim();
                    if (!trimmed.isEmpty()) {
                        names.add(trimmed);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<AlbumOption> result = new ArrayList<>();
        for (String n : names) {
            result.add(new AlbumOption(n, CategoryDisplay.displayOf(n)));
        }
        try {
            result.sort((a, b) -> a.display.compareToIgnoreCase(b.display));
        } catch (Throwable ignored) {
        }
        return result;
    }

    private void showAlbumPicker(@NonNull PhotoEntry entry, @NonNull List<AlbumOption> albums) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_album_picker, null, false);
        ListView listView = dialogView.findViewById(R.id.albumPickerList);
        View newButton = dialogView.findViewById(R.id.albumPickerNew);
        View cancelButton = dialogView.findViewById(R.id.albumPickerCancel);
        List<String> displayNames = new ArrayList<>();
        for (AlbumOption o : albums) {
            displayNames.add(o.display);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_album_picker, R.id.albumPickerItemText, displayNames) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(R.id.albumPickerItemText);
                if (tv != null) {
                    tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                }
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setHeaderDividersEnabled(false);
        listView.setFooterDividersEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < albums.size()) {
                addEntryToAlbum(entry, albums.get(position).name);
            }
            dialog.dismiss();
        });
        if (newButton != null) {
            newButton.setOnClickListener(v -> {
                dialog.dismiss();
                promptCreateAlbum(entry);
            });
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void promptCreateAlbum(@NonNull PhotoEntry entry) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_album, null, false);
        EditText input = dialogView.findViewById(R.id.addAlbumInput);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        View confirm = dialogView.findViewById(R.id.addAlbumConfirm);
        View cancel = dialogView.findViewById(R.id.addAlbumCancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> dialog.dismiss());
        }
        if (confirm != null) {
            confirm.setOnClickListener(v -> {
                String name = input != null && input.getText() != null
                        ? input.getText().toString().trim()
                        : "";
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.albums_add_album_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                addEntryToAlbum(entry, name);
                dialog.dismiss();
            });
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void addEntryToAlbum(@NonNull PhotoEntry entry, @NonNull String albumName) {
        dbExecutor.execute(() -> {
            boolean success = false;
            try {
                CustomAlbumsStore.add(getApplicationContext(), albumName);
                com.example.photos.db.CategoryRecord record = new com.example.photos.db.CategoryRecord();
                record.mediaKey = entry.url;
                record.category = albumName;
                record.score = 1f;
                record.updatedAt = System.currentTimeMillis();
                com.example.photos.db.PhotosDb.get(getApplicationContext()).categoryDao().upsert(record);
                success = true;
            } catch (Throwable ignored) {
            }
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this,
                        finalSuccess
                                ? getString(R.string.album_add_success, albumName)
                                : getString(R.string.album_add_failed),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showDeleteConfirmDialog(@NonNull PhotoEntry entry, @NonNull Uri uri, int position) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_confirm, null, false);
        TextView message = dialogView.findViewById(R.id.deleteConfirmMessage);
        AppCompatButton positive = dialogView.findViewById(R.id.deleteConfirmPositive);
        AppCompatButton negative = dialogView.findViewById(R.id.deleteConfirmNegative);
        if (message != null) {
            message.setText(R.string.delete_confirm_message);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (positive != null) {
            positive.setText(R.string.delete_confirm_positive);
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                performDelete(entry, uri, position);
            });
        }
        if (negative != null) {
            negative.setText(R.string.delete_confirm_negative);
            negative.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setWindowAnimations(R.style.BottomDialogAnimation);
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.y = (int) (getResources().getDisplayMetrics().density * 48); // offset upward a bit above bottom
            dialog.getWindow().setAttributes(lp);
        }
    }

    private void performDelete(@NonNull PhotoEntry entry, @NonNull Uri uri, int position) {
        if (needsManageMediaPermission(uri) && !hasManageMediaPermission()) {
            requestManageMediaPermission(entry, uri, position);
            return;
        }
        performDeleteWithPermission(entry, uri, position);
    }

    private void performDeleteWithPermission(@NonNull PhotoEntry entry, @NonNull Uri uri, int position) {
        try {
            if (deleteViaContentResolver(uri)) {
                onDeleteSuccess(position, entry.id);
                return;
            }
        } catch (RecoverableSecurityException rse) {
            if (launchDeletePermission(uri, entry, position)) return;
        } catch (SecurityException se) {
            if (launchDeletePermission(uri, entry, position)) return;
        } catch (Throwable ignored) {
            // fall through to file deletion
        }
        if (tryDeleteFile(entry.url)) {
            onDeleteSuccess(position, entry.id);
        } else {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishWithResult() {
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_DELETED_IDS, deletedIds);
        setResult(RESULT_OK, data);
        finish();
    }

    private void scrollToPosition(int position) {
        if (pager == null || position < 0 || position >= entries.size()) return;
        pager.setCurrentItem(position, false);
        updateCounter();
    }

    private String formatDate(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() >= 10) {
            return raw.substring(0, 10);
        }
        return raw;
    }

    private void applyWindowInsets(View root) {
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            WindowInsetsCompat windowInsets = insets;
            int top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            if (topBar != null) {
                topBar.setPadding(topBar.getPaddingLeft(), topBasePaddingTop + top,
                        topBar.getPaddingRight(), topBar.getPaddingBottom());
            }
            if (bottomPanel != null) {
                bottomPanel.setPadding(bottomPanel.getPaddingLeft(),
                        bottomPanel.getPaddingTop(),
                        bottomPanel.getPaddingRight(),
                        bottomBasePaddingBottom + bottom);
            }
        return insets;
    });
}

    private boolean deleteViaContentResolver(@NonNull Uri uri) {
        int rows = getContentResolver().delete(uri, null, null);
        return rows > 0;
    }

    private boolean hasManageMediaPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return MediaStore.canManageMedia(this);
    }

    private boolean needsManageMediaPermission(@NonNull Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        return "content".equalsIgnoreCase(uri.getScheme())
                && (MediaStore.AUTHORITY.equals(uri.getAuthority()) || (uri.getAuthority() != null && uri.getAuthority().contains("media")));
    }

    private void requestManageMediaPermission(@NonNull PhotoEntry entry, @NonNull Uri uri, int position) {
        pendingManageEntry = entry;
        pendingManagePosition = position;
        pendingManageUri = uri;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            manageMediaPermissionLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.manage_media_permission_needed, Toast.LENGTH_SHORT).show();
            clearPendingManage();
        }
    }

    private boolean launchDeletePermission(@NonNull Uri uri, @NonNull PhotoEntry entry, int position) {
        if (deletePermissionLauncher == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            pendingDeleteEntry = entry;
            pendingDeletePosition = position;
            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(),
                    Collections.singletonList(uri));
            IntentSenderRequest request = new IntentSenderRequest.Builder(pi.getIntentSender()).build();
            deletePermissionLauncher.launch(request);
            return true;
        } catch (Exception e) {
            clearPendingDelete();
            return false;
        }
    }

    private boolean tryDeleteFile(String url) {
        try {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File f = new File(uri.getPath());
                return f.exists() && f.delete();
            }
            File f = new File(url);
            return f.exists() && f.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void onDeleteSuccess(int position, String entryId) {
        if (position < 0 || position >= entries.size()) {
            clearPendingDelete();
            updateCounter();
            return;
        }
        if (entryId != null) {
            deletedIds.add(entryId);
        }
        removeFromDbAsync(entryId, position >= 0 && position < entries.size() ? entries.get(position).url : null);
        entries.remove(position);
        adapter.notifyItemRemoved(position);
        if (thumbAdapter != null) {
            thumbAdapter.notifyItemRemoved(position);
        }
        if (entries.isEmpty()) {
            finishWithResult();
            return;
        }
        adapter.notifyItemRangeChanged(position, entries.size() - position);
        if (thumbAdapter != null) {
            thumbAdapter.notifyItemRangeChanged(position, entries.size() - position);
            thumbAdapter.setSelected(Math.min(position, entries.size() - 1));
        }
        if (pager != null) {
            int newPos = Math.min(position, entries.size() - 1);
            pager.setCurrentItem(newPos, false);
            pager.post(this::updateCounter);
        } else {
            updateCounter();
        }
        clearPendingDelete();
    }

    private void clearPendingManage() {
        pendingManageEntry = null;
        pendingManagePosition = RecyclerView.NO_POSITION;
        pendingManageUri = null;
    }

    private void clearPendingDelete() {
        pendingDeleteEntry = null;
        pendingDeletePosition = RecyclerView.NO_POSITION;
    }

    private void removeFromDbAsync(String id, String uri) {
        if (id == null && uri == null) return;
        dbExecutor.execute(() -> {
            try {
                com.example.photos.db.PhotosDb db = com.example.photos.db.PhotosDb.get(getApplicationContext());
                if (id != null) {
                    try {
                        long lid = Long.parseLong(id);
                        db.photoDao().deleteById(lid);
                    } catch (NumberFormatException ignored) {}
                }
                if (uri != null) {
                    db.categoryDao().deleteByMediaKey(uri);
                    db.featureDao().deleteByMediaKey(uri);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void updateThumbSelection() {
        if (thumbAdapter == null || pager == null) return;
        thumbAdapter.setSelected(pager.getCurrentItem());
    }

    private static class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailAdapter.ThumbViewHolder> {
        private final List<PhotoEntry> items;
        private int selected = 0;
        private final java.util.function.IntConsumer onClick;

        ThumbnailAdapter(List<PhotoEntry> items, java.util.function.IntConsumer onClick) {
            this.items = items == null ? new ArrayList<>() : items;
            this.onClick = onClick;
        }

        void setSelected(int index) {
            int old = selected;
            selected = index;
            notifyItemChanged(old);
            notifyItemChanged(selected);
        }

        @NonNull
        @Override
        public ThumbViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album_viewer_thumb, parent, false);
            return new ThumbViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbViewHolder holder, int position) {
            holder.bind(items.get(position), position == selected, () -> {
                if (onClick != null) onClick.accept(position);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ThumbViewHolder extends RecyclerView.ViewHolder {
            private final ImageView imageView;
            private final View overlay;

            ThumbViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.thumbImageView);
                overlay = itemView.findViewById(R.id.thumbOverlay);
            }

            void bind(PhotoEntry entry, boolean selected, Runnable onClick) {
                overlay.setVisibility(selected ? View.VISIBLE : View.GONE);
                Glide.with(imageView.getContext())
                        .load(entry == null ? null : entry.url)
                        .apply(VIEWER_OPTIONS)
                        .placeholder(R.drawable.ic_photo_placeholder)
                        .error(R.drawable.ic_photo_placeholder)
                        .into(imageView);
                itemView.setOnClickListener(v -> onClick.run());
            }
        }
    }

    private static class AlbumPagerAdapter extends RecyclerView.Adapter<AlbumPagerAdapter.ViewHolder> {

        private final List<PhotoEntry> items;
        private final ViewPager2 pager;
        private final Runnable onToggleChrome;
        private final Runnable onHideChrome;
        private final TriConsumer<Integer, Float, Boolean> onScaleChange;
        private final java.util.function.IntToDoubleFunction initialScaleProvider;
        private final java.util.function.BooleanSupplier filmstripModeSupplier;
        private final java.util.function.DoubleSupplier previewProgressSupplier;

        AlbumPagerAdapter(@NonNull List<PhotoEntry> items,
                          ViewPager2 pager,
                          Runnable onToggleChrome,
                          Runnable onHideChrome,
                          TriConsumer<Integer, Float, Boolean> onScaleChange,
                          java.util.function.IntToDoubleFunction initialScaleProvider,
                          java.util.function.BooleanSupplier filmstripModeSupplier,
                          java.util.function.DoubleSupplier previewProgressSupplier) {
            this.items = items == null ? new ArrayList<>() : items;
            this.pager = pager;
            this.onToggleChrome = onToggleChrome;
            this.onHideChrome = onHideChrome;
            this.onScaleChange = onScaleChange;
            this.initialScaleProvider = initialScaleProvider;
            this.filmstripModeSupplier = filmstripModeSupplier;
            this.previewProgressSupplier = previewProgressSupplier;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album_viewer_photo, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final com.example.photos.ui.albums.ZoomableImageView imageView;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.albumViewerImageView);
            }

            void bind(PhotoEntry entry) {
                if (entry == null) return;
                if (imageView != null) {
                    float initScale = 1f;
                    int pos = getBindingAdapterPosition();
                    if (initialScaleProvider != null && pos != RecyclerView.NO_POSITION) {
                        initScale = (float) initialScaleProvider.applyAsDouble(pos);
                    }
                    if (initScale >= 0.999f) {
                        imageView.clearDesiredScale();
                    } else {
                        imageView.setDesiredScale(initScale, false);
                    }
                    imageView.setOnClickListener(v -> handleClick());
                    imageView.setOnTransformListener(() -> {
                        if (onHideChrome != null) onHideChrome.run();
                    });
                    imageView.setOnScaleChangeListener((scale, fromUser) -> {
                        if (onScaleChange != null) {
                            int scalePos = getBindingAdapterPosition();
                            if (scalePos != RecyclerView.NO_POSITION) {
                                onScaleChange.accept(scalePos, scale, fromUser);
                            }
                        }
                    });
                }
                Glide.with(imageView.getContext())
                        .load(entry.url)
                        .apply(VIEWER_OPTIONS)
                        .placeholder(R.drawable.ic_photo_placeholder)
                        .error(R.drawable.ic_photo_placeholder)
                        .into(imageView);
                itemView.setOnClickListener(v -> handleClick());
            }

            private void handleClick() {
                int pos = getBindingAdapterPosition();
                // Filmstrip/transition: don't reset or toggle chrome; optionally jump to tapped page.
                if (filmstripModeSupplier.getAsBoolean() || previewProgressSupplier.getAsDouble() > 0d) {
                    if (pos != RecyclerView.NO_POSITION && pager != null && pos != pager.getCurrentItem()) {
                        pager.setCurrentItem(pos, true);
                    }
                    return;
                }

                if (imageView != null && imageView.resetIfShrunk()) {
                    return;
                }
                if (onToggleChrome != null) onToggleChrome.run();
            }
        }
    }

    private static class PhotoEntry {
        final String id;
        final String url;
        final String date;

        PhotoEntry(String id, String url, String date) {
            this.id = id;
            this.url = url;
            this.date = date;
        }
    }

    private static class MediaInfo {
        String displayName;
        String path;
        long sizeBytes = 0;
        int width = 0;
        int height = 0;
        String dateText;
        String exifDateText;
        String cameraMake;
        String cameraModel;
        String lensModel;
        double focalLengthMm = Double.NaN;
        double aperture = Double.NaN;
        double exposureTimeSeconds = Double.NaN;
        int iso = -1;
        double exposureBiasEv = Double.NaN;
        float latitude = Float.NaN;
        float longitude = Float.NaN;
        String address;

        String prettySize() {
            if (sizeBytes <= 0) return "";
            double mb = sizeBytes / (1024.0 * 1024.0);
            if (mb >= 1) return String.format(java.util.Locale.getDefault(), "%.2f MB", mb);
            double kb = sizeBytes / 1024.0;
            return String.format(java.util.Locale.getDefault(), "%.0f KB", kb);
        }

        String sizeAndResolution() {
            String size = sizeBytes > 0 ? prettySize() : "";
            String resolution = (width > 0 && height > 0) ? width + " x " + height : "";
            if (!size.isEmpty() && !resolution.isEmpty()) return size + "  " + resolution;
            if (!size.isEmpty()) return size;
            if (!resolution.isEmpty()) return resolution;
            return "\u672a\u77e5"; // 未知
        }

        String displayDate() {
            String exifDate = formatExifDate();
            if (exifDate != null && !exifDate.isEmpty()) {
                return exifDate;
            }
            return dateText == null ? "" : dateText;
        }

        private String formatExifDate() {
            if (exifDateText == null || exifDateText.isEmpty()) return null;
            try {
                java.text.SimpleDateFormat inFmt = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.getDefault());
                java.util.Date date = inFmt.parse(exifDateText);
                if (date != null) {
                    return new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault()).format(date);
                }
            } catch (Exception ignored) {
            }
            return exifDateText;
        }

        String cameraText() {
            StringBuilder sb = new StringBuilder();
            if (cameraModel != null && !cameraModel.isEmpty()) {
                sb.append(cameraModel.trim());
            }
            if (lensModel != null && !lensModel.isEmpty()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(lensModel.trim());
            }
            if (cameraMake != null && !cameraMake.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(cameraMake.trim());
            }
            return sb.length() == 0 ? "\u65e0\u76f8\u673a\u4fe1\u606f" : sb.toString(); // 无相机信息
        }

        String isoText() {
            return iso > 0 ? "ISO " + iso : "";
        }

        String evText() {
            if (!isKnown(exposureBiasEv)) return "";
            return "EV " + trimTrailingZeros(exposureBiasEv);
        }

        String locationDisplay(@NonNull Context ctx) {
            if (hasLatLong()) {
                if (address != null && !address.isEmpty()) {
                    return address;
                }
                return String.format(java.util.Locale.getDefault(), "%.5f, %.5f", latitude, longitude);
            }
            return "\u65e0\u4f4d\u7f6e\u4fe1\u606f"; // 无位置信息
        }

        private String trimTrailingZeros(double value) {
            String text = String.format(java.util.Locale.getDefault(), "%.2f", value);
            while (text.contains(".") && text.endsWith("0")) {
                text = text.substring(0, text.length() - 1);
            }
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }

        private boolean isKnown(double value) {
            return !Double.isNaN(value);
        }

        private boolean hasLatLong() {
            return !Float.isNaN(latitude) && !Float.isNaN(longitude);
        }
    }

    private static class AlbumOption {
        final String name;
        final String display;

        AlbumOption(String name, String display) {
            this.name = name;
            this.display = display == null ? name : display;
        }
    }
}
