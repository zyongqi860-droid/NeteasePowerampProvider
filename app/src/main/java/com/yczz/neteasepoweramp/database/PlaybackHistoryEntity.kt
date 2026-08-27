package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One playback history entry. Recorded each time a song starts playing.
 *
 * [completed] is true when the song played past a significant portion of its duration
 * (e.g. > 50%). Short skips are not counted as completed plays.
 */
@Entity(
    tableName = "playback_history",
    indices = [Index("song_id"), Index("played_at")],
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "song_id")
    val songId: Long,

    /** Epoch milliseconds when this play started. */
    @ColumnInfo(name = "played_at")
    val playedAt: Long,

    /** Duration of the track in ms (as known at play time). */
    @ColumnInfo(name = "duration")
    val duration: Long,

    /** True if the track was played to completion (or near-completion). */
    @ColumnInfo(name = "completed")
    val completed: Boolean = false,
)
