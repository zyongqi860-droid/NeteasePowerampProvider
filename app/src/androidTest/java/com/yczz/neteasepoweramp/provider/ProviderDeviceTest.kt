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
    fun rootListsAllFolders() {
        childDocuments(ProviderConstants.ROOT_DOC_ID).use { c ->
            val ids = collectColumn(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            assertTrue("search folder missing: $ids", ids.contains(ProviderConstants.SEARCH_FOLDER_PATH))
            assertTrue("playlists folder missing: $ids", ids.contains(ProviderConstants.PLAYLISTS_FOLDER_PATH))
            assertTrue("test folder missing: $ids", ids.contains(ProviderConstants.TEST_FOLDER_DOC_ID))
        }
    }

    @Test
    fun importedPlaylistIsListedWithOrderedSongs() {
        val dao = AppDatabase.get(context()).songDao()
        val playlistDao = AppDatabase.get(context()).playlistDao()
        val pid = 888_888_001L
        val songA = fixtureSong().copy(songId = 999_999_012L, title = "A曲")
        val songB = fixtureSong().copy(songId = 999_999_011L, title = "B曲")
        dao.insertSongs(listOf(songA, songB))
        playlistDao.insertPlaylists(
            listOf(
                com.yczz.neteasepoweramp.database.PlaylistEntity(
                    playlistId = pid,
                    name = "测试歌单",
                    coverUrl = "",
                    trackCount = 2,
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        // Deliberately store B before A: the provider must honor playlist_tracks positions.
        playlistDao.replacePlaylistTracks(
            pid,
            listOf(
                com.yczz.neteasepoweramp.database.PlaylistTrackEntity(pid, songA.songId, 0),
                com.yczz.neteasepoweramp.database.PlaylistTrackEntity(pid, songB.songId, 1),
            ),
        )
        try {
            val playlistDocId = ProviderConstants.playlistDocId(pid)
            childDocuments(ProviderConstants.PLAYLISTS_FOLDER_PATH).use { c ->
                val ids = collectColumn(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                assertTrue("imported playlist missing: $ids", ids.contains(playlistDocId))
            }
            childDocuments(playlistDocId).use { c ->
                val names = collectColumn(c, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                assertEquals("playlist order must follow positions", listOf("A曲.mp3", "B曲.mp3"), names)
            }
            document(ProviderConstants.playlistSongDocId(pid, songA.songId)).use { c ->
                assertTrue("playlist song document query failed", c.moveToFirst())
            }
        } finally {
            playlistDao.deletePlaylist(pid)
            dao.deleteSong(songA.songId)
            dao.deleteSong(songB.songId)
        }
    }

    /**
     * End-to-end over the real network: NetEase v6 playlist API -> parse -> Room (songs +
     * playlists + playlist_tracks) -> provider cursor. Fetches a real public chart playlist
     * (网易云热歌榜) but stores it under the fixture id; cleans up afterwards.
     */
    @Test
    fun realPlaylistImportFlowsThroughProvider() {
        val pid = 888_888_002L
        val context = context()
        val service = com.yczz.neteasepoweramp.service.PlaylistService(context)
        val playlistDao = AppDatabase.get(context).playlistDao()
        val detail = com.yczz.neteasepoweramp.netease.NeteaseClient().fetchPlaylist(REAL_CHART_PLAYLIST_ID)
        assertTrue("v6 api returned no songs", detail.songs.isNotEmpty())
        service.storePlaylist(detail.copy(playlist = detail.playlist.copy(playlistId = pid)))
        try {
            val stored = playlistDao.getPlaylist(pid)
            assertNotNull("playlist row missing", stored)
            childDocuments(ProviderConstants.PLAYLISTS_FOLDER_PATH).use { c ->
                val ids = collectColumn(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                assertTrue("imported playlist not listed: $ids", ids.contains(ProviderConstants.playlistDocId(pid)))
            }
            childDocuments(ProviderConstants.playlistDocId(pid)).use { c ->
                val names = collectColumn(c, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                assertEquals("listed count != api count", detail.songs.size, names.size)
            }
        } finally {
            playlistDao.deletePlaylist(pid)
            detail.songs.forEach { AppDatabase.get(context).songDao().deleteSong(it.songId) }
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

    /**
     * End-to-end for the phase-5 blocker (song/url code=400): resolve a real, free NetEase
     * song through the exact code path Poweramp uses - CALL_GET_URL with a full tree content
     * URI - and verify the returned URL actually serves MP3 bytes over the network.
     */
    @Test
    fun callGetUrlReturnsPlayableUrlForRealNeteaseSong() {
        val dao = AppDatabase.get(context()).songDao()
        dao.insertSongs(listOf(realSong()))
        try {
            // Exact argument shape observed from Poweramp on device (encoded tree document URI).
            val arg = "content://${ProviderConstants.AUTHORITY}/tree/root/document/" +
                android.net.Uri.encode(ProviderConstants.songDocId(REAL_SONG_ID))
            val result = resolver.call(
                Uri.parse("content://${ProviderConstants.AUTHORITY}"),
                com.maxmpz.poweramp.player.TrackProviderConsts.CALL_GET_URL,
                arg,
                null,
            )
            assertNotNull("CALL_GET_URL returned null bundle", result)
            val url = result!!.getString(com.maxmpz.poweramp.player.TrackProviderConsts.COLUMN_URL)
            assertNotNull("CALL_GET_URL bundle has no url", url)
            assertTrue("url must be https to avoid cleartext policy: $url", url!!.startsWith("https://"))

            // The handed-off URL must stream real audio bytes by itself (that is what Poweramp does).
            val head = fetchHead(url)
            assertTrue("resolved url is not MP3: ${head.copyOf(4).toHexString()}", isMp3(head))
        } finally {
            dao.deleteSong(REAL_SONG_ID)
        }
    }

    /** openDocument path for a real NetEase song: proxy fd over Range GETs must yield MP3 bytes. */
    @Test
    fun openDocumentStreamsRealNeteaseSong() {
        val dao = AppDatabase.get(context()).songDao()
        dao.insertSongs(listOf(realSong()))
        try {
            resolver.openFileDescriptor(documentUri(ProviderConstants.songDocId(REAL_SONG_ID)), "r")!!.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    val head = ByteArray(64 * 1024)
                    var total = 0
                    while (total < head.size) {
                        val read = input.read(head, total, head.size - total)
                        if (read < 0) break
                        total += read
                    }
                    assertTrue("remote stream produced no bytes", total >= 3)
                    assertTrue("remote bytes are not MP3 (total=$total)", isMp3(head))
                }
            }
        } finally {
            dao.deleteSong(REAL_SONG_ID)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A known free NetEase song id (used by the on-device playback chain tests). */
    private val REAL_SONG_ID = 5_257_138L

    /** NetEase 热歌榜 - a public chart playlist that answers anonymously with full tracks. */
    private val REAL_CHART_PLAYLIST_ID = 3_778_678L

    private fun realSong() = SongEntity(
        songId = REAL_SONG_ID,
        title = "Escape",
        artist = "NetEase Test",
        album = "Device Test",
        duration = 0L,
        coverUrl = "",
        updatedAt = System.currentTimeMillis(),
    )

    private fun isMp3(head: ByteArray): Boolean {
        val isId3 = head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        val isFrameSync = head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
        return isId3 || isFrameSync
    }

    private fun fetchHead(url: String): ByteArray {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            assertTrue("resolved url HTTP ${response.code}", response.isSuccessful)
            val body = response.body ?: throw AssertionError("empty body")
            val sink = java.io.ByteArrayOutputStream()
            val src = body.byteStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < buf.size) {
                val read = src.read(buf, total, buf.size - total)
                if (read < 0) break
                total += read
            }
            sink.write(buf, 0, total)
            return sink.toByteArray()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

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
