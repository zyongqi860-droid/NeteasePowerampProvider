package com.yczz.neteasepoweramp.service

import android.content.Context
import android.util.Log
import com.yczz.neteasepoweramp.provider.ProviderConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cover art cache (task section 5): coverUrl -> file under cache/images/.
 *
 * The provider's openDocumentThumbnail serves ONLY from this cache after the first fetch, so
 * repeated Poweramp scans never hit the network. NetEase image CDN supports the "param=WxH"
 * suffix, used here to keep downloads small.
 *
 * THREADING: per-URL locks let different covers download concurrently while duplicate requests
 * for the same cover wait and then reuse the result.
 */
class AlbumArtService(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, IMAGE_CACHE_DIR)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val urlLocks = ConcurrentHashMap<String, Any>()

    /**
     * @return local cover file, or null when the URL is empty or the download fails
     * (callers treat null as "no artwork available").
     */
    fun getCachedOrFetch(coverUrl: String): File? {
        if (coverUrl.isEmpty()) return null
        val target = cacheFileFor(coverUrl)
        if (target.isFile && target.length() > 0L) return target

        // computeIfAbsent needs API 24; this is the API 21-compatible equivalent.
        var lock = urlLocks[coverUrl]
        if (lock == null) {
            val created = Any()
            lock = urlLocks.putIfAbsent(coverUrl, created) ?: created
        }
        synchronized(lock) {
            // Re-check after acquiring: another thread may have finished the download.
            if (target.isFile && target.length() > 0L) return target
            return try {
                download(coverUrl, target)
                target
            } catch (e: IOException) {
                Log.e(ProviderConstants.TAG, "cover download failed: ${e.message}")
                target.delete()
                null
            } finally {
                urlLocks.remove(coverUrl, lock)
            }
        }
    }

    private fun cacheFileFor(coverUrl: String): File {
        cacheDir.mkdirs()
        val name = coverUrl.hashCode().toUInt().toString(16) + IMAGE_SUFFIX
        return File(cacheDir, name)
    }

    private fun download(coverUrl: String, target: File) {
        val request = Request.Builder()
            .url(sizedUrl(coverUrl))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("cover HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("cover empty body")
            if (bytes.isEmpty()) throw IOException("cover zero bytes")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            Log.i(ProviderConstants.TAG, "cover cached size=${bytes.size}")
        }
    }

    /** Appends the NetEase CDN resize hint unless the URL already carries a query string. */
    private fun sizedUrl(coverUrl: String): String =
        if (coverUrl.contains('?')) coverUrl else "$coverUrl?param=$COVER_SIZE"

    private companion object {
        const val TAG = ProviderConstants.TAG
        const val IMAGE_CACHE_DIR = "images"
        const val IMAGE_SUFFIX = ".img"
        const val COVER_SIZE = "350y350"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
    }
}
