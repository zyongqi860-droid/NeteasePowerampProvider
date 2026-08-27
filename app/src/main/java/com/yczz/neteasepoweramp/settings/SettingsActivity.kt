package com.yczz.neteasepoweramp.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.database.AppDatabase
import com.yczz.neteasepoweramp.netease.NeteaseClient
import com.yczz.neteasepoweramp.netease.NeteaseCookieStore
import com.yczz.neteasepoweramp.provider.ProviderConstants

/**
 * Static provider info, Poweramp detection, and the NetEase MUSIC_U cookie editor.
 *
 * The cookie is the user's own login credential pasted from a browser where they signed in to
 * music.163.com. It unlocks full-length URLs for VIP songs; anonymous access only gets ~30s
 * trials. Stored in private prefs, never logged, only sent to music.163.com.
 */
class SettingsActivity : Activity() {

    private lateinit var cookieStore: NeteaseCookieStore
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Stops the QR poll loop when the activity dies or a new QR is generated. */
    @Volatile
    private var qrPollActive = false

    /**
     * Shared across "send captcha" and "login" so both carry the same MUSIC_A visitor
     * session. A fresh client per click is what triggered NetEase code=10004.
     */
    private val loginClient = NeteaseClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        cookieStore = NeteaseCookieStore(this)
        val input = findViewById<EditText>(R.id.cookie_input)
        val status = findViewById<TextView>(R.id.cookie_status)
        val loginStatus = findViewById<TextView>(R.id.login_status)

        findViewById<Button>(R.id.btn_web_login).setOnClickListener {
            startActivityForResult(Intent(this, WebLoginActivity::class.java), REQ_WEB_LOGIN)
        }

