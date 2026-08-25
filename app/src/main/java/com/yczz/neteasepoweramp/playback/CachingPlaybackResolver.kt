package com.yczz.neteasepoweramp.playback

import com.yczz.neteasepoweramp.database.SongDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * URL-cache decorator around any [PlaybackResolver] (task section 10).
 *
 * Flow: play request -> cached [SongEntity.lastPlayUrl] still valid? -> reuse it; otherwise
 * delegate resolve and persist the fresh URL + expiry. The DB row is a cache only - a missing
 * or expired URL simply triggers a new resolve, never a failure.
 */
class CachingPlaybackResolver(
    private val delegate: PlaybackResolver,
    private val dao: SongDao,
) : PlaybackResolver {

    override suspend fun resolve(songId: Long): PlaybackSource {
        val row = withContext(Dispatchers.IO) { dao.getSong(songId) }
        val cachedUrl = row?.lastPlayUrl
        if (!cachedUrl.isNullOrEmpty() && row.urlExpireTime > System.currentTimeMillis()) {
            return PlaybackSource(
                url = cachedUrl,
                mimeType = DEFAULT_MIME_TYPE,
                expireTime = row.urlExpireTime,
            )
        }
        val fresh = delegate.resolve(songId)
        if (fresh.url.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                dao.updatePlaybackCache(songId, fresh.url, fresh.expireTime)
            }
        }
        return fresh
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "audio/mpeg"
    }
}
