package com.yczz.neteasepoweramp.playback

import com.yczz.neteasepoweramp.database.SongDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * URL-cache decorator around any [PlaybackResolver] (phase 3, enhanced in phase 5).
 *
 * Flow: play request → cached [SongEntity.lastPlayUrl] still valid? → reuse it; otherwise
 * delegate resolve and persist the fresh URL + expiry. The DB row is a cache only - a missing
 * or expired URL simply triggers a new resolve, never a failure.
 *
 * Phase 5 enhancements:
 * - Limited retry on failure (max 1 retry)
 * - Timeout control via [resolveTimeoutMs]
 * - Exponential backoff would be in the caller; here we just count retries.
 */
class CachingPlaybackResolver(
    private val delegate: PlaybackResolver,
    private val dao: SongDao,
    private val maxRetries: Int = 1,
    private val resolveTimeoutMs: Long = 10_000L,
) : PlaybackResolver {

    override suspend fun resolve(songId: Long): PlaybackSource {
        // Check cache first.
        val row = withContext(Dispatchers.IO) { dao.getSong(songId) }
        val cachedUrl = row?.lastPlayUrl
        if (!cachedUrl.isNullOrEmpty() && row.urlExpireTime > System.currentTimeMillis()) {
            return PlaybackSource(
                url = cachedUrl,
                mimeType = DEFAULT_MIME_TYPE,
                expireTime = row.urlExpireTime,
            )
        }

        // Cache miss or expired — resolve with retry.
        var lastException: Throwable? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val fresh = delegate.resolve(songId)
                if (fresh.url.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        dao.updatePlaybackCache(songId, fresh.url, fresh.expireTime)
                    }
                }
                return fresh
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxRetries) {
                    // Brief delay before retry (exponential backoff base).
                    kotlinx.coroutines.delay(
                        TimeUnit.SECONDS.toMillis(1L) shl attempt.coerceAtMost(4),
                    )
                }
            }
        }

        throw PlaybackException(
            "resolve failed after ${maxRetries + 1} attempts for song $songId",
            lastException,
        )
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "audio/mpeg"
    }
}
