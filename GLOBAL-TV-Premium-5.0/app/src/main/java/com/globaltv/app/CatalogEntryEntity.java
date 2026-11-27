package com.globaltv.app;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
@Entity(tableName="catalog_entries", indices={
 @Index(value={"normalizedName"}),@Index(value={"normalizedGroup"}),@Index(value={"sourceId"}),@Index(value={"url"})
})
public class CatalogEntryEntity {
 @PrimaryKey @NonNull public String id="";
 @NonNull public String url="";
 @NonNull public String name="";
 @NonNull public String normalizedName="";
 @NonNull public String groupName="";
 @NonNull public String normalizedGroup="";
 @NonNull public String type="";
 @NonNull public String sourceId="";
 public String logo="";
 public String tvgId="";
 @NonNull public String tvgLanguage="";
 public String container="";
 public String entryId="";
 public String plot="";
 public String rating="";
 public String year="";
 public String duration="";
 public String genre="";
 public String cast="";
 public String director="";
 public int seasonNumber=-1;
 public int episodeNumber=-1;
 public String seriesTitle="";
 public long updatedAt;
}