package com.example.photos.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 稀疏分类结果表：仅存储已推断过的少量资产的主类别与分数。
 */
@Entity(tableName = "categories_sparse", primaryKeys = {"mediaKey", "category"})
public class CategoryRecord {

    @NonNull
    public String mediaKey;   // contentUri 或 url:<httpUrl>
    @NonNull
    public String category;   // 动态类别字符串（如 PEOPLE/FOOD/TRAVEL/ACG 等）
    public float score;       // 0..1 置信度或规则得分归一化
    public long updatedAt;    // 毫秒时间戳
}


