package com.yczz.neteasepoweramp.netease

import com.yczz.neteasepoweramp.model.Song
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Thin HTTP wrapper around the public NetEase web search endpoint.
 *
 * Scope (phase 2): keyword search only - no login, no cookies, no audio URLs, no lyrics.
 * JSON is parsed with the platform org.json to keep third-party dependencies minimal.
 */
class NeteaseClient {

    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * Runs a synchronous search; blocking IO, so callers must invoke from a background thread.
     *
     * @return songs in API order (relevance), possibly empty.
     * @throws IOException on network failure.
     * @throws NeteaseApiException when the API answers with a non-OK code or unexpected payload.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun search(keyword: String, limit: Int = DEFAULT_LIMIT): List<Song> {
        val body = FormBody.Builder()
            .add("s", keyword)
            .add("type", SEARCH_TYPE_SONG)
            .add("limit", limit.toString())
            .add("offset", "0")
            .build()

        val request = Request.Builder()
            .url(SEARCH_URL)
            // The web API rejects requests without a browser-ish UA.
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("search HTTP ${response.code}")
            }
            val payload = response.body?.string() ?: throw IOException("empty search response body")
            return parseSongs(payload)
        }
    }

    /** Visible for tests: pure mapping from raw JSON text to [Song] list. */
    internal fun parseSongs(json: String): List<Song> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw NeteaseApiException("malformed search response", e)
        }
        val code = root.optInt("code", -1)
        if (code != OK_CODE) {
            throw NeteaseApiException("api code=$code")
        }
        val result = root.optJSONObject("result") ?: return emptyList()
        val songs: JSONArray = result.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val songId = item.optLong("id", 0L)
            val title = item.optString("name").trim()
            if (songId <= 0L || title.isEmpty()) continue

            val artistNames = StringBuilder()
            val artists: JSONArray? = item.optJSONArray("artists")
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    val name = artists.optJSONObject(j)?.optString("name").orEmpty().trim()
                    if (name.isNotEmpty()) {
                        if (artistNames.isNotEmpty()) artistNames.append('/')
                        artistNames.append(name)
                    }
                }
            }
            val album = item.optJSONObject("album")
            val albumName = album?.optString("name").orEmpty().trim()
            val coverUrl = album?.optString("picUrl").orEmpty().trim()
            val durationMs = item.optLong("duration", 0L)

            out.add(
                Song(
                    songId = songId,
                    title = title,
                    artist = if (artistNames.isEmpty()) UNKNOWN_ARTIST else artistNames.toString(),
                    album = albumName,
                    durationMs = if (durationMs > 0L) durationMs else 0L,
                    coverUrl = coverUrl,
                ),
            )
        }
        return out
    }

    /** API responded but with an error/unexpected payload; never contains sensitive data. */
    class NeteaseApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val SEARCH_URL = "https://music.163.com/api/search/get"
        const val SEARCH_TYPE_SONG = "1"
        const val DEFAULT_LIMIT = 30
        const val OK_CODE = 200
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val REFERER = "https://music.163.com"
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}
