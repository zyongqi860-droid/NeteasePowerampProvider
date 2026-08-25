package com.yczz.neteasepoweramp.model

/**
 * Authorization state manager (task section 7): read / save / clear the [UserSession].
 *
 * Backed by SharedPreferences. Deliberately stores only state flag + non-sensitive identifiers -
 * never passwords, cookies or tokens (log/credential red line).
 */
class SessionManager(context: android.content.Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /** @return current session; anonymous placeholder when nothing was ever stored. */
    fun read(): UserSession = UserSession(
        state = prefs.getString(KEY_STATE, null) ?: UserSession.STATE_ANONYMOUS,
        userId = prefs.getLong(KEY_USER_ID, 0L),
        displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
        updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
    )

    fun save(session: UserSession) {
        prefs.edit()
            .putString(KEY_STATE, session.state)
            .putLong(KEY_USER_ID, session.userId)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putLong(KEY_UPDATED_AT, session.updatedAt)
            .apply()
    }

    /** Wipes the session (logout / expiry); back to anonymous. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    val isAuthorized: Boolean
        get() = read().state == UserSession.STATE_AUTHORIZED

    private companion object {
        const val PREFS_NAME = "netease_session"
        const val KEY_STATE = "state"
        const val KEY_USER_ID = "user_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
