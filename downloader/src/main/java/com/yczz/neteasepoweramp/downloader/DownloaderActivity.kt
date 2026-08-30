package com.yczz.neteasepoweramp.downloader

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.yczz.neteasepoweramp.downloader.netease.NeteaseApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

private data class DocRow(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String = "",
    val coverUrl: String = "",
) {
    /** NetEase song id parsed from the "song_<id>" suffix, or null for folders/unknown. */
    val songId: Long?
        get() = docId.substringAfterLast("song_").toLongOrNull()
}

/**
 * Standalone NetEase downloader: login (QR / cookie), keyword search, multi-threaded CDN
 * download - all inside this app, independent from the Poweramp provider. A browse mode
 * additionally exposes the provider's document tree over SAF for provider-curated libraries.
 */
class DownloaderActivity : Activity() {

    private enum class Mode { SEARCH, BROWSE }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences("downloader", MODE_PRIVATE) }
    private val api: NeteaseApi by lazy { NeteaseApi(this) }

    private lateinit var accountView: TextView
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var pickButton: Button
    private lateinit var downloadAllButton: Button
    private lateinit var loginButton: Button
    private lateinit var modeButton: Button
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var listView: ListView

    private var mode = Mode.SEARCH
    private var treeUri: Uri? = null
    private val navStack = ArrayDeque<Pair<String, String>>()
    private var browseRows: List<DocRow> = emptyList()
    private var searchResults: List<NeteaseApi.SongResult> = emptyList()

    /** Keys of rows picked for batch download (browse docIds / "s<songId>" for search hits). */
    private val selectedKeys = LinkedHashSet<String>()

    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloader)
        accountView = findViewById(R.id.account_view)
        pathView = findViewById(R.id.path_view)
        statusView = findViewById(R.id.status_view)
        pickButton = findViewById(R.id.btn_pick)
        downloadAllButton = findViewById(R.id.btn_download_all)
        loginButton = findViewById(R.id.btn_login)
        modeButton = findViewById(R.id.btn_mode)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        listView = findViewById(R.id.doc_list)

        // targetSdk 35 is edge-to-edge: pad content below the system status bar.
        val root = findViewById<View>(R.id.downloader_root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        updateThreadsLabel()
        findViewById<Button>(R.id.btn_threads).setOnClickListener { showThreadDialog() }

        loginButton.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        modeButton.setOnClickListener { setMode(if (mode == Mode.SEARCH) Mode.BROWSE else Mode.SEARCH) }
        findViewById<Button>(R.id.btn_search).setOnClickListener { runSearch() }
        searchInput.setOnEditorActionListener { _, _, _ -> runSearch(); true }
        pickButton.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ),
                REQUEST_PICK_TREE,
            )
        }
        downloadAllButton.setOnClickListener { downloadAllVisible() }
        listView.setOnItemClickListener { _, _, position, _ -> onRowTapped(position) }

        setMode(Mode.SEARCH)
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
    }

    private fun updateThreadsLabel() {
        findViewById<Button>(R.id.btn_threads).text = getString(R.string.threads_button, threadsPref())
    }

    private fun threadsPref(): Int = prefs.getInt(KEY_THREADS, DEFAULT_THREADS)

    private fun showThreadDialog() {
        val options = intArrayOf(2, 4, 8, 16, 24, 32)
        val current = threadsPref()
        val checked = options.indexOf(current).coerceAtLeast(0)
        val labels = options.map { "$it" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.threads_dialog_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putInt(KEY_THREADS, options[which]).apply()
                updateThreadsLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshAccount() {
        accountView.text = getString(R.string.account_anonymous)
        Thread {
            val account = try {
                api.verifyAccount()
            } catch (t: Throwable) {
                null
            }
            mainHandler.post {
                accountView.text = when {
                    account != null -> getString(R.string.account_ok, account.nickname, account.vipLabel)
                    api.musicU != null -> getString(R.string.account_expired)
                    else -> getString(R.string.account_anonymous)
                }
            }
        }.start()
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        if (mode == Mode.BROWSE) {
            searchBar.visibility = View.GONE
            modeButton.text = getString(R.string.btn_mode_browse)
            val saved = prefs.getString(KEY_TREE, null)
            if (saved != null && navStack.isEmpty()) {
                treeUri = Uri.parse(saved)
                navStack.add(DocumentsContract.getTreeDocumentId(treeUri) to "Netease Poweramp")
            }
            if (treeUri == null) showPickState() else refreshBrowse()
        } else {
            searchBar.visibility = View.VISIBLE
            modeButton.text = getString(R.string.btn_mode_search)
            statusView.text = getString(R.string.target_dir)
            pathView.text = getString(R.string.app_name)
            pickButton.visibility = View.GONE
            renderList()
        }
    }

    // ------------------------------------------------------------------ search mode

    private fun runSearch(): Boolean {
        val keyword = searchInput.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) return true
        statusView.text = getString(R.string.search_running)
        Thread {
            val results = try {
                api.search(keyword, 30)
            } catch (t: Throwable) {
                Log.w(TAG, "search failed", t)
                mainHandler.post { statusView.text = getString(R.string.status_failed, t.message ?: "") }
                null
            }
            if (results != null) {
                searchResults = results
                selectedKeys.clear()
                mainHandler.post {
                    statusView.text = getString(R.string.search_done, results.size)
                    renderList()
                }
            }
        }.start()
        return true
    }

    private fun downloadSearchOne(song: NeteaseApi.SongResult) {
        val resolved = api.resolveSongUrl(song.songId)
            ?: throw IllegalStateException("无法解析播放地址（VIP 歌曲请先登录）")
        val docRow = DocRow(
            docId = "song_${song.songId}",
            name = song.title,
            isDir = false,
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            album = song.album,
            coverUrl = song.coverUrl,
        )
        val cdn = downloadFromCdn(docRow, resolved.url, null)
        tagAndPublish(docRow, cdn)
    }

    // ------------------------------------------------------------------ browse mode (SAF)

    override fun onBackPressed() {
        if (mode == Mode.BROWSE && navStack.size > 1) {
            navStack.removeLast()
            refreshBrowse()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.w(TAG, "persistable grant missing", e)
            }
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            treeUri = uri
            navStack.clear()
            navStack.add(DocumentsContract.getTreeDocumentId(uri) to "Netease Poweramp")
            refreshBrowse()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LEGACY_STORAGE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            downloadAllVisible()
        }
    }

    private fun showPickState() {
        pickButton.visibility = View.VISIBLE
        downloadAllButton.visibility = View.GONE
        pathView.text = getString(R.string.app_name)
        statusView.text = getString(R.string.pick_hint)
        browseRows = emptyList()
        renderList()
    }

    private fun refreshBrowse() {
        val tree = treeUri ?: return
        pickButton.visibility = View.GONE
        val current = navStack.last()
        pathView.text = navStack.joinToString(" / ") { it.second }
        statusView.text = getString(R.string.target_dir)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, current.first)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.MediaStore.Audio.AudioColumns.DURATION,
                android.provider.MediaStore.MediaColumns.TITLE,
                android.provider.MediaStore.Audio.AudioColumns.ARTIST,
            )
            val out = ArrayList<DocRow>()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val isDir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                    val name = c.getString(2) ?: docId
                    val duration = if (c.isNull(3)) 0L else c.getLong(3)
                    val title = c.getString(4).orEmpty()
                    val artist = c.getString(5).orEmpty()
                    out.add(DocRow(docId, name, isDir, title, artist, duration))
                }
            }
            browseRows = out.sortedWith(compareBy({ !it.isDir }, { it.name }))
        } catch (e: Exception) {
            Log.w(TAG, "query children failed", e)
            browseRows = emptyList()
            statusView.text = "读取失败: ${e.message}"
        }
        renderList()
        if (browseRows.isEmpty()) statusView.text = getString(R.string.status_empty)
    }

    // ------------------------------------------------------------------ list

    private data class DisplayRow(val key: String, val title: String, val subtitle: String, val isSong: Boolean)

    private fun currentDisplay(): List<DisplayRow> = when (mode) {
        Mode.BROWSE -> browseRows.map { row ->
            if (row.isDir) {
                DisplayRow(row.docId, "📁 ${row.name}", "文件夹", false)
            } else {
                DisplayRow(row.docId, row.title.ifEmpty { row.name }, row.subtitle(), true)
            }
        }
        Mode.SEARCH -> searchResults.map { s ->
            DisplayRow(
                "s${s.songId}",
                s.title,
                listOf(s.artist, s.album, formatDuration(s.durationMs))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
                true,
            )
        }
    }

    private fun renderList() {
        val display = currentDisplay()
        downloadAllButton.visibility = if (display.any { it.isSong }) View.VISIBLE else View.GONE
        updateDownloadAllLabel()
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = display.size
            override fun getItem(position: Int): Any = display[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_doc, parent, false)
                val row = display[position]
                view.findViewById<TextView>(R.id.item_title).text = row.title
                view.findViewById<TextView>(R.id.item_subtitle).text = row.subtitle
                val check = view.findViewById<android.widget.CheckBox>(R.id.item_check)
                check.visibility = if (row.isSong) View.VISIBLE else View.GONE
                // Reset the listener before state to avoid recycled-view feedback loops.
                check.setOnCheckedChangeListener(null)
                check.isChecked = selectedKeys.contains(row.key)
                check.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedKeys.add(row.key) else selectedKeys.remove(row.key)
                    updateDownloadAllLabel()
                }
                return view
            }
        }
    }

    private fun updateDownloadAllLabel() {
        downloadAllButton.text = if (selectedKeys.isNotEmpty()) {
            getString(R.string.download_selected, selectedKeys.size)
        } else if (mode == Mode.SEARCH) {
            getString(R.string.download_all_results)
        } else {
            getString(R.string.download_all)
        }
    }

    private fun toggleSelection(key: String) {
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        renderList()
    }

    private fun DocRow.subtitle(): String = buildString {
        if (artist.isNotEmpty()) append(artist)
        if (album.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(album)
        }
        if (durationMs > 0) {
            if (isNotEmpty()) append(" · ")
            append(formatDuration(durationMs))
        }
    }.ifEmpty { name }

    private fun formatDuration(ms: Long): String =
        "${ms / 60000}:${"%02d".format((ms / 1000) % 60)}"

    private fun onRowTapped(position: Int) {
        if (downloading) return
        val display = currentDisplay()
        val row = display.getOrNull(position) ?: return
        if (mode == Mode.BROWSE && !row.isSong) {
            val docRow = browseRows.getOrNull(position)
            if (docRow != null && docRow.isDir) {
                navStack.addLast(docRow.docId to docRow.name)
                selectedKeys.clear()
                refreshBrowse()
            }
            return
        }
        // With a selection active, tapping a song toggles it (bulk-pick flow).
        if (selectedKeys.isNotEmpty()) {
            toggleSelection(row.key)
            return
        }
        when (mode) {
            Mode.BROWSE -> browseRows.getOrNull(position)?.let { enqueue(listOf(it)) }
            Mode.SEARCH -> searchResults.getOrNull(position)?.let { enqueueSearch(listOf(it)) }
        }
    }

    private fun downloadAllVisible() {
        if (downloading) return
        if (selectedKeys.isNotEmpty()) {
            val keys = selectedKeys.toSet()
            when (mode) {
                Mode.BROWSE -> {
                    if (Build.VERSION.SDK_INT < 29 && !hasLegacyStorage()) {
                        requestPermissions(arrayOf(LEGACY_WRITE), REQUEST_LEGACY_STORAGE)
                        return
                    }
                    enqueue(browseRows.filter { keys.contains(it.docId) })
                }
                Mode.SEARCH -> enqueueSearch(searchResults.filter { keys.contains("s${it.songId}") })
            }
            return
        }
        when (mode) {
            Mode.BROWSE -> {
                val songs = browseRows.filter { !it.isDir }
                if (songs.isEmpty()) return
                if (Build.VERSION.SDK_INT < 29 && !hasLegacyStorage()) {
                    requestPermissions(arrayOf(LEGACY_WRITE), REQUEST_LEGACY_STORAGE)
                    return
                }
                enqueue(songs)
            }
            Mode.SEARCH -> enqueueSearch(searchResults)
        }
    }

    // ------------------------------------------------------------------ download queue

    private fun enqueue(songs: List<DocRow>) {
        if (downloading || songs.isEmpty()) return
        downloading = true
        downloadAllButton.isEnabled = false
        var ok = 0
        var fail = 0
        Thread {
            for (song in songs) {
                try {
                    val written = downloadOne(song)
                    ok++
                    mainHandler.post { statusView.text = getString(R.string.status_saved, describe(song, written)) }
                } catch (t: Throwable) {
                    fail++
                    Log.w(TAG, "download failed ${song.name}", t)
                    mainHandler.post { statusView.text = getString(R.string.status_failed, t.message ?: "") }
                }
            }
            finishQueue(ok, fail)
        }.start()
    }

    private fun enqueueSearch(songs: List<NeteaseApi.SongResult>) {
        if (downloading || songs.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyStorage()) {
            requestPermissions(arrayOf(LEGACY_WRITE), REQUEST_LEGACY_STORAGE)
            return
        }
        downloading = true
        downloadAllButton.isEnabled = false
        var ok = 0
        var fail = 0
        Thread {
            for (song in songs) {
                try {
                    downloadSearchOne(song)
                    ok++
                    mainHandler.post { statusView.text = getString(R.string.status_saved, song.title) }
                } catch (t: Throwable) {
                    fail++
                    Log.w(TAG, "search download failed ${song.title}", t)
                    mainHandler.post { statusView.text = getString(R.string.status_failed, t.message ?: "") }
                }
            }
            finishQueue(ok, fail)
        }.start()
    }

    private fun finishQueue(ok: Int, fail: Int) {
        mainHandler.post {
            downloading = false
            downloadAllButton.isEnabled = true
            Toast.makeText(this, getString(R.string.download_summary, ok, fail), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ providers of bytes

    /** Browse mode: resolve the CDN url via the provider (Poweramp's own playback call). */
    private fun downloadOne(song: DocRow): Long {
        val tree = treeUri ?: throw IllegalStateException("no tree selected")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, song.docId)
        val bundle = contentResolver.call(tree, METHOD_GET_URL, docUri.toString(), null)
        val url = bundle?.getString(COLUMN_URL)?.takeIf { it.startsWith("http") }
        if (url != null) {
            try {
                val cdn = downloadFromCdn(song, url, bundle.getString(COLUMN_HEADERS))
                tagAndPublish(song, cdn)
                return cdn.size
            } catch (t: Throwable) {
                Log.w(TAG, "direct download failed for ${song.name}", t)
            }
        }
        // Proxy-fd fallback (one Range request per 64 KB - slow, but always works).
        val ext = sniffViaProxy(docUri)
        val cache = newCacheFile(ext)
        try {
            openStream(docUri).use { input ->
                FileOutputStream(cache).use { out -> copyStream(input, out, -1) }
            }
            tagAndPublish(song, CdnResult(cache, ext, cache.length(), ""))
            return cache.length()
        } finally {
            cache.delete()
        }
    }

    private data class CdnResult(val cache: File, val ext: String, val size: Long, val filename: String)

    /**
     * Embeds title/artist/album/lyrics/cover into the downloaded file, then publishes it into
     * Music/NeteaseDownload. Best-effort: a failed tag never fails the download.
     */
    private fun tagAndPublish(song: DocRow, cdn: CdnResult) {
        mainHandler.post { statusView.text = getString(R.string.status_tagging) }
        try {
            val songId = song.songId
            if (songId != null) {
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(cdn.cache)
                val tag = audioFile.tagOrCreateDefault
                tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, song.title.ifEmpty { song.name })
                tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, song.artist.ifEmpty { "Unknown Artist" })
                if (song.album.isNotEmpty()) {
                    tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, song.album)
                }
                try {
                    api.songLyric(songId)?.let { tag.setField(org.jaudiotagger.tag.FieldKey.LYRICS, it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "lyrics tag failed", t)
                }
                try {
                    val coverUrl = song.coverUrl.ifEmpty { api.coverUrlFor(songId) }
                    val bytes = coverUrl?.let { fetchBytes(it) }
                    if (bytes != null && bytes.size > 1024) {
                        val art = org.jaudiotagger.tag.images.AndroidArtwork()
                        art.binaryData = bytes
                        art.mimeType =
                            if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'N'.code.toByte() && bytes[2] == 'G'.code.toByte()) {
                                "image/png"
                            } else {
                                "image/jpeg"
                            }
                        art.pictureType = org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID
                        try {
                            tag.deleteArtworkField()
                        } catch (_: Exception) {
                        }
                        tag.setField(art)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "cover tag failed", t)
                }
                audioFile.commit()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tagging failed", t)
        }
        cdn.cache.inputStream().use { input ->
            if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStoreDirect(cdn.filename, song, cdn.ext, input, cdn.size)
            } else {
                saveToPublicDirDirect(cdn.filename, input)
            }
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        } catch (t: Throwable) {
            Log.w(TAG, "fetch bytes failed $url", t)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun newCacheFile(ext: String): File = File(cacheDir, "ndl_${System.currentTimeMillis()}$ext")

    /**
     * Single- or multi-connection download of the raw bytes into a cache file. NetEase's CDN
     * caps per-connection bandwidth, so files above [MIN_SEG_FILE] are fetched with parallel
     * Range requests (IDM/aria2 style). Tagging + publishing happen in [tagAndPublish].
     */
    private fun downloadFromCdn(song: DocRow, url: String, headers: String?): CdnResult {
        val probe = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        val ext: String
        val size: Long
        try {
            probe.connectTimeout = 10_000
            probe.readTimeout = 30_000
            probe.instanceFollowRedirects = true
            applyHeaders(probe, headers)
            probe.connect()
            val code = probe.responseCode
            if (code < 200 || code >= 300) throw java.io.IOException("直链 HTTP $code")
            size = probe.contentLengthLong
            val head = ByteArray(4)
            probe.inputStream.use { input ->
                var total = 0
                while (total < head.size) {
                    val read = input.read(head, total, head.size - total)
                    if (read < 0) break
                    total += read
                }
            }
            ext = when {
                probe.contentType?.contains("flac", ignoreCase = true) == true -> ".flac"
                flacMagic(head) -> ".flac"
                else -> ".mp3"
            }
        } finally {
            probe.disconnect()
        }

        val filename = sanitize("${song.artist.ifEmpty { "Unknown" }} - ${song.title.ifEmpty { song.name }}") + ext
        // The extension matters: jaudiotagger picks the tag reader by file extension.
        val cache = newCacheFile(ext)
        try {
            if (size in 1 until MIN_SEG_FILE) {
                singleInto(cache, url, headers)
            } else if (!segmentInto(cache, url, headers, size)) {
                singleInto(cache, url, headers)
            }
            return CdnResult(cache, ext, if (size > 0) size else cache.length(), filename)
        } catch (t: Throwable) {
            cache.delete()
            throw t
        }
    }

    /** Multi-connection Range download into a preallocated cache file. */
    private fun segmentInto(cache: File, url: String, headers: String?, size: Long): Boolean {
        val segments = (size / SEGMENT_TARGET).toInt().coerceIn(2, threadsPref())
        try {
            RandomAccessFile(cache, "rw").use { it.setLength(size) }
            val done = java.util.concurrent.atomic.AtomicLong()
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val segSize = size / segments
            val threads = (0 until segments).map { index ->
                val start = index * segSize
                val end = if (index == segments - 1) size - 1 else (index + 1) * segSize - 1
                Thread {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 30_000
                        applyHeaders(conn, headers)
                        conn.setRequestProperty("Range", "bytes=$start-$end")
                        conn.connect()
                        if (conn.responseCode != 206) {
                            throw java.io.IOException("segment HTTP ${conn.responseCode}")
                        }
                        conn.inputStream.use { input ->
                            RandomAccessFile(cache, "rw").use { raf ->
                                raf.seek(start)
                                val buffer = ByteArray(COPY_BUFFER)
                                var pos = start
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    raf.write(buffer, 0, read)
                                    pos += read
                                    done.addAndGet(read.toLong())
                                }
                                if (pos != end + 1) throw java.io.IOException("segment short $pos != ${end + 1}")
                            }
                        }
                    } catch (t: Throwable) {
                        failed.set(true)
                        Log.w(TAG, "segment $index failed", t)
                    }
                }.apply { isDaemon = true }
            }
            var ticking = true
            val ticker = object : Runnable {
                override fun run() {
                    if (!ticking) return
                    statusView.text = getString(
                        R.string.status_downloading,
                        "${mb(done.get())}/${mb(size)} · ${segments}线程",
                    )
                    mainHandler.postDelayed(this, 400)
                }
            }
            mainHandler.post(ticker)
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            ticking = false
            mainHandler.removeCallbacks(ticker)
            if (failed.get() || done.get() != size) {
                Log.w(TAG, "segmented download incomplete done=$done size=$size")
                return false
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "segmented download error", t)
            return false
        }
    }

    /** Small files / segment-failure fallback: one plain GET into the cache file. */
    private fun singleInto(cache: File, url: String, headers: String?): Long {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            applyHeaders(conn, headers)
            conn.connect()
            val code = conn.responseCode
            if (code < 200 || code >= 300) throw java.io.IOException("直链 HTTP $code")
            conn.inputStream.use { input ->
                RandomAccessFile(cache, "rw").use { raf ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        total += read
                    }
                    return total
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun applyHeaders(conn: java.net.HttpURLConnection, headers: String?) {
        headers?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.split("\r\n").forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    conn.setRequestProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                }
            }
        }
    }

    private fun flacMagic(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()

    // ------------------------------------------------------------------ sinks

    private fun mediaValues(filename: String, ext: String, song: DocRow) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, if (ext == ".flac") "audio/flac" else "audio/mpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeteaseDownload")
        put(MediaStore.Audio.Media.TITLE, song.title.ifEmpty { song.name.removeSuffix(".mp3") })
        put(MediaStore.Audio.Media.ARTIST, song.artist.ifEmpty { "Unknown Artist" })
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun saveViaMediaStore(docUri: Uri, filename: String, song: DocRow, ext: String): Long {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, mediaValues(filename, ext, song))
            ?: throw IllegalStateException("MediaStore insert failed")
        val written = contentResolver.openOutputStream(target)!!.use { out -> copyWithProgress(docUri, out) }
        contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return written
    }

    private fun saveViaMediaStoreDirect(
        filename: String,
        song: DocRow,
        ext: String,
        input: java.io.InputStream,
        contentLength: Long,
    ): Long {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, mediaValues(filename, ext, song))
            ?: throw IllegalStateException("MediaStore insert failed")
        val written = contentResolver.openOutputStream(target)!!.use { out -> copyStream(input, out, contentLength) }
        contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return written
    }

    private fun saveToPublicDir(docUri: Uri, filename: String): Long {
        val target = publicFile(filename)
        val written = FileOutputStream(target).use { out -> copyWithProgress(docUri, out) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
    }

    private fun saveToPublicDirDirect(filename: String, input: java.io.InputStream): Long {
        val target = publicFile(filename)
        val written = FileOutputStream(target).use { out -> copyStream(input, out, -1) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
    }

    private fun publicFile(filename: String): File {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NeteaseDownload")
        dir.mkdirs()
        return File(dir, filename)
    }

    private fun copyWithProgress(docUri: Uri, out: java.io.OutputStream): Long {
        openStream(docUri).use { input -> return copyStream(input, out, -1) }
    }

    private fun copyStream(input: java.io.InputStream, out: java.io.OutputStream, contentLength: Long): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
            if (total - lastReport > PROGRESS_STEP) {
                lastReport = total
                val sofar = total
                val totalLabel = if (contentLength > 0) "/${mb(contentLength)}" else ""
                mainHandler.post { statusView.text = getString(R.string.status_downloading, "${mb(sofar)}$totalLabel") }
            }
        }
        return total
    }

    private fun openStream(docUri: Uri) =
        contentResolver.openInputStream(docUri) ?: throw IllegalStateException("无法打开歌曲流")

    private fun sniffViaProxy(docUri: Uri): String {
        openStream(docUri).use { input ->
            val head = ByteArray(4)
            var total = 0
            while (total < head.size) {
                val read = input.read(head, total, head.size - total)
                if (read < 0) break
                total += read
            }
            return if (flacMagic(head)) ".flac" else ".mp3"
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hasLegacyStorage(): Boolean =
        checkSelfPermission(LEGACY_WRITE) == PackageManager.PERMISSION_GRANTED

    private fun describe(song: DocRow, bytes: Long): String =
        "${song.title.ifEmpty { song.name }} (${mb(bytes)})"

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().ifEmpty { "song" }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    private companion object {
        const val TAG = "NeteaseDownloader"
        const val KEY_TREE = "tree_uri"
        const val REQUEST_PICK_TREE = 51
        const val REQUEST_LEGACY_STORAGE = 52
        const val COPY_BUFFER = 64 * 1024
        const val PROGRESS_STEP = 512L * 1024

        /** Provider call() method that resolves the CDN url at download time (Poweramp's own). */
        const val METHOD_GET_URL = "com.maxmpz.audioplayer:get_url"
        const val COLUMN_URL = "url"
        const val COLUMN_HEADERS = "headers"

        /** Segmented download tuning: NetEase CDN caps per-connection bandwidth. */
        const val SEGMENT_TARGET = 4L * 1024 * 1024
        const val DEFAULT_THREADS = 16
        const val MIN_SEG_FILE = 8L * 1024 * 1024
        const val KEY_THREADS = "download_threads"

        /** Only needed below API 29; MediaStore handles storage on 29+. */
        const val LEGACY_WRITE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
