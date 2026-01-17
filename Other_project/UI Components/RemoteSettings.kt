package com.example.myapplication.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import android.content.Context
import android.content.SharedPreferences

/**
 * Remote Settings Manager
 * Manages the settings for the remote control.
 */
object RemoteSettingsManager {
    private const val PREFS_NAME = "remote_settings"
    private const val KEY_VIBRATE = "vibrate_on_click"
    private const val KEY_NAV_MODE = "nav_mode"
    private const val KEY_APP_THEME = "app_theme"
    
    enum class NavigationMode {
        D_PAD,
        SWIPE_PAD
    }

    enum class AppTheme {
        FOLLOW_SYSTEM,
        DARK,
        LIGHT
    }
    
    // Vibrate on Click setting
    private val _vibrateOnClick = MutableStateFlow(true)
    val vibrateOnClick: StateFlow<Boolean> = _vibrateOnClick.asStateFlow()

    // Navigation Mode setting
    private val _navigationMode = MutableStateFlow(NavigationMode.D_PAD)
    val navigationMode: StateFlow<NavigationMode> = _navigationMode.asStateFlow()

    // App Theme setting
    private val _appTheme = MutableStateFlow(AppTheme.FOLLOW_SYSTEM)
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Load saved value, default to TRUE (ON)
            _vibrateOnClick.value = prefs?.getBoolean(KEY_VIBRATE, true) ?: true
            
            // Load Nav Mode, default to D_PAD (Button D-Pad)
            val modeOrdinal = prefs?.getInt(KEY_NAV_MODE, NavigationMode.D_PAD.ordinal) ?: NavigationMode.D_PAD.ordinal
            _navigationMode.value = NavigationMode.values().getOrElse(modeOrdinal) { NavigationMode.D_PAD }

            // Load App Theme, default to FOLLOW_SYSTEM
            val themeOrdinal = prefs?.getInt(KEY_APP_THEME, AppTheme.FOLLOW_SYSTEM.ordinal) ?: AppTheme.FOLLOW_SYSTEM.ordinal
            _appTheme.value = AppTheme.values().getOrElse(themeOrdinal) { AppTheme.FOLLOW_SYSTEM }
            
            // Load Volume Keys setting, default to TRUE (ON)
            _useVolumeKeys.value = prefs?.getBoolean(KEY_VOLUME_KEYS, true) ?: true
        }
    }

    fun setVibration(enabled: Boolean) {
        _vibrateOnClick.value = enabled
        prefs?.edit()?.putBoolean(KEY_VIBRATE, enabled)?.apply()
    }
    
    fun setNavigationMode(mode: NavigationMode) {
        _navigationMode.value = mode
        prefs?.edit()?.putInt(KEY_NAV_MODE, mode.ordinal)?.apply()
    }

    fun setAppTheme(theme: AppTheme) {
        _appTheme.value = theme
        prefs?.edit()?.putInt(KEY_APP_THEME, theme.ordinal)?.apply()
    }

    // Volume Keys setting
    private const val KEY_VOLUME_KEYS = "use_volume_keys"
    private val _useVolumeKeys = MutableStateFlow(true)
    val useVolumeKeys: StateFlow<Boolean> = _useVolumeKeys.asStateFlow()

    fun setVolumeKeysEnabled(enabled: Boolean) {
        _useVolumeKeys.value = enabled
        prefs?.edit()?.putBoolean(KEY_VOLUME_KEYS, enabled)?.apply()
    }
}

// --- Theme Definitions ---

data class RemoteThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceHighlight: Color, // Card Color
    val primaryBrand: Color,
    val primaryContent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val stateConnected: Color,
    val stateConnecting: Color,
    val stateError: Color,
    val divider: Color,
    val interactionHighlight: Color
)

val DarkRemoteColors = RemoteThemeColors(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF1C1C1E),
    surfaceHighlight = Color(0xFF2C2C2E),
    primaryBrand = Color(0xFFD0BCFF),
    primaryContent = Color(0xFF381E72),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFAAAAAA),
    textTertiary = Color(0xFF6E6E73),
    stateConnected = Color(0xFF81C784),
    stateConnecting = Color(0xFFFFB74D),
    stateError = Color(0xFFE57373),
    divider = Color(0xFF2C2C2E),
    interactionHighlight = Color(0xFF4A4A4E)
)

val LightRemoteColors = RemoteThemeColors(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceHighlight = Color(0xFFE5E5EA), // Cards
    primaryBrand = Color(0xFF5E4994), // Slightly darker for better visibility on white
    primaryContent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF3C3C43),
    textTertiary = Color(0xFF8E8E93),
    stateConnected = Color(0xFF34C759),
    stateConnecting = Color(0xFFFF9500),
    stateError = Color(0xFFFF3B30),
    divider = Color(0xFFC6C6C8),
    interactionHighlight = Color(0xFFD1D1D6)
)

