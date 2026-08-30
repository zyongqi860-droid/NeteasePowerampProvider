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
     * Anonymous visitor cookie (MUSIC_A) for the current SMS-login attempt.
     * Send-captcha and login must share this: NetEase treats a login without the
     * matching visitor session as a risky new device (code 10004).
     */
    @Volatile
    private var visitorCookie: String? = null

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
            .add("total", "true")
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
            val songs = parseSongs(payload)
            return enrichCovers(songs)
        }
    }

    /**
     * Fills missing album cover URLs via /api/song/detail (search often omits picUrl).
     * Songs that already have a cover are left untouched. Failures are ignored.
     */
    fun enrichCovers(songs: List<Song>): List<Song> {
        val missing = songs.filter { it.coverUrl.isEmpty() }.map { it.songId }
        if (missing.isEmpty()) return songs
        val covers = try {
            fetchCoverUrls(missing)
        } catch (_: Exception) {
            return songs
        }
        if (covers.isEmpty()) return songs
        return songs.map { song ->
            val cover = covers[song.songId]
            if (song.coverUrl.isEmpty() && !cover.isNullOrEmpty()) song.copy(coverUrl = cover) else song
        }
    }

    /** Cover URL per song id; empty map on failure. Blocking IO. */
    fun fetchCoverUrls(songIds: List<Long>): Map<Long, String> {
        if (songIds.isEmpty()) return emptyMap()
        val ids = songIds.take(50).joinToString(",")
        val request = Request.Builder()
            .url("$SONG_DETAIL_API?ids=$ids")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .apply { cookieStore?.cookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            val payload = response.body?.string() ?: return emptyMap()
            return parseCoverUrls(payload)
        }
    }

    internal fun parseCoverUrls(json: String): Map<Long, String> {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return emptyMap()
        }
        if (root.optInt("code", -1) != OK_CODE) return emptyMap()
        val songs = root.optJSONArray("songs") ?: return emptyMap()
        val out = HashMap<Long, String>(songs.length())
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            val cover = resolveCoverUrl(item, item.optJSONObject("al") ?: item.optJSONObject("album"))
            if (cover.isNotEmpty()) out[id] = cover
        }
        return out
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
     * Registers an anonymous visitor and keeps MUSIC_A on this client instance.
     * Required before SMS login; otherwise NetEase answers code=10004 (risky login).
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun ensureVisitor(): String {
        visitorCookie?.let { return it }
        val username = anonymousUsername()
        val result = eapiPost(
            "/api/register/anonimous",
            JSONObject().put("username", username),
        )
        val root = JSONObject(result.body)
        val code = root.optInt("code", -1)
        if (code != OK_CODE) {
            throw NeteaseApiException(apiError("anonymous register", code, root))
        }
        val musicA = extractCookie(result.cookies, "MUSIC_A")
            ?: throw NeteaseApiException("anonymous register ok but no MUSIC_A cookie")
        val csrf = extractCookie(result.cookies, "__csrf")
        visitorCookie = buildString {
            append("MUSIC_A=").append(musicA)
            if (!csrf.isNullOrEmpty()) append("; __csrf=").append(csrf)
        }
        return visitorCookie!!
    }

    /**
     * Sends a login SMS captcha to [phone]. Blocking IO; call from a background thread.
     *
     * Must go through eapi on interface.music.163.com: the weapi POST path is stripped
     * by CDN (HTTP 200, empty body), and the unencrypted /api path answers code=405.
     * Call [ensureVisitor] first so send+login share the same MUSIC_A session.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun sendCaptcha(phone: String, countrycode: String) {
        ensureVisitor()
        val result = eapiPost(
            "/api/sms/captcha/sent",
            JSONObject()
                .put("ctcode", countrycode)
                .put("cellphone", phone),
        )
        val root = JSONObject(result.body)
        val code = root.optInt("code", -1)
        if (code != OK_CODE) {
            throw NeteaseApiException(apiError("send captcha", code, root))
        }
    }

    /**
     * Logs in with phone + SMS captcha via eapi and returns the MUSIC_U cookie.
     * Blocking IO; call from a background thread. The captcha is never stored or logged.
     * Must use the same [NeteaseClient] instance that sent the captcha.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun loginCellphoneCaptcha(phone: String, countrycode: String, captcha: String): LoginResult {
        ensureVisitor()
        val result = eapiPost(
            "/api/w/login/cellphone",
            JSONObject()
                .put("type", "1")
                .put("https", "true")
                .put("phone", phone)
                .put("countrycode", countrycode)
                .put("captcha", captcha)
                .put("remember", "true"),
        )
        return parseLoginResult(result, "sms login")
    }

    /**
     * Logs in with phone + password via eapi. Password is md5'd before leaving this method
     * and is never stored or logged. Often blocked by risk control (code 8821 / -460);
     * SMS login is the primary path.
     */
    @Throws(IOException::class, NeteaseApiException::class)
    fun loginCellphone(phone: String, countrycode: String, password: String): LoginResult {
        val result = eapiPost(
            "/api/w/login/cellphone",
            JSONObject()
                .put("type", "1")
                .put("https", "true")
                .put("phone", phone)
                .put("countrycode", countrycode)
                .put("password", md5Hex(password))
                .put("remember", "true"),
        )
        return parseLoginResult(result, "password login")
    }

    private fun parseLoginResult(result: EncryptedResponse, what: String): LoginResult {
        val root = JSONObject(result.body)
        val code = root.optInt("code", -1)
        if (code != OK_CODE) {
            throw NeteaseApiException(apiError(what, code, root))
        }
        val musicU = extractMusicU(result.cookies)
            ?: extractMusicUFromBody(root)
            ?: throw NeteaseApiException("$what ok but no MUSIC_U cookie")
        val profile = root.optJSONObject("profile")
            ?: throw NeteaseApiException("$what ok but no profile")
        return LoginResult(
            musicU = musicU,
            account = AccountStatus(
                nickname = profile.optString("nickname").orEmpty(),
                vipType = profile.optInt("vipType", 0),
            ),
        )
    }

    private fun md5Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- QR login
    // Password login is often blocked (code=8821 / -460). QR is the web-supported fallback.
    // Unikey + poll use the unencrypted GET /api path: weapi POST is CDN-stripped.

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
        val result = apiGet("$API_BASE/login/qrcode/unikey?type=$QR_TYPE")
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
        val result = apiGet("$API_BASE/login/qrcode/client/login?key=${key.unikey}&type=$QR_TYPE")
        val root = JSONObject(result.body)
        val code = root.optInt("code", -1)
        val message = root.optString("message").ifEmpty { root.optString("msg") }
        if (code == QrPollResult.SUCCESS) {
            val musicU = extractMusicU(result.cookies)
                ?: extractMusicUFromBody(root)
                ?: throw NeteaseApiException("qr login ok but no MUSIC_U cookie")
            return QrPollResult(code, musicU, message)
        }
        return QrPollResult(code, null, message)
    }

    private data class EncryptedResponse(val body: String, val cookies: List<String>)

    /**
     * POST helper for eapi endpoints. [apiPath] is the /api/... path the server decrypts;
     * the request goes to interface.music.163.com/eapi/...
     */
    private fun eapiPost(apiPath: String, payload: JSONObject): EncryptedResponse {
        val json = payload.toString()
        val body = FormBody.Builder()
            .add("params", EapiCrypto.encrypt(apiPath, json))
            .build()
        val request = Request.Builder()
            .url("$EAPI_BASE${apiPath.removePrefix("/api")}")
            .header("User-Agent", EAPI_USER_AGENT)
            .header("Referer", REFERER)
            .header("Cookie", eapiCookieHeader())
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("eapi HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("empty eapi body")
            if (text.isEmpty()) throw IOException("empty eapi body")
            return EncryptedResponse(text, response.headers.values("Set-Cookie"))
        }
    }

    private fun apiGet(url: String): EncryptedResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("api GET HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("empty api body")
            if (text.isEmpty()) throw IOException("empty api body")
            return EncryptedResponse(text, response.headers.values("Set-Cookie"))
        }
    }

    private fun eapiCookieHeader(): String {
        val visitor = visitorCookie
        return if (visitor.isNullOrEmpty()) EAPI_COOKIE else "$EAPI_COOKIE; $visitor"
    }

    /**
     * NetEase anonymous username = base64(deviceId + " " + md5(xor(deviceId))).
     * Same construction the official web client uses for /api/register/anonimous.
     */
    private fun anonymousUsername(): String {
        val deviceId = buildString(ANON_DEVICE_ID_LENGTH) {
            repeat(ANON_DEVICE_ID_LENGTH) {
                append(ANON_DEVICE_HEX[kotlin.random.Random.nextInt(ANON_DEVICE_HEX.length)])
            }
        }
        val xorKey = ANON_XOR_KEY
        val xored = buildString(deviceId.length) {
            for (i in deviceId.indices) {
                append((deviceId[i].code xor xorKey[i % xorKey.length].code).toChar())
            }
        }
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(xored.toByteArray(Charsets.UTF_8))
        val encodedId = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        return android.util.Base64.encodeToString(
            "$deviceId $encodedId".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun extractMusicU(setCookies: List<String>): String? = extractCookie(setCookies, "MUSIC_U")

    /** First Set-Cookie pair whose name equals [name]; ignores MUSIC_A_T / MUSIC_R_U etc. */
    private fun extractCookie(setCookies: List<String>, name: String): String? {
        val prefix = "$name="
        return setCookies.firstNotNullOfOrNull { header ->
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith(prefix) && !pair.startsWith("${name}_")) {
                pair.substring(prefix.length).takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }

    private fun extractMusicUFromBody(root: JSONObject): String? {
        val cookie = root.optString("cookie")
        if (cookie.isEmpty()) return null
        return cookie.substringAfter("MUSIC_U=", "").substringBefore(';').takeIf { it.isNotEmpty() }
    }

    private fun apiError(what: String, code: Int, root: JSONObject): String {
        val msg = root.optString("message").ifEmpty { root.optString("msg") }
        return "$what failed (code=$code ${msg.ifEmpty { "no message" }})"
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
            val detail = parsePlaylist(payload, playlistId)
            return detail.copy(songs = enrichCovers(detail.songs))
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

    private fun resolveCoverUrl(item: JSONObject, album: JSONObject?): String {
        val direct = album?.optString("picUrl").orEmpty().trim()
            .ifEmpty { item.optString("albumPic").orEmpty().trim() }
            .ifEmpty { item.optString("picUrl").orEmpty().trim() }
        if (direct.isNotEmpty()) return direct
        val picStr = album?.optString("pic_str").orEmpty().trim()
            .ifEmpty { album?.optString("picId_str").orEmpty().trim() }
        val id = picStr.toLongOrNull()
            ?: album?.optLong("pic", 0L)?.takeIf { it > 0L }
            ?: album?.optLong("picId", 0L)?.takeIf { it > 0L }
            ?: 0L
        if (id <= 0L) return ""
        return "https://p1.music.126.net/${encryptPicId(id)}/$id.jpg"
    }

    /** NetEase CDN path segment for an album pic id (public algorithm). */
    private fun encryptPicId(picId: Long): String {
        val key = PIC_XOR_KEY
        val src = picId.toString()
        val mixed = buildString(src.length) {
            for (i in src.indices) {
                append((src[i].code xor key[i % key.length].code).toChar())
            }
        }
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(mixed.toByteArray(Charsets.ISO_8859_1))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .replace("=", "")
    }

    /** Maps one API song object to [Song]; null when the item is unusable. */
    private fun parseSongItem(item: JSONObject): Song? {
        val songId = item.optLong("id", 0L)
        val title = item.optString("name").trim()
        if (songId <= 0L || title.isEmpty()) return null

        val artistNames = StringBuilder()
        var artistId = 0L
        val artists: JSONArray? = item.optJSONArray("artists") ?: item.optJSONArray("ar")
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
        val album = item.optJSONObject("album") ?: item.optJSONObject("al")
        val albumName = album?.optString("name").orEmpty().trim()
        val albumId = album?.optLong("id", 0L) ?: 0L
        val coverUrl = resolveCoverUrl(item, album)
        val durationMs = item.optLong("duration", 0L).let { if (it > 0L) it else item.optLong("dt", 0L) }

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
        const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
        const val SONG_DETAIL_API = "https://music.163.com/api/song/detail"
        const val PLAYLIST_DETAIL_URL = "https://music.163.com/api/v6/playlist/detail"
        const val ACCOUNT_API = "https://music.163.com/api/nuser/account/get"
        const val API_BASE = "https://music.163.com/api"
        const val EAPI_BASE = "https://interface.music.163.com/eapi"
        const val SEARCH_TYPE_SONG = "1"
        const val DEFAULT_LIMIT = 30
        const val OK_CODE = 200
        const val LOGIN_REQUIRED_CODE = 301
        const val QR_TYPE = "1"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val EAPI_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"
        const val EAPI_COOKIE =
            "os=pc; appver=3.1.17.204416; osver=Microsoft-Windows-10-Professional-build-19045-64bit; channel=netease; __remember_me=true"
        const val ANON_DEVICE_HEX = "0123456789ABCDEF"
        const val ANON_DEVICE_ID_LENGTH = 52
        const val ANON_XOR_KEY = "3go8&$8*3*3h0k(2)2"
        const val PIC_XOR_KEY = "3go8&$8*3*3h0k(2)2"
        const val REFERER = "https://music.163.com"
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}
