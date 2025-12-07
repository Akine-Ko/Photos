package com.example.photos.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores simple search preferences like result limit.
 */
public final class SearchPreferences {

    private static final String PREF = "search_prefs";
    private static final String KEY_LIMIT = "search_limit";
    private static final int DEFAULT_LIMIT = 4;
    private static final int[] ALLOWED_LIMITS = new int[]{1, 4, 8, 16, 32, 64};

    private SearchPreferences() {}

    public static int getSearchLimit(Context context) {
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int value = sp.getInt(KEY_LIMIT, DEFAULT_LIMIT);
        return sanitizeLimit(value);
    }

    public static void setSearchLimit(Context context, int limit) {
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_LIMIT, sanitizeLimit(limit)).apply();
    }

    public static int[] getAllowedLimits() {
        return ALLOWED_LIMITS.clone();
    }

    private static int sanitizeLimit(int value) {
        for (int allowed : ALLOWED_LIMITS) {
            if (allowed == value) return value;
        }
        return DEFAULT_LIMIT;
    }
}
