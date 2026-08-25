package com.yczz.neteasepoweramp.settings

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.yczz.neteasepoweramp.R
import com.yczz.neteasepoweramp.provider.ProviderConstants

/** Phase-1 settings page: static provider info plus a best-effort Poweramp detection. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
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
}
