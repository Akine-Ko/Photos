package com.example.photos.search;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.photos.db.PhotoAsset;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * DINOv3 ViT-B/16 image encoder (ONNX/ORT) for image-to-image search.
 */
public final class DinoImageEmbedder {

    private static final String TAG = "DinoImageEmbedder";
    private static final String CONFIG_ASSET = "models/dinov3/config.json";
    private static final String DEFAULT_MODEL_ASSET = "models/dinov3/model_quantized.onnx";
    private static final String DEFAULT_MODEL_DATA_ASSET = "models/dinov3/model_quantized.onnx_data";

    private static final Object LOCK = new Object();
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;
    private static OrtEnvironment env;
    private static OrtSession session;
    private static String inputName = "pixel_values";
    private static String outputName = "pooler_output";
    private static int inputSize = 224;
    private static float[] mean = new float[]{0.485f, 0.456f, 0.406f};
    private static float[] std = new float[]{0.229f, 0.224f, 0.225f};
    private static String modelAsset = DEFAULT_MODEL_ASSET;
    private static String modelDataAsset = DEFAULT_MODEL_DATA_ASSET;

    private DinoImageEmbedder() {}

    public static void warmup(Context context) {
        ensureInitialized(context.getApplicationContext());
    }

    public static boolean isReady() {
        return initialized && session != null;
    }

    @Nullable
    public static float[] encode(Context context, @Nullable PhotoAsset asset) {
        if (asset == null || asset.contentUri == null) {
            return null;
        }
        return encode(context, Uri.parse(asset.contentUri));
    }

    @Nullable
    public static float[] encode(Context context, @Nullable Uri uri) {
        ensureInitialized(context.getApplicationContext());
        if (!initialized || session == null || uri == null) {
            return null;
        }
        Bitmap bmp = null;
        try {
            bmp = decodeAndCenterCrop(context, uri, inputSize, inputSize);
            if (bmp == null) return null;
            float[] chw = toCHWNormalized(bmp);
            float[] embedding = runOnnx(chw);
            if (embedding == null) return null;
            l2Normalize(embedding);
            return embedding;
        } catch (Throwable t) {
            Log.w(TAG, "encode failed: " + t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static void ensureInitialized(Context context) {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            try {
                initFailed = false;
                AssetManager am = context.getAssets();
                loadConfig(am);
                File model = copyAssetToCache(context, modelAsset, fileName(modelAsset, "dinov3.onnx"));
                File modelData = copyAssetToCache(context, modelDataAsset, fileName(modelDataAsset, "dinov3.onnx.data"));
                if (model == null || !model.exists()) {
                    throw new IllegalStateException("Model asset missing: " + modelAsset);
                }
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(model.getAbsolutePath(), opts);
                if (modelData != null && modelData.exists()) {
                    // External data is resolved automatically when placed next to the model.
                }
                // Allow overriding input/output names from config.
                if (session != null) {
                    if (inputName == null || inputName.isEmpty()) {
                        inputName = session.getInputNames().iterator().next();
                    }
                    if (outputName == null || outputName.isEmpty()) {
                        outputName = session.getOutputNames().iterator().next();
                    }
                }
                initialized = true;
                Log.i(TAG, "DINOv3 encoder ready. input=" + inputName + " output=" + outputName);
            } catch (Throwable t) {
                initFailed = true;
                Log.w(TAG, "Failed to init DINOv3 encoder: " + t);
            }
        }
    }

    @Nullable
    private static float[] runOnnx(float[] chw) throws Exception {
        if (session == null || env == null) return null;
        FloatBuffer fb = FloatBuffer.wrap(chw);
        OnnxTensor tensor = null;
        OrtSession.Result out = null;
        try {
            tensor = OnnxTensor.createTensor(env, fb, new long[]{1, 3, inputSize, inputSize});
            out = session.run(Collections.singletonMap(inputName, tensor));
            String target = outputName;
            float[][] value = null;
            OnnxValue ov = null;
            if (target != null) {
                try {
                    java.util.Optional<OnnxValue> opt = out.get(target);
                    if (opt.isPresent()) ov = opt.get();
                } catch (Exception ignore) {
                }
            }
            if (ov == null) {
                for (Map.Entry<String, OnnxValue> e : out) { ov = e.getValue(); break; }
            }
            if (ov != null) {
                value = (float[][]) ov.getValue();
            }
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
                modelAsset = "models/dinov3/" + modelFile;
            }
            String modelDataFile = obj.optString("model_data_file", null);
            if (modelDataFile != null && !modelDataFile.isEmpty()) {
                modelDataAsset = "models/dinov3/" + modelDataFile;
            }
            String input = obj.optString("input_name", null);
            if (input != null && !input.isEmpty()) {
                inputName = input;
            }
            String output = obj.optString("output_name", null);
            if (output != null && !output.isEmpty()) {
                outputName = output;
            }
        } catch (Throwable ignore) {
        }
    }

    @Nullable
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

    private static float[] toCHWNormalized(Bitmap bmp) {
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

    private static void l2Normalize(float[] vector) {
        double ss = 0d;
        for (float v : vector) {
            ss += v * v;
        }
        double norm = Math.sqrt(Math.max(ss, 1e-12));
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    @Nullable
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
}
