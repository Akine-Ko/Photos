package com.example.photos.sync;

import android.content.Context;
import android.util.Log;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.photos.R;
import com.example.photos.classify.ClipClassifier;
import com.example.photos.db.FeatureDao;
import com.example.photos.db.FeatureRecord;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;
import com.example.photos.features.FeatureEncoding;
import com.example.photos.features.FeatureType;
import com.example.photos.search.DinoImageEmbedder;
import com.example.photos.search.HnswImageIndex;
import com.example.photos.search.face.SFaceOpenCv;
import com.example.photos.util.PerfLogger;

import java.util.List;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Worker that precomputes Chinese-CLIP + DINOv3 image embeddings and stores them in features_sparse.
 */
public class ClipEmbeddingWorker extends Worker {

    private static final String TAG = "ClipEmbeddingWorker";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LIMIT = "limit";
    private static final String KEY_FORCE = "force";
    private static final String MODE_FULL = "full";
    private static final String MODE_RECENT = "recent";
    private static final String MODE_SAMPLE = "sample";
    public static final String UNIQUE_RECENT = "clip_embed_recent";
    public static final String UNIQUE_FULL = "clip_embed_full";
    public static final String UNIQUE_SAMPLE = "clip_embed_sample";
    public static final String TAG_EMBED = "clip_embed";
    private static final int NOTIFICATION_ID = 10001;

    private int progressProcessed = 0;
    private int progressTotal = 0;
    private int clipCount = 0;
    private int dinoCount = 0;
    private int faceCount = 0;

