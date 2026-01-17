package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.manager.MdnsManager
import com.example.myapplication.manager.PermissionManager
import com.example.myapplication.manager.AndroidMdnsService
import com.example.myapplication.manager.AndroidTvRemoteManager
import com.example.myapplication.manager.ThemePreferenceManager
import com.example.myapplication.ui.screens.*
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.manager.AuthManager
import com.example.myapplication.ui.components.AndroidAccountData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds shared intent data to pass to ShareFilesScreen
 */
object SharedIntentData {
    var sharedFiles: List<Uri> = emptyList()
    var sharedText: String? = null
    var sharedUrl: String? = null
    
    fun clear() {
        sharedFiles = emptyList()
        sharedText = null
        sharedUrl = null
    }
    
    fun hasContent(): Boolean {
        return sharedFiles.isNotEmpty() || !sharedText.isNullOrEmpty() || !sharedUrl.isNullOrEmpty()
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize PermissionManager
        PermissionManager.initialize(this)
        AuthManager.initialize(this)
        
        // Initialize ThemePreferenceManager
        ThemePreferenceManager.initialize(this)
        
        // Start AndroidMdnsService to broadcast this device
        AndroidMdnsService.start(this)
        
        // Handle share intent
        handleShareIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            // Observe theme preference using collectAsState
            val themePreference by ThemePreferenceManager.themePreference.collectAsState()
            
            MyApplicationTheme(themePreference = themePreference) {
                LiveNotificationApp()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop AndroidMdnsService when app closes
        AndroidMdnsService.stop()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle share intent
        handleShareIntent(intent)
    }
    
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        
        // Handle Share Intent
        when (intent.action) {
            Intent.ACTION_SEND -> {
                // Single item shared
                val mimeType = intent.type ?: ""
                
                if (mimeType.startsWith("text/")) {
                    // Text or URL shared
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrEmpty()) {
                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            SharedIntentData.sharedUrl = text
                            SharedIntentData.sharedText = null
                        } else {
                            SharedIntentData.sharedText = text
                            SharedIntentData.sharedUrl = null
                        }
                        SharedIntentData.sharedFiles = emptyList()
                    }
                } else {
                    // File shared
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        SharedIntentData.sharedFiles = listOf(uri)
                        SharedIntentData.sharedText = null
                        SharedIntentData.sharedUrl = null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple files shared
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    SharedIntentData.sharedFiles = uris.toList()
                    SharedIntentData.sharedText = null
                    SharedIntentData.sharedUrl = null
                }
            }
        }
    }
}

/**
 * Handles navigation events from Home Screen Shortcuts
 */
object ShortcutHandler {
    private val _shortcutEvent = kotlinx.coroutines.flow.MutableStateFlow<AndroidTvRemoteManager.AndroidTv?>(null)
    val shortcutEvent = _shortcutEvent.asStateFlow()

    fun trigger(tv: AndroidTvRemoteManager.AndroidTv) {
        _shortcutEvent.value = tv
    }

    fun clear() {
        _shortcutEvent.value = null
    }
}

