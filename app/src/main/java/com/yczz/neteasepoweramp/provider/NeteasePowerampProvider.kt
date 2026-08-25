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
     * Serves the test audio as a direct read-only file descriptor over the extracted local file.
     * The fd is seekable, so Poweramp gets seeking/wave-seekbar/tag reading without extra protocol work.
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

        val file: File =
            try {
                val isNeteaseSong = ProviderConstants.parseSongId(documentId) != null
                if (!isNeteaseSong && documentId != ProviderConstants.TRACK_DOC_ID) {
                    throw FileNotFoundException("unknown document $documentId")
                }
                if (isNeteaseSong) {
                    // Phase 2 is display-only: NetEase songs temporarily stream the bundled test
                    // audio so Poweramp playback stays functional until phase-3 URL support lands.
                    Log.i(TAG, "netease song requested, serving test audio placeholder documentId=$documentId")
                } else {
                    Log.i(TAG, "track requested documentId=$documentId")
                }
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
        // Phase 1 has no URL tracks, so CALL_GET_URL is not needed. CALL_RESCAN may arrive any time;
        // returning null (like the official handleRescan does) tells Poweramp there is nothing to refresh.
        Log.w(TAG, "call method=$method arg=$arg")
        return super.call(method, arg, extras)
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

        val DEFAULT_TRACK_PROJECTION = DEFAULT_DOCUMENT_PROJECTION
    }
}
