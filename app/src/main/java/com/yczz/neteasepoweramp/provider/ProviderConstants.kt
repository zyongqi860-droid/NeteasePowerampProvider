package com.yczz.neteasepoweramp.provider

object ProviderConstants {
    /** Log tag required for all phase-1 development logging. Never log cookies/tokens/account data here. */
    const val TAG = "NeteasePowerampProvider"

    /** Authority registered in AndroidManifest.xml, referenced by Poweramp and other SAF clients. */
    const val AUTHORITY = "com.yczz.neteasepoweramp.provider"

    // Fixed document IDs. Poweramp treats document ids as opaque strings but relies on their
    // stability across scans, so no random UUIDs are allowed here.
    const val ROOT_DOC_ID = "root"
    const val TEST_FOLDER_DOC_ID = "$ROOT_DOC_ID/test_folder"
    const val TRACK_DOC_ID = "$TEST_FOLDER_DOC_ID/track_test_001"

    /**
     * Phase-2 hierarchy under the root:
     * ```
     * root
     * ├── root/netease_search              ("搜索结果", songs saved from MainActivity)
     * │     └── root/netease_search/song_<songId>
     * └── root/test_folder                 (phase-1 Test Music, kept for regression testing)
     * ```
     * Song ids stay stable across scans and are reused by phase-3 playback URL resolution.
     */
    const val SEARCH_FOLDER_DOC_ID = "netease_search"

    /** Full document id of the search-results folder below the root document. */
    const val SEARCH_FOLDER_PATH = "$ROOT_DOC_ID/$SEARCH_FOLDER_DOC_ID"

    /** Prefix for cached NetEase songs, e.g. root/netease_search/song_186016. */
    private const val SONG_DOC_PREFIX = "song_"

    /**
     * Reserved song id of the phase-3 remote-stream test track ("test_remote_001" in the task
     * spec). Seeded into Room by SearchService.ensureRemoteTestSong and resolved to a public
     * Range-capable test MP3 by MockPlaybackResolver - no real NetEase API involved.
     */
    const val MOCK_REMOTE_SONG_ID = 999_999_777L

    fun songDocId(songId: Long): String = "$SEARCH_FOLDER_PATH/$SONG_DOC_PREFIX$songId"

    /** @return NetEase song id embedded in [documentId], or null if it is not a song id. */
    fun parseSongId(documentId: String): Long? {
        if (!documentId.startsWith("$SEARCH_FOLDER_PATH/$SONG_DOC_PREFIX")) return null
        return documentId.substringAfter(SONG_DOC_PREFIX, "").toLongOrNull()
    }

    const val ROOT_ID = "netease_root"

    /** Shown as the root name in the system picker / Poweramp Music Folders dialog. */
    const val ROOT_TITLE = "Netease Poweramp"

    /** Human-readable provider display name shown next to the root. */
    const val ROOT_SUMMARY = "网易云音乐"

    /** Display name of the virtual folder under the root. */
    const val TEST_FOLDER_DISPLAY_NAME = "Test Music"
}
