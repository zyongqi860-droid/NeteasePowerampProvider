package com.yczz.neteasepoweramp.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.util.Log
import androidx.annotation.RequiresApi
import com.yczz.neteasepoweramp.provider.ProviderConstants
import com.yczz.neteasepoweramp.service.AudioStreamClient
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns a [PlaybackSource] into a seekable read-only [ParcelFileDescriptor] for Poweramp.
 *
 * API 26+ (primary path): StorageManager.openProxyFileDescriptor + ProxyFileDescriptorCallback.
 * Every onRead maps to one Range GET via [AudioStreamClient], so seeking/pause/resume/re-open
 * never downloads the whole file (task section 6). Callbacks run on a dedicated HandlerThread -
 * the blocking network there is by design, never on a main thread.
 *
 * API < 26 fallback: openProxyFileDescriptor does not exist, so the source is downloaded once
 * into cacheDir and served as a plain file fd (seekable, correct, just not streaming).
 *
 * The callback HandlerThread is started lazily and lives for the whole process. It must never
 * be quit: HandlerThread.getLooper() returns null once the thread has died, so a quit thread
 * breaks every later proxy open with an NPE in Handler.<init> — i.e. the second played track
 * would fail. One idle blocked thread per provider process costs nothing.
 */
object AudioProxyFd {

    private const val TAG = ProviderConstants.TAG

    /** Number of currently open proxy fds, for logging only. */
    private val activeFdCount = AtomicInteger(0)

    private val callbackThread: HandlerThread by lazy {
        HandlerThread("npa-proxy-fd").apply { start() }
    }

    /** Open [source] as a seekable read-only fd. Throws FileNotFoundException on any failure. */
    @Throws(FileNotFoundException::class)
    fun open(appContext: Context, source: PlaybackSource, streamClient: AudioStreamClient): ParcelFileDescriptor {
        return if (android.os.Build.VERSION.SDK_INT >= 26) {
            openProxy(appContext, source, streamClient)
        } else {
            openDownloadedCopy(appContext, source, streamClient)
        }
    }

    // ------------------------------------------------------------------ API 26+ streaming

    @RequiresApi(26)
    private fun openProxy(
        appContext: Context,
        source: PlaybackSource,
        streamClient: AudioStreamClient,
    ): ParcelFileDescriptor {
        val storageManager = appContext.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("no StorageManager for proxy fd")
        var size = source.size

        activeFdCount.incrementAndGet()

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long {
                if (size == PlaybackSource.SIZE_UNKNOWN) {
                    size = streamClient.contentLength(source)
                    Log.i(TAG, "stream size=$size")
                }
                return size
            }

            // Framework contract: fill dst with up to dst.size bytes starting at offset,
            // return the number of bytes read (0 = EOF).
            override fun onRead(offset: Long, size: Int, dst: ByteArray): Int =
                streamClient.readRange(source, offset, size, dst)

            override fun onRelease() {
                val remaining = activeFdCount.decrementAndGet()
                Log.i(TAG, "stream closed, active fds=$remaining")
            }
        }
        return try {
            val pfd = storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                Handler(callbackThread.looper),
            )
            Log.i(TAG, "stream opened host=${Uri.parse(source.url).host}")
            pfd
        } catch (e: Exception) {
            activeFdCount.decrementAndGet()
            Log.e(TAG, "openProxyFileDescriptor failed", e)
            throw FileNotFoundException("cannot open remote stream: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ legacy (< API 26)

    private fun openDownloadedCopy(
        appContext: Context,
        source: PlaybackSource,
        streamClient: AudioStreamClient,
    ): ParcelFileDescriptor {
        val cache = File(appContext.cacheDir, "remote_${source.url.hashCode().toUInt()}.mp3")
        try {
            if (!cache.isFile || cache.length() <= 0L) {
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                cache.parentFile?.mkdirs()
                cache.outputStream().use { out ->
                    var offset = 0L
                    while (true) {
                        val read = streamClient.readRange(source, offset, DOWNLOAD_BUFFER_BYTES, buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        offset += read
                    }
                }
                Log.i(TAG, "legacy fallback downloaded size=${cache.length()}")
            }
            return ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: PlaybackException) {
            cache.delete()
            throw FileNotFoundException("cannot download remote audio: ${e.message}")
        } catch (e: java.io.IOException) {
            cache.delete()
            throw FileNotFoundException("cannot write remote audio cache: ${e.message}")
        }
    }

    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
}
