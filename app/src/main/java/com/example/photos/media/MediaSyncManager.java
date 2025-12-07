package com.example.photos.media;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import com.example.photos.db.CategoryDao;
import com.example.photos.db.FeatureDao;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 同步管理器：负责注册监听、执行全量/增量入库。
 */
public class MediaSyncManager implements MediaStoreObserver.OnChangeListener {

    private final Context appContext;
    private final PhotoDao photoDao;
    private final CategoryDao categoryDao;
    private final FeatureDao featureDao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MediaStoreObserver observer;

    public MediaSyncManager(Context context) {
        this.appContext = context.getApplicationContext();
        PhotosDb db = PhotosDb.get(appContext);
        this.photoDao = db.photoDao();
        this.categoryDao = db.categoryDao();
        this.featureDao = db.featureDao();
    }

    /** 首次/补偿：执行全量扫描并入库 */
    public void fullScanAsync() {
        io.execute(() -> {
            List<PhotoAsset> all;
            try {
                all = MediaScanner.scanAll(appContext);
            } catch (SecurityException se) {
                // 无权限时不做破坏性删除，等待用户授权
                return;
            }
            // Drop DB rows (and their indexes) whose files disappeared from MediaStore.
            List<PhotoAsset> existing = photoDao.getAll();
            HashSet<Long> latestIds = new HashSet<>();
            if (all != null) {
                for (PhotoAsset asset : all) {
                    if (asset != null) latestIds.add(asset.id);
                }
            }
            if (existing != null) {
                for (PhotoAsset stale : existing) {
                    if (stale != null && !latestIds.contains(stale.id)) {
                        removeAssetAndIndexes(stale);
                    }
                }
            }
            photoDao.upsert(all);
        });
    }

    /** 增量：按最大修改时间后增量拉取 */
    public void incrementalScanAsync() {
        io.execute(() -> {
            Long max = photoDao.maxDateModified();
            long ts = max == null ? 0L : max;
            List<PhotoAsset> delta = MediaScanner.scanModifiedAfter(appContext, ts);
            photoDao.upsert(delta);
        });
    }

    /** 注册媒体库变更监听 */
    public void registerObserver() {
        if (observer != null) return;
        observer = new MediaStoreObserver(appContext, this);
        ContentResolver cr = appContext.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        cr.registerContentObserver(uri, true, observer);
    }

    /** 取消监听 */
    public void unregisterObserver() {
        if (observer == null) return;
        appContext.getContentResolver().unregisterContentObserver(observer);
        observer = null;
    }

    @Override
    public void onInsertOrUpdate(PhotoAsset asset) {
        io.execute(() -> photoDao.upsert(java.util.Arrays.asList(asset)));
        // 新增/更新时触发一次最近批次的向量/分类流水线（唯一任务，正在跑时不会重复）
        com.example.photos.sync.ClipJobScheduler.enqueueRecentPipeline(appContext, 32);
    }

    @Override
    public void onDelete(long id) {
        io.execute(() -> removeAssetAndIndexes(id));
    }

    private void removeAssetAndIndexes(long id) {
        PhotoAsset asset = photoDao.findById(id);
        removeAssetAndIndexes(asset);
    }

    private void removeAssetAndIndexes(PhotoAsset asset) {
        if (asset == null) return;
        String key = asset.contentUri;
        photoDao.deleteById(asset.id);
        if (key != null) {
            categoryDao.deleteByMediaKey(key);
            featureDao.deleteByMediaKey(key);
        }
    }
}
