package com.example.photos.features;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Helper methods to convert between float vectors and byte arrays for Room storage.
 */
public final class FeatureEncoding {
    private FeatureEncoding() {}

    public static byte[] floatsToBytes(float[] values) {
        if (values == null || values.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] bytesToFloats(byte[] data) {
        if (data == null || data.length == 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = data.length / 4;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }
}
