package com.example.photos.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.example.photos.data.MediaStoreRepository;
import com.example.photos.db.FeatureDao;
import com.example.photos.db.FeatureRecord;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;
import com.example.photos.features.FeatureEncoding;
import com.example.photos.features.FeatureType;
import com.example.photos.model.Photo;
import com.example.photos.search.face.SFaceOpenCv;
import com.example.photos.util.PerfLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 图片以图搜图，底层用 DINOv3，支持人脸加权。
 */
public final class ImageSearchEngine {

    private static final String TAG = "ImageSearchEngine";
    private static final float FACE_BLEND = 0.85f;
    private static final float FACE_SIM_STRONG = 0.6f;
    private static final float FACE_SIM_SOFT = 0.4f;

    private ImageSearchEngine() {}

    public static class SearchResult {
        public final Photo photo;
        public final float score;

        public SearchResult(Photo photo, float score) {
            this.photo = photo;
            this.score = score;
        }
    }

    public static List<SearchResult> search(Context context, @Nullable Uri queryUri, int limit) {
        if (queryUri == null) return Collections.emptyList();
        PhotosDb db = PhotosDb.get(context.getApplicationContext());
        PhotoDao photoDao = db.photoDao();
        PhotoAsset asset = photoDao.findByContentUri(queryUri.toString());
        if (asset == null) {
            asset = new PhotoAsset();
            asset.id = 0;
            asset.contentUri = queryUri.toString();
        }
        return search(context, asset, limit);
    }

    public static List<SearchResult> search(Context context, PhotoAsset queryAsset, int limit) {
        if (queryAsset == null || queryAsset.contentUri == null) {
            return Collections.emptyList();
        }
        final int topK = Math.max(1, limit);
        String perfSession = "img-" + System.currentTimeMillis();
        long totalStart = SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        PhotosDb db = PhotosDb.get(app);
        FeatureDao featureDao = db.featureDao();
        float[] query = loadOrEncodeQuery(app, featureDao, queryAsset);
        if (query == null || query.length == 0) {
            android.util.Log.w(TAG, "query embedding is null");
            return Collections.emptyList();
        }
        long annStart = SystemClock.elapsedRealtime();
        SearchWithIndexResult indexed = searchWithIndex(app, featureDao, query, topK);
        double annMs = SystemClock.elapsedRealtime() - annStart;
        List<SearchResultInternal> ordered = indexed.results;
        HashMap<String, Object> annExtra = new HashMap<>();
        annExtra.put("used_hnsw", indexed.usedHnsw);
        annExtra.put("limit", topK);
        annExtra.put("results", ordered == null ? 0 : ordered.size());
        PerfLogger.log("image_search_ann", annMs, perfSession, annExtra);
        ordered = rerankByFace(app, featureDao, ordered, queryAsset, topK, perfSession);

        List<SearchResult> out = new ArrayList<>();
        PhotoDao photoDao = db.photoDao();
        for (int i = 0; i < ordered.size(); i++) {
            SearchResultInternal internal = ordered.get(i);
            Photo photo = mapToPhoto(photoDao, internal.mediaKey);
            if (photo != null) {
                out.add(new SearchResult(photo, internal.score));
                if (i < 3) {
                    android.util.Log.i(TAG, "top[" + i + "] " + internal.mediaKey + " score=" + internal.score);
                }
            }
        }
        double totalMs = SystemClock.elapsedRealtime() - totalStart;
        HashMap<String, Object> totalExtra = new HashMap<>();
        totalExtra.put("used_hnsw", indexed.usedHnsw);
        totalExtra.put("limit", topK);
        totalExtra.put("results", out.size());
        PerfLogger.log("image_search_total", totalMs, perfSession, totalExtra);
        return out;
    }

