package com.example.photos.search;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class ClipTextEncoder {

    private static final String TAG = "ClipTextEncoder";
    private static final String DEFAULT_MODEL_ASSET = "models/clip/vit-b-16.txt.fp16.onnx";
    private static final String INPUT_NAME = "text";

    private static final Object LOCK = new Object();

    private static volatile boolean initialized = false;
    private static OrtEnvironment env;
    private static OrtSession session;
    private static ClipTextTokenizer tokenizer;
    private static String modelAsset = DEFAULT_MODEL_ASSET;
    private static String modelDataAsset = null;

    private ClipTextEncoder() {}

    public static void setModelAssets(String assetPath, String dataPath) {
        synchronized (LOCK) {
            String resolvedAsset = (assetPath == null || assetPath.trim().isEmpty())
                    ? DEFAULT_MODEL_ASSET : assetPath;
            String resolvedData = (dataPath != null && !dataPath.trim().isEmpty()) ? dataPath : null;
            boolean changed = !resolvedAsset.equals(modelAsset)
                    || ((modelDataAsset == null && resolvedData != null)
                    || (modelDataAsset != null && !modelDataAsset.equals(resolvedData)));
            if (changed) {
                modelAsset = resolvedAsset;
                modelDataAsset = resolvedData;
                if (session != null) {
                    try {
                        session.close();
                    } catch (Exception ignore) { }
                    session = null;
                }
                initialized = false;
            }
        }
    }

    public static float[] encode(Context context, String text) {
        ensureInitialized(context.getApplicationContext());
        if (!initialized || session == null || tokenizer == null) {
            return null;
        }
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            int[] tokens = tokenizer.tokenize(text);
            long[] input = new long[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                input[i] = tokens[i];
            }
            LongBuffer buffer = LongBuffer.wrap(input);
            OnnxTensor tensor = OnnxTensor.createTensor(env, buffer, new long[]{1, tokens.length});
            OrtSession.Result result = session.run(Collections.singletonMap(INPUT_NAME, tensor));
            float[][] value = (float[][]) result.get(0).getValue();
            float[] embedding = value != null && value.length > 0 ? value[0] : null;
            if (embedding != null) {
                normalize(embedding);
            }
            result.close();
            tensor.close();
            return embedding;
        } catch (Exception e) {
            Log.w(TAG, "encode failed", e);
            return null;
        }
    }

    private static void ensureInitialized(Context context) {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            try {
                env = OrtEnvironment.getEnvironment();
                File modelFile = copyAsset(context, modelAsset, fileName(modelAsset));
                if (modelDataAsset != null) {
                    copyExtraIfExists(context, modelDataAsset, fileName(modelDataAsset));
                } else {
                    copyExtraIfExists(context, modelAsset + ".extra_file", fileName(modelAsset + ".extra_file"));
                }
                session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                tokenizer = new ClipTextTokenizer(context);
                Log.i(TAG, "Text encoder initialized. inputs=" + session.getInputNames() + " outputs=" + session.getOutputNames());
                initialized = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize text encoder", e);
            }
        }
    }

    private static void normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    private static File copyAsset(Context context, String assetPath, String name) throws Exception {
        AssetManager am = context.getAssets();
        File out = new File(context.getCacheDir(), name);
        try (InputStream is = am.open(assetPath); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        return out;
    }

    private static void copyExtraIfExists(Context context, String assetPath, String name) {
        AssetManager am = context.getAssets();
        try (InputStream is = am.open(assetPath); FileOutputStream fos = new FileOutputStream(new File(context.getCacheDir(), name))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        } catch (Exception ignore) {
            // optional extra file
        }
    }

    private static String fileName(String assetPath) {
        if (assetPath == null) return "model.onnx";
        int idx = assetPath.lastIndexOf('/');
        if (idx >= 0 && idx < assetPath.length() - 1) {
            return assetPath.substring(idx + 1);
        }
        return assetPath;
    }
}
