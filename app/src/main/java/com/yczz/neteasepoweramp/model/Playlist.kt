package com.yczz.neteasepoweramp.model

/** A music playlist (phase 4). Populated from the source API; stored in Room for the provider. */
data class Playlist(
    val playlistId: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
)

/**
 * A playlist together with its ordered songs, as returned by the source API. [songs] order
 * preserves the playlist's own ordering (position column in Room).
 */
data class PlaylistDetail(
    val playlist: Playlist,
    val songs: List<Song>,
)
