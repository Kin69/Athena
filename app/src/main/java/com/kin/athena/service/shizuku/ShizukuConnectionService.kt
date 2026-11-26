package com.kin.athena.service.shizuku

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.model.Log
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallManager
import com.kin.athena.service.utils.manager.FirewallService
import com.kin.athena.service.utils.notifications.showStartNotification
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import javax.inject.Inject

@AndroidEntryPoint
class ShizukuConnectionService : Service(), CoroutineScope by CoroutineScope(Dispatchers.IO), FirewallService {

    @Inject lateinit var applicationUseCases: ApplicationUseCases
    @Inject lateinit var preferencesUseCases: PreferencesUseCases
    @Inject lateinit var networkFilterUseCases: NetworkFilterUseCases
    @Inject lateinit var logUseCases: LogUseCases
    @Inject lateinit var firewallManager: FirewallManager
    @Inject @ApplicationContext lateinit var appContext: Context

    private var installedApplications: List<Application>? = null
    private var isLoggingEnabled = false
    private var tcpLoggerJob: kotlinx.coroutines.Job? = null
    private var udpLoggerJob: kotlinx.coroutines.Job? = null
    
    // Track logged connections to avoid duplicates
    private val loggedConnections = mutableSetOf<String>()

    private val binder = LocalBinder()

