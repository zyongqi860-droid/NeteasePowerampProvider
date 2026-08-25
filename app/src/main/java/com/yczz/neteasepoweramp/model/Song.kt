package com.yczz.neteasepoweramp.model

/**
 * A song from a music source as shown in the Poweramp provider hierarchy.
 *
 * [songId] is the stable key: the provider document id for a song always ends with
 * "song_<songId>" (see ProviderConstants), so playback can resolve the same document back to
 * the song without any extra mapping. [source] tags the upstream ("netease" today, other
 * licensed sources later).
 */
data class Song(
    val songId: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    /** Duration in milliseconds as reported by the source API; 0 = unknown. */
    val durationMs: Long,
    /** Album cover URL, best-effort; may be empty when the API does not return one. */
    val coverUrl: String,
    val source: String = SOURCE_NETEASE,
) {
    companion object {
        const val SOURCE_NETEASE = "netease"
    }
}
