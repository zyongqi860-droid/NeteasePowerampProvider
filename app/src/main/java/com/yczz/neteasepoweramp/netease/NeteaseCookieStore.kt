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
        prefs.edit().putString(KEY_MUSIC_U, value.trim()).apply()
    }

    /** @return the stored MUSIC_U value, or null when browsing anonymously. */
    fun musicU(): String? = prefs.getString(KEY_MUSIC_U, null)?.takeIf { it.isNotEmpty() }

    fun clear() {
        prefs.edit().remove(KEY_MUSIC_U).apply()
    }

    /** Ready-to-use Cookie header value for music.163.com requests, or null when anonymous. */
    fun cookieHeader(): String? = musicU()?.let { "$KEY_MUSIC_U=$it" }

    private companion object {
        const val PREFS_NAME = "netease_cookies"
        const val KEY_MUSIC_U = "MUSIC_U"
    }
}
