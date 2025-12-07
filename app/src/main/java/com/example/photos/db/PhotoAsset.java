package com.example.photos.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 媒体库图片的持久化实体（端侧入库）。
 */
@Entity(tableName = "photo_assets",
        indices = {
                @Index(value = {"dateModified"}),
                @Index(value = {"bucketId"})
        })
public class PhotoAsset {

    /** MediaStore 的 _ID 作为主键，稳定可复用 */
    @PrimaryKey
    public long id;

    /** content:// 形式的唯一标识 URI 字符串 */
    @NonNull
    public String contentUri;

    public String displayName;
    public long dateTaken;      // 拍摄时间（可能为 0）
    public long dateModified;   // 修改时间（增量同步基准）
    public String mimeType;
    public long size;
    public int width;
    public int height;
    public String bucketId;     // 相册/目录 ID
    public String bucketName;   // 相册/目录 名称
    public int orientation;     // 方向角度
}
