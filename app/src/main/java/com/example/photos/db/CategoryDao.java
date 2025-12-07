package com.example.photos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CategoryRecord record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(List<CategoryRecord> records);

    @Query("SELECT * FROM categories_sparse WHERE mediaKey = :key LIMIT 1")
    CategoryRecord findByKey(String key);

    @Query("SELECT category, COUNT(*) AS cnt FROM categories_sparse GROUP BY category")
    List<CategoryCount> countsByCategory();

    @Query("SELECT mediaKey FROM categories_sparse WHERE category = :category LIMIT :limit OFFSET :offset")
    List<String> mediaKeysByCategory(String category, int limit, int offset);

    @Query("SELECT mediaKey FROM categories_sparse WHERE category = :category LIMIT 1")
    String anyKeyForCategory(String category);

    @Query("SELECT category FROM categories_sparse WHERE mediaKey = :mediaKey")
    List<String> categoriesFor(String mediaKey);

    @Query("SELECT mediaKey FROM categories_sparse WHERE category = :category ORDER BY updatedAt DESC LIMIT 1")
    String latestKeyForCategory(String category);

    @Query("DELETE FROM categories_sparse")
    void clearAll();

    @Query("DELETE FROM categories_sparse WHERE category = :category")
    void deleteByCategory(String category);

    @Query("DELETE FROM categories_sparse WHERE category LIKE :pattern")
    void deleteByPattern(String pattern);

    @Query("DELETE FROM categories_sparse WHERE mediaKey = :mediaKey")
    void deleteByMediaKey(String mediaKey);

    @Query("UPDATE categories_sparse SET category = :to WHERE category = :from")
    void renameCategory(String from, String to);

    class CategoryCount {
        public String category;
        public int cnt;
    }
}
