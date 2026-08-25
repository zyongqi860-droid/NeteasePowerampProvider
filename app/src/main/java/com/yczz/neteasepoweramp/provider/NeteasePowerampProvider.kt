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
import com.yczz.neteasepoweramp.database.SongDao
import com.yczz.neteasepoweramp.playback.AudioProxyFd
import com.yczz.neteasepoweramp.playback.CachingPlaybackResolver
import com.yczz.neteasepoweramp.playback.MockPlaybackResolver
import com.yczz.neteasepoweramp.playback.PlaybackResolver
import com.yczz.neteasepoweramp.service.AudioStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private lateinit var playbackResolver: PlaybackResolver
    private lateinit var streamClient: AudioStreamClient

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
        // Phase-3 playback stack: caching decorator over the swappable source resolver.
        // Phase 4 replaces MockPlaybackResolver with a licensed music-service implementation.
        streamClient = AudioStreamClient()
        playbackResolver = CachingPlaybackResolver(MockPlaybackResolver(), songDao)
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
                    val songId = ProviderConstants.parseSongId(documentId)
                    if (songId == null) throw FileNotFoundException("unknown document $documentId")
                    val song = songDao.getSong(songId)
                        ?: throw FileNotFoundException("song $songId is not saved in the provider database")
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        ProviderCursorBuilder.fillSongRow(newRow(), song, servedSongSize())
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
                            ProviderCursorBuilder.fillSongRow(newRow(), song, servedSongSize())
                        }
                    }
                }

                ProviderConstants.TEST_FOLDER_DOC_ID ->
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        ProviderCursorBuilder.fillTestTrackRow(newRow(), testAudioManager.fileSizeOrFallback(), apkInstallTime)
                    }

                else -> throw FileNotFoundException("unknown parent document $parentDocumentId")
            }
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (th: Throwable) {
            Log.e(TAG, "queryChildDocuments parentDocumentId=$parentDocumentId", th)
            null
        }
    }

    /** Album art for the test track (only advertised via FLAG_SUPPORTS_THUMBNAIL on the track row). */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        Log.w(TAG, "openDocumentThumbnail documentId=$documentId sizeHint=$sizeHint")
        if (documentId != ProviderConstants.TRACK_DOC_ID) throw FileNotFoundException(documentId)
        return try {
            checkNotNull(context).resources.openRawResourceFd(R.raw.test_cover)
        } catch (e: IOException) {
            throw FileNotFoundException("no thumbnail for $documentId: ${e.message}")
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
     */
    private fun openRemoteAudio(documentId: String, songId: Long): ParcelFileDescriptor {
        Log.i(TAG, "track requested documentId=$documentId")
        val source = try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    Log.i(TAG, "resolve playback source songId=$songId")
                    playbackResolver.resolve(songId)
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
        val songId = ProviderConstants.parseSongId(arg) ?: run {
            Log.w(TAG, "get_url for non-song document $arg")
            return null
        }
        return try {
            val source = runBlocking {
                withContext(Dispatchers.IO) {
                    Log.i(TAG, "resolve playback source (get_url) songId=$songId")
                    playbackResolver.resolve(songId)
                }
            }
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

    // ---------------------------------------------------------------------- helpers

    /**
     * SIZE reported for NetEase songs. Phase 2 serves the test audio file for playback, so the
     * honest answer is that file's size; phase 3 will replace this with real per-song sizes.
     */
    private fun servedSongSize(): Long = testAudioManager.fileSizeOrFallback()

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

        /** Label of the "搜索结果" folder shown in Poweramp and the system picker. */
        const val SEARCH_FOLDER_DISPLAY_NAME = "搜索结果"

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
        )
    }
}
