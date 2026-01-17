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
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.NetworkInterface
import java.net.InetAddress

/**
 * MdnsManager - Handles mDNS service discovery for finding Windows devices on the network
 * 
 * Uses Android's NsdManager to discover devices advertising "_httpapp._tcp." services.
 * Device information is extracted directly from mDNS TXT records (device_data JSON).
 * Uses TCP socket checks for device reachability verification.
 * 
 * Supports two modes:
 * 1. One-time discovery with timeout
 * 2. Continuous discovery for automatic device detection
 */
object MdnsManager {
    private const val TAG = "MdnsManager"
    private const val SERVICE_TYPE = "_httpapp._tcp."
    private const val DISCOVERY_TIMEOUT_MS = 15000L
    private const val CONTINUOUS_REFRESH_INTERVAL_MS = 30000L // Refresh every 30 seconds
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isResolvingService = false
    private val pendingServices = mutableListOf<NsdServiceInfo>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryJob: Job? = null
    private var continuousDiscoveryJob: Job? = null
    private var isContinuousMode = false
    private var appContext: Context? = null
    private var localIpAddress: String? = null
    
    // State flows for UI
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Callback for when devices are discovered
    private var onDeviceDiscoveredCallback: ((DiscoveredDevice) -> Unit)? = null
    private var onDeviceLostCallback: ((String) -> Unit)? = null
    
    /**
     * Data class representing a discovered device (Windows or Android)
     */
    data class DiscoveredDevice(
        val ipAddress: String,
        val port: Int,
        val hostname: String = "Unknown",
        val fullName: String = "Device",
        val email: String = "N/A",
        val isConnected: Boolean = true,
        val deviceType: String = "windows", // "windows" or "android"
        val rawDeviceData: String? = null // Store raw JSON for extended parsing
    )
    
    /**
     * Set callback for when a new device is discovered
     */
    fun setOnDeviceDiscoveredCallback(callback: (DiscoveredDevice) -> Unit) {
        onDeviceDiscoveredCallback = callback
    }
    
    /**
     * Set callback for when a device is lost from the network
     */
    fun setOnDeviceLostCallback(callback: (String) -> Unit) {
        onDeviceLostCallback = callback
    }
    
    /**
     * Start continuous mDNS discovery - keeps monitoring indefinitely
     * Automatically detects new devices and removes lost ones
     */
    fun startContinuousDiscovery(context: Context) {
        appContext = context.applicationContext
        isContinuousMode = true
        
        Log.d(TAG, "Starting continuous mDNS discovery mode")
        startDiscoveryInternal(context, isContinuous = true)
    }
    
    /**
     * Stop continuous discovery mode
     */
    fun stopContinuousDiscovery() {
        isContinuousMode = false
        continuousDiscoveryJob?.cancel()
        continuousDiscoveryJob = null
        stopDiscovery()
    }
    
    /**
     * Start mDNS discovery for Windows devices
     * Always clears old devices and starts fresh
     */
    fun startDiscovery(context: Context) {
        startDiscoveryInternal(context, isContinuous = false)
    }
    
    private fun startDiscoveryInternal(context: Context, isContinuous: Boolean) {
        // Always stop any existing discovery first
        stopDiscovery()
        
        // Clear all old devices - start fresh every time
        _discoveredDevices.value = emptyList()
        pendingServices.clear()
        isResolvingService = false
        
        _isScanning.value = true
        _error.value = null
        
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val listener = createDiscoveryListener()
        discoveryListener = listener
        
        Log.d(TAG, "Starting mDNS discovery for $SERVICE_TYPE (mode: ${if (isContinuous) "continuous" else "one-time"})")
        
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            
            if (!isContinuous) {
                // Auto-stop after timeout for one-time discovery
                discoveryJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(DISCOVERY_TIMEOUT_MS)
                    if (_isScanning.value) {
                        Log.d(TAG, "Discovery timeout reached, stopping...")
                        stopDiscovery()
                    }
                }
            } else {
                // In continuous mode, periodically restart discovery to catch new devices
                continuousDiscoveryJob = CoroutineScope(Dispatchers.Main).launch {
                    while (isActive && isContinuousMode) {
                        delay(CONTINUOUS_REFRESH_INTERVAL_MS)
                        if (isContinuousMode) {
                            Log.d(TAG, "Continuous mode: Refreshing device list...")
                            refreshDeviceConnections()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            _error.value = "Failed to start discovery: ${e.message}"
            _isScanning.value = false
        }
    }
    
    /**
     * Refresh connections for all discovered devices
     */
    private fun refreshDeviceConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            val currentDevices = _discoveredDevices.value.toMutableList()
            val stillAlive = mutableListOf<DiscoveredDevice>()
            
            currentDevices.forEach { device ->
                if (isDeviceReachable(device.ipAddress, device.port)) {
                    stillAlive.add(device)
                } else {
                    Log.d(TAG, "Device ${device.hostname} at ${device.ipAddress} no longer reachable")
                    onDeviceLostCallback?.invoke(device.ipAddress)
                }
            }
            
            withContext(Dispatchers.Main) {
                _discoveredDevices.value = stillAlive
            }
        }
    }
    
