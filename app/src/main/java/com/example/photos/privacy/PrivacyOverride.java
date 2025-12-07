package com.example.photos.privacy;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 仅存储“有特殊隐私需求”的少量资产，未在此表中的资产按默认策略处理。
 */
@Entity(tableName = "privacy_overrides")
public class PrivacyOverride {

    /**
     * 使用内容键标识媒体：优先 contentUri（如 content://...），其次自定义前缀，如 url:<httpUrl>
     */
    @PrimaryKey
    @NonNull
    public String mediaKey;

    /** 0=PUBLIC, 1=SENSITIVE, 2=SECRET */
    public int policy;

    /** SECRET 时的加密文件本地路径（可空） */
    public String localEncryptedPath;

    /** 更新时间戳（毫秒） */
    public long lastUpdated;
}