package com.example.photos.ui.albums;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight persistence for user-created albums (names only).
 */
final class CustomAlbumsStore {

    private static final String PREFS = "custom_albums_store";
    private static final String KEY_NAMES = "names";

    private CustomAlbumsStore() {}

    static List<String> loadAll(Context context) {
        if (context == null) return Collections.emptyList();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_NAMES, Collections.emptySet());
        return new ArrayList<>(set == null ? Collections.emptySet() : set);
    }

    static boolean add(Context context, String name) {
        if (context == null || name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_NAMES, Collections.emptySet());
        Set<String> copy = new HashSet<>(set == null ? Collections.emptySet() : set);
        boolean added = copy.add(trimmed);
        if (!added) return false;
        prefs.edit().putStringSet(KEY_NAMES, copy).apply();
        return true;
    }
}
