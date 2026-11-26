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

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.kin.athena.R
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.core.logging.Logger
import com.kin.athena.data.service.ConnectionStateManager
import com.kin.athena.domain.model.Settings
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class VpnConnectionClient : VpnService(), ConnectionStateManager.UnderlyingNetworkObserver {

    @Inject @ApplicationContext lateinit var context: Context
    @Inject lateinit var preferencesUseCases: PreferencesUseCases
    @Inject lateinit var applicationUseCases: com.kin.athena.domain.usecase.application.ApplicationUseCases
    @Inject lateinit var domainCacheService: com.kin.athena.data.cache.DomainCacheService
    @Inject lateinit var connectionStateManager: ConnectionStateManager


    private var settings: Settings? = null
    private var isStoppedBySelf = false
    // Also read on ConnectionStateManager's network thread (onUnderlyingNetwork)
    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null
    private var fd: Int = -1


    fun loadSettings() {
        runBlocking {
            preferencesUseCases.loadSettings.execute().fold(
                ifSuccess = { settings = it },
                ifFailure = { Logger.error("Failed to fetch applications: ${it.message}") }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.debug { "[Vpn] Client created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a restart by the system (START_STICKY) or an always-on VPN start
        Logger.debug {
            val alwaysOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " alwaysOn=$isAlwaysOn lockdown=$isLockdownEnabled" else ""
            "[Vpn] Client onStartCommand action=${intent?.action} flags=$flags startId=$startId running=${vpnInterface != null}$alwaysOn"
        }
        intent?.action?.let { handleAction(it) } ?: Logger.warn("No action specified in intent.")
        return START_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            NetworkConstants.ACTION_START_VPN -> startVpnIfNotRunning()
            NetworkConstants.ACTION_STOP_VPN -> stopVpn()
            else -> Logger.warn("Unhandled action: $action")
        }
    }

    private fun startVpnIfNotRunning() {
        if (vpnInterface == null) {
            Logger.debug { "[Vpn] Client starting: no VPN interface yet" }
            try {
                // Initialize domain cache when VPN starts (lazy loading)
                domainCacheService.initializeGlobally()

                configureAndStartVpn()
            } catch (e: Exception) {
                Logger.error("Error starting VPN", e)
                onDestroy()
            }
        } else {
            Logger.info("VPN is already running.")
        }
    }

    private fun configureAndStartVpn() {
        val startedAt = SystemClock.uptimeMillis()
        vpnInterface = createVpnBuilder().establish()
        Logger.debug { "[Vpn] establish() -> ${if (vpnInterface != null) "interface" else "null (not prepared or revoked)"} in ${SystemClock.uptimeMillis() - startedAt} ms" }
        vpnInterface?.let {
            // Only the VpnService that established the VPN can set its underlying network
            connectionStateManager.addUnderlyingNetworkObserver(this)
            fd = it.detachFd()
            Logger.debug { "[Vpn] Detached fd=$fd, handing it to the server" }
            startVpnServiceWithFd(fd)
        } ?: run {
            Logger.error("Failed to establish VPN interface.")
            onDestroy()
        }
    }

    private fun createVpnBuilder() = Builder().apply {
        loadSettings()
        addDnsServer(NetworkConstants.VPN_DNS_ADDRESS)    // IPv4 DNS
        addAddress(settings!!.ipv4, 32)
        // IPv6 disabled - filtering not implemented, would allow unfiltered traffic
        // addAddress(settings!!.ipv6, 128)
        addRoute(NetworkConstants.ALL_TRAFFIC, 0)  // Route IPv4 traffic only
        // addRoute("::", 0)  // IPv6 routing disabled
        setMtu(NetworkConstants.MAX_PACKET_LEN)
        setBlocking(true)
        setSession(getString(R.string.app_name))
        addDisallowedApplication(context.packageName)
        addDisallowedApplication("com.google.android.projection.gearhead")
        // Debug logs only
        var bypassApps = 0

        // Add VPN bypass for apps that have bypassVpn=true
        runBlocking {
            applicationUseCases.getApplications.execute().fold(
                ifSuccess = { apps: List<com.kin.athena.domain.model.Application> ->
                    apps.filter { app: com.kin.athena.domain.model.Application -> app.bypassVpn }
                        .forEach { app: com.kin.athena.domain.model.Application ->
                            try {
                                addDisallowedApplication(app.packageID)
                                bypassApps++
                                Logger.info("VPN: Bypassing VPN for ${app.packageID}")
                            } catch (e: Exception) {
                                Logger.error("VPN: Failed to bypass VPN for ${app.packageID}: ${e.message}")
                            }
                        }
                },
                ifFailure = { error: com.kin.athena.core.utils.Error ->
                    Logger.error("VPN: Failed to load apps for VPN bypass: ${error.message}")
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // false: the VPN inherits meteredness from its underlying network (metered on mobile data, unmetered on
            // Wi-Fi), which is set explicitly on every change (onUnderlyingNetwork). true would be "always metered".
            setMetered(false)
        }
        Logger.debug {
            "[Vpn] Builder: address=${settings?.ipv4}/32 dns=${NetworkConstants.VPN_DNS_ADDRESS} route=${NetworkConstants.ALL_TRAFFIC}/0 " +
                "mtu=${NetworkConstants.MAX_PACKET_LEN} disallowed=${bypassApps + 2} ($bypassApps bypass apps)"
        }
    }

    private fun startVpnServiceWithFd(fd: Int) {
        val intent = createServiceIntent(fd, NetworkConstants.ACTION_START_VPN)
        Logger.debug { "[Vpn] Starting the server with fd=$fd (foreground=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O})" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpn(shouldStopSelf: Boolean = true) {
        Logger.debug { "[Vpn] Client stopping by ${Logger.caller()}: stopSelf=$shouldStopSelf fd=$fd interface=${vpnInterface != null}" }
        connectionStateManager.removeUnderlyingNetworkObserver(this)
        stopVpnService(fd)
        vpnInterface.closeSafely()
        vpnInterface = null
        if (shouldStopSelf) {
            isStoppedBySelf = true
            stopSelf()
        } else {
            Logger.info("Firewall was stopped, possibly by another VPN.")
        }
    }

    /**
     * ConnectionStateManager, network thread: the default network changed. The VPN declares it as its underlying
     * network, so its capabilities (transport, metered, ...) follow it and the apps get onCapabilitiesChanged for the
     * VPN at each change. Null while there is no network.
     */
    override fun onUnderlyingNetwork(network: Network?) {
        if (vpnInterface == null) {
            Logger.debug { "[Vpn] Not setting the underlying network $network: the VPN is not established" }
            return
        }
        try {
            val networks = network?.let { arrayOf(it) }
            val ok = setUnderlyingNetworks(networks)
            Logger.debug { "[Vpn] setUnderlyingNetworks(${networks?.joinToString(prefix = "[", postfix = "]") ?: "null"}) -> $ok" }
            if (!ok) Logger.warn("Could not set the VPN's underlying network $network")
        } catch (e: RuntimeException) {
            Logger.error("Could not set the VPN's underlying network $network: ${e.message}", e)
        }
    }

    private fun stopVpnService(fd: Int) {
        try {
            val intent = createServiceIntent(fd, NetworkConstants.ACTION_STOP_VPN)
            val stopped = stopService(intent)
            Logger.debug { "[Vpn] stopService(server) -> $stopped" }
        } catch (e: Exception) {
            Logger.error("Failed to stop VPN service: ${e.message}", e)
        }
    }

    private fun createServiceIntent(fd: Int, action: String) = Intent(this, VpnConnectionServer::class.java).apply {
        this.action = action
        putExtra("vpnInterfaceFd", fd)
    }

    private fun ParcelFileDescriptor?.closeSafely() {
        try {
            this?.close()
        } catch (e: IOException) {
            Logger.error("Failed to close VPN interface: ${e.localizedMessage}", e)
        }
    }

    override fun onRevoke() {
        Logger.debug { "[Vpn] onRevoke: another VPN took over or the user revoked the permission" }
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.debug { "[Vpn] Client destroyed, stoppedBySelf=$isStoppedBySelf" }
        if (!isStoppedBySelf) {
            stopVpn(false)
        }
    }
}
