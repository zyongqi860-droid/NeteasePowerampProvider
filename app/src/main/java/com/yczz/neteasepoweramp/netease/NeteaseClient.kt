package com.yczz.neteasepoweramp.netease

import com.yczz.neteasepoweramp.model.PlaylistDetail
import com.yczz.neteasepoweramp.model.Playlist
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
        requireOkCode(root, "search")
        val result = root.optJSONObject("result") ?: return emptyList()
        val songs: JSONArray = result.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            parseSongItem(songs.optJSONObject(i) ?: continue)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Fetches one playlist with its ordered songs. Public playlists are readable without
     * authorization; private/limited playlists answer code 301 and surface as "login required"
     * - we never try to bypass (phase-4 red line).
     */
    fun fetchPlaylist(playlistId: Long): PlaylistDetail {
        val body = FormBody.Builder()
            .add("id", playlistId.toString())
            .build()
        val request = Request.Builder()
            .url(PLAYLIST_DETAIL_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("playlist HTTP ${response.code}")
            }
            val payload = response.body?.string() ?: throw IOException("empty playlist response body")
            return parsePlaylist(payload, playlistId)
        }
    }

    /** Visible for tests: pure mapping from raw playlist JSON. */
    internal fun parsePlaylist(json: String, requestedId: Long): PlaylistDetail {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw NeteaseApiException("malformed playlist response", e)
        }
        requireOkCode(root, "playlist")
        val playlist = root.optJSONObject("playlist")
            ?: throw NeteaseApiException("playlist payload missing")
        val playlistId = playlist.optLong("id", requestedId)
        val name = playlist.optString("name").trim()
        val coverUrl = playlist.optString("coverImgUrl").orEmpty().trim()
        val trackCount = playlist.optInt("trackCount", 0)
        val head = Playlist(playlistId, name, coverUrl, trackCount)

        // Source of order is trackIds; full metadata comes from tracks. Ids without metadata
        // (large playlists) are skipped rather than guessed.
        val trackIds: JSONArray? = playlist.optJSONArray("trackIds")
        val tracks: JSONArray? = playlist.optJSONArray("tracks")
        val byId = HashMap<Long, Song>(if (tracks != null) tracks.length() else 0)
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                parseSongItem(tracks.optJSONObject(i) ?: continue)?.let { byId[it.songId] = it }
            }
        }
        val ordered = ArrayList<Song>(byId.size)
        if (trackIds != null && trackIds.length() > 0) {
            for (i in 0 until trackIds.length()) {
                val id = trackIds.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                byId[id]?.let { ordered.add(it) }
            }
        } else {
            ordered.addAll(byId.values)
        }
        return PlaylistDetail(head, ordered)
    }

    private fun requireOkCode(root: JSONObject, what: String) {
        val code = root.optInt("code", -1)
        if (code == LOGIN_REQUIRED_CODE) {
            throw NeteaseApiException("$what requires login (code=$code)")
        }
        if (code != OK_CODE) {
            throw NeteaseApiException("$what api code=$code")
        }
    }

    /** Maps one API song object to [Song]; null when the item is unusable. */
    private fun parseSongItem(item: JSONObject): Song? {
        val songId = item.optLong("id", 0L)
        val title = item.optString("name").trim()
        if (songId <= 0L || title.isEmpty()) return null

        val artistNames = StringBuilder()
        var artistId = 0L
        val artists: JSONArray? = item.optJSONArray("artists")
        if (artists != null) {
            for (j in 0 until artists.length()) {
                val artist = artists.optJSONObject(j) ?: continue
                val name = artist.optString("name").orEmpty().trim()
                if (name.isNotEmpty()) {
                    if (artistNames.isNotEmpty()) artistNames.append('/')
                    artistNames.append(name)
                }
                if (artistId == 0L) artistId = artist.optLong("id", 0L)
            }
        }
        val album = item.optJSONObject("album")
        val albumName = album?.optString("name").orEmpty().trim()
        val albumId = album?.optLong("id", 0L) ?: 0L
        val coverUrl = album?.optString("picUrl").orEmpty().trim()
        val durationMs = item.optLong("duration", 0L)

        return Song(
            songId = songId,
            title = title,
            artist = if (artistNames.isEmpty()) UNKNOWN_ARTIST else artistNames.toString(),
            artistId = artistId,
            album = albumName,
            albumId = albumId,
            durationMs = if (durationMs > 0L) durationMs else 0L,
            coverUrl = coverUrl,
            source = Song.SOURCE_NETEASE,
        )
    }

    /** API responded but with an error/unexpected payload; never contains sensitive data. */
    class NeteaseApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val SEARCH_URL = "https://music.163.com/api/search/get"
        const val PLAYLIST_DETAIL_URL = "https://music.163.com/api/playlist/detail"
        const val SEARCH_TYPE_SONG = "1"
        const val DEFAULT_LIMIT = 30
        const val OK_CODE = 200
        const val LOGIN_REQUIRED_CODE = 301
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val REFERER = "https://music.163.com"
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}
