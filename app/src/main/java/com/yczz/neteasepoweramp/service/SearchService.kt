package com.yczz.neteasepoweramp.service

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.database.SongEntity
import com.yczz.neteasepoweramp.database.toEntity
import com.yczz.neteasepoweramp.database.toSong
import com.yczz.neteasepoweramp.model.Song
import com.yczz.neteasepoweramp.netease.NeteaseClient
import com.yczz.neteasepoweramp.netease.NeteaseCookieStore
import com.yczz.neteasepoweramp.provider.ProviderConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Business layer: keyword -> NetEase search -> Song list, "save to provider" -> Room, and
 * seeding of the phase-3 remote-test song. All work runs on [scope] (Dispatchers.IO, a
 * SupervisorJob - no GlobalScope) so callers never do network or disk work themselves.
 *
 * Results are delivered on the calling coroutine thread; MainActivity hops back to the main
 * thread itself via Handler.
 */
class SearchService(context: Context) {

    interface SearchCallback {
        /** Called with the result list on success; empty list means "no matches". */
        fun onSearchSuccess(songs: List<Song>)

        /** Called with a short non-sensitive failure description. */
        fun onSearchError(message: String)
    }

    private val appContext = context.applicationContext
    private val client = NeteaseClient(NeteaseCookieStore(appContext))
    private val dao = AppDatabase.get(appContext).songDao()

    /** IO scope with SupervisorJob: one failed child never cancels the others. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun search(keyword: String, callback: SearchCallback) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            // Empty input is a caller error, not worth a network round trip.
            callback.onSearchError("empty keyword")
            return
        }
        Log.w(ProviderConstants.TAG, "search start keyword=$trimmed")
        scope.launch {
            try {
                val songs = client.search(trimmed)
                Log.i(ProviderConstants.TAG, "search result count=${songs.size}")
                callback.onSearchSuccess(songs)
            } catch (e: IOException) {
                Log.e(ProviderConstants.TAG, "search network failure", e)
                callback.onSearchError("network failure: ${e.message}")
            } catch (e: NeteaseClient.NeteaseApiException) {
                Log.e(ProviderConstants.TAG, "search api failure", e)
                callback.onSearchError("api failure: ${e.message}")
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "search unexpected failure", e)
                callback.onSearchError("unexpected failure")
            }
        }
    }

    /**
     * Persists the given songs as the current "搜索结果" content. REPLACE semantics keep ids
     * stable when the same song is saved again after metadata changed server-side.
     */
    /** Saved songs currently visible to Poweramp. Result is delivered on [scope] (IO). */
    fun listSavedSongs(onResult: (List<Song>) -> Unit) {
        scope.launch {
            val songs = try {
                dao.getSongs().map { it.toSong() }
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "list saved songs failed", e)
                emptyList()
            }
            onResult(songs)
        }
    }

    /** Removes songs from the provider cache. Poweramp drops them on the next rescan. */
    fun deleteSongs(songIds: List<Long>, onDone: () -> Unit) {
        if (songIds.isEmpty()) {
            onDone()
            return
        }
        scope.launch {
            try {
                dao.deleteSongs(songIds)
                notifyProviderChanged()
                Log.i(ProviderConstants.TAG, "database delete count=${songIds.size}")
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "database delete failed count=${songIds.size}", e)
            }
            onDone()
        }
    }

    fun saveSongs(songs: List<Song>, onDone: (() -> Unit)? = null) {
        if (songs.isEmpty()) {
            onDone?.invoke()
            return
        }
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                dao.insertSongs(songs.map { it.toEntity(now) })
                Log.i(ProviderConstants.TAG, "database insert count=${songs.size}")
                notifyProviderChanged()
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "database insert failed", e)
            }
            onDone?.invoke()
        }
    }

    /** Tells SAF observers (and Poweramp, if listening) that provider content changed. */
    private fun notifyProviderChanged() {
        try {
            appContext.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(ProviderConstants.AUTHORITY),
                null,
            )
        } catch (e: Exception) {
            Log.e(ProviderConstants.TAG, "notifyChange failed", e)
        }
    }

    /**
     * Seeds the phase-3 remote-stream test track (task section 12: "test_remote_001") once, so
     * Poweramp can exercise Provider -> PlaybackResolver -> remote stream without any real
     * NetEase API. Duration stays 0: the cursor omits DURATION and Poweramp reads the real one
     * from the stream, keeping metadata and audio consistent.
     */
    fun ensureRemoteTestSong() {
        scope.launch {
            try {
                if (dao.getSong(ProviderConstants.MOCK_REMOTE_SONG_ID) == null) {
                    dao.insertSongs(
                        listOf(
                            SongEntity(
                                songId = ProviderConstants.MOCK_REMOTE_SONG_ID,
                                title = REMOTE_TEST_TITLE,
                                artist = REMOTE_TEST_ARTIST,
                                album = REMOTE_TEST_ALBUM,
                                duration = 0L,
                                coverUrl = "",
                                updatedAt = System.currentTimeMillis(),
                                sourceId = "mock",
                            ),
                        ),
                    )
                    Log.i(ProviderConstants.TAG, "remote test song seeded songId=${ProviderConstants.MOCK_REMOTE_SONG_ID}")
                }
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "seeding remote test song failed", e)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        const val REMOTE_TEST_TITLE = "Remote Stream Test"
        const val REMOTE_TEST_ARTIST = "MockPlaybackResolver"
        const val REMOTE_TEST_ALBUM = "Phase 3 Verification"
    }
}
