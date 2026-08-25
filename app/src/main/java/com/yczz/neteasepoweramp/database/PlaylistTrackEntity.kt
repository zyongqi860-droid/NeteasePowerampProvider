package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Ordered membership of a song in a playlist. Songs themselves live in [SongEntity]; this table
 * only maps positions, so one cached song can appear in many playlists.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("song_id")],
)
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    /** Song reference into songs.song_id (no FK: songs may be evicted independently). */
    @ColumnInfo(name = "song_id")
    val songId: Long,
    /** Zero-based order inside the playlist. */
    @ColumnInfo(name = "position")
    val position: Int,
)
