package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Playlist metadata cached from the source API; the "我的歌单" folder lists these rows. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    /** Number of tracks at fetch time; informational. */
    @ColumnInfo(name = "track_count", defaultValue = "0")
    val trackCount: Int = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
