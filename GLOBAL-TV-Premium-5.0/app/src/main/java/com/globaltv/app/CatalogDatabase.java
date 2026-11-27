package com.globaltv.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {CatalogEntryEntity.class}, version = 2, exportSchema = false)
public abstract class CatalogDatabase extends RoomDatabase {
    private static volatile CatalogDatabase INSTANCE;
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE catalog_entries ADD COLUMN tvgLanguage TEXT NOT NULL DEFAULT ''");
        }
    };

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
                    ).addMigrations(MIGRATION_1_2).build();
                    INSTANCE = instance;
                }
            }
        }
        return instance;
    }
}
