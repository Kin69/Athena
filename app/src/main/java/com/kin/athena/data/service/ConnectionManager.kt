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

package com.kin.athena.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.kin.athena.core.logging.Logger
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.service.vpn.service.VpnConnectionServer
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@AndroidEntryPoint
class NetworkChangeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var networkManager: NetworkManager

    @Inject
    lateinit var connectionStateManager: ConnectionStateManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val connectionType = networkManager.getCurrentConnectionType()
            // The sticky broadcast comes right when the receiver is registered, not on a network change
            Logger.debug {
                "[Conn] ${intent.action} sticky=$isInitialStickyBroadcast " +
                    "type ${connectionStateManager.getCurrentConnectionType()}->$connectionType active=${networkManager.describeActiveNetwork()}"
            }
            connectionStateManager.updateConnectionType(connectionType)
        } else {
            Logger.debug { "[Conn] Ignoring broadcast ${intent.action}" }
        }
    }
}

@Singleton
class ConnectionStateManager @Inject constructor(
    @ApplicationContext var context: Context,
    var networkManager: NetworkManager
) {
    private var currentConnectionType: NetworkManager.ConnectionType = networkManager.getCurrentConnectionType()

    fun getCurrentConnectionType(): NetworkManager.ConnectionType {
        return currentConnectionType
    }

    fun updateConnectionType(connectionType: NetworkManager.ConnectionType) {
        Logger.debug { "[Conn] updateConnectionType $currentConnectionType->$connectionType by ${Logger.caller()}" }
        currentConnectionType = connectionType
        try {
            val clearSessionsServiceIntent = Intent(context, VpnConnectionServer::class.java).apply {
                action = NetworkConstants.ACTION_CLEAR_SESSIONS
            }
            
            // Use regular startService for session clearing since it's a quick operation
            // that doesn't require foreground service capabilities
            context.startService(clearSessionsServiceIntent)
            Logger.debug { "[Conn] Asked the VPN server to clear sessions (network change)" }
        } catch (e: Exception) {
            Logger.error("Failed to start VPN service for session clearing: ${e.message}", e)
        }
    }
}

@Singleton
class NetworkManager @Inject constructor(
    private val context: Context
) {

    fun getCurrentConnectionType(): ConnectionType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return ConnectionType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ConnectionType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.MOBILE
            else -> ConnectionType.NONE
        }
    }

    /** For debug logs: "123 [WIFI validated]", the active network with its transports, or "none". */
    fun describeActiveNetwork(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "none"
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return "$network [gone]"
            val parts = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "WIFI"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "CELLULAR"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "ETHERNET"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts += "validated"
            "$network [${parts.joinToString(" ")}]"
        } catch (e: RuntimeException) {
            "unknown (${e.message})"
        }
    }

    enum class ConnectionType {
        WIFI, MOBILE, NONE
    }
}