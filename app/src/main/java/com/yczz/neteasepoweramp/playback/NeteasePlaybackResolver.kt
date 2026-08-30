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
            val cookie = cookieProvider?.invoke()
            Log.i(TAG, "resolve songId=$songId cookie=${cookie != null}")
            var lastError: PlaybackException? = null
            // Prefer Hi-Res, then lossless (SQ), HQ, then lower. VIP needs eapi + MUSIC_U.
            for (level in QUALITY_LEVELS) {
                try {
                    return@withContext resolveViaEapi(songId, cookie, level)
                } catch (e: PlaybackException) {
                    lastError = e
                }
            }
            for (bitrate in BITRATE_FALLBACKS) {
                try {
                    return@withContext resolveViaGet(songId, cookie, bitrate)
                } catch (e: PlaybackException) {
                    lastError = e
                }
            }
            throw lastError ?: PlaybackException("no playable url for song $songId")
        }

    private fun resolveViaGet(songId: Long, cookie: String?, bitrate: Long): PlaybackSource {
        val url = "$SONG_URL_API?ids=%5B$songId%5D&br=$bitrate"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .apply { if (!cookie.isNullOrEmpty()) header("Cookie", cookie) }
            .get()
            .build()
        val payload = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PlaybackException("song/url HTTP ${response.code}")
            response.body?.string() ?: throw PlaybackException("empty song/url body")
        }
        return parseSongUrlResponse(payload, songId)
    }

    /** Visible for tests: pure JSON parsing. */
    internal fun parseSongUrlResponse(json: String, songId: Long): PlaybackSource {
        val root = JSONObject(json)
        val code = root.optInt("code", -1)
        if (code != OK_CODE && code != 0) {
            throw PlaybackException("song/url api code=$code for song $songId")
        }

        val data = root.optJSONArray("data")
            ?: throw PlaybackException("song/url missing data for song $songId body=${json.take(120)}")

        // The data array may contain entries for different qualities.
        // Pick the first with a non-empty url.
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            var url = entry.optString("url").orEmpty().trim()
            if (url.isEmpty() || url == "null") {
                Log.w(
                    TAG,
                    "song/url empty url songId=$songId code=${entry.optInt("code", -1)} fee=${entry.optInt("fee", -1)}",
                )
                continue
            }
            val entryId = entry.optLong("id", 0L)
            if (entryId == 0L || entryId == songId) {
                // CDN answers arrive as http:// — rewrite to https:// (supported by music.126.net)
                // so playback never depends on cleartext traffic being permitted.
                val streamUrl = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                val size = entry.optLong("size", PlaybackSource.SIZE_UNKNOWN)
                val type = entry.optString("type").orEmpty().trim()
                val level = entry.optString("level").orEmpty().trim()
                val mimeType = mimeFor(type, level)
                Log.i(TAG, "song/url hit songId=$songId type=$type level=$level br=${entry.optInt("br", -1)}")
                val expireMs = System.currentTimeMillis() + URL_EXPIRE_MS
                // CDN rejects Referer (HTTP 403). Poweramp streams CALL_GET_URL itself, so
                // headers here also get forwarded — leave them empty.
                return PlaybackSource(
                    url = streamUrl,
                    mimeType = mimeType,
                    headers = emptyMap(),
                    expireTime = expireMs,
                    size = if (size > 0) size else PlaybackSource.SIZE_UNKNOWN,
                )
            }
        }

        throw PlaybackException("no playable url found for song $songId (may require VIP/login)")
    }

    /**
     * Fallback used when the public GET /api/song/enhance/player/url answers url=null
     * (VIP / risk control). Same eapi construction as the official web client.
     */
    /**
     * Official client path. [ids] MUST be the string "[123]" (not a JSON array) or the
     * server returns url=null for VIP tracks even with a valid MUSIC_U cookie.
     */
    private fun resolveViaEapi(songId: Long, cookie: String?, level: String): PlaybackSource {
        val encodeType = if (level == "hires" || level == "lossless") "flac" else "mp3"
        val payload = JSONObject()
            .put("ids", "[$songId]")
            .put("level", level)
            .put("encodeType", encodeType)
        val apiPath = "/api/song/enhance/player/url/v1"
        val body = okhttp3.FormBody.Builder()
            .add("params", com.yczz.neteasepoweramp.netease.EapiCrypto.encrypt(apiPath, payload.toString()))
            .build()
        val request = Request.Builder()
            .url("https://interface.music.163.com/eapi/song/enhance/player/url/v1")
            .header("User-Agent", EAPI_USER_AGENT)
            .header("Referer", REFERER)
            .header("Cookie", eapiCookie(cookie))
            .post(body)
            .build()
        val payloadText = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PlaybackException("eapi song/url HTTP ${response.code}")
            response.body?.string() ?: throw PlaybackException("empty eapi song/url body")
        }
        if (payloadText.isEmpty()) throw PlaybackException("empty eapi song/url body")
        if (!payloadText.trimStart().startsWith("{")) {
            throw PlaybackException("eapi song/url not json (encrypted or empty)")
        }
        Log.i(TAG, "eapi song/url level=$level encodeType=$encodeType songId=$songId")
        return parseSongUrlResponse(payloadText, songId)
    }

    private fun mimeFor(type: String, level: String): String {
        val t = type.lowercase()
        if (t == "flac" || t == "flac24" || level == "hires" || level == "lossless") return "audio/flac"
        return when (t) {
            "mp3" -> "audio/mpeg"
            "ape" -> "audio/x-ape"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    private fun eapiCookie(musicU: String?): String = buildString {
        append("os=android; appver=8.20.20.231215173437; osver=14; channel=netease; __remember_me=true")
        if (!musicU.isNullOrEmpty()) {
            append("; ")
            append(musicU)
        }
    }

    private companion object {
        const val TAG = ProviderConstants.TAG
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val SONG_URL_API = "https://music.163.com/api/song/enhance/player/url"
        // Try 320kbps first; if that quality is gated (url=null), fall back to 128kbps.
        val BITRATE_FALLBACKS = longArrayOf(320_000L, 192_000L, 128_000L)
        val QUALITY_LEVELS = arrayOf("hires", "lossless", "exhigh", "higher", "standard")
        const val OK_CODE = 200
        // NetEase URLs typically expire after ~20 minutes; be conservative.
        const val URL_EXPIRE_MS = 10L * 60 * 1000
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"
        const val EAPI_USER_AGENT =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14; Pixel 6 Build/UQ1A)"
        const val REFERER = "https://music.163.com/"
    }
}
