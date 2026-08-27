package com.yczz.neteasepoweramp.lyrics

/**
 * Parses LRC-format lyrics into a sorted list of [LyricLine].
 *
 * Supported format:
 * ```
 * [00:12.50]歌词文本
 * [00:13.00]第二行歌词
 * [ti:Song Title]
 * [ar:Artist Name]
 * [al:Album Name]
 * [by:Creator]
 * ```
 *
 * Metadata tags (ti/ar/al/by) are currently ignored — only timestamped lines
 * are returned, sorted by timestamp.
 */
object LyricParser {

    /**
     * Parse LRC content string into a list of [LyricLine] sorted by timestamp.
     *
     * @param lrcContent Raw LRC file content.
     * @return Sorted list of lyric lines; empty list if no valid lines found.
     */
    fun parse(lrcContent: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        val regex = """\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.+)""".toRegex()

        lrcContent.lineSequence().forEach { line ->
            val match = regex.find(line.trim()) ?: return@forEach
            val (minStr, secStr, msStr, text) = match.destructured
            val ts = parseTimestamp(minStr, secStr, msStr)
            if (ts >= 0 && text.isNotBlank()) {
                lines.add(LyricLine(ts, text.trim()))
            }
        }

        return lines.sortedBy { it.timestamp }
    }

    /**
     * Find the lyric line that should be displayed at the given playback position.
     *
     * @param lyrics Sorted list of lyric lines (from [parse]).
     * @param positionMs Current playback position in milliseconds.
     * @return The active lyric line, or null if before the first or after the last.
     */
    fun findActiveLine(lyrics: List<LyricLine>, positionMs: Long): LyricLine? {
        if (lyrics.isEmpty()) return null
        // Find last line with timestamp <= positionMs.
        var result: LyricLine? = null
        for (line in lyrics) {
            if (line.timestamp <= positionMs) {
                result = line
            } else {
                break
            }
        }
        return result
    }

    /**
     * Find the index of the active line for efficient scrolling / highlighting.
     */
    fun findActiveIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        for ((index, line) in lyrics.withIndex()) {
            if (line.timestamp > positionMs) {
                return (index - 1).coerceAtLeast(0)
            }
        }
        return lyrics.lastIndex
    }

    // ---- internal -----------------------------------------------------------

    private fun parseTimestamp(min: String, sec: String, ms: String): Long {
        return try {
            val m = min.toLongOrNull() ?: return -1L
            val s = sec.toLongOrNull() ?: return -1L
            var millis = ms.toLongOrNull() ?: 0L
            // Normalize to milliseconds: if ms is 2 digits, treat as hundredths.
            if (ms.length <= 2) millis *= 10
            (m * 60_000L) + (s * 1000L) + millis
        } catch (_: Exception) {
            -1L
        }
    }
}
