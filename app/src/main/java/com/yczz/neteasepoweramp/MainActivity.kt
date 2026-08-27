package com.yczz.neteasepoweramp

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.CheckBox
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
import com.yczz.neteasepoweramp.service.PlaylistService
import com.yczz.neteasepoweramp.service.SearchService
import com.yczz.neteasepoweramp.settings.SettingsActivity

/**
 * Launcher UI.
 *
 * Responsibilities (Provider data-source only — no player UI):
 * - NetEase keyword search + save results to Room (Provider cache)
 * - Playlist import by numeric id
 * - Sync to Poweramp (notifyChange + scan broadcast)
 * - Provider status self-check
 * - Open Poweramp Music Folders / Settings
 */
class MainActivity : Activity() {

    private lateinit var searchService: SearchService
    private lateinit var playlistService: PlaylistService
    private lateinit var resultAdapter: SongAdapter
    private lateinit var libraryAdapter: SongAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35 edge-to-edge inset handling.
        val root = findViewById<View>(R.id.main_root)
        val contentPad = (RESERVED_PAD_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left + contentPad, bars.top + contentPad, bars.right + contentPad, bars.bottom + contentPad)
            WindowInsetsCompat.CONSUMED
        }

        // Services.
        searchService = SearchService(this)
        searchService.ensureRemoteTestSong()
        playlistService = PlaylistService(this)

        // Search results list.
        resultAdapter = SongAdapter(checkable = false)
        val resultsList = findViewById<ListView>(R.id.search_results)
        resultsList.adapter = resultAdapter
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val song = resultAdapter.getItem(position) ?: return@setOnItemClickListener
            saveSong(song)
        }

        libraryAdapter = SongAdapter(checkable = true)
        val libraryList = findViewById<ListView>(R.id.saved_songs)
        libraryList.adapter = libraryAdapter
        libraryList.setOnItemClickListener { _, _, position, _ ->
            libraryAdapter.toggle(position)
        }
        findViewById<View>(R.id.btn_select_all).setOnClickListener { libraryAdapter.toggleAll() }
        findViewById<View>(R.id.btn_delete_selected).setOnClickListener { confirmDeleteSelected() }
        refreshLibrary()
    }

    override fun onDestroy() {
        searchService.shutdown()
        playlistService.shutdown()
        super.onDestroy()
    }

    // ==================== Search & Save ====================

    /** "搜索" button / IME action. */
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
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.search_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    private fun saveSong(song: Song) {
        Log.w(ProviderConstants.TAG, "save requested songId=${song.songId}")
        searchService.saveSongs(listOf(song))
        Toast.makeText(this, getString(R.string.search_saved, song.title, song.artist), Toast.LENGTH_SHORT).show()
        // Auto-rescan so the song shows up in Poweramp without a manual sync (saveSongs is async).
        mainHandler.postDelayed({
            refreshLibrary()
            triggerPowerampRescan(showToast = false)
        }, RESCAN_DELAY_MS)
    }

    private fun confirmDeleteSelected() {
        val ids = libraryAdapter.selectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.library_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_delete_confirm_title)
            .setMessage(getString(R.string.library_delete_confirm_message, ids.size))
            .setPositiveButton(R.string.library_delete_confirm) { _, _ -> deleteSongs(ids) }
            .setNegativeButton(R.string.library_delete_cancel, null)
            .show()
    }

    private fun deleteSongs(songIds: List<Long>) {
        searchService.deleteSongs(songIds) {
            mainHandler.post {
                Toast.makeText(this, getString(R.string.library_deleted, songIds.size), Toast.LENGTH_SHORT).show()
                libraryAdapter.clearSelection()
                refreshLibrary()
                triggerPowerampRescan(showToast = false)
            }
        }
    }

    private fun refreshLibrary() {
        searchService.listSavedSongs { songs ->
            mainHandler.post {
                libraryAdapter.replace(songs)
                findViewById<TextView>(R.id.library_header).text =
                    getString(R.string.library_header, songs.size)
            }
        }
    }

    // ==================== Playlist Import ====================

    /** "导入歌单" button. */
    fun importPlaylist(view: View?) {
        val raw = findViewById<EditText>(R.id.playlist_id).text.toString().trim()
        val playlistId = raw.toLongOrNull()
        if (playlistId == null || playlistId <= 0L) {
            Toast.makeText(this, R.string.playlist_id_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<View>(R.id.btn_import_playlist).isEnabled = false
        playlistService.importPlaylist(
            playlistId,
            object : PlaylistService.ImportCallback {
                override fun onImportSuccess(playlistName: String, songCount: Int) {
                    mainHandler.post {
                        findViewById<View>(R.id.btn_import_playlist).isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.playlist_imported, playlistName, songCount),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun onImportError(message: String) {
                    mainHandler.post {
                        findViewById<View>(R.id.btn_import_playlist).isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.playlist_import_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    // ==================== Provider Sync & Status ====================

    /** "同步到 Poweramp" button. */
    fun syncProvider(view: View?) {
        Log.w(ProviderConstants.TAG, "sync requested")
        triggerPowerampRescan(showToast = true)
    }

    /** Notifies SAF observers and asks Poweramp to rescan providers. */
    private fun triggerPowerampRescan(showToast: Boolean) {
        try {
            contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(ProviderConstants.AUTHORITY),
                null,
            )
        } catch (e: Exception) {
            Log.e(ProviderConstants.TAG, "notifyChange failed", e)
        }
        try {
            val intent = Intent(PowerampAPI.Scanner.ACTION_SCAN_DIRS)
            intent.setComponent(PowerampAPIHelper.getApiReceiverComponentName(this))
            intent.putExtra(PowerampAPI.Scanner.EXTRA_SCAN_PROVIDERS, true)
            sendBroadcast(intent)
            if (showToast) {
                Toast.makeText(this, R.string.sync_done, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Log.e(ProviderConstants.TAG, "poweramp scan broadcast failed", e)
        }
    }

    /** "检查 Provider 配置" button. */
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

    /** Opens Poweramp Settings / Library / Music Folders. */
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

    // ==================== Song Adapter ====================

    /** Two-line rows: title + artist/album/duration. Optional checkboxes for the library list. */
    private inner class SongAdapter(private val checkable: Boolean) : BaseAdapter() {
        private val songs = ArrayList<Song>()
        private val selected = HashSet<Long>()

        fun replace(newSongs: List<Song>) {
            songs.clear()
            songs.addAll(newSongs)
            selected.retainAll(newSongs.map { it.songId }.toSet())
            notifyDataSetChanged()
        }

        fun toggle(position: Int) {
            if (!checkable) return
            val id = songs[position].songId
            if (!selected.add(id)) selected.remove(id)
            notifyDataSetChanged()
        }

        fun toggleAll() {
            if (!checkable || songs.isEmpty()) return
            if (selected.size == songs.size) selected.clear() else songs.forEach { selected.add(it.songId) }
            notifyDataSetChanged()
        }

        fun clearSelection() {
            selected.clear()
            notifyDataSetChanged()
        }

        fun selectedIds(): List<Long> = selected.toList()

        override fun getCount(): Int = songs.size

        override fun getItem(position: Int): Song = songs[position]

        override fun getItemId(position: Int): Long = songs[position].songId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val song = songs[position]
            val durationSec = song.durationMs / 1000L
            val subtitle = "${song.artist} · ${song.album}  ${durationSec / 60}:${"%02d".format(durationSec % 60)}"
            val layout = if (checkable) R.layout.item_saved_song else R.layout.item_search_song
            val view = convertView ?: layoutInflater.inflate(layout, parent, false)
            view.findViewById<TextView>(R.id.song_title).text = song.title
            view.findViewById<TextView>(R.id.song_subtitle).text = subtitle
            if (checkable) {
                view.findViewById<CheckBox>(R.id.song_check).isChecked = selected.contains(song.songId)
            }
            return view
        }
    }

    companion object {
        private const val RESERVED_PAD_DP = 16

        /** Gives the async Room insert time to land before Poweramp rescans. */
        private const val RESCAN_DELAY_MS = 1500L
    }
}
