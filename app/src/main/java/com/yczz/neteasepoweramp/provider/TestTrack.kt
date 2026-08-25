package com.yczz.neteasepoweramp.provider

/** Metadata of the single test track served during phase 1. */
object TestTrack {
    const val TITLE = "Poweramp Provider Test"
    const val ARTIST = "NeteasePowerampProvider"
    const val ALBUM = "Provider Test"
    const val GENRE = "Test"
    const val DISPLAY_NAME = "Poweramp Provider Test.mp3"
    const val MIME_TYPE = "audio/mpeg"
    const val TRACK_NUMBER = 1

    /**
     * Real duration of res/raw/test_audio.mp3. The file is the official bensound-dubstep.mp3
     * sample from the poweramp_provider_example assets, whose duration the official example
     * declares as 125000 ms (ExampleProvider.DUBSTEP_DURATION_MS).
     */
    const val DURATION_MS = 125_000L

    /** Fallback size matching res/raw/test_audio.mp3, used only if the extracted file cannot be stat'ed. */
    const val FALLBACK_SIZE = 2_044_859L
}
