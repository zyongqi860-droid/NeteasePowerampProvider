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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Arrays

/**
 * Minimal Poweramp Track Provider (phase 1).
 *
 * Hierarchy:
 * ```
 * root (Netease Poweramp / 网易云音乐)
 * └── root/test_folder (Test Music)
 *     └── root/test_folder/track_test_001 (Poweramp Provider Test)
 * ```
 *
 * The track is served as a direct seekable file descriptor pointing at the extracted
 * res/raw/test_audio.mp3 copy, which is the officially recommended approach for file-backed
 * tracks (see poweramp_provider_example readme: wave-seekbar, seeking and local tag reading work
 * out of the box for seekable fds).
 *
 * THREADING: DocumentsProvider methods may be called concurrently from multiple binder threads
 * (Poweramp also opens up to 2 tracks in parallel for crossfade). All shared state below is
 * immutable after [onCreate]; the only lazily initialized piece, the extracted test audio file,
 * is guarded by a lock inside [TestAudioManager].
 */
class NeteasePowerampProvider : DocumentsProvider() {

    /** APK install/update time, used as a stable COLUMN_LAST_MODIFIED so Poweramp can scan incrementally. */
    @Volatile
    private var apkInstallTime = 0L

    private lateinit var testAudioManager: TestAudioManager

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
                        fillFolderRow(documentId, this, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                        // Same label as Root.COLUMN_TITLE - official example recommends consistent labels for roots.
                        add(Document.COLUMN_DISPLAY_NAME, ProviderConstants.ROOT_TITLE)
                    }
                }

                ProviderConstants.TEST_FOLDER_DOC_ID ->
                    MatrixCursor(resolveDocumentProjection(projection)).apply {
                        newRow().apply {
                            fillFolderRow(documentId, this, TrackProviderConsts.FLAG_NO_SUBDIRS)
                            add(Document.COLUMN_DISPLAY_NAME, ProviderConstants.TEST_FOLDER_DISPLAY_NAME)
                        }
                    }

                ProviderConstants.TRACK_DOC_ID ->
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        fillTestTrackRow(newRow())
                    }

                else -> throw FileNotFoundException("unknown document $documentId")
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
                            fillFolderRow(ProviderConstants.TEST_FOLDER_DOC_ID, this, TrackProviderConsts.FLAG_NO_SUBDIRS)
                            add(Document.COLUMN_DISPLAY_NAME, ProviderConstants.TEST_FOLDER_DISPLAY_NAME)
                        }
                    }

                ProviderConstants.TEST_FOLDER_DOC_ID ->
                    MatrixCursor(resolveTrackProjection(projection)).apply {
                        fillTestTrackRow(newRow())
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
                if (documentId != ProviderConstants.TRACK_DOC_ID) throw FileNotFoundException("unknown document $documentId")
                Log.i(TAG, "track requested documentId=$documentId")
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
                    (documentId == ProviderConstants.TRACK_DOC_ID && parentDocumentId == ProviderConstants.TEST_FOLDER_DOC_ID) ||
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

    private fun fillTestTrackRow(row: MatrixCursor.RowBuilder) {
        row.add(Document.COLUMN_DOCUMENT_ID, ProviderConstants.TRACK_DOC_ID)
        row.add(Document.COLUMN_MIME_TYPE, TestTrack.MIME_TYPE)
        row.add(Document.COLUMN_DISPLAY_NAME, TestTrack.DISPLAY_NAME)
        row.add(Document.COLUMN_LAST_MODIFIED, apkInstallTime)
        row.add(Document.COLUMN_SIZE, testAudioManager.fileSizeOrFallback())
        // TITLE/DURATION present => Poweramp uses cursor metadata and skips tag-scanning the file;
        // FLAG_SUPPORTS_THUMBNAIL then makes Poweramp fetch album art via openDocumentThumbnail.
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)

        row.add(MediaStore.MediaColumns.TITLE, TestTrack.TITLE)
        row.add(MediaStore.Audio.AudioColumns.ARTIST, TestTrack.ARTIST)
        row.add(MediaStore.Audio.AudioColumns.ALBUM, TestTrack.ALBUM)
        row.add(MediaStore.Audio.AudioColumns.TRACK, TestTrack.TRACK_NUMBER)
        row.add(MediaStore.Audio.AudioColumns.DURATION, TestTrack.DURATION_MS)
        row.add(TrackProviderConsts.COLUMN_GENRE, TestTrack.GENRE)
    }

    private fun fillFolderRow(documentId: String, row: MatrixCursor.RowBuilder, subdirHint: Int) {
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        row.add(Document.COLUMN_LAST_MODIFIED, apkInstallTime)
        // TrackProviderConsts.COLUMN_FLAGS (not Document.COLUMN_FLAGS) carries the folder hint that lets
        // Poweramp avoid loading children until the user expands the tree.
        if (subdirHint != 0) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, subdirHint)
        }
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