    /**
     * Check if a device is still reachable via TCP socket
     */
    private fun isDeviceReachable(ipAddress: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ipAddress, port), 2000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Stop mDNS discovery
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
     * Clear discovered devices
     */
    fun clearDevices() {
        _discoveredDevices.value = emptyList()
    }
    
    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                
                // Filter out self-discovery by service name
                if (isSelfService(serviceInfo.serviceName)) {
                    Log.d(TAG, "Ignoring self-service: ${serviceInfo.serviceName}")
                    return
                }
                
                // Filter out Android devices - this manager is for Windows only
                val name = serviceInfo.serviceName?.replace("\"", "") ?: ""
                if (name.startsWith("android-") || name.lowercase().contains("android")) {
                    Log.d(TAG, "Ignoring Android device in MdnsManager: $name")
                    return
                }
                
                pendingServices.add(serviceInfo)
                
                if (!isResolvingService) {
                    tryResolveNextService()
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }
                
                // Remove from discovered devices
                val currentDevices = _discoveredDevices.value.toMutableList()
                val removedDevice = currentDevices.find { it.hostname == serviceInfo.serviceName }
                currentDevices.removeAll { it.hostname == serviceInfo.serviceName }
                _discoveredDevices.value = currentDevices
                
                // Notify callback
                removedDevice?.let { onDeviceLostCallback?.invoke(it.ipAddress) }
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
                mainHandler.post {
                    // Fix: Only update state if we don't have a new active listener
                    // If discoveryListener is not null, it means a new discovery has been started
                    // so we should ignore this stop event from the previous session
                    if (discoveryListener == null && !isContinuousMode) {
                        _isScanning.value = false
                    }
                }
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
        if (isResolvingService || pendingServices.isEmpty()) {
            return
        }
        
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
                
                // Filter out self-discovery by IP address
                if (isSelfIpAddress(ipAddress)) {
                    Log.d(TAG, "Ignoring self IP address: $ipAddress")
                    isResolvingService = false
                    mainHandler.post { tryResolveNextService() }
                    return
                }
                
                val port = service.port
                
                // Try to extract device_data from TXT records first (API 21+)
                val deviceFromTxt = extractDeviceFromTxtRecords(service, ipAddress, port)
                
