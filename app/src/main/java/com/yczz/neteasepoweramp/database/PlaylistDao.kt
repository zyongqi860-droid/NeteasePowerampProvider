package com.yczz.neteasepoweramp.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Blocking access like [SongDao]: callers are services on background coroutines and the
 * provider on binder threads. Never call on the main thread.
 */
@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylists(playlists: List<PlaylistEntity>)

    /** Rows for the "我的歌单" folder, newest import first. */
    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE playlist_id = :playlistId")
    fun getPlaylist(playlistId: Long): PlaylistEntity?

    /** Replaces the track membership of one playlist in one transaction. */
    @Transaction
    fun replacePlaylistTracks(playlistId: Long, tracks: List<PlaylistTrackEntity>) {
        deletePlaylistTracks(playlistId)
        insertPlaylistTracks(tracks)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    fun deletePlaylistTracks(playlistId: Long)

    /** Songs of one playlist in stored order; missing song rows are skipped by the provider. */
    @Query(
        "SELECT s.* FROM songs s JOIN playlist_tracks t ON s.song_id = t.song_id " +
            "WHERE t.playlist_id = :playlistId ORDER BY t.position",
    )
    fun getPlaylistSongs(playlistId: Long): List<SongEntity>

    @Query("DELETE FROM playlists WHERE playlist_id = :playlistId")
    fun deletePlaylist(playlistId: Long)
}
