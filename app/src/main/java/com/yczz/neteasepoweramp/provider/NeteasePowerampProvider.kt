package com.yczz.neteasepoweramp.provider

import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import com.maxmpz.poweramp.player.TrackProviderConsts
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.database.PlaybackHistoryRepository
import com.yczz.neteasepoweramp.database.PlaylistDao
import com.yczz.neteasepoweramp.database.SongDao
import com.yczz.neteasepoweramp.playback.AudioProxyFd
import com.yczz.neteasepoweramp.playback.CachingPlaybackResolver
import com.yczz.neteasepoweramp.playback.MockPlaybackResolver
import com.yczz.neteasepoweramp.playback.NeteasePlaybackResolver
import com.yczz.neteasepoweramp.playback.PlaybackResolver
import com.yczz.neteasepoweramp.service.AlbumArtService
import com.yczz.neteasepoweramp.service.AudioStreamClient
import com.yczz.neteasepoweramp.netease.NeteaseCookieStore
import com.yczz.neteasepoweramp.lyrics.LyricService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Arrays

/**
 * Minimal Poweramp Track Provider (phases 1-2).
 *
 * Hierarchy:
 * ```
 * root (Netease Poweramp / 网易云音乐)
 * ├── root/netease_search ("搜索结果", cached songs saved from MainActivity)
 * │     └── root/netease_search/song_<songId>
 * └── root/test_folder (Test Music)
 *       └── root/test_folder/track_test_001 (Poweramp Provider Test)
 * ```
 *
 * The test track is served as a direct seekable file descriptor pointing at the extracted
 * res/raw/test_audio.mp3 copy, which is the officially recommended approach for file-backed
 * tracks (see poweramp_provider_example readme: wave-seekbar, seeking and local tag reading work
 * out of the box for seekable fds). NetEase songs reuse the same fd as an audio placeholder:
 * phase 2 is display-only, real playback URLs arrive in phase 3.
 *
 * THREADING: DocumentsProvider methods may be called concurrently from multiple binder threads
 * (Poweramp also opens up to 2 tracks in parallel for crossfade). All shared state below is
 * immutable after [onCreate]; the only lazily initialized piece, the extracted test audio file,
 * is guarded by a lock inside [TestAudioManager]. Room handles its own synchronization; song
 * queries are small blocking reads that are safe on binder threads.
 */
class NeteasePowerampProvider : DocumentsProvider() {

    /** APK install/update time, used as a stable COLUMN_LAST_MODIFIED so Poweramp can scan incrementally. */
    @Volatile
    private var apkInstallTime = 0L

    private lateinit var testAudioManager: TestAudioManager
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var albumArtService: AlbumArtService
    private lateinit var lyricService: LyricService
    private lateinit var playbackResolver: PlaybackResolver
    private lateinit var streamClient: AudioStreamClient
    private lateinit var historyRepository: PlaybackHistoryRepository

    override fun onCreate(): Boolean {
        val context = checkNotNull(context)
        apkInstallTime = try {
            val info = context.packageManager.getPackageInfo(context.applicationInfo.packageName, 0)
            if (info.lastUpdateTime > 0L) info.lastUpdateTime else info.firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "onCreate", e)
            System.currentTimeMillis()
        }
        testAudioManager = TestAudioManager(context.applicationContext)
        songDao = AppDatabase.get(context.applicationContext).songDao()
        playlistDao = AppDatabase.get(context.applicationContext).playlistDao()
        albumArtService = AlbumArtService(context.applicationContext)
        lyricService = LyricService(context.applicationContext)
        // Phase-5 playback stack: caching decorator over the real NetEase URL resolver.
        // NeteasePlaybackResolver delegates the phase-1 mock test track to MockPlaybackResolver.
        streamClient = AudioStreamClient()
        // Read the cookie lazily per resolve so Settings edits apply without restarting the process.
        val cookieStore = NeteaseCookieStore(context.applicationContext)
        playbackResolver = CachingPlaybackResolver(
            NeteasePlaybackResolver(cookieProvider = { cookieStore.cookieHeader() }),
            songDao,
        )
        historyRepository = PlaybackHistoryRepository(context.applicationContext)
        // Extract up front so the first playback does not pay for the copy; later calls are cheap existence checks.
        try {
            testAudioManager.ensureTestAudioFile()
        } catch (e: FileNotFoundException) {
            // Already logged; openDocument will surface this properly if it persists.
        }
        Log.w(TAG, "Provider initialized authority=${ProviderConstants.AUTHORITY} lastModified=$apkInstallTime")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor? {
        Log.w(TAG, "queryRoots projection=${Arrays.toString(projection)}")
        return try {
            MatrixCursor(resolveRootProjection(projection)).apply {
                newRow().apply {
                    add(Root.COLUMN_ROOT_ID, ProviderConstants.ROOT_ID)
                    add(Root.COLUMN_TITLE, ProviderConstants.ROOT_TITLE)
                    add(Root.COLUMN_SUMMARY, ProviderConstants.ROOT_SUMMARY)
                    // Required by Poweramp so sub-folder selection works in Music Folders dialog.
                    add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                    add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                    add(Root.COLUMN_DOCUMENT_ID, ProviderConstants.ROOT_DOC_ID)
                }
            }
        } catch (th: Throwable) {
            Log.e(TAG, "queryRoots", th)
            null
        }
    }

