package com.yczz.neteasepoweramp.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * All access is blocking on purpose: callers are the provider (binder threads) and
 * SearchService (background executor). Never call these on the main thread.
 */
@Dao
interface SongDao {

    /** Re-saving a search result updates metadata instead of failing on the primary key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongs(songs: List<SongEntity>)

    /** Everything currently saved; this is what the "搜索结果" folder lists. */
    @Query("SELECT * FROM songs ORDER BY artist, title")
    fun getSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :keyword || '%' OR artist LIKE '%' || :keyword || '%' OR album LIKE '%' || :keyword || '%' ORDER BY artist, title")
    fun searchSongs(keyword: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE song_id = :songId")
    fun getSong(songId: Long): SongEntity?

    /** Used by device tests to remove their fixture rows without touching user-saved songs. */
    @Query("DELETE FROM songs WHERE song_id = :songId")
    fun deleteSong(songId: Long)

    @Query("DELETE FROM songs")
    fun clearSongs()
}
