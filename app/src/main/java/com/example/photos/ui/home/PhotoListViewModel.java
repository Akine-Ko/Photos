package com.example.photos.ui.home;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.photos.data.MediaStoreRepository;
import com.example.photos.db.PhotoAsset;
import com.example.photos.model.Photo;
import com.example.photos.model.PhotoCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoListViewModel extends ViewModel {

    private final MediaStoreRepository mediaStoreRepository = new MediaStoreRepository();
    private List<Photo> baseline;
    private final MutableLiveData<List<Photo>> photosLiveData = new MutableLiveData<>();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mediaLoaded = new AtomicBoolean(false);
    private final AtomicBoolean mediaLoading = new AtomicBoolean(false);
    private final AtomicInteger filterVersion = new AtomicInteger(0);
    private final AtomicBoolean cleared = new AtomicBoolean(false);

    private PhotoCategory currentCategory = PhotoCategory.ALL;
    private String currentQuery = "";

    private static final Comparator<Photo> STABLE_PHOTO_ORDER = (a, b) -> {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        String da = a.getCaptureDate() == null ? "" : a.getCaptureDate();
        String db = b.getCaptureDate() == null ? "" : b.getCaptureDate();
        int cmp = db.compareTo(da); // descending by formatted datetime
        if (cmp != 0) return cmp;
        long ida = parseLongSafe(a.getId());
        long idb = parseLongSafe(b.getId());
        if (ida != idb) return Long.compare(idb, ida); // newer ids first
        String ua = a.getImageUrl() == null ? "" : a.getImageUrl();
        String ub = b.getImageUrl() == null ? "" : b.getImageUrl();
        return ub.compareTo(ua);
    };

    public PhotoListViewModel() {
        baseline = new ArrayList<>();
        photosLiveData.setValue(baseline);
    }

    public LiveData<List<Photo>> getPhotosLiveData() {
        return photosLiveData;
    }

    public void loadFromMediaStore(Context context) {
        loadFromMediaStoreInternal(context, false);
    }

    public void reloadFromMediaStore(Context context) {
        loadFromMediaStoreInternal(context, true);
    }

    public void updateFromDbAssets(java.util.List<PhotoAsset> assets) {
        if (!canRun(mediaExecutor)) return;
        try {
            mediaExecutor.execute(() -> {
                if (!canRun(mediaExecutor)) return;
                List<Photo> media = mapAssets(assets);
                if (media != null) {
                    media.sort(STABLE_PHOTO_ORDER);
                    baseline = media;
                    mediaLoaded.set(true);
                    if (canRun(filterExecutor)) {
                        applyFilters(currentQuery, currentCategory);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void loadFromMediaStoreInternal(Context context, boolean force) {
        if (!force && mediaLoaded.get()) {
            return;
        }
        if (mediaLoading.getAndSet(true)) {
            return;
        }
        if (!canRun(mediaExecutor)) {
            mediaLoading.set(false);
            return;
        }
        try {
            mediaExecutor.execute(() -> {
                try {
                    if (!canRun(mediaExecutor)) return;
                    List<Photo> media = mediaStoreRepository.loadAll(context);
                    if (media != null) {
                        media.sort(STABLE_PHOTO_ORDER);
                        baseline = media;
                    }
                    mediaLoaded.set(true);
                    if (canRun(filterExecutor)) {
                        applyFilters(currentQuery, currentCategory);
                    }
                } finally {
                    mediaLoading.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            mediaLoading.set(false);
        }
    }

    public void applyFilters(String query, PhotoCategory category) {
        if (!canRun(filterExecutor)) return;
        // Keep raw query for UI (so spaces the user types stay visible),
        // but normalize for filtering.
        currentQuery = query == null ? "" : query;
        String normalizedQuery = query == null ? "" : query.trim();
        currentCategory = category == null ? PhotoCategory.ALL : category;
        final int version = filterVersion.incrementAndGet();
        final List<Photo> source = baseline;
        try {
            filterExecutor.execute(() -> {
                if (!canRun(filterExecutor)) return;
                List<Photo> filtered = filterPhotos(source, normalizedQuery, currentCategory);
                if (version != filterVersion.get() || !canRun(filterExecutor)) return; // drop stale results
                photosLiveData.postValue(filtered);
            });
        } catch (RejectedExecutionException ignored) {
            // executor closed after ViewModel cleared; ignore late tasks
        }
    }

    public PhotoCategory getCurrentCategory() {
        return currentCategory;
    }

    public String getCurrentQuery() {
        return currentQuery;
    }

    private List<Photo> mapAssets(List<PhotoAsset> assets) {
        List<Photo> out = new ArrayList<>();
        if (assets == null) return out;
        for (PhotoAsset a : assets) {
            Photo p = MediaStoreRepository.toPhoto(a);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static List<Photo> filterPhotos(List<Photo> source, String query, PhotoCategory category) {
        List<Photo> out = new ArrayList<>();
        if (source == null) return out;
        for (Photo p : source) {
            if (p == null) continue;
            if (p.isInCategory(category) && p.matchesQuery(query)) {
                out.add(p);
            }
        }
        out.sort(STABLE_PHOTO_ORDER);
        return out;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cleared.set(true);
        mediaExecutor.shutdownNow();
        filterExecutor.shutdownNow();
    }

    private static long parseLongSafe(String v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private boolean canRun(ExecutorService executor) {
        return !cleared.get() && executor != null && !executor.isShutdown() && !executor.isTerminated();
    }
}
