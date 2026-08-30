package com.yczz.neteasepoweramp.floatsearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.model.Song
import com.yczz.neteasepoweramp.provider.ProviderConstants
import com.yczz.neteasepoweramp.service.SearchService

/**
 * Floating NetEase search panel that sits on top of Poweramp (scheme 3: zero Poweramp changes).
 *
 * A draggable bubble expands into a search panel; typing a keyword live-queries NetEase through
 * [SearchService]; tapping a result saves it to the provider library and hands the document to
 * Poweramp for immediate playback, then asks Poweramp to rescan so the track stays in its index.
 *
 * Threading: all view work happens on the main thread; SearchService delivers results via
 * [mainHandler].
 */
class FloatingSearchService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var searchService: SearchService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val layoutInflater by lazy { getSystemService(android.view.LayoutInflater::class.java) }

    private var bubbleView: View? = null
    private var panelView: View? = null
    private lateinit var resultsAdapter: ResultsAdapter
    private lateinit var keywordInput: EditText

    private var panelOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        searchService = SearchService(this)
        resultsAdapter = ResultsAdapter()
        startForegroundWithNotification()
        addBubble()
        Log.w(ProviderConstants.TAG, "floating search service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        removeBubble()
        searchService.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bubble

    private fun addBubble() {
        if (bubbleView != null) return
        val size = dp(BUBBLE_SIZE_DP)
        val bubble = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE5F57C22.toInt())
            }
            background = bg
            elevation = dp(6f).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            val pad = dp(12).toInt()
            setPadding(pad, pad, pad, pad)
            setColorFilter(Color.WHITE)
        }
        bubble.addView(
            icon,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        bubble.setOnTouchListener(BubbleTouchListener())
        windowManager.addView(
            bubble,
            WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(BUBBLE_X_DP).toInt()
                y = dp(BUBBLE_Y_DP).toInt()
            },
        )
        bubbleView = bubble
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    /** Drags the bubble; a tap (no drag) toggles the panel. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                (v.layoutParams as? WindowManager.LayoutParams)?.let {
                    startX = it.x
                    startY = it.y
                }
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (moved || dx * dx + dy * dy > dp(8f) * dp(8f)) {
                    moved = true
                    val lp = v.layoutParams as WindowManager.LayoutParams
                    lp.x = startX + dx
                    lp.y = startY + dy
                    windowManager.updateViewLayout(v, lp)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    togglePanel()
                }
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ panel

    private fun togglePanel() {
        if (panelOpen) removePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelOpen) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(0xF2141414.toInt())
                setStroke(dp(1).toInt(), 0x33F57C22)
            }
            elevation = dp(10f).toFloat()
            clipToOutline = true
        }

        // Header: keyword input + go button.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10).toInt(), dp(8).toInt(), dp(10).toInt(), dp(8).toInt())
        }
        keywordInput = EditText(this).apply {
            hint = getString(R.string.floating_search_hint)
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(0xFF232323.toInt())
            }
            setPadding(dp(10).toInt(), dp(8).toInt(), dp(10).toInt(), dp(8).toInt())
            setOnEditorActionListener { _, _, _ ->
                runSearch()
                true
            }
        }
        header.addView(
            keywordInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val go = TextView(this).apply {
            text = getString(R.string.btn_search)
            setTextColor(Color.WHITE)
            setPadding(dp(14).toInt(), dp(6).toInt(), dp(14).toInt(), dp(6).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(0xFFF57C22.toInt())
            }
            setOnClickListener { runSearch() }
        }
        header.addView(
            go,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8).toInt()
            },
        )
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = ListView(this).apply {
            adapter = resultsAdapter
            dividerHeight = 1
            setDivider(GradientDrawable().apply { setColor(0x22FFFFFF) })
            setOnItemClickListener { _, _, position, _ ->
                val song = resultsAdapter.getItem(position) ?: return@setOnItemClickListener
                playInPoweramp(song)
            }
        }
        panel.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4).toInt()
            },
        )

        val lp = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP).toInt(),
            dp(PANEL_HEIGHT_DP).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Place the panel next to wherever the bubble currently sits.
            (bubbleView?.layoutParams as? WindowManager.LayoutParams)?.let {
                x = (it.x - dp(PANEL_WIDTH_DP - BUBBLE_SIZE_DP) / 2)
                    .coerceAtLeast(dp(8).toInt())
                y = (it.y + dp(BUBBLE_SIZE_DP + 8))
                    .coerceAtMost(resources.displayMetrics.heightPixels - dp(PANEL_HEIGHT_DP + 24))
            }
        }
        windowManager.addView(panel, lp)
        panelView = panel
        panelOpen = true
        keywordInput.requestFocus()
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelOpen = false
    }

    // ------------------------------------------------------------------ search & play

    private fun runSearch() {
        val keyword = keywordInput.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) return
        resultsAdapter.setSearching()
        searchService.search(
            keyword,
            object : SearchService.SearchCallback {
                override fun onSearchSuccess(songs: List<Song>) {
                    mainHandler.post {
                        resultsAdapter.replace(songs)
                        if (songs.isEmpty()) {
                            Toast.makeText(this@FloatingSearchService, R.string.search_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onSearchError(message: String) {
                    mainHandler.post {
                        resultsAdapter.replace(emptyList())
                        Toast.makeText(
                            this@FloatingSearchService,
                            getString(R.string.search_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    /** Saves the song and hands it to Poweramp exactly like the in-app flow (no UI switch back here). */
    private fun playInPoweramp(song: Song) {
        searchService.saveSongs(listOf(song)) {
            mainHandler.post {
                val tree = DocumentsContract.buildTreeDocumentUri(
                    ProviderConstants.AUTHORITY,
                    ProviderConstants.ROOT_DOC_ID,
                )
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, ProviderConstants.songDocId(song.songId))
                try {
                    val pak = PowerampAPIHelper.getPowerampPackageName(this)
                    grantUriPermission(pak, doc, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val open = Intent(PowerampAPI.ACTION_OPEN_LIST_AND_PLAY)
                    open.setPackage(pak)
                    open.setDataAndType(doc, "audio/mpeg")
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(open)
                    Toast.makeText(this, getString(R.string.search_playing, song.title), Toast.LENGTH_SHORT).show()
                    removePanel()
                } catch (e: Throwable) {
                    Log.e(ProviderConstants.TAG, "floating search play failed", e)
                    Toast.makeText(this, getString(R.string.search_saved, song.title, song.artist), Toast.LENGTH_SHORT).show()
                }
                notifyProviderChanged()
            }
        }
    }

    private fun notifyProviderChanged() {
        try {
            contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(ProviderConstants.AUTHORITY),
                null,
            )
            val scan = Intent(PowerampAPI.Scanner.ACTION_SCAN_DIRS)
            scan.setComponent(PowerampAPIHelper.getApiReceiverComponentName(this))
            scan.putExtra(PowerampAPI.Scanner.EXTRA_SCAN_PROVIDERS, true)
            sendBroadcast(scan)
        } catch (e: Exception) {
            Log.e(ProviderConstants.TAG, "floating search rescan notify failed", e)
        }
    }

    // ------------------------------------------------------------------ results adapter

    /** Two-line rows reusing the in-app item layout; a single placeholder row while searching. */
    private inner class ResultsAdapter : BaseAdapter() {
        private val songs = ArrayList<Song>()
        private var searching = false

        fun replace(newSongs: List<Song>) {
            searching = false
            songs.clear()
            songs.addAll(newSongs)
            notifyDataSetChanged()
        }

        fun setSearching() {
            searching = true
            songs.clear()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = if (searching) 1 else songs.size

        override fun getItem(position: Int): Song? =
            if (searching || position >= songs.size) null else songs[position]

        override fun getItemId(position: Int): Long =
            getItem(position)?.songId ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (searching) {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_search_song, parent, false)
                view.findViewById<TextView>(R.id.song_title).text = getString(R.string.floating_searching)
                view.findViewById<TextView>(R.id.song_subtitle).text = ""
                return view
            }
            val song = songs[position]
            val view = convertView ?: layoutInflater.inflate(R.layout.item_search_song, parent, false)
            view.findViewById<TextView>(R.id.song_title).text = song.title
            view.findViewById<TextView>(R.id.song_subtitle).text = TextUtils.concat(
                song.artist, " · ", song.album,
            )
            return view
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.floating_channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setOngoing(true)
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "floating_search"
        private const val NOTIFICATION_ID = 42

        private const val BUBBLE_SIZE_DP = 48
        private const val BUBBLE_X_DP = 24f
        private const val BUBBLE_Y_DP = 220f
        private const val PANEL_WIDTH_DP = 340
        private const val PANEL_HEIGHT_DP = 420

        const val ACTION_STOP = "com.yczz.neteasepoweramp.floatsearch.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingSearchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingSearchService::class.java).setAction(ACTION_STOP))
        }
    }
}
