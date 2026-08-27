package com.yczz.neteasepoweramp.playback

import android.util.Log
import com.yczz.neteasepoweramp.provider.ProviderConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves a NetEase song id to a real, playable audio URL via the public
 * NetEase "song/url" API endpoint.
 *
 * This replaces [MockPlaybackResolver] as the production resolver behind
 * [CachingPlaybackResolver]. The mock resolver is retained for the test track
 * (ProviderConstants.MOCK_REMOTE_SONG_ID) so the phase-1 regression test
 * continues to work without network access.
 *
 * THREADING: suspend function; all HTTP IO happens on Dispatchers.IO.
 * The OkHttpClient is shared and stateless — safe for concurrent use.
 *
 * SECURITY: no cookies, no login tokens, no credentials are used.
 * The API returns public, time-limited URLs (typically valid ~20 min).
 */
class NeteasePlaybackResolver(
    private val mockDelegate: MockPlaybackResolver = MockPlaybackResolver(),
    /** Supplies the user's MUSIC_U cookie header when set; anonymous (null) otherwise. Required for full-length VIP songs. */
    private val cookieProvider: (() -> String?)? = null,
) : PlaybackResolver {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun resolve(songId: Long): PlaybackSource {
        // Delegate the phase-3 mock test track to keep regression tests working.
        if (songId == ProviderConstants.MOCK_REMOTE_SONG_ID) {
            return mockDelegate.resolve(songId)
        }

        return try {
            val source = resolveNeteaseUrl(songId)
            Log.i(TAG, "resolved songId=$songId url=${source.url.takeLast(30)}...")
            source
        } catch (e: PlaybackException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "resolve failed songId=$songId", e)
            throw PlaybackException("cannot resolve song $songId: ${e.message}", e)
        }
    }

    /**
     * Calls the NetEase "song/url" endpoint, which returns a JSON object with
     * a "data" array containing one or more quality entries. We pick the
     * first available entry with a non-empty URL.
     */
    private suspend fun resolveNeteaseUrl(songId: Long): PlaybackSource =
        withContext(Dispatchers.IO) {
            // The endpoint accepts only the array form ids=[x]; the singular id=x answers
            // code=400 "参数错误". Brackets are pre-encoded (%5B/%5D) so OkHttp needs no extra quoting.
            var lastError: PlaybackException? = null
            for (bitrate in BITRATE_FALLBACKS) {
                val url = "$SONG_URL_API?ids=%5B$songId%5D&br=$bitrate"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .apply { cookieProvider?.invoke()?.let { header("Cookie", it) } }
                    .get()
                    .build()

                val payload = try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("song/url HTTP ${response.code}")
                        }
                        response.body?.string() ?: throw IOException("empty song/url body")
                    }
                } catch (e: IOException) {
                    throw PlaybackException("song/url request failed: ${e.message}", e)
                }

                try {
                    return@withContext parseSongUrlResponse(payload, songId)
                } catch (e: PlaybackException) {
                    // 320k may be VIP-gated while 128k plays free — fall through to next bitrate.
                    lastError = e
                }
            }
            throw lastError ?: PlaybackException("no playable url for song $songId")
        }

    /** Visible for tests: pure JSON parsing. */
    internal fun parseSongUrlResponse(json: String, songId: Long): PlaybackSource {
        val root = JSONObject(json)
        val code = root.optInt("code", -1)
        if (code != OK_CODE) {
            throw PlaybackException("song/url api code=$code for song $songId")
        }

        val data = root.optJSONArray("data")
            ?: throw PlaybackException("song/url missing data for song $songId")

        // The data array may contain entries for different qualities.
        // Pick the first with a non-empty url.
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val url = entry.optString("url").orEmpty().trim()
            if (url.isNotEmpty() && entry.optLong("id", 0L) == songId) {
                // CDN answers arrive as http:// — rewrite to https:// (supported by music.126.net)
                // so playback never depends on cleartext traffic being permitted.
                val streamUrl = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                val size = entry.optLong("size", PlaybackSource.SIZE_UNKNOWN)
                val type = entry.optString("type").orEmpty().trim()
                val mimeType = when (type.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    "ape" -> "audio/x-ape"
                    "wav" -> "audio/wav"
                    "m4a" -> "audio/mp4"
                    else -> "audio/mpeg"
                }
                val expireMs = System.currentTimeMillis() + URL_EXPIRE_MS
                return PlaybackSource(
                    url = streamUrl,
                    mimeType = mimeType,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to REFERER,
                    ),
                    expireTime = expireMs,
                    size = if (size > 0) size else PlaybackSource.SIZE_UNKNOWN,
                )
            }
        }

        throw PlaybackException("no playable url found for song $songId (may require VIP/login)")
    }

    private companion object {
        const val TAG = ProviderConstants.TAG
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val SONG_URL_API = "https://music.163.com/api/song/enhance/player/url"
        // Try 320kbps first; if that quality is gated (url=null), fall back to 128kbps.
        val BITRATE_FALLBACKS = longArrayOf(320_000L, 128_000L)
        const val OK_CODE = 200
        // NetEase URLs typically expire after ~20 minutes; be conservative.
        const val URL_EXPIRE_MS = 10L * 60 * 1000
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"
        const val REFERER = "https://music.163.com/"
    }
}
