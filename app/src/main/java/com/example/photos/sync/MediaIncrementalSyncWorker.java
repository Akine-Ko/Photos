package com.example.photos.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * 兜底增量同步 Worker：按 last_sync_ts 基线做 DATE_MODIFIED 增量扫描。
 */
public class MediaIncrementalSyncWorker extends Worker {

    public static final String UNIQUE_PERIODIC_NAME = "images_incremental_periodic";
    public static final String UNIQUE_ONETIME_NAME = "images_incremental_onetime";

    @VisibleForTesting
    static void upsertDelta(android.content.Context appContext,
                            java.util.List<com.example.photos.db.PhotoAsset> assets) {
        if (assets == null || assets.isEmpty()) return;
        com.example.photos.db.PhotosDb db = com.example.photos.db.PhotosDb.get(appContext);
        db.photoDao().upsert(assets);
    }

    public MediaIncrementalSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        LastSyncStore store = new LastSyncStore(app);
        long last = store.getLastImagesTs();
        boolean firstSync = last <= 0;
        long maxTs = last;
        boolean hasDelta = false;
        try {
            java.util.List<com.example.photos.db.PhotoAsset> list = com.example.photos.media.MediaScanner.scanModifiedAfter(app, last);
            // 把增量写入本地表，后续向量/分类才能覆盖到新媒体。
            upsertDelta(app, list);
            for (com.example.photos.db.PhotoAsset a : list) {
                if (a != null) {
                    if (a.dateModified > maxTs) maxTs = a.dateModified;
                }
            }
            hasDelta = maxTs > last;
            if (hasDelta) {
                store.setLastImagesTs(maxTs);
                int batch = Math.min(64, Math.max(4, list == null ? 0 : list.size()));
                if (!firstSync && batch > 0) {
                    ClipJobScheduler.enqueueRecentPipeline(app, batch);
                }
            }
        } catch (SecurityException se) {
            return Result.success();
        } catch (Throwable t) {
            return Result.retry();
        }
        return Result.success(new androidx.work.Data.Builder()
                .putBoolean("hasDelta", hasDelta)
                .putLong("maxTs", maxTs)
                .build());
    }

    public static void enqueueOneTime(Context context) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(MediaIncrementalSyncWorker.class)
                .setConstraints(new Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_ONETIME_NAME, ExistingWorkPolicy.KEEP, req);
    }

    public static void enqueuePeriodic(Context context) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(MediaIncrementalSyncWorker.class,
                60, TimeUnit.MINUTES, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
