package com.example.tvcontrolhub.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.tvcontrolhub.manager.AndroidTvRemoteManager
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.compose.ui.platform.LocalContext
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import androidx.core.content.getSystemService
import android.widget.Toast
import android.os.Build
import com.example.tvcontrolhub.MainActivity
import com.example.tvcontrolhub.R
import com.example.tvcontrolhub.ui.components.RemoteSettingsDialog
import com.example.tvcontrolhub.ui.components.RemoteSettingsManager
import com.example.tvcontrolhub.ui.components.LocalRemoteColors
import com.example.tvcontrolhub.ui.components.getRemoteColors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.focusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange

// Professional Enterprise Dark Theme
// Colors are now handled by LocalRemoteColors via RemoteSettingsManager

// Modern Shapes
private val ButtonShape = RoundedCornerShape(16.dp)
private val CardShape = RoundedCornerShape(24.dp)
private val DPadShape = RoundedCornerShape(28.dp)

/**
 * Premium Remote Control Screen for controlling a paired Android TV
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteControlScreen(
    tv: AndroidTvRemoteManager.AndroidTv,
    onBack: () -> Unit,
    isShortcut: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isConnecting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    // Observe connection state
    val connectionState by AndroidTvRemoteManager.connectionState.collectAsState()
    
    // Observe volume info
    val volumeInfo by AndroidTvRemoteManager.volumeInfo.collectAsState()
    
    // Keyboard dialog state
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    
    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    val colors = getRemoteColors()
    CompositionLocalProvider(LocalRemoteColors provides colors) {
    
    // Connect on screen open
    LaunchedEffect(tv.ipAddress) {
        AndroidTvRemoteManager.connectToTv(tv.ipAddress)
    }

    // Initialize Remote Settings
    LaunchedEffect(Unit) {
        RemoteSettingsManager.init(context)
    }
    
    // Handle connection errors
    LaunchedEffect(connectionState) {
        when (val state = connectionState) {
            is AndroidTvRemoteManager.ConnectionState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    actionLabel = "Retry",
                    duration = SnackbarDuration.Long
                )
            }
            else -> {}
        }
    }
    
    // Track if we should show disconnection dialog
    var showDisconnectionDialog by remember { mutableStateOf(false) }
    
    // Track if we should show unpair confirmation dialog
    var showUnpairConfirmDialog by remember { mutableStateOf(false) }
    
    // Show disconnection dialog on error
    LaunchedEffect(connectionState) {
        showDisconnectionDialog = connectionState is AndroidTvRemoteManager.ConnectionState.Error &&
            (connectionState as? AndroidTvRemoteManager.ConnectionState.Error)?.message?.contains("Connection lost", ignoreCase = true) == true
    }
    
    // Disconnection Dialog
    if (showDisconnectionDialog) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = LocalRemoteColors.current.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = LocalRemoteColors.current.stateError,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Connection Lost",
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalRemoteColors.current.textPrimary
                )
            },
            text = {
                Text(
                    text = "The connection to ${tv.name} was interrupted.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalRemoteColors.current.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectionDialog = false
                        scope.launch { AndroidTvRemoteManager.reconnectToTv(tv.ipAddress) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalRemoteColors.current.primaryBrand,
                        contentColor = LocalRemoteColors.current.primaryContent
                    ),
                    shape = ButtonShape
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reconnect")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showDisconnectionDialog = false
                        AndroidTvRemoteManager.disconnectFromTv()
                        onBack()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalRemoteColors.current.textSecondary)
                ) {
                    Text("Go Back")
                }
            }
        )
    }
    
    // Keyboard Input Dialog
    if (showKeyboardDialog) {
        val focusRequester = remember { FocusRequester() }
        
        // Auto-focus the text field when dialog opens
        LaunchedEffect(showKeyboardDialog) {
            if (showKeyboardDialog) {
                kotlinx.coroutines.delay(100)
                focusRequester.requestFocus()
            }
        }
        
        AlertDialog(
            onDismissRequest = { 
                keyboardText = ""
                showKeyboardDialog = false 
            },
            containerColor = LocalRemoteColors.current.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = null,
                    tint = LocalRemoteColors.current.primaryBrand,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Type Text",
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalRemoteColors.current.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter text to send to the TV:",
                        color = LocalRemoteColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search...", color = LocalRemoteColors.current.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalRemoteColors.current.textPrimary,
                            unfocusedTextColor = LocalRemoteColors.current.textPrimary,
                            focusedBorderColor = LocalRemoteColors.current.primaryBrand,
                            unfocusedBorderColor = LocalRemoteColors.current.textTertiary,
                            cursorColor = LocalRemoteColors.current.primaryBrand,
                            focusedPlaceholderColor = LocalRemoteColors.current.textTertiary,
                            unfocusedPlaceholderColor = LocalRemoteColors.current.textTertiary
                        ),
                        singleLine = true,
                        shape = ButtonShape
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Quick action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Backspace button
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // Send KEYCODE_DEL (backspace)
                                    AndroidTvRemoteManager.sendKeyEvent(tv.ipAddress, 67)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalRemoteColors.current.textSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LocalRemoteColors.current.textTertiary.copy(alpha=0.5f)),
                            shape = ButtonShape
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Erase", style = MaterialTheme.typography.labelMedium)
                        }
                        
                        // Enter button
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // Send KEYCODE_ENTER
                                    AndroidTvRemoteManager.sendKeyEvent(tv.ipAddress, 66)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalRemoteColors.current.primaryBrand),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LocalRemoteColors.current.primaryBrand.copy(alpha=0.5f)),
                            shape = ButtonShape
                        ) {
                            Icon(Icons.Default.KeyboardReturn, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enter", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val textToSend = keyboardText
                        if (textToSend.isNotEmpty()) {
                            // Clear text but keep dialog open
                            keyboardText = ""
                            scope.launch {
                                // Add space at end if not already present
                                val finalText = if (textToSend.endsWith(" ")) {
                                    textToSend
                                } else {
                                    "$textToSend "
                                }
                                AndroidTvRemoteManager.sendText(tv.ipAddress, finalText)
                                // Re-focus the text field
                                focusRequester.requestFocus()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalRemoteColors.current.primaryBrand,
                        contentColor = LocalRemoteColors.current.primaryContent
                    ),
                    shape = ButtonShape
                ) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        keyboardText = ""
                        showKeyboardDialog = false 
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalRemoteColors.current.textSecondary)
                ) {
                    Text("Close")
                }
            }
        )
    }


    
    // Unpair Confirmation Dialog
    if (showUnpairConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirmDialog = false },
            containerColor = LocalRemoteColors.current.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = LocalRemoteColors.current.stateError,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Remove Device?",
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalRemoteColors.current.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to unpair \"${tv.name}\"? You will need to pair again to use the remote.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalRemoteColors.current.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairConfirmDialog = false
                        AndroidTvRemoteManager.disconnectFromTv()
                        AndroidTvRemoteManager.unpairTv(tv)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalRemoteColors.current.stateError,
                        contentColor = LocalRemoteColors.current.textPrimary
                    ),
                    shape = ButtonShape
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUnpairConfirmDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalRemoteColors.current.textSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    fun sendKey(keyCode: Int) {
        scope.launch {
            isConnecting = true
            AndroidTvRemoteManager.sendKeyEvent(tv.ipAddress, keyCode)
            isConnecting = false
        }
    }

    // Hardware Volume Control Logic
    val volumeFocusRequester = remember { FocusRequester() }

    // Request focus initially and when dialogs close to ensure volume keys work
    LaunchedEffect(Unit, showKeyboardDialog, showSettingsDialog, showUnpairConfirmDialog) {
        if (!showKeyboardDialog && !showSettingsDialog && !showUnpairConfirmDialog) {
            volumeFocusRequester.requestFocus()
        }
    }

    val useVolumeKeys by RemoteSettingsManager.useVolumeKeys.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(volumeFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (!useVolumeKeys) return@onKeyEvent false

                val keyCode = event.nativeKeyEvent.keyCode
                if (event.type == KeyEventType.KeyDown) {
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                            sendKey(AndroidTvRemoteManager.KEY_VOLUME_UP)
                            true // Consume event
                        }
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            sendKey(AndroidTvRemoteManager.KEY_VOLUME_DOWN)
                            true // Consume event
                        }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyUp) {
                    // Also consume KeyUp for volume keys to prevent system volume change
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_UP,
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = tv.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = LocalRemoteColors.current.textPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Connection status indicator
                                val (statusColor, statusText) = when (connectionState) {
                                    is AndroidTvRemoteManager.ConnectionState.Connected -> LocalRemoteColors.current.stateConnected to "Connected"
                                    is AndroidTvRemoteManager.ConnectionState.Connecting -> LocalRemoteColors.current.stateConnecting to "Connecting..."
                                    is AndroidTvRemoteManager.ConnectionState.Disconnected -> LocalRemoteColors.current.textTertiary to "Disconnected"
                                    is AndroidTvRemoteManager.ConnectionState.Error -> LocalRemoteColors.current.stateError to "Error"
                                }
                                
                                // Animated pulse for connecting state
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val pulseScale by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 1.3f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200), // Slower, calmer pulse
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse"
                                )
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp) // Smaller, more refined
                                            .scale(if (connectionState is AndroidTvRemoteManager.ConnectionState.Connecting) pulseScale else 1f)
                                            .background(statusColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$statusText ${tv.ipAddress}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalRemoteColors.current.textSecondary
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (!isShortcut) {
                        IconButton(onClick = {
                            AndroidTvRemoteManager.disconnectFromTv()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LocalRemoteColors.current.textSecondary)
                        }
                    }
                },
                actions = {
                    if (connectionState is AndroidTvRemoteManager.ConnectionState.Disconnected || 
                        connectionState is AndroidTvRemoteManager.ConnectionState.Error) {
                        IconButton(onClick = {
                            scope.launch { AndroidTvRemoteManager.reconnectToTv(tv.ipAddress) }
                        }) {
                            Icon(Icons.Default.Refresh, "Reconnect", tint = LocalRemoteColors.current.primaryBrand)
                        }
                    }
                    
                    if (!isShortcut) {
                        // Add Shortcut Button
                        IconButton(onClick = {
                            val shortcutId = "tv_${tv.ipAddress.replace(".", "_")}"
                            addHomeScreenShortcut(
                                context = context,
                                shortcutId = shortcutId,
                                shortcutName = tv.name,
                                deviceIp = tv.ipAddress,
                                deviceName = tv.name
                            )
                        }) {
                            // TODO: Add custom icon or use Material icon
                            Icon(
                                imageVector = Icons.Default.AddHome,
                                contentDescription = "Add to Home Screen",
                                tint = LocalRemoteColors.current.primaryBrand
                            )
                        }

                        IconButton(onClick = {
                            showUnpairConfirmDialog = true
                        }) {
                            Icon(Icons.Default.Delete, "Unpair", tint = LocalRemoteColors.current.stateError)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalRemoteColors.current.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = LocalRemoteColors.current.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection progress indicator
            if (isConnecting || connectionState is AndroidTvRemoteManager.ConnectionState.Connecting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = LocalRemoteColors.current.primaryBrand,
                    trackColor = LocalRemoteColors.current.surface
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }
            
            // Quick Actions Row (Power, Keyboard)
            SectionCard(title = null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickActionButton(
                        icon = Icons.Default.PowerSettingsNew,
                        label = "Power",
                        isDestructive = true,
                        onClick = { sendKey(AndroidTvRemoteManager.KEY_POWER) }
                    )
                    QuickActionButton(
                        icon = Icons.Default.Keyboard,
                        label = "Type",
                        onClick = { showKeyboardDialog = true }
                    )
                    QuickActionButton(
                        icon = Icons.Default.Settings,
                        label = "Settings",
                        onClick = { showSettingsDialog = true }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Navigation (DPad or Swipe)
            SectionCard(title = null) {
                val navMode by RemoteSettingsManager.navigationMode.collectAsState()
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (navMode == RemoteSettingsManager.NavigationMode.SWIPE_PAD) {
                         SwipePadController(
                             onUp = { sendKey(AndroidTvRemoteManager.KEY_DPAD_UP) },
                             onDown = { sendKey(AndroidTvRemoteManager.KEY_DPAD_DOWN) },
                             onLeft = { sendKey(AndroidTvRemoteManager.KEY_DPAD_LEFT) },
                             onRight = { sendKey(AndroidTvRemoteManager.KEY_DPAD_RIGHT) },
                             onCenter = { sendKey(AndroidTvRemoteManager.KEY_DPAD_CENTER) }
                         )
                    } else {
                        // D-Pad with full pie section clickable areas
                        DPadController(
                            onUp = { sendKey(AndroidTvRemoteManager.KEY_DPAD_UP) },
                            onDown = { sendKey(AndroidTvRemoteManager.KEY_DPAD_DOWN) },
                            onLeft = { sendKey(AndroidTvRemoteManager.KEY_DPAD_LEFT) },
                            onRight = { sendKey(AndroidTvRemoteManager.KEY_DPAD_RIGHT) },
                            onCenter = { sendKey(AndroidTvRemoteManager.KEY_DPAD_CENTER) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    // Back, Home
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            label = "Back",
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_BACK) }
                        )
                        NavButton(
                            icon = Icons.Default.Home,
                            label = "Home",
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_HOME) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Media Controls Section (Volume + Playback)
            SectionCard(title = "Media") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    // Volume Controls - Capsule Style (Mute circle + Volume capsule)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute Button (Round circle on left)
                        VolumeMuteButton(
                            isMuted = volumeInfo?.muted == true,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_MUTE) }
                        )
                        
                        // Volume Capsule (Down | Up)
                        VolumeCapsule(
                            onVolumeDown = { sendKey(AndroidTvRemoteManager.KEY_VOLUME_DOWN) },
                            onVolumeUp = { sendKey(AndroidTvRemoteManager.KEY_VOLUME_UP) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Playback Controls - Capsule Style Container
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(LocalRemoteColors.current.surfaceHighlight),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlaybackButton(
                            icon = Icons.Default.FastRewind,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_MEDIA_REWIND) }
                        )
                        PlaybackButton(
                            icon = Icons.Default.SkipPrevious,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_MEDIA_PREVIOUS) }
                        )
                        // Play/Pause button - Primary (larger and highlighted)
                        PlaybackButton(
                            icon = Icons.Default.PlayArrow,
                            isPrimary = true,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_PLAY_PAUSE) }
                        )
                        PlaybackButton(
                            icon = Icons.Default.SkipNext,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_MEDIA_NEXT) }
                        )
                        PlaybackButton(
                            icon = Icons.Default.FastForward,
                            onClick = { sendKey(AndroidTvRemoteManager.KEY_MEDIA_FAST_FORWARD) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Channel Controls
            SectionCard(title = "Channel") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ChannelButton(
                        text = "CH-",
                        onClick = { sendKey(AndroidTvRemoteManager.KEY_CHANNEL_DOWN) }
                    )
                    ChannelButton(
                        icon = Icons.Default.Tv,
                        onClick = { sendKey(AndroidTvRemoteManager.KEY_GUIDE) }
                    )
                    ChannelButton(
                        text = "CH+",
                        onClick = { sendKey(AndroidTvRemoteManager.KEY_CHANNEL_UP) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Number Pad
            SectionCard(title = "Number Pad") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NumberButton("1") { sendKey(AndroidTvRemoteManager.KEY_1) }
                        NumberButton("2") { sendKey(AndroidTvRemoteManager.KEY_2) }
                        NumberButton("3") { sendKey(AndroidTvRemoteManager.KEY_3) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NumberButton("4") { sendKey(AndroidTvRemoteManager.KEY_4) }
                        NumberButton("5") { sendKey(AndroidTvRemoteManager.KEY_5) }
                        NumberButton("6") { sendKey(AndroidTvRemoteManager.KEY_6) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NumberButton("7") { sendKey(AndroidTvRemoteManager.KEY_7) }
                        NumberButton("8") { sendKey(AndroidTvRemoteManager.KEY_8) }
                        NumberButton("9") { sendKey(AndroidTvRemoteManager.KEY_9) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        NumberButton("0") { sendKey(AndroidTvRemoteManager.KEY_0) }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        // Settings Screen Overlay with Slide Animation
        AnimatedVisibility(
            visible = showSettingsDialog,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            // Ensure it is on top of everything else in the Box
            modifier = Modifier.fillMaxSize().zIndex(100f) 
        ) {
            RemoteSettingsDialog(
                onDismiss = { showSettingsDialog = false }
            )
        }
    } // End of Scaffold content
    } // End of Box handling volume keys
    } // End of CompositionLocalProvider
}

// ============ COMPONENT FUNCTIONS ============

@Composable
private fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = LocalRemoteColors.current.textTertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier
                    .padding(start = 12.dp, bottom = 8.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LocalRemoteColors.current.surface),
            shape = CardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                content()
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val contentColor = if (isDestructive) LocalRemoteColors.current.stateError else LocalRemoteColors.current.textSecondary
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.repeatingClickable(
            interactionSource = interactionSource,
            onClick = onClick
        )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(if (isPressed) 0.95f else 1f)
                .then(
                    if (isPressed) Modifier.shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color.White.copy(alpha = 0.3f),
                        spotColor = Color.White.copy(alpha = 0.3f)
                    ) else Modifier
                )
                .background(
                    color = if (isPressed) LocalRemoteColors.current.interactionHighlight else Color.Transparent,
                    shape = CircleShape
                )
                .border(
                    width = if (isPressed) 1.dp else 0.dp,
                    color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isPressed) LocalRemoteColors.current.textPrimary else contentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalRemoteColors.current.textTertiary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun DPadButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .background(
                color = if (isPressed) LocalRemoteColors.current.surfaceHighlight.copy(alpha=1.5f) else LocalRemoteColors.current.surfaceHighlight,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalRemoteColors.current.textPrimary.copy(alpha = 0.8f),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun OkButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .background(
                color = if (isPressed) LocalRemoteColors.current.primaryBrand.copy(alpha=0.9f) else LocalRemoteColors.current.primaryBrand,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "OK",
            color = LocalRemoteColors.current.primaryContent,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
    }
}

// ============ JOYSTICK STYLE D-PAD COMPONENTS ============

@Composable
private fun JoystickChevron(
    direction: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val chevronChar = when (direction) {
        "up" -> "ï¸¿"
        "down" -> "ï¹€"
        "left" -> "â€¹"
        "right" -> "â€º"
        else -> "â€¢"
    }
    
    val rotation = when (direction) {
        "left", "right" -> 0f
        else -> 0f
    }
    
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = chevronChar,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            color = if (isPressed) LocalRemoteColors.current.textPrimary.copy(alpha = 0.9f) else LocalRemoteColors.current.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.scale(if (isPressed) 1.1f else 1f)
        )
    }
}

@Composable
private fun JoystickOkButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(90.dp)
            .scale(if (isPressed) 0.97f else 1f)
            .clip(CircleShape)
            .background(
                color = if (isPressed) LocalRemoteColors.current.interactionHighlight else LocalRemoteColors.current.surfaceHighlight
            )
            .border(
                width = 1.dp,
                color = LocalRemoteColors.current.divider,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Empty - just a touchable circle (like the reference image)
    }
}

// ============ D-PAD CONTROLLER WITH FULL PIE SECTIONS ============

@Composable
private fun DPadController(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit
) {
    val upInteractionSource = remember { MutableInteractionSource() }
    val downInteractionSource = remember { MutableInteractionSource() }
    val leftInteractionSource = remember { MutableInteractionSource() }
    val rightInteractionSource = remember { MutableInteractionSource() }
    val centerInteractionSource = remember { MutableInteractionSource() }
    
    val isUpPressed by upInteractionSource.collectIsPressedAsState()
    val isDownPressed by downInteractionSource.collectIsPressedAsState()
    val isLeftPressed by leftInteractionSource.collectIsPressedAsState()
    val isRightPressed by rightInteractionSource.collectIsPressedAsState()
    val isCenterPressed by centerInteractionSource.collectIsPressedAsState()
    
    val outerColor = LocalRemoteColors.current.surfaceHighlight
    val pressedColor = LocalRemoteColors.current.interactionHighlight
    val centerColor = LocalRemoteColors.current.interactionHighlight
    val lineColor = LocalRemoteColors.current.surface
    
    Box(
        modifier = Modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        // Draw D-Pad background and pressed states with Canvas
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val outerRadius = size.minDimension / 2
            val innerRadius = outerRadius * 0.375f
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            val strokeWidth = 2.5f.dp.toPx()
            val sqrt2 = 1.41421356f
            
            // Draw outer circle background
            drawCircle(
                color = outerColor,
                radius = outerRadius,
                center = center
            )
            
            // Draw pressed pie sections as arcs
            if (isUpPressed) {
                drawArc(
                    color = pressedColor,
                    startAngle = -135f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size
                )
            }
            if (isRightPressed) {
                drawArc(
                    color = pressedColor,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size
                )
            }
            if (isDownPressed) {
                drawArc(
                    color = pressedColor,
                    startAngle = 45f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size
                )
            }
            if (isLeftPressed) {
                drawArc(
                    color = pressedColor,
                    startAngle = 135f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size
                )
            }
            
            // Draw diagonal divider lines
            val tlbrOuterStart = androidx.compose.ui.geometry.Offset(
                center.x - (outerRadius / sqrt2), 
                center.y - (outerRadius / sqrt2)
            )
            val tlbrInnerEnd = androidx.compose.ui.geometry.Offset(
                center.x - (innerRadius / sqrt2) - 2.dp.toPx(), 
                center.y - (innerRadius / sqrt2) - 2.dp.toPx()
            )
            val brOuterStart = androidx.compose.ui.geometry.Offset(
                center.x + (outerRadius / sqrt2), 
                center.y + (outerRadius / sqrt2)
            )
            val brInnerEnd = androidx.compose.ui.geometry.Offset(
                center.x + (innerRadius / sqrt2) + 2.dp.toPx(), 
                center.y + (innerRadius / sqrt2) + 2.dp.toPx()
            )
            val trblOuterStart = androidx.compose.ui.geometry.Offset(
                center.x + (outerRadius / sqrt2), 
                center.y - (outerRadius / sqrt2)
            )
            val trblInnerEnd = androidx.compose.ui.geometry.Offset(
                center.x + (innerRadius / sqrt2) + 2.dp.toPx(), 
                center.y - (innerRadius / sqrt2) - 2.dp.toPx()
            )
            val blOuterStart = androidx.compose.ui.geometry.Offset(
                center.x - (outerRadius / sqrt2), 
                center.y + (outerRadius / sqrt2)
            )
            val blInnerEnd = androidx.compose.ui.geometry.Offset(
                center.x - (innerRadius / sqrt2) - 2.dp.toPx(), 
                center.y + (innerRadius / sqrt2) + 2.dp.toPx()
            )
            
            drawLine(color = lineColor, start = tlbrOuterStart, end = tlbrInnerEnd, strokeWidth = strokeWidth)
            drawLine(color = lineColor, start = brOuterStart, end = brInnerEnd, strokeWidth = strokeWidth)
            drawLine(color = lineColor, start = trblOuterStart, end = trblInnerEnd, strokeWidth = strokeWidth)
            drawLine(color = lineColor, start = blOuterStart, end = blInnerEnd, strokeWidth = strokeWidth)
            
            // Draw center circle
            drawCircle(
                color = if (isCenterPressed) pressedColor else centerColor,
                radius = innerRadius,
                center = center
            )
        }
        
        // Invisible clickable areas for each direction
        // UP section
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 120.dp, height = 75.dp)
                .repeatingClickable(
                    interactionSource = upInteractionSource,
                    onClick = onUp
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Up",
                tint = if (isUpPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(32.dp).offset(y = 8.dp)
            )
        }
        
        // DOWN section
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = 120.dp, height = 75.dp)
                .repeatingClickable(
                    interactionSource = downInteractionSource,
                    onClick = onDown
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Down",
                tint = if (isDownPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(32.dp).offset(y = (-8).dp)
            )
        }
        
        // LEFT section
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(width = 75.dp, height = 120.dp)
                .repeatingClickable(
                    interactionSource = leftInteractionSource,
                    onClick = onLeft
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Left",
                tint = if (isLeftPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(32.dp).offset(x = 8.dp)
            )
        }
        
        // RIGHT section
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(width = 75.dp, height = 120.dp)
                .repeatingClickable(
                    interactionSource = rightInteractionSource,
                    onClick = onRight
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Right",
                tint = if (isRightPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(32.dp).offset(x = (-8).dp)
            )
        }
        
        // CENTER button
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(90.dp)
                .scale(if (isCenterPressed) 0.95f else 1f)
                .clip(CircleShape)
                .repeatingClickable(
                    interactionSource = centerInteractionSource,
                    onClick = onCenter
                ),
            contentAlignment = Alignment.Center
        ) {
            // Empty - just the touchable center
        }
    }
}

// ============ D-PAD PIE SECTION STYLE COMPONENTS (Legacy) ============

@Composable
private fun DPadPieButton(
    direction: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val chevronIcon = when (direction) {
        "up" -> Icons.Default.KeyboardArrowUp
        "down" -> Icons.Default.KeyboardArrowDown
        "left" -> Icons.Default.KeyboardArrowLeft
        "right" -> Icons.Default.KeyboardArrowRight
        else -> Icons.Default.KeyboardArrowUp
    }
    
    Box(
        modifier = modifier
            .size(50.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .clip(CircleShape)
            .background(
                if (isPressed) LocalRemoteColors.current.interactionHighlight else Color.Transparent
            )
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = chevronIcon,
            contentDescription = direction,
            tint = if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun DPadCenterButton(
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(90.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .clip(CircleShape)
            .background(
                if (isPressed) LocalRemoteColors.current.interactionHighlight else Color.Transparent
            )
            .border(
                width = if (isPressed) 2.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.3f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Empty - just touchable with glow effect
    }
}

// ============ VOLUME CAPSULE STYLE COMPONENTS ============

@Composable
private fun VolumeMuteButton(
    isMuted: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .clip(CircleShape)
            .background(
                when {
                    isMuted -> LocalRemoteColors.current.stateError.copy(alpha = 0.3f)
                    isPressed -> LocalRemoteColors.current.interactionHighlight
                    else -> LocalRemoteColors.current.surfaceHighlight
                }
            )
            .border(
                width = if (isPressed || isMuted) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.2f) 
                       else if (isMuted) LocalRemoteColors.current.stateError.copy(alpha = 0.5f) 
                       else Color.Transparent,
                shape = CircleShape
            )
            .repeatingClickable(
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeMute,
            contentDescription = "Mute",
            tint = if (isMuted) LocalRemoteColors.current.stateError else if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun VolumeCapsule(
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downInteractionSource = remember { MutableInteractionSource() }
    val upInteractionSource = remember { MutableInteractionSource() }
    val isDownPressed by downInteractionSource.collectIsPressedAsState()
    val isUpPressed by upInteractionSource.collectIsPressedAsState()
    
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(LocalRemoteColors.current.surfaceHighlight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Volume Down (Left half)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (isDownPressed) LocalRemoteColors.current.interactionHighlight else Color.Transparent
                )
                .repeatingClickable(
                    interactionSource = downInteractionSource,
                    onClick = onVolumeDown
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeDown,
                contentDescription = "Volume Down",
                tint = if (isDownPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Divider line
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(LocalRemoteColors.current.surface)
        )
        
        // Volume Up (Right half)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (isUpPressed) LocalRemoteColors.current.interactionHighlight else Color.Transparent
                )
                .repeatingClickable(
                    interactionSource = upInteractionSource,
                    onClick = onVolumeUp
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "Volume Up",
                tint = if (isUpPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ============ PLAYBACK BUTTON COMPONENT ============

@Composable
private fun PlaybackButton(
    icon: ImageVector,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(if (isPrimary) 48.dp else 40.dp)
            .scale(if (isPressed) 0.9f else 1f)
            .clip(CircleShape)
            .background(
                when {
                    isPrimary -> if (isPressed) LocalRemoteColors.current.primaryBrand.copy(alpha = 0.8f) else LocalRemoteColors.current.primaryBrand
                    isPressed -> LocalRemoteColors.current.interactionHighlight
                    else -> Color.Transparent
                }
            )
            .repeatingClickable(
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isPrimary) LocalRemoteColors.current.primaryContent else if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
            modifier = Modifier.size(if (isPrimary) 28.dp else 22.dp)
        )
    }
}

// ============ VOLUME PILL STYLE COMPONENTS (Legacy) ============

@Composable
private fun VolumePillButton(
    icon: ImageVector,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .clip(CircleShape)
            .background(
                color = when {
                    isActive -> LocalRemoteColors.current.stateError.copy(alpha = 0.3f)
                    isPressed -> LocalRemoteColors.current.interactionHighlight
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) LocalRemoteColors.current.stateError else if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.repeatingClickable(
            interactionSource = interactionSource,
            onClick = onClick
        )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(if (isPressed) 0.95f else 1f)
                .then(
                    if (isPressed) Modifier.shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color.White.copy(alpha = 0.3f),
                        spotColor = Color.White.copy(alpha = 0.3f)
                    ) else Modifier
                )
                .background(
                    if (isPressed) LocalRemoteColors.current.interactionHighlight else LocalRemoteColors.current.surfaceHighlight, 
                    CircleShape
                )
                .border(
                    width = if (isPressed) 1.dp else 0.dp,
                    color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalRemoteColors.current.textTertiary
        )
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(if (isPrimary) 64.dp else 48.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .then(
                if (isPressed) Modifier.shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color.White.copy(alpha = 0.3f),
                    spotColor = Color.White.copy(alpha = 0.3f)
                ) else Modifier
            )
            .background(
                color = when {
                    isPressed -> LocalRemoteColors.current.interactionHighlight
                    isPrimary -> LocalRemoteColors.current.primaryBrand
                    else -> LocalRemoteColors.current.surfaceHighlight
                },
                shape = CircleShape
            )
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isPressed) LocalRemoteColors.current.textPrimary else if (isPrimary) LocalRemoteColors.current.primaryContent else LocalRemoteColors.current.textPrimary,
            modifier = Modifier.size(if (isPrimary) 32.dp else 24.dp)
        )
    }
}

@Composable
private fun VolumeButton(
    icon: ImageVector,
    isToggled: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .background(if (isToggled) LocalRemoteColors.current.stateError.copy(alpha=0.2f) else LocalRemoteColors.current.surfaceHighlight, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isToggled) LocalRemoteColors.current.stateError else LocalRemoteColors.current.textPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ChannelButton(
    text: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .then(
                if (isPressed) Modifier.shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color.White.copy(alpha = 0.3f),
                    spotColor = Color.White.copy(alpha = 0.3f)
                ) else Modifier
            )
            .background(
                if (isPressed) LocalRemoteColors.current.interactionHighlight else LocalRemoteColors.current.surfaceHighlight, 
                CircleShape
            )
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                shape = CircleShape
            )
            .repeatingClickable(
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun NumberButton(
    number: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .then(
                if (isPressed) Modifier.shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color.White.copy(alpha = 0.3f),
                    spotColor = Color.White.copy(alpha = 0.3f)
                ) else Modifier
            )
            .background(
                color = if (isPressed) LocalRemoteColors.current.interactionHighlight else LocalRemoteColors.current.surfaceHighlight,
                shape = CircleShape
            )
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = if (isPressed) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                shape = CircleShape
            )
            .repeatingClickable(
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number,
            color = if (isPressed) LocalRemoteColors.current.textPrimary else LocalRemoteColors.current.textSecondary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ SHORTCUT HELPER FUNCTIONS ============

private fun createShortcutIntent(
    context: Context,
    deviceIp: String,
    deviceName: String
): Intent {
    return Intent(context, com.example.tvcontrolhub.ShortcutInitActivity::class.java).apply {
        action = Intent.ACTION_VIEW

        // Data passed to init activity
        putExtra("EXTRA_DEVICE_IP", deviceIp)
        putExtra("EXTRA_DEVICE_NAME", deviceName)
        putExtra("EXTRA_OPEN_SOURCE", "shortcut")

        // Important flags
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}

private fun addHomeScreenShortcut(
    context: Context,
    shortcutId: String,
    shortcutName: String,
    deviceIp: String,
    deviceName: String
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)

        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            // Remove old shortcut if it exists (this is how we "edit")
            try {
                shortcutManager.removeDynamicShortcuts(listOf(shortcutId))
            } catch (e: Exception) {
                // Ignore errors removing old shortcuts
            }

            val shortcutIntent = createShortcutIntent(
                context,
                deviceIp,
                deviceName
            )

            val shortcut = ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(shortcutName)
                .setLongLabel("Open $deviceName")
                .setIcon(
                    Icon.createWithResource(
                        context,
                        android.R.drawable.ic_menu_preferences  // Using system icon for now
                    )
                )
                .setIntent(shortcutIntent)
                .build()

            shortcutManager.requestPinShortcut(shortcut, null)
        } else {
             Toast.makeText(context, "Launcher does not support pinning shortcuts", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Shortcuts need Android 8+", Toast.LENGTH_SHORT).show()
    }
}

fun Context.vibrateStrong(
    duration: Long = 40L,
    amplitude: Int = 255 // MAX strength
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val vibratorManager =
            getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator.vibrate(
            VibrationEffect.createOneShot(duration, amplitude)
        )
    } else {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(duration, amplitude)
            )
        } else {
            vibrator.vibrate(duration)
        }
    }
}

private fun Modifier.repeatingClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {

    val currentClickListener by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val vibrate by RemoteSettingsManager.vibrateOnClick.collectAsState()

    val initialDelay = 400L
    val minDelay = 50L
    val decayFactor = 0.8f

    this.pointerInput(interactionSource, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position)
            scope.launch { interactionSource.emit(press) }

            // ðŸ”¥ REAL PHONE-LIKE VIBRATION
            if (vibrate) {
                context.vibrateStrong(
                    duration = 35L,
                    amplitude = 255
                )
            }

            currentClickListener()

            val job = scope.launch {
                delay(initialDelay)
                var currentDelay = initialDelay
                while (isActive) {
                    // Vibrate on repeat (shorter feedback)
                    if (vibrate) {
                       context.vibrateStrong(
                           duration = 20L, // Shorter "machine gun" feel
                           amplitude = 200 // Slightly lower intensity
                       )
                    }
                    currentClickListener()
                    delay(currentDelay)
                    currentDelay =
                        (currentDelay * decayFactor).toLong().coerceAtLeast(minDelay)
                }
            }

            val up = waitForUpOrCancellation()
            job.cancel()

            scope.launch {
                interactionSource.emit(
                    if (up != null) PressInteraction.Release(press)
                    else PressInteraction.Cancel(press)
                )
            }
        }
    }
}


private enum class DpadDirection { UP, DOWN, LEFT, RIGHT }

@Composable
fun SwipePadController(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit
) {
    val context = LocalContext.current
    val vibrate by RemoteSettingsManager.vibrateOnClick.collectAsState()
    
    // Feedback function
    fun performHaptic(isRepeat: Boolean = false) {
        if (vibrate) {
            if (isRepeat) {
                // Lighter vibration for repeat
                context.vibrateStrong(duration = 10L, amplitude = 100)
            } else {
                // Stronger for initial press / tap
                context.vibrateStrong(duration = 30L, amplitude = 200)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(280.dp) // Same size as DPad
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF252525))
            .border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var direction: DpadDirection? = null
                        var repeatJob: Job? = null
                        var dragSum = Offset.Zero
                        // Threshold to lock direction (px)
                        val threshold = 18f 

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                
                                if (change == null || change.changedToUp()) {
                                    change?.consume()
                                    break
                                }

                                val delta = change.positionChange()
                                change.consume()
                                
                                // Logic: If not yet locked, accumulate drag. 
                                // Once threshold crossed, LOCK direction and start repeating.
                                if (direction == null) {
                                    dragSum += delta
                                    val dist = dragSum.getDistance()
                                    
                                    if (dist > threshold) {
                                        // Lock direction ONCE
                                        direction = if (abs(dragSum.x) > abs(dragSum.y)) {
                                            if (dragSum.x > 0) DpadDirection.RIGHT else DpadDirection.LEFT
                                        } else {
                                            if (dragSum.y > 0) DpadDirection.DOWN else DpadDirection.UP
                                        }
                                        
                                        // Start Repeating Job
                                        repeatJob = launch {
                                            val action = when(direction) {
                                                DpadDirection.UP -> onUp
                                                DpadDirection.DOWN -> onDown
                                                DpadDirection.LEFT -> onLeft
                                                DpadDirection.RIGHT -> onRight
                                                null -> {}
                                            }
                                            
                                            // 1. Immediate execution
                                            performHaptic(isRepeat = false)
                                            (action as? () -> Unit)?.invoke()
                                            
                                            // 2. Initial Delay
                                            delay(300)
                                            
                                            // 3. Repeat Loop
                                            while (isActive) {
                                                performHaptic(isRepeat = true)
                                                (action as? () -> Unit)?.invoke()
                                                delay(90) // Fast repeat rate
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            repeatJob?.cancel()
                        }

                        // Tap Handling: If we never locked a direction, it's a Tap -> Enter
                        if (direction == null) {
                            performHaptic(isRepeat = false)
                            onCenter()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = Color(0xFF6E6E73),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "SWIPE TO NAVIGATE\nTAP TO SELECT",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6E6E73),
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )
        }
    }
}