@Composable
fun LiveNotificationApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    
    // Check if required permissions are granted
    val areRequiredPermissionsGranted = remember {
        mutableStateOf(PermissionManager.areRequiredPermissionsGranted(context))
    }
    
    // Auth State
    val scope = rememberCoroutineScope()
    var currentUser by remember { mutableStateOf(AuthManager.getCurrentUser()) }
    
    // Auth State Listener
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose {
            FirebaseAuth.getInstance().removeAuthStateListener(listener)
        }
    }

    // Google Sign In Launcher
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            val user = AuthManager.signInWithGoogle(result.data)
            if (user != null) {
                Toast.makeText(context, "Welcome ${user.displayName}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Sign in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // State for selected Windows device
    var selectedDevice by remember { mutableStateOf<MdnsManager.DiscoveredDevice?>(null) }
    
    // State for selected Android TV device for Remote/Pairing
    var selectedTv by remember { mutableStateOf<AndroidTvRemoteManager.AndroidTv?>(null) }
    
    // Determine start destination based on permission status and login
    val arePermissionsGranted = areRequiredPermissionsGranted.value
    val isLoggedIn = currentUser != null
    val hasSharedContent = SharedIntentData.hasContent()
    
    // If shared content exists and user is logged in, go directly to share_files
    val startDestination = when {
        !arePermissionsGranted || !isLoggedIn -> "permissions"
        hasSharedContent -> "share_files"
        else -> "main"
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(400))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(400))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(400))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(400))
        }
    ) {
        // ═══════════════════════════════════════════════════════════════
        // Permission Screen (shown first if permissions not granted)
        // ═══════════════════════════════════════════════════════════════
        composable("permissions") {
            PermissionScreen(
                onAllPermissionsGranted = {
                    navController.navigate("main") {
                        popUpTo("permissions") { inclusive = true }
                    }
                },
                onContinueClick = {
                    // This is called when both permissions and login are successful
                    if (PermissionManager.areRequiredPermissionsGranted(context) && AuthManager.getCurrentUser() != null) {
                        navController.navigate("main") {
                            popUpTo("permissions") { inclusive = true }
                        }
                    }
                },
                onNavigateToAnonymousLogin = {
                    // Navigate to Avatar Selection / Create Profile screen
                    navController.navigate("create_profile")
                }
            )
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Create Profile Screen (Anonymous Login with Avatar Selection)
        // ═══════════════════════════════════════════════════════════════
        composable("create_profile") {
            AvatarSelectionScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onProfileCreated = { avatarId, displayName ->
                    // Sign in anonymously with selected avatar and name
                    scope.launch {
                        val user = AuthManager.signInAnonymously(avatarId, displayName)
                        if (user != null) {
                            Toast.makeText(context, "Welcome $displayName!", Toast.LENGTH_SHORT).show()
                            // Navigate to main screen after successful login
                            navController.navigate("main") {
                                popUpTo("permissions") { inclusive = true }
                            }
                        } else {
                            Toast.makeText(context, "Failed to create profile", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Missing Permissions Screen (Flux UI)
        // ═══════════════════════════════════════════════════════════════
        composable("missing_permissions") {
             MissingPermissionsScreen(
                 onBackClick = {
                     navController.popBackStack()
                 }
             )
        }

        // ═══════════════════════════════════════════════════════════════
        // Main Home Screen
        // ═══════════════════════════════════════════════════════════════
        composable("main") {
            MainScreen(

                // Audio Cast button → Audio Cast Screen
                onAudioCastClick = {
                    // Use selected device IP if available
                    navController.navigate("audio_cast")
                },
                // Send Files button -> ShareFilesScreen
                onSendFilesClick = {
                    navController.navigate("share_files")
                },
                // Stream Video button -> StreamVideoScreen
                onStreamVideoClick = {
                    navController.navigate("stream_video")
                },
                // Review permissions button → Missing Permissions Screen
                // Review permissions button → Missing Permissions Screen
                onReviewPermissionsClick = {
                    navController.navigate("missing_permissions")
                },
                // Windows device card → Connected device details
                onWindowsDeviceClick = { device ->
                    selectedDevice = device
                    if (device != null) {
                        navController.navigate("windows_connected")
                    } else {
                        Toast.makeText(context, "No device selected", Toast.LENGTH_SHORT).show()
                    }
                },
                // Android account card → User profile
                onAndroidAccountClick = {
                    if (currentUser == null) {
                        val signInIntent = AuthManager.getSignInIntent()
                        if (signInIntent != null) {
                            authLauncher.launch(signInIntent)
                        } else {
                            Toast.makeText(context, "Auth not initialized", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        navController.navigate("user_profile")
                    }
                },
                // Pass current user data
                accountData = currentUser?.let { user ->
                    // Use cached profile data (works for both Google and Anonymous users)
                    val cachedDisplayName = AuthManager.getCachedDisplayName()
                    val cachedEmail = AuthManager.getCachedEmail()
                    val cachedAvatarId = AuthManager.getCachedAvatarId()
                    val isAnonymous = AuthManager.isAnonymousUser()
                    
                    AndroidAccountData(
                        displayName = cachedDisplayName.ifEmpty { user.displayName ?: "User" },
                        email = cachedEmail.ifEmpty { user.email ?: "" },
                        photoUrl = user.photoUrl?.toString(), // Only for Google users
                        isSignedIn = true,
                        isAnonymous = isAnonymous,
                        avatarId = if (isAnonymous && cachedAvatarId > 0) cachedAvatarId else null
                    )
                },

                // External links → Open in browser
                onLinkClick = { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                    }
                },
                // GitHub logo → Open GitHub profile
                onGithubClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shrey113"))
                    context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open GitHub", Toast.LENGTH_SHORT).show()
                    }
                },
                // TV button -> DeviceDiscoveryScreen (TV Remote + Cast)
                onTvClick = {
                    navController.navigate("device_discovery")
                },
                // Settings button -> SettingsScreen
                onSettingsClick = {
                    navController.navigate("settings")
                },
                // Show permission bar if not all permissions granted
                showMissingPermissions = !PermissionManager.areAllPermissionsGranted(context),
                showUsbDebuggingWarning = false
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Device Discovery Screen (TV Remote + Cast unified)
        // ═══════════════════════════════════════════════════════════════
        composable("device_discovery") {
            DeviceDiscoveryScreen(
                onBack = {
                    navController.popBackStack()
                },
                onRemoteDeviceSelected = { tv, isPaired ->
                    selectedTv = tv
                    if (isPaired) {
                        navController.navigate("tv_remote_control")
                    } else {
                        navController.navigate("tv_remote_pairing")
                    }
                },
                onCastDeviceSelected = { routeInfo ->
                    // Navigate to Cast Media screen after device selected
                    navController.navigate("cast_media")
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Cast Media Screen - Direct access, device selection via MediaRouteButton
        // ═══════════════════════════════════════════════════════════════
        composable("cast_media") {
            CastMediaScreen(
                deviceName = "Cast Device",  // Default name, actual device shown after connection
                deviceId = "",
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        
        
        // ═══════════════════════════════════════════════════════════════
        // TV Remote Control Screen (for paired devices)
        // ═══════════════════════════════════════════════════════════════
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


        // ═══════════════════════════════════════════════════════════════
        // Stream Video Screen
        // ═══════════════════════════════════════════════════════════════
        composable("stream_video") {
            StreamVideoScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                // We need Android IP here. For now passing 0.0.0.0, will fix in Screen
                ipAddress = "0.0.0.0"
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Share Files Screen
        // ═══════════════════════════════════════════════════════════════
        composable("share_files") {
            ShareFilesScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }



        // ═══════════════════════════════════════════════════════════════
        // Windows Connected Device Details Screen
        // ═══════════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════
        // Windows Connected Device Details Screen
        // ═══════════════════════════════════════════════════════════════
        composable("windows_connected") {
            WindowsConnectedScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                // Pass device data for server connection
                ipAddress = selectedDevice?.ipAddress ?: "0.0.0.0",
                hostname = selectedDevice?.hostname ?: "Unknown Device",
                email = selectedDevice?.email ?: ""
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // User Profile Screen
        // ═══════════════════════════════════════════════════════════════
        composable("user_profile") {
            UserProfileScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSignOut = {
                    AuthManager.signOut()
                    // Navigate back to permissions/login flow
                    navController.navigate("permissions") {
                        popUpTo("main") { inclusive = true }
                    }
                    Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Audio Cast Screen
        // ═══════════════════════════════════════════════════════════════
        composable("audio_cast") {
            AudioCastScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Settings Screen
        // ═══════════════════════════════════════════════════════════════
        composable("settings") {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}