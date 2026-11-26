/*
 * Copyright (C) 2024 Vexzure
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.kin.athena.service.vpn.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kin.athena.BuildConfig
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.core.logging.Logger
import com.kin.athena.data.service.ConnectionStateManager
import com.kin.athena.data.service.NetworkManager
import com.kin.athena.domain.model.Application
import com.kin.athena.service.utils.receiver.AppChangeCallback
import com.kin.athena.service.utils.receiver.AppChangeReceiver
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.service.firewall.handler.RuleHandler
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallManager
import com.kin.athena.service.utils.manager.FirewallService
import com.kin.athena.service.utils.manager.VpnManager
import com.kin.athena.service.utils.notifications.showInstallNotification
import com.kin.athena.service.utils.notifications.showStartNotification
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class VpnConnectionServer : Service(), CoroutineScope by MainScope(), AppChangeCallback, FirewallService,
    ConnectionStateManager.Listener {

    @Inject lateinit var firewallManager: FirewallManager
    @Inject lateinit var ruleManager: RuleHandler
    @Inject lateinit var applicationUseCases: ApplicationUseCases
    @Inject lateinit var preferencesUseCases: PreferencesUseCases
    @Inject lateinit var connectionStateManager: ConnectionStateManager
    @Inject @ApplicationContext lateinit var appContext: Context


    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var tunnelManager: TunnelManager
    private var netGuardJob: Job? = null

    private var installedApplications: List<Application>? = null
    private val appChangeReceiver = AppChangeReceiver(this)
    private var isStoppedBySelf = false
    // Also read on the network thread (ConnectionStateManager.Listener)
    @Volatile
    private var isShuttingDown = false
    // Debug logs only
    private var isForeground = false
    private var isBound = false

    inner class VpnConnection : Binder() {
        val service: VpnConnectionServer
            get() = this@VpnConnectionServer
    }

    fun loadApplications() {
        runBlocking {
            applicationUseCases.getApplications.execute().fold(
                ifSuccess = { apps ->
                    installedApplications = apps
                    Logger.debug { "[Vpn] Loaded ${apps.size} apps: wifiBlocked=${apps.count { !it.internetAccess }} cellBlocked=${apps.count { !it.cellularAccess }} bypass=${apps.count { it.bypassVpn }}" }
                },
                ifFailure = { Logger.error("Failed to fetch applications: ${it.message}") }
            )
        }
    }

    override fun startService(context: Context) {
        VpnManager.start(context)
    }

    override fun stopService(context: Context) {
        VpnManager.stop(context)
    }

    override fun updateLogs(enabled: Boolean) {
        ruleManager.updateLogs(enabled)
    }

    override fun updateScreen(value: Boolean) {
        ruleManager.updateScreenSetting()
    }


    override suspend fun updateDomains(progressCallback: (suspend (Int) -> Unit)?) {
        ruleManager.updateBlocklist(progressCallback)
    }

    override fun updateHttpSettings() {
        ruleManager.updateHttpSettings()
    }
    
    override fun setDnsBlocking(enabled: Boolean) {
        ruleManager.setDnsBlocking(enabled)
    }
    
    override fun isDnsBlockingEnabled(): Boolean {
        return ruleManager.isDnsBlockingEnabled()
    }


    override fun updateRules(application: Application?) {
        Logger.info("Updating firewall rules${if (application != null) " for ${application.packageID}" else ""}")
        
        runBlocking {
            loadApplications()
            showStartNotification(installedApplications!!, preferencesUseCases, appContext)
        }
        
        if (::ruleManager.isInitialized) {
            val loading = ruleManager.updateAppRules(application)
            // Reset only once the new rules are loaded: an app reconnecting right after the reset must meet the new
            // rules, or its new connection is let through and never checked again (only SYNs are checked)
            launch(Dispatchers.IO) {
                loading.joinAll()
                resetForRuleUpdate(application)
            }
            Logger.info("Firewall rules updated successfully")
        } else {
            Logger.error("Attempted to update rules before ruleManager was initialized.")
        }
    }

    override fun onAppInstalled(packageName: String?) {

        packageName?.let { pkg ->
            CoroutineScope(Dispatchers.IO).launch {
                showInstallNotification(
                    pkg,
                    applicationUseCases,
                    preferencesUseCases,
                    useRootMode = false,
                    firewallManager = firewallManager
                )
            }
        }
    }

    override fun onAppUninstalled(packageName: String?) {

    }

    override fun onBind(intent: Intent): IBinder {
        isBound = true
        Logger.debug { "[Vpn] Server bound" }
        return VpnConnection()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        Logger.debug { "[Vpn] Server unbound" }
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        Logger.debug { "[Vpn] Server created" }
    }

    @SuppressLint("MissingPermission")
    private fun toggleWifiAccess(packageName: String?) {
        toggleAccess(packageName) { app -> app.copy(internetAccess = !app.internetAccess) }
    }

    @SuppressLint("MissingPermission")
    private fun toggleCellularAccess(packageName: String?) {
        toggleAccess(packageName) { app -> app.copy(cellularAccess = !app.cellularAccess) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a restart by the system after the process was killed (START_STICKY)
        Logger.debug {
            "[Vpn] Server onStartCommand action=${intent?.action} flags=$flags startId=$startId " +
                "tunnel=${::tunnelManager.isInitialized} engine=${netGuardJob?.isActive == true} foreground=$isForeground bound=$isBound"
        }
        when (intent?.action) {
            NetworkConstants.ACTION_TOGGLE_CELLURAL -> toggleWifiAccess(intent.getStringExtra("packageName"))
            NetworkConstants.ACTION_TOGGLE_WIFI -> toggleCellularAccess(intent.getStringExtra("packageName"))
            NetworkConstants.ACTION_STOP_VPN -> stopVpn()
            NetworkConstants.ACTION_START_VPN -> {
                startVpn(intent)
                return START_STICKY
            }
            NetworkConstants.ACTION_CLEAR_SESSIONS -> {
                // Nothing in the app sends this anymore: network changes come from ConnectionStateManager
                Logger.debug { "[Vpn] clearSessions cause=ACTION_CLEAR_SESSIONS" }
                clearSessions()
                // Never stop a running server here. Only an instance created just for this intent has nothing to do.
                if (!::tunnelManager.isInitialized) {
                    Logger.debug { "[Vpn] stopSelf($startId) after ACTION_CLEAR_SESSIONS: no tunnel in this instance" }
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * Resets all TCP connections and frees all sessions. The packet thread does it, this returns at once.
     */
    fun clearSessions() {
        try {
            if (isShuttingDown) {
                Logger.warn("Cannot clear sessions: VPN is shutting down")
                return
            }
            
            if (::tunnelManager.isInitialized) {
                tunnelManager.clearSessions()
            } else {
                Logger.warn("TunnelManager not initialized for session clearing")
            }
        } catch (e: Exception) {
            Logger.error("Error clearing sessions: ${e.message}", e)
        }
    }

    // After a rule update: one app changed, so only its connections are reset; otherwise all of them
    private fun resetForRuleUpdate(application: Application?) {
        try {
            if (isShuttingDown || !::tunnelManager.isInitialized) {
                Logger.debug { "[Vpn] Rule update without a running tunnel, no sessions to reset" }
            } else if (application != null) {
                Logger.debug { "[Vpn] Rule update for ${application.packageID}: resetting the TCP connections of uid ${application.uid} (rules loaded)" }
                tunnelManager.resetSessions(intArrayOf(application.uid))
            } else {
                Logger.debug { "[Vpn] clearSessions cause=rule update app=all (rules loaded)" }
                tunnelManager.clearSessions()
            }
        } catch (e: Exception) {
            Logger.debug(e) { "[Vpn] Resetting sessions for rule update failed" }
        }
    }

    // ConnectionStateManager.Listener, network thread
    override fun onUnderlyingNetworkLost(lost: Network) {
        // Every upstream socket on the lost network is dead: reset the apps' connections now, so they reconnect on
        // the next network at once instead of waiting for a timeout
        Logger.debug { "[Vpn] Network change kind=lost: $lost is gone, resetting all TCP connections and freeing all sessions" }
        clearSessions()
    }

    // ConnectionStateManager.Listener, network thread
    override fun onUnderlyingNetworkSwitched(previous: Network?, network: Network, type: NetworkManager.ConnectionType, changedAt: Long): Boolean {
        // The old network still works, but Android stops using it: after a short linger it black-holes the
        // connections on it without telling anyone. Every TCP connection opened before the change was connect()ed on
        // it, so reset those now; the apps reconnect on the new network. Connections opened since keep running,
        // except those of apps blocked on the new network type. UDP follows the default network by itself.
        val uids = ruleManager.blockedUids(type)
        Logger.debug {
            "[Vpn] Network change kind=switch: ${previous ?: "none"} -> $network ($type), " +
                "${if (previous != null) "resetting the TCP connections opened before the change and" else "keeping sessions,"} " +
                "resetting the TCP connections of ${uids.size} uids blocked on $type${if (uids.isNotEmpty()) ": ${uids.joinToString(",")}" else ""}"
        }
        if (isShuttingDown || !::tunnelManager.isInitialized) return false
        if (previous != null) tunnelManager.resetSessionsBefore(changedAt)
        if (uids.isNotEmpty()) tunnelManager.resetSessions(uids)
        // The engine reports the written RSTs to ConnectionStateManager.onResetBeforeDone
        return previous != null
    }


    private fun toggleAccess(packageName: String?, update: (Application) -> Application) {
        packageName?.let { pkg ->
            CoroutineScope(Dispatchers.IO).launch {
                applicationUseCases.getApplication.execute(pkg).fold(
                    ifSuccess = { app ->
                        val updatedApp = update(app)
                        applicationUseCases.updateApplication.execute(updatedApp).fold(
                            ifSuccess = {
                                ruleManager.updateAppRules(updatedApp).joinAll()
                                resetForRuleUpdate(updatedApp)
                                showInstallNotification(
                                    pkg,
                                    applicationUseCases,
                                    preferencesUseCases,
                                    useRootMode = true,
                                    firewallManager = firewallManager
                                )
                            },
                            ifFailure = { Logger.error("Failed to update application: ${it.message}") }
                        )
                    },
                    ifFailure = { Logger.error("Failed to fetch application: ${it.message}") }
                )
            }
        }
    }

    private fun startVpn(intent: Intent) {
        val vpnInterfaceFd = intent.getIntExtra("vpnInterfaceFd", -1)
        Logger.debug { "[Vpn] Server startVpn fd=$vpnInterfaceFd tunnelBefore=${::tunnelManager.isInitialized} engineBefore=${netGuardJob?.isActive == true}" }
        if (vpnInterfaceFd == -1) Logger.debug { "[Vpn] START_VPN without an fd, nothing started" }
        if (vpnInterfaceFd != -1) {
            // Start foreground service immediately to avoid timing issues
            runBlocking {
                loadApplications()
                showStartNotification(installedApplications!!, preferencesUseCases)
            }
            isForeground = true
            Logger.debug { "[Vpn] Server in the foreground" }
            
            vpnInterface = ParcelFileDescriptor.adoptFd(vpnInterfaceFd)
            vpnInterface?.let {
                initializeVpnComponents(it)
                startVpnCoroutines()
                appChangeReceiver.register(this)
            } ?: run {
                Logger.error("Invalid VPN interface file descriptor received. Service is stopping.")
                stopSelf()
            }
        }
    }

    private fun initializeVpnComponents(vpnInterface: ParcelFileDescriptor) {
        // Get DNS servers from settings
        val (dnsServerV4, dnsServerV6) = runBlocking {
            preferencesUseCases.loadSettings.execute().fold(
                ifSuccess = { Pair(it.dnsServer1, it.dnsServer1v6) },
                ifFailure = {
                    Logger.debug { "[Vpn] Settings not loaded (${it.message}), using the default DNS servers" }
                    Pair("9.9.9.9", "2620:fe::fe")
                }
            )
        }
        Logger.debug { "[Vpn] Upstream DNS v4=$dnsServerV4 v6=$dnsServerV6" }

        // Initialize TunnelManager with the injected RuleHandler and user's DNS servers
        tunnelManager = TunnelManager(ruleManager, dnsServerV4, dnsServerV6)
        // A DNS server on the LAN (the router, a Pi-hole) says nothing about the internet connection, so its silence
        // must not make Android drop a working Wi-Fi
        if (isLanAddress(dnsServerV4)) {
            Logger.debug { "[Vpn] Not reporting unanswered DNS to Android: DNS server $dnsServerV4 is a LAN address" }
        } else {
            tunnelManager.upstreamDnsListener = connectionStateManager::onUpstreamDns
        }
        tunnelManager.resetBeforeListener = connectionStateManager::onResetBeforeDone
        
        if (!tunnelManager.initialize()) {
            Logger.error("Failed to initialize Tunnel Manager")
            stopSelf()
            return
        }

        Logger.info("Tunnel Manager initialized successfully")
    }


    // A numeric private, link-local, loopback or carrier-grade NAT address. Never resolves a name.
    private fun isLanAddress(address: String): Boolean {
        if (address.isEmpty() || !address.all { it.isDigit() || it in "abcdefABCDEF.:" }) return false
        return try {
            val inet = InetAddress.getByName(address)
            val bytes = inet.address
            inet.isSiteLocalAddress || inet.isLinkLocalAddress || inet.isLoopbackAddress || inet.isAnyLocalAddress ||
                (bytes.size == 4 && bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 64) ||
                (bytes.size == 16 && bytes[0].toInt() and 0xfe == 0xfc)
        } catch (e: Exception) {
            false
        }
    }

    private fun startVpnCoroutines() {
        // Follow the default network from now on, before the first packet is checked
        connectionStateManager.start(this)
        launch { if (::firewallManager.isInitialized) { firewallManager.update(FirewallStatus.ONLINE) } }
        launch(Dispatchers.IO) { ruleManager.updateAppRules() }
        
        // Start Tunnel Manager VPN processing. The native engine logs everything in debug builds; release builds keep
        // only warnings and errors. Below ANDROID_LOG_WARN it also checks IPv4 header checksums, as NetGuard does in debug.
        val logLevel = if (BuildConfig.DEBUG) Log.VERBOSE else Log.WARN
        tunnelManager.start(logLevel)
        
        netGuardJob = launch(Dispatchers.IO) {
            val startedAt = SystemClock.uptimeMillis()
            try {
                vpnInterface?.let { vpnFd ->
                    Logger.info("Starting Tunnel Manager packet processing")
                    Logger.debug { "[Vpn] Tunnel thread started on fd=${vpnFd.fd} (${Thread.currentThread().name})" }
                    tunnelManager.run(vpnFd.fd, forwardDns = true, rcode = 3)
                    Logger.debug { "[Vpn] Tunnel thread on fd=${vpnFd.fd} ended after ${SystemClock.uptimeMillis() - startedAt} ms" }
                } ?: Logger.debug { "[Vpn] Tunnel thread not started: no VPN interface" }
            } catch (e: Exception) {
                Logger.error("Tunnel Manager processing error: ${e.message}", e)
            }
        }
    }


    private fun closeResources() {
        Logger.debug { "[Vpn] Closing resources: engine=${netGuardJob?.isActive == true} tunnel=${::tunnelManager.isInitialized}" }
        isShuttingDown = true
        connectionStateManager.stop(this)
        netGuardJob?.cancel()
        if (::tunnelManager.isInitialized) {
            tunnelManager.stop()
            tunnelManager.release()
        }
        closeTunAfterEngine()
    }

    /**
     * Closes the tun once the engine's loop has ended (review d2): the loop can still be finishing an iteration that
     * writes to the tun after jni_stop/jni_done, and a closed fd number can already belong to another file or socket.
     * The loop ends within milliseconds of jni_stop; if it does not, the tun is closed after [TUN_CLOSE_TIMEOUT_MS]
     * anyway, so the VPN never stays up.
     */
    private fun closeTunAfterEngine() {
        val tun = vpnInterface ?: return
        vpnInterface = null
        val job = netGuardJob
        val closed = AtomicBoolean(false)
        val requestedAt = SystemClock.uptimeMillis()
        val close = { why: String ->
            if (closed.compareAndSet(false, true)) {
                try {
                    tun.close()
                    Logger.debug { "[Vpn] Closed the tun ${SystemClock.uptimeMillis() - requestedAt} ms after the stop: $why" }
                } catch (e: IOException) {
                    Logger.error("Error closing the tun: ${e.localizedMessage}", e)
                }
            }
        }
        if (job == null || job.isCompleted) {
            close("no engine loop running")
            return
        }
        job.invokeOnCompletion { close("the engine loop ended") }
        Handler(Looper.getMainLooper()).postDelayed({ close("the engine loop did not end within $TUN_CLOSE_TIMEOUT_MS ms") }, TUN_CLOSE_TIMEOUT_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.debug { "[Vpn] Server destroyed: stoppedBySelf=$isStoppedBySelf tunnel=${::tunnelManager.isInitialized} engine=${netGuardJob?.isActive == true} foreground=$isForeground" }
        isForeground = false
        if (::connectionStateManager.isInitialized) connectionStateManager.stop(this)

        // Immediately stop foreground to prevent timeout exception
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        // Do cleanup in background to avoid blocking main thread
        if (!isStoppedBySelf) {
            Thread {
                try {
                    stopVpn()
                } catch (e: Exception) {
                    Logger.error("Error during service cleanup: ${e.message}", e)
                }
            }.start()
        }
    }


    private fun stopVpn() {
        Logger.debug { "[Vpn] Server stopVpn by ${Logger.caller()}: status=${if (::firewallManager.isInitialized) firewallManager.rulesLoaded.value.name() else "?"}" }
        appChangeReceiver.unregister(this)
        if (::firewallManager.isInitialized) {
            if (firewallManager.rulesLoaded.value.name() == FirewallStatus.ONLINE.name()) {
                firewallManager.update(FirewallStatus.OFFLINE)
                coroutineContext.cancel()
                try {
                    closeResources()
                } catch (e: IOException) {
                    Logger.error("Error closing resources: ${e.localizedMessage}", e)
                }

                // stopForeground is now called in onDestroy() to prevent timeout
                Logger.info("Vpn server is stopped")
                isStoppedBySelf = true
                stopSelf()
            } else {
                Logger.debug { "[Vpn] Server not ONLINE, nothing stopped" }
            }
        } else {
            Logger.warn("Vpn server is not running")
        }
    }

    private companion object {
        // closeTunAfterEngine: the longest wait for the engine's loop to end before the tun is closed anyway
        const val TUN_CLOSE_TIMEOUT_MS = 2_000L
    }
}