    /**
     * Used by the Android picker to show folders/tracks and by Poweramp to retrieve metadata
     * during library scans and for Info/Tags UI.
     */
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor? {
        Log.w(TAG, "queryDocument documentId=$documentId projection=${Arrays.toString(projection)}")
        return try {
            when (documentId) {
                ProviderConstants.ROOT_DOC_ID -> MatrixCursor(resolveDocumentProjection(projection)).apply {
                    newRow().apply {
                        // Root has child folders, so hint HAS_SUBDIRS for Poweramp's tree expansion.
                        ProviderCursorBuilder.fillFolderRow(
                            this,
                            documentId,
                            ProviderConstants.ROOT_TITLE,
                            apkInstallTime,
                            TrackProviderConsts.FLAG_HAS_SUBDIRS,
                        )
                    }
                }

                ProviderConstants.SEARCH_FOLDER_PATH ->
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                documentId,
                                SEARCH_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                    }

                ProviderConstants.PLAYLISTS_FOLDER_PATH ->
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                documentId,
                                PLAYLISTS_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                    }

                ProviderConstants.TEST_FOLDER_DOC_ID ->
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                documentId,
                                ProviderConstants.TEST_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                    }

                ProviderConstants.TRACK_DOC_ID ->
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        ProviderCursorBuilder.fillTestTrackRow(newRow(), testAudioManager.fileSizeOrFallback(), apkInstallTime)
                    }

                else -> {
                    val playlistId = ProviderConstants.parsePlaylistId(documentId)
                    if (playlistId != null) {
                        val playlist = playlistDao.getPlaylist(playlistId)
                            ?: throw FileNotFoundException("playlist $playlistId is not imported")
                        MatrixCursor(resolveDocumentProjection(projection)).apply {
                            newRow().apply {
                                ProviderCursorBuilder.fillPlaylistRow(
                                    this,
                                    documentId,
                                    playlist.name,
                                    playlist.updatedAt,
                                )
                            }
                        }
                    } else {
                        val songId = ProviderConstants.parseSongId(documentId)
                        if (songId == null) throw FileNotFoundException("unknown document $documentId")
                        val song = songDao.getSong(songId)
                            ?: throw FileNotFoundException("song $songId is not saved in the provider database")
                        MatrixCursor(resolveTrackProjection(projection)).apply {
                            ProviderCursorBuilder.fillSongRow(
                                newRow(),
                                documentId,
                                song,
                                servedSongSize(),
                                lyricsFor(song.songId),
                            )
                        }
                    }
                }
            }
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            // Never crash the client process (Poweramp) - log and report failure per official guidance.
            Log.e(TAG, "queryDocument documentId=$documentId", th)
            null
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        Log.w(
            TAG,
            "queryChildDocuments parentDocumentId=$parentDocumentId projection=${Arrays.toString(projection)} sortOrder=$sortOrder",
        )
        return try {
            when (parentDocumentId) {
                ProviderConstants.ROOT_DOC_ID ->
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                ProviderConstants.SEARCH_FOLDER_PATH,
                                SEARCH_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                ProviderConstants.PLAYLISTS_FOLDER_PATH,
                                PLAYLISTS_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                        newRow().apply {
                            ProviderCursorBuilder.fillFolderRow(
                                this,
                                ProviderConstants.TEST_FOLDER_DOC_ID,
                                ProviderConstants.TEST_FOLDER_DISPLAY_NAME,
                                apkInstallTime,
                                TrackProviderConsts.FLAG_NO_SUBDIRS,
                            )
                        }
                    }

                ProviderConstants.SEARCH_FOLDER_PATH -> {
                    // Blocking Room read on the binder thread: tiny table, no network involved.
                    val songs = songDao.getSongs()
                    Log.i(TAG, "provider query songs count=${songs.size}")
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        for (song in songs) {
                            ProviderCursorBuilder.fillSongRow(
                                newRow(),
                                ProviderConstants.songDocId(song.songId),
                                song,
                                servedSongSize(),
                                lyricsFor(song.songId),
                            )
                        }
                    }
                }

                ProviderConstants.PLAYLISTS_FOLDER_PATH -> {
                    val playlists = playlistDao.getPlaylists()
                    Log.i(TAG, "provider query playlists count=${playlists.size}")
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        for (playlist in playlists) {
                            newRow().apply {
                                ProviderCursorBuilder.fillPlaylistRow(
                                    this,
                                    ProviderConstants.playlistDocId(playlist.playlistId),
                                    playlist.name,
                                    playlist.updatedAt,
                                )
                            }
                        }
                    }
                }

                ProviderConstants.TEST_FOLDER_DOC_ID ->
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        ProviderCursorBuilder.fillTestTrackRow(newRow(), testAudioManager.fileSizeOrFallback(), apkInstallTime)
                    }

                else -> {
                    val playlistId = ProviderConstants.parsePlaylistId(parentDocumentId)
                        ?: throw FileNotFoundException("unknown parent document $parentDocumentId")
                    val songs = playlistDao.getPlaylistSongs(playlistId)
                    Log.i(TAG, "provider query playlist songs count=${songs.size} playlistId=$playlistId")
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        for (song in songs) {
                            ProviderCursorBuilder.fillSongRow(
                                newRow(),
                                ProviderConstants.playlistSongDocId(playlistId, song.songId),
                                song,
                                servedSongSize(),
                                lyricsFor(song.songId),
                            )
                        }
                    }
                }
            }
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "queryChildDocuments parentDocumentId=$parentDocumentId", th)
            null
        }
    }

    /** Album art: test track uses the bundled cover; songs fetch/cache via AlbumArtService (phase 4). */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        Log.w(TAG, "openDocumentThumbnail documentId=$documentId sizeHint=$sizeHint")
        val songId = ProviderConstants.parseSongId(documentId)
        if (songId == null) {
            if (documentId != ProviderConstants.TRACK_DOC_ID) throw FileNotFoundException(documentId)
            return try {
                checkNotNull(context).resources.openRawResourceFd(R.raw.test_cover)
            } catch (e: IOException) {
                throw FileNotFoundException("no thumbnail for $documentId: ${e.message}")
            }
        }
        val song = songDao.getSong(songId) ?: throw FileNotFoundException(documentId)
        val cover = albumArtService.getCachedOrFetch(song.coverUrl)
            ?: throw FileNotFoundException("no artwork for $documentId")
        return try {
            val pfd = ParcelFileDescriptor.open(cover, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(pfd, 0, pfd.statSize)
        } catch (e: IOException) {
            throw FileNotFoundException("cannot open artwork for $documentId: ${e.message}")
        }
    }

    /**
     * Serves audio for Poweramp.
     *
     * - test_track: direct read-only fd over the extracted local test file (phase-1 behavior).
     * - netease songs: PlaybackResolver -> PlaybackSource -> remote stream.
     *   API 26+ uses StorageManager.openProxyFileDescriptor (Range GET per read, seekable,
     *   nothing downloaded up front); older devices fall back to a one-time cached download.
     *   Any resolver/stream failure surfaces as FileNotFoundException so Poweramp shows a
     *   playback error instead of crashing (task section 9).
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        Log.w(TAG, "openDocument documentId=$documentId mode=$mode callingPackage=${callingPackage}")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_READ_ONLY) == 0) {
            throw IllegalAccessException("write modes are not supported, mode=$mode")
        }
        signal?.throwIfCanceled()

        val songId = ProviderConstants.parseSongId(documentId)
        if (songId == null && documentId != ProviderConstants.TRACK_DOC_ID) {
            throw FileNotFoundException("unknown document $documentId")
        }

        if (songId == null) {
            Log.i(TAG, "track requested documentId=$documentId")
            return openLocalTestAudio(documentId)
        }
        return openRemoteAudio(documentId, songId)
    }

    private fun openLocalTestAudio(documentId: String): ParcelFileDescriptor {
        val file: File = try {
            testAudioManager.ensureTestAudioFile()
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "openDocument failed to prepare audio", th)
            throw FileNotFoundException("cannot prepare test audio for $documentId")
        }
        Log.i(TAG, "test audio opened file=${file.name} size=${file.length()}")
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "openDocument", th)
            throw FileNotFoundException("cannot open $documentId")
        }
    }

    /**
     * Remote playback path. runBlocking on the binder thread is intentional: openDocument is a
     * synchronous contract and Poweramp expects it to block until the fd exists; the network
     * work itself happens on Dispatchers.IO / the proxy callback thread, never a main thread.
     *
     * Phase 5: added [RESOLVE_TIMEOUT_MS] so a hung resolver does not block the binder
     * thread indefinitely — the caller (Poweramp) sees a FileNotFoundException
     * and can surface a playback error to the user.
     */
    private fun openRemoteAudio(documentId: String, songId: Long): ParcelFileDescriptor {
        Log.i(TAG, "track requested documentId=$documentId")
        // Fire-and-forget: record play request (completed=false). Provider cannot reliably
        // know actual playback completion; Poweramp owns the player state. (Phase 5 reposition.)
        recordPlayRequest(songId)
        val source = try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        Log.i(TAG, "resolve playback source songId=$songId")
                        playbackResolver.resolve(songId)
                    }
                    if (resolved == null) {
                        throw FileNotFoundException("resolve timed out for song $songId")
                    }
                    resolved
                }
            }
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "resolve failed for songId=$songId", th)
            throw FileNotFoundException("no playback source for $documentId")
        }
        return try {
            AudioProxyFd.open(checkNotNull(context).applicationContext, source, streamClient)
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "remote stream open failed songId=$songId", th)
            throw FileNotFoundException("cannot stream $documentId")
        }
    }

    /**
     * Fire-and-forget: record a play *request* (not completion) on a background thread.
     * Provider cannot reliably know whether Poweramp finished playing the track;
     * [PlaybackHistoryRepository.recordPlay] is called with completed=false.
     */
    private fun recordPlayRequest(songId: Long) {
        Thread {
            try {
                runBlocking { historyRepository.recordPlay(songId, durationMs = 0L, completed = false) }
            } catch (th: Throwable) {
                Log.w(TAG, "history record play request failed songId=$songId", th)
            }
        }.apply { isDaemon = true; name = "play-request-$songId" }.start()
    }

    /** Called by SAF to validate hierarchy; must be overridden with Root.FLAG_SUPPORTS_IS_CHILD roots. */
    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val res =
            parentDocumentId != null && documentId != null && (
                (documentId == ProviderConstants.TEST_FOLDER_DOC_ID && parentDocumentId == ProviderConstants.ROOT_DOC_ID) ||
                    (documentId == ProviderConstants.SEARCH_FOLDER_PATH && parentDocumentId == ProviderConstants.ROOT_DOC_ID) ||
                    documentId.startsWith("$parentDocumentId/")
                )
        Log.w(TAG, "isChildDocument parentDocumentId=$parentDocumentId documentId=$documentId => $res")
        return res
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.w(TAG, "call method=$method arg=$arg")
        if (method == TrackProviderConsts.CALL_GET_URL && arg != null) {
            return handleGetUrl(arg)
        }
        // CALL_RESCAN may arrive any time; returning null (like the official handleRescan does)
        // tells Poweramp there is nothing to refresh.
        return super.call(method, arg, extras)
    }

    /**
     * Official dynamic-URL mechanism (TrackProviderConsts.DYNAMIC_URL / CALL_GET_URL): for
     * tracks advertised with COLUMN_URL=DYNAMIC_URL, Poweramp calls this at the moment of
     * playback instead of openDocument. We resolve through the same PlaybackResolver as the
     * proxy-fd path and hand Poweramp the URL + headers so its own player streams (and seeks)
     * natively. Unknown songs return null - Poweramp treats it as "no url available".
     */
    private fun handleGetUrl(arg: String): Bundle? {
        // Poweramp may pass either a raw documentId or a full content:// URI.
        // Extract the documentId from either form before parsing the songId.
        val documentId = extractDocumentId(arg)
        val songId = ProviderConstants.parseSongId(documentId) ?: run {
            Log.w(TAG, "get_url for non-song document $arg (parsed: $documentId)")
            return null
        }
        return try {
            val source = runBlocking {
                withContext(Dispatchers.IO) {
                    val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        Log.i(TAG, "resolve playback source (get_url) songId=$songId")
                        playbackResolver.resolve(songId)
                    }
                    if (resolved == null) {
                        Log.w(TAG, "get_url resolve timed out songId=$songId")
                        return@withContext null
                    }
                    resolved
                }
            } ?: return null
            Bundle().apply {
                putString(TrackProviderConsts.COLUMN_URL, source.url)
                if (source.headers.isNotEmpty()) {
                    // Single string, CRLF-separated "name: value" pairs (official bundle format).
                    putString(
                        TrackProviderConsts.COLUMN_HEADERS,
                        source.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" },
                    )
                }
            }
        } catch (th: Throwable) {
            Log.e(TAG, "get_url resolve failed songId=$songId", th)
            null
        }
    }

    /**
     * Poweramp's CALL_GET_URL passes the document reference as either:
     * - A raw documentId: "root/netease_search/song_123"
     * - A full content URI: "content://authority/tree/root/document/root%2Fnetease_search%2Fsong_123"
     * This extracts the plain documentId from either form.
     */
    private fun extractDocumentId(arg: String): String {
        if (!arg.startsWith("content://")) return arg
        // For content URIs, the documentId is the last path segment after "document/"
        val uri = android.net.Uri.parse(arg)
        // tree URIs: content://authority/tree/<rootId>/document/<documentId>
        val doc = uri.lastPathSegment
        // lastPathSegment may be the encoded documentId (e.g. "root%2Fnetease_search%2Fsong_123")
        return android.net.Uri.decode(doc ?: arg)
    }

    // ---------------------------------------------------------------------- helpers

    /**
     * SIZE reported for NetEase songs. Phase 2 serves the test audio file for playback, so the
     * honest answer is that file's size; phase 3 will replace this with real per-song sizes.
     */
    private fun servedSongSize(): Long = testAudioManager.fileSizeOrFallback()

    /**
     * Cached LRC for Poweramp lyrics columns. Cache miss: kick a background fetch so the next
     * scan (or Info/Tags open) can pick it up without blocking this binder call.
     */
    private fun lyricsFor(songId: Long): String? {
        val raw = try {
            lyricService.cachedRawLyrics(songId)
        } catch (e: Exception) {
            Log.w(TAG, "lyrics lookup failed songId=$songId", e)
            return null
        }
        if (raw == null) {
            lyricService.prefetch(songId)
            return null
        }
        return raw.takeIf { it.isNotBlank() }
    }

    private fun resolveProjection(projection: Array<out String>?, defaults: Array<String>): Array<String> =
        if (projection != null && projection.isNotEmpty()) {
            // Copy caller-supplied columns into a plain Array<String>; MatrixCursor matches named adds by column name.
            Array(projection.size) { i -> projection[i] }
        } else {
            defaults
        }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> =
        resolveProjection(projection, DEFAULT_ROOT_PROJECTION)

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> =
        resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION)

    private fun resolveTrackProjection(projection: Array<out String>?): Array<String> =
        resolveProjection(projection, DEFAULT_TRACK_PROJECTION)

    private companion object {
        const val TAG = ProviderConstants.TAG

        /** Timeout for playback URL resolution on binder threads (ms). */
        const val RESOLVE_TIMEOUT_MS = 15_000L

        /** Label of the "搜索结果" folder shown in Poweramp and the system picker. */
        const val SEARCH_FOLDER_DISPLAY_NAME = "搜索结果"

        /** Label of the "我的歌单" folder (phase 4). */
        const val PLAYLISTS_FOLDER_DISPLAY_NAME = "我的歌单"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_DOCUMENT_ID,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )

        // Track default projection also carries the metadata columns so clients that query with
        // defaults (and Poweramp's Info/Tags UI) can read TITLE/ARTIST/ALBUM/DURATION directly.
        val DEFAULT_TRACK_PROJECTION = arrayOf(
            *DEFAULT_DOCUMENT_PROJECTION,
            MediaStore.MediaColumns.TITLE,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.DURATION,
            TrackProviderConsts.COLUMN_GENRE,
            TrackProviderConsts.COLUMN_FLAGS,
            TrackProviderConsts.COLUMN_URL,
            TrackProviderConsts.COLUMN_TRACK_LYRICS,
            TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED,
        )
    }
}
