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
import java.util.HashSet;
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
    private static volatile HnswImageIndex dinoHnsw;
    private static volatile HnswImageIndex faceHnsw;

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
        long queryStart = SystemClock.elapsedRealtime();
        QueryEmbeddingResult queryResult = loadOrEncodeQuery(app, featureDao, queryAsset);
        double queryMs = SystemClock.elapsedRealtime() - queryStart;
        HashMap<String, Object> queryExtra = new HashMap<>();
        queryExtra.put("cache_hit", queryResult.cacheHit);
        PerfLogger.log("image_query_embedding", queryMs, perfSession, queryExtra);
        float[] query = queryResult.embedding;
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
        annExtra.put("index_available", indexed.usedHnsw);
        annExtra.put("query_cache_hit", queryResult.cacheHit);
        annExtra.put("limit", topK);
        annExtra.put("results", ordered == null ? 0 : ordered.size());
        PerfLogger.log("image_search_ann", annMs, perfSession, annExtra);
        ordered = rerankByFace(app, featureDao, ordered, queryAsset, topK, perfSession);

        List<SearchResult> out = new ArrayList<>();
        PhotoDao photoDao = db.photoDao();
        Set<String> seenCanonicalKeys = new HashSet<>();
        String queryCanonicalKey = canonicalIdentityKey(queryAsset.contentUri);
        boolean queryAdded = false;
        for (int i = 0; i < ordered.size(); i++) {
            SearchResultInternal internal = ordered.get(i);
            Photo photo = mapToPhoto(photoDao, internal.mediaKey);
            if (photo != null) {
                String internalCanonicalKey = canonicalIdentityKey(internal.mediaKey);
                // Deduplicate same photo even if URI forms differ (media URI vs document URI).
                if (!seenCanonicalKeys.add(internalCanonicalKey)) {
                    continue;
                }
                // Keep at most one self result for query image.
                if (queryCanonicalKey.equals(internalCanonicalKey)) {
                    if (queryAdded) {
                        continue;
                    }
                    queryAdded = true;
                }
                out.add(new SearchResult(photo, internal.score));
                int rank = out.size() - 1;
                android.util.Log.i(TAG, "top[" + rank + "] " + internal.mediaKey + " score=" + internal.score);
                if (out.size() >= topK) {
                    break;
                }
            }
        }
        double totalMs = SystemClock.elapsedRealtime() - totalStart;
        HashMap<String, Object> totalExtra = new HashMap<>();
        totalExtra.put("used_hnsw", indexed.usedHnsw);
        totalExtra.put("index_available", indexed.usedHnsw);
        totalExtra.put("query_cache_hit", queryResult.cacheHit);
        totalExtra.put("limit", topK);
        totalExtra.put("results", out.size());
        PerfLogger.log("image_search_total", totalMs, perfSession, totalExtra);
        return out;
    }

    private static SearchWithIndexResult searchWithIndex(Context app, FeatureDao featureDao, float[] query, int topK) {
        HnswImageIndex hnsw = getDinoHnsw(app);
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

    private static QueryEmbeddingResult loadOrEncodeQuery(Context context, FeatureDao featureDao, PhotoAsset asset) {
        byte[] cached = featureDao.vectorForKey(asset.contentUri, FeatureType.DINO_IMAGE_EMB.getCode());
        if (cached != null && cached.length > 0) {
            return new QueryEmbeddingResult(FeatureEncoding.bytesToFloats(cached), true);
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
        return new QueryEmbeddingResult(embedding, false);
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
        Boolean faceIndexUsedHnsw = null;
        SFaceOpenCv recognizer;
        try {
            recognizer = new SFaceOpenCv(ctx);
        } catch (Exception e) {
            android.util.Log.w(TAG, "face pipeline init failed", e);
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran, faceIndexUsedHnsw);
            return base;
        }
        Bitmap qbmp = decodeKeepAspect(ctx, Uri.parse(queryAsset.contentUri), 960);
        if (qbmp == null) {
            android.util.Log.i(TAG, "face rerank: query bitmap null");
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran, faceIndexUsedHnsw);
            return base;
        }
        float[][] qfaces = recognizer.embedAll(qbmp);
        qbmp.recycle();
        if (qfaces == null || qfaces.length == 0) {
            android.util.Log.i(TAG, "face rerank: query face embedding null");
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran, faceIndexUsedHnsw);
            return base;
        }
        faceCount = qfaces.length;
        FaceCandidatesResult faceCandidates = loadFaceCandidates(ctx, featureDao, qfaces, topK * 5);
        Map<String, Float> faceSims = faceCandidates.scores;
        candidateCount = faceSims.size();
        faceIndexUsedHnsw = faceCandidates.usedHnsw;
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
            logFaceRerank(perfSession, start, faceCount, candidateCount, ran, faceIndexUsedHnsw);
            return out;
        }
        logFaceRerank(perfSession, start, faceCount, candidateCount, ran, faceIndexUsedHnsw);
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

    private static FaceCandidatesResult loadFaceCandidates(Context ctx,
                                                           FeatureDao featureDao,
                                                           float[][] qfaces,
                                                           int topK) {
        Map<String, Float> best = new HashMap<>();
        HnswImageIndex idx = getFaceHnsw(ctx);
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
        return new FaceCandidatesResult(best, usedHnsw);
    }

    private static HnswImageIndex getDinoHnsw(Context context) {
        if (dinoHnsw == null) {
            synchronized (ImageSearchEngine.class) {
                if (dinoHnsw == null) {
                    dinoHnsw = new HnswImageIndex(context.getApplicationContext(), "dino_hnsw.index");
                }
            }
        }
        return dinoHnsw;
    }

    private static HnswImageIndex getFaceHnsw(Context context) {
        if (faceHnsw == null) {
            synchronized (ImageSearchEngine.class) {
                if (faceHnsw == null) {
                    faceHnsw = new HnswImageIndex(context.getApplicationContext(), "face_hnsw.index");
                }
            }
        }
        return faceHnsw;
    }

    private static String parseMediaKey(String id) {
        int pos = id.indexOf("#f");
        if (pos > 0) {
            return id.substring(0, pos);
        }
        return id;
    }

    private static String canonicalIdentityKey(String rawKey) {
        String key = parseMediaKey(rawKey == null ? "" : rawKey);
        if (key.isEmpty()) {
            return key;
        }
        try {
            Uri uri = Uri.parse(key);
            String last = uri.getLastPathSegment();
            if (last != null && !last.isEmpty()) {
                String decoded = Uri.decode(last);
                int colon = decoded.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < decoded.length()) {
                    String tail = decoded.substring(colon + 1);
                    if (isDigits(tail)) {
                        return tail;
                    }
                }
                if (isDigits(decoded)) {
                    return decoded;
                }
            }
            String whole = Uri.decode(key);
            int colon = whole.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < whole.length()) {
                String tail = whole.substring(colon + 1);
                if (isDigits(tail)) {
                    return tail;
                }
            }
        } catch (Throwable ignore) {
            // fallback below
        }
        return key;
    }

    private static boolean isDigits(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static void logFaceRerank(String session,
                                      long startMs,
                                      int faceCount,
                                      int candidates,
                                      boolean ran,
                                      @Nullable Boolean usedHnsw) {
        double dur = SystemClock.elapsedRealtime() - startMs;
        HashMap<String, Object> extra = new HashMap<>();
        extra.put("query_faces", faceCount);
        extra.put("candidates", candidates);
        extra.put("ran", ran);
        if (usedHnsw != null) {
            extra.put("used_hnsw", usedHnsw);
            extra.put("index_available", usedHnsw);
        }
        PerfLogger.log("image_search_face_rerank", dur, session, extra);
    }

    private static final class QueryEmbeddingResult {
        final float[] embedding;
        final boolean cacheHit;

        QueryEmbeddingResult(float[] embedding, boolean cacheHit) {
            this.embedding = embedding;
            this.cacheHit = cacheHit;
        }
    }

    private static final class FaceCandidatesResult {
        final Map<String, Float> scores;
        final boolean usedHnsw;

        FaceCandidatesResult(Map<String, Float> scores, boolean usedHnsw) {
            this.scores = scores == null ? Collections.emptyMap() : scores;
            this.usedHnsw = usedHnsw;
        }
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
