package com.example.photos.util;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Map;

/**
 * Lightweight perf logger that emits JSON to logcat for external parsing.
 */
public final class PerfLogger {
    private static final String TAG = "PerfMetric";

    private PerfLogger() {}

    public static void log(String event, double durMs, @Nullable String session) {
        log(event, durMs, session, null);
    }

    public static void log(String event,
                           double durMs,
                           @Nullable String session,
                           @Nullable Map<String, ?> extras) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("event", event);
            obj.put("dur_ms", durMs);
            if (session != null) {
                obj.put("session", session);
            }
            if (extras != null) {
                for (Map.Entry<String, ?> e : extras.entrySet()) {
                    obj.put(e.getKey(), e.getValue());
                }
            }
            Log.i(TAG, obj.toString());
        } catch (Exception ignore) {
            // best-effort logging only
        }
    }
}
