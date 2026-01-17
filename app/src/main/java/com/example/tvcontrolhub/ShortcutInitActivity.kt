package com.example.tvcontrolhub

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tvcontrolhub.manager.AndroidTvRemoteManager

/**
 * Dedicated Shortcut Initialization Activity.
 * 
 * Responsibilities:
 * 1. Initialize AndroidTvRemoteManager (Service/Singletons)
 * 2. Launch the target UI (TvRemoteActivity)
 * 3. Finish immediately (no UI shown)
 */
class ShortcutInitActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prevent any default background from flashing by setting null before super
        window.setBackgroundDrawable(null)
        super.onCreate(savedInstanceState)

        // ✅ ONLY what is needed
        AndroidTvRemoteManager.initialize(this)

        // Get shortcut data
        val deviceIp = intent.getStringExtra("EXTRA_DEVICE_IP")
        val deviceName = intent.getStringExtra("EXTRA_DEVICE_NAME") ?: "TV"

        // Open ONLY the target page (TvRemoteActivity)
        if (!deviceIp.isNullOrEmpty()) {
            val targetIntent = TvRemoteActivity.createIntent(this, deviceIp, deviceName)
            startActivity(targetIntent)
        }

        // Finish immediately to keep this invisible
        finish()
    }
}
