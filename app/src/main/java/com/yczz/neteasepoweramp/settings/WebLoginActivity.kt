package com.yczz.neteasepoweramp.settings

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.netease.NeteaseCookieStore
import com.yczz.neteasepoweramp.provider.ProviderConstants

/**
 * Opens the official NetEase web login page. After the user signs in (QR / SMS / third-party
 * on music.163.com itself), MUSIC_U is read from the WebView cookie store and returned.
 *
 * This is the reliable login path: NetEase risk-controls API-only SMS/password from this app
 * (code 10004), but accepts a real browser session.
 */
class WebLoginActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)

        val webView = findViewById<WebView>(R.id.web_login)
        val progress = findViewById<ProgressBar>(R.id.web_login_progress)
        val status = findViewById<TextView>(R.id.web_login_status)

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
                tryCapture(status)
            }
        }

        status.text = getString(R.string.web_login_status_loading)
        webView.loadUrl(LOGIN_URL, mapOf("Referer" to "https://music.163.com/"))
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (captured || isFinishing) return
                tryCapture(status)
                mainHandler.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
    }

    private fun tryCapture(status: TextView) {
        if (captured) return
        val raw = CookieManager.getInstance().getCookie("https://music.163.com").orEmpty()
        val musicU = NeteaseCookieStore.parseMusicU(raw) ?: return
        captured = true
        status.text = getString(R.string.web_login_status_success)
        val data = intent
        data.putExtra(EXTRA_MUSIC_U, musicU)
        setResult(RESULT_OK, data)
        Log.i(ProviderConstants.TAG, "web login captured MUSIC_U (value not logged)")
        mainHandler.postDelayed({ finish() }, 400)
    }

    override fun onDestroy() {
        captured = true
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MUSIC_U = "music_u"
        private const val LOGIN_URL = "https://music.163.com/login"
        private const val POLL_MS = 1500L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
