package com.yczz.neteasepoweramp.model

/**
 * A NetEase song as shown in the Poweramp provider hierarchy.
 *
 * [songId] is the stable key: the provider document id for a song is always
 * "netease_search/song_<songId>" (see ProviderConstants), so phase-3 playback can resolve
 * the same document back to a NetEase song without any extra mapping.
 */
data class Song(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    /** Duration in milliseconds as reported by the NetEase search API. */
    val durationMs: Long,
    /** Album cover URL, best-effort; may be empty when the API does not return one. */
    val coverUrl: String,
)