    public ClipEmbeddingWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String mode = getInputData().getString(KEY_MODE);
        boolean full = MODE_FULL.equals(mode);
        int limit = getInputData().getInt(KEY_LIMIT, 120);
        boolean force = getInputData().getBoolean(KEY_FORCE, false);
        String perfSession = "embed-" + (mode == null ? "unknown" : mode) + "-" + System.currentTimeMillis();
        Context app = getApplicationContext();
        Log.i(TAG, "Start embedding mode=" + mode + " full=" + full + " limit=" + limit + " force=" + force);
        if (!startForeground()) {
            Log.e(TAG, "Foreground start failed; failing fast for debug");
            return Result.failure();
        }
        ClipClassifier.warmup(app);
        DinoImageEmbedder.warmup(app);
        PhotosDb db = PhotosDb.get(app);
        PhotoDao photoDao = db.photoDao();
        FeatureDao featureDao = db.featureDao();
        try {
            if (full) {
                runFull(photoDao, featureDao, force, perfSession);
            } else {
                runRecent(photoDao, featureDao, limit, force, perfSession);
            }
            return Result.success();
        } catch (Throwable t) {
            Log.e(TAG, "Embedding run failed", t);
            return Result.failure();
        }
    }

    private void runRecent(PhotoDao photoDao, FeatureDao featureDao, int limit, boolean force, String perfSession) {
        if (limit <= 0) limit = 120;
        List<PhotoAsset> latest = photoDao.queryLatest(limit);
        Log.i(TAG, "runRecent fetched=" + (latest == null ? 0 : latest.size()));
        if (isStopped()) return;
        resetProgress(latest == null ? 0 : latest.size());
        resetCounters();
        encodeBatch(latest, featureDao, force, perfSession);
        if (isStopped()) return;
        rebuildHnsw(featureDao, perfSession);
        if (isStopped()) return;
        rebuildFaceHnsw(featureDao, perfSession);
        if (isStopped()) return;
        rebuildClipHnsw(featureDao, perfSession);
        Log.i(TAG, "Embedding recent done processed=" + progressProcessed + "/" + progressTotal
                + " clip=" + clipCount + " dino=" + dinoCount + " face=" + faceCount
                + " stopped=" + isStopped());
    }

    private void runFull(PhotoDao photoDao, FeatureDao featureDao, boolean force, String perfSession) {
        final int PAGE = 200;
        int offset = 0;
        int total = photoDao.countAll();
        Log.i(TAG, "runFull total=" + total + " page=" + PAGE);
        resetProgress(total);
        resetCounters();
        while (!isStopped()) {
            List<PhotoAsset> page = photoDao.queryPaged(PAGE, offset);
            if (page == null || page.isEmpty()) break;
            encodeBatch(page, featureDao, force, perfSession);
            offset += PAGE;
        }
        if (isStopped()) return;
        rebuildHnsw(featureDao, perfSession);
        if (isStopped()) return;
        rebuildFaceHnsw(featureDao, perfSession);
        if (isStopped()) return;
        rebuildClipHnsw(featureDao, perfSession);
        Log.i(TAG, "Embedding full done processed=" + progressProcessed + "/" + progressTotal
                + " clip=" + clipCount + " dino=" + dinoCount + " face=" + faceCount
                + " stopped=" + isStopped());
    }

    private void encodeBatch(List<PhotoAsset> assets, FeatureDao featureDao, boolean force, String perfSession) {
        if (assets == null || assets.isEmpty()) return;
        for (PhotoAsset asset : assets) {
            if (isStopped()) return;
            incrementProgress();
            if (asset == null || asset.contentUri == null) continue;
            boolean needClip = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.CLIP_IMAGE_EMB.getCode()) == 0;
            boolean needDino = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.DINO_IMAGE_EMB.getCode()) == 0;
            boolean needFace = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.FACE_SFACE_EMB.getCode()) == 0;
            Log.d(TAG, "asset=" + asset.contentUri
                    + " needClip=" + needClip
                    + " needDino=" + needDino
                    + " needFace=" + needFace);
            if (!needClip && !needDino && !needFace) continue;
            if (force) {
                if (needClip) {
                    featureDao.deleteByKeyAndType(asset.contentUri, FeatureType.CLIP_IMAGE_EMB.getCode());
                }
                if (needDino) {
                    featureDao.deleteByKeyAndType(asset.contentUri, FeatureType.DINO_IMAGE_EMB.getCode());
                }
                if (needFace) {
                    featureDao.deleteByKeyAndType(asset.contentUri, FeatureType.FACE_SFACE_EMB.getCode());
                }
            }
            if (needClip) {
                if (isStopped()) return;
                float[] embedding = null;
                long t0 = SystemClock.elapsedRealtime();
                try {
                    embedding = ClipClassifier.encodeImageEmbedding(getApplicationContext(), asset);
                } catch (Throwable t) {
                    Log.w(TAG, "clip encode failed: " + asset.contentUri, t);
                }
                double dur = SystemClock.elapsedRealtime() - t0;
                if (embedding != null) {
                    FeatureRecord record = new FeatureRecord();
                    record.mediaKey = asset.contentUri;
                    record.featType = FeatureType.CLIP_IMAGE_EMB.getCode();
                    record.faceId = 0;
                    record.vector = FeatureEncoding.floatsToBytes(embedding);
                    record.updatedAt = System.currentTimeMillis() / 1000L;
                    featureDao.upsert(record);
                    clipCount++;
                    HashMap<String, Object> extra = new HashMap<>();
                    extra.put("media", asset.contentUri);
                    PerfLogger.log("clip_encode", dur, perfSession, extra);
                }
            }
            if (needDino) {
                if (isStopped()) return;
                float[] embedding = null;
                long t0 = SystemClock.elapsedRealtime();
                try {
                    embedding = DinoImageEmbedder.encode(getApplicationContext(), asset);
                } catch (Throwable t) {
                    Log.w(TAG, "dino encode failed: " + asset.contentUri, t);
                }
                double dur = SystemClock.elapsedRealtime() - t0;
                if (embedding != null) {
                    FeatureRecord record = new FeatureRecord();
                    record.mediaKey = asset.contentUri;
                    record.featType = FeatureType.DINO_IMAGE_EMB.getCode();
                    record.faceId = 0;
                    record.vector = FeatureEncoding.floatsToBytes(embedding);
                    record.updatedAt = System.currentTimeMillis() / 1000L;
                    featureDao.upsert(record);
                    dinoCount++;
                    HashMap<String, Object> extra = new HashMap<>();
                    extra.put("media", asset.contentUri);
                    PerfLogger.log("dino_encode", dur, perfSession, extra);
                }
            }
            if (needFace) {
                if (isStopped()) return;
                com.example.photos.search.face.SFaceOpenCv sface;
                try {
                    sface = new com.example.photos.search.face.SFaceOpenCv(getApplicationContext());
                } catch (Exception e) {
                    continue;
                }
                if (isStopped()) return;
                android.graphics.Bitmap bmp = com.example.photos.search.ImageSearchEngine.decodeKeepAspect(getApplicationContext(), android.net.Uri.parse(asset.contentUri), 960);
                if (bmp != null) {
                    long tFace = SystemClock.elapsedRealtime();
                    float[][] faces = sface.embedAll(bmp);
                    double durFace = SystemClock.elapsedRealtime() - tFace;
                    bmp.recycle();
                    int faceCnt = faces == null ? 0 : faces.length;
                    HashMap<String, Object> extraFace = new HashMap<>();
                    extraFace.put("media", asset.contentUri);
                    extraFace.put("faces", faceCnt);
                    PerfLogger.log("face_encode", durFace, perfSession, extraFace);
                    if (faces != null && faces.length > 0) {
                        int fid = 0;
                        for (float[] f : faces) {
                            if (isStopped()) return;
                            if (f == null || f.length == 0) continue;
                            FeatureRecord r = new FeatureRecord();
                            r.mediaKey = asset.contentUri;
                            r.featType = FeatureType.FACE_SFACE_EMB.getCode();
                            r.faceId = fid++;
                            r.vector = FeatureEncoding.floatsToBytes(f);
                            r.updatedAt = System.currentTimeMillis() / 1000L;
                            featureDao.upsert(r);
                            faceCount++;
                        }
                    }
                }
            }
        }
    }

    private void rebuildHnsw(FeatureDao featureDao, String perfSession) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.DINO_IMAGE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "dino_hnsw.index");
            long t0 = SystemClock.elapsedRealtime();
            idx.build(HnswImageIndex.fromRecords(records, dim, false), dim);
            idx.save();
            double dur = SystemClock.elapsedRealtime() - t0;
            HashMap<String, Object> extra = new HashMap<>();
            extra.put("size", records.size());
            extra.put("dim", dim);
            PerfLogger.log("hnsw_build_dino", dur, perfSession, extra);
            Log.i(TAG, "HNSW rebuilt size=" + records.size() + " dim=" + dim);
        } catch (Throwable t) {
            Log.w(TAG, "HNSW rebuild failed", t);
        }
    }

    private void rebuildFaceHnsw(FeatureDao featureDao, String perfSession) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.FACE_SFACE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "face_hnsw.index");
            long t0 = SystemClock.elapsedRealtime();
            idx.build(HnswImageIndex.fromRecords(records, dim, true), dim);
            idx.save();
            double dur = SystemClock.elapsedRealtime() - t0;
            HashMap<String, Object> extra = new HashMap<>();
            extra.put("size", records.size());
            extra.put("dim", dim);
            PerfLogger.log("hnsw_build_face", dur, perfSession, extra);
            Log.i(TAG, "Face HNSW rebuilt size=" + records.size() + " dim=" + dim);
        } catch (Throwable t) {
            Log.w(TAG, "Face HNSW rebuild failed", t);
        }
    }

    private void rebuildClipHnsw(FeatureDao featureDao, String perfSession) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.CLIP_IMAGE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "clip_hnsw.index");
            long t0 = SystemClock.elapsedRealtime();
            idx.build(HnswImageIndex.fromRecords(records, dim, false), dim);
            idx.save();
            double dur = SystemClock.elapsedRealtime() - t0;
            HashMap<String, Object> extra = new HashMap<>();
            extra.put("size", records.size());
            extra.put("dim", dim);
            PerfLogger.log("hnsw_build_clip", dur, perfSession, extra);
            Log.i(TAG, "CLIP HNSW rebuilt size=" + records.size() + " dim=" + dim);
        } catch (Throwable t) {
            Log.w(TAG, "CLIP HNSW rebuild failed", t);
        }
    }

    public static void enqueueRecent(Context context) {
        enqueue(context, newRecentWorkRequest(120, false), UNIQUE_RECENT, ExistingWorkPolicy.KEEP);
    }

    public static void enqueueFull(Context context) {
        enqueue(context, newFullWorkRequest(), UNIQUE_FULL, ExistingWorkPolicy.KEEP);
    }

    public static void enqueueSample(Context context, int limit, boolean force) {
        enqueue(context, newSampleWorkRequest(limit, force), UNIQUE_SAMPLE, ExistingWorkPolicy.REPLACE);
    }

    public static OneTimeWorkRequest newFullWorkRequest() {
        return buildRequest(MODE_FULL, 0, false, UNIQUE_FULL);
    }

    public static OneTimeWorkRequest newRecentWorkRequest(int limit, boolean force) {
        return buildRequest(MODE_RECENT, limit, force, UNIQUE_RECENT);
    }

    public static OneTimeWorkRequest newSampleWorkRequest(int limit, boolean force) {
        return buildRequest(MODE_SAMPLE, limit, force, UNIQUE_SAMPLE);
    }

    private static void enqueue(Context context,
                                OneTimeWorkRequest request,
                                String uniqueName,
                                ExistingWorkPolicy policy) {
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(uniqueName, policy, request);
    }

    private static OneTimeWorkRequest buildRequest(String mode,
                                                   int limit,
                                                   boolean force,
                                                   String tagAlias) {
        Data.Builder builder = new Data.Builder().putString(KEY_MODE, mode);
        if (limit > 0) {
            builder.putInt(KEY_LIMIT, limit);
        }
        builder.putBoolean(KEY_FORCE, force);
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(ClipEmbeddingWorker.class)
                .setInputData(builder.build())
                .addTag(TAG_EMBED);
        if (tagAlias != null && !tagAlias.isEmpty()) {
            requestBuilder.addTag(tagAlias);
        }
        return requestBuilder.build();
    }

    @Override
    public void onStopped() {
        super.onStopped();
        Log.w(TAG, "onStopped() called, isStopped=" + isStopped());
    }

    private boolean startForeground() {
        try {
            setForegroundAsync(ForegroundHelper.create(
                    getApplicationContext(),
                    getApplicationContext().getString(R.string.notification_embedding_title),
                    getApplicationContext().getString(R.string.notification_embedding_text),
                    NOTIFICATION_ID,
                    0,
                    progressTotal
            )).get(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start foreground notification", e);
            return false;
        }
    }

    private void resetProgress(int total) {
        progressTotal = total;
        progressProcessed = 0;
        updateProgress();
        updateForeground(progressProcessed, progressTotal);
    }

    private void resetCounters() {
        clipCount = 0;
        dinoCount = 0;
        faceCount = 0;
    }

    private void incrementProgress() {
        progressProcessed++;
        if (progressTotal > 0 && progressProcessed > progressTotal) {
            progressTotal = progressProcessed;
        }
        updateProgress();
        updateForeground(progressProcessed, progressTotal);
    }

    private void updateProgress() {
        setProgressAsync(new Data.Builder()
                .putInt("processed", progressProcessed)
                .putInt("total", progressTotal)
                .build());
    }

    private void updateForeground(int processed, int total) {
        try {
            setForegroundAsync(ForegroundHelper.create(
                    getApplicationContext(),
                    getApplicationContext().getString(R.string.notification_embedding_title),
                    getApplicationContext().getString(R.string.notification_embedding_text),
                    NOTIFICATION_ID,
                    processed,
                    total
            ));
        } catch (Exception e) {
            Log.w(TAG, "Failed to update foreground notification", e);
        }
    }
}
