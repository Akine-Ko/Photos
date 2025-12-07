package com.example.photos.sync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 负责保存媒体增量同步的时间基线（last_sync_ts），以 DATE_MODIFIED 为基准。
 */
public class LastSyncStore {

    private static final String PREF = "media_sync_prefs";
    private static final String KEY_LAST_TS_IMAGES = "last_sync_ts_images";

    private final SharedPreferences sp;

    public LastSyncStore(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public long getLastImagesTs() {
        return sp.getLong(KEY_LAST_TS_IMAGES, 0L);
    }

    public void setLastImagesTs(long ts) {
        sp.edit().putLong(KEY_LAST_TS_IMAGES, ts).apply();
    }
}
