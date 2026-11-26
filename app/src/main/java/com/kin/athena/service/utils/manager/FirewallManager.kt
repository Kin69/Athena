/*
 * Copyright (C) 2025-2026 Vexzure
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

package com.kin.athena.service.utils.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Application
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.root.service.RootConnectionService
import com.kin.athena.service.shizuku.ShizukuConnectionService
import com.kin.athena.service.vpn.service.VpnConnectionServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class FirewallMode {
    ROOT,
    VPN,
    SHIZUKU,
}

interface FirewallService {
    fun updateRules(application: Application?)
    fun startService(context: Context)
    fun stopService(context: Context)
    fun updateLogs(enabled: Boolean)
    fun updateScreen(value: Boolean)
    suspend fun updateDomains(progressCallback: (suspend (Int) -> Unit)? = null)
    fun updateHttpSettings()
    fun setDnsBlocking(enabled: Boolean)
    fun isDnsBlockingEnabled(): Boolean
}

@Singleton
class FirewallStateManager @Inject constructor() {
    var mode: FirewallMode = FirewallMode.VPN
}

@Singleton
class FirewallManager @Inject constructor(
    private val firewallStateManager: FirewallStateManager,
    @ApplicationContext private val context: Context,
    private val rootService: FirewallService,
    private val vpnService: FirewallService,
    private val shizukuService: FirewallService
) {

    private val _rulesLoaded: MutableStateFlow<FirewallStatus> = MutableStateFlow(FirewallStatus.OFFLINE)
    val rulesLoaded: StateFlow<FirewallStatus> get() = _rulesLoaded.asStateFlow()


    var currentService: MutableState<FirewallService?> = mutableStateOf(null)


    init {
        updateService()
        bindService()
    }

    private fun updateService() {
        currentService.value = when(firewallStateManager.mode) {
            FirewallMode.ROOT -> rootService
            FirewallMode.VPN -> vpnService
            FirewallMode.SHIZUKU -> shizukuService
        }
    }

    private var isServiceBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Logger.debug { "[Fw] Bound to ${className.shortClassName} mode=${firewallStateManager.mode}" }
            currentService.value = when (firewallStateManager.mode) {
                FirewallMode.ROOT -> rootService
                FirewallMode.VPN -> vpnService
                FirewallMode.SHIZUKU -> shizukuService
            }
            isServiceBound = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Logger.debug { "[Fw] Service ${componentName.shortClassName} disconnected (process died?)" }
            isServiceBound = false
        }
    }

    fun updateScreen(value: Boolean) {
        Logger.debug { "[Fw] updateScreen value=$value service=${currentService.value?.javaClass?.simpleName}" }
        currentService.value?.updateScreen(value)
    }

    fun updateLogs(enabled: Boolean) {
        Logger.debug { "[Fw] updateLogs enabled=$enabled service=${currentService.value?.javaClass?.simpleName}" }
        currentService.value?.updateLogs(enabled)
    }

    suspend fun updateDomains(progressCallback: (suspend (Int) -> Unit)? = null) {
        Logger.debug { "[Fw] updateDomains service=${currentService.value?.javaClass?.simpleName}" }
        currentService.value?.updateDomains(progressCallback)
    }

    fun updateHttpSettings() {
        Logger.debug { "[Fw] updateHttpSettings service=${currentService.value?.javaClass?.simpleName}" }
        currentService.value?.updateHttpSettings()
    }

    fun update(state: FirewallStatus) {
        // Progress updates while loading are not logged
        if (_rulesLoaded.value.name() != state.name()) {
            Logger.debug { "[Fw] Status ${_rulesLoaded.value.name()} -> ${state.name()} by ${Logger.caller()}" }
        }
        _rulesLoaded.value = state
    }

    private fun bindService() {
        try {
            val intent = when (firewallStateManager.mode) {
                FirewallMode.ROOT -> Intent(context, RootConnectionService::class.java)
                FirewallMode.VPN -> Intent(context, VpnConnectionServer::class.java)
                FirewallMode.SHIZUKU -> Intent(context, ShizukuConnectionService::class.java)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Logger.debug { "[Fw] bindService(${intent.component?.shortClassName}) mode=${firewallStateManager.mode} -> $bound" }
        } catch (e: IllegalArgumentException) {
            Logger.debug("Cannot bind service: ${e.message}")
        }
    }

    private fun unbindService() {
        Logger.debug { "[Fw] unbindService bound=$isServiceBound" }
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    fun updateFirewallRules(application: Application?) {
        Logger.debug { "[Fw] updateFirewallRules ${application?.packageID ?: "all"} bound=$isServiceBound by ${Logger.caller()}" }
        if (isServiceBound) {
            currentService.value?.updateRules(application) ?: Logger.warn("Cannot update rules: Service is not bound.")
        } else {
            Logger.warn("Cannot update rules: Service is not bound.")
        }
    }

    fun setFirewallMode(mode: FirewallMode) {
        Logger.debug { "[Fw] setFirewallMode ${firewallStateManager.mode} -> $mode" }
        updateService()
        firewallStateManager.mode = mode
    }

    fun startFirewall() {
        Logger.debug { "[Fw] startFirewall mode=${firewallStateManager.mode} status=${_rulesLoaded.value.name()} by ${Logger.caller()}" }
        updateService()
        bindService()
        currentService.value?.startService(context) ?: Logger.warn("Service not available for start.")
    }

    fun stopFirewall() {
        Logger.debug { "[Fw] stopFirewall mode=${firewallStateManager.mode} status=${_rulesLoaded.value.name()} by ${Logger.caller()}" }
        updateService()
        unbindService()
        currentService.value?.stopService(context) ?: Logger.warn("Service not available for stop.")
    }
    
    /**
     * The system revoked the VPN (Settings > VPN > Disconnect, or another VPN app took over). Unbinds the VPN server,
     * as stopFirewall does, so it can stop: VpnConnectionClient then stops it, and the server ends the engine, closes
     * the tun and sets the status OFFLINE. Nothing starts it again until the user turns the firewall on.
     */
    fun onVpnRevoked() {
        Logger.debug { "[Fw] VPN revoked: mode=${firewallStateManager.mode} status=${_rulesLoaded.value.name()} bound=$isServiceBound" }
        if (firewallStateManager.mode != FirewallMode.VPN) return
        unbindService()
    }

    fun setDnsBlocking(enabled: Boolean) {
        Logger.debug { "[Fw] setDnsBlocking enabled=$enabled bound=$isServiceBound" }
        if (isServiceBound) {
            currentService.value?.setDnsBlocking(enabled) ?: Logger.warn("Cannot set DNS blocking: Service is not bound.")
        } else {
            Logger.warn("Cannot set DNS blocking: Service is not bound.")
        }
    }
    
    fun isDnsBlockingEnabled(): Boolean {
        return if (isServiceBound) {
            currentService.value?.isDnsBlockingEnabled() ?: false
        } else {
            Logger.warn("Cannot get DNS blocking status: Service is not bound.")
            false
        }
    }
}
