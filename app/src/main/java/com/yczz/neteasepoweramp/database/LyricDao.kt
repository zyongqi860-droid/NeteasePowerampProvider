package com.yczz.neteasepoweramp.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the lyrics cache table. All operations are suspend (IO thread).
 */
@Dao
interface LyricDao {

    @Query("SELECT content FROM lyrics WHERE song_id = :songId")
    suspend fun getLyrics(songId: Long): String?

    /** Blocking read for the DocumentsProvider binder thread. */
    @Query("SELECT content FROM lyrics WHERE song_id = :songId")
    fun getLyricsBlocking(songId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLyric(entity: LyricEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveLyricBlocking(entity: LyricEntity)

    @Query("DELETE FROM lyrics WHERE song_id = :songId")
    suspend fun deleteLyrics(songId: Long)

    /** Delete all lyrics older than [maxAgeMs] milliseconds. Returns rows affected. */
    @Query("DELETE FROM lyrics WHERE updated_at < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int
}
