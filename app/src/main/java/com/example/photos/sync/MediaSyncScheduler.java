package com.example.photos.sync;

import android.content.Context;

/**
 * 统一调度入口，避免在多处重复创建 Work。
 */
public class MediaSyncScheduler {

    public static void scheduleOnPermissionGranted(Context context) {
        MediaIncrementalSyncWorker.enqueueOneTime(context);
    }

    public static void ensurePeriodic(Context context) {
        // no-op: rely on ContentObserver + one-time incremental on launch
    }
}
