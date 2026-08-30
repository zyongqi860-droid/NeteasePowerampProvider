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
        val ext = sniffExtension(docUri)
        val filename = sanitize("${song.artist.ifEmpty { "Unknown" }} - ${song.title.ifEmpty { song.name }}") + ext
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(docUri, filename, song, ext)
        } else {
            saveToPublicDir(docUri, filename)
        }
    }

    /** The proxy fd re-opens cleanly, so sniffing reads the stream head twice. */
    private fun sniffExtension(docUri: Uri): String {
        openStream(docUri).use { input ->
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

    private fun saveToPublicDir(docUri: Uri, filename: String): Long {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NeteaseDownload")
        dir.mkdirs()
        val target = File(dir, filename)
        val written = FileOutputStream(target).use { out -> copyWithProgress(docUri, out) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
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

        /** Only needed below API 29; MediaStore handles storage on 29+. */
        const val LEGACY_WRITE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
