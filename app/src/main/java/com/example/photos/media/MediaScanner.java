package com.example.photos.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.example.photos.db.PhotoAsset;

import java.util.ArrayList;
import java.util.List;

/**
 * */
public class MediaScanner {

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final Uri VIDEOS = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    private static final String DEFAULT_ORDER =
            MediaStore.MediaColumns.DATE_MODIFIED + " DESC, " + MediaStore.MediaColumns._ID + " DESC";

    private static final String[] IMAGE_PROJECTION = new String[]{
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION
    };

    private static final String[] VIDEO_PROJECTION = new String[]{
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    };

    public static List<PhotoAsset> scanAll(Context context) {
        return queryImages(context, null, null, DEFAULT_ORDER);
    }

    /**
     * Returns both photos and videos so the home screen count matches the system gallery.
     */
    public static List<PhotoAsset> scanAllMedia(Context context) {
        List<PhotoAsset> merged = new ArrayList<>();
        merged.addAll(scanAll(context));
        merged.addAll(queryVideos(context, null, null, DEFAULT_ORDER));
        merged.sort((a, b) -> {
            long ta = bestTimestampMillis(a);
            long tb = bestTimestampMillis(b);
            if (ta != tb) return Long.compare(tb, ta);
            long ida = a == null ? 0L : a.id;
            long idb = b == null ? 0L : b.id;
            if (ida != idb) return Long.compare(idb, ida);
            String ua = a == null || a.contentUri == null ? "" : a.contentUri;
            String ub = b == null || b.contentUri == null ? "" : b.contentUri;
            return ub.compareTo(ua);
        });
        return merged;
    }

    private static long bestTimestampMillis(PhotoAsset asset) {
        if (asset == null) return 0L;
        if (asset.dateTaken > 0L) return asset.dateTaken;
        long mod = asset.dateModified;
        if (mod <= 0L) return 0L;
        return mod < 10_000_000_000L ? mod * 1000L : mod;
    }

    public static List<PhotoAsset> scanModifiedAfter(Context context, long ts) {
        String sel = MediaStore.Images.Media.DATE_MODIFIED + "> ?";
        String[] args = new String[]{String.valueOf(ts)};
        return queryImages(context, sel, args, MediaStore.Images.Media.DATE_MODIFIED + " DESC");
    }

    /**
     * 查询修改时间小于给定阈值的一批较旧图片，按修改时间倒序返回。
     * 用于全库分页向旧翻页：每批处理后以本批最小 DATE_MODIFIED 作为下一批游标。
     */
    public static List<PhotoAsset> scanModifiedBefore(Context context, long tsExclusive) {
        String sel = MediaStore.Images.Media.DATE_MODIFIED + " < ?";
        String[] args = new String[]{String.valueOf(tsExclusive)};
        return queryImages(context, sel, args, MediaStore.Images.Media.DATE_MODIFIED + " DESC");
    }

