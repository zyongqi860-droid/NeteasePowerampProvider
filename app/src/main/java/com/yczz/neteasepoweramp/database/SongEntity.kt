package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row of the cached songs table. This is the ONLY data source the provider reads from,
 * so Poweramp scans never hit the network (phase-2 rule: provider methods do no IO beyond Room).
 */
@Entity(tableName = "songs")
data class SongEntity(
    /** NetEase song id; also the stable suffix of the provider document id. */
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    /** Duration in milliseconds. */
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    /** Wall clock time of the last insert/update, for future cache invalidation. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
