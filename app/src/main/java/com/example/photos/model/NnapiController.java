package com.example.photos.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Tracks NNAPI stability. If consecutive failures exceed threshold, future runs stay on CPU.
 */
public final class NnapiController {
    private static final String PREF = "nnapi_prefs";
    private static final String KEY_DISABLED_PREFIX = "disabled_";
    private static final String KEY_FAIL_PREFIX = "fail_";
    private static final int FAIL_THRESHOLD = 2;

    private NnapiController() {}

    public static boolean shouldUseNnapi(Context ctx, String key) {
        if (Build.VERSION.SDK_INT < 27) return false;
        SharedPreferences sp = prefs(ctx);
        return !sp.getBoolean(KEY_DISABLED_PREFIX + key, false);
    }

    public static void recordFailure(Context ctx, String key) {
        SharedPreferences sp = prefs(ctx);
        int fails = sp.getInt(KEY_FAIL_PREFIX + key, 0) + 1;
        SharedPreferences.Editor e = sp.edit();
        e.putInt(KEY_FAIL_PREFIX + key, fails);
        if (fails >= FAIL_THRESHOLD) {
            e.putBoolean(KEY_DISABLED_PREFIX + key, true);
        }
        e.apply();
    }

    public static void recordSuccess(Context ctx, String key) {
        SharedPreferences sp = prefs(ctx);
        if (sp.getBoolean(KEY_DISABLED_PREFIX + key, false)) {
            return; // stay disabled until user clears data
        }
        sp.edit().putInt(KEY_FAIL_PREFIX + key, 0).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
