package com.yczz.neteasepoweramp.playback

/**
 * Raised when a playback source cannot be resolved or opened. Never contains credentials;
 * messages are safe to log under the NeteasePowerampProvider tag.
 */
class PlaybackException(message: String, cause: Throwable? = null) : Exception(message, cause)
