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
    fun openDocumentReturnsReadableSeekableAudioForLocalTrackAndRemoteMock() {
        // Local phase-1 track: direct file fd.
        resolver.openFileDescriptor(documentUri(ProviderConstants.TRACK_DOC_ID), "r")!!.use { pfd ->
            assertTrue("local fd not readable", pfd.statSize > 100_000L)
        }
        // Remote phase-3 mock song: proxy fd backed by Range GETs. statSize can be 0/-1 for
        // proxy fds depending on when the framework stats them, so assert on actual readability:
        // read the first bytes and verify MP3 framing (ID3 tag or frame sync).
        resolver.openFileDescriptor(documentUri(ProviderConstants.songDocId(ProviderConstants.MOCK_REMOTE_SONG_ID)), "r")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val head = ByteArray(64 * 1024)
                var total = 0
                while (total < head.size) {
                    val read = input.read(head, total, head.size - total)
                    if (read < 0) break
                    total += read
                }
                assertTrue("remote stream produced no bytes", total > 0)
                val isId3 = total >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
                val isFrameSync = total >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
                assertTrue(
                    "remote bytes are not MP3 (total=$total, head=${head[0]}${head[1]}${head[2]}${head[3]})",
                    isId3 || isFrameSync,
                )
            }
        }
    }

    @Test
    fun openDocumentForUnresolvableSongFailsCleanly() {
        // Songs without a playback source (not the seeded mock id) must surface as a clean
        // client error, never crash Poweramp.
        var threw = false
        try {
            resolver.openFileDescriptor(documentUri(ProviderConstants.songDocId(999_999_002L)), "r")?.close()
        } catch (expected: java.io.FileNotFoundException) {
            threw = true
        }
        assertTrue("unresolvable song must fail with FileNotFoundException", threw)
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
