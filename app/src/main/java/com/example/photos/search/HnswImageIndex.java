package com.example.photos.search;

import android.content.Context;
import android.util.Log;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HNSW index built on DINO image embeddings.
 */
public final class HnswImageIndex {
    private static final String TAG = "HnswImageIndex";
    private final String indexFileName;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 200;
    private static final int EF_SEARCH = 64;

    private final Object lock = new Object();
    private HnswIndex<String, float[], VectorItem, Float> index;
    private final File indexFile;
    private final File legacyCacheFile;

    public HnswImageIndex(Context ctx, String indexFileName) {
        this.indexFileName = indexFileName;
        this.indexFile = new File(ctx.getFilesDir(), indexFileName);
        this.legacyCacheFile = new File(ctx.getCacheDir(), indexFileName);
    }

    public boolean isReady() {
        synchronized (lock) {
            return index != null;
        }
    }

    public void clear() {
        synchronized (lock) {
            index = null;
        }
        deleteIfExists(indexFile);
        deleteIfExists(legacyCacheFile);
    }

    public void save() {
        synchronized (lock) {
            if (index == null) return;
            try (FileOutputStream fos = new FileOutputStream(indexFile)) {
                index.save(fos);
            } catch (Exception e) {
                Log.w(TAG, "save hnsw failed", e);
            }
        }
    }

    public boolean loadIfExists() {
        if (indexFile.exists()) {
            return loadFrom(indexFile);
        }
        if (legacyCacheFile.exists()) {
            boolean loaded = loadFrom(legacyCacheFile);
            if (loaded) {
                save();
                deleteIfExists(legacyCacheFile);
            }
            return loaded;
        }
        return false;
    }

    public void build(List<VectorItem> items, int dim) {
        HnswIndex.Builder<float[], Float> builder = HnswIndex.newBuilder(
                dim, DistanceFunctions.FLOAT_COSINE_DISTANCE, items.size() + 10)
                .withM(M)
                .withEf(EF_SEARCH)
                .withEfConstruction(EF_CONSTRUCTION);
        HnswIndex<String, float[], VectorItem, Float> idx = builder.build();
        for (VectorItem item : items) {
            idx.add(item);
        }
        idx.setEf(EF_SEARCH);
        synchronized (lock) {
            index = idx;
        }
        Log.i(TAG, "hnsw built, size=" + items.size() + " dim=" + dim);
    }

    public List<SearchResult<VectorItem, Float>> search(float[] query, int topK) {
        HnswIndex<String, float[], VectorItem, Float> idx;
        synchronized (lock) {
            idx = index;
        }
        if (idx == null) return Collections.emptyList();
        try {
            return idx.findNearest(query, topK);
        } catch (Exception e) {
            Log.w(TAG, "hnsw search failed", e);
            return Collections.emptyList();
        }
    }

    private boolean loadFrom(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            HnswIndex<String, float[], VectorItem, Float> loaded =
                    HnswIndex.load(fis);
            loaded.setEf(EF_SEARCH);
            synchronized (lock) {
                index = loaded;
            }
            Log.i(TAG, "loaded hnsw, size=" + loaded.size());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "load hnsw failed", e);
            return false;
        }
    }

    private static void deleteIfExists(File file) {
        if (file == null || !file.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static final class VectorItem implements Item<String, float[]>, java.io.Serializable {
        private final String id;
        private final float[] vec;
        public VectorItem(String id, float[] vec) {
            this.id = id;
            this.vec = vec;
        }
        @Override public String id() { return id; }
        @Override public float[] vector() { return vec; }
        @Override public int dimensions() { return vec.length; }
    }

    public static List<VectorItem> fromRecords(List<com.example.photos.db.FeatureRecord> records, int dim, boolean includeFaceId) {
        if (records == null || records.isEmpty()) return Collections.emptyList();
        List<VectorItem> items = new ArrayList<>(records.size());
        for (com.example.photos.db.FeatureRecord r : records) {
            if (r.vector == null || r.vector.length == 0) continue;
            float[] v = com.example.photos.features.FeatureEncoding.bytesToFloats(r.vector);
            if (v.length != dim) continue;
            String id;
            if (includeFaceId) {
                id = r.mediaKey + "#f" + r.faceId;
            } else {
                id = r.mediaKey;
            }
            items.add(new VectorItem(id, v));
        }
        return items;
    }
}
