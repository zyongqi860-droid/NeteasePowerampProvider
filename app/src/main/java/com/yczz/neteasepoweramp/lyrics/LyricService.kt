package com.yczz.neteasepoweramp.lyrics

import android.content.Context
import android.util.Log
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.provider.ProviderConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches LRC lyrics from the NetEase public lyric API and caches them via [LyricRepository].
 *
 * Phase 5: now connected to the real NetEase lyric endpoint
 * (https://music.163.com/api/song/lyric). No login or cookies required
 * for public songs.
 *
 * THREADING: all network IO runs on Dispatchers.IO.
 */
class LyricService(context: Context) {

    private val appContext = context.applicationContext
    private val repository = LyricRepository(appContext)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Get lyrics for a song. Checks cache first, then fetches from the
     * NetEase lyric API if needed.
     *
     * @param songId The NetEase song id.
     * @param lyricUrl Optional direct URL to fetch LRC from. If empty, the
     *                 NetEase lyric API is used.
     * @return Parsed list of [LyricLine], or empty list if unavailable.
     */
    suspend fun getLyrics(songId: Long, lyricUrl: String = ""): List<LyricLine> {
        // 1) Check cache.
        val cached = repository.getLyrics(songId)
        if (cached != null && cached.isNotEmpty()) {
            return LyricParser.parse(cached)
        }

        // 2) Fetch from network.
        return try {
            val content = if (lyricUrl.isNotEmpty()) {
                fetchLyricsFromUrl(lyricUrl)
            } else {
                fetchLyricsFromNetease(songId)
            }
            if (content.isNotEmpty()) {
                repository.saveLyrics(songId, content)
                LyricParser.parse(content)
            } else {
                emptyList()
            }
        } catch (e: IOException) {
            Log.w(ProviderConstants.TAG, "lyrics fetch failed for song $songId", e)
            emptyList()
        }
    }

    /** Cached raw LRC only; never hits the network. Empty string means a previous miss. */
    fun cachedRawLyrics(songId: Long): String? =
        AppDatabase.get(appContext).lyricDao().getLyricsBlocking(songId)

    /**
     * Prefetch lyrics in the background so the next Poweramp scan can serve them from cache.
     * Safe to call from a binder thread; work runs on a daemon thread.
     */
    fun prefetch(songId: Long) {
        Thread {
            try {
                if (cachedRawLyrics(songId) != null) return@Thread
                val content = fetchLyricsFromNeteaseBlocking(songId)
                AppDatabase.get(appContext).lyricDao().saveLyricBlocking(
                    com.yczz.neteasepoweramp.database.LyricEntity(
                        songId = songId,
                        content = content,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                Log.w(ProviderConstants.TAG, "lyrics prefetch failed for song $songId", e)
            }
        }.apply { isDaemon = true; name = "lyric-prefetch-$songId" }.start()
    }

    private fun fetchLyricsFromNeteaseBlocking(songId: Long): String {
        val body = FormBody.Builder()
            .add("id", songId.toString())
            .add("lv", "-1")
            .add("kv", "-1")
            .add("tv", "-1")
            .build()
        val request = Request.Builder()
            .url(LYRIC_API)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(ProviderConstants.TAG, "lyrics HTTP ${response.code} for song $songId")
                return ""
            }
            return parseLyricResponse(response.body?.string().orEmpty())
        }
    }

    /**
     * Clear cached lyrics for a song (e.g. on user request or cache invalidation).
     */
    suspend fun clearCache(songId: Long) {
        repository.deleteLyrics(songId)
    }

    // ---- internal -----------------------------------------------------------

    /**
     * Fetches lyrics from the NetEase public lyric API.
     * Endpoint: https://music.163.com/api/song/lyric
     * Params: id=songId, lv=1 (request lyrics), tv=-1 (no translated lyrics)
     *
     * Returns the raw LRC text, or empty string on failure.
     */
    private suspend fun fetchLyricsFromNetease(songId: Long): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("id", songId.toString())
                .add("lv", "1")
                .add("tv", "-1")
                .build()

            val request = Request.Builder()
                .url(LYRIC_API)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .post(body)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(ProviderConstants.TAG, "lyrics HTTP ${response.code} for song $songId")
                        return@withContext ""
                    }
                    val payload = response.body?.string().orEmpty()
                    parseLyricResponse(payload)
                }
            } catch (e: IOException) {
                Log.w(ProviderConstants.TAG, "lyrics network failed for song $songId", e)
                ""
            }
        }

    /** Visible for tests: extract the LRC text from the API JSON response. */
    internal fun parseLyricResponse(json: String): String {
        if (json.isEmpty()) return ""
        return try {
            val root = JSONObject(json)
            val code = root.optInt("code", -1)
            if (code != OK_CODE) return ""
            // "lrc" object contains the "lyric" field with raw LRC text.
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty().trim()
            if (lrc.isNotEmpty() && lrc != "nolyric" && lrc != "纯音乐，请欣赏") return lrc
            val tlyric = root.optJSONObject("tlyric")?.optString("lyric").orEmpty().trim()
            if (tlyric.isNotEmpty()) return tlyric
            ""
        } catch (e: Exception) {
            Log.w(ProviderConstants.TAG, "lyrics JSON parse failed", e)
            ""
        }
    }

    /** Fetch LRC from a direct URL (e.g. a user-provided URL). */
    private suspend fun fetchLyricsFromUrl(url: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("lyrics HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw e
            }
        }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val LYRIC_API = "https://music.163.com/api/song/lyric"
        const val OK_CODE = 200
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val REFERER = "https://music.163.com"
    }
}
