package com.example.photos.media;

import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;

import android.content.Context;

/**
 * ContentObserver：监听媒体库变化，做细粒度增量更新。
 */
public class MediaStoreObserver extends ContentObserver {

    public interface OnChangeListener {
        void onInsertOrUpdate(PhotoAsset asset);
        void onDelete(long id);
    }

    private final Context appContext;
    private final OnChangeListener listener;

    public MediaStoreObserver(Context context, OnChangeListener listener) {
        super(new Handler(Looper.getMainLooper()));
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        if (uri == null) return;
        try {
            // uri 形如 content://media/external/images/media/12345
            String last = uri.getLastPathSegment();
            if (last == null) return;
            long id = Long.parseLong(last);
            PhotoAsset asset = MediaScanner.queryById(appContext, id);
            if (asset != null) {
                if (listener != null) listener.onInsertOrUpdate(asset);
            } else {
                if (listener != null) listener.onDelete(id);
            }
        } catch (Throwable ignore) {
        }
    }
}
