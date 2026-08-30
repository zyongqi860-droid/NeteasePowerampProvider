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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
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
)

/**
 * Standalone SAF client for the NeteasePoweramp provider - the same access model Poweramp
 * uses: pick the provider root once (persisted tree grant), then browse the document tree
 * and download songs into the public Music/NeteaseDownload folder. Downloads stream straight
 * from the provider (Range GETs on NetEase's CDN happen inside the provider process).
 */
class DownloaderActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences("downloader", MODE_PRIVATE) }

    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var pickButton: Button
    private lateinit var downloadAllButton: Button
    private lateinit var listView: ListView

    private var treeUri: Uri? = null
    private val navStack = ArrayDeque<Pair<String, String>>() // docId to display name
    private var rows: List<DocRow> = emptyList()

    private var downloading = false
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloader)
        pathView = findViewById(R.id.path_view)
        statusView = findViewById(R.id.status_view)
        pickButton = findViewById(R.id.btn_pick)
        downloadAllButton = findViewById(R.id.btn_download_all)
        listView = findViewById(R.id.doc_list)

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
        downloadAllButton.setOnClickListener { enqueueAll() }
        listView.setOnItemClickListener { _, _, position, _ -> onRowTapped(rows[position]) }

        restoreTree()
    }

    override fun onBackPressed() {
        if (navStack.size > 1) {
            navStack.removeLast()
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "persistable grant missing", e)
            }
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            treeUri = uri
            navStack.clear()
            navStack.add(DocumentsContract.getTreeDocumentId(uri) to "Netease Poweramp")
            refresh()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LEGACY_STORAGE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            enqueueAll()
        }
    }

    // ------------------------------------------------------------------ browse

    private fun restoreTree() {
        val saved = prefs.getString(KEY_TREE, null)
        if (saved == null) {
            showPickState()
            return
        }
        treeUri = Uri.parse(saved)
        navStack.clear()
        navStack.add(DocumentsContract.getTreeDocumentId(treeUri) to "Netease Poweramp")
        refresh()
    }

    private fun showPickState() {
        pickButton.visibility = View.VISIBLE
        downloadAllButton.visibility = View.GONE
        pathView.text = getString(R.string.app_name)
        statusView.text = getString(R.string.pick_hint)
        rows = emptyList()
        listView.adapter = simpleAdapter(emptyList())
    }

    private fun refresh() {
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
                DocumentsContract.Document.COLUMN_SIZE,
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
                    val duration = if (c.isNull(4)) 0L else c.getLong(4)
                    val title = c.getString(5).orEmpty()
                    val artist = c.getString(6).orEmpty()
                    out.add(DocRow(docId, name, isDir, title, artist, duration))
                }
            }
            rows = out.sortedWith(compareBy({ !it.isDir }, { it.name }))
        } catch (e: Exception) {
            Log.w(TAG, "query children failed", e)
            rows = emptyList()
            statusView.text = "读取失败: ${e.message}"
        }
        listView.adapter = simpleAdapter(rows)
        downloadAllButton.visibility = if (rows.any { !it.isDir }) View.VISIBLE else View.GONE
        if (rows.isEmpty()) statusView.text = getString(R.string.status_empty)
    }

    private fun simpleAdapter(list: List<DocRow>): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = list.size
        override fun getItem(position: Int): DocRow = list[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_doc, parent, false)
            val row = list[position]
            view.findViewById<TextView>(R.id.item_title).text =
                if (row.isDir) "📁 ${row.name}" else (row.title.ifEmpty { row.name })
            val subtitle = when {
                row.isDir -> "文件夹"
                else -> buildString {
                    if (row.artist.isNotEmpty()) append(row.artist)
                    if (row.durationMs > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${row.durationMs / 60000}:${"%02d".format((row.durationMs / 1000) % 60)}")
                    }
                }.ifEmpty { row.name }
            }
            view.findViewById<TextView>(R.id.item_subtitle).text = subtitle
            return view
        }
    }

    private fun onRowTapped(row: DocRow) {
        if (row.isDir) {
            navStack.addLast(row.docId to row.name)
            refresh()
            return
        }
        enqueue(listOf(row))
    }

    private fun enqueueAll() {
        if (downloading) return
        val songs = rows.filter { !it.isDir }
        if (songs.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyStorage()) {
            requestPermissions(arrayOf(LEGACY_WRITE), REQUEST_LEGACY_STORAGE)
            return
        }
        enqueue(songs)
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
                    lastError = t.message ?: t.javaClass.simpleName
                    Log.w(TAG, "download failed ${song.name}", t)
                    mainHandler.post { statusView.text = getString(R.string.status_failed, lastError) }
                }
            }
            mainHandler.post {
                downloading = false
                downloadAllButton.isEnabled = true
                Toast.makeText(this, getString(R.string.download_summary, ok, fail), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun downloadOne(song: DocRow): Long {
        val tree = treeUri ?: throw IllegalStateException("no tree selected")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, song.docId)
        // Fast path: resolve the CDN URL once via the provider (same call Poweramp uses for
        // playback) and stream the whole file with a single GET - full CDN speed. The proxy-fd
        // path is only a fallback: it issues one HTTP Range request per 64 KB read, which is
        // why downloads used to crawl.
        try {
            val bytes = downloadDirect(song, docUri)
            if (bytes != null) return bytes
            Log.w(TAG, "no direct url for ${song.name}, falling back to proxy fd")
        } catch (t: Throwable) {
            Log.w(TAG, "direct download failed for ${song.name}, falling back to proxy fd", t)
        }
        val ext = sniffViaProxy(docUri)
        val filename = sanitize("${song.artist.ifEmpty { "Unknown" }} - ${song.title.ifEmpty { song.name }}") + ext
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(docUri, filename, song, ext)
        } else {
            saveToPublicDir(docUri, filename)
        }
    }

    /** Fallback sniffing through the provider's proxy fd (one small Range read). */
    private fun sniffViaProxy(docUri: Uri): String {
        openStream(docUri).use { input -> return sniff(input) }
    }

    /**
     * Single-connection download straight from the CDN. Returns null when the provider has no
     * url for this song; throws on network/HTTP errors so the caller can fall back.
     *
     * NetEase's CDN caps per-connection bandwidth, so files above [MIN_SEG_FILE] are fetched
     * with [MAX_SEGMENTS] parallel Range requests into a preallocated cache file (IDM/aria2
     * style), then published into the media store with one fast local copy.
     */
    private fun downloadDirect(song: DocRow, docUri: Uri): Long? {
        val tree = treeUri ?: return null
        val bundle = contentResolver.call(tree, METHOD_GET_URL, docUri.toString(), null)
        val url = bundle?.getString(COLUMN_URL)?.takeIf { it.startsWith("http") } ?: return null
        val headers = bundle.getString(COLUMN_HEADERS)
        Log.i(TAG, "direct download via ${Uri.parse(url).host}")

        // Probe: response code, total size, content type + first bytes for the extension.
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
        if (size in 1 until MIN_SEG_FILE) {
            return downloadSingle(url, headers, filename, song, ext, size)
        }
        downloadSegmented(url, headers, size, filename, song, ext)?.let { return it }
        // Segments failed (server ignored Range etc.) - still better than the proxy fd.
        return downloadSingle(url, headers, filename, song, ext, -1)
    }

    /** Multi-connection Range download into a preallocated cache file, then publish. */
    private fun downloadSegmented(
        url: String,
        headers: String?,
        size: Long,
        filename: String,
        song: DocRow,
        ext: String,
    ): Long? {
        val segments = (size / SEGMENT_TARGET).toInt().coerceIn(2, MAX_SEGMENTS)
        val cache = File(cacheDir, "ndl_${System.currentTimeMillis()}")
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
                return null
            }
            cache.inputStream().use { input ->
                if (Build.VERSION.SDK_INT >= 29) {
                    saveViaMediaStoreDirect(filename, song, ext, input, size)
                } else {
                    saveToPublicDirDirect(filename, input)
                }
            }
            return size
        } finally {
            cache.delete()
        }
    }

    /** Small files / segment-failure fallback: one plain GET. */
    private fun downloadSingle(
        url: String,
        headers: String?,
        filename: String,
        song: DocRow,
        ext: String,
        contentLength: Long,
    ): Long {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            applyHeaders(conn, headers)
            conn.connect()
            val code = conn.responseCode
            if (code < 200 || code >= 300) throw java.io.IOException("直链 HTTP $code")
            val input = conn.inputStream
            return if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStoreDirect(filename, song, ext, input, contentLength)
            } else {
                saveToPublicDirDirect(filename, input)
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

    /** Reads the stream head to detect FLAC vs MP3. */
    private fun sniff(input: java.io.InputStream): String {
        val head = ByteArray(4)
        var total = 0
        while (total < head.size) {
            val read = input.read(head, total, head.size - total)
            if (read < 0) break
            total += read
        }
        if (total >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) {
            return ".flac"
        }
        return ".mp3"
    }

    private fun openStream(docUri: Uri) =
        contentResolver.openInputStream(docUri) ?: throw IllegalStateException("无法打开歌曲流")

    private fun copyWithProgress(docUri: Uri, out: java.io.OutputStream): Long {
        openStream(docUri).use { input ->
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
                    mainHandler.post { statusView.text = getString(R.string.status_downloading, mb(sofar)) }
                }
            }
            return total
        }
    }

    /** Proxy-fd path (fallback): MediaStore (API 29+), lands in Music/NeteaseDownload. */
    private fun saveViaMediaStore(docUri: Uri, filename: String, song: DocRow, ext: String): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (ext == ".flac") "audio/flac" else "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeteaseDownload")
            put(MediaStore.Audio.Media.TITLE, song.title.ifEmpty { song.name.removeSuffix(".mp3") })
            put(MediaStore.Audio.Media.ARTIST, song.artist.ifEmpty { "Unknown Artist" })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val written = contentResolver.openOutputStream(target)!!.use { out -> copyWithProgress(docUri, out) }
        contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return written
    }

    /** Direct-CDN path (fast): single GET streamed straight into MediaStore. */
    private fun saveViaMediaStoreDirect(
        filename: String,
        song: DocRow,
        ext: String,
        input: java.io.InputStream,
        contentLength: Long,
    ): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (ext == ".flac") "audio/flac" else "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeteaseDownload")
            put(MediaStore.Audio.Media.TITLE, song.title.ifEmpty { song.name.removeSuffix(".mp3") })
            put(MediaStore.Audio.Media.ARTIST, song.artist.ifEmpty { "Unknown Artist" })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val written = contentResolver.openOutputStream(target)!!.use { out -> copyStream(input, out, contentLength) }
        contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return written
    }

    /** Proxy-fd path (fallback): public Music dir for Android 9 and below + media scan. */
    private fun saveToPublicDir(docUri: Uri, filename: String): Long {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NeteaseDownload")
        dir.mkdirs()
        val target = File(dir, filename)
        val written = FileOutputStream(target).use { out -> copyWithProgress(docUri, out) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
    }

    /** Direct-CDN path (fast) for legacy devices. */
    private fun saveToPublicDirDirect(filename: String, input: java.io.InputStream): Long {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NeteaseDownload")
        dir.mkdirs()
        val target = File(dir, filename)
        val written = FileOutputStream(target).use { out -> copyStream(input, out, -1) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
    }

    /** Straight pipe for the direct-CDN path (contentLength from Content-Length when known). */
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
        const val MAX_SEGMENTS = 16
        const val MIN_SEG_FILE = 8L * 1024 * 1024

        /** Only needed below API 29; MediaStore handles storage on 29+. */
        const val LEGACY_WRITE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
