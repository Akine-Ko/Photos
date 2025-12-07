package com.example.photos.sync;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
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
import com.example.photos.db.CategoryDao;
import com.example.photos.db.CategoryRecord;
import com.example.photos.db.PhotoAsset;
import com.example.photos.db.PhotoDao;
import com.example.photos.db.PhotosDb;
import com.example.photos.db.FeatureDao;
import com.example.photos.features.FeatureEncoding;
import com.example.photos.features.FeatureType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that runs CLIP inference and populates categories_sparse.
 */
public class ClassificationWorker extends Worker {

    private static final String TAG = "ClassificationWorker";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LIMIT = "limit";
    private static final String KEY_REPROCESS = "reprocess";
    private static final String MODE_FULL = "full";
    private static final String MODE_RECENT = "recent";
    private static final String MODE_SAMPLE = "sample";
    public static final String UNIQUE_RECENT = "clip_classify_recent";
    public static final String UNIQUE_FULL = "clip_classify_full";
    public static final String UNIQUE_SAMPLE = "clip_classify_sample";
    public static final String TAG_CLASSIFY = "clip_classify";
    private static final int NOTIFICATION_ID = 10002;
    private int progressTotal = 0;
    private int progressProcessed = 0;

    public ClassificationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String mode = getInputData().getString(KEY_MODE);
        boolean full = MODE_FULL.equals(mode);
        int limit = getInputData().getInt(KEY_LIMIT, 120);
        boolean reprocess = getInputData().getBoolean(KEY_REPROCESS, false);
        Context app = getApplicationContext();
        startForeground();
        ClipClassifier.warmup(app);
        ClipClassifier.Status status = ClipClassifier.status(app);
        if (!status.ready) {
            Log.w(TAG, "ClipClassifier not ready; retry later");
            return Result.retry();
        }
        PhotosDb db = PhotosDb.get(app);
        PhotoDao photoDao = db.photoDao();
        CategoryDao categoryDao = db.categoryDao();
        FeatureDao featureDao = db.featureDao();
        try {
        if (full) {
            runFullClassification(photoDao, categoryDao, featureDao);
        } else {
            runRecentClassification(photoDao, categoryDao, featureDao, limit, reprocess);
        }
            return Result.success();
        } catch (Throwable t) {
            Log.w(TAG, "Classification run failed: " + t);
            return Result.retry();
        }
    }

    private void runRecentClassification(PhotoDao photoDao,
                                         CategoryDao categoryDao,
                                         FeatureDao featureDao,
                                         int limit,
                                         boolean reprocess) {
        if (limit <= 0) limit = 120;
        List<PhotoAsset> latest = photoDao.queryLatest(limit);
        if (isStopped()) return;
        int total = latest == null ? 0 : latest.size();
        resetProgress(total);
        classifyBatch(latest, categoryDao, featureDao, reprocess);
    }

    private void runFullClassification(PhotoDao photoDao,
                                       CategoryDao categoryDao,
                                       FeatureDao featureDao) {
        final int PAGE = 200;
        int offset = 0;
        int total = photoDao.countAll();
        resetProgress(total);
        while (!isStopped()) {
            List<PhotoAsset> page = photoDao.queryPaged(PAGE, offset);
            if (page == null || page.isEmpty()) break;
            classifyBatch(page, categoryDao, featureDao, true);
            offset += PAGE;
        }
        updateProgress(progressProcessed, progressTotal);
    }

    private int classifyBatch(List<PhotoAsset> assets,
                               CategoryDao categoryDao,
                               FeatureDao featureDao,
                               boolean reprocessExisting) {
        if (assets == null || assets.isEmpty()) return 0;
        List<CategoryRecord> pending = new ArrayList<>();
        int visited = 0;
        for (PhotoAsset asset : assets) {
            if (isStopped()) return visited;
            if (asset == null || asset.contentUri == null) continue;
            visited++;
            incrementProgress();
            if (!reprocessExisting) {
                CategoryRecord existing = categoryDao.findByKey(asset.contentUri);
                if (existing != null) continue;
            } else {
                categoryDao.deleteByMediaKey(asset.contentUri);
            }
            float[] embedding = loadEmbedding(asset.contentUri, featureDao);
            if (isStopped()) return visited;
            if (embedding == null || embedding.length == 0) continue;
            String assetLabel = describeAsset(asset);
            ClipClassifier.Result result = ClipClassifier.bestLabel(embedding);
            if (result == null) {
                Log.i(TAG, "No label scored for " + assetLabel);
                continue;
            }
            float threshold = ClipClassifier.thresholdForLabel(result.label);
            Log.i(TAG, "Score for " + assetLabel + " -> "
                    + result.label + " = " + result.score + " (threshold " + threshold + ")");
            if (result.score < threshold) {
                continue;
            }
            CategoryRecord record = new CategoryRecord();
            record.mediaKey = asset.contentUri;
            record.category = result.label;
            record.score = result.score;
            record.updatedAt = System.currentTimeMillis() / 1000L;
            pending.add(record);
        }
        if (!pending.isEmpty()) {
            categoryDao.upsert(pending);
            Log.i(TAG, "Classified " + pending.size() + " assets.");
        }
        return visited;
    }

    public static void enqueueRecent(Context context) {
        enqueue(context, newRecentWorkRequest(120, false), UNIQUE_RECENT, ExistingWorkPolicy.KEEP);
    }

    public static void enqueueFull(Context context) {
        enqueue(context, newFullWorkRequest(), UNIQUE_FULL, ExistingWorkPolicy.KEEP);
    }

    public static void enqueueSample(Context context, int limit) {
        enqueue(context, newSampleWorkRequest(limit), UNIQUE_SAMPLE, ExistingWorkPolicy.REPLACE);
    }

    public static OneTimeWorkRequest newFullWorkRequest() {
        return buildRequest(MODE_FULL, 0, true, UNIQUE_FULL);
    }

    public static OneTimeWorkRequest newRecentWorkRequest(int limit, boolean reprocess) {
        return buildRequest(MODE_RECENT, limit, reprocess, UNIQUE_RECENT);
    }

    public static OneTimeWorkRequest newSampleWorkRequest(int limit) {
        return buildRequest(MODE_SAMPLE, limit, true, UNIQUE_SAMPLE);
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
                                                   boolean reprocess,
                                                   String tagAlias) {
        Data.Builder builder = new Data.Builder().putString(KEY_MODE, mode);
        if (limit > 0) {
            builder.putInt(KEY_LIMIT, limit);
        }
        builder.putBoolean(KEY_REPROCESS, reprocess);
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(ClassificationWorker.class)
                .setInputData(builder.build())
                .addTag(TAG_CLASSIFY);
        if (tagAlias != null && !tagAlias.isEmpty()) {
            requestBuilder.addTag(tagAlias);
        }
        return requestBuilder.build();
    }

    private void startForeground() {
        try {
            setForegroundAsync(ForegroundHelper.create(
                    getApplicationContext(),
                    getApplicationContext().getString(R.string.notification_classify_title),
                    getApplicationContext().getString(R.string.notification_classify_text),
                    NOTIFICATION_ID
            )).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start foreground notification", e);
        }
    }

    private void updateProgress(int processed, int total) {
        setProgressAsync(new Data.Builder()
                .putInt("processed", processed)
                .putInt("total", total)
                .build());
    }

    private void resetProgress(int total) {
        progressTotal = total;
        progressProcessed = 0;
        updateProgress(progressProcessed, progressTotal);
    }

    private void incrementProgress() {
        progressProcessed++;
        if (progressTotal > 0 && progressProcessed > progressTotal) {
            progressTotal = progressProcessed;
        }
        updateProgress(progressProcessed, progressTotal);
    }

    private float[] loadEmbedding(String mediaKey, FeatureDao featureDao) {
        byte[] cached = featureDao.vectorForKey(mediaKey, FeatureType.CLIP_IMAGE_EMB.getCode());
        if (cached == null || cached.length == 0) {
            Log.d(TAG, "No cached embedding for " + mediaKey);
            return null;
        }
        return FeatureEncoding.bytesToFloats(cached);
    }

    private String describeAsset(PhotoAsset asset) {
        if (asset == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(asset.displayName)) {
            sb.append(asset.displayName);
        } else {
            String last = extractLastSegment(asset.contentUri);
            if (!TextUtils.isEmpty(last)) {
                sb.append(last);
            }
        }
        if (!TextUtils.isEmpty(asset.contentUri)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("(").append(asset.contentUri).append(")");
        } else if (asset.id > 0) {
            sb.append("#").append(asset.id);
        }
        return sb.length() > 0 ? sb.toString() : "unknown";
    }

    private String extractLastSegment(String uriText) {
        if (TextUtils.isEmpty(uriText)) return "";
        try {
            Uri uri = Uri.parse(uriText);
            String last = uri.getLastPathSegment();
            return last != null ? last : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
