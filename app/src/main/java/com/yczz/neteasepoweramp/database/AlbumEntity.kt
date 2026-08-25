package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Album metadata cache (phase 4); rows are best-effort, the provider does not depend on them. */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
)
