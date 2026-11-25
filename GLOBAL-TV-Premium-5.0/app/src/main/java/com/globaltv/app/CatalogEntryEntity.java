package com.globaltv.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "catalog_entries", indices = {
        @Index(value = {"normalizedName"}),
        @Index(value = {"normalizedGroup"}),
        @Index(value = {"sourceId"})
})
public class CatalogEntryEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String url = "";
    @NonNull public String name = "";
    @NonNull public String normalizedName = "";
    @NonNull public String groupName = "";
    @NonNull public String normalizedGroup = "";
    @NonNull public String type = "";
    @NonNull public String sourceId = "";
    public String logo = "";
    public String tvgId = "";
    public long updatedAt;
}