    private static SearchWithIndexResult searchWithIndex(Context app, FeatureDao featureDao, float[] query, int topK) {
        HnswImageIndex hnsw = new HnswImageIndex(app, "dino_hnsw.index");
        boolean usedHnsw = false;
        if (hnsw.loadIfExists()) {
            usedHnsw = true;
            Map<String, Float> best = new LinkedHashMap<>();
            var res = hnsw.search(query, topK);
            for (var r : res) {
                String key = parseMediaKey(r.item().id());
                float score = (float) (-r.distance()); // cosine distance -> similarity
                Float cur = best.get(key);
                if (cur == null || score > cur) {
                    best.put(key, score);
                }
            }
            List<SearchResultInternal> ordered = new ArrayList<>();
            for (var e : best.entrySet()) {
                ordered.add(new SearchResultInternal(e.getKey(), e.getValue()));
            }
            ordered.sort((a, b) -> Float.compare(b.score, a.score));
            android.util.Log.i(TAG, "HNSW search used, got=" + ordered.size());
            if (ordered.size() > topK) {
                return new SearchWithIndexResult(ordered.subList(0, topK), usedHnsw);
            }
            return new SearchWithIndexResult(ordered, usedHnsw);
        }
        List<FeatureRecord> records = featureDao.getAllByType(FeatureType.DINO_IMAGE_EMB.getCode());
        List<SearchResultInternal> ordered = linearSearch(records, query, topK);
        android.util.Log.i(TAG, "HNSW missing -> linear search, results=" + ordered.size());
        return new SearchWithIndexResult(ordered, usedHnsw);
    }

    private static List<SearchResultInternal> linearSearch(List<FeatureRecord> records, float[] query, int topK) {
        if (records == null || records.isEmpty()) {
            android.util.Log.w(TAG, "No DINO embeddings cached");
            return Collections.emptyList();
        }
        PriorityQueue<SearchResultInternal> heap = new PriorityQueue<>(topK, Comparator.comparingDouble(r -> r.score));
        for (FeatureRecord record : records) {
            if (record.vector == null || record.vector.length == 0) continue;
            float[] emb = FeatureEncoding.bytesToFloats(record.vector);
            if (emb.length != query.length) continue;
            float score = dot(query, emb);
            if (heap.size() < topK) {
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.offer(new SearchResultInternal(record.mediaKey, score));
            }
        }
        List<SearchResultInternal> ordered = new ArrayList<>(heap);
        ordered.sort((a, b) -> Float.compare(b.score, a.score));
        return ordered;
    }

    private static float[] loadOrEncodeQuery(Context context, FeatureDao featureDao, PhotoAsset asset) {
        byte[] cached = featureDao.vectorForKey(asset.contentUri, FeatureType.DINO_IMAGE_EMB.getCode());
        if (cached != null && cached.length > 0) {
            return FeatureEncoding.bytesToFloats(cached);
        }
        float[] embedding = DinoImageEmbedder.encode(context, asset);
        if (embedding != null) {
            FeatureRecord record = new FeatureRecord();
            record.mediaKey = asset.contentUri;
            record.featType = FeatureType.DINO_IMAGE_EMB.getCode();
            record.faceId = 0;
            record.vector = FeatureEncoding.floatsToBytes(embedding);
            record.updatedAt = System.currentTimeMillis() / 1000L;
            featureDao.upsert(record);
        }
        return embedding;
    }

    private static Photo mapToPhoto(PhotoDao photoDao, String mediaKey) {
        String key = parseMediaKey(mediaKey);
        PhotoAsset asset = photoDao.findByContentUri(key);
        if (asset != null) {
            return MediaStoreRepository.toPhoto(asset);
        }
        List<String> tags = Collections.emptyList();
        return new Photo(key, "", "", "", null, tags, key, false, com.example.photos.model.PhotoCategory.ALL);
    }

