package com.example.photos.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room Database：包含媒体资产、隐私覆盖与稀疏特征索引。
 */
@Database(
        entities = {
                PhotoAsset.class,
                com.example.photos.privacy.PrivacyOverride.class,
                FeatureRecord.class,
                CategoryRecord.class
        },
        version = 6,
        exportSchema = true
)
public abstract class PhotosDb extends RoomDatabase {

    public abstract PhotoDao photoDao();
    public abstract com.example.photos.privacy.PrivacyDao privacyDao();
    public abstract FeatureDao featureDao();
    public abstract CategoryDao categoryDao();

    private static volatile PhotosDb INSTANCE;

    public static PhotosDb get(Context context) {
        if (INSTANCE == null) {
            synchronized (PhotosDb.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    PhotosDb.class,
                                    "photos.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
