package com.yczz.neteasepoweramp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.yczz.neteasepoweramp.model.Song
import com.yczz.neteasepoweramp.provider.ProviderConstants
import com.yczz.neteasepoweramp.service.SearchService
import com.yczz.neteasepoweramp.settings.SettingsActivity

/**
 * Launcher UI. Phase 1: status, provider self-check, Poweramp shortcuts.
 * Phase 2 adds the keyword search box; tapping a result row saves it into the provider's
 * Room cache so Poweramp shows it under 网易云音乐 -> 搜索结果 after a refresh.
 * All network/database work happens inside [SearchService]; this activity only hops results
 * back to the main thread.
 */
class MainActivity : Activity() {

    private lateinit var searchService: SearchService
    private lateinit var resultAdapter: SongAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 35 enforces edge-to-edge; without inset handling the IME pans the window and
        // the search row can end up stuck off-screen (observed on a live Android 16 device).
        // Resizing for IME + padding for system bars keeps every control visible and tappable.
        val root = findViewById<View>(R.id.main_root)
        val contentPad = (RESERVED_PAD_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left + contentPad, bars.top + contentPad, bars.right + contentPad, bars.bottom + contentPad)
            WindowInsetsCompat.CONSUMED
        }
        searchService = SearchService(this)
        resultAdapter = SongAdapter()
        val resultsList = findViewById<ListView>(R.id.search_results)
        resultsList.adapter = resultAdapter
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val song = resultAdapter.getItem(position) ?: return@setOnItemClickListener
            saveSong(song)
        }
    }

    override fun onDestroy() {
        searchService.shutdown()
        super.onDestroy()
    }

    /** "搜索" button / IME action: kicks off an async NetEase keyword search. */
    fun startSearch(view: View?) {
        val keyword = findViewById<EditText>(R.id.search_keyword).text.toString()
        findViewById<View>(R.id.btn_search).isEnabled = false
        searchService.search(
            keyword,
            object : SearchService.SearchCallback {
                override fun onSearchSuccess(songs: List<Song>) {
                    mainHandler.post {
                        findViewById<View>(R.id.btn_search).isEnabled = true
                        if (songs.isEmpty()) {
                            Toast.makeText(this@MainActivity, R.string.search_empty, Toast.LENGTH_SHORT).show()
                        }
                        resultAdapter.replace(songs)
                    }
                }

                override fun onSearchError(message: String) {
                    mainHandler.post {
                        findViewById<View>(R.id.btn_search).isEnabled = true
                        Toast.makeText(this@MainActivity, getString(R.string.search_failed, message), Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    private fun saveSong(song: Song) {
        Log.w(ProviderConstants.TAG, "save requested songId=${song.songId}")
        searchService.saveSongs(listOf(song))
        Toast.makeText(this, getString(R.string.search_saved, song.title, song.artist), Toast.LENGTH_SHORT).show()
    }

    /** Two-line rows: title + artist/album/duration. Kept dependency-free on purpose. */
    private inner class SongAdapter : BaseAdapter() {
        private val songs = ArrayList<Song>()

        fun replace(newSongs: List<Song>) {
            songs.clear()
            songs.addAll(newSongs)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = songs.size

        override fun getItem(position: Int): Song = songs[position]

        override fun getItemId(position: Int): Long = songs[position].songId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val song = songs[position]
            view.findViewById<TextView>(android.R.id.text1).text = song.title
            val durationSec = song.durationMs / 1000L
            view.findViewById<TextView>(android.R.id.text2).text =
                "${song.artist} - ${song.album} (${durationSec / 60}:${"%02d".format(durationSec % 60)})"
            return view
        }
    }

    /** "检查 Provider 配置" button: queries our own roots via SAF and detects Poweramp. */
    fun checkProviderConfig(view: View?) {
        Log.w(ProviderConstants.TAG, "checkProviderConfig")

        val sb = StringBuilder()
        sb.append(getString(R.string.check_authority_line, ProviderConstants.AUTHORITY))

        val roots = try {
            contentResolver.query(
                DocumentsContract.buildRootsUri(ProviderConstants.AUTHORITY),
                null, null, null, null,
            )
        } catch (e: Exception) {
            Log.e(ProviderConstants.TAG, "provider check failed", e)
            null
        }
        roots?.use { c ->
            if (c.moveToFirst()) {
                val title = c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Root.COLUMN_TITLE))
                sb.append('\n').append(getString(R.string.check_root_found, title))
            }
        }
        sb.append('\n')
            .append(
                if (roots != null) {
                    getString(R.string.check_provider_registered)
                } else {
                    getString(R.string.check_provider_failed)
                },
            )

        val powerampPak: String? = try {
            PowerampAPIHelper.getPowerampPackageName(this, false)
        } catch (e: Throwable) {
            Log.e(ProviderConstants.TAG, "poweramp detection failed", e)
            null
        }
        sb.append('\n').append(
            if (powerampPak != null) {
                getString(R.string.check_poweramp_detected, powerampPak)
            } else {
                getString(R.string.check_poweramp_missing)
            },
        )

        findViewById<TextView>(R.id.status).text = sb.toString()
    }

    /** Opens Poweramp Settings / Library / Music Folders where the provider can be added. */
    fun openPowerampMusicFolders(view: View?) {
        Log.w(ProviderConstants.TAG, "openPowerampMusicFolders")
        try {
            val intent = Intent()
            intent.component = ComponentName(
                PowerampAPIHelper.getPowerampPackageName(this),
                PowerampAPI.ACTIVITY_SETTINGS,
            )
            intent.putExtra(PowerampAPI.Settings.EXTRA_OPEN_PATH, "library/music_folders_button")
            intent.putExtra(PowerampAPI.Settings.EXTRA_NO_BACKSTACK, true)
            intent.putExtra(PowerampAPI.EXTRA_PACKAGE, packageName)
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(ProviderConstants.TAG, "cannot open Poweramp music folders", e)
        }
    }

    fun openSettings(view: View?) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private companion object {
        /** Replaces the layout's static 16dp padding once insets are applied. */
        const val RESERVED_PAD_DP = 16
    }
}
