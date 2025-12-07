package com.example.photos.privacy;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 稀疏隐私覆盖 DAO：仅对有需要的资产建档。
 */
@Dao
public interface PrivacyDao {

    @Query("SELECT * FROM privacy_overrides WHERE mediaKey = :key LIMIT 1")
    PrivacyOverride findByKey(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PrivacyOverride override);

    @Query("DELETE FROM privacy_overrides WHERE mediaKey = :key")
    void delete(String key);

    @Query("SELECT * FROM privacy_overrides")
    List<PrivacyOverride> getAll();
}