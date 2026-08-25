package com.yczz.neteasepoweramp.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.database.SongEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device provider contract tests (phase 1 + phase 2 hierarchy).
 *
 * They run inside the app process, so the exported DocumentsProvider is accessible without
 * the MANAGE_DOCUMENTS permission that adb shell (uid 2000) deliberately lacks.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDeviceTest {

    private lateinit var resolver: ContentResolver

    /** Fixture song id far away from real NetEase ids to avoid collisions with user-saved rows. */
    private val fixtureSongId = 999_999_001L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = context.contentResolver
    }

    @After
    fun tearDown() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.get(context).songDao().deleteSong(fixtureSongId)
    }

    @Test
    fun rootsExposeNeteaseRoot() {
        resolver.query(DocumentsContract.buildRootsUri(ProviderConstants.AUTHORITY), null, null, null, null)!!.use { c ->
            assertTrue("queryRoots returned no rows", c.moveToFirst())
            assertEquals(
                ProviderConstants.ROOT_ID,
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)),
            )
            assertEquals(
                ProviderConstants.ROOT_DOC_ID,
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)),
            )
            val flags = c.getInt(c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_FLAGS))
            assertTrue("root must advertise FLAG_SUPPORTS_IS_CHILD", flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD != 0)
        }
    }

    @Test
    fun rootListsSearchFolderAndTestFolder() {
        childDocuments(ProviderConstants.ROOT_DOC_ID).use { c ->
            val ids = collectColumn(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            assertTrue("search folder missing: $ids", ids.contains(ProviderConstants.SEARCH_FOLDER_PATH))
            assertTrue("test folder missing: $ids", ids.contains(ProviderConstants.TEST_FOLDER_DOC_ID))
        }
    }

    @Test
    fun testFolderListsFixedTrack() {
        childDocuments(ProviderConstants.TEST_FOLDER_DOC_ID).use { c ->
            val ids = collectColumn(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            assertTrue("test track missing: $ids", ids.contains(ProviderConstants.TRACK_DOC_ID))
        }
        document(ProviderConstants.TRACK_DOC_ID).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                TestTrack.MIME_TYPE,
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)),
            )
        }
    }

    @Test
    fun savedSongIsListedWithMetadata() {
        val dao = AppDatabase.get(context()).songDao()
        dao.insertSongs(listOf(fixtureSong()))

        childDocuments(ProviderConstants.SEARCH_FOLDER_PATH).use { c ->
            var found = false
            while (c.moveToNext()) {
                if (fixtureSongDocId() == c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))) {
                    found = true
                    assertEquals("晴天", c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).replace(".mp3", ""))
                    assertEquals(
                        "周杰伦",
                        c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.AudioColumns.ARTIST)),
                    )
                    assertEquals(
                        269_000L,
                        c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.AudioColumns.DURATION)),
                    )
                }
            }
            assertTrue("saved song not listed under search folder", found)
        }

        document(fixtureSongDocId()).use { c ->
            assertTrue("single document query failed for saved song", c.moveToFirst())
        }
    }

    @Test
    fun unknownSongAndUnknownDocumentFailCleanly() {
        // DocumentsProvider reports missing documents as a null cursor from ContentResolver.query
        // (the framework swallows the internal FileNotFoundException) - that is the clean,
        // client-safe failure mode; a crash or non-null garbage cursor would be wrong.
        val unknownSong = resolver.query(documentUri(ProviderConstants.songDocId(888_888_888L)), null, null, null, null)
        assertTrue("unknown song must yield null cursor", unknownSong == null)

        val unknownFolder = resolver.query(documentUri("root/does_not_exist"), null, null, null, null)
        assertTrue("unknown folder must yield null cursor", unknownFolder == null)
    }

    @Test
    fun openDocumentReturnsReadableSeekableAudioForBothNodes() {
        AppDatabase.get(context()).songDao().insertSongs(listOf(fixtureSong()))
        try {
            for (docId in arrayOf(ProviderConstants.TRACK_DOC_ID, fixtureSongDocId())) {
                resolver.openFileDescriptor(documentUri(docId), "r")!!.use { pfd ->
                    assertTrue("fd not readable for $docId", pfd.statSize > 100_000L)
                }
            }
        } finally {
            AppDatabase.get(context()).songDao().deleteSong(fixtureSongId)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureSongDocId(): String = ProviderConstants.songDocId(fixtureSongId)

    private fun fixtureSong() = SongEntity(
        songId = fixtureSongId,
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        duration = 269_000L,
        coverUrl = "",
        updatedAt = System.currentTimeMillis(),
    )

    private fun childDocuments(parentDocumentId: String): Cursor =
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(ProviderConstants.AUTHORITY, ProviderConstants.ROOT_DOC_ID),
                parentDocumentId,
            ),
            null, null, null, null,
        ) ?: throw AssertionError("null cursor for children of $parentDocumentId")

    private fun document(documentId: String): Cursor =
        resolver.query(documentUri(documentId), null, null, null, null)
            ?: throw AssertionError("null cursor for document $documentId")

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUri(ProviderConstants.AUTHORITY, documentId)

    private fun collectColumn(c: Cursor, column: String): List<String> {
        val out = ArrayList<String>()
        val idx = c.getColumnIndexOrThrow(column)
        while (c.moveToNext()) out.add(c.getString(idx))
        return out
    }
}
