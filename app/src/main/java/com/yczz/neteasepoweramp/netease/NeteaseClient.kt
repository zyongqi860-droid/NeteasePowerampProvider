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
class NeteaseClient(
    /** When present, requests carry the user's MUSIC_U cookie (personalized results, VIP access). */
    private val cookieStore: NeteaseCookieStore? = null,
) {

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
            .apply { cookieStore?.cookieHeader()?.let { header("Cookie", it) } }
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

    /**
     * Verifies a MUSIC_U cookie by asking NetEase for the current account.
     * Blocking IO; call from a background thread.
     *
     * @return nickname + vipType of the logged-in account.
     * @throws IOException on network failure, NeteaseApiException when the cookie is invalid.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun verifyAccount(musicU: String): AccountStatus {
        val request = Request.Builder()
            .url("$ACCOUNT_API?csrf_token=")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Cookie", "MUSIC_U=$musicU")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("account HTTP ${response.code}")
            val payload = response.body?.string() ?: throw IOException("empty account body")
            val root = JSONObject(payload)
            if (root.optInt("code", -1) != OK_CODE) {
                throw NeteaseApiException("cookie invalid (code=${root.optInt("code", -1)})")
            }
            val profile = root.optJSONObject("profile")
                ?: throw NeteaseApiException("cookie has no account profile (likely expired)")
            return AccountStatus(
                nickname = profile.optString("nickname").orEmpty(),
                vipType = profile.optInt("vipType", 0),
            )
        }
    }

    /** Account summary returned by [verifyAccount]; vipType 0 = none, 11 = VIP, 13 = SVIP. */
    data class AccountStatus(val nickname: String, val vipType: Int)

    /**
     * Login result: the MUSIC_U cookie that authorizes all later requests, plus the
     * account summary returned in the same response.
     */
    data class LoginResult(val musicU: String, val account: AccountStatus)

    /**
     * Logs in with phone number + password via the encrypted weapi endpoint and returns the
     * fresh MUSIC_U cookie. Blocking IO; call from a background thread.
     *
     * The password is hashed (md5) before it leaves this method and is never stored or logged.
     *
     * @throws IOException on network failure.
     * @throws NeteaseApiException on wrong credentials (code 502), risk control (code 405/406),
     * or any other non-200 answer.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun loginCellphone(phone: String, countrycode: String, password: String): LoginResult {
        val payload = org.json.JSONObject()
            .put("phone", phone)
            .put("countrycode", countrycode)
            .put("password", md5Hex(password))
            .put("rememberLogin", "true")
            .toString()
        val encrypted = WeapiCrypto.encrypt(payload)

        val body = FormBody.Builder()
            .add("params", encrypted.params)
            .add("encSecKey", encrypted.encSecKey)
            .build()
        val request = Request.Builder()
            .url("$WEAPI_BASE/login/cellphone")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("login HTTP ${response.code}")
            val payloadText = response.body?.string() ?: throw IOException("empty login body")
            val root = JSONObject(payloadText)
            val code = root.optInt("code", -1)
            if (code != OK_CODE) {
                val msg = root.optString("message").ifEmpty { root.optString("msg") }
                throw NeteaseApiException("login failed (code=$code ${msg.ifEmpty { "no message" }})")
            }
            // The credential is a Set-Cookie header on the login response itself.
            val musicU = response.headers.values("Set-Cookie")
                .firstNotNullOfOrNull { cookie ->
                    cookie.substringAfter("MUSIC_U=", "").substringBefore(';').takeIf { it.isNotEmpty() }
                }
                ?: throw NeteaseApiException("login ok but no MUSIC_U cookie in response")
            val profile = root.optJSONObject("profile")
                ?: throw NeteaseApiException("login ok but no profile")
            return LoginResult(
                musicU = musicU,
                account = AccountStatus(
                    nickname = profile.optString("nickname").orEmpty(),
                    vipType = profile.optInt("vipType", 0),
                ),
            )
        }
    }

    private fun md5Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- QR login
    // Phone+password login is blocked by NetEase risk control (code=8821 "请切换其他登录方式"),
    // even with correct credentials. QR scan login is the officially supported web flow.

    /** Fresh key for the QR login flow; the QR content is a login URL carrying this key. */
    data class QrKey(val unikey: String)

    /** One poll result; [state] is the NetEase QR protocol code. */
    data class QrPollResult(val state: Int, val musicU: String?, val message: String) {
        companion object {
            const val WAITING_FOR_SCAN = 801
            const val SCANNED_WAITING_CONFIRM = 802
            const val SUCCESS = 803
            const val EXPIRED = 800
        }
    }

    /** Step 1: request a fresh unikey for QR login. Blocking IO. */
    @Throws(IOException::class, NeteaseApiException::class)
    fun createQrKey(): QrKey {
        val result = weapiPost("$WEAPI_BASE/login/qrcode/unikey", mapOf("type" to "1"))
        val root = JSONObject(result.body)
        if (root.optInt("code", -1) != OK_CODE) {
            throw NeteaseApiException("qrcode unikey failed (code=${root.optInt("code", -1)})")
        }
        val unikey = root.optString("unikey").orEmpty()
        if (unikey.isEmpty()) throw NeteaseApiException("qrcode unikey empty")
        return QrKey(unikey)
    }

    /**
     * Step 2: poll the login state for a unikey. Blocking IO.
     * state 801=waiting for scan, 802=scanned (confirm on phone), 803=success (musicU set),
     * 800=expired.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun pollQrLogin(key: QrKey): QrPollResult {
        val result = weapiPost("$WEAPI_BASE/login/qrcode/client/login", mapOf("key" to key.unikey, "type" to "1"))
        val root = JSONObject(result.body)
        val code = root.optInt("code", -1)
        val message = root.optString("message").ifEmpty { root.optString("msg") }
        if (code == QrPollResult.SUCCESS) {
            // MUSIC_U arrives both as a Set-Cookie header and as a "cookie" string in the body.
            val fromHeader = result.cookies.firstNotNullOfOrNull { cookie ->
                cookie.substringAfter("MUSIC_U=", "").substringBefore(';').takeIf { it.isNotEmpty() }
            }
            val fromBody = runCatching {
                root.optString("cookie").substringAfter("MUSIC_U=", "").substringBefore(';').takeIf { it.isNotEmpty() }
            }.getOrNull()
            val musicU = fromHeader ?: fromBody
                ?: throw NeteaseApiException("qr login ok but no MUSIC_U cookie")
            return QrPollResult(code, musicU, message)
        }
        return QrPollResult(code, null, message)
    }

    /** POST helper for weapi endpoints: encrypts [params] and returns body + Set-Cookie headers. */
    private data class WeapiResponse(val body: String, val cookies: List<String>)

    private fun weapiPost(url: String, params: Map<String, String>): WeapiResponse {
        val payload = org.json.JSONObject()
        for ((name, value) in params) payload.put(name, value)
        val encrypted = WeapiCrypto.encrypt(payload.toString())
        val body = FormBody.Builder()
            .add("params", encrypted.params)
            .add("encSecKey", encrypted.encSecKey)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("weapi HTTP ${response.code}")
            return WeapiResponse(
                body = response.body?.string() ?: throw IOException("empty weapi body"),
                cookies = response.headers.values("Set-Cookie"),
            )
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
            // n = how many full track objects the server returns alongside trackIds.
            .add("n", "1000")
            .build()
        val request = Request.Builder()
            .url(PLAYLIST_DETAIL_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .apply { cookieStore?.cookieHeader()?.let { header("Cookie", it) } }
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
        const val PLAYLIST_DETAIL_URL = "https://music.163.com/api/v6/playlist/detail"
        const val ACCOUNT_API = "https://music.163.com/api/nuser/account/get"
        const val WEAPI_BASE = "https://music.163.com/weapi"
        const val SEARCH_TYPE_SONG = "1"
        const val DEFAULT_LIMIT = 30
        const val OK_CODE = 200
        const val LOGIN_REQUIRED_CODE = 301
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val REFERER = "https://music.163.com"
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}
