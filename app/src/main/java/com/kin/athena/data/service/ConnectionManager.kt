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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kin.athena.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Follows the underlying network of the VPN: the system's default network, never a VPN.
 *
 * Athena is excluded from its own VPN (VpnConnectionClient disallows its own package), so its default network
 * callback reports the real default network (Wi-Fi, mobile data, ...), the one its upstream sockets use. Each real
 * change of that network (another Network or another transport) is handled once, as it happens:
 * - the network type for the per-app rules changes at once,
 * - the old network is gone (Wi-Fi off or out of range): the [Listener] resets all connections at once,
 * - another network became the default while the old one is still up (back on Wi-Fi from mobile data): the
 *   [Listener] keeps the sessions and only resets the apps blocked on the new network type,
 * - the [UnderlyingNetworkObserver] (the VpnService) sets the VPN's underlying network.
 * The initial event of the callback and repeated events for the same network (signal strength, bandwidth,
 * validation) change nothing.
 */
@Singleton
class ConnectionStateManager @Inject constructor(
    @ApplicationContext var context: Context,
    var networkManager: NetworkManager
) {
    /** The running tunnel (VpnConnectionServer). Called on the main thread. */
    interface Listener {
        /** The default network [lost] is gone, so all connections through it are dead. */
        fun onUnderlyingNetworkLost(lost: Network)

        /**
         * [network] of [type] became the default network while [previous] (null: there was none) is still up.
         * Connections on [previous] keep working.
         */
        fun onUnderlyingNetworkSwitched(previous: Network?, network: Network, type: NetworkManager.ConnectionType)
    }

    /** The VpnService that established the VPN. Called on the main thread, at once on every change. */
    interface UnderlyingNetworkObserver {
        fun onUnderlyingNetwork(network: Network?)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Read for every packet on the tunnel thread. Everything else is used on the main thread only.
    @Volatile
    private var currentConnectionType: NetworkManager.ConnectionType = networkManager.getCurrentConnectionType()

    private var underlyingNetwork: Network? = null
    // SystemClock.uptimeMillis() of the last change of the underlying network
    private var changedAt = 0L
    private var listener: Listener? = null
    private var observer: UnderlyingNetworkObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var initialEventPending = false
    // Debug logs only: capability updates of the same network come every few seconds
    private val sameNetworkLog = Logger.Throttle()

    // Forwarded DNS queries in a row without answer on the current underlying network, see onUpstreamDns
    private var dnsUnanswered = 0
    private var firstUnansweredAt = 0L
    private val noConnectivityReports = HashMap<Network, Long>()

    fun getCurrentConnectionType(): NetworkManager.ConnectionType {
        return currentConnectionType
    }

    /** Starts following the default network for a running tunnel. Its sessions start on the current network. */
    fun start(listener: Listener) = onMain {
        this.listener = listener
        val network = networkManager.getDefaultNetwork()
        val type = if (network == null) NetworkManager.ConnectionType.NONE else networkManager.getConnectionType(networkManager.getCapabilities(network))
        underlyingNetwork = network
        currentConnectionType = type
        changedAt = SystemClock.uptimeMillis()
        dnsUnanswered = 0
        Logger.debug { "[Conn] Tunnel started on default network $network ($type)" }
        if (networkCallback == null) watchDefaultNetwork()
        observer?.let { observer ->
            Logger.debug { "[Conn] Telling the VPN its underlying network $network (tunnel start)" }
            observer.onUnderlyingNetwork(network)
        }
    }

    /** Stops following the default network when the tunnel of [listener] stops. Safe from any thread. */
    fun stop(listener: Listener) = onMain {
        // A tunnel that was started again in the meantime has its own listener and keeps the callback
        if (this.listener !== listener) {
            Logger.debug { "[Conn] Ignoring stop from a previous tunnel" }
            return@onMain
        }
        this.listener = null
        underlyingNetwork = null
        networkCallback?.let { callback ->
            try {
                context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Logger.error("ConnectionStateManager: Cannot unregister the network callback: ${e.message}", e)
            }
        }
        networkCallback = null
        Logger.debug { "[Conn] Stopped watching the default network" }
    }

    /** Adds the VpnService that established the VPN. While a tunnel runs, it is told the current network at once. */
    fun addUnderlyingNetworkObserver(observer: UnderlyingNetworkObserver) = onMain {
        this.observer = observer
        if (listener != null) observer.onUnderlyingNetwork(underlyingNetwork)
    }

    fun removeUnderlyingNetworkObserver(observer: UnderlyingNetworkObserver) = onMain {
        if (this.observer === observer) this.observer = null
    }

    /**
     * Called on the tunnel thread when a DNS query that the engine forwarded at [sentAt] (SystemClock.uptimeMillis)
     * got no answer ([answered] false), or when answers come again ([answered] true).
     *
     * With a VPN, Android's own data stall detection does not see these queries (they are Athena's, not the system
     * resolver's), so a Wi-Fi that is still associated but passes nothing stays the default network for longer.
     * After [DNS_UNANSWERED_REPORT] unanswered queries in a row, sent on the same network within [DNS_WINDOW_MS],
     * Android is told the network has no connectivity, so it validates it again and can move to mobile data sooner.
     * At most once per [REPORT_INTERVAL_MS] per network.
     */
    fun onUpstreamDns(answered: Boolean, sentAt: Long) {
        handler.post {
            if (answered) {
                if (dnsUnanswered > 0) Logger.debug { "[Conn] Forwarded DNS answered again on $underlyingNetwork after $dnsUnanswered unanswered" }
                dnsUnanswered = 0
                return@post
            }
            val network = underlyingNetwork
            if (network == null || listener == null) {
                Logger.debug { "[Conn] Unanswered DNS query ignored: no default network or no tunnel" }
                return@post
            }
            // Sent before the network changed, so it says nothing about the current one
            if (sentAt < changedAt) {
                Logger.debug { "[Conn] Unanswered DNS query ignored: sent ${changedAt - sentAt} ms before the change to $network" }
                return@post
            }
            if (dnsUnanswered == 0 || sentAt - firstUnansweredAt > DNS_WINDOW_MS) {
                dnsUnanswered = 0
                firstUnansweredAt = sentAt
            }
            dnsUnanswered++
            Logger.debug { "[Conn] Forwarded DNS query got no answer on $network: $dnsUnanswered in a row within ${sentAt - firstUnansweredAt} ms" }
            if (dnsUnanswered < DNS_UNANSWERED_REPORT) return@post

            val now = SystemClock.uptimeMillis()
            noConnectivityReports.entries.removeAll { now - it.value >= REPORT_INTERVAL_MS }
            noConnectivityReports[network]?.let { reportedAt ->
                Logger.debug { "[Conn] Not reporting $network again, reported ${now - reportedAt} ms ago" }
                return@post
            }
            noConnectivityReports[network] = now
            try {
                context.getSystemService(ConnectivityManager::class.java)?.reportNetworkConnectivity(network, false)
                Logger.debug { "[Conn] reportNetworkConnectivity($network, false): $dnsUnanswered forwarded DNS queries in a row got no answer" }
            } catch (e: RuntimeException) {
                Logger.error("ConnectionStateManager: Cannot report no connectivity on $network: ${e.message}", e)
            }
        }
    }

    /**
     * Follows the default network with a NetworkCallback instead of the deprecated CONNECTIVITY_ACTION, which comes
     * twice per switch, late (after the apps already handled the change), and once more as a sticky broadcast when
     * the receiver is registered. Athena is excluded from its own VPN, so its default network is never the VPN.
     */
    private fun watchDefaultNetwork() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val callback = DefaultNetworkCallback()
        try {
            if (connectivityManager == null) throw IllegalStateException("No ConnectivityManager")
            // The first event is the current default network, which start() already has
            initialEventPending = true
            networkCallback = callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectivityManager.registerDefaultNetworkCallback(callback, handler)
            } else {
                connectivityManager.registerDefaultNetworkCallback(callback)
            }
            Logger.debug { "[Conn] Watching the default network with a NetworkCallback" }
        } catch (e: RuntimeException) {
            networkCallback = null
            Logger.error("ConnectionStateManager: Cannot register the default network callback: ${e.message}", e)
        }
    }

    // Main thread only. onAvailable or onCapabilitiesChanged of the default network.
    private fun onDefaultNetwork(network: Network, capabilities: NetworkCapabilities?, event: String) {
        val caps = capabilities ?: networkManager.getCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            Logger.debug { "[Conn] $event: ignoring VPN network $network" }
            return
        }
        val type = if (caps == null) NetworkManager.ConnectionType.NONE else networkManager.getConnectionType(caps)
        val previous = underlyingNetwork
        val previousType = currentConnectionType
        val initial = initialEventPending
        initialEventPending = false

        if (network == previous && type == previousType) {
            if (initial) {
                Logger.debug { "[Conn] $event $network ($type): kind=initial, the network the tunnel started on, nothing to do" }
            } else if (event == "onAvailable" || sameNetworkLog.shouldLog(network)) {
                Logger.debug { "[Conn] $event $network ($type): kind=same, nothing to do" }
            }
            return
        }

        // The rules follow the new network at once, before anything else
        currentConnectionType = type
        underlyingNetwork = network
        changedAt = SystemClock.uptimeMillis()
        dnsUnanswered = 0

        val listener = listener
        if (previous != null && previous != network && networkManager.getCapabilities(previous) == null) {
            // The old network went away without onLost (it is handled like onLost)
            Logger.debug { "[Conn] $event $network ($previousType->$type): kind=lost, previous $previous is gone" }
            listener?.onUnderlyingNetworkLost(previous)
        } else {
            Logger.debug {
                "[Conn] $event $network ($previousType->$type): kind=switch from ${previous ?: "none"}" +
                    "${if (previous != null && previous != network) " (still up)" else ""}${if (initial) ", first event of the callback" else ""}"
            }
            listener?.onUnderlyingNetworkSwitched(previous, network, type)
        }
        setUnderlyingNetwork(network)
    }

    // Main thread only
    private fun onDefaultNetworkLost(network: Network) {
        initialEventPending = false
        if (network != underlyingNetwork) {
            Logger.debug { "[Conn] onLost $network: kind=same, not the current default ${underlyingNetwork ?: "none"}" }
            return
        }
        val previousType = currentConnectionType
        // No network until the next onAvailable: the rules fail closed (AppRule)
        currentConnectionType = NetworkManager.ConnectionType.NONE
        underlyingNetwork = null
        changedAt = SystemClock.uptimeMillis()
        dnsUnanswered = 0
        Logger.debug { "[Conn] onLost $network ($previousType->NONE): kind=lost, no default network now" }
        // Reset before the VPN's underlying network changes below: the apps get the RST before Android tells them
        // about the change, and reconnect right away
        listener?.onUnderlyingNetworkLost(network)
        setUnderlyingNetwork(null)
    }

    // Main thread only
    private fun setUnderlyingNetwork(network: Network?) {
        val observer = observer
        if (observer == null) {
            Logger.debug { "[Conn] No VPN to tell its underlying network $network" }
            return
        }
        observer.onUnderlyingNetwork(network)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /**
     * Events come on the main thread (API 26+: registered with the main handler; API 24-25: handed over here, in
     * order). Events of a callback that was unregistered in the meantime are dropped.
     */
    private inner class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Android 7 does not always follow onAvailable with onCapabilitiesChanged
            onMainIfCurrent { onDefaultNetwork(network, null, "onAvailable") }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            onMainIfCurrent { onDefaultNetwork(network, networkCapabilities, "onCapabilitiesChanged") }
        }

        override fun onLost(network: Network) {
            // Only when no network is the default anymore. Another network that becomes the default comes as
            // onAvailable.
            onMainIfCurrent { onDefaultNetworkLost(network) }
        }

        private fun onMainIfCurrent(block: () -> Unit) {
            onMain { if (networkCallback === this) block() }
        }
    }

    private companion object {
        const val DNS_UNANSWERED_REPORT = 3
        // The unanswered queries counted for one report were all sent within this time
        const val DNS_WINDOW_MS = 15_000L
        // At most one report per network in this time
        const val REPORT_INTERVAL_MS = 30_000L
    }
}

