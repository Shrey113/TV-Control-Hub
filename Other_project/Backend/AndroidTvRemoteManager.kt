package com.example.myapplication.manager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.*
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*


/**
 * AndroidTvRemoteManager - Handles Android TV Remote Protocol v2
 * 
 * This implements the same protocol used by Google TV Remote app:
 * - Service discovery via mDNS (_androidtvremote2._tcp)
 * - TLS pairing with certificate exchange
 * - Remote control commands
 *
 * Protocol details:
 * - Discovery: mDNS service type "_androidtvremote2._tcp"
 * - Pairing Port: 6467 (TLS)
 * - Command Port: 6466 (TLS)
 * - Pairing Code: 6-character hexadecimal
 */
object AndroidTvRemoteManager {
    private const val TAG = "AndroidTvRemoteManager"
    
    // mDNS service type for Android TV Remote v2
    private const val SERVICE_TYPE = "_androidtvremote2._tcp."
    private const val DISCOVERY_TIMEOUT_MS = 20000L
    
    // Protocol ports
    private const val PAIRING_PORT = 6467
    private const val COMMAND_PORT = 6466
    
    // Android TV Remote Key Codes - Navigation
    const val KEY_DPAD_UP = 19
    const val KEY_DPAD_DOWN = 20
    const val KEY_DPAD_LEFT = 21
    const val KEY_DPAD_RIGHT = 22
    const val KEY_DPAD_CENTER = 23  // OK/Select
    const val KEY_BACK = 4
    const val KEY_HOME = 3
    const val KEY_RECENT_APPS = 187  // KEYCODE_APP_SWITCH
    
    // Volume and Power
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25
    const val KEY_MUTE = 164
    const val KEY_POWER = 26
    
    // Media Controls
    const val KEY_PLAY_PAUSE = 85
    const val KEY_MEDIA_STOP = 86
    const val KEY_MEDIA_NEXT = 87
    const val KEY_MEDIA_PREVIOUS = 88
    const val KEY_MEDIA_REWIND = 89
    const val KEY_MEDIA_FAST_FORWARD = 90
    
    // TV Controls
    const val KEY_SETTINGS = 176
    const val KEY_TV_INPUT = 178
    const val KEY_MENU = 82
    const val KEY_CHANNEL_UP = 166
    const val KEY_CHANNEL_DOWN = 167
    const val KEY_GUIDE = 172
    const val KEY_INFO = 165
    
    // Number Pad (0-9)
    const val KEY_0 = 7
    const val KEY_1 = 8
    const val KEY_2 = 9
    const val KEY_3 = 10
    const val KEY_4 = 11
    const val KEY_5 = 12
    const val KEY_6 = 13
    const val KEY_7 = 14
    const val KEY_8 = 15
    const val KEY_9 = 16
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isResolvingService = false
    private val pendingServices = mutableListOf<NsdServiceInfo>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryJob: Job? = null
    
    // Cached SSL Context to ensure TLS identity reuse
    private var cachedSslContext: SSLContext? = null
    
    // Application context
    private var appContext: Context? = null
    
    // Persistent command connection
    private var commandSocket: SSLSocket? = null
    private var commandOutput: DataOutputStream? = null
    private var connectedTvIp: String? = null
    private val socketLock = Object()
    
    // Certificate storage
    private var clientKeyPair: KeyPair? = null
    private var clientCertificate: X509Certificate? = null
    
    // State flows for UI
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _discoveredTvs = MutableStateFlow<List<AndroidTv>>(emptyList())
    val discoveredTvs: StateFlow<List<AndroidTv>> = _discoveredTvs.asStateFlow()
    
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()
    
    // Command connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Volume info from TV
    data class VolumeInfo(
        val level: Int,
        val max: Int,
        val muted: Boolean
    )
    
    private val _volumeInfo = MutableStateFlow<VolumeInfo?>(null)
    val volumeInfo: StateFlow<VolumeInfo?> = _volumeInfo.asStateFlow()
    
    // Current app info from TV (when IME is active)
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()
    
    // TV power state
    private val _isTvOn = MutableStateFlow<Boolean?>(null)
    val isTvOn: StateFlow<Boolean?> = _isTvOn.asStateFlow()
    
    // IME counters for text input (from remote_ime_batch_edit messages)
    private var imeCounter: Int = 0
    private var imeFieldCounter: Int = 0
    
    /**
     * Data class representing a discovered Android TV
     */
    data class AndroidTv(
        val name: String,
        val ipAddress: String,
        val pairingPort: Int = PAIRING_PORT,
        val commandPort: Int = COMMAND_PORT,
        val modelName: String = "",
        val manufacturer: String = "",
        val isPaired: Boolean = false
    )
    
    /**
     * Pairing state machine
     */
    sealed class PairingState {
        object Idle : PairingState()
        object Connecting : PairingState()
        object WaitingForCode : PairingState()
        // WaitingForTvPairingMode removed
        object SubmittingCode : PairingState()
        data class Success(val tv: AndroidTv) : PairingState()
        data class Error(val message: String) : PairingState()
    }
    
    /**
     * Connection state for command socket
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val tvIp: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    /**
     * Pairing session holder
     * Now includes serverCertificate for computing the pairing secret hash
     */
    class PairingSession(
        val tv: AndroidTv,
        val socket: SSLSocket,
        val inputStream: DataInputStream,
        val outputStream: DataOutputStream,
        val serverCertificate: X509Certificate? = null
    ) {
        fun close() {
            try {
                // Flush any pending data before closing
                try { outputStream.flush() } catch (_: Exception) {}
                try { inputStream.close() } catch (_: Exception) {}
                try { outputStream.close() } catch (_: Exception) {}
                // Properly close SSL session
                if (!socket.isClosed) {
                    socket.close()
                }
            } catch (e: Exception) {
                // Ignore close errors - socket may already be closed by TV
                Log.d(TAG, "Session close: ${e.message}")
            }
        }
    }
    
    private var currentSession: PairingSession? = null
    
    // Paired TVs storage
    private const val PREFS_NAME = "android_tv_remote_prefs"
    private const val PREFS_PAIRED_TVS = "paired_tvs"
    
    private val _pairedTvs = MutableStateFlow<Set<String>>(emptySet())
    val pairedTvs: StateFlow<Set<String>> = _pairedTvs.asStateFlow()
    
