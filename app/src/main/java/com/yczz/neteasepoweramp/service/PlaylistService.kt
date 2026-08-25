package com.yczz.neteasepoweramp.service

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.database.PlaylistEntity
import com.yczz.neteasepoweramp.database.PlaylistTrackEntity
import com.yczz.neteasepoweramp.database.toEntity
import com.yczz.neteasepoweramp.model.PlaylistDetail
import com.yczz.neteasepoweramp.netease.NeteaseClient
import com.yczz.neteasepoweramp.provider.ProviderConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Phase-4 playlist import: source API -> Room (playlists + playlist_tracks + songs), so the
 * provider's "我的歌单" folder can list everything without any network on its side.
 *
 * Only data the user can legitimately read is fetched; playlists that require login surface a
 * "login required" error and are never bypassed.
 */
class PlaylistService(context: Context) {

    interface ImportCallback {
        /** Import finished; [songCount] songs were stored for the playlist. */
        fun onImportSuccess(playlistName: String, songCount: Int)

        /** Non-sensitive failure description (network / api / login required). */
        fun onImportError(message: String)
    }

    private val appContext = context.applicationContext
    private val client = NeteaseClient()
    private val songDao = AppDatabase.get(appContext).songDao()
    private val playlistDao = AppDatabase.get(appContext).playlistDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fetches one public playlist by id and stores playlist + ordered membership + songs. */
    fun importPlaylist(playlistId: Long, callback: ImportCallback) {
        if (playlistId <= 0L) {
            callback.onImportError("invalid playlist id")
            return
        }
        Log.w(ProviderConstants.TAG, "playlist import start playlistId=$playlistId")
        scope.launch {
            try {
                val detail = client.fetchPlaylist(playlistId)
                storePlaylist(detail)
                Log.i(
                    ProviderConstants.TAG,
                    "playlist import done playlistId=$playlistId songs=${detail.songs.size}",
                )
                callback.onImportSuccess(detail.playlist.name, detail.songs.size)
            } catch (e: IOException) {
                Log.e(ProviderConstants.TAG, "playlist import network failure", e)
                callback.onImportError("network failure: ${e.message}")
            } catch (e: NeteaseClient.NeteaseApiException) {
                Log.e(ProviderConstants.TAG, "playlist import api failure", e)
                callback.onImportError(e.message ?: "api failure")
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "playlist import unexpected failure", e)
                callback.onImportError("unexpected failure")
            }
        }
    }

    /** Blocking variant used by tests; runs the same storage path as [importPlaylist]. */
    fun storePlaylist(detail: PlaylistDetail) {
        val now = System.currentTimeMillis()
        songDao.insertSongs(detail.songs.map { it.toEntity(now) })
        playlistDao.insertPlaylists(
            listOf(
                PlaylistEntity(
                    playlistId = detail.playlist.playlistId,
                    name = detail.playlist.name,
                    coverUrl = detail.playlist.coverUrl,
                    trackCount = detail.playlist.trackCount,
                    updatedAt = now,
                ),
            ),
        )
        playlistDao.replacePlaylistTracks(
            detail.playlist.playlistId,
            detail.songs.mapIndexed { index, song ->
                PlaylistTrackEntity(
                    playlistId = detail.playlist.playlistId,
                    songId = song.songId,
                    position = index,
                )
            },
        )
        Log.i(ProviderConstants.TAG, "database insert playlist songs=${detail.songs.size}")
        notifyProviderChanged()
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

    fun shutdown() {
        scope.cancel()
    }
}
