package com.yczz.neteasepoweramp.playback

import com.yczz.neteasepoweramp.provider.ProviderConstants

/**
 * Phase-3 architecture validator: resolves ONLY the seeded remote-test song
 * (ProviderConstants.MOCK_REMOTE_SONG_ID, the "test_remote_001" of the task spec) to a public,
 * Range-capable test MP3. Real NetEase URL resolution is intentionally NOT implemented here -
 * phase 4 plugs a licensed source resolver behind the same [PlaybackResolver] interface.
 */
class MockPlaybackResolver : PlaybackResolver {

    override suspend fun resolve(songId: Long): PlaybackSource {
        if (songId != ProviderConstants.MOCK_REMOTE_SONG_ID) {
            throw PlaybackException("no playback source for song $songId (phase 3 ships only the mock remote song)")
        }
        return PlaybackSource(
            url = MOCK_URL,
            mimeType = "audio/mpeg",
            headers = emptyMap(),
            // Mock URL is stable; short-ish expiry exercises the re-resolve path without churn.
            expireTime = System.currentTimeMillis() + EXPIRE_MS,
            size = SIZE_UNKNOWN,
        )
    }

    private companion object {
        /** Public SoundHelix sample: plain MP3 over http(s), supports Range requests for seeking. */
        const val MOCK_URL = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        const val EXPIRE_MS = 60L * 60 * 1000
        const val SIZE_UNKNOWN = -1L
    }
}
