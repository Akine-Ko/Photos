package com.example.photos.search;

import android.content.Context;
import android.os.SystemClock;

import com.example.photos.data.MediaStoreRepository;
import com.example.photos.db.FeatureDao;
import com.example.photos.db.FeatureRecord;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;
import com.example.photos.features.FeatureEncoding;
import com.example.photos.features.FeatureType;
import com.example.photos.model.Photo;
import com.example.photos.util.PerfLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
        long totalStart = SystemClock.elapsedRealtime();
        String perfSession = "text-" + System.currentTimeMillis();
        String prepared = query == null ? "" : query.trim();
        String translated = prepared;
        long translateStart = SystemClock.elapsedRealtime();
        try {
            OnnxZhEnTranslator translator = OnnxZhEnTranslator.getInstance(context);
            if (translator != null) {
                translated = translator.translate(prepared);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "Translator unavailable, fallback to raw text", t);
            translated = prepared;
        }
        double translateMs = SystemClock.elapsedRealtime() - translateStart;
        HashMap<String, Object> translateExtra = new HashMap<>();
        translateExtra.put("raw_len", prepared.length());
        translateExtra.put("translated_len", translated.length());
        PerfLogger.log("text_translate", translateMs, perfSession, translateExtra);

        long encodeStart = SystemClock.elapsedRealtime();
        float[] textEmbedding = ClipTextEncoder.encode(context, translated);
        double encodeMs = SystemClock.elapsedRealtime() - encodeStart;
        PerfLogger.log("text_encode", encodeMs, perfSession, null);
        if (textEmbedding == null) {
            android.util.Log.w(TAG, "textEmbedding is null");
            return Collections.emptyList();
        }
        PhotosDb db = PhotosDb.get(context.getApplicationContext());
        FeatureDao featureDao = db.featureDao();
        List<FeatureRecord> records = featureDao.getAllByType(FeatureType.CLIP_IMAGE_EMB.getCode());
        if (records == null || records.isEmpty()) {
            android.util.Log.w(TAG, "No image embeddings cached");
            return Collections.emptyList();
        }
        android.util.Log.i(TAG, "search query=\"" + query + "\" translated=\"" + translated + "\" textDim=" + textEmbedding.length + " vectors=" + records.size());
        long annStart = SystemClock.elapsedRealtime();
        boolean usedHnsw = false;
        List<SearchResultInternal> ordered = searchWithHnsw(context, textEmbedding, limit);
        if (ordered == null) {
            ordered = linearSearch(records, textEmbedding, limit);
        } else {
            usedHnsw = true;
        }
        double annMs = SystemClock.elapsedRealtime() - annStart;
        HashMap<String, Object> annExtra = new HashMap<>();
        annExtra.put("used_hnsw", usedHnsw);
        annExtra.put("limit", limit);
        annExtra.put("vectors", records.size());
        PerfLogger.log("text_search_ann", annMs, perfSession, annExtra);
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
        for (SearchResultInternal internal : ordered) {
            Photo photo = mapToPhoto(photoDao, internal.mediaKey);
            if (photo != null) {
                out.add(new SearchResult(photo, internal.score));
            }
        }
        double totalMs = SystemClock.elapsedRealtime() - totalStart;
        HashMap<String, Object> totalExtra = new HashMap<>();
        totalExtra.put("used_hnsw", usedHnsw);
        totalExtra.put("limit", limit);
        totalExtra.put("results", out.size());
        PerfLogger.log("text_search_total", totalMs, perfSession, totalExtra);
        return out;
    }

    private static List<SearchResultInternal> searchWithHnsw(Context context, float[] query, int limit) {
        HnswImageIndex hnsw = new HnswImageIndex(context.getApplicationContext(), "clip_hnsw.index");
        if (!hnsw.loadIfExists()) {
            return null;
        }
        var res = hnsw.search(query, limit);
        List<SearchResultInternal> ordered = new ArrayList<>();
        for (var r : res) {
            // cosine distance -> similarity
            float score = (float) (1.0 - r.distance());
            ordered.add(new SearchResultInternal(r.item().id(), score));
        }
        ordered.sort((a, b) -> Float.compare(b.score, a.score));
        android.util.Log.i(TAG, "HNSW search used, got=" + ordered.size());
        return ordered;
    }

    private static List<SearchResultInternal> linearSearch(List<FeatureRecord> records, float[] textEmbedding, int limit) {
        PriorityQueue<SearchResultInternal> heap = new PriorityQueue<>(limit, Comparator.comparingDouble(r -> r.score));
        int used = 0;
        int skippedDim = 0;
        for (FeatureRecord record : records) {
            if (record.vector == null || record.vector.length == 0) continue;
            float[] imageEmb = FeatureEncoding.bytesToFloats(record.vector);
            if (imageEmb.length != textEmbedding.length) {
                skippedDim++;
                continue;
            }
            float score = dot(textEmbedding, imageEmb);
            if (heap.size() < limit) {
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            }
            used++;
        }
        android.util.Log.i(TAG, "processed=" + used + " skippedDim=" + skippedDim + " heap=" + heap.size());
        List<SearchResultInternal> ordered = new ArrayList<>(heap);
        ordered.sort((a, b) -> Float.compare(b.score, a.score));
        android.util.Log.i(TAG, "HNSW missing -> linear search, results=" + ordered.size());
        return ordered;
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

    private static final class SearchResultInternal {
        final String mediaKey;
        final float score;

        SearchResultInternal(String mediaKey, float score) {
            this.mediaKey = mediaKey;
            this.score = score;
        }
    }
}