    private static float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static List<SearchResultInternal> rerankByFace(Context ctx,
                                                           FeatureDao featureDao,
                                                           List<SearchResultInternal> base,
                                                           PhotoAsset queryAsset,
                                                           int topK,
                                                           String perfSession) {
        long start = SystemClock.elapsedRealtime();
        int faceCount = 0;
        int candidateCount = 0;
        boolean ran = false;
        SFaceOpenCv recognizer;
        try {
            recognizer = new SFaceOpenCv(ctx);
        } catch (Exception e) {
            android.util.Log.w(TAG, "face pipeline init failed", e);
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran);
            return base;
        }
        Bitmap qbmp = decodeKeepAspect(ctx, Uri.parse(queryAsset.contentUri), 960);
        if (qbmp == null) {
            android.util.Log.i(TAG, "face rerank: query bitmap null");
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran);
            return base;
        }
        float[][] qfaces = recognizer.embedAll(qbmp);
        qbmp.recycle();
        if (qfaces == null || qfaces.length == 0) {
            android.util.Log.i(TAG, "face rerank: query face embedding null");
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran);
            return base;
        }
        faceCount = qfaces.length;
        Map<String, Float> faceSims = loadFaceCandidates(ctx, featureDao, qfaces, topK * 5);
        candidateCount = faceSims.size();
        ran = true;
        Map<String, Float> baseMap = new LinkedHashMap<>();
        if (base != null) {
            for (SearchResultInternal s : base) {
                baseMap.put(parseMediaKey(s.mediaKey), s.score);
            }
        }
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baseMap.keySet());
        allKeys.addAll(faceSims.keySet());
        if (allKeys.isEmpty()) return base == null ? Collections.emptyList() : base;

        List<SearchResultInternal> fusedList = new ArrayList<>();
        for (String key : allKeys) {
            float baseScore = baseMap.getOrDefault(key, 0f);
            Float fs = faceSims.get(key);
            float faceSim = fs == null ? Float.NaN : fs;
            float fused = baseScore;
            if (!Float.isNaN(faceSim)) {
                if (faceSim < FACE_SIM_SOFT) {
                    fused = baseScore;
                } else if (faceSim < FACE_SIM_STRONG) {
                    fused = 0.8f * baseScore + 0.2f * faceSim;
                } else {
                    fused = FACE_BLEND * faceSim + (1f - FACE_BLEND) * baseScore;
                }
            }
            fusedList.add(new SearchResultInternal(key, fused));
        }
        fusedList.sort((a, b) -> Float.compare(b.score, a.score));
        if (fusedList.size() > topK) {
            List<SearchResultInternal> out = fusedList.subList(0, topK);
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran);
            return out;
        }
        logFaceRerank(perfSession, start, faceCount, candidateCount, ran);
        return fusedList;
    }

    public static Bitmap decodeKeepAspect(Context ctx, Uri uri, int maxSide) {
        try (java.io.InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            Bitmap src = android.graphics.BitmapFactory.decodeStream(is);
            if (src == null) return null;
            int w = src.getWidth();
            int h = src.getHeight();
            int longer = Math.max(w, h);
            if (longer <= maxSide) {
                return src;
            }
            float scale = maxSide / (float) longer;
            int nw = Math.max(1, Math.round(w * scale));
            int nh = Math.max(1, Math.round(h * scale));
            Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(src, nw, nh, true);
            if (scaled != src) src.recycle();
            return scaled;
        } catch (Exception e) {
            android.util.Log.w(TAG, "decodeKeepAspect failed: " + uri, e);
            return null;
        }
    }

    private static Map<String, Float> loadFaceCandidates(Context ctx,
                                                         FeatureDao featureDao,
                                                         float[][] qfaces,
                                                         int topK) {
        Map<String, Float> best = new HashMap<>();
        HnswImageIndex idx = new HnswImageIndex(ctx, "face_hnsw.index");
        boolean usedHnsw = false;
        if (idx.loadIfExists()) {
            usedHnsw = true;
            for (float[] q : qfaces) {
                var res = idx.search(q, topK);
                for (var r : res) {
                    String mediaKey = parseMediaKey(r.item().id());
                    float sim = (float) (1.0 - r.distance());
                    Float cur = best.get(mediaKey);
                    if (cur == null || sim > cur) {
                        best.put(mediaKey, sim);
                    }
                }
            }
        }
        if (!usedHnsw) {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.FACE_SFACE_EMB.getCode());
            if (records != null && !records.isEmpty()) {
                for (FeatureRecord r : records) {
                    if (r.vector == null || r.vector.length == 0) continue;
                    float[] emb = FeatureEncoding.bytesToFloats(r.vector);
                    for (float[] q : qfaces) {
                        float sim = dot(q, emb);
                        Float cur = best.get(r.mediaKey);
                        if (cur == null || sim > cur) {
                            best.put(r.mediaKey, sim);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static String parseMediaKey(String id) {
        int pos = id.indexOf("#f");
        if (pos > 0) {
            return id.substring(0, pos);
        }
        return id;
    }

    private static void logFaceRerank(String session, long startMs, int faceCount, int candidates, boolean ran) {
        double dur = SystemClock.elapsedRealtime() - startMs;
        HashMap<String, Object> extra = new HashMap<>();
        extra.put("query_faces", faceCount);
        extra.put("candidates", candidates);
        extra.put("ran", ran);
        PerfLogger.log("image_search_face_rerank", dur, session, extra);
    }

    private static final class SearchWithIndexResult {
        final List<SearchResultInternal> results;
        final boolean usedHnsw;

        SearchWithIndexResult(List<SearchResultInternal> results, boolean usedHnsw) {
            this.results = results == null ? Collections.emptyList() : results;
            this.usedHnsw = usedHnsw;
        }
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
