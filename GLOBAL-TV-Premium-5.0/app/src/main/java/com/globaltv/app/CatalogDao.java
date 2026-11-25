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

    @Query("SELECT * FROM catalog_entries WHERE normalizedName LIKE '%' || :token || '%' OR normalizedGroup LIKE '%' || :token || '%' OR lower(tvgId) LIKE '%' || :token || '%' OR lower(plot) LIKE '%' || :token || '%' OR lower(genre) LIKE '%' || :token || '%' OR lower(cast) LIKE '%' || :token || '%' OR lower(director) LIKE '%' || :token || '%' OR lower(seriesTitle) LIKE '%' || :token || '%' OR lower(year) LIKE '%' || :token || '%' ORDER BY name COLLATE NOCASE LIMIT :limit")
    List<CatalogEntryEntity> searchToken(String token, int limit);

    @Query("SELECT COUNT(*) FROM catalog_entries")
    int count();

    @Query("SELECT * FROM catalog_entries WHERE url = :url LIMIT 1")
    CatalogEntryEntity findByUrl(String url);
}
