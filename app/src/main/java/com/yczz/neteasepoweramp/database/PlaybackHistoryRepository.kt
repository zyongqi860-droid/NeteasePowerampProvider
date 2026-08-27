package com.yczz.neteasepoweramp.database

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository wrapper around [PlaybackHistoryDao] that enforces an automatic
 * row cap on every insert, so the playback history table never grows
 * unbounded.
 *
 * Phase 5: addresses the "播放历史自动截断策略" optimization — previously
 * [PlaybackHistoryDao.trimToMax] existed but had to be called manually.
 *
 * The default cap [MAX_ENTRIES] = 500 keeps the table small enough for
 * fast queries while retaining a generous window of recent plays.
 */
class PlaybackHistoryRepository(
    context: Context,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    private val dao = AppDatabase.get(context.applicationContext).playbackHistoryDao()

    /**
     * Record a play and automatically trim to [maxEntries].
     */
    suspend fun recordPlay(songId: Long, durationMs: Long, completed: Boolean = false) {
        withContext(Dispatchers.IO) {
            val entry = PlaybackHistoryEntity(
                songId = songId,
                playedAt = System.currentTimeMillis(),
                duration = durationMs,
                completed = completed,
            )
            dao.recordPlay(entry)
            // Auto-trim after every insert so the table never grows unbounded.
            val trimmed = dao.trimToMax(maxEntries)
            if (trimmed > 0) {
                Log.d(TAG, "trimmed $trimmed old history rows (cap=$maxEntries)")
            }
        }
    }

    suspend fun markCompleted(entryId: Long) {
        withContext(Dispatchers.IO) { dao.markCompleted(entryId) }
    }

    suspend fun getRecentPlays(limit: Int = 100): List<PlaybackHistoryEntity> {
        return withContext(Dispatchers.IO) { dao.getRecentPlays(limit) }
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) { dao.totalCount() }

    companion object {
        private const val TAG = "NeteasePowerampProvider"
        const val MAX_ENTRIES = 500
    }
}
