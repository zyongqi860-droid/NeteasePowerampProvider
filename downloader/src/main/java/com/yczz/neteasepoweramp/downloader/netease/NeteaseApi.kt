package com.yczz.neteasepoweramp.downloader.netease

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Self-contained NetEase client for the downloader app: keyword search, account verification,
 * QR login and VIP-aware playback-url resolution. Login state is one MUSIC_U cookie kept in
 * private SharedPreferences; it identifies the user's own account, and the server decides
 * entitlements (full song vs 30s preview) exactly as it does for the official client.
 */
class NeteaseApi(context: Context) {

    // ------------------------------------------------------------------ cookie store

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var musicU: String?
        get() = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY, value).apply()
        }

    /** Accepts a raw token, "MUSIC_U=...", or a whole Cookie header; returns success. */
    fun importCookie(pasted: String): Boolean {
        val token = pasted.split(";", "\n")
            .map { it.trim() }
            .firstOrNull { it.startsWith("MUSIC_U=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
            ?: pasted.trim().takeIf { it.length >= 32 && !it.contains(' ') && !it.contains('=') }
        return if (token != null) {
            musicU = token
            true
        } else {
            false
        }
    }

    fun logout() {
        musicU = null
    }

    private fun cookieHeader(): String? = musicU?.let { "MUSIC_U=$it" }

    // ------------------------------------------------------------------ http

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String, withCookie: Boolean = false): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .apply { if (withCookie) cookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun post(url: String, form: FormBody, eapiCookie: Boolean = false): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EAPI_UA)
            .header("Referer", REFERER)
            .header("Cookie", if (eapiCookie) eapiCookieHeader() else cookieHeader() ?: EAPI_BASE_COOKIE)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    // ------------------------------------------------------------------ search

    data class SongResult(
        val songId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String = "",
    )

    /** @return songs in NetEase relevance order (most popular first). */
    fun search(keyword: String, limit: Int = 30): List<SongResult> {
        val body = FormBody.Builder()
            .add("s", keyword)
            .add("type", "1")
            .add("limit", limit.toString())
            .add("offset", "0")
            .add("total", "true")
            .build()
        val request = Request.Builder()
            .url(SEARCH_URL)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .apply { cookieHeader()?.let { header("Cookie", it) } }
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("search HTTP ${response.code}")
            val payload = response.body?.string() ?: throw IOException("empty search body")
            return parseSearch(payload)
        }
    }

    internal fun parseSearch(json: String): List<SongResult> {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 200) return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<SongResult>(songs.length())
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            val title = item.optString("name").trim()
            if (id <= 0L || title.isEmpty()) continue
            val artists = item.optJSONArray("ar") ?: item.optJSONArray("artists")
            val artist = buildString {
                if (artists != null) {
                    for (j in 0 until artists.length()) {
                        val name = artists.optJSONObject(j)?.optString("name").orEmpty().trim()
                        if (name.isNotEmpty()) {
                            if (isNotEmpty()) append('/')
                            append(name)
                        }
                    }
                }
            }.ifEmpty { "Unknown Artist" }
            val albumObj = item.optJSONObject("al")
            val album = albumObj?.optString("name").orEmpty().trim()
            // Search answers with http:// cover urls; cleartext is blocked on modern Android.
            var coverUrl = albumObj?.optString("picUrl").orEmpty().trim()
            if (coverUrl.startsWith("http://")) coverUrl = "https://" + coverUrl.removePrefix("http://")
            val duration = item.optLong("dt", 0L).let { if (it > 0) it else item.optLong("duration", 0L) }
            out.add(SongResult(id, title, artist, album, duration, coverUrl))
        }
        return out
    }

    /** Plain LRC lyrics for a song, or null when none / not accessible. */
    fun songLyric(songId: Long): String? {
        val (code, body) = get("$BASE/song/lyric?id=$songId&lv=1&tv=-1", withCookie = true)
        if (code != 200) return null
        val lyric = try {
            JSONObject(body).optJSONObject("lrc")?.optString("lyric").orEmpty()
        } catch (_: Exception) {
            return null
        }
        return lyric.trim().takeIf { it.isNotEmpty() }
    }

    /** Best-effort album cover url for a song id (search results usually carry one already). */
    fun coverUrlFor(songId: Long): String? {
        val (code, body) = get("$BASE/song/detail?ids=%5B$songId%5D", withCookie = true)
        if (code != 200) return null
        return try {
            val root = JSONObject(body)
            val song = root.optJSONArray("songs")?.optJSONObject(0) ?: return null
            // song/detail answers with the legacy "album" object; newer payloads use "al".
            val albumObj = song.optJSONObject("al") ?: song.optJSONObject("album") ?: return null
            var url = albumObj.optString("picUrl").orEmpty().trim()
            if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
            url.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ account

    data class Account(val nickname: String, val vipType: Int) {
        val vipLabel: String
            get() = when (vipType) {
                11 -> "VIP"
                13 -> "SVIP"
                0 -> "非 VIP"
                else -> "VIP${vipType}"
            }
    }

    /** @return account summary, or null when the cookie is missing/expired. */
    fun verifyAccount(): Account? {
        val cookie = cookieHeader() ?: return null
        val (code, body) = get("$BASE/api/nuser/account/get?csrf_token=", withCookie = true)
        if (code != 200) return null
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        if (root.optInt("code", -1) != 200) return null
        val profile = root.optJSONObject("profile") ?: return null
        return Account(
            nickname = profile.optString("nickname").orEmpty().ifEmpty { "账号" },
            vipType = profile.optInt("vipType", 0),
        )
    }

    // ------------------------------------------------------------------ url resolution

    data class Resolved(val url: String, val size: Long)

    /**
     * Resolves a playable URL for [songId]. Quality ladder hires→lossless→exhigh→higher→
     * standard mirrors the official client; the user's own cookie decides full song vs trial.
     * @return null when no playable url exists (unknown/VIP-gated without login).
     */
    fun resolveSongUrl(songId: Long): Resolved? {
        for (level in QUALITY_LEVELS) {
            try {
                val payload = JSONObject()
                    .put("ids", "[$songId]")
                    .put("level", level)
                    .put("encodeType", if (level == "hires" || level == "lossless") "flac" else "mp3")
                val form = FormBody.Builder()
                    .add("params", eapiEncrypt(RESOLVE_API_PATH, payload.toString()))
                    .build()
                val (code, body) = post(EAPI_RESOLVE_URL, form, eapiCookie = true)
                if (code != 200 || body.isEmpty() || !body.trimStart().startsWith("{")) continue
                val resolved = parseSongUrl(body, songId)
                if (resolved != null) return resolved
            } catch (_: Exception) {
                // try next level
            }
        }
        // Anonymous-friendly GET fallback.
        for (br in longArrayOf(320_000, 192_000, 128_000)) {
            try {
                val (code, body) = get("$BASE/song/enhance/player/url?ids=%5B$songId%5D&br=$br", withCookie = true)
                if (code != 200) continue
                val resolved = parseSongUrl(body, songId)
                if (resolved != null) return resolved
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun parseSongUrl(json: String, songId: Long): Resolved? {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 200) return null
        val data = root.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            var url = entry.optString("url").orEmpty().trim()
            if (url.isEmpty() || url == "null") continue
            val entryId = entry.optLong("id", 0L)
            if (entryId != 0L && entryId != songId) continue
            if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
            val size = entry.optLong("size", 0L).takeIf { it > 0 } ?: -1L
            return Resolved(url, size)
        }
        return null
    }

    // ------------------------------------------------------------------ eapi

    private fun eapiCookieHeader(): String = buildString {
        append(EAPI_BASE_COOKIE)
        musicU?.let { append("; MUSIC_U=").append(it) }
    }

    private companion object {
        const val PREFS = "downloader_netease"
        const val KEY = "MUSIC_U"
        const val BASE = "https://music.163.com/api"
        const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
        const val EAPI_RESOLVE_URL = "https://interface.music.163.com/eapi/song/enhance/player/url/v1"
        const val RESOLVE_API_PATH = "/api/song/enhance/player/url/v1"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 Chrome/107 Mobile Safari/537.36"
        const val EAPI_UA =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14; Pixel 6)"
        const val REFERER = "https://music.163.com/"
        const val EAPI_BASE_COOKIE = "os=android; appver=8.20.20.231215173437; osver=14; channel=netease; __remember_me=true"
        val QUALITY_LEVELS = arrayOf("hires", "lossless", "exhigh", "higher", "standard")

        fun eapiEncrypt(apiPath: String, payloadJson: String): String {
            val key = "e82ckenh8dichen8".toByteArray(Charsets.US_ASCII)
            val digest = MessageDigest.getInstance("MD5")
                .digest("nobody${apiPath}use${payloadJson}md5forencrypt".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val message = apiPath + "-36cd479b6b5-" + payloadJson + "-36cd479b6b5-" + digest
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        }
    }
}
