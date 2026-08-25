package com.yczz.neteasepoweramp.provider

import android.content.Context
import android.util.Log
import com.yczz.neteasepoweramp.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Extracts res/raw/test_audio.mp3 into filesDir once and hands out a stable local file.
 *
 * Poweramp is given a seekable file descriptor pointing to a real file. An asset fd points into
 * the APK itself and cannot be played reliably (Poweramp can't detect EOF properly for many
 * formats) - the official ExampleProvider does the same asset-to-files copy (USE_MP3_COPY).
 */
class TestAudioManager(private val appContext: Context) {

    private val lock = Any()

    /** Target location of the extracted test audio. THREADING: immutable after construction. */
    val testAudioFile: File = File(appContext.filesDir, FILE_NAME)

    /**
     * Returns the extracted test audio file, copying it from raw resources on first use.
     * Repeated calls do not re-copy as long as the file exists and is non-empty.
     * THREADING: synchronized; may be called from any DocumentsProvider worker thread.
     */
    @Throws(FileNotFoundException::class)
    fun ensureTestAudioFile(): File {
        synchronized(lock) {
            if (!testAudioFile.isFile || testAudioFile.length() <= 0L) {
                try {
                    appContext.resources.openRawResource(R.raw.test_audio).use { input ->
                        testAudioFile.parentFile?.mkdirs()
                        testAudioFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.i(ProviderConstants.TAG, "test audio extracted size=${testAudioFile.length()}")
                } catch (e: IOException) {
                    Log.e(ProviderConstants.TAG, "test audio extraction failed", e)
                    throw FileNotFoundException("cannot provide test audio: ${e.message}")
                }
            }
            return testAudioFile
        }
    }

    /** @return real file size in bytes, or [TestTrack.FALLBACK_SIZE] if the file was never extracted. */
    fun fileSizeOrFallback(): Long {
        val length = if (testAudioFile.isFile) testAudioFile.length() else 0L
        return if (length > 0L) length else TestTrack.FALLBACK_SIZE
    }

    private companion object {
        const val FILE_NAME = "test_audio.mp3"
    }
}
