package com.example.photos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FeatureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(FeatureRecord record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(List<FeatureRecord> records);

    @Query("SELECT * FROM features_sparse WHERE featType = :type")
    List<FeatureRecord> getAllByType(int type);

    @Query("SELECT * FROM features_sparse WHERE mediaKey = :key")
    List<FeatureRecord> getByMediaKey(String key);

    @Query("SELECT COUNT(*) FROM features_sparse WHERE mediaKey = :key AND featType = :type")
    int countByKeyAndType(String key, int type);

    @Query("SELECT vector FROM features_sparse WHERE mediaKey = :key AND featType = :type LIMIT 1")
    byte[] vectorForKey(String key, int type);

    @Query("DELETE FROM features_sparse WHERE mediaKey = :key AND featType = :type")
    void deleteByKeyAndType(String key, int type);

    @Query("DELETE FROM features_sparse WHERE mediaKey = :key")
    void deleteByMediaKey(String key);

    @Query("DELETE FROM features_sparse WHERE featType = :type")
    void deleteByType(int type);

    @Query("SELECT vector FROM features_sparse WHERE featType = :type LIMIT :limit OFFSET :offset")
    List<byte[]> getVectorsPaged(int type, int limit, int offset);
}
