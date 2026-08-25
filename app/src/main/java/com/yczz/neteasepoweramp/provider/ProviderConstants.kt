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

    const val ROOT_ID = "netease_root"

    /** Shown as the root name in the system picker / Poweramp Music Folders dialog. */
    const val ROOT_TITLE = "Netease Poweramp"

    /** Human-readable provider display name shown next to the root. */
    const val ROOT_SUMMARY = "网易云音乐"

    /** Display name of the virtual folder under the root. */
    const val TEST_FOLDER_DISPLAY_NAME = "Test Music"
}
