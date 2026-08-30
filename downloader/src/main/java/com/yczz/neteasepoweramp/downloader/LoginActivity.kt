package com.yczz.neteasepoweramp.downloader

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yczz.neteasepoweramp.downloader.netease.NeteaseApi

/**
 * NetEase login for the downloader: hosts the official music.163.com login page in a WebView
 * (the page offers QR / SMS / password / third-party logins itself). Once MUSIC_U shows up in
 * the WebView cookie store the login is saved and the page closes. Pasting a cookie from a
 * desktop browser session stays available as a fallback.
 */
class LoginActivity : Activity() {

    private lateinit var api: NeteaseApi
    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        api = NeteaseApi(this)
        statusView = findViewById(R.id.login_status)
        webView = findViewById(R.id.login_web)
        val progress = findViewById<ProgressBar>(R.id.login_progress)

        // targetSdk 35 is edge-to-edge: keep the status line below the system status bar.
        val root = findViewById<View>(R.id.login_root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        refreshStatus()

        findViewById<Button>(R.id.btn_paste_cookie).setOnClickListener { pasteCookieDialog() }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                tryCapture()
            }
        }
        statusView.text = getString(R.string.login_web_loading)
        webView.loadUrl(LOGIN_URL, mapOf("Referer" to "https://music.163.com/"))
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (captured || isFinishing) return
                tryCapture()
                mainHandler.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
    }

    override fun onDestroy() {
        captured = true
        mainHandler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private fun tryCapture() {
        if (captured) return
        val raw = CookieManager.getInstance().getCookie("https://music.163.com").orEmpty()
        val token = raw.split(";", "\n")
            .map { it.trim() }
            .firstOrNull { it.startsWith("MUSIC_U=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
            ?: return
        captured = true
        api.musicU = token
        Log.i(TAG, "web login captured MUSIC_U (value not logged)")
        refreshStatus()
        mainHandler.postDelayed({ finish() }, 600)
    }

    private fun refreshStatus() {
        Thread {
            val account = try {
                api.verifyAccount()
            } catch (t: Throwable) {
                null
            }
            mainHandler.post {
                statusView.text = when {
                    account != null -> getString(R.string.login_ok, account.nickname, account.vipLabel)
                    api.musicU != null -> getString(R.string.login_expired)
                    else -> getString(R.string.login_anonymous)
                }
            }
        }.start()
    }

    private fun pasteCookieDialog() {
        val input = android.widget.EditText(this)
        input.hint = getString(R.string.login_cookie_hint)
        val container = android.widget.FrameLayout(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, 0)
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.login_cookie_title)
            .setView(container)
            .setPositiveButton(R.string.login_cookie_save) { _, _ ->
                if (api.importCookie(input.text?.toString().orEmpty())) {
                    Toast.makeText(this, R.string.login_cookie_saved, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                } else {
                    Toast.makeText(this, R.string.login_cookie_invalid, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val TAG = "NeteaseLogin"
        const val LOGIN_URL = "https://music.163.com/login"
        const val POLL_MS = 1500L
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
