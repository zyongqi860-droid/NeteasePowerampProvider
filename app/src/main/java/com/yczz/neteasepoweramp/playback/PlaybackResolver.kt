package com.yczz.neteasepoweramp.playback

/**
 * Resolves a song id into a currently valid [PlaybackSource].
 *
 * Implementations must be swappable (task section: 可替换接口) - phase 3 ships
 * [MockPlaybackResolver] behind [CachingPlaybackResolver]; a licensed music-service resolver
 * can replace the delegate later without touching provider/streaming code.
 *
 * THREADING: implementations are suspend functions and must be safe to call concurrently from
 * multiple binder threads (Poweramp may open two tracks in parallel for crossfade).
 */
interface PlaybackResolver {

    /**
     * @return a playback source whose URL is valid now.
     * @throws PlaybackException when the song has no playable source (unknown id, expired
     * entitlement, upstream failure...). Callers translate this into a client-safe error.
     */
    suspend fun resolve(songId: Long): PlaybackSource
}
