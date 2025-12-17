package com.example.photos.ui.albums;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight persistence for user-created albums (names with creation order).
 */
final class CustomAlbumsStore {

    private static final String PREFS = "custom_albums_store";
    private static final String KEY_NAMES = "names"; // legacy
    private static final String KEY_ORDERED = "names_ordered";

    private CustomAlbumsStore() {}

    static List<String> loadAll(Context context) {
        List<AlbumMeta> metas = loadAllWithMeta(context);
        List<String> names = new ArrayList<>();
        for (AlbumMeta m : metas) {
            names.add(m.name);
        }
        return names;
    }

    static List<AlbumMeta> loadAllWithMeta(Context context) {
        if (context == null) return Collections.emptyList();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ORDERED, null);
        List<AlbumMeta> result = new ArrayList<>();
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    String name = obj.optString("name", "").trim();
                    long ts = obj.optLong("createdAt", System.currentTimeMillis());
                    if (!name.isEmpty()) {
                        result.add(new AlbumMeta(name, ts));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!result.isEmpty()) return result;
        // Fallback for legacy unordered set
        Set<String> set = prefs.getStringSet(KEY_NAMES, Collections.emptySet());
        Set<String> safe = set == null ? Collections.emptySet() : set;
        long base = System.currentTimeMillis();
        for (String n : safe) {
            if (n == null) continue;
            String trimmed = n.trim();
            if (!trimmed.isEmpty()) {
                result.add(new AlbumMeta(trimmed, base++)); // preserve deterministic order
            }
        }
        return result;
    }

    static boolean add(Context context, String name) {
        if (context == null || name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<AlbumMeta> metas = loadAllWithMeta(context);
        for (AlbumMeta m : metas) {
            if (m != null && trimmed.equalsIgnoreCase(m.name)) {
                return false;
            }
        }
        metas.add(new AlbumMeta(trimmed, System.currentTimeMillis()));
        persist(prefs, metas);
        return true;
    }

    static void remove(Context context, String name) {
        if (context == null || name == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<AlbumMeta> metas = loadAllWithMeta(context);
        boolean changed = false;
        for (int i = metas.size() - 1; i >= 0; i--) {
            AlbumMeta m = metas.get(i);
            if (m != null && name.equalsIgnoreCase(m.name)) {
                metas.remove(i);
                changed = true;
            }
        }
        if (changed) {
            persist(prefs, metas);
        }
    }

    private static void persist(@NonNull SharedPreferences prefs, @NonNull List<AlbumMeta> metas) {
        try {
            JSONArray arr = new JSONArray();
            for (AlbumMeta m : metas) {
                if (m == null || m.name == null || m.name.trim().isEmpty()) continue;
                JSONObject obj = new JSONObject();
                obj.put("name", m.name.trim());
                obj.put("createdAt", m.createdAt);
                arr.put(obj);
            }
            prefs.edit()
                    .putString(KEY_ORDERED, arr.toString())
                    .putStringSet(KEY_NAMES, new HashSet<String>()) // clear legacy slot
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static final class AlbumMeta {
        final String name;
        final long createdAt;

        AlbumMeta(String name, long createdAt) {
            this.name = name;
            this.createdAt = createdAt;
        }
    }
}
