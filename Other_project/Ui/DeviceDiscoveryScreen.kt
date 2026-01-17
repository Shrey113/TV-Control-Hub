package com.example.myapplication.ui.screens

import android.content.Context
import android.util.Log 
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.myapplication.manager.AndroidTvRemoteManager
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "DeviceDiscoveryScreen"

/**
 * Data class for unique Cast device
 */
data class UniqueCastDevice(
    val deviceId: String,
    val name: String,
    val routeInfo: MediaRouter.RouteInfo,
    val castDevice: CastDevice
)

/**
 * Device Discovery Screen - Unified TV Remote & Cast
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(
    onBack: () -> Unit,
    onRemoteDeviceSelected: (AndroidTvRemoteManager.AndroidTv, isPaired: Boolean) -> Unit,
    onCastDeviceSelected: (MediaRouter.RouteInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Remote TV states
    val isRemoteScanning by AndroidTvRemoteManager.isScanning.collectAsState()
    val remoteTvs by AndroidTvRemoteManager.discoveredTvs.collectAsState()

    // Cast device states
    val castDevices by produceState<List<UniqueCastDevice>>(initialValue = emptyList()) {
        getUniqueCastDevices(context).collect { devices ->
            value = devices
        }
    }
    var connectingDeviceId by remember { mutableStateOf<String?>(null) }

    // MediaRouter for selecting Cast devices
    val mediaRouter = remember { MediaRouter.getInstance(context) }

    // CastContext for session management
    val castContext = remember {
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CastContext", e)
            null
        }
    }

    // Track the pending route to navigate after connection
    var pendingCastRoute by remember { mutableStateOf<MediaRouter.RouteInfo?>(null) }

    // Session listener to detect when connection is established
    DisposableEffect(castContext) {
        val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
            override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
                pendingCastRoute?.let { route ->
                    onCastDeviceSelected(route)
                    pendingCastRoute = null
                    connectingDeviceId = null
                }
            }
            override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                connectingDeviceId = null
                pendingCastRoute = null
            }
            override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
                pendingCastRoute?.let { route ->
                    onCastDeviceSelected(route)
                    pendingCastRoute = null
                    connectingDeviceId = null
                }
            }
            override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                connectingDeviceId = null
                pendingCastRoute = null
            }
            override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
        }

        castContext?.sessionManager?.addSessionManagerListener(
            listener,
            com.google.android.gms.cast.framework.CastSession::class.java
        )

        onDispose {
            castContext?.sessionManager?.removeSessionManagerListener(
                listener,
                com.google.android.gms.cast.framework.CastSession::class.java
            )
        }
    }

    // Combined scanning state
    val isScanning = isRemoteScanning
    val scope = rememberCoroutineScope()

    // Start discovery on launch
    LaunchedEffect(Unit) {
        AndroidTvRemoteManager.initialize(context)
        AndroidTvRemoteManager.startDiscovery(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            AndroidTvRemoteManager.stopDiscovery()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Connected TVs",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(
                            onClick = {
                                AndroidTvRemoteManager.startDiscovery(context)
                            }
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
        ) {
            // ===== TV REMOTE SECTION =====
            item {
                DiscoverySectionHeader(title = "TV Remote")
            }

            if (remoteTvs.isEmpty()) {
                item {
                    if (isScanning) {
                        LoadingStateCard(message = "Searching for Android TVs...")
                    } else {
                        EmptyStateActionCard(
                            title = "No Android TVs found",
                            subtitle = "Ensure your TV is on and connected to Wi-Fi"
                        )
                    }
                }
            } else {
                items(remoteTvs) { tv ->
                    RemoteDeviceCardRedesigned(
                        tv = tv,
                        onClick = { onRemoteDeviceSelected(tv, tv.isPaired) }
                    )
                }
            }

            // ===== CAST SECTION =====
            item {
                Spacer(modifier = Modifier.height(16.dp))
                DiscoverySectionHeader(title = "Cast Media")
            }

            if (castDevices.isEmpty()) {
                item {
                    if (isScanning) {
                        LoadingStateCard(message = "Searching for Cast devices...")
                    } else {
                        EmptyStateActionCard(
                            title = "Not seeing your device?",
                            subtitle = "Ensure your TV is turned on and connected to the same Wi-Fi network."
                        )
                    }
                }
            } else {
                items(castDevices, key = { it.deviceId }) { device ->
                    CastDeviceCardRedesigned(
                        device = device,
                        isConnecting = connectingDeviceId == device.deviceId,
                        onClick = {
                            connectingDeviceId = device.deviceId
                            pendingCastRoute = device.routeInfo

                            val currentSession = castContext?.sessionManager?.currentCastSession
                            if (currentSession != null && currentSession.isConnected) {
                                connectingDeviceId = null
                                pendingCastRoute = null
                                onCastDeviceSelected(device.routeInfo)
                                return@CastDeviceCardRedesigned
                            }

                            mediaRouter.selectRoute(device.routeInfo)

                            scope.launch {
                                delay(2000)
                                val session = castContext?.sessionManager?.currentCastSession
                                if (session != null && session.isConnected && connectingDeviceId == device.deviceId) {
                                    connectingDeviceId = null
                                    pendingCastRoute = null
                                    onCastDeviceSelected(device.routeInfo)
                                    return@launch
                                }
                                delay(5000)
                                if (connectingDeviceId == device.deviceId) {
                                    connectingDeviceId = null
                                    pendingCastRoute = null
                                    onCastDeviceSelected(device.routeInfo)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// UI COMPONENTS
// -----------------------------------------------------------------------------

@Composable
private fun DiscoverySectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
            .fillMaxWidth()
    )
}

@Composable
private fun RemoteDeviceCardRedesigned(
    tv: AndroidTvRemoteManager.AndroidTv,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tv.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (tv.isPaired) "Ready to control" else tv.ipAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (tv.isPaired) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Paired",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastDeviceCardRedesigned(
    device: UniqueCastDevice,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Cast,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isConnecting) "Connecting..." else "Tap to cast photos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingStateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyStateActionCard(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// HELPER FUNCTIONS
// -----------------------------------------------------------------------------

private fun getUniqueCastDevices(context: Context): Flow<List<UniqueCastDevice>> = callbackFlow {
    val mediaRouter = MediaRouter.getInstance(context)

    val selector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
        )
        .build()

    val deviceMap = mutableMapOf<String, UniqueCastDevice>()

    fun processRoutes() {
        deviceMap.clear()

        mediaRouter.routes.forEach { route ->
            if (!route.isEnabled) return@forEach
            if (route.isDefault) return@forEach
            if (!route.matchesSelector(selector)) return@forEach

            val castDevice = try {
                CastDevice.getFromBundle(route.extras)
            } catch (e: Exception) {
                null
            }

            if (castDevice != null) {
                val deviceId = castDevice.deviceId
                val existingDevice = deviceMap[deviceId]

                val shouldUpdate = when {
                    existingDevice == null -> true
                    route.isSelected -> true
                    !existingDevice.routeInfo.isSelected && route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED -> true
                    else -> false
                }

                if (shouldUpdate) {
                    deviceMap[deviceId] = UniqueCastDevice(
                        deviceId = deviceId,
                        name = castDevice.friendlyName ?: route.name,
                        routeInfo = route,
                        castDevice = castDevice
                    )
                }
            }
        }

        trySend(deviceMap.values.toList())
    }

    val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = processRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = processRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = processRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = processRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = processRoutes()
    }

    mediaRouter.addCallback(
        selector,
        callback,
        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
    )

    processRoutes()

    awaitClose {
        mediaRouter.removeCallback(callback)
    }
}
