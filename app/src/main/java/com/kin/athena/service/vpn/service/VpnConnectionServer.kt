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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kin.athena.BuildConfig
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.core.logging.Logger
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
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class VpnConnectionServer : Service(), CoroutineScope by MainScope(), AppChangeCallback, FirewallService {

    @Inject lateinit var firewallManager: FirewallManager
    @Inject lateinit var ruleManager: RuleHandler
    @Inject lateinit var applicationUseCases: ApplicationUseCases
    @Inject lateinit var preferencesUseCases: PreferencesUseCases
    @Inject @ApplicationContext lateinit var appContext: Context


    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var tunnelManager: TunnelManager
    private var netGuardJob: Job? = null

    private var installedApplications: List<Application>? = null
    private val appChangeReceiver = AppChangeReceiver(this)
    private var isStoppedBySelf = false
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
        
        // Clear sessions directly if tunnel manager is initialized
        try {
            if (::tunnelManager.isInitialized) {
                Logger.info("Clearing sessions for rule update")
                Logger.debug { "[Vpn] clearSessions cause=rule update app=${application?.packageID ?: "all"} by ${Logger.caller()}" }
                tunnelManager.clearSessions()
            } else {
                Logger.debug { "[Vpn] Rule update without a tunnel, no sessions to clear" }
            }
        } catch (e: Exception) {
            Logger.debug(e) { "[Vpn] Clearing sessions for rule update failed" }
        }
        
        runBlocking {
            loadApplications()
            showStartNotification(installedApplications!!, preferencesUseCases, appContext)
        }
        
        if (::ruleManager.isInitialized) {
            ruleManager.updateAppRules()
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
                Logger.debug { "[Vpn] clearSessions cause=network change" }
                clearSessions()
                // For session clearing, we don't need to keep the service running
                // A running server stays only while it is bound, so this line shows whether the clear could stop it
                Logger.debug { "[Vpn] stopSelf after clear: tunnel=${::tunnelManager.isInitialized} engine=${netGuardJob?.isActive == true} foreground=$isForeground bound=$isBound" }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Clears all active sessions. This is useful when firewall rules are updated
     * to ensure previously allowed sessions are terminated and new rules are applied.
     */
    fun clearSessions() {
        try {
            if (isShuttingDown) {
                Logger.warn("Cannot clear sessions: VPN is shutting down")
                return
            }
            
            if (::tunnelManager.isInitialized) {
                Logger.info("Clearing all active sessions due to network change")
                val startedAt = SystemClock.uptimeMillis()
                tunnelManager.clearSessions()
                Logger.info("All sessions cleared successfully")
                Logger.debug { "[Vpn] Sessions cleared in ${SystemClock.uptimeMillis() - startedAt} ms" }
            } else {
                Logger.warn("TunnelManager not initialized for session clearing")
            }
        } catch (e: Exception) {
            Logger.error("Error clearing sessions: ${e.message}", e)
        }
    }


    private fun toggleAccess(packageName: String?, update: (Application) -> Application) {
        packageName?.let { pkg ->
            CoroutineScope(Dispatchers.IO).launch {
                applicationUseCases.getApplication.execute(pkg).fold(
                    ifSuccess = { app ->
                        val updatedApp = update(app)
                        applicationUseCases.updateApplication.execute(updatedApp).fold(
                            ifSuccess = {
                                ruleManager.updateAppRules(updatedApp)
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
        
        if (!tunnelManager.initialize()) {
            Logger.error("Failed to initialize Tunnel Manager")
            stopSelf()
            return
        }

        Logger.info("Tunnel Manager initialized successfully")
    }


    private fun startVpnCoroutines() {
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
        netGuardJob?.cancel()
        if (::tunnelManager.isInitialized) {
            tunnelManager.stop()
            tunnelManager.release()
        }
        vpnInterface?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.debug { "[Vpn] Server destroyed: stoppedBySelf=$isStoppedBySelf tunnel=${::tunnelManager.isInitialized} engine=${netGuardJob?.isActive == true} foreground=$isForeground" }
        isForeground = false

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
}


