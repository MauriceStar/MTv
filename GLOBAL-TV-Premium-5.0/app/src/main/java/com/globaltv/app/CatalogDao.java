package com.globaltv.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<CatalogEntryEntity> entries);

    @Query("SELECT * FROM catalog_entries ORDER BY name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    List<CatalogEntryEntity> page(int limit, int offset);

    @Query("SELECT * FROM catalog_entries WHERE normalizedName LIKE '%' || :query || '%' OR normalizedGroup LIKE '%' || :query || '%' OR tvgId LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    List<CatalogEntryEntity> search(String query, int limit, int offset);

    @Query("SELECT COUNT(*) FROM catalog_entries")
    int count();

    @Query("SELECT * FROM catalog_entries WHERE url = :url LIMIT 1")
    CatalogEntryEntity findByUrl(String url);
}
