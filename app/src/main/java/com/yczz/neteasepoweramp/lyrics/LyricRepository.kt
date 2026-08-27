package com.yczz.neteasepoweramp.lyrics

import android.content.Context
import com.yczz.neteasepoweramp.database.AppDatabase

/**
 * Room-backed lyrics cache.
 *
 * Lyrics are stored as raw LRC text; parsing happens on read via [LyricParser].
 * This keeps the DB schema simple and avoids versioning parsed structures.
 */
class LyricRepository(context: Context) {

    private val dao = AppDatabase.get(context.applicationContext).lyricDao()

    suspend fun getLyrics(songId: Long): String? = dao.getLyrics(songId)

    suspend fun saveLyrics(songId: Long, content: String) {
        if (content.isBlank()) return
        dao.saveLyric(
            com.yczz.neteasepoweramp.database.LyricEntity(
                songId = songId,
                content = content,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteLyrics(songId: Long) {
        dao.deleteLyrics(songId)
    }
}
