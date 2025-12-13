package com.example.photos.search;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class ClipTextEncoder {

    private static final String TAG = "ClipTextEncoder";
    private static final String MODEL_NAME = "models/clip/MobileCLIP2-S2_text_encoder.onnx";
    private static final String MODEL_DATA_NAME = "models/clip/MobileCLIP2-S2_text_encoder.onnx.data";

    private static final Object LOCK = new Object();

    private static volatile boolean initialized = false;
    private static OrtEnvironment env;
    private static OrtSession session;
    private static ClipTextTokenizer tokenizer;
    private static String inputName = "text";

    private ClipTextEncoder() {}

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
            OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor));
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
                File modelFile = copyAsset(context, MODEL_NAME, "MobileCLIP2-S2_text_encoder.onnx");
                copyAsset(context, MODEL_DATA_NAME, "MobileCLIP2-S2_text_encoder.onnx.data");
                session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                tokenizer = new ClipTextTokenizer(context);
                try {
                    List<String> names = new ArrayList<>(session.getInputNames());
                    if (!names.isEmpty()) {
                        inputName = names.get(0);
                    }
                    Log.i(TAG, "Text encoder initialized. input=" + inputName + " outputs=" + session.getOutputNames());
                } catch (Throwable ignore) {}
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
}