                if (deviceFromTxt != null) {
                    Log.d(TAG, "Got device info from mDNS TXT records: ${deviceFromTxt.hostname}")
                    CoroutineScope(Dispatchers.Main).launch {
                        addDeviceIfNotExists(deviceFromTxt)
                    }
                } else {
                    // No TXT records available, add device with minimal info from mDNS
                    Log.d(TAG, "TXT records unavailable, adding device with mDNS service name")
                    val minimalDevice = DiscoveredDevice(
                        ipAddress = ipAddress,
                        port = port,
                        hostname = service.serviceName.takeIf { it.isNotEmpty() } ?: "Unknown",
                        fullName = "Windows PC",
                        email = "N/A",
                        isConnected = true,
                        deviceType = "windows"
                    )
                    CoroutineScope(Dispatchers.Main).launch {
                        addDeviceIfNotExists(minimalDevice)
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
     * Extract device information from mDNS TXT records
     * The Python server includes device_data JSON in the TXT properties
     */
    private fun extractDeviceFromTxtRecords(service: NsdServiceInfo, ipAddress: String, port: Int): DiscoveredDevice? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attributes = service.attributes
                
                if (attributes.isEmpty()) {
                    Log.d(TAG, "No TXT attributes found in service")
                    return null
                }
                
                // Try to get device_data JSON first (most complete data)
                val deviceDataBytes = attributes["device_data"]
                if (deviceDataBytes != null) {
                    val deviceDataJson = String(deviceDataBytes, Charsets.UTF_8)
                    Log.d(TAG, "Found device_data in TXT: $deviceDataJson")
                    return parseDeviceDataJson(deviceDataJson, ipAddress, port)
                }
                
                // Fallback to individual TXT properties
                val hostname = attributes["host"]?.let { String(it, Charsets.UTF_8) } ?: "Unknown"
                val fullName = attributes["full_name"]?.let { String(it, Charsets.UTF_8) } ?: "Windows PC"
                val email = attributes["email"]?.let { String(it, Charsets.UTF_8) } ?: "N/A"
                val deviceType = attributes["device_type"]?.let { String(it, Charsets.UTF_8) } ?: "windows"
                
                Log.d(TAG, "Parsed TXT records: host=$hostname, name=$fullName, email=$email")
                
                DiscoveredDevice(
                    ipAddress = ipAddress,
                    port = port,
                    hostname = hostname,
                    fullName = fullName,
                    email = email,
                    isConnected = true,
                    deviceType = if (deviceType.contains("android")) "android" else deviceType
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting TXT records", e)
            null
        }
    }
    
    /**
     * Parse the device_data JSON from TXT records
     */
    private fun parseDeviceDataJson(jsonString: String, ipAddress: String, port: Int): DiscoveredDevice? {
        return try {
            val json = JSONObject(jsonString)
            
            if (json.optString("status") == "success") {
                val deviceInfo = json.optJSONObject("device_info")
                
                if (deviceInfo != null) {
                    DiscoveredDevice(
                        ipAddress = ipAddress,
                        port = port,
                        hostname = deviceInfo.optString("hostname", "Unknown"),
                        fullName = deviceInfo.optString("full_name", "Windows PC"),
                        email = deviceInfo.optString("microsoft_email", "N/A"),
                        isConnected = true,
                        deviceType = if (json.optString("device_type", "windows").contains("android")) "android" else "windows",
                        rawDeviceData = jsonString
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device_data JSON", e)
            null
        }
    }
    
    
    private suspend fun addDeviceWithMinimalInfo(ipAddress: String, port: Int) {
        val device = DiscoveredDevice(
            ipAddress = ipAddress,
            port = port,
            hostname = "Unknown",
            fullName = "Windows PC",
            email = "N/A",
            isConnected = true
        )
        addDeviceIfNotExists(device)
    }
    
    private suspend fun addDeviceIfNotExists(device: DiscoveredDevice) {
        withContext(Dispatchers.Main) {
            // Double-check: Don't add self
            if (isSelfIpAddress(device.ipAddress)) {
                Log.d(TAG, "Skipping self-device: ${device.ipAddress}")
                return@withContext
            }
            
            // Only add Windows devices - ignore Android and Android TV devices
            if (device.deviceType == "android" || device.deviceType == "android_tv" || device.hostname.lowercase().contains("android")) {
                Log.d(TAG, "Skipping Android device: ${device.ipAddress} (MdnsManager is for Windows only)")
                return@withContext
            }
            
            val currentDevices = _discoveredDevices.value.toMutableList()
            val existingIndex = currentDevices.indexOfFirst { 
                it.ipAddress == device.ipAddress && it.port == device.port 
            }
            
            if (existingIndex == -1) {
                currentDevices.add(device)
                _discoveredDevices.value = currentDevices
                Log.d(TAG, "Added device: ${device.hostname} at ${device.ipAddress}:${device.port}")
                
                // Notify callback for new device
                onDeviceDiscoveredCallback?.invoke(device)
            } else {
                // Update existing device if we have better info
                val existing = currentDevices[existingIndex]
                if (device.hostname != "Unknown" && existing.hostname == "Unknown") {
                    currentDevices[existingIndex] = device
                    _discoveredDevices.value = currentDevices
                    Log.d(TAG, "Updated device info: ${device.hostname}")
                }
            }
        }
    }
    /**
     * Manually resolve a device by IP (used by InitializationService)
     * Uses TCP socket check to verify reachability, returns minimal device info
     * Device details should be obtained through mDNS discovery instead
     */
    suspend fun resolveDeviceFromIp(ipAddress: String, port: Int = 5001): DiscoveredDevice {
        return withContext(Dispatchers.IO) {
            try {
                // Check if device is reachable via TCP socket
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ipAddress, port), 2000)
                }
                // Device is reachable, return minimal info
                // Full device info will be populated when discovered via mDNS
                DiscoveredDevice(
                    ipAddress = ipAddress,
                    port = port,
                    hostname = "Windows PC",
                    fullName = "Windows PC",
                    email = "N/A",
                    isConnected = true,
                    deviceType = "windows"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving device from IP: $ipAddress:$port", e)
                DiscoveredDevice(
                    ipAddress = ipAddress,
                    port = port,
                    hostname = "Windows PC",
                    fullName = "Windows PC", 
                    email = "N/A",
                    isConnected = false,
                    deviceType = "windows"
                )
            }
        }
    }
    
    /**
     * Get local IP address of this device
     */
    private fun getLocalIpAddress(): String? {
        if (localIpAddress != null) return localIpAddress
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    
                    // Skip loopback and IPv6 addresses
                    if (!address.isLoopbackAddress && address is InetAddress && address.hostAddress?.contains(':') == false) {
                        val ip = address.hostAddress
                        
                        // Prefer addresses starting with 192.168 or 10.0
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.0.") || ip.startsWith("172."))) {
                            localIpAddress = ip
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
    }
    
    /**
     * Check if the given IP address is this device's IP
     */
    private fun isSelfIpAddress(ipAddress: String): Boolean {
        val localIp = getLocalIpAddress()
        return localIp != null && localIp == ipAddress
    }
    
    /**
     * Check if the given service name belongs to this device
     * Android service names are like: "android-192-168-1-100"
     */
    private fun isSelfService(serviceName: String): Boolean {
        val localIp = getLocalIpAddress() ?: return false
        val expectedServiceName = "android-${localIp.replace('.', '-')}"
        val cleanName = serviceName.replace("\"", "")
        return cleanName == expectedServiceName || cleanName.startsWith("$expectedServiceName (")
    }
}
