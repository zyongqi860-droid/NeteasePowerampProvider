package com.yczz.neteasepoweramp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.TextView
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.yczz.neteasepoweramp.provider.ProviderConstants
import com.yczz.neteasepoweramp.settings.SettingsActivity

/**
 * Minimal phase-1 launcher UI: status, provider self-check and shortcuts into Poweramp.
 * No complex UI on purpose (task section 16).
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
}
