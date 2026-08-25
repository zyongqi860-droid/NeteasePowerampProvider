package com.yczz.neteasepoweramp.service

import android.content.Context
import android.util.Log
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.database.SongEntity
import com.yczz.neteasepoweramp.model.Song
import com.yczz.neteasepoweramp.netease.NeteaseClient
import com.yczz.neteasepoweramp.provider.ProviderConstants
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phase-2 business layer: keyword -> NetEase search -> Song list, and "save to provider" ->
 * Room. Both directions are executed on a private single thread so callers (MainActivity,
 * future UI) never do network or disk work themselves.
 *
 * Results are delivered on the calling executor thread; MainActivity hops back to the main
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
    private val client = NeteaseClient()
    private val dao = AppDatabase.get(appContext).songDao()

    /** Single worker: keeps NetEase calls sequential and avoids hammering the API. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "netease-search").apply { isDaemon = false }
    }

    fun search(keyword: String, callback: SearchCallback) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            // Empty input is a caller error, not worth a network round trip.
            callback.onSearchError("empty keyword")
            return
        }
        Log.w(ProviderConstants.TAG, "search start keyword=$trimmed")
        executor.execute {
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
    fun saveSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val now = System.currentTimeMillis()
        executor.execute {
            try {
                dao.insertSongs(songs.map { it.toEntity(now) })
                Log.i(ProviderConstants.TAG, "database insert count=${songs.size}")
            } catch (e: Exception) {
                // Never crash the UI; the provider simply keeps showing the previous rows.
                Log.e(ProviderConstants.TAG, "database insert failed", e)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun Song.toEntity(now: Long) = SongEntity(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        duration = durationMs,
        coverUrl = coverUrl,
        updatedAt = now,
    )
}
