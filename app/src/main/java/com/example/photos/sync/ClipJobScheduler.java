package com.example.photos.sync;

import android.content.Context;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/**
 * Helper that chains embedding + classification workers so they run sequentially.
 */
public final class ClipJobScheduler {

    public static final String PIPELINE_FULL = "clip_pipeline_full";
    public static final String PIPELINE_SAMPLE = "clip_pipeline_sample";
    public static final String PIPELINE_RECENT = "clip_pipeline_recent";

    private ClipJobScheduler() {
    }

    public static void enqueueFullPipeline(Context context) {
        enqueue(context,
                ClipEmbeddingWorker.newFullWorkRequest(),
                ClassificationWorker.newFullWorkRequest(),
                PIPELINE_FULL,
                ExistingWorkPolicy.REPLACE);
    }

    public static void enqueueSamplePipeline(Context context, int limit) {
        enqueue(context,
                ClipEmbeddingWorker.newSampleWorkRequest(limit, true),
                ClassificationWorker.newSampleWorkRequest(limit),
                PIPELINE_SAMPLE,
                ExistingWorkPolicy.REPLACE);
    }

    public static void enqueueRecentPipeline(Context context, int limit) {
        enqueue(context,
                ClipEmbeddingWorker.newRecentWorkRequest(limit, false),
                ClassificationWorker.newRecentWorkRequest(limit, false),
                PIPELINE_RECENT,
                ExistingWorkPolicy.KEEP);
    }

    private static void enqueue(Context context,
                                OneTimeWorkRequest embedding,
                                OneTimeWorkRequest classification,
                                String uniqueName,
                                ExistingWorkPolicy policy) {
        WorkManager.getInstance(context.getApplicationContext())
                .beginUniqueWork(uniqueName, policy, embedding)
                .then(classification)
                .enqueue();
    }
}
