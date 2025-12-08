package com.example.photos.sync;

import android.content.Context;
import android.util.Log;

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

import java.util.List;
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
        Context app = getApplicationContext();
        if (!startForeground()) {
            Log.w(TAG, "Foreground start failed; will retry");
            return Result.retry();
        }
        ClipClassifier.warmup(app);
        DinoImageEmbedder.warmup(app);
        PhotosDb db = PhotosDb.get(app);
        PhotoDao photoDao = db.photoDao();
        FeatureDao featureDao = db.featureDao();
        try {
            if (full) {
                runFull(photoDao, featureDao, force);
            } else {
                runRecent(photoDao, featureDao, limit, force);
            }
            return Result.success();
        } catch (Throwable t) {
            Log.w(TAG, "Embedding run failed: " + t);
            return Result.retry();
        }
    }

    private void runRecent(PhotoDao photoDao, FeatureDao featureDao, int limit, boolean force) {
        if (limit <= 0) limit = 120;
        List<PhotoAsset> latest = photoDao.queryLatest(limit);
        if (isStopped()) return;
        resetProgress(latest == null ? 0 : latest.size());
        encodeBatch(latest, featureDao, force);
        if (isStopped()) return;
        rebuildHnsw(featureDao);
        if (isStopped()) return;
        rebuildFaceHnsw(featureDao);
        if (isStopped()) return;
        rebuildClipHnsw(featureDao);
    }

    private void runFull(PhotoDao photoDao, FeatureDao featureDao, boolean force) {
        final int PAGE = 200;
        int offset = 0;
        int total = photoDao.countAll();
        resetProgress(total);
        while (!isStopped()) {
            List<PhotoAsset> page = photoDao.queryPaged(PAGE, offset);
            if (page == null || page.isEmpty()) break;
            encodeBatch(page, featureDao, force);
            offset += PAGE;
        }
        if (isStopped()) return;
        rebuildHnsw(featureDao);
        if (isStopped()) return;
        rebuildFaceHnsw(featureDao);
        if (isStopped()) return;
        rebuildClipHnsw(featureDao);
    }

    private void encodeBatch(List<PhotoAsset> assets, FeatureDao featureDao, boolean force) {
        if (assets == null || assets.isEmpty()) return;
        for (PhotoAsset asset : assets) {
            if (isStopped()) return;
            incrementProgress();
            if (asset == null || asset.contentUri == null) continue;
            boolean needClip = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.CLIP_IMAGE_EMB.getCode()) == 0;
            boolean needDino = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.DINO_IMAGE_EMB.getCode()) == 0;
            boolean needFace = force || featureDao.countByKeyAndType(asset.contentUri, FeatureType.FACE_SFACE_EMB.getCode()) == 0;
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
                float[] embedding = ClipClassifier.encodeImageEmbedding(getApplicationContext(), asset);
                if (embedding != null) {
                    FeatureRecord record = new FeatureRecord();
                    record.mediaKey = asset.contentUri;
                    record.featType = FeatureType.CLIP_IMAGE_EMB.getCode();
                    record.faceId = 0;
                    record.vector = FeatureEncoding.floatsToBytes(embedding);
                    record.updatedAt = System.currentTimeMillis() / 1000L;
                    featureDao.upsert(record);
                }
            }
            if (needDino) {
                if (isStopped()) return;
                float[] embedding = DinoImageEmbedder.encode(getApplicationContext(), asset);
                if (embedding != null) {
                    FeatureRecord record = new FeatureRecord();
                    record.mediaKey = asset.contentUri;
                    record.featType = FeatureType.DINO_IMAGE_EMB.getCode();
                    record.faceId = 0;
                    record.vector = FeatureEncoding.floatsToBytes(embedding);
                    record.updatedAt = System.currentTimeMillis() / 1000L;
                    featureDao.upsert(record);
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
                    float[][] faces = sface.embedAll(bmp);
                    bmp.recycle();
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
                        }
                    }
                }
            }
        }
    }

    private void rebuildHnsw(FeatureDao featureDao) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.DINO_IMAGE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "dino_hnsw.index");
            idx.build(HnswImageIndex.fromRecords(records, dim, false), dim);
            idx.save();
            Log.i(TAG, "HNSW rebuilt size=" + records.size() + " dim=" + dim);
        } catch (Throwable t) {
            Log.w(TAG, "HNSW rebuild failed", t);
        }
    }

    private void rebuildFaceHnsw(FeatureDao featureDao) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.FACE_SFACE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "face_hnsw.index");
            idx.build(HnswImageIndex.fromRecords(records, dim, true), dim);
            idx.save();
            Log.i(TAG, "Face HNSW rebuilt size=" + records.size() + " dim=" + dim);
        } catch (Throwable t) {
            Log.w(TAG, "Face HNSW rebuild failed", t);
        }
    }

    private void rebuildClipHnsw(FeatureDao featureDao) {
        try {
            List<FeatureRecord> records = featureDao.getAllByType(FeatureType.CLIP_IMAGE_EMB.getCode());
            if (records == null || records.isEmpty()) return;
            float[] first = FeatureEncoding.bytesToFloats(records.get(0).vector);
            int dim = first == null ? 0 : first.length;
            if (dim <= 0) return;
            if (isStopped()) return;
            HnswImageIndex idx = new HnswImageIndex(getApplicationContext(), "clip_hnsw.index");
            idx.build(HnswImageIndex.fromRecords(records, dim, false), dim);
            idx.save();
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

    private boolean startForeground() {
        try {
            setForegroundAsync(ForegroundHelper.create(
                    getApplicationContext(),
                    getApplicationContext().getString(R.string.notification_embedding_title),
                    getApplicationContext().getString(R.string.notification_embedding_text),
                    NOTIFICATION_ID
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
    }

    private void incrementProgress() {
        progressProcessed++;
        if (progressTotal > 0 && progressProcessed > progressTotal) {
            progressTotal = progressProcessed;
        }
        updateProgress();
    }

    private void updateProgress() {
        setProgressAsync(new Data.Builder()
                .putInt("processed", progressProcessed)
                .putInt("total", progressTotal)
                .build());
    }
}
