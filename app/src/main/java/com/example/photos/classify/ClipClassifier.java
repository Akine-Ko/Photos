package com.example.photos.classify;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.example.photos.db.PhotoAsset;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Minimal CLIP image encoder wrapper for smart classification.
 * <p>
 * Loads a MobileCLIP2 image encoder exported to ONNX together with fixed text embeddings.
 * Classification is cosine similarity between the image embedding and precomputed text embeddings.
 */
public final class ClipClassifier {

    private static final String TAG = "ClipClassifier";
    private static final String DEFAULT_MODEL_ASSET = "models/clip/image_encoder.onnx";
    private static final String DEFAULT_MODEL_EXT_ASSET = "models/clip/image_encoder.onnx.data";
    private static final String LABELS_ASSET = "models/clip/category_keys.txt";
    private static final String TEXT_EMBED_ASSET = "models/clip/text_embeds_f32.bin";
    private static final String CONFIG_ASSET = "models/clip/config.json";

    private static final Object LOCK = new Object();

    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;
    private static OrtEnvironment env;
    private static OrtSession session;
    private static List<String> labels = new ArrayList<>();
    private static float[] textEmbeddings;
    private static int embeddingDim = 0;
    private static int inputSize = 224;
    private static float[] mean = new float[]{0.48145466f, 0.4578275f, 0.40821073f};
    private static float[] std = new float[]{0.26862954f, 0.26130258f, 0.27577711f};
    private static String inputName = "images";
    private static String modelAsset = DEFAULT_MODEL_ASSET;
    private static String modelExtAsset = DEFAULT_MODEL_EXT_ASSET;
    private static final float DEFAULT_THRESHOLD = 0.32f;
    private static final Map<String, Float> thresholds = new HashMap<>();

    private ClipClassifier() {}

    public static Status status(Context context) {
            ensureInitialized(context.getApplicationContext());
            return new Status(initialized, new ArrayList<>(labels), initFailed);
    }

    public static void warmup(Context context) {
        ensureInitialized(context.getApplicationContext());
    }

