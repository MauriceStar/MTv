package com.globaltv.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {CatalogEntryEntity.class}, version = 1, exportSchema = false)
public abstract class CatalogDatabase extends RoomDatabase {
    private static volatile CatalogDatabase INSTANCE;

    public abstract CatalogDao catalogDao();

    public static CatalogDatabase getInstance(Context context) {
        CatalogDatabase instance = INSTANCE;
        if (instance == null) {
            synchronized (CatalogDatabase.class) {
                instance = INSTANCE;
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            CatalogDatabase.class,
                            "mtv_catalog.db"
                    ).build();
                    INSTANCE = instance;
                }
            }
        }
        return instance;
    }
}
