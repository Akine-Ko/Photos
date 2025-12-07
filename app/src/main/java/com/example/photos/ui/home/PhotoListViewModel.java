package com.example.photos.ui.home;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.photos.data.MediaStoreRepository;
import com.example.photos.data.PhotoRepository;
import com.example.photos.db.PhotoAsset;
import com.example.photos.model.Photo;
import com.example.photos.model.PhotoCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首页列表的状态容器：负责组合仓库数据与筛选逻辑。
 */
public class PhotoListViewModel extends ViewModel {

    private final PhotoRepository repository = PhotoRepository.getInstance();
    private final MediaStoreRepository mediaStoreRepository = new MediaStoreRepository();
    private List<Photo> baseline;
    private final MutableLiveData<List<Photo>> photosLiveData = new MutableLiveData<>();
    private final MutableLiveData<HomeUiState> uiStateLiveData = new MutableLiveData<>();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mediaLoaded = new AtomicBoolean(false);
    private final AtomicBoolean mediaLoading = new AtomicBoolean(false);
    private final AtomicInteger filterVersion = new AtomicInteger(0);
    private final AtomicBoolean cleared = new AtomicBoolean(false);

    private PhotoCategory currentCategory = PhotoCategory.ALL;
    private String currentQuery = "";

    /**
     * 初始化时加载样本数据，并生成默认 UI 状态。
     */
    public PhotoListViewModel() {
        baseline = new ArrayList<>();
        photosLiveData.setValue(baseline);
        updateUiState(baseline);
    }

    public LiveData<List<Photo>> getPhotosLiveData() {
        return photosLiveData;
    }

    public LiveData<HomeUiState> getUiStateLiveData() {
        return uiStateLiveData;
    }

    public List<PhotoCategory> getAvailableCategories() {
        return repository.getFilterableCategories();
    }

    public void loadFromMediaStore(Context context) {
        loadFromMediaStoreInternal(context, false);
    }

    public void reloadFromMediaStore(Context context) {
        loadFromMediaStoreInternal(context, true);
    }

    /**
     * 用数据库最新的 photo_assets 刷新 baseline，保证首页与分类同步。
     */
    public void updateFromDbAssets(java.util.List<PhotoAsset> assets) {
        if (!canRun(mediaExecutor)) return;
        try {
            mediaExecutor.execute(() -> {
                if (!canRun(mediaExecutor)) return;
                List<Photo> media = mapAssets(assets);
                if (media != null) {
                    media.sort(Comparator.comparing(Photo::getCaptureDate, String::compareTo).reversed());
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
                        media.sort(Comparator.comparing(Photo::getCaptureDate, String::compareTo).reversed());
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

    /**
     * 接收查询词与类别来自 Chip/搜索框的输入，驱动列表更新。
     */
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
                updateUiState(filtered);
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

    /**
     * 汇总核心指标并拼装 HomeUiState，方便 Fragment 一次性消费。
     */
    private void updateUiState(List<Photo> filtered) {
        int total = baseline == null ? 0 : baseline.size();
        int favorites = 0;
        if (baseline != null) {
            for (Photo p : baseline) {
                if (p != null && p.isFavorite()) favorites++;
            }
        }
        int filteredCount = filtered == null ? 0 : filtered.size();
        String insight = buildInsight(filteredCount);
        uiStateLiveData.postValue(new HomeUiState(currentCategory, currentQuery, total, favorites, filteredCount, insight));
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
        out.sort(Comparator.comparing(Photo::getCaptureDate, String::compareTo).reversed());
        return out;
    }

    /**
     * 根据筛选结果数量与类别生成拟人化文案，贴近开题需求描述。
     */
    private String buildInsight(int filteredCount) {
        if (filteredCount == 0) {
            return "未找到符合条件的照片，尝试调整筛选条";
        }
        String categoryName = currentCategory.getDisplayName();
        if (currentCategory == PhotoCategory.ALL) {
            return String.format(Locale.CHINA, "智能推荐 %d 张高质量照片，随时生成图文纪要", filteredCount);
        }
        return String.format(Locale.CHINA, "%s频道筛选出 %d 张候选照片，已同步至端侧知识图谱", categoryName, filteredCount);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cleared.set(true);
        mediaExecutor.shutdownNow();
        filterExecutor.shutdownNow();
    }

    private boolean canRun(ExecutorService executor) {
        return !cleared.get() && executor != null && !executor.isShutdown() && !executor.isTerminated();
    }
}