    private static void ensureInitialized(Context context) {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            try {
                initFailed = false;
                AssetManager am = context.getAssets();
                loadConfig(am);
                labels = loadLabels(am, LABELS_ASSET);
                textEmbeddings = loadEmbeddings(am, TEXT_EMBED_ASSET);
                if (labels.isEmpty() || textEmbeddings == null) {
                    throw new IllegalStateException("Missing labels or text embeddings");
                }
                if (textEmbeddings.length % labels.size() != 0) {
                    throw new IllegalStateException("textEmbeds size mismatch labels");
                }
                embeddingDim = textEmbeddings.length / labels.size();
                for (int i = 0; i < labels.size(); i++) {
                    l2Normalize(textEmbeddings, i * embeddingDim, embeddingDim);
                }
                File model = copyAssetToCache(context, modelAsset, fileName(modelAsset, "image_encoder.onnx"));
                if (model == null || !model.exists()) {
                    throw new IllegalStateException("Model asset not found. Place image_encoder.onnx in assets.");
                }
                copyAssetToCache(context, modelExtAsset, fileName(modelExtAsset, "image_encoder.onnx.data"));
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(model.getAbsolutePath(), new OrtSession.SessionOptions());
                inputName = session.getInputNames().iterator().next();
                initialized = true;
                Log.i(TAG, "ClipClassifier initialized. labels=" + labels.size());
            } catch (Throwable t) {
                initFailed = true;
                Log.w(TAG, "Failed to initialize ClipClassifier: " + t);
            }
        }
    }

    public static Result classify(Context context, PhotoAsset asset) {
        float[] embedding = encodeImageEmbedding(context, asset);
        if (embedding == null) return null;
        return classifyEmbedding(embedding);
    }

    public static float[] encodeImageEmbedding(Context context, PhotoAsset asset) {
        if (asset == null || asset.contentUri == null) return null;
        ensureInitialized(context.getApplicationContext());
        if (!initialized || session == null) return null;
        Bitmap bmp = null;
        try {
            bmp = decodeAndCenterCrop(context, Uri.parse(asset.contentUri), inputSize, inputSize);
            if (bmp == null) return null;
            float[] chw = toCHWClipNormalized(bmp);
            float[] imgEmb = runOnnx(chw);
            if (imgEmb == null) return null;
            l2Normalize(imgEmb, 0, imgEmb.length);
            return imgEmb;
        } catch (Throwable t) {
            Log.w(TAG, "encodeImageEmbedding failed: " + t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    public static Result classifyEmbedding(float[] embedding) {
        Result best = bestLabel(embedding);
        if (best == null) return null;
        float threshold = thresholdForLabel(best.label);
        if (best.score < threshold) {
            return null;
        }
        return best;
    }

    public static Result bestLabel(float[] embedding) {
        if (embedding == null) return null;
        if (!initialized || textEmbeddings == null) return null;
        float bestScore = -Float.MAX_VALUE;
        String bestLabel = null;
        for (int i = 0; i < labels.size(); i++) {
            float s = dot(embedding, textEmbeddings, i * embeddingDim, embeddingDim);
            if (s > bestScore) {
                bestScore = s;
                bestLabel = labels.get(i);
            }
        }
        return bestLabel == null ? null : new Result(bestLabel, bestScore);
    }

    private static float[] runOnnx(float[] chw) throws Exception {
        if (session == null || env == null) return null;
        FloatBuffer fb = FloatBuffer.wrap(chw);
        OnnxTensor tensor = null;
        OrtSession.Result out = null;
        try {
            tensor = OnnxTensor.createTensor(env, fb, new long[]{1, 3, inputSize, inputSize});
            out = session.run(Collections.singletonMap(inputName, tensor));
            float[][] value = (float[][]) out.get(0).getValue();
            return value != null && value.length > 0 ? value[0] : null;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
            if (tensor != null) try { tensor.close(); } catch (Throwable ignore) {}
        }
    }

    private static void loadConfig(AssetManager am) {
        try (InputStream is = am.open(CONFIG_ASSET)) {
            String text = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(text);
            inputSize = obj.optInt("input_size", 224);
            JSONArray meanArr = obj.optJSONArray("mean");
            JSONArray stdArr = obj.optJSONArray("std");
            if (meanArr != null && meanArr.length() == 3) {
                mean = new float[]{(float) meanArr.getDouble(0),
                        (float) meanArr.getDouble(1),
                        (float) meanArr.getDouble(2)};
            }
            if (stdArr != null && stdArr.length() == 3) {
                std = new float[]{(float) stdArr.getDouble(0),
                        (float) stdArr.getDouble(1),
                        (float) stdArr.getDouble(2)};
            }
            String modelFile = obj.optString("model_file", null);
            if (modelFile != null && !modelFile.isEmpty()) {
                modelAsset = "models/clip/" + modelFile;
            } else {
                modelAsset = DEFAULT_MODEL_ASSET;
            }
            String modelExtFile = obj.optString("model_data_file", null);
            if (modelExtFile != null && !modelExtFile.isEmpty()) {
                modelExtAsset = "models/clip/" + modelExtFile;
            } else {
                modelExtAsset = DEFAULT_MODEL_EXT_ASSET;
            }
            thresholds.clear();
            JSONObject th = obj.optJSONObject("thresholds");
            if (th != null) {
                JSONArray names = th.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String key = names.optString(i, null);
                        if (key == null) continue;
                        float value = (float) th.optDouble(key, DEFAULT_THRESHOLD);
                        thresholds.put(key.toUpperCase(Locale.US), value);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static List<String> loadLabels(AssetManager am, String assetName) {
        List<String> out = new ArrayList<>();
        try (InputStream is = am.open(assetName)) {
            String[] lines = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed.toUpperCase(Locale.US));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load labels: " + t);
        }
        return out;
    }

    private static float[] loadEmbeddings(AssetManager am, String assetName) {
        try (InputStream is = am.open(assetName)) {
            byte[] data = readAll(is);
            if (data == null || data.length % 4 != 0) return null;
            FloatBuffer fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] out = new float[fb.remaining()];
            fb.get(out);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load embeddings: " + t);
            return null;
        }
    }

    private static void l2Normalize(float[] vector, int off, int len) {
        double ss = 0d;
        for (int i = 0; i < len; i++) {
            double v = vector[off + i];
            ss += v * v;
        }
        double norm = Math.sqrt(Math.max(ss, 1e-12));
        for (int i = 0; i < len; i++) {
            vector[off + i] = (float) (vector[off + i] / norm);
        }
    }

    private static float dot(float[] a, float[] b, int bOffset, int len) {
        float s = 0f;
        for (int i = 0; i < len; i++) {
            s += a[i] * b[bOffset + i];
        }
        return s;
    }

    private static Bitmap decodeAndCenterCrop(Context context, Uri uri, int tw, int th) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            Bitmap src = BitmapFactory.decodeStream(is);
            if (src == null) return null;
            int w = Math.max(1, src.getWidth());
            int h = Math.max(1, src.getHeight());
            float scale = Math.max(tw / (float) w, th / (float) h);
            int sw = Math.max(1, Math.round(w * scale));
            int sh = Math.max(1, Math.round(h * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
            if (scaled != src) src.recycle();
            int x = Math.max(0, (sw - tw) / 2);
            int y = Math.max(0, (sh - th) / 2);
            Bitmap out = Bitmap.createBitmap(scaled, x, y,
                    Math.min(tw, scaled.getWidth() - x),
                    Math.min(th, scaled.getHeight() - y));
            if (out != scaled) scaled.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "decode failed: " + t);
            return null;
        }
    }

    private static float[] toCHWClipNormalized(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        float[] out = new float[3 * w * h];
        int o0 = 0, o1 = w * h, o2 = 2 * w * h;
        for (int i = 0; i < w * h; i++) {
            int c = px[i];
            float r = ((c >> 16) & 0xFF) / 255f;
            float g = ((c >> 8) & 0xFF) / 255f;
            float b = (c & 0xFF) / 255f;
            out[o0 + i] = (r - mean[0]) / std[0];
            out[o1 + i] = (g - mean[1]) / std[1];
            out[o2 + i] = (b - mean[2]) / std[2];
        }
        return out;
    }

    private static File copyAssetToCache(Context ctx, String assetPath, String outName) {
        if (assetPath == null) return null;
        try {
            AssetManager am = ctx.getAssets();
            File out = new File(ctx.getCacheDir(), outName);
            try (InputStream is = am.open(assetPath); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "copyAssetToCache failed: " + assetPath + " -> " + t);
            return null;
        }
    }

    private static String fileName(String assetPath, String fallback) {
        if (assetPath == null || assetPath.trim().isEmpty()) {
            return fallback;
        }
        int idx = assetPath.lastIndexOf('/');
        if (idx >= 0 && idx < assetPath.length() - 1) {
            return assetPath.substring(idx + 1);
        }
        return assetPath;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static float thresholdForLabel(String label) {
        if (label == null) return DEFAULT_THRESHOLD;
        Float value = thresholds.get(label.toUpperCase(Locale.US));
        return value != null ? value : DEFAULT_THRESHOLD;
    }

    public static final class Result {
        public final String label;
        public final float score;

        public Result(String label, float score) {
            this.label = label;
            this.score = score;
        }

        @Override
        public String toString() {
            return "Result{" + label + ":" + score + "}";
        }
    }

    public static final class Status {
        public final boolean ready;
        public final List<String> categories;
        public final boolean failed;

        public Status(boolean ready, List<String> categories, boolean failed) {
            this.ready = ready;
            this.categories = categories;
            this.failed = failed;
        }
    }
}
