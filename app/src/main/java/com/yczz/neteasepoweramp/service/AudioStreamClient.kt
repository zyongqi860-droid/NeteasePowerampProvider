package com.yczz.neteasepoweramp.service

import com.yczz.neteasepoweramp.playback.PlaybackException
import com.yczz.neteasepoweramp.playback.PlaybackSource
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource

/**
 * Opens ranged reads over a [PlaybackSource] URL. Kept out of the provider on purpose: the
 * provider only asks "give me [offset, offset+length) of this source" and never sees HTTP code.
 *
 * Each read issues one short-lived GET with a Range header - the simplest correct model for a
 * media player that seeks randomly, pauses and re-opens tracks. Servers ignoring Range are
 * handled by discarding up to [offset] (slow but correct); 416 answers map to clean EOF.
 *
 * THREADING: stateless beyond the shared OkHttpClient; safe for concurrent use from the proxy
 * file descriptor callback threads.
 */
class AudioStreamClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()    /** Content length via HEAD, falling back to a 1-byte Range probe; -1 when undeterminable. */
    fun contentLength(source: PlaybackSource): Long {
        headLength(source)?.let { return it }
        return probeLengthViaRange(source)
    }

    /**
     * Reads up to [length] bytes of the source starting at [offset] into [dst].
     *
     * @return bytes copied; 0 means EOF at or before [offset].
     * @throws PlaybackException on network/HTTP failure (never raw OkHttp types).
     */
    fun readRange(source: PlaybackSource, offset: Long, length: Int, dst: ByteArray): Int {
        if (length <= 0) return 0
        val request = baseRequest(source)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_PARTIAL_CONTENT -> Unit // expected
                    response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                        Log.w(TAG, "stream read 416 at offset $offset (EOF)")
                        return 0 // seek past EOF = EOF
                    }
                    response.code == HTTP_OK -> Unit // server ignored Range; fall through and skip
                    else -> {
                        Log.w(TAG, "stream read HTTP ${response.code} at offset $offset")
                        throw PlaybackException("stream HTTP ${response.code} at offset $offset")
                    }
                }
                val body = response.body ?: throw PlaybackException("stream empty body at offset $offset")
                val src: BufferedSource = body.source()
                if (response.code == HTTP_OK) {
                    // No Range support: burn the first [offset] bytes, then read (bounded chunks,
                    // since okio's skip() both returns Unit and buffers the whole skip range).
                    var toSkip = offset
                    while (toSkip > 0) {
                        val chunk = minOf(toSkip, SKIP_CHUNK_BYTES.toLong()).toInt()
                        val read = src.read(SKIP_SINK, 0, chunk)
                        if (read == -1) return 0 // stream shorter than requested offset
                        toSkip -= read
                    }
                }
                var total = 0
                while (total < length) {
                    val read = src.read(dst, total, length - total)
                    if (read == -1) break
                    total += read
                }
                return total
            }
        } catch (e: PlaybackException) {
            throw e
        } catch (e: IOException) {
            throw PlaybackException("stream read failed at offset $offset: ${e.message}", e)
        }
    }

    private fun headLength(source: PlaybackSource): Long? = try {
        httpClient.newCall(baseRequest(source).head().build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "stream head HTTP ${response.code}")
                null
            } else {
                // Some servers answer HEAD with "Content-Length: 0" regardless of the real
                // resource size; treat non-positive lengths as unreliable and fall through to
                // the Range probe.
                val len = response.body?.contentLength() ?: -1L
                if (len > 0L) len else null
            }
        }
    } catch (e: IOException) {
        throw PlaybackException("stream probe failed: ${e.message}", e)
    }

    private fun probeLengthViaRange(source: PlaybackSource): Long = try {
        val request = baseRequest(source).header("Range", "bytes=0-0").build()
        httpClient.newCall(request).execute().use { response ->
            val range = response.header("Content-Range")
            // Content-Range: bytes 0-0/3721234
            val total = range?.substringAfter('/', "")?.toLongOrNull()
            Log.w(TAG, "stream range probe HTTP ${response.code} total=$total")
            total ?: -1L
        }
    } catch (e: IOException) {
        throw PlaybackException("stream range probe failed: ${e.message}", e)
    }

    private fun baseRequest(source: PlaybackSource): Request.Builder {
        val builder = Request.Builder().url(source.url)
        for ((name, value) in source.headers) {
            builder.header(name, value)
        }
        return builder
    }

    private companion object {
        const val TAG = "NeteasePowerampProvider"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val SKIP_CHUNK_BYTES = 64 * 1024

        /** Shared discard buffer for the no-Range fallback path (only touched on that path). */
        val SKIP_SINK = ByteArray(SKIP_CHUNK_BYTES)
    }
}