@Composable
fun getRemoteColors(): RemoteThemeColors {
    val theme by RemoteSettingsManager.appTheme.collectAsState()
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    
    return when (theme) {
        RemoteSettingsManager.AppTheme.DARK -> DarkRemoteColors
        RemoteSettingsManager.AppTheme.LIGHT -> LightRemoteColors
        RemoteSettingsManager.AppTheme.FOLLOW_SYSTEM -> if (isSystemDark) DarkRemoteColors else LightRemoteColors
    }
}

val LocalRemoteColors = staticCompositionLocalOf { DarkRemoteColors }

/**
 * Remote Settings Dialog (Now as a Full Screen Overlay)
 * Displays settings control in a full screen format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSettingsDialog(
    onDismiss: () -> Unit
) {
    // Initialize settings if needed
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        RemoteSettingsManager.init(context)
    }

    // Handle system back press
    BackHandler(onBack = onDismiss)

    val vibrate by RemoteSettingsManager.vibrateOnClick.collectAsState()
    val useVolumeKeys by RemoteSettingsManager.useVolumeKeys.collectAsState()
    val navMode by RemoteSettingsManager.navigationMode.collectAsState()
    val appTheme by RemoteSettingsManager.appTheme.collectAsState()
    
    val colors = LocalRemoteColors.current

    // Full Screen Surface with Theme Background
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // --- Top Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.textPrimary
                    )
                }
                Text(
                    text = "Remote Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // --- Scrollable Content ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // --- App Theme Selector ---
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Remote Theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    
                    var expanded by remember { mutableStateOf(false) }
                    
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = when(appTheme) {
                                RemoteSettingsManager.AppTheme.FOLLOW_SYSTEM -> "System Default"
                                RemoteSettingsManager.AppTheme.DARK -> "Dark Mode"
                                RemoteSettingsManager.AppTheme.LIGHT -> "Light Mode"
                            },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedContainerColor = colors.surfaceHighlight,
                                unfocusedContainerColor = colors.surfaceHighlight,
                                focusedBorderColor = colors.primaryBrand,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(colors.surfaceHighlight)
                        ) {
                            DropdownMenuItem(
                                text = { Text("System Default", color = colors.textPrimary) },
                                onClick = {
                                    RemoteSettingsManager.setAppTheme(RemoteSettingsManager.AppTheme.FOLLOW_SYSTEM)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("Dark Mode", color = colors.textPrimary) },
                                onClick = {
                                    RemoteSettingsManager.setAppTheme(RemoteSettingsManager.AppTheme.DARK)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("Light Mode", color = colors.textPrimary) },
                                onClick = {
                                    RemoteSettingsManager.setAppTheme(RemoteSettingsManager.AppTheme.LIGHT)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
                

                // --- Toggle Cards Section ---
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Vibrate Toggle Card
                    SettingsToggleCard(
                        title = "Vibrate on click",
                        subtitle = "Haptic feedback when pressing buttons\n(All Buttons)",
                        isChecked = vibrate,
                        onCheckedChange = { RemoteSettingsManager.setVibration(it) }
                    )

                    // Volume Keys Toggle Card
                    SettingsToggleCard(
                        title = "Use Volume Buttons",
                        subtitle = "Control TV volume with phone buttons\n(When App is Open)",
                        isChecked = useVolumeKeys,
                        onCheckedChange = { RemoteSettingsManager.setVolumeKeysEnabled(it) }
                    )
                }

                // --- Navigation Style Visual Selector ---
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Navigation Style",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Button D-Pad Card
                        NavigationModeCard(
                            title = "Button D-Pad",
                            description = "Physical remote feel",
                            icon = Icons.Default.Games,
                            isSelected = navMode == RemoteSettingsManager.NavigationMode.D_PAD,
                            onClick = { RemoteSettingsManager.setNavigationMode(RemoteSettingsManager.NavigationMode.D_PAD) },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // Swipe Pad Card
                        NavigationModeCard(
                            title = "Swipe Pad",
                            description = "Swipe to move, tap to select",
                            icon = Icons.Default.TouchApp,
                            isSelected = navMode == RemoteSettingsManager.NavigationMode.SWIPE_PAD,
                            onClick = { RemoteSettingsManager.setNavigationMode(RemoteSettingsManager.NavigationMode.SWIPE_PAD) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalRemoteColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!isChecked) },
        colors = CardDefaults.cardColors(containerColor = colors.surfaceHighlight),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.primaryBrand,
                    checkedTrackColor = colors.primaryBrand.copy(alpha = 0.5f),
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.interactionHighlight
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
    }
}

@Composable
private fun NavigationModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalRemoteColors.current
    val borderColor = if (isSelected) colors.primaryBrand else Color.Transparent
    val backgroundColor = if (isSelected) colors.primaryBrand.copy(alpha = 0.12f) else colors.surfaceHighlight
    
    Card(
        modifier = modifier
            .height(130.dp) // Fixed height for alignment
            .clip(RoundedCornerShape(16.dp))
            .border(width = if (isSelected) 2.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) colors.primaryBrand else colors.textSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) colors.textPrimary else colors.textSecondary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}
