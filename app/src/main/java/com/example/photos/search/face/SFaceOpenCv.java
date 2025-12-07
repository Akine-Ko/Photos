package com.example.photos.search.face;

import android.content.Context;
import android.graphics.Bitmap;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.opencv.android.Utils;

import java.io.File;

/**
 * YuNet + SFace wrapper using OpenCV (FaceDetectorYN + FaceRecognizerSF).
 */
public final class SFaceOpenCv {
    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;

    static {
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("OpenCV init failed");
        }
    }

    public SFaceOpenCv(Context context) throws Exception {
        FaceModels.Config cfg = FaceModels.config(context);
        File det = FaceModels.ensureAssetToCache(context, "face/face_detection_yunet_2023mar.onnx", "face_detection_yunet_2023mar.onnx");
        File rec = FaceModels.ensureAssetToCache(context, "face/face_recognition_sface_2021dec.onnx", "face_recognition_sface_2021dec.onnx");
        if (det == null || rec == null || !det.exists() || !rec.exists()) {
            throw new IllegalStateException("face models missing");
        }
        // detector input size will be set per image
        detector = FaceDetectorYN.create(det.getAbsolutePath(), "", new Size(320, 320), 0.5f, 0.3f, 5000);
        recognizer = FaceRecognizerSF.create(rec.getAbsolutePath(), "");
    }

    /**
     * Compute embedding of largest face in bitmap. Returns null on failure.
     */
    public float[] embed(Bitmap bmp) {
        float[][] all = embedAll(bmp);
        if (all == null || all.length == 0) return null;
        return all[0];
    }

    /**
     * Compute embeddings for all detected faces, sorted by score desc. Returns null/empty on failure.
     */
    public float[][] embedAll(Bitmap bmp) {
        try {
            Mat mat = new Mat();
            Utils.bitmapToMat(bmp, mat);
            // OpenCV uses BGR, bitmapToMat gives RGBA; drop alpha and convert to BGR
            if (mat.channels() == 4) {
                Mat bgr = new Mat();
                org.opencv.imgproc.Imgproc.cvtColor(mat, bgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR);
                mat.release();
                mat = bgr;
            }
            Mat faces = detectAll(mat);
            if (faces == null) {
                mat.release();
                return null;
            }
            int n = faces.rows();
            float[][] outs = new float[n][];
            for (int i = 0; i < n; i++) {
                Mat one = faces.row(i);
                Mat aligned = new Mat();
                recognizer.alignCrop(mat, one, aligned);
                Mat feat = new Mat();
                recognizer.feature(aligned, feat);
                float[] out = new float[(int) feat.total()];
                feat.get(0, 0, out);
                l2(out);
                outs[i] = out;
                aligned.release();
                feat.release();
                one.release();
            }
            faces.release();
            mat.release();
            return outs;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Detect largest face and return 1x15 Mat expected by alignCrop.
     */
    private Mat detect(Mat bgr) {
        detector.setInputSize(new Size(bgr.cols(), bgr.rows()));
        Mat faces = new Mat();
        detector.detect(bgr, faces);
        if (faces.empty()) {
            faces.release();
            return null;
        }
        // faces: Nx15, last col is score
        int rows = faces.rows();
        int best = 0;
        double bestScore = faces.get(0, 14)[0];
        for (int i = 1; i < rows; i++) {
            double score = faces.get(i, 14)[0];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        Mat out = new Mat(1, 15, CvType.CV_32F);
        faces.row(best).copyTo(out);
        faces.release();
        return out;
    }

    /**
     * Detect all faces, sorted by score desc.
     */
    private Mat detectAll(Mat bgr) {
        detector.setInputSize(new Size(bgr.cols(), bgr.rows()));
        Mat faces = new Mat();
        detector.detect(bgr, faces);
        if (faces.empty()) {
            faces.release();
            return null;
        }
        // sort by score desc
        int rows = faces.rows();
        float[][] arr = new float[rows][15];
        for (int i = 0; i < rows; i++) {
            faces.get(i, 0, arr[i]);
        }
        java.util.Arrays.sort(arr, (a, b) -> Float.compare(b[14], a[14]));
        Mat out = new Mat(rows, 15, CvType.CV_32F);
        for (int i = 0; i < rows; i++) {
            out.put(i, 0, arr[i]);
        }
        faces.release();
        return out;
    }

    private static void l2(float[] v) {
        double ss = 0;
        for (float x : v) ss += x * x;
        double n = Math.sqrt(Math.max(ss, 1e-12));
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
    }
}
