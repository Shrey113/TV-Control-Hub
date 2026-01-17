package com.example.tvcontrolhub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.tvcontrolhub.manager.AndroidTvRemoteManager
import com.example.tvcontrolhub.ui.screens.TvRemoteControlScreen
import com.example.tvcontrolhub.ui.theme.TVControlHubTheme

/**
 * Clean, lightweight Activity to host ONLY the TV Remote Control screen.
 * This is launched by ShortcutInitActivity.
 */
class TvRemoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val deviceIp = intent.getStringExtra("EXTRA_DEVICE_IP")
        val deviceName = intent.getStringExtra("EXTRA_DEVICE_NAME") ?: "TV"
        
        if (deviceIp.isNullOrEmpty()) {
            Log.e("TvRemoteActivity", "Missing device IP, finishing")
            finish()
            return
        }

        // Construct the TV object
        val tv = AndroidTvRemoteManager.AndroidTv(
            name = deviceName,
            ipAddress = deviceIp,
            modelName = "Android TV",
            isPaired = true 
        )

        setContent {
            TVControlHubTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TvRemoteControlScreen(
                        tv = tv,
                        onBack = {
                            // Finish this activity directly
                            finish()
                        },
                        isShortcut = true // This hides the back button if that logic is still desired
                    )
                }
            }
        }
    }
    
    companion object {
        fun createIntent(context: Context, deviceIp: String, deviceName: String): Intent {
            return Intent(context, TvRemoteActivity::class.java).apply {
                putExtra("EXTRA_DEVICE_IP", deviceIp)
                putExtra("EXTRA_DEVICE_NAME", deviceName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }
}
