package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached LRC lyrics for one song.
 *
 * The raw LRC text is stored; parsing to [com.yczz.neteasepoweramp.lyrics.LyricLine]
 * happens at read time via [com.yczz.neteasepoweramp.lyrics.LyricParser].
 */
@Entity(tableName = "lyrics")
data class LyricEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,

    @ColumnInfo(name = "content")
    val content: String,

    /** When this cache entry was last updated (epoch ms). */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