    public static PhotoAsset queryById(Context context, long id) {
        String sel = MediaStore.Images.Media._ID + "=?";
        String[] args = new String[]{String.valueOf(id)};
        List<PhotoAsset> list = queryImages(context, sel, args, null);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 批量按 ID 查询，使用 IN 子句，避免逐条查询带来的卡顿。
     */
    public static List<PhotoAsset> queryByIds(Context context, java.util.List<Long> ids) {
        java.util.List<PhotoAsset> out = new java.util.ArrayList<>();
        if (ids == null || ids.isEmpty()) return out;
        // 分批以避免 SQL 占位符过多（部分设备上上限较小）
        final int CHUNK = 200;
        for (int i = 0; i < ids.size(); i += CHUNK) {
            int end = Math.min(i + CHUNK, ids.size());
            java.util.List<Long> sub = ids.subList(i, end);
            StringBuilder sb = new StringBuilder();
            sb.append(MediaStore.Images.Media._ID).append(" IN (");
            String[] args = new String[sub.size()];
            for (int k = 0; k < sub.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append('?');
                args[k] = String.valueOf(sub.get(k));
            }
            sb.append(')');
            out.addAll(queryImages(context, sb.toString(), args, MediaStore.Images.Media.DATE_MODIFIED + " DESC"));
        }
        return out;
    }

    private static List<PhotoAsset> queryImages(Context context, String selection, String[] selArgs, String order) {
        return queryInternal(context,
                IMAGES,
                IMAGE_PROJECTION,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.ORIENTATION,
                selection,
                selArgs,
                order);
    }

    private static List<PhotoAsset> queryVideos(Context context, String selection, String[] selArgs, String order) {
        return queryInternal(context,
                VIDEOS,
                VIDEO_PROJECTION,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                null,
                selection,
                selArgs,
                order);
    }

    private static List<PhotoAsset> queryInternal(Context context,
                                                  Uri baseUri,
                                                  String[] projection,
                                                  String dateTakenColumn,
                                                  String dateModifiedColumn,
                                                  String bucketIdColumn,
                                                  String bucketNameColumn,
                                                  String orientationColumn,
                                                  String selection,
                                                  String[] selArgs,
                                                  String order) {
        ContentResolver resolver = context.getContentResolver();
        selection = mergeSelection(selection);
        List<PhotoAsset> out = new ArrayList<>();
        try (Cursor c = resolver.query(baseUri, projection, selection, selArgs, order)) {
            if (c == null) return out;
            int idxId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int idxName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int idxTaken = dateTakenColumn == null ? -1 : c.getColumnIndex(dateTakenColumn);
            int idxMod = dateModifiedColumn == null ? -1 : c.getColumnIndex(dateModifiedColumn);
            int idxMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int idxSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int idxW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int idxH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int idxBucketId = bucketIdColumn == null ? -1 : c.getColumnIndex(bucketIdColumn);
            int idxBucketName = bucketNameColumn == null ? -1 : c.getColumnIndex(bucketNameColumn);
            int idxOrientation = orientationColumn == null ? -1 : c.getColumnIndex(orientationColumn);
            while (c.moveToNext()) {
                long id = c.getLong(idxId);
                Uri uri = ContentUris.withAppendedId(baseUri, id);
                PhotoAsset a = new PhotoAsset();
                a.id = id;
                a.contentUri = uri.toString();
                a.displayName = safeString(c, idxName);
                a.dateTaken = safeLong(c, idxTaken);
                a.dateModified = safeLong(c, idxMod);
                a.mimeType = safeString(c, idxMime);
                a.size = safeLong(c, idxSize);
                a.width = safeInt(c, idxW);
                a.height = safeInt(c, idxH);
                a.bucketId = safeString(c, idxBucketId);
                a.bucketName = safeString(c, idxBucketName);
                a.orientation = safeInt(c, idxOrientation);
                out.add(a);
            }
        }
        return out;
    }

    private static String safeString(Cursor c, int idx) {
        return (idx >= 0 && !c.isNull(idx)) ? c.getString(idx) : null;
    }

    private static long safeLong(Cursor c, int idx) {
        return (idx >= 0 && !c.isNull(idx)) ? c.getLong(idx) : 0L;
    }

    private static int safeInt(Cursor c, int idx) {
        return (idx >= 0 && !c.isNull(idx)) ? c.getInt(idx) : 0;
    }

    private static String mergeSelection(String selection) {
        List<String> clauses = new ArrayList<>();
        if (!TextUtils.isEmpty(selection)) {
            clauses.add("(" + selection + ")");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clauses.add(MediaStore.MediaColumns.IS_PENDING + "=0");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clauses.add(MediaStore.MediaColumns.IS_TRASHED + "=0");
        }
        if (clauses.isEmpty()) return selection;
        return TextUtils.join(" AND ", clauses);
    }
}
