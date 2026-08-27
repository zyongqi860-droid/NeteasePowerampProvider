package com.yczz.neteasepoweramp.lyrics

/**
 * One parsed line of an LRC file.
 *
 * @property timestamp Time offset in milliseconds from the start of the track.
 * @property text The lyric text for this timestamp.
 */
data class LyricLine(
    val timestamp: Long,
    val text: String,
)
