package com.example.photos.model;

import android.content.Context;
import android.os.Build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks NNAPI stability per-process. Failures disable NNAPI for the remainder of the process,
 * but state is not persisted so future app launches will try NNAPI again.
 */
public final class NnapiController {
    private static final int FAIL_THRESHOLD = 2;
    private static final Map<String, Integer> failCounts = new HashMap<>();
    private static final Set<String> disabledKeys = new HashSet<>();

    private NnapiController() {}

    public static boolean shouldUseNnapi(Context ctx, String key) {
        if (Build.VERSION.SDK_INT < 27) return false;
        synchronized (NnapiController.class) {
            return !disabledKeys.contains(key);
        }
    }

    public static void recordFailure(Context ctx, String key) {
        synchronized (NnapiController.class) {
            int fails = failCounts.getOrDefault(key, 0) + 1;
            failCounts.put(key, fails);
            if (fails >= FAIL_THRESHOLD) {
                disabledKeys.add(key);
            }
        }
    }

    public static void recordSuccess(Context ctx, String key) {
        synchronized (NnapiController.class) {
            if (disabledKeys.contains(key)) {
                return; // stay disabled for the rest of this process
            }
            failCounts.remove(key);
        }
    }
}
