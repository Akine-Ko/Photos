package com.example.photos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.lifecycle.LiveData;

import java.util.List;

/**
 * Room DAO：提供增量 upsert 与查询能力。
 */
@Dao
public interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(List<PhotoAsset> assets);

    @Query("DELETE FROM photo_assets WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    PhotoAsset findById(long id);

    @Query("SELECT * FROM photo_assets ORDER BY dateModified DESC LIMIT 2000")
    LiveData<List<PhotoAsset>> observeAll();

    @Query("SELECT * FROM photo_assets ORDER BY dateModified DESC LIMIT :limit OFFSET :offset")
    List<PhotoAsset> queryPaged(int limit, int offset);

    @Query("SELECT * FROM photo_assets")
    List<PhotoAsset> getAll();

    @Query("SELECT * FROM photo_assets ORDER BY dateModified DESC LIMIT :limit")
    List<PhotoAsset> queryLatest(int limit);

    @Query("SELECT MAX(dateModified) FROM photo_assets")
    Long maxDateModified();

    @Query("SELECT COUNT(*) FROM photo_assets")
    int countAll();

    @Query("SELECT * FROM photo_assets WHERE contentUri = :uri LIMIT 1")
    PhotoAsset findByContentUri(String uri);
}
