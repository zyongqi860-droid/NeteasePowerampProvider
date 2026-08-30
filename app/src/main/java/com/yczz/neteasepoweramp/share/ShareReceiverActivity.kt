package com.yczz.neteasepoweramp.share

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.provider.ProviderConstants
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Share target for songs handed over by Poweramp (provider content URIs, granted via
 * FLAG_GRANT_READ_URI_PERMISSION). Downloads each streamed song into the public
 * Music/NeteasePoweramp folder so it survives outside the player: MediaStore on API 29+,
 * plain public-dir file + media scan below that. File format is sniffed from the stream
 * head (fLaC -> .flac, ID3 -> .mp3); the proxy fd supports re-opening, so sniff and copy
 * simply read the stream twice.
 */
class ShareReceiverActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var doneButton: Button

    private var queue: List<Uri> = emptyList()
    private var failures = 0
    private var finished = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)
        titleView = findViewById(R.id.share_title)
        subtitleView = findViewById(R.id.share_subtitle)
        progress = findViewById(R.id.share_progress)
        statusView = findViewById(R.id.share_status)
        doneButton = findViewById(R.id.btn_share_done)
        doneButton.setOnClickListener { finish() }

        queue = extractStreams(intent)
        if (queue.isEmpty()) {
            titleView.text = getString(R.string.share_nothing)
            statusView.text = getString(R.string.share_nothing_hint)
            doneButton.isEnabled = true
            return
        }
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyStorage()) {
            requestPermissions(arrayOf(LEGACY_WRITE), STORAGE_PERMISSION_REQUEST)
            return
        }
        startQueue()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQueue()
            } else {
                titleView.text = getString(R.string.share_permission_denied)
                statusView.text = getString(R.string.share_nothing_hint)
                doneButton.isEnabled = true
            }
        }
    }

    private fun extractStreams(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            return intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
        }
        @Suppress("DEPRECATION")
        return listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    // ------------------------------------------------------------------ pipeline

    private fun startQueue() = downloadNext()

    private fun downloadNext() {
        val uri = queue.firstOrNull()
        if (uri == null) {
            onAllDone()
            return
        }
        queue = queue.drop(1)
        val songId = ProviderConstants.documentIdFromUri(uri)?.let { ProviderConstants.parseSongId(it) }
        val song = songId?.let { id -> AppDatabase.get(this).songDao().getSong(id) }
        val displayTitle = song?.title?.ifEmpty { "song_$songId" } ?: getString(R.string.share_unknown_song)
        val displayArtist = song?.artist.orEmpty()
        mainHandler.post {
            titleView.text = displayTitle
            subtitleView.text = if (displayArtist.isEmpty()) {
                getString(R.string.share_target_dir)
            } else {
                "$displayArtist · ${getString(R.string.share_target_dir)}"
            }
            progress.isIndeterminate = true
            statusView.text = getString(R.string.share_status_starting)
        }
        Thread {
            downloadOne(uri, displayTitle, displayArtist)
            mainHandler.post { downloadNext() }
        }.start()
    }

    private fun downloadOne(uri: Uri, title: String, artist: String) {
        try {
            val ext = sniffExtension(uri)
            val filename = sanitize("$artist - $title") + ext
            val bytes = if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStore(uri, filename, title, artist)
            } else {
                saveToPublicDir(uri, filename)
            }
            finished++
            Log.i(ProviderConstants.TAG, "share download saved $filename bytes=$bytes")
            mainHandler.post {
                statusView.text = getString(R.string.share_status_saved, finished, failures)
            }
        } catch (t: Throwable) {
            failures++
            Log.w(ProviderConstants.TAG, "share download failed", t)
            mainHandler.post {
                statusView.text = getString(R.string.share_status_failed, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** MediaStore (API 29+): lands in Music/NeteasePoweramp, visible to every music app. */
    private fun saveViaMediaStore(uri: Uri, filename: String, title: String, artist: String): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (filename.endsWith(".flac")) "audio/flac" else "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeteasePoweramp")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist.ifEmpty { "Unknown Artist" })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val written = contentResolver.openOutputStream(target)!!.use { out ->
            copyWithProgress(uri, out)
        }
        contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return written
    }

    /** Public Music dir for Android 9 and below, then a media scan so it shows up everywhere. */
    private fun saveToPublicDir(uri: Uri, filename: String): Long {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NeteasePoweramp")
        dir.mkdirs()
        val target = File(dir, filename)
        val written = FileOutputStream(target).use { out ->
            copyWithProgress(uri, out)
        }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return written
    }

    private fun copyWithProgress(uri: Uri, out: OutputStream): Long {
        contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            var lastReport = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (total - lastReport > PROGRESS_STEP_BYTES) {
                    lastReport = total
                    val sofar = total
                    mainHandler.post { statusView.text = getString(R.string.share_status_downloading, mb(sofar)) }
                }
            }
            return total
        }
    }

    private fun sniffExtension(uri: Uri): String {
        contentResolver.openInputStream(uri)!!.use { input: InputStream ->
            val head = ByteArray(4)
            var total = 0
            while (total < head.size) {
                val read = input.read(head, total, head.size - total)
                if (read < 0) break
                total += read
            }
            return extensionFor(head, total)
        }
    }

    private fun onAllDone() {
        progress.isIndeterminate = false
        progress.progress = progress.max
        doneButton.isEnabled = true
        if (finished + failures > 0) {
            Toast.makeText(this, getString(R.string.share_summary, finished, failures), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hasLegacyStorage(): Boolean =
        checkSelfPermission(LEGACY_WRITE) == PackageManager.PERMISSION_GRANTED

    private fun fill(input: InputStream, dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val read = input.read(dst, total, dst.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun extensionFor(head: ByteArray, len: Int): String {
        if (len >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) {
            return ".flac"
        }
        return ".mp3"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().ifEmpty { "song" }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 512L * 1024
        const val STORAGE_PERMISSION_REQUEST = 41

        /** Only needed below API 29; MediaStore handles storage on 29+. */
        const val LEGACY_WRITE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