    // The AIDL-bound interface to your user service
    private var shizukuFirewallService: IShizukuFirewallService? = null
    private var isServiceBound = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuFirewallUserService::class.java.name)
        )
            .processNameSuffix("firewall_service")
            .debuggable(true)
        // version etc if needed
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Logger.debug("ShizukuConnectionService: Shizuku user service connected")
            shizukuFirewallService = IShizukuFirewallService.Stub.asInterface(service)
            isServiceBound = true

            // Once bound, ensure the firewall chain is enabled
            enableFirewallChainSafely()
            
            // Start logging if it was enabled before service was bound
            if (isLoggingEnabled) {
                Logger.debug("ShizukuConnectionService: Starting deferred packet logging")
                startPacketLogging()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Logger.warn("ShizukuConnectionService: Shizuku user service disconnected")
            shizukuFirewallService = null
            isServiceBound = false
            // Stop logging jobs since service is disconnected
            stopPacketLogging()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.info("ShizukuConnectionService: onStartCommand called")
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Logger.info("ShizukuConnectionService: onCreate")
        // Log to verify dependencies are injected
        Logger.debug("ShizukuConnectionService: logUseCases initialized: ${::logUseCases.isInitialized}")
        Logger.debug("ShizukuConnectionService: firewallManager initialized: ${::firewallManager.isInitialized}")
        firewallManager.update(FirewallStatus.OFFLINE)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.info("ShizukuConnectionService: onDestroy")
        firewallManager.update(FirewallStatus.OFFLINE)
        unbindUserService()
    }

    override fun startService(context: Context) {
        Logger.info("ShizukuConnectionService: startService called")
        firewallManager.update(FirewallStatus.LOADING(0f))
        context.startService(Intent(context, ShizukuConnectionService::class.java))
        launch {
            bindUserService()
        }
    }

    override fun stopService(context: Context) {
        Logger.info("ShizukuConnectionService: stopService called")
        
        stopPacketLogging()
        
        launch {
            disableFirewallChainSafely()
        }
        
        firewallManager.update(FirewallStatus.OFFLINE)
        unbindUserService()
        context.stopService(Intent(context, ShizukuConnectionService::class.java))
    }

    override fun updateRules(application: Application?) {
        Logger.debug { "[Shizuku] updateRules ${application?.packageID ?: "all (ignored)"} wifi=${application?.internetAccess} cell=${application?.cellularAccess}" }
        application?.let { app ->
            launch {
                setAppNetworking(app)
            }
        }
    }

    override fun updateLogs(enabled: Boolean) {
        Logger.info("ShizukuConnectionService: updateLogs($enabled)")
        isLoggingEnabled = enabled
        
        if (enabled) {
            Logger.info("Shizuku firewall logging started")
            startPacketLogging()
        } else {
            Logger.info("Shizuku firewall logging stopped")
            stopPacketLogging()
        }
    }

    override fun updateScreen(value: Boolean) {
        Logger.debug("ShizukuConnectionService: updateScreen($value) not implemented yet")
    }

    override suspend fun updateDomains(progressCallback: (suspend (Int) -> Unit)?) {
        Logger.debug("ShizukuConnectionService: updateDomains not implemented yet")
    }

    override fun updateHttpSettings() {
        Logger.debug("ShizukuConnectionService: updateHttpSettings not implemented yet")
    }

    override fun setDnsBlocking(enabled: Boolean) {
        Logger.debug("ShizukuConnectionService: setDnsBlocking($enabled) not implemented yet")
    }

    override fun isDnsBlockingEnabled(): Boolean {
        return false
    }

    private fun bindUserService() {
        if (!Shizuku.pingBinder()) {
            Logger.warn("ShizukuConnectionService: Shizuku not available yet")
            return
        }
        try {
            Shizuku.bindUserService(serviceArgs, connection)
            Logger.debug("ShizukuConnectionService: binding user service")
        } catch (e: Exception) {
            Logger.error("ShizukuConnectionService: failed to bind user service: ${e.message}")
        }
    }

    private fun unbindUserService() {
        if (isServiceBound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true)
                Logger.debug("ShizukuConnectionService: unbound user service")
            } catch (e: Exception) {
                Logger.error("ShizukuConnectionService: error unbinding: ${e.message}")
            } finally {
                isServiceBound = false
                shizukuFirewallService = null
            }
        }
    }

    private fun enableFirewallChainSafely() {
        val svc = shizukuFirewallService
        if (svc != null) {
            try {
                Logger.debug("ShizukuConnectionService: calling enableFirewallChain() on user service...")
                val result = svc.enableFirewallChain()
                Logger.debug("ShizukuConnectionService: enableFirewallChain() returned: $result")
                
                if (result) {
                    Logger.info("ShizukuConnectionService: firewall chain enabled")
                    // continue loading apps, etc.
                    firewallManager.update(FirewallStatus.LOADING(0.5f))
                    launch {
                        loadApplications()
                    }
                } else {
                    Logger.warn("ShizukuConnectionService: enableFirewallChain returned false")
                    firewallManager.update(FirewallStatus.OFFLINE)  // or error
                }
            } catch (e: Exception) {
                Logger.error("ShizukuConnectionService: exception enabling firewall chain: ${e.message}")
                firewallManager.update(FirewallStatus.OFFLINE)
            }
        } else {
            Logger.warn("ShizukuConnectionService: cannot enable chain — user service not bound yet")
        }
    }

    private fun disableFirewallChainSafely() {
        val svc = shizukuFirewallService
        if (svc != null) {
            try {
                Logger.debug("ShizukuConnectionService: calling disableFirewallChain() on user service...")
                val result = svc.disableFirewallChain()
                Logger.debug("ShizukuConnectionService: disableFirewallChain() returned: $result")
                
                if (result) {
                    Logger.info("ShizukuConnectionService: firewall chain disabled successfully")
                } else {
                    Logger.warn("ShizukuConnectionService: disableFirewallChain returned false")
                }
            } catch (e: Exception) {
                Logger.error("ShizukuConnectionService: exception disabling firewall chain: ${e.message}")
            }
        } else {
            Logger.warn("ShizukuConnectionService: cannot disable chain — user service not bound")
        }
    }

    private suspend fun loadApplications() {
        runBlocking {
            applicationUseCases.getApplications.execute().fold(
                ifSuccess = {
                    installedApplications = it
                },
                ifFailure = { err ->
                    Logger.error("ShizukuConnectionService: failed to load apps: ${err.message}")
                }
            )
        }
        firewallManager.update(FirewallStatus.LOADING(0.5f))
        installedApplications?.let { apps ->
            showStartNotification(apps, preferencesUseCases, appContext)
        }
        // mark fully online
        firewallManager.update(FirewallStatus.ONLINE)
        
        // Start logging automatically like root service does
        installedApplications?.let {
            // Ensure all dependencies are initialized before starting logging
            if (::logUseCases.isInitialized) {
                launch { 
                    isLoggingEnabled = true
                    startPacketLogging() 
                }
            } else {
                Logger.error("ShizukuConnectionService: Cannot start logging - logUseCases not initialized!")
            }
        }
    }

    private fun startPacketLogging() {
        if (!isLoggingEnabled) return
        
        // Cancel any existing jobs first
        stopPacketLogging()
        
        // Check if service is bound before starting
        if (!isServiceBound || shizukuFirewallService == null) {
            Logger.warn("Cannot start packet logging: Shizuku service not bound yet")
            return
        }
        
        tcpLoggerJob = launch {
            try {
                Logger.info("Starting TCP packet logging via Shizuku")
                monitorNetworkConnections("tcp")
            } catch (e: Exception) {
                Logger.error("TCP packet logging failed: ${e.message}")
            }
        }
        
        udpLoggerJob = launch {
            try {
                Logger.info("Starting UDP packet logging via Shizuku") 
                monitorNetworkConnections("udp")
            } catch (e: Exception) {
                Logger.error("UDP packet logging failed: ${e.message}")
            }
        }
    }
    
    private fun stopPacketLogging() {
        tcpLoggerJob?.cancel()
        udpLoggerJob?.cancel()
        tcpLoggerJob = null
        udpLoggerJob = null
        loggedConnections.clear()
        Logger.info("Stopped packet logging")
    }
    
    private suspend fun monitorNetworkConnections(protocol: String) {
        while (isLoggingEnabled && isServiceBound) {
            try {
                val svc = shizukuFirewallService
                if (svc == null) {
                    Logger.warn("Shizuku service became null during $protocol monitoring")
                    kotlinx.coroutines.delay(1000)
                    continue
                }
                
                val result = svc.executeCommand("cat /proc/net/$protocol")
                if (result.isNotEmpty()) {
                    Logger.debug("Received $protocol data: ${result.lines().size} lines")
                    parseNetworkConnections(result, protocol.uppercase())
                } else {
                    Logger.warn("Empty result from /proc/net/$protocol")
                }
                kotlinx.coroutines.delay(1000)
            } catch (e: Exception) {
                Logger.error("Error monitoring $protocol connections: ${e.message}")
                kotlinx.coroutines.delay(5000)
            }
        }
        Logger.info("Stopped monitoring $protocol connections")
    }
    
    private fun parseNetworkConnections(data: String, protocol: String) {
        if (!isLoggingEnabled) return
        
        launch {
            try {
                data.lines().drop(1).forEach { line ->
                    if (line.trim().isNotEmpty()) {
                        parseConnectionLine(line, protocol)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error parsing network connections: ${e.message}")
            }
        }
    }
    
    private fun parseConnectionLine(line: String, protocol: String) {
        try {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 8) return
            
            val localAddress = parts[1]
            val remoteAddress = parts[2]
            val uid = parts[7].toIntOrNull() ?: return
            
            val (sourceIP, sourcePort) = parseAddress(localAddress)
            val (destIP, destPort) = parseAddress(remoteAddress)
            
            if (destIP != "0.0.0.0" && destPort != "0") {
                // Create unique key for this connection to avoid duplicates
                val connectionKey = "$protocol:$uid:$destIP:$destPort"
                
                // Skip if already logged
                if (loggedConnections.contains(connectionKey)) {
                    return
                }
                
                val app = installedApplications?.firstOrNull { it.uid == uid }
                // Check if app has internet access enabled
                // For Shizuku firewall, we primarily check internetAccess flag
                val isAllowed = app?.internetAccess ?: true
                
                Logger.debug("Logging connection: $protocol UID=$uid $destIP:$destPort (allowed=$isAllowed)")
                
                val log = Log(
                    time = System.currentTimeMillis(),
                    protocol = protocol,
                    packageID = uid,
                    sourceIP = sourceIP,
                    destinationAddress = null,
                    sourcePort = sourcePort,
                    destinationIP = destIP,
                    destinationPort = destPort,
                    packetStatus = if (isAllowed) FirewallResult.ACCEPT else FirewallResult.DROP
                )
                
                launch {
                    if (::logUseCases.isInitialized) {
                        val result = logUseCases.addLog.execute(log)
                        result.fold(
                            ifSuccess = {
                                // Mark connection as logged only after successful save
                                loggedConnections.add(connectionKey)
                                Logger.debug("Successfully logged connection: $connectionKey")
                            },
                            ifFailure = { error ->
                                Logger.error("Failed to log connection: ${error.message}")
                            }
                        )
                    } else {
                        Logger.warn("Cannot log connection: logUseCases not initialized yet")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.debug("Error parsing connection line: ${e.message}")
        }
    }
    
    private fun parseAddress(address: String): Pair<String, String> {
        try {
            val (hexIP, hexPort) = address.split(":")
            val ip = hexToIP(hexIP)
            val port = hexPort.toInt(16).toString()
            return Pair(ip, port)
        } catch (e: Exception) {
            return Pair("0.0.0.0", "0")
        }
    }
    
    private fun hexToIP(hex: String): String {
        try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.reversed()
            return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } catch (e: Exception) {
            return "0.0.0.0"
        }
    }

    private suspend fun setAppNetworking(app: Application) {
        val allow = app.internetAccess && app.cellularAccess
        val svc = shizukuFirewallService
        if (svc != null && isServiceBound) {
            try {
                val success = svc.setPackageNetworking(app.packageID, allow)
                if (success) {
                    Logger.debug("ShizukuConnectionService: setPackageNetworking for ${app.packageID} = $allow succeeded")
                } else {
                    Logger.warn("ShizukuConnectionService: setPackageNetworking for ${app.packageID} = $allow failed")
                }
            } catch (e: Exception) {
                Logger.error("ShizukuConnectionService: exception setting package networking: ${e.message}")
            }
        } else {
            Logger.warn("ShizukuConnectionService: cannot set package networking — service not bound")
        }
    }

    inner class LocalBinder : Binder() {
        val service: ShizukuConnectionService
            get() = this@ShizukuConnectionService
    }
}
