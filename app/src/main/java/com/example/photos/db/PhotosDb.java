package com.example.photos.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room Database：包含媒体资产与特征索引。
 */
@Database(
        entities = {
                PhotoAsset.class,
                FeatureRecord.class,
                CategoryRecord.class
        },
        version = 7,
        exportSchema = true
)
public abstract class PhotosDb extends RoomDatabase {

    public abstract PhotoDao photoDao();
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
