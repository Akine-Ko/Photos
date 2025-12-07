package com.example.photos.search;

import android.content.Context;

import com.example.photos.data.MediaStoreRepository;
import com.example.photos.db.FeatureDao;
import com.example.photos.db.FeatureRecord;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;
import com.example.photos.features.FeatureEncoding;
import com.example.photos.features.FeatureType;
import com.example.photos.model.Photo;
import com.example.photos.search.HnswImageIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class TextSearchEngine {

    private static final String TAG = "TextSearchEngine";

    private TextSearchEngine() {}

    public static class SearchResult {
        public final Photo photo;
        public final float score;

        public SearchResult(Photo photo, float score) {
            this.photo = photo;
            this.score = score;
        }
    }

    public static List<SearchResult> search(Context context, String query, int limit) {
        String prepared = query == null ? "" : query.trim();
        float[] textEmbedding = ClipTextEncoder.encode(context, prepared);
        if (textEmbedding == null) {
            android.util.Log.w(TAG, "textEmbedding is null");
            return Collections.emptyList();
        }
        PhotosDb db = PhotosDb.get(context.getApplicationContext());
        FeatureDao featureDao = db.featureDao();
        List<SearchResultInternal> ordered = searchClip(featureDao, textEmbedding, limit, context);
        // Log top scores for debugging/search visibility in logcat.
        if (!ordered.isEmpty()) {
            int logCount = Math.min(5, ordered.size());
            StringBuilder sb = new StringBuilder("top scores: ");
            for (int i = 0; i < logCount; i++) {
                SearchResultInternal r = ordered.get(i);
                if (i > 0) sb.append(" | ");
                sb.append(i).append(":").append(r.mediaKey).append("=").append(r.score);
            }
            android.util.Log.i(TAG, sb.toString());
        }
        List<SearchResult> out = new ArrayList<>();
        PhotoDao photoDao = db.photoDao();
        int logIdx = 0;
        for (SearchResultInternal internal : ordered) {
            Photo photo = mapToPhoto(photoDao, internal.mediaKey);
            if (photo != null) {
                out.add(new SearchResult(photo, internal.score));
                android.util.Log.i(TAG, "result[" + logIdx + "] score=" + internal.score + " key=" + internal.mediaKey + " uri=" + photo.getImageUrl());
                logIdx++;
            }
        }
        return out;
    }

    private static Photo mapToPhoto(PhotoDao photoDao, String mediaKey) {
        PhotoAsset asset = photoDao.findByContentUri(mediaKey);
        if (asset != null) {
            return MediaStoreRepository.toPhoto(asset);
        }
        // fallback minimal photo
        List<String> tags = Collections.emptyList();
        return new Photo(mediaKey, "", "", "", null, tags, mediaKey, false, com.example.photos.model.PhotoCategory.ALL);
    }

    private static float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static List<SearchResultInternal> searchClip(FeatureDao featureDao, float[] query, int limit, Context ctx) {
        HnswImageIndex idx = new HnswImageIndex(ctx, "clip_hnsw.index");
        List<SearchResultInternal> ordered = new ArrayList<>();
        if (idx.loadIfExists()) {
            var res = idx.search(query, limit);
            for (var r : res) {
                float score = (float) (-r.distance());
                String key = parseMediaKey(r.item().id());
                ordered.add(new SearchResultInternal(key, score));
            }
            ordered.sort((a, b) -> Float.compare(b.score, a.score));
            android.util.Log.i(TAG, "HNSW CLIP used, got=" + ordered.size());
            return ordered;
        }
        List<FeatureRecord> records = featureDao.getAllByType(FeatureType.CLIP_IMAGE_EMB.getCode());
        if (records == null || records.isEmpty()) {
            android.util.Log.w(TAG, "No image embeddings cached");
            return Collections.emptyList();
        }
        PriorityQueue<SearchResultInternal> heap = new PriorityQueue<>(limit, Comparator.comparingDouble(r -> r.score));
        int used = 0;
        int skippedDim = 0;
        for (FeatureRecord record : records) {
            if (record.vector == null || record.vector.length == 0) continue;
            float[] imageEmb = FeatureEncoding.bytesToFloats(record.vector);
            if (imageEmb.length != query.length) {
                skippedDim++;
                continue;
            }
            float score = dot(query, imageEmb);
            if (heap.size() < limit) {
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            }
            used++;
        }
        android.util.Log.i(TAG, "CLIP linear processed=" + used + " skippedDim=" + skippedDim + " heap=" + heap.size());
        List<SearchResultInternal> linear = new ArrayList<>(heap);
        linear.sort((a, b) -> Float.compare(b.score, a.score));
        return linear;
    }

    private static final class SearchResultInternal {
        final String mediaKey;
        final float score;

        SearchResultInternal(String mediaKey, float score) {
            this.mediaKey = mediaKey;
            this.score = score;
        }
    }

    private static String parseMediaKey(String id) {
        int pos = id.indexOf("#f");
        if (pos > 0) {
            return id.substring(0, pos);
        }
        return id;
    }
}
