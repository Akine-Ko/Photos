package com.example.photos.features;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 轻量特征计算：颜色直方图(64) 与 aHash(64bit)。
 */
public class FeatureExtractor {

    public static byte[] extractColorHist64(Context context, String contentUri) {
        Bitmap bmp = decodeDownsampled(context, contentUri, 128);
        if (bmp == null) return new byte[0];
        float[] hist = new float[64];
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int c : pixels) {
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int ri = r >> 6; // 0..3
            int gi = g >> 6;
            int bi = b >> 6;
            int idx = (ri << 4) | (gi << 2) | bi; // r*16 + g*4 + b
            hist[idx] += 1f;
        }
        // L1 归一化
        float sum = (float) (w * h);
        if (sum <= 0f) sum = 1f;
        for (int i = 0; i < hist.length; i++) hist[i] /= sum;
        bmp.recycle();
        return floatsToBytes(hist);
    }

    public static byte[] extractAHash64(Context context, String contentUri) {
        Bitmap bmp = decodeDownsampled(context, contentUri, 8); // 8x8
        if (bmp == null) return new byte[0];
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, 8, 8, true);
        if (scaled != bmp) bmp.recycle();
        int[] gray = new int[64];
        int sum = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = scaled.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                int v = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                gray[y * 8 + x] = v;
                sum += v;
            }
        }
        scaled.recycle();
        int mean = sum / 64;
        long hash = 0L;
        for (int i = 0; i < 64; i++) {
            if (gray[i] >= mean) hash |= (1L << i);
        }
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(hash);
        return buf.array();
    }

    public static int hamming64(byte[] a, byte[] b) {
        if (a == null || b == null || a.length < 8 || b.length < 8) return Integer.MAX_VALUE;
        long la = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN).getLong();
        long lb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
        long x = la ^ lb;
        return Long.bitCount(x);
    }

    public static double l2(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Double.MAX_VALUE;
        double s = 0d;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    public static float[] bytesToFloats(byte[] data) {
        if (data == null || data.length == 0) return new float[0];
        Float[] boxed = new Float[0];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int n = data.length / 4;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = buf.getFloat();
        return out;
    }

    private static byte[] floatsToBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) buf.putFloat(f);
        return buf.array();
    }

    private static Bitmap decodeDownsampled(Context context, String contentUri, int targetSize) {
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse(contentUri);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(cr, uri);
                return android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    android.util.Size size = info.getSize();
                    int maxSide = Math.max(size.getWidth(), size.getHeight());
                    int sample = 1;
                    while ((maxSide / sample) > targetSize) sample <<= 1;
                    decoder.setTargetSampleSize(Math.max(1, sample));
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else {
                // Fallback for older devices
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream is = cr.openInputStream(uri)) { BitmapFactory.decodeStream(is, null, o); }
                int w = o.outWidth, h = o.outHeight;
                if (w <= 0 || h <= 0) return null;
                int maxSide = Math.max(w, h);
                int sample = 1;
                while ((maxSide / sample) > targetSize) sample <<= 1;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = Math.max(1, sample);
                o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
                try (InputStream is2 = cr.openInputStream(uri)) {
                    return BitmapFactory.decodeStream(is2, null, o2);
                }
            }
        } catch (Throwable ignore) {
            return null;
        }
    }
}
