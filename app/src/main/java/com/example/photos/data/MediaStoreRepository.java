package com.example.photos.data;

import android.content.Context;

import com.example.photos.db.PhotoAsset;
import com.example.photos.media.MediaScanner;
import com.example.photos.model.Photo;
import com.example.photos.model.PhotoCategory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 直接从 MediaStore 读取必要元数据，避免全量复制到 SQLite。
 * 仅做最小字段映射到 UI 复用的 Photo 模型。
 */
public class MediaStoreRepository {

    /**
     * 读取全部图片的必要字段，映射为 UI 可用的 Photo 列表。
     */
    public List<Photo> loadAll(Context context) {
        List<Photo> out = new ArrayList<>();
        List<PhotoAsset> assets;
        try {
            assets = MediaScanner.scanAllMedia(context.getApplicationContext());
        } catch (SecurityException se) {
            return out;
        }
        for (PhotoAsset a : assets) {
            if (a == null) continue;
            String id = String.valueOf(a.id);
            String title = a.displayName == null ? "" : a.displayName;
            String description = a.mimeType == null ? "" : a.mimeType;
            String captureDate = formatDate(resolveBestTimestamp(a));
            String location = null; // MediaStore 无直接地点字段，保持为空
            List<String> tags = new ArrayList<>();
            String imageUrl = a.contentUri;
            boolean favorite = false; // 轻索引阶段不维护收藏，默认 false
            PhotoCategory category = PhotoCategory.ALL; // 不做分类推断，统一 ALL
            out.add(new Photo(id, title, description, captureDate, location, tags, imageUrl, favorite, category));
        }
        return out;
    }

    /** 将 PhotoAsset 映射为 UI Photo（最小字段） */
    public static Photo toPhoto(PhotoAsset a) {
        if (a == null) return null;
        String id = String.valueOf(a.id);
        String title = a.displayName == null ? "" : a.displayName;
        String description = a.mimeType == null ? "" : a.mimeType;
        String captureDate = formatDate(resolveBestTimestamp(a));
        String location = null;
        java.util.List<String> tags = new java.util.ArrayList<>();
        String imageUrl = a.contentUri;
        boolean favorite = false;
        com.example.photos.model.PhotoCategory category = com.example.photos.model.PhotoCategory.ALL;
        return new com.example.photos.model.Photo(id, title, description, captureDate, location, tags, imageUrl, favorite, category);
    }

    public Photo loadById(Context context, long id) {
        PhotoAsset a = com.example.photos.media.MediaScanner.queryById(context.getApplicationContext(), id);
        return toPhoto(a);
    }

    /**
     * DATE_TAKEN 使用毫秒，DATE_MODIFIED 多数机型为秒；缺失拍摄时间时用修改时间兜底。
     */
    private static long resolveBestTimestamp(PhotoAsset asset) {
        if (asset == null) return 0L;
        if (asset.dateTaken > 0L) return asset.dateTaken;
        long mod = asset.dateModified;
        if (mod <= 0L) return 0L;
        return mod < 10_000_000_000L ? mod * 1000L : mod;
    }

    private static String formatDate(long ts) {
        if (ts <= 0L) return "";
        Date d = new Date(ts);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(d);
    }
}
