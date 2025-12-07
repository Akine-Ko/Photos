package com.example.photos.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * 稀疏特征表：轻量特征存储（本地检索用）
 * 复合主键：(mediaKey, featType, faceId)
 * 对非人脸特征，faceId 固定为 0；对人脸特征（FACE_SFACE_EMB），faceId 为 0..N-1
 */
@Entity(tableName = "features_sparse",
        primaryKeys = {"mediaKey", "featType", "faceId"},
        indices = {@Index(value = {"featType"})})
public class FeatureRecord {
    @NonNull
    public String mediaKey;   // contentUri 或 url:<httpUrl>
    public int featType;      // 见FeatureType.getCode()
    public int faceId;        // 非人脸特征固定 0；人脸特征对应的人脸序号
    public byte[] vector;     // BLOB：float32 数组或 8 字节哈希
    public long updatedAt;    // 毫秒时间戳
}
