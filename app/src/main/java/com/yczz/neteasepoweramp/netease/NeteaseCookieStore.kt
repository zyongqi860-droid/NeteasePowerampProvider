package com.yczz.neteasepoweramp.netease

import android.content.Context

/**
 * Stores the NetEase login cookie (MUSIC_U) the user pastes in Settings.
 *
 * VIP-gated songs only resolve to full-length URLs when the song/url request carries the
 * user's own login cookie; anonymous requests get ~30s trial clips. The cookie is a plain
 * credential: it lives in private SharedPreferences, is never logged, and is only attached
 * as a Cookie header to music.163.com requests.
 */
class NeteaseCookieStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveMusicU(value: String) {
        val musicU = parseMusicU(value)
            ?: throw IllegalArgumentException("no MUSIC_U in pasted text")
        prefs.edit().putString(KEY_MUSIC_U, musicU).apply()
    }

    /** @return the stored MUSIC_U value, or null when browsing anonymously. */
    fun musicU(): String? = prefs.getString(KEY_MUSIC_U, null)?.takeIf { it.isNotEmpty() }

    fun clear() {
        prefs.edit().remove(KEY_MUSIC_U).apply()
    }

    /** Ready-to-use Cookie header value for music.163.com requests, or null when anonymous. */
    fun cookieHeader(): String? = musicU()?.let { "$KEY_MUSIC_U=$it" }

    companion object {
        const val PREFS_NAME = "netease_cookies"
        const val KEY_MUSIC_U = "MUSIC_U"

        /**
         * Accepts a raw MUSIC_U value, `MUSIC_U=...`, or a full Cookie header
         * (`MUSIC_U=...; __csrf=...`). Returns just the MUSIC_U token.
         */
        fun parseMusicU(raw: String): String? {
            val text = raw.trim().trim('"', '\'')
            if (text.isEmpty()) return null
            if (!text.contains('=') && !text.contains(';')) {
                return text.takeIf { it.isNotEmpty() }
            }
            val parts = text.split(';')
            for (part in parts) {
                val pair = part.trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.equals(KEY_MUSIC_U, ignoreCase = true) && value.isNotEmpty()) {
                    return value
                }
            }
            return null
        }
    }
}