        // ---- SMS captcha login (often risk-blocked; web login is the primary path) ----
        val phoneInput = findViewById<EditText>(R.id.login_phone)
        val captchaInput = findViewById<EditText>(R.id.login_captcha)
        findViewById<Button>(R.id.btn_send_captcha).setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, R.string.login_empty_phone, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loginStatus.text = getString(R.string.login_sending_captcha)
            Thread {
                val result = try {
                    loginClient.sendCaptcha(phone, DEFAULT_COUNTRY_CODE)
                    getString(R.string.login_captcha_sent)
                } catch (e: Exception) {
                    Log.e(ProviderConstants.TAG, "send captcha failed", e)
                    getString(R.string.login_failed, e.message)
                }
                mainHandler.post { loginStatus.text = result }
            }.apply { isDaemon = true; name = "netease-captcha" }.start()
        }
        findViewById<Button>(R.id.btn_login).setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            val captcha = captchaInput.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, R.string.login_empty_phone, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (captcha.isEmpty()) {
                Toast.makeText(this, R.string.login_empty_captcha, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loginStatus.text = getString(R.string.login_in_progress)
            Thread {
                val result = try {
                    val login = loginClient.loginCellphoneCaptcha(phone, DEFAULT_COUNTRY_CODE, captcha)
                    cookieStore.saveMusicU(login.musicU)
                    clearPlaybackUrlCache()
                    getString(R.string.login_success, login.account.nickname, vipLabel(login.account.vipType))
                } catch (e: Exception) {
                    Log.e(ProviderConstants.TAG, "sms login failed", e)
                    getString(R.string.login_failed, e.message)
                }
                mainHandler.post {
                    captchaInput.setText("")
                    loginStatus.text = result
                    status.text = result
                }
            }.apply { isDaemon = true; name = "netease-login" }.start()
        }

        // ---- QR scan login (fallback) ----
        val qrStatus = findViewById<TextView>(R.id.qr_status)
        findViewById<Button>(R.id.btn_qr_login).setOnClickListener {
            startQrLogin(qrStatus)
        }

        findViewById<Button>(R.id.btn_save_cookie).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                Toast.makeText(this, R.string.cookie_empty_input, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                cookieStore.saveMusicU(value)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.cookie_empty_input, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            input.setText("")
            clearPlaybackUrlCache()
            status.text = getString(R.string.cookie_status_saved)
            Log.i(ProviderConstants.TAG, "netease cookie saved (value not logged)")
            verifyStoredCookie(status)
        }

        findViewById<Button>(R.id.btn_verify_cookie).setOnClickListener {
            val musicU = cookieStore.musicU()
                ?: run {
                    status.text = getString(R.string.cookie_status_anonymous)
                    return@setOnClickListener
                }
            status.text = getString(R.string.cookie_status_verifying)
            Thread {
                val result = try {
                    val account = NeteaseClient().verifyAccount(musicU)
                    getString(R.string.cookie_status_ok, account.nickname, vipLabel(account.vipType))
                } catch (e: Exception) {
                    Log.e(ProviderConstants.TAG, "cookie verify failed", e)
                    getString(R.string.cookie_status_invalid, e.message)
                }
                mainHandler.post { status.text = result }
            }.apply { isDaemon = true; name = "cookie-verify" }.start()
        }

        findViewById<Button>(R.id.btn_clear_cookie).setOnClickListener {
            cookieStore.clear()
            input.setText("")
            clearPlaybackUrlCache()
            status.text = getString(R.string.cookie_status_anonymous)
            Log.i(ProviderConstants.TAG, "netease cookie cleared")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WEB_LOGIN) return
        val status = findViewById<TextView>(R.id.cookie_status)
        val musicU = data?.getStringExtra(WebLoginActivity.EXTRA_MUSIC_U)
        if (resultCode != RESULT_OK || musicU.isNullOrEmpty()) {
            status.text = getString(R.string.web_login_cancelled)
            return
        }
        cookieStore.saveMusicU(musicU)
        clearPlaybackUrlCache()
        verifyStoredCookie(status)
    }

    private fun verifyStoredCookie(status: TextView) {
        val musicU = cookieStore.musicU()
            ?: run {
                status.text = getString(R.string.cookie_status_anonymous)
                return
            }
        status.text = getString(R.string.cookie_status_verifying)
        Thread {
            val result = try {
                val account = NeteaseClient().verifyAccount(musicU)
                getString(R.string.cookie_status_ok, account.nickname, vipLabel(account.vipType))
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "cookie verify failed", e)
                getString(R.string.cookie_status_invalid, e.message)
            }
            mainHandler.post { status.text = result }
        }.apply { isDaemon = true; name = "cookie-verify" }.start()
    }

    /**
     * Cached URLs belong to the previous (or anonymous) account - e.g. 30s trial links. Drop
     * them so the next play re-resolves with the new cookie. Fire-and-forget on a daemon thread.
     */
    private fun clearPlaybackUrlCache() {
        Thread {
            try {
                AppDatabase.get(applicationContext).songDao().clearPlaybackCache()
                Log.i(ProviderConstants.TAG, "playback url cache cleared (cookie changed)")
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "clear playback cache failed", e)
            }
        }.apply { isDaemon = true; name = "cookie-cache-clear" }.start()
    }

    // ---------------------------------------------------------------- QR login

    /**
     * Creates a fresh login QR, shows it, then polls every [QR_POLL_INTERVAL_MS] until the user
     * scans+confirms (803), the key expires (800), or the activity goes away.
     */
    private fun startQrLogin(qrStatus: TextView) {
        qrPollActive = false // stop any previous loop
        qrStatus.text = getString(R.string.qr_status_generating)
        Thread {
            val client = NeteaseClient()
            try {
                val key = client.createQrKey()
                val qrContent = "https://music.163.com/login?codekey=${key.unikey}"
                val bitmap = renderQr(qrContent, QR_BITMAP_SIZE)
                qrPollActive = true
                mainHandler.post {
                    findViewById<ImageView>(R.id.qr_code).setImageBitmap(bitmap)
                    qrStatus.text = getString(R.string.qr_status_waiting)
                }
                pollQrLoop(client, key, qrStatus)
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "qr login start failed", e)
                mainHandler.post {
                    qrStatus.text = getString(R.string.qr_status_failed, e.message)
                }
            }
        }.apply { isDaemon = true; name = "qr-login" }.start()
    }

    private fun pollQrLoop(client: NeteaseClient, key: NeteaseClient.QrKey, qrStatus: TextView) {
        var polls = 0
        while (qrPollActive && polls < QR_MAX_POLLS) {
            try {
                Thread.sleep(QR_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
            if (!qrPollActive) return
            polls++
            val result = try {
                client.pollQrLogin(key)
            } catch (e: Exception) {
                Log.e(ProviderConstants.TAG, "qr poll failed", e)
                continue // transient network error: keep polling while the QR is valid
            }
            when (result.state) {
                NeteaseClient.QrPollResult.WAITING_FOR_SCAN -> Unit // already displayed

                NeteaseClient.QrPollResult.SCANNED_WAITING_CONFIRM ->
                    mainHandler.post { qrStatus.text = getString(R.string.qr_status_scanned) }

                NeteaseClient.QrPollResult.SUCCESS -> {
                    qrPollActive = false
                    val musicU = checkNotNull(result.musicU)
                    cookieStore.saveMusicU(musicU)
                    clearPlaybackUrlCache()
                    val account = try {
                        client.verifyAccount(musicU)
                    } catch (e: Exception) {
                        null
                    }
                    val summary = account?.let {
                        getString(R.string.qr_status_success, it.nickname, vipLabel(it.vipType))
                    } ?: getString(R.string.cookie_status_saved)
                    mainHandler.post { qrStatus.text = summary }
                    Log.i(ProviderConstants.TAG, "qr login success (cookie stored)")
                    return
                }

                NeteaseClient.QrPollResult.EXPIRED -> {
                    qrPollActive = false
                    mainHandler.post { qrStatus.text = getString(R.string.qr_status_expired) }
                    return
                }

                else -> {
                    qrPollActive = false
                    mainHandler.post {
                        qrStatus.text = getString(R.string.qr_status_failed, result.message.ifEmpty { "code=${result.state}" })
                    }
                    return
                }
            }
        }
        if (qrPollActive) {
            qrPollActive = false
            mainHandler.post { qrStatus.text = getString(R.string.qr_status_expired) }
        }
    }

    /** Renders the login URL as a QR bitmap (zxing). Called on the login thread. */
    private fun renderQr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 0),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    override fun onDestroy() {
        qrPollActive = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val statusView = findViewById<TextView>(R.id.poweramp_status)
        val powerampPak = try {
            PowerampAPIHelper.getPowerampPackageName(this, false)
        } catch (e: Throwable) {
            // Detection must never take the provider down (task section 17).
            Log.e(ProviderConstants.TAG, "poweramp detection failed", e)
            null
        }
        statusView.text =
            if (powerampPak != null) {
                getString(R.string.poweramp_detected, powerampPak)
            } else {
                getString(R.string.poweramp_not_detected)
            }
    }

    private fun vipLabel(vipType: Int): String = when (vipType) {
        0 -> getString(R.string.cookie_vip_none)
        11 -> getString(R.string.cookie_vip_vip)
        13 -> getString(R.string.cookie_vip_svip)
        else -> getString(R.string.cookie_vip_other, vipType)
    }

    private companion object {
        const val REQ_WEB_LOGIN = 1001

        /** CN mobile numbers; the only market this app realistically targets. */
        const val DEFAULT_COUNTRY_CODE = "86"

        const val QR_BITMAP_SIZE = 480
        const val QR_POLL_INTERVAL_MS = 3000L

        /** ~4 minutes of polling before giving up (NetEase keys expire around then anyway). */
        const val QR_MAX_POLLS = 80
    }
}
