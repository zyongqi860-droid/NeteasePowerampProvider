package com.yczz.neteasepoweramp.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for playback history. Keeps a bounded log of recent plays.
 */
@Dao
interface PlaybackHistoryDao {

    @Insert
    suspend fun recordPlay(entry: PlaybackHistoryEntity): Long

    /** Mark an existing entry as completed. */
    @Query("UPDATE playback_history SET completed = 1 WHERE id = :entryId")
    suspend fun markCompleted(entryId: Long)

    /** Get the most recent plays, newest first. */
    @Query("SELECT * FROM playback_history ORDER BY played_at DESC LIMIT :limit")
    suspend fun getRecentPlays(limit: Int = 100): List<PlaybackHistoryEntity>

    /** Count total recorded plays (for stats). */
    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun totalCount(): Int

    /** Delete entries older than [cutoff] epoch ms. Returns rows removed. */
    @Query("DELETE FROM playback_history WHERE played_at < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    /**
     * Keep only the most recent [maxEntries] rows.
     * Call this after inserting to enforce a cap.
     */
    @Query(
        "DELETE FROM playback_history WHERE id NOT IN " +
            "(SELECT id FROM playback_history ORDER BY played_at DESC LIMIT :maxEntries)",
    )
    suspend fun trimToMax(maxEntries: Int): Int
}
