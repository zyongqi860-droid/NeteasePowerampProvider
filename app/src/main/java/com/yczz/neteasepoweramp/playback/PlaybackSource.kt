package com.yczz.neteasepoweramp.playback

/**
 * A playback source resolved for one song: everything needed to open the remote audio.
 *
 * [url] may point at any http(s) audio resource; [headers] lets future sources attach
 * User-Agent/Authorization/Cookie without changing call sites (phase-3 rule: no hardcoded
 * credentials anywhere). [expireTime] is epoch ms after which the URL must be re-resolved
 * (0 = unknown/does not expire). [size] is the expected content length in bytes, -1 unknown.
 */
data class PlaybackSource(
    val url: String,
    val mimeType: String = "audio/mpeg",
    val headers: Map<String, String> = emptyMap(),
    val expireTime: Long = 0L,
    val size: Long = SIZE_UNKNOWN,
) {
    companion object {
        const val SIZE_UNKNOWN = -1L
    }
}