@Singleton
class NetworkManager @Inject constructor(
    private val context: Context
) {
    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * The system's default network, never a VPN: Athena is excluded from its own VPN, so its active network is the
     * real one. Null if there is none.
     */
    fun getDefaultNetwork(): Network? {
        return try {
            val network = connectivityManager?.activeNetwork ?: return null
            if (getCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Logger.debug { "[Conn] The active network $network is a VPN, using none" }
                null
            } else network
        } catch (e: RuntimeException) {
            Logger.error("NetworkManager: Cannot read the active network: ${e.message}", e)
            null
        }
    }

    fun getCurrentConnectionType(): ConnectionType {
        val network = getDefaultNetwork() ?: return ConnectionType.NONE
        return getConnectionType(getCapabilities(network))
    }

    fun getCapabilities(network: Network): NetworkCapabilities? {
        return try {
            connectivityManager?.getNetworkCapabilities(network)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun getConnectionType(capabilities: NetworkCapabilities?): ConnectionType {
        return when {
            capabilities == null -> ConnectionType.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.MOBILE
            else -> ConnectionType.OTHER
        }
    }

    /**
     * NONE is no network at all. OTHER is a network that is neither Wi-Fi nor mobile data (Ethernet, Bluetooth
     * tethering, ...), which the per-app Wi-Fi and mobile rules do not cover.
     */
    enum class ConnectionType {
        WIFI, MOBILE, NONE, OTHER
    }
}
