package com.example.tvcontrolhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.tvcontrolhub.ui.screens.DeviceDiscoveryScreen
import com.example.tvcontrolhub.ui.screens.TvRemoteControlScreen
import com.example.tvcontrolhub.ui.theme.TVControlHubTheme
import com.example.tvcontrolhub.manager.AndroidTvRemoteManager
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TVControlHubTheme {
                TVControlHubApp()
            }
        }
    }
}

@Composable
fun TVControlHubApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    
    // State for selected Android TV device for Remote/Pairing
    var selectedTv by remember { mutableStateOf<AndroidTvRemoteManager.AndroidTv?>(null) }
    
    NavHost(
        navController = navController,
        startDestination = "device_discovery"
    ) {
        // Device Discovery Screen (TV Remote + Cast unified)
        composable("device_discovery") {
            DeviceDiscoveryScreen(
                onBack = {
                    // No back action for main screen
                },
                onRemoteDeviceSelected = { tv, isPaired ->
                    selectedTv = tv
                    if (isPaired) {
                        navController.navigate("tv_remote_control")
                    } else {
                        // For unpaired TVs, could add pairing screen later
                        // For now, just go to control screen
                        navController.navigate("tv_remote_control")
                    }
                },
                onCastDeviceSelected = { routeInfo ->
                    // Cast functionality can be added later if needed
                }
            )
        }
        
        // TV Remote Control Screen (for paired devices)
        composable("tv_remote_control") {
            val tv = selectedTv
            if (tv != null) {
                TvRemoteControlScreen(
                    tv = tv,
                    onBack = {
                        selectedTv = null
                        navController.popBackStack()
                    },
                    isShortcut = false
                )
            } else {
                // No TV selected, go back
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
    }
}