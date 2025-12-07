package com.example.photos.search.face;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Lazy loader for face detection / recognition ONNX models from assets/face.
 */
public final class FaceModels {
    private static final String TAG = "FaceModels";
    private static final String CONFIG_ASSET = "face/config.json";

    public static class Config {
        public String detModel;
        public int detW;
        public int detH;
        public float detScore;
        public float detNms;
        public int detKeepK;
        public String recModel;
        public int recSize;
    }

    private static Config config;

    private FaceModels() {}

    public static Config config(Context ctx) {
        if (config != null) return config;
        try (InputStream is = ctx.getAssets().open(CONFIG_ASSET)) {
            String text = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(text);
            JSONObject det = obj.getJSONObject("detector");
            JSONObject rec = obj.getJSONObject("recognizer");
            Config cfg = new Config();
            cfg.detModel = det.optString("model", "version-RFB-320.onnx");
            cfg.detW = det.optInt("input_width", 320);
            cfg.detH = det.optInt("input_height", 240);
            cfg.detScore = (float) det.optDouble("score_thresh", 0.6);
            cfg.detNms = (float) det.optDouble("nms_thresh", 0.3);
            cfg.detKeepK = det.optInt("keep_top_k", 5);
            cfg.recModel = rec.optString("model", "arcfaceresnet100-8.onnx");
            cfg.recSize = rec.optInt("input_size", 112);
            config = cfg;
        } catch (Exception e) {
            Log.w(TAG, "load config failed", e);
            Config cfg = new Config();
            cfg.detModel = "version-RFB-320.onnx";
            cfg.detW = 320;
            cfg.detH = 240;
            cfg.detScore = 0.6f;
            cfg.detNms = 0.3f;
            cfg.detKeepK = 5;
            cfg.recModel = "arcfaceresnet100-8.onnx";
            cfg.recSize = 112;
            config = cfg;
        }
        return config;
    }

    public static File ensureAssetToCache(Context ctx, String assetPath, String outName) {
        try {
            File out = new File(ctx.getCacheDir(), outName);
            if (out.exists() && out.length() > 0) return out;
            AssetManager am = ctx.getAssets();
            try (InputStream is = am.open(assetPath); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "copy asset failed: " + assetPath, e);
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
