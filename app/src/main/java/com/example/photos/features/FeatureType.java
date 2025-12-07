package com.example.photos.features;

/**
 * 端侧轻量特征类型定义。
 */
public enum FeatureType {
    COLOR_HIST_64(1),      // 64 维颜色直方图（RGB 4 档量化）
    AHASH_64(2),           // 64bit 感知哈希（均值哈希）
    CLIP_IMAGE_EMB(3),     // MobileCLIP 图像嵌入（float32 向量）
    DINO_IMAGE_EMB(4),     // DINOv3 图像嵌入（float32 向量）
    FACE_SFACE_EMB(5);     // SFace 人脸嵌入（float32 向量，多脸，每脸一条记录）

    private final int code;

    FeatureType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static FeatureType from(int code) {
        for (FeatureType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return COLOR_HIST_64;
    }
}