    /**
     * Initialize the manager with context
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        loadOrGenerateClientCertificate()
        loadPairedTvs()
    }
    
    /**
     * Load paired TVs from SharedPreferences
     */
    private fun loadPairedTvs() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val paired = prefs?.getStringSet(PREFS_PAIRED_TVS, emptySet()) ?: emptySet()
        _pairedTvs.value = paired
        Log.d(TAG, "Loaded ${paired.size} paired TVs: $paired")
    }
    
    /**
     * Save a TV as paired
     */
    fun savePairedTv(tv: AndroidTv) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _pairedTvs.value.toMutableSet()
        current.add(tv.ipAddress)
        prefs?.edit()?.putStringSet(PREFS_PAIRED_TVS, current)?.apply()
        _pairedTvs.value = current
        Log.d(TAG, "Saved paired TV: ${tv.name} (${tv.ipAddress})")
    }
    
    /**
     * Unpair a TV (remove from saved list)
     */
    fun unpairTv(tv: AndroidTv) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _pairedTvs.value.toMutableSet()
        current.remove(tv.ipAddress)
        prefs?.edit()?.putStringSet(PREFS_PAIRED_TVS, current)?.apply()
        _pairedTvs.value = current
        Log.d(TAG, "Unpaired TV: ${tv.name} (${tv.ipAddress})")
    }
    
    /**
     * Check if a TV is already paired
     */
    fun isTvPaired(tv: AndroidTv): Boolean {
        return _pairedTvs.value.contains(tv.ipAddress)
    }
    
    /**
     * Try to connect to command channel (auto-reconnect)
     * Returns true if connection succeeds (TV is paired and online)
     */
    suspend fun tryAutoConnect(tv: AndroidTv): Boolean {
        if (!isTvPaired(tv)) {
            Log.d(TAG, "TV not paired, need to pair first")
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Trying auto-connect to ${tv.name} on port $COMMAND_PORT")
                val sslContext = getOrCreateSslContext()
                val socket = sslContext.socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(tv.ipAddress, COMMAND_PORT), 5000)
                socket.soTimeout = 5000
                socket.startHandshake()
                socket.close()
                Log.d(TAG, "Auto-connect successful - TV is paired and online!")
                true
            } catch (e: Exception) {
                Log.d(TAG, "Auto-connect failed: ${e.message}")
                false
            }
        }
    }
    
    // Reader job for handling incoming messages (pings)
    private var readerJob: kotlinx.coroutines.Job? = null
    
    /**
     * Connect to command channel (port 6466)
     * Uses same TLS certificate from pairing
     * This creates a persistent connection that stays open for multiple commands
     */
    suspend fun connectToTv(tvIp: String): Boolean {
        // Check if already connected to this TV
        synchronized(socketLock) {
            if (connectedTvIp == tvIp && commandSocket?.isConnected == true && commandSocket?.isClosed == false) {
                Log.d(TAG, "Already connected to $tvIp")
                return true
            }
        }
        
        // Check if TV is paired first
        if (!_pairedTvs.value.contains(tvIp)) {
            Log.w(TAG, "TV $tvIp is not paired. Please pair first.")
            _connectionState.value = ConnectionState.Error("TV not paired. Please pair first.")
            return false
        }
        
        _connectionState.value = ConnectionState.Connecting
        
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            val maxRetries = 3
            val baseDelayMs = 1000L
            
            repeat(maxRetries) { attempt ->
                try {
                    Log.d(TAG, "Command socket connection attempt ${attempt + 1}/$maxRetries")
                    val sslContext = getOrCreateSslContext()
                    val socket = sslContext.socketFactory.createSocket() as SSLSocket
                    socket.connect(InetSocketAddress(tvIp, COMMAND_PORT), 5000)
                    socket.soTimeout = 0 // No timeout for persistent connection
                    socket.keepAlive = true
                    socket.startHandshake()
                    
                    synchronized(socketLock) {
                        // Close existing connection if any
                        disconnectFromTvInternal()
                        
                        commandSocket = socket
                        commandOutput = DataOutputStream(BufferedOutputStream(socket.outputStream))
                        connectedTvIp = tvIp
                    }
                    
                    // Start the reader/ping responder
                    startPingResponder(socket)
                    
                    Log.d(TAG, "Command socket connected successfully")
                    _connectionState.value = ConnectionState.Connected(tvIp)
                    return@withContext true
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Command socket attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                    
                    // Check if this is a certificate error (TV needs re-pairing)
                    if (e.message?.contains("CERTIFICATE_UNKNOWN") == true || 
                        e.message?.contains("certificate") == true) {
                        Log.w(TAG, "Certificate error - TV may need re-pairing")
                        // Mark as unpaired since the certificate isn't recognized
                        unpairTvByIp(tvIp)
                        _connectionState.value = ConnectionState.Error("Certificate rejected. Please re-pair with the TV.")
                        return@withContext false
                    }
                    
                    if (attempt < maxRetries - 1) {
                        delay(baseDelayMs * (attempt + 1))
                    }
                }
            }
            
            Log.e(TAG, "Failed to connect command socket after $maxRetries attempts", lastException)
            _connectionState.value = ConnectionState.Error("Connection failed: ${lastException?.message ?: "Unknown error"}")
            false
        }
    }

    /**
     * Force a reconnection to the TV
     * Disconnects any existing session first to ensure a clean state
     */
    suspend fun reconnectToTv(tvIp: String): Boolean {
        Log.d(TAG, "Force reconnecting to $tvIp")
        disconnectFromTv()
        delay(500) // Brief delay to ensure socket cleanup
        return connectToTv(tvIp)
    }
    
    /**
     * Start a background reader that handles incoming messages from the TV.
     * 
     * The TV sends:
     * - Field 1: remote_configure (device info) - we must respond with our config
     * - Field 2: remote_set_active - we must respond with active status  
     * - Field 8: remote_ping_request - we must respond with field 9 ping_response
     * - Field 40: remote_start - TV power state
     * - Field 50: remote_set_volume_level - volume info
     * 
     * If we don't respond, the TV closes the connection!
     */
    private fun startPingResponder(socket: SSLSocket) {
        // Cancel any existing reader job
        readerJob?.cancel()
        
        readerJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                Log.d(TAG, "Message handler started")
                
                while (isActive && !socket.isClosed && socket.isConnected) {
                    try {
                        // Read message length (varint)
                        val msgLen = readVarint(input)
                        if (msgLen <= 0 || msgLen > 10000) {
                            Log.w(TAG, "Invalid message length: $msgLen")
                            continue
                        }
                        
                        // Read the message
                        val msgBytes = ByteArray(msgLen)
                        input.readFully(msgBytes)
                        
                        // Parse first byte to get field number
                        if (msgBytes.isEmpty()) continue
                        
                        val firstByte = msgBytes[0].toInt() and 0xFF
                        val fieldNumber = firstByte shr 3
                        val wireType = firstByte and 0x07
                        
                        when (fieldNumber) {
                            1 -> {
                                // remote_configure - TV sends device info
                                Log.d(TAG, "Received remote_configure, sending our config response")
                                sendConfigureResponse()
                            }
                            2 -> {
                                // remote_set_active - TV requests active status
                                Log.d(TAG, "Received remote_set_active, responding")
                                sendSetActiveResponse()
                            }
                            8 -> {
                                // remote_ping_request - extract val1 and respond
                                val val1 = extractPingVal1FromField8(msgBytes)
                                Log.d(TAG, "Received ping request, responding with val1=$val1")
                                sendPingResponse(val1)
                            }
                            20 -> {
                                // remote_ime_key_inject - current app info
                                val appPackage = extractAppPackage(msgBytes)
                                if (appPackage != null) {
                                    Log.d(TAG, "Received current app: $appPackage")
                                    _currentApp.value = appPackage
                                }
                            }
                            21 -> {
                                // remote_ime_batch_edit - extract IME counters for text input
                                val (imeC, fieldC) = extractImeCounters(msgBytes)
                                imeCounter = imeC
                                imeFieldCounter = fieldC
                                Log.d(TAG, "IME counters updated: ime=$imeCounter, field=$imeFieldCounter")
                            }
                            40 -> {
                                // remote_start - TV power state
                                val isOn = extractTvPowerState(msgBytes)
                                Log.d(TAG, "Received remote_start (TV is on: $isOn)")
                                _isTvOn.value = isOn
                            }
                            50 -> {
                                // remote_set_volume_level - parse volume info
                                val volInfo = parseVolumeInfo(msgBytes)
                                if (volInfo != null) {
                                    Log.d(TAG, "Volume: ${volInfo.level}/${volInfo.max}, muted: ${volInfo.muted}")
                                    _volumeInfo.value = volInfo
                                }
                            }
                            else -> {
                                Log.d(TAG, "Received message field=$fieldNumber (${msgBytes.size} bytes)")
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // No data available, continue
                        continue
                    }
                }
            } catch (e: java.io.EOFException) {
                // Socket closed cleanly
                Log.d(TAG, "Socket closed (EOF)")
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Message handler error: ${e.message}")
                    // Connection lost
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Error("Connection lost. Tap to retry.")
                    }
                }
            } finally {
                Log.d(TAG, "Message handler stopped")
            }
        }
    }
    
    /**
     * Extract val1 from a ping request message (field 8)
     */
    private fun extractPingVal1FromField8(msgBytes: ByteArray): Int {
        // Field 8 format: [0x42] [length] [0x08] [val1_varint]
        // 0x42 = (8 << 3) | 2 = field 8, wire type 2 (length-delimited)
        if (msgBytes.size >= 3 && msgBytes[0] == 0x42.toByte()) {
            val innerLen = msgBytes[1].toInt() and 0xFF
            if (msgBytes.size >= 2 + innerLen && innerLen >= 2) {
                // Look for field 1 (0x08) inside
                if (msgBytes[2] == 0x08.toByte()) {
                    return msgBytes.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
                }
            }
        }
        return 0
    }
    
    /**
     * Send remote_configure response with our client info
     */
    private fun sendConfigureResponse() {
        synchronized(socketLock) {
            try {
                val output = commandOutput ?: return
                
                // Build RemoteDeviceInfo
                val deviceInfo = ByteArrayOutputStream().apply {
                    // Field 3: unknown1 = 1
                    write(0x18); write(0x01)
                    // Field 4: unknown2 = "1"
                    write(0x22); write(0x01); write('1'.code)
                    // Field 5: package_name = "atvremote"
                    val pkgName = "atvremote".toByteArray()
                    write(0x2A); write(pkgName.size); write(pkgName, 0, pkgName.size)
                    // Field 6: app_version = "1.0.0"
                    val appVer = "1.0.0".toByteArray()
                    write(0x32); write(appVer.size); write(appVer, 0, appVer.size)
                }.toByteArray()
                
                // Build RemoteConfigure
                val configure = ByteArrayOutputStream().apply {
                    // Field 1: code1 = 622 (features mask - KEY, APP_LINK, IME)
                    write(0x08); write(0xEE); write(0x04)
                    // Field 2: device_info
                    write(0x12); write(deviceInfo.size); write(deviceInfo, 0, deviceInfo.size)
                }.toByteArray()
                
                // Build full RemoteMessage with field 1 = remote_configure
                val fullMessage = ByteArrayOutputStream().apply {
                    write(0x0A) // Field 1, wire type 2
                    write(configure.size)
                    write(configure, 0, configure.size)
                }.toByteArray()
                
                writeVarint(output, fullMessage.size)
                output.write(fullMessage)
                output.flush()
                
                Log.d(TAG, "Sent configure response")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send configure response: ${e.message}")
            }
        }
    }
    
    /**
     * Send remote_set_active response
     */
    private fun sendSetActiveResponse() {
        synchronized(socketLock) {
            try {
                val output = commandOutput ?: return
                
                // Build RemoteSetActive with active = 622 (same features mask)
                val setActive = ByteArrayOutputStream().apply {
                    write(0x08) // Field 1: active (varint)
                    write(0xEE)
                    write(0x04)
                }.toByteArray()
                
                // Build full RemoteMessage with field 2 = remote_set_active
                val fullMessage = ByteArrayOutputStream().apply {
                    write(0x12) // Field 2, wire type 2
                    write(setActive.size)
                    write(setActive, 0, setActive.size)
                }.toByteArray()
                
                writeVarint(output, fullMessage.size)
                output.write(fullMessage)
                output.flush()
                
                Log.d(TAG, "Sent set_active response")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send set_active response: ${e.message}")
            }
        }
    }
    
    /**
     * Send a ping response (field 9) to keep the connection alive
     */
    private fun sendPingResponse(val1: Int) {
        synchronized(socketLock) {
            try {
                val output = commandOutput ?: return
                
                // Build RemotePingResponse with val1
                val pingResponse = ByteArrayOutputStream().apply {
                    write(0x08) // Field 1: val1 (varint)
                    write(val1 and 0x7F)
                }.toByteArray()
                
                // Build full RemoteMessage with field 9 = remote_ping_response
                // Field 9, wire type 2 = (9 << 3) | 2 = 74 = 0x4A
                val fullMessage = ByteArrayOutputStream().apply {
                    write(0x4A)
                    write(pingResponse.size)
                    write(pingResponse)
                }.toByteArray()
                
                writeVarint(output, fullMessage.size)
                output.write(fullMessage)
                output.flush()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ping response: ${e.message}")
            }
        }
    }
    
    /**
     * Parse volume info from remote_set_volume_level message (field 50)
     */
    private fun parseVolumeInfo(msgBytes: ByteArray): VolumeInfo? {
        try {
            // Field 50, wire type 2: (50 << 3) | 2 = 402 = 0x92 0x03
            // Inside: field 6 = volume_max, field 7 = volume_level, field 8 = volume_muted
            var volumeMax = 100
            var volumeLevel = 0
            var volumeMuted = false
            
            var i = 2 // Skip field tag and length
            while (i < msgBytes.size) {
                val tag = msgBytes[i].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07
                i++
                
                when (fieldNum) {
                    6 -> { // volume_max (varint)
                        volumeMax = readVarintFromBytes(msgBytes, i)
                        i += variantSize(volumeMax)
                    }
                    7 -> { // volume_level (varint)
                        volumeLevel = readVarintFromBytes(msgBytes, i)
                        i += variantSize(volumeLevel)
                    }
                    8 -> { // volume_muted (varint bool)
                        volumeMuted = msgBytes.getOrNull(i)?.toInt() != 0
                        i++
                    }
                    else -> {
                        // Skip unknown fields
                        if (wireType == 0) i++ // varint
                        else if (wireType == 2) { // length-delimited
                            val len = msgBytes.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                            i += 1 + len
                        }
                        else i++
                    }
                }
            }
            
            return VolumeInfo(volumeLevel, volumeMax, volumeMuted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse volume info: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract TV power state from remote_start message (field 40)
     */
    private fun extractTvPowerState(msgBytes: ByteArray): Boolean {
        try {
            // Field 40, wire type 2: inside has field 1 = started (bool)
            for (i in 2 until msgBytes.size - 1) {
                if ((msgBytes[i].toInt() and 0xFF) == 0x08) { // Field 1 varint
                    return msgBytes.getOrNull(i + 1)?.toInt() != 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse TV power state: ${e.message}")
        }
        return true // Default to on
    }
    
    /**
     * Extract app package from remote_ime_key_inject message (field 20)
     */
    private fun extractAppPackage(msgBytes: ByteArray): String? {
        try {
            // Look for field 12 (app_package) inside the nested structure
            var i = 0
            while (i < msgBytes.size - 2) {
                val tag = msgBytes[i].toInt() and 0xFF
                val fieldNum = tag shr 3
                
                if (fieldNum == 12 && (tag and 0x07) == 2) { // length-delimited string
                    val len = msgBytes.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: 0
                    if (i + 2 + len <= msgBytes.size) {
                        return String(msgBytes, i + 2, len, Charsets.UTF_8)
                    }
                }
                i++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse app package: ${e.message}")
        }
        return null
    }
    
    private fun readVarintFromBytes(bytes: ByteArray, start: Int): Int {
        var result = 0
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            i++
        }
        return result
    }
    
    private fun variantSize(value: Int): Int {
        if (value < 128) return 1
        if (value < 16384) return 2
        if (value < 2097152) return 3
        return 4
    }
    
    /**
     * Extract IME counters from remote_ime_batch_edit message (field 21)
     */
    private fun extractImeCounters(msgBytes: ByteArray): Pair<Int, Int> {
        var imeC = 0
        var fieldC = 0
        try {
            var i = 2 // Skip field tag and outer length
            while (i < msgBytes.size - 1) {
                val tag = msgBytes[i].toInt() and 0xFF
                val fieldNum = tag shr 3
                i++
                
                when (fieldNum) {
                    1 -> { // ime_counter (varint)
                        imeC = readVarintFromBytes(msgBytes, i)
                        i += variantSize(imeC)
                    }
                    2 -> { // field_counter (varint)
                        fieldC = readVarintFromBytes(msgBytes, i)
                        i += variantSize(fieldC)
                    }
                    else -> i++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse IME counters: ${e.message}")
        }
        return Pair(imeC, fieldC)
    }
    
    /**
     * Send text to the TV (for keyboard input)
     * Uses RemoteImeBatchEdit message (field 21) for proper text input
     * The TV must have a text field focused for this to work
     */
    suspend fun sendText(tvIp: String, text: String): Boolean {
        if (text.isEmpty()) return false
        
        return withContext(Dispatchers.IO) {
            try {
                synchronized(socketLock) {
                    if (connectedTvIp != tvIp || commandSocket?.isConnected != true) {
                        Log.w(TAG, "Not connected to TV for text input")
                        return@withContext false
                    }
                    
                    val output = commandOutput ?: return@withContext false
                    
                    // Build RemoteImeObject with text
                    // RemoteImeObject: start=field1, end=field2, value=field3
                    val paramValue = text.length - 1
                    val textBytes = text.toByteArray(Charsets.UTF_8)
                    
                    val imeObject = ByteArrayOutputStream().apply {
                        // Field 1: start (varint) = (1 << 3) | 0 = 0x08
                        write(0x08)
                        writeVarint(this, paramValue)
                        // Field 2: end (varint) = (2 << 3) | 0 = 0x10
                        write(0x10)
                        writeVarint(this, paramValue)
                        // Field 3: value (string) = (3 << 3) | 2 = 0x1A
                        write(0x1A)
                        write(textBytes.size)
                        write(textBytes, 0, textBytes.size)
                    }.toByteArray()
                    
                    // Build RemoteEditInfo with insert=1 and text_field_status
                    // RemoteEditInfo: insert=field1, text_field_status=field2
                    val editInfo = ByteArrayOutputStream().apply {
                        // Field 1: insert (varint) = (1 << 3) | 0 = 0x08
                        write(0x08)
                        write(0x01)
                        // Field 2: text_field_status (nested) = (2 << 3) | 2 = 0x12
                        write(0x12)
                        write(imeObject.size)
                        write(imeObject, 0, imeObject.size)
                    }.toByteArray()
                    
                    // Build RemoteImeBatchEdit
                    val batchEdit = ByteArrayOutputStream().apply {
                        // Field 1: ime_counter (varint)
                        write(0x08)
                        writeVarint(this, imeCounter)
                        // Field 2: field_counter (varint)
                        write(0x10)
                        writeVarint(this, imeFieldCounter)
                        // Field 3: edit_info (nested, repeated)
                        write(0x1A)
                        write(editInfo.size)
                        write(editInfo, 0, editInfo.size)
                    }.toByteArray()
                    
                    // Build RemoteMessage with field 21 = remote_ime_batch_edit
                    // Field 21, wire type 2 = (21 << 3) | 2 = 170 = 0xAA 0x01
                    val remoteMessage = ByteArrayOutputStream().apply {
                        write(0xAA)
                        write(0x01)
                        write(batchEdit.size)
                        write(batchEdit, 0, batchEdit.size)
                    }.toByteArray()
                    
                    // Write with length prefix (encodeDelimited)
                    writeVarint(output, remoteMessage.size)
                    output.write(remoteMessage)
                    output.flush()
                    
                    Log.d(TAG, "Sent text via IME: $text (ime=$imeCounter, field=$imeFieldCounter)")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send text: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Send a single key without flushing
     */
    private fun sendSingleKey(output: DataOutputStream, keyCode: Int) {
        val direction = 3 // SHORT (tap)
        
        val keyInject = ByteArrayOutputStream().apply {
            write(0x08)
            writeVarint(this, keyCode)
            write(0x10)
            writeVarint(this, direction)
        }.toByteArray()
        
        val remoteMessage = ByteArrayOutputStream().apply {
            write(0x52) // Field 10, wire type 2
            write(keyInject.size)
            write(keyInject, 0, keyInject.size)
        }.toByteArray()
        
        writeVarint(output, remoteMessage.size)
        output.write(remoteMessage)
    }
    
    /**
     * Disconnect from the currently connected TV
     */
    fun disconnectFromTv() {
        readerJob?.cancel()
        readerJob = null
        synchronized(socketLock) {
            disconnectFromTvInternal()
        }
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * Internal disconnect without state update (for use in synchronized blocks)
     */
    private fun disconnectFromTvInternal() {
        try {
            commandOutput?.flush()
        } catch (_: Exception) {}
        try {
            commandOutput?.close()
        } catch (_: Exception) {}
        try {
            commandSocket?.close()
        } catch (_: Exception) {}
        commandSocket = null
        commandOutput = null
        connectedTvIp = null
    }
    
    /**
     * Unpair a TV by IP address
     */
    private fun unpairTvByIp(tvIp: String) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _pairedTvs.value.toMutableSet()
        current.remove(tvIp)
        prefs?.edit()?.putStringSet(PREFS_PAIRED_TVS, current)?.apply()
        _pairedTvs.value = current
        Log.d(TAG, "Unpaired TV by IP: $tvIp")
    }
    
    /**
     * Send a key event to the TV
     * Uses persistent connection if available, otherwise creates a new one
     * @param tvIp IP address of the TV
     * @param keyCode Key code (use KEY_* constants)
     * @return true if key was sent successfully, false otherwise
     */
    suspend fun sendKeyEvent(tvIp: String, keyCode: Int): Boolean {
        // Check if TV is paired first
        if (!_pairedTvs.value.contains(tvIp)) {
            Log.w(TAG, "Cannot send key - TV $tvIp is not paired")
            _connectionState.value = ConnectionState.Error("TV not paired. Please pair first.")
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                // Ensure we're connected
                synchronized(socketLock) {
                    if (connectedTvIp != tvIp || commandSocket?.isConnected != true || commandSocket?.isClosed == true) {
                        // Need to connect first
                        Log.d(TAG, "Not connected, will connect first")
                    } else {
                        // Already connected, send directly
                        val output = commandOutput ?: throw IllegalStateException("Output stream not available")
                        
                        // Send key with direction SHORT (tap = down+up combined)
                        sendKeyAction(output, keyCode, 3)
                        output.flush()
                        
                        Log.d(TAG, "Sent key event: $keyCode")
                        return@withContext true
                    }
                }
                
                // Need to connect first
                val connected = connectToTv(tvIp)
                if (!connected) {
                    return@withContext false
                }
                
                // Now send the key
                synchronized(socketLock) {
                    val output = commandOutput ?: throw IllegalStateException("Output stream not available")
                    
                    // Send key with direction SHORT (tap = down+up combined)
                    sendKeyAction(output, keyCode, 3)
                    output.flush()
                }
                
                Log.d(TAG, "Sent key event: $keyCode")
                true
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                Log.e(TAG, "Failed to send key event: $errorMessage")
                
                // Connection broken, disconnect and try to reconnect once
                disconnectFromTv()
                
                // Check if it's a broken pipe/connection error - auto-retry once
                if (errorMessage.contains("Broken pipe", ignoreCase = true) || 
                    errorMessage.contains("Connection reset", ignoreCase = true) ||
                    errorMessage.contains("Socket closed", ignoreCase = true)) {
                    
                    Log.d(TAG, "Connection lost, attempting to reconnect...")
                    _connectionState.value = ConnectionState.Connecting
                    
                    // Try to reconnect and send again
                    try {
                        val reconnected = connectToTv(tvIp)
                        if (reconnected) {
                            synchronized(socketLock) {
                                val output = commandOutput ?: throw IllegalStateException("Output stream not available")
                                sendKeyAction(output, keyCode, 3)
                                output.flush()
                            }
                            Log.d(TAG, "Reconnected and sent key event: $keyCode")
                            return@withContext true
                        }
                    } catch (retryError: Exception) {
                        Log.e(TAG, "Retry also failed: ${retryError.message}")
                    }
                    
                    _connectionState.value = ConnectionState.Error("Connection lost. Tap to retry.")
                } else {
                    _connectionState.value = ConnectionState.Error("Failed to send command: $errorMessage")
                }
                false
            }
        }
    }
    
    /**
     * Send a single key action
     * Uses RemoteKeyInject (field 10 in RemoteMessage) with direction SHORT=3 for tap
     */
    private fun sendKeyAction(output: DataOutputStream, keyCode: Int, action: Int) {
        // Direction: 1=START_LONG, 2=END_LONG, 3=SHORT (tap)
        // For key press we use SHORT (3) - combines down+up
        val direction = 3 // SHORT
        
        // Build RemoteKeyInject message
        val keyInject = ByteArrayOutputStream().apply {
            // Field 1: key_code (varint)
            write(0x08)
            writeVarint(this, keyCode)
            
            // Field 2: direction (varint)
            write(0x10)
            writeVarint(this, direction)
        }.toByteArray()
        
        // Build RemoteMessage with field 10 = remote_key_inject
        // Field 10, wire type 2 = (10 << 3) | 2 = 82 = 0x52
        val remoteMessage = ByteArrayOutputStream().apply {
            write(0x52)
            write(keyInject.size)
            write(keyInject, 0, keyInject.size)
        }.toByteArray()
        
        // Write with length prefix (encodeDelimited)
        writeVarint(output, remoteMessage.size)
        output.write(remoteMessage)
    }
    
    /**
     * Start discovering Android TVs on the network
     */
    fun startDiscovery(context: Context) {
        stopDiscovery()
        
        _discoveredTvs.value = emptyList()
        pendingServices.clear()
        isResolvingService = false
        
        _isScanning.value = true
        _error.value = null
        
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val listener = createDiscoveryListener()
        discoveryListener = listener
        
        Log.d(TAG, "Starting mDNS discovery for $SERVICE_TYPE")
        
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            
            // Auto-stop after timeout
            discoveryJob = CoroutineScope(Dispatchers.Main).launch {
                delay(DISCOVERY_TIMEOUT_MS)
                if (_isScanning.value) {
                    Log.d(TAG, "Discovery timeout reached")
                    stopDiscovery()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            _error.value = "Failed to start discovery: ${e.message}"
            _isScanning.value = false
        }
    }
    
    /**
     * Stop discovery
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
                Log.d(TAG, "Discovery stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
        
        pendingServices.clear()
        isResolvingService = false
        _isScanning.value = false
    }
    
    /**
     * Start pairing with an Android TV
     * The Android TV Remote Protocol v2 uses a multi-phase handshake:
     * 1. PairingRequest → TV responds with PAIRING_OPTION (status=2)
     * 2. PairingOption → Select ENCODING_TYPE_HEXADECIMAL
     * 3. Configuration → Exchange client/server configuration
     * 4. TV displays code → User enters it → Secret exchange
     */
    suspend fun startPairing(tv: AndroidTv): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _pairingState.value = PairingState.Connecting
                Log.d(TAG, "Starting pairing with ${tv.name} at ${tv.ipAddress}:${tv.pairingPort}")
                
                // Use cached SSL context for pairing too
                val sslContext = getOrCreateSslContext()
                val socketFactory = sslContext.socketFactory
                
                val socket = socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(tv.ipAddress, tv.pairingPort), 10000)
                socket.soTimeout = 300000 // 5 minutes timeout for user to enter code
                
                // Start TLS handshake
                socket.startHandshake()
                Log.d(TAG, "TLS handshake complete")
                
                // Extract server certificate for computing pairing secret hash
                val serverCert = try {
                    socket.session.peerCertificates.firstOrNull() as? X509Certificate
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get server certificate: ${e.message}")
                    null
                }
                Log.d(TAG, "Server certificate: ${serverCert?.subjectDN}")
                
                val inputStream = DataInputStream(BufferedInputStream(socket.inputStream))
                val outputStream = DataOutputStream(BufferedOutputStream(socket.outputStream))
                
                // ===== Phase 1: Send PairingRequest =====
                Log.d(TAG, "Phase 1: Sending PairingRequest")
                sendPairingRequest(outputStream)
                
                // Wait for PairingRequestAck
                val phase1Response = readPairingMessage(inputStream)
                Log.d(TAG, "Phase 1 response: status=${phase1Response.status}")
                
                if (phase1Response.status != 200) { // STATUS_OK = 200
                    Log.e(TAG, "Phase 1 failed with status: ${phase1Response.status}")
                    socket.close()
                    _pairingState.value = PairingState.Error("Pairing rejected (status=${phase1Response.status})")
                    return@withContext false
                }
                
                // ===== Phase 2: Send PairingOption =====
                Log.d(TAG, "Phase 2: Sending PairingOption")
                sendPairingOption(outputStream)
                
                // Wait for PairingOption response
                val phase2Response = readPairingMessage(inputStream)
                Log.d(TAG, "Phase 2 response: status=${phase2Response.status}")
                
                if (phase2Response.status != 200) {
                    Log.e(TAG, "Phase 2 failed with status: ${phase2Response.status}")
                    socket.close()
                    _pairingState.value = PairingState.Error("Configuration rejected (status=${phase2Response.status})")
                    return@withContext false
                }
                
                // ===== Phase 3: Send PairingConfiguration =====
                // THIS triggers the TV to show the pairing code!
                Log.d(TAG, "Phase 3: Sending PairingConfiguration")
                sendPairingConfiguration(outputStream)
                
                // Wait for PairingConfigurationAck - after this, TV shows the code
                val phase3Response = readPairingMessage(inputStream)
                Log.d(TAG, "Phase 3 response: status=${phase3Response.status}")
                
                if (phase3Response.status != 200) {
                    Log.e(TAG, "Phase 3 failed with status: ${phase3Response.status}")
                    socket.close()
                    _pairingState.value = PairingState.Error("Configuration failed (status=${phase3Response.status})")
                    return@withContext false
                }
                
                // TV should now display the pairing code!
                currentSession = PairingSession(tv, socket, inputStream, outputStream, serverCert)
                _pairingState.value = PairingState.WaitingForCode
                Log.d(TAG, "All 3 phases complete - TV should display code now!")
                
                // Timeout logic removed - keep waiting for code input indefinitely
                
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting pairing", e)
                _pairingState.value = PairingState.Error("Connection failed: ${e.message}")
                false
            }
        }
    }
    

    
    /**
     * Data class for parsed pairing messages
     */
    private data class PairingMessageData(
        val status: Int = 0,
        val value: Int = 0,
        val rawBytes: ByteArray = byteArrayOf()
    )
    
    /**
     * Submit the pairing code entered by the user
     * The code is displayed on the TV screen (6-character hex like "381AB0")
     */
    suspend fun submitPairingCode(code: String): Boolean {
        val session = currentSession ?: run {
            _pairingState.value = PairingState.Error("No active pairing session")
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                _pairingState.value = PairingState.SubmittingCode
                Log.d(TAG, "Submitting pairing code: $code")
                
                // Send the secret code (computed as SHA-256 hash with certificates)
                sendPairingSecret(session.outputStream, code, session.serverCertificate)
                session.outputStream.flush()
                
                // Read confirmation
                val success = readPairingConfirmation(session.inputStream)
                
                if (success) {
                    // Store the paired TV for future auto-reconnect
                    savePairedTv(session.tv)
                    Log.d(TAG, "Pairing successful! Waiting for TV to finalize certificate...")
                    
                    // CRITICAL: Allow TV time to finalize certificate pinning
                    // The TV needs to store our client certificate before we can connect
                    // to the command port. Without this delay, we get CERTIFICATE_UNKNOWN.
                    delay(2000)
                    
                    _pairingState.value = PairingState.Success(session.tv.copy(isPaired = true))
                    Log.d(TAG, "Ready to accept commands")
                } else {
                    _pairingState.value = PairingState.Error("Invalid pairing code")
                }
                
                session.close()
                currentSession = null
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting pairing code", e)
                _pairingState.value = PairingState.Error("Failed to submit code: ${e.message}")
                session.close()
                currentSession = null
                false
            }
        }
    }
    
    /**
     * Cancel ongoing pairing
     */
    fun cancelPairing() {
        currentSession?.close()
        currentSession = null
        _pairingState.value = PairingState.Idle
    }
    
    // ========== Private Methods ==========
    
    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Android TV found: ${serviceInfo.serviceName}")
                pendingServices.add(serviceInfo)
                
                if (!isResolvingService) {
                    tryResolveNextService()
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Android TV lost: ${serviceInfo.serviceName}")
                pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }
                
                val currentTvs = _discoveredTvs.value.toMutableList()
                currentTvs.removeAll { it.name == serviceInfo.serviceName }
                _discoveredTvs.value = currentTvs
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
                mainHandler.post { _isScanning.value = false }
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                mainHandler.post {
                    _error.value = "Discovery failed (code: $errorCode)"
                    _isScanning.value = false
                }
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }
    }
    
    private fun tryResolveNextService() {
        if (isResolvingService || pendingServices.isEmpty()) return
        
        val serviceInfo = pendingServices.removeAt(0)
        isResolvingService = true
        
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
                isResolvingService = false
                mainHandler.post { tryResolveNextService() }
            }
            
            override fun onServiceResolved(service: NsdServiceInfo) {
                Log.d(TAG, "Resolved: ${service.serviceName} -> ${service.host?.hostAddress}:${service.port}")
                
                val ipAddress = service.host?.hostAddress?.let {
                    if (it.startsWith("/")) it.substring(1) else it
                } ?: return
                
                // Extract device info from TXT records if available
                var modelName = ""
                var manufacturer = ""
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attributes = service.attributes
                    modelName = attributes["mn"]?.let { String(it, Charsets.UTF_8) } ?: ""
                    manufacturer = attributes["md"]?.let { String(it, Charsets.UTF_8) } ?: ""
                }
                
                val tv = AndroidTv(
                    name = service.serviceName,
                    ipAddress = ipAddress,
                    pairingPort = PAIRING_PORT,
                    commandPort = COMMAND_PORT,
                    modelName = modelName,
                    manufacturer = manufacturer,
                    isPaired = _pairedTvs.value.contains(ipAddress)
                )
                
                mainHandler.post {
                    val currentTvs = _discoveredTvs.value.toMutableList()
                    if (currentTvs.none { it.ipAddress == tv.ipAddress }) {
                        currentTvs.add(tv)
                        _discoveredTvs.value = currentTvs
                        Log.d(TAG, "Added TV: ${tv.name} at ${tv.ipAddress} (paired: ${tv.isPaired})")
                    }
                }
                
                isResolvingService = false
                mainHandler.post { tryResolveNextService() }
            }
        }
        
        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
            isResolvingService = false
            mainHandler.post { tryResolveNextService() }
        }
    }
    
    /**
     * Get or create cached SSL context
     * Android TV Pins the specific SSLContext instance/identity used during pairing
     */
    private fun getOrCreateSslContext(): SSLContext {
        cachedSslContext?.let { return it }

        // Trust all certificates (self-signed TV certs)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        // Prepare KeyStore with our client certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        
        if (clientKeyPair != null && clientCertificate != null) {
            keyStore.setKeyEntry(
                "client",
                clientKeyPair!!.private,
                null, 
                arrayOf(clientCertificate!!)
            )
        } else {
            Log.e(TAG, "Client certificate missing during SSL context creation!")
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        // Find the base X509KeyManager
        val baseKm = kmf.keyManagers.first { it is X509KeyManager } as X509KeyManager

        // Create forced KeyManager wrapper
        val forcedKm = object : X509KeyManager {
            override fun chooseClientAlias(
                keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?
            ) = "client"

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
                arrayOf("client")

            override fun chooseServerAlias(
                keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?
            ) = null

            override fun getServerAliases(
                keyType: String?, issuers: Array<out Principal>?
            ) = null

            override fun getCertificateChain(alias: String?) =
                baseKm.getCertificateChain("client")

            override fun getPrivateKey(alias: String?) =
                baseKm.getPrivateKey("client")
        }

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(arrayOf(forcedKm), trustAllCerts, SecureRandom())

        cachedSslContext = sslContext
        return sslContext
    }
    
    /**
     * Generate or load client certificate for TLS
     * The Android TV Remote protocol requires mutual TLS authentication
     */
    private fun loadOrGenerateClientCertificate() {
        try {
            val context = appContext ?: return
            val keyStoreFile = File(context.filesDir, "androidtv_keystore.bks")
            
            if (keyStoreFile.exists()) {
                // Try to load existing keystore
                try {
                    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                    FileInputStream(keyStoreFile).use { fis ->
                        keyStore.load(fis, "androidtv".toCharArray())
                    }
                    
                    val key = keyStore.getKey("client", "androidtv".toCharArray()) as? PrivateKey
                    val cert = keyStore.getCertificate("client") as? X509Certificate
                    
                    if (key != null && cert != null) {
                        // Reconstruct KeyPair from private key and certificate's public key
                        clientKeyPair = KeyPair(cert.publicKey, key)
                        clientCertificate = cert
                        Log.d(TAG, "Loaded existing client certificate")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load existing keystore, generating new one", e)
                }
            }
            
            // Generate new self-signed certificate
            Log.d(TAG, "Generating new client certificate")
            generateSelfSignedCertificate(context, keyStoreFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error with client certificate", e)
        }
    }
    
    /**
     * Generate a self-signed X.509 certificate for mutual TLS
     */
    private fun generateSelfSignedCertificate(context: Context, keyStoreFile: File) {
        try {
            // Generate RSA key pair
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // Create self-signed certificate using reflection to access internal APIs
            // This works on Android without external dependencies
            val cert = createSelfSignedCert(keyPair)
            
            if (cert != null) {
                clientKeyPair = keyPair
                clientCertificate = cert
                
                // Save to keystore for future use
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                keyStore.setKeyEntry("client", keyPair.private, "androidtv".toCharArray(), arrayOf(cert))
                
                FileOutputStream(keyStoreFile).use { fos ->
                    keyStore.store(fos, "androidtv".toCharArray())
                }
                
                Log.d(TAG, "Generated and saved new client certificate")
            } else {
                // Fallback: use key pair without certificate (may not work with all TVs)
                clientKeyPair = keyPair
                Log.w(TAG, "Using key pair without proper certificate (fallback mode)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating certificate", e)
        }
    }
    
    /**
     * Create a self-signed X.509 certificate using BouncyCastle
     * This is required for mutual TLS authentication with Android TV
     */
    private fun createSelfSignedCert(keyPair: KeyPair): X509Certificate? {
        return try {
            val startDate = Date()
            val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000 * 10) // 10 years validity
            
            val deviceName = Build.MODEL?.replace(" ", "_")?.replace("\"", "") ?: "AndroidDevice"
            val safeDeviceName = deviceName.take(40) // Limit length for X.500 name
            
            // Create X500Name for subject and issuer (self-signed)
            val subjectName = X500Name("CN=AndroidTvRemote_$safeDeviceName, O=AdbDeviceManager, C=US")
            
            // Generate a unique serial number
            val serialNumber = BigInteger(System.currentTimeMillis().toString())
            
            // Build the certificate using BouncyCastle
            val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                subjectName,           // issuer (same as subject for self-signed)
                serialNumber,          // serial number
                startDate,            // not before
                endDate,              // not after
                subjectName,          // subject
                keyPair.public        // public key
            )
            
            // Add Extensions (Required for some TVs to accept the cert)
            // Key Usage: Digital Signature, Key Encipherment
            certBuilder.addExtension(
                Extension.keyUsage,
                true, // critical
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            
            // Extended Key Usage: Client Auth ONLY (Server Auth causes command port failure)
            certBuilder.addExtension(
                Extension.extendedKeyUsage,
                false, // not critical
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)
            )
            
            // Sign the certificate with SHA256withRSA
            val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.private)
            
            val certificate = JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(contentSigner))
            
            Log.d(TAG, "Successfully created X.509 certificate using BouncyCastle")
            certificate
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create certificate using BouncyCastle", e)
            null
        }
    }
    
    /**
     * Send pairing request message
     * Based on proto: 
     *   PairingMessage { protocol_version=1, status=2, pairing_request=10 }
     *   PairingRequest { service_name=1, client_name=2 }
     */
    private fun sendPairingRequest(output: DataOutputStream) {
        val clientName = Build.MODEL ?: "Android Phone"
        val serviceName = "androidtvremote"
        
        // Build PairingRequest sub-message (field 10)
        val pairingRequest = ByteArrayOutputStream().apply {
            // service_name (field 1)
            write(0x0A) // field 1, wire type 2
            writeVarint(this, serviceName.length)
            write(serviceName.toByteArray(Charsets.UTF_8))
            
            // client_name (field 2)
            write(0x12) // field 2, wire type 2
            writeVarint(this, clientName.length)
            write(clientName.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        
        // Build outer PairingMessage
        val message = ByteArrayOutputStream().apply {
            // protocol_version (field 1) = 2
            write(0x08) // field 1, wire type 0
            writeVarint(this, 2)
            
            // status (field 2) = 200 (STATUS_OK)
            write(0x10) // field 2, wire type 0
            writeVarint(this, 200)
            
            // pairing_request (field 10) - nested message
            write(0x52) // field 10, wire type 2 (10 << 3 | 2 = 82 = 0x52)
            writeVarint(this, pairingRequest.size)
            write(pairingRequest)
        }.toByteArray()
        
        // Write with varint length prefix (delimited)
        writeVarint(output, message.size)
        output.write(message)
        output.flush()
        
        Log.d(TAG, "Sent PairingRequest (${message.size} bytes)")
    }
    
    /**
     * Read and parse a pairing message from the TV
     * Proto: PairingMessage { protocol_version=1, status=2, ... }
     */
    private fun readPairingMessage(input: DataInputStream): PairingMessageData {
        try {
            val length = readVarint(input)
            if (length <= 0 || length > 10000) {
                Log.e(TAG, "Invalid message length: $length")
                return PairingMessageData()
            }
            
            val buffer = ByteArray(length)
            input.readFully(buffer)
            Log.d(TAG, "Received message (${buffer.size} bytes): ${buffer.toHexString()}")
            
            // Parse protobuf fields
            var protocolVersion = 0
            var status = 0
            var pos = 0
            
            while (pos < buffer.size) {
                val tag = buffer[pos].toInt() and 0xFF
                pos++
                
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07
                
                if (wireType == 0) { // Varint
                    var v = 0
                    var shift = 0
                    while (pos < buffer.size) {
                        val b = buffer[pos].toInt() and 0xFF
                        pos++
                        v = v or ((b and 0x7F) shl shift)
                        shift += 7
                        if (b and 0x80 == 0) break
                    }
                    when (fieldNumber) {
                        1 -> protocolVersion = v  // protocol_version
                        2 -> status = v           // status (200 = OK)
                    }
                    Log.d(TAG, "Field $fieldNumber = $v")
                } else if (wireType == 2) { // Length-delimited
                    var len = 0
                    var shift = 0
                    while (pos < buffer.size) {
                        val b = buffer[pos].toInt() and 0xFF
                        pos++
                        len = len or ((b and 0x7F) shl shift)
                        shift += 7
                        if (b and 0x80 == 0) break
                    }
                    Log.d(TAG, "Field $fieldNumber (nested, len=$len)")
                    pos += len // Skip the content
                }
            }
            
            Log.d(TAG, "Parsed: protocolVersion=$protocolVersion, status=$status")
            return PairingMessageData(status, protocolVersion, buffer)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pairing message", e)
            return PairingMessageData()
        }
    }
    
    /**
     * Send PairingOption message
     * Based on proto:
     *   PairingMessage { protocol_version=1, status=2, pairing_option=20 }
     *   PairingOption { input_encodings=1, output_encodings=2, preferred_role=3 }
     *   PairingEncoding { type=1, symbol_length=2 }
     *   ENCODING_TYPE_HEXADECIMAL = 3
     *   ROLE_TYPE_INPUT = 1
     */
    private fun sendPairingOption(output: DataOutputStream) {
        // Build PairingEncoding: { type: 3 (HEXADECIMAL), symbol_length: 6 }
        val encoding = ByteArrayOutputStream().apply {
            write(0x08) // type (field 1)
            writeVarint(this, 3) // ENCODING_TYPE_HEXADECIMAL = 3
            write(0x10) // symbol_length (field 2)
            writeVarint(this, 6)
        }.toByteArray()
        
        // Build PairingOption: { input_encodings, preferred_role }
        val pairingOption = ByteArrayOutputStream().apply {
            // input_encodings (field 1, repeated) - length-delimited
            write(0x0A) // field 1, wire type 2
            writeVarint(this, encoding.size)
            write(encoding)
            
            // preferred_role (field 3) = ROLE_TYPE_INPUT = 1
            write(0x18) // field 3, wire type 0
            writeVarint(this, 1)
        }.toByteArray()
        
        // Build outer PairingMessage
        val message = ByteArrayOutputStream().apply {
            // protocol_version (field 1) = 2
            write(0x08)
            writeVarint(this, 2)
            
            // status (field 2) = 200 (STATUS_OK)
            write(0x10)
            writeVarint(this, 200)
            
            // pairing_option (field 20) - 20 << 3 | 2 = 162 = 0xA2, needs varint encoding
            // For field 20: tag = 20 * 8 + 2 = 162 = 0xA2 0x01 (since >127)
            write(0xA2)
            write(0x01)
            writeVarint(this, pairingOption.size)
            write(pairingOption)
        }.toByteArray()
        
        writeVarint(output, message.size)
        output.write(message)
        output.flush()
        
        Log.d(TAG, "Sent PairingOption (${message.size} bytes)")
    }
    
    /**
     * Send PairingConfiguration message
     * Based on proto:
     *   PairingMessage { protocol_version=1, status=2, pairing_configuration=30 }
     *   PairingConfiguration { encoding=1, client_role=2 }
     */
    private fun sendPairingConfiguration(output: DataOutputStream) {
        // Build PairingEncoding
        val encoding = ByteArrayOutputStream().apply {
            write(0x08) // type (field 1)
            writeVarint(this, 3) // ENCODING_TYPE_HEXADECIMAL = 3
            write(0x10) // symbol_length (field 2)
            writeVarint(this, 6)
        }.toByteArray()
        
        // Build PairingConfiguration: { encoding, client_role }
        val pairingConfig = ByteArrayOutputStream().apply {
            // encoding (field 1) - length-delimited
            write(0x0A) // field 1, wire type 2
            writeVarint(this, encoding.size)
            write(encoding)
            
            // client_role (field 2) = ROLE_TYPE_INPUT = 1
            write(0x10) // field 2, wire type 0
            writeVarint(this, 1)
        }.toByteArray()
        
        // Build outer PairingMessage
        val message = ByteArrayOutputStream().apply {
            // protocol_version (field 1) = 2
            write(0x08)
            writeVarint(this, 2)
            
            // status (field 2) = 200 (STATUS_OK)
            write(0x10)
            writeVarint(this, 200)
            
            // pairing_configuration (field 30) - 30 << 3 | 2 = 242 = 0xF2, needs varint
            // For field 30: tag = 30 * 8 + 2 = 242 = 0xF2 0x01
            write(0xF2)
            write(0x01)
            writeVarint(this, pairingConfig.size)
            write(pairingConfig)
        }.toByteArray()
        
        writeVarint(output, message.size)
        output.write(message)
        output.flush()
        
        Log.d(TAG, "Sent PairingConfiguration (${message.size} bytes)")
    }
    
    /**
     * Legacy read response (kept for compatibility)
     */
    private fun readPairingResponse(input: DataInputStream): Boolean {
        val message = readPairingMessage(input)
        return message.status > 0
    }
    
    /**
     * Write a varint (variable-length integer) to output stream
     * Used for protobuf length prefixes
     */
    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v > 127) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v and 0x7F)
    }
    
    /**
     * Read a varint (variable-length integer) from input stream
     */
    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = input.read()
            if (b == -1) throw IOException("EOF while reading varint")
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        return result
    }
    
    /**
     * Extension function to convert ByteArray to hex string for debugging
     */
    private fun ByteArray.toHexString(): String {
        return this.take(50).joinToString(" ") { "%02X".format(it) } + 
            if (this.size > 50) "..." else ""
    }
    
    /**
     * Send the pairing secret (computed SHA-256 hash)
     * 
     * IMPORTANT: The Android TV Remote Protocol v2 requires a SHA-256 hash computed from:
     * 1. Client certificate public key modulus
     * 2. Client certificate public key exponent
     * 3. Server certificate public key modulus
     * 4. Server certificate public key exponent
     * 5. Last 2 bytes of the pairing code (e.g., for "48A964" → [0xA9, 0x64])
     * 
     * The message must be wrapped in a full PairingMessage with protocolVersion and status.
     */
    private fun sendPairingSecret(output: DataOutputStream, code: String, serverCert: X509Certificate?) {
        try {
            // Convert 6-char hex string to bytes
            val hexCode = code.uppercase()
            val codeBytes = ByteArray(3)
            for (i in 0 until 3) {
                codeBytes[i] = hexCode.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            
            Log.d(TAG, "Pairing code '$hexCode' as bytes: ${codeBytes.toHexString()}")
            
            // Calculate the secret hash
            val secretHash = computePairingSecretHash(codeBytes, serverCert)
            
            if (secretHash != null) {
                Log.d(TAG, "Computed secret hash (${secretHash.size} bytes): ${secretHash.toHexString()}")
                
                // Build the pairingSecret nested message (field 1 = secret bytes)
                val pairingSecretMsg = ByteArrayOutputStream().apply {
                    // field 1, wire type 2 (length-delimited) for secret bytes
                    write(0x0A)
                    writeVarint(this, secretHash.size)
                    write(secretHash)
                }.toByteArray()
                
                // Build full PairingMessage with protocolVersion=2, status=200, pairingSecret
                val fullMessage = ByteArrayOutputStream().apply {
                    // Field 1: protocolVersion = 2 (wire type 0 = varint)
                    write(0x08)  // field 1, wire type 0
                    write(0x02)  // value 2
                    
                    // Field 2: status = 200 (wire type 0 = varint)
                    write(0x10)  // field 2, wire type 0
                    write(0xC8)  // 200 in varint (0xC8 0x01)
                    write(0x01)
                    
                    // Field 40: pairingSecret (wire type 2 = length-delimited)
                    // Field number 40 = (40 << 3) | 2 = 322 = 0xC2 0x02 in varint
                    write(0xC2)
                    write(0x02)
                    writeVarint(this, pairingSecretMsg.size)
                    write(pairingSecretMsg)
                }.toByteArray()
                
                // Write with varint length prefix
                writeVarint(output, fullMessage.size)
                output.write(fullMessage)
                output.flush()
                
                Log.d(TAG, "Sent pairing secret message (${fullMessage.size} bytes): ${fullMessage.toHexString()}")
            } else {
                Log.e(TAG, "Hash computation failed!")
                throw Exception("Failed to compute pairing secret hash")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending pairing secret", e)
            throw e
        }
    }
    
    /**
     * Compute the pairing secret hash according to Android TV Remote Protocol v2.
     * 
     * Based on Node.js implementation (louis49/androidtv-remote):
     * - hash = SHA256(clientModulus + clientExponent + serverModulus + serverExponent + lastTwoBytes)
     * - The LAST 2 bytes of the 6-character code are used (code.slice(2) = last 4 hex chars = 2 bytes)
     * - Verification: hash[0] must equal codeBytes[0] (first byte of code)
     * 
     * If verification fails, pairing will be rejected.
     */
    private fun computePairingSecretHash(codeBytes: ByteArray, serverCert: X509Certificate?): ByteArray? {
        return try {
            val clientPublicKey = clientCertificate?.publicKey as? java.security.interfaces.RSAPublicKey
            val serverPublicKey = serverCert?.publicKey as? java.security.interfaces.RSAPublicKey
            
            if (clientPublicKey == null) {
                Log.e(TAG, "Client public key not available")
                return null
            }
            
            if (serverPublicKey == null) {
                Log.e(TAG, "Server public key not available")
                return null
            }
            
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            
            // Get modulus as hex string (like Node.js client_certificate.modulus)
            // Node.js gets modulus as uppercase hex string from certificate
            val clientModulusHex = clientPublicKey.modulus.toString(16).uppercase()
            val serverModulusHex = serverPublicKey.modulus.toString(16).uppercase()
            
            // Get exponent - Node.js uses "0" + exponent.slice(2) 
            // exponent is usually "0x10001" (65537), so slice(2) gives "10001", then "0" + "10001" = "010001"
            val clientExponentHex = clientPublicKey.publicExponent.toString(16).uppercase().padStart(6, '0')
            val serverExponentHex = serverPublicKey.publicExponent.toString(16).uppercase().padStart(6, '0')
            
            // Convert hex strings to bytes
            val clientModulusBytes = hexStringToBytes(clientModulusHex)
            val clientExponentBytes = hexStringToBytes(clientExponentHex)
            val serverModulusBytes = hexStringToBytes(serverModulusHex)
            val serverExponentBytes = hexStringToBytes(serverExponentHex)
            
            Log.d(TAG, "Client modulus (${clientModulusBytes.size} bytes): ${clientModulusBytes.take(8).toByteArray().toHexString()}...")
            Log.d(TAG, "Client exponent (${clientExponentBytes.size} bytes): ${clientExponentBytes.toHexString()}")
            Log.d(TAG, "Server modulus (${serverModulusBytes.size} bytes): ${serverModulusBytes.take(8).toByteArray().toHexString()}...")
            Log.d(TAG, "Server exponent (${serverExponentBytes.size} bytes): ${serverExponentBytes.toHexString()}")
            
            // The nonce is the LAST 2 BYTES of the code (code.slice(2) in JS = last 4 hex chars)
            // For code "6AE4D0" → bytes [0x6A, 0xE4, 0xD0] → last 2 bytes = [0xE4, 0xD0]
            val nonce = byteArrayOf(codeBytes[1], codeBytes[2])
            Log.d(TAG, "Nonce (last 2 bytes of code): ${nonce.toHexString()}")
            
            // Compute hash = SHA256(clientMod + clientExp + serverMod + serverExp + nonce)
            digest.update(clientModulusBytes)
            digest.update(clientExponentBytes)
            digest.update(serverModulusBytes)
            digest.update(serverExponentBytes)
            digest.update(nonce)
            
            val secretHash = digest.digest()
            Log.d(TAG, "Secret hash (32 bytes): ${secretHash.toHexString()}")
            
            // Verification: first byte of hash should match first byte of code
            // This is a check the TV does - if our hash[0] != code[0], TV will reject
            if (secretHash[0] != codeBytes[0]) {
                Log.w(TAG, "Hash verification failed! hash[0]=${"%02X".format(secretHash[0])} != code[0]=${"%02X".format(codeBytes[0])}")
                Log.w(TAG, "This means the code was likely entered incorrectly or there's a certificate mismatch")
            } else {
                Log.d(TAG, "Hash verification passed: hash[0] == code[0] == ${"%02X".format(codeBytes[0])}")
            }
            
            // Return the full 32-byte hash as the secret
            secretHash
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute pairing secret hash", e)
            null
        }
    }
    
    /**
     * Convert hex string to byte array
     */
    private fun hexStringToBytes(hex: String): ByteArray {
        val paddedHex = if (hex.length % 2 != 0) "0$hex" else hex
        return ByteArray(paddedHex.length / 2) { i ->
            paddedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    /**
     * Read pairing confirmation
     * Parses protobuf response and checks status code
     */
    private fun readPairingConfirmation(input: DataInputStream): Boolean {
        return try {
            val length = readVarint(input)
            Log.d(TAG, "Confirmation length prefix: $length")
            
            if (length > 0 && length < 10000) {
                val buffer = ByteArray(length)
                input.readFully(buffer)
                
                Log.d(TAG, "Received pairing confirmation (${buffer.size} bytes): ${buffer.toHexString()}")
                
                // Parse protobuf to check status code
                var status = 0
                var pos = 0
                
                while (pos < buffer.size) {
                    val tag = buffer[pos].toInt() and 0xFF
                    pos++
                    
                    val fieldNumber = tag ushr 3
                    val wireType = tag and 0x07
                    
                    if (wireType == 0) { // Varint
                        var v = 0
                        var shift = 0
                        while (pos < buffer.size) {
                            val b = buffer[pos].toInt() and 0xFF
                            pos++
                            v = v or ((b and 0x7F) shl shift)
                            shift += 7
                            if (b and 0x80 == 0) break
                        }
                        when (fieldNumber) {
                            2 -> status = v  // status field
                        }
                    } else if (wireType == 2) { // Length-delimited
                        var len = 0
                        var shift = 0
                        while (pos < buffer.size) {
                            val b = buffer[pos].toInt() and 0xFF
                            pos++
                            len = len or ((b and 0x7F) shl shift)
                            shift += 7
                            if (b and 0x80 == 0) break
                        }
                        pos += len // Skip the content
                    }
                }
                
                Log.d(TAG, "Pairing confirmation status: $status")
                
                // 200 = success, 400 = bad code, other = error
                if (status == 200) {
                    Log.d(TAG, "Pairing code accepted!")
                    true
                } else {
                    Log.e(TAG, "Pairing code REJECTED with status: $status")
                    false
                }
            } else {
                Log.e(TAG, "Invalid confirmation length: $length")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pairing confirmation", e)
            false
        }
    }
    
    /**
     * Save paired device info for future connections
     */
    private fun savePairedDevice(tv: AndroidTv) {
        try {
            val context = appContext ?: return
            val prefs = context.getSharedPreferences("android_tv_paired", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("${tv.ipAddress}_name", tv.name)
                .putBoolean("${tv.ipAddress}_paired", true)
                .apply()
            Log.d(TAG, "Saved paired device: ${tv.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving paired device", e)
        }
    }
    
    /**
     * Check if a device is already paired
     */
    fun isDevicePaired(ipAddress: String): Boolean {
        val context = appContext ?: return false
        val prefs = context.getSharedPreferences("android_tv_paired", Context.MODE_PRIVATE)
        return prefs.getBoolean("${ipAddress}_paired", false)
    }
}
