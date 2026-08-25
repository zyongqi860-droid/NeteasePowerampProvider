package com.yczz.neteasepoweramp.model

/**
 * User authorization state (phase 4 infrastructure).
 *
 * Phase 4 ships NO login flow: the session starts as [STATE_ANONYMOUS]. A future phase fills
 * in a real authorization flow; the rest of the app only reads [SessionManager] state.
 *
 * SECURITY: never store passwords, cookies or tokens here. Only non-sensitive identifiers and
 * a state flag.
 */
data class UserSession(
    val state: String,
    val userId: Long,
    val displayName: String,
    val updatedAt: Long,
) {
    companion object {
        /** No authorization performed yet - the default and current phase-4 state. */
        const val STATE_ANONYMOUS = "anonymous"

        /** A future login flow succeeded; user id/display name are meaningful. */
        const val STATE_AUTHORIZED = "authorized"

        /** A previously authorized session was cleared or expired. */
        const val STATE_EXPIRED = "expired"
    }
}
