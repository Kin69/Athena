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
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.kin.athena.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Follows the underlying network of the VPN: the system's default network, never a VPN.
 *
 * Not registerDefaultNetworkCallback: an app's default-network request has no NOT_VPN, and Android lets a VPN satisfy
 * the requests of the app that owns it even when the VPN excludes that app (NetworkCapabilities.satisfiedByUids), so
 * that callback reports Athena's own VPN (seen on Android 10, 13 and 17). The callback here asks for NOT_VPN networks
 * only and follows the system default among them, the network Athena's upstream sockets use. Each real change of
 * that network (another Network or another rule type) is handled once, as it happens:
 * - the network type for the per-app rules changes at once,
 * - the old network is gone, or no longer validated (Wi-Fi off, out of range, weak Wi-Fi left behind): the
 *   [Listener] resets all connections,
 * - another network became the default while the old one still works (back on Wi-Fi from mobile data): the
 *   [Listener] resets the connections opened before the change (they run on the old network, which Android stops
 *   using) and those of the apps blocked on the new network type, and keeps the rest,
 * - the [UnderlyingNetworkObserver] (the VpnService) sets the VPN's underlying network.
 * The initial event of the callback and repeated events for the same network (signal strength, bandwidth) change
 * nothing.
 *
 * All state lives on one background thread ([handler]), so a busy main thread cannot delay a change.
 */
@Singleton
class ConnectionStateManager @Inject constructor(
    @ApplicationContext var context: Context,
    var networkManager: NetworkManager
) {
    /** The running tunnel (VpnConnectionServer). Called on the network thread. */
    interface Listener {
        /** The default network [lost] is gone (or no longer works), so all connections through it are dead. */
        fun onUnderlyingNetworkLost(lost: Network)

        /**
         * [network] of [type] became the default network at [changedAt] (SystemClock.uptimeMillis) while [previous]
         * still works. [previous] is null when there was no network, or when only the type of [network] changed.
         */
        fun onUnderlyingNetworkSwitched(previous: Network?, network: Network, type: NetworkManager.ConnectionType, changedAt: Long)
    }

    /** The VpnService that established the VPN. Called on the network thread, at once on every change. */
    interface UnderlyingNetworkObserver {
        fun onUnderlyingNetwork(network: Network?)
    }

    private val handler = Handler(HandlerThread("AthenaNetwork").apply { start() }.looper)

    // Read for every packet on the tunnel thread. Everything else is used on the network thread only.
    @Volatile
    private var currentConnectionType: NetworkManager.ConnectionType = networkManager.getCurrentConnectionType()

    private var underlyingNetwork: Network? = null
    // The VPN was told underlyingNetwork. Until the first callback event it is told null (follow the default).
    private var underlyingDeclared = false
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
    fun start(listener: Listener) {
        // The rules get the current type before the first packet, the rest happens on the network thread
        val network = networkManager.getDefaultNetwork()
        val type = if (network == null) NetworkManager.ConnectionType.NONE else networkManager.getConnectionType(networkManager.getCapabilities(network))
        currentConnectionType = type
        onHandler {
            this.listener = listener
            underlyingNetwork = network
            underlyingDeclared = false
            changedAt = SystemClock.uptimeMillis()
            dnsUnanswered = 0
            Logger.debug { "[Conn] Tunnel started on default network $network ($type)" }
            if (networkCallback == null) watchDefaultNetwork()
            // Not the start network yet: until the callback confirms it, the VPN follows the default network
            setUnderlyingNetwork(null, "tunnel start, until the first callback event")
        }
    }

    /** Stops following the default network when the tunnel of [listener] stops. Safe from any thread. */
    fun stop(listener: Listener) = onHandler {
        // A tunnel that was started again in the meantime has its own listener and keeps the callback
        if (this.listener !== listener) {
            Logger.debug { "[Conn] Ignoring stop from a previous tunnel" }
            return@onHandler
        }
        this.listener = null
        underlyingNetwork = null
        underlyingDeclared = false
        handler.removeCallbacksAndMessages(RECHECK_TOKEN)
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
    fun addUnderlyingNetworkObserver(observer: UnderlyingNetworkObserver) = onHandler {
        this.observer = observer
        if (listener != null) observer.onUnderlyingNetwork(if (underlyingDeclared) underlyingNetwork else null)
    }

    fun removeUnderlyingNetworkObserver(observer: UnderlyingNetworkObserver) = onHandler {
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
     * At most once per [REPORT_INTERVAL_MS] per network, and only for a network Android still sees as validated.
     * Only queries the engine forwarded to the user's DNS server count: answered locally (blocked domains) they
     * never reach the engine's forwarding, and VpnConnectionServer does not listen when that server is a LAN one.
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
            // A network Android already sees as without internet (not validated, captive portal) needs no report
            val caps = networkManager.getCapabilities(network)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                Logger.debug { "[Conn] Not reporting $network: ${if (caps == null) "gone" else "not validated or captive portal"} already" }
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
     * Follows the underlying default network with a NetworkCallback instead of the deprecated CONNECTIVITY_ACTION,
     * which comes twice per switch, late (after the apps already handled the change), and once more as a sticky
     * broadcast when the receiver is registered.
     * - API 31+: registerBestMatchingNetworkCallback for INTERNET + NOT_VPN. Android picks the best match the same
     *   way it picks the default network, so this follows the system default.
     * - API 24-30: registerNetworkCallback for INTERNET + NOT_VPN keeps every real network. The default among them
     *   is Athena's activeNetwork (correct for an app excluded from the VPN), checked again [RECHECK_MS] after each
     *   event because the active network can change just after the callback ([NetworkManager.pickUnderlying]).
     */
    private fun watchDefaultNetwork() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        // NetworkRequest.Builder already requires NOT_RESTRICTED, TRUSTED and NOT_VPN; added explicitly for clarity
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            if (connectivityManager == null) throw IllegalStateException("No ConnectivityManager")
            // The first event is the current default network, which start() already has
            initialEventPending = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = BestNetworkCallback()
                networkCallback = callback
                connectivityManager.registerBestMatchingNetworkCallback(request, callback, handler)
            } else {
                val callback = AllNetworksCallback()
                networkCallback = callback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    connectivityManager.registerNetworkCallback(request, callback, handler)
                } else {
                    connectivityManager.registerNetworkCallback(request, callback)
                }
            }
            Logger.debug { "[Conn] Watching the underlying default network with a NOT_VPN NetworkCallback (${networkCallback?.javaClass?.simpleName})" }
        } catch (e: RuntimeException) {
            networkCallback = null
            // The VPN stays on null underlying networks, which follows the default network by itself
            Logger.error("ConnectionStateManager: Cannot register the network callback, the rules keep the start network type: ${e.message}", e)
        }
    }

    // Network thread only. The callback's current default network and its capabilities.
    // [previousGone]: the callback already knows the previous network disconnected (API 24-30 onLost).
    private fun onDefaultNetwork(network: Network, capabilities: NetworkCapabilities?, event: String, previousGone: Boolean = false) {
        val caps = capabilities ?: networkManager.getCapabilities(network)
        if (caps == null) {
            // Gone already, or not known yet: onCapabilitiesChanged or onLost follows
            Logger.debug { "[Conn] $event $network without capabilities, waiting for the next event" }
            return
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            Logger.warn("ConnectionStateManager: $event delivered VPN network $network to a NOT_VPN callback, ignored")
            return
        }
        val type = networkManager.getConnectionType(caps)
        val previous = underlyingNetwork
        val previousType = currentConnectionType
        val initial = initialEventPending
        initialEventPending = false

        if (network == previous && type == previousType) {
            if (initial) {
                Logger.debug { "[Conn] Underlying default network $network ${networkManager.describe(caps)} (initial: the network the tunnel started on) by $event" }
            } else if (event == "onAvailable" || sameNetworkLog.shouldLog(network)) {
                Logger.debug { "[Conn] Underlying default network $network ${networkManager.describe(caps)} (same) by $event" }
            }
            if (!underlyingDeclared) setUnderlyingNetwork(network, "first callback event")
            return
        }

        // The rules follow the new network at once, before anything else
        currentConnectionType = type
        underlyingNetwork = network
        changedAt = SystemClock.uptimeMillis()
        dnsUnanswered = 0

        val listener = listener
        val previousCaps = if (previous != null && previous != network && !previousGone) networkManager.getCapabilities(previous) else null
        val previousWorks = previousCaps != null &&
            previousCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            previousCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (previous != null && previous != network && !previousWorks) {
            // The old network went away, or Android left it because it stopped working (weak Wi-Fi): every
            // connection on it is dead
            val why = if (previousCaps == null) "gone" else "still connected but ${networkManager.describe(previousCaps)}"
            Logger.debug { "[Conn] Underlying default network $network ${networkManager.describe(caps)} (lost: previous $previous $why, $previousType->$type) by $event" }
            listener?.onUnderlyingNetworkLost(previous)
        } else {
            val from = if (previous != null && previous != network) previous else null
            Logger.debug {
                "[Conn] Underlying default network $network ${networkManager.describe(caps)} (switch: from " +
                    "${if (from != null) "$from ${networkManager.describe(previousCaps)} still up" else if (previous == null) "none" else "the same network"}" +
                    ", $previousType->$type${if (initial) ", first event of the callback" else ""}) by $event"
            }
            listener?.onUnderlyingNetworkSwitched(from, network, type, changedAt)
        }
        setUnderlyingNetwork(network, "change")
    }

    // Network thread only
    private fun onDefaultNetworkLost(network: Network) {
        initialEventPending = false
        if (network != underlyingNetwork) {
            Logger.debug { "[Conn] Underlying default network ${underlyingNetwork ?: "none"} (same: $network lost, not the default)" }
            return
        }
        val previousType = currentConnectionType
        // No network until the next onAvailable: the rules fail closed (AppRule)
        currentConnectionType = NetworkManager.ConnectionType.NONE
        underlyingNetwork = null
        changedAt = SystemClock.uptimeMillis()
        dnsUnanswered = 0
        Logger.debug { "[Conn] Underlying default network none (lost: $network is gone, $previousType->NONE)" }
        listener?.onUnderlyingNetworkLost(network)
        setUnderlyingNetwork(null, "no network")
    }

    // Network thread only
    private fun setUnderlyingNetwork(network: Network?, why: String) {
        underlyingDeclared = network != null || why == "no network"
        val observer = observer
        if (observer == null) {
            Logger.debug { "[Conn] No VPN to tell its underlying network $network ($why)" }
            return
        }
        Logger.debug { "[Conn] Telling the VPN its underlying network $network ($why)" }
        observer.onUnderlyingNetwork(network)
    }

    private fun onHandler(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    /** API 31+: events come on the network thread (registered with [handler]). */
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class BestNetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (networkCallback === this) onDefaultNetwork(network, null, "onAvailable")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCallback === this) onDefaultNetwork(network, networkCapabilities, "onCapabilitiesChanged")
        }

        override fun onLost(network: Network) {
            // Only when no network matches anymore. Another network taking over comes as onAvailable.
            if (networkCallback === this) onDefaultNetworkLost(network)
        }
    }

    /**
     * API 24-30: there is no best matching callback, so this keeps every real network and picks the underlying
     * default itself, see [NetworkManager.pickUnderlying]. Events come on the network thread (API 26+) or are handed
     * to it in order (API 24-25).
     */
    private inner class AllNetworksCallback : ConnectivityManager.NetworkCallback() {
        // Network thread only
        private val networks = HashMap<Network, NetworkCapabilities>()

        override fun onAvailable(network: Network) {
            // Android 7 does not always follow onAvailable with onCapabilitiesChanged
            val caps = networkManager.getCapabilities(network)
            onHandlerIfCurrent {
                if (caps != null) networks[network] = caps
                update("onAvailable", gone = null)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            onHandlerIfCurrent {
                networks[network] = networkCapabilities
                update("onCapabilitiesChanged", gone = null)
            }
        }

        override fun onLost(network: Network) {
            onHandlerIfCurrent {
                networks.remove(network)
                update("onLost", gone = network)
            }
        }

        private fun update(event: String, gone: Network?) {
            val network = networkManager.pickUnderlying(networks)
            if (network == null) {
                underlyingNetwork?.let { onDefaultNetworkLost(it) }
            } else {
                onDefaultNetwork(network, networks[network], event, previousGone = gone != null && gone == underlyingNetwork)
            }
            // The active network can switch just after the event that caused it: look again shortly
            handler.removeCallbacksAndMessages(RECHECK_TOKEN)
            if (event != "recheck") {
                handler.postAtTime({ if (networkCallback === this) update("recheck", gone = null) }, RECHECK_TOKEN,
                    SystemClock.uptimeMillis() + RECHECK_MS)
            }
        }

        private fun onHandlerIfCurrent(block: () -> Unit) {
            onHandler { if (networkCallback === this) block() }
        }
    }

    private companion object {
        const val DNS_UNANSWERED_REPORT = 3
        // The unanswered queries counted for one report were all sent within this time
        const val DNS_WINDOW_MS = 15_000L
        // At most one report per network in this time
        const val REPORT_INTERVAL_MS = 60_000L
        // API 24-30: the active network is checked again this long after each callback event
        const val RECHECK_MS = 200L
        val RECHECK_TOKEN = Any()
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
     * real one (unlike its default network callback, see ConnectionStateManager). Null if there is none.
     */
    fun getDefaultNetwork(): Network? {
        return try {
            val network = connectivityManager?.activeNetwork ?: return null
            if (getCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Logger.warn("NetworkManager: The active network $network is a VPN, using none")
                null
            } else network
        } catch (e: RuntimeException) {
            Logger.error("NetworkManager: Cannot read the active network: ${e.message}", e)
            null
        }
    }

    /**
     * Picks the underlying default network out of [networks] (all real networks, API 24-30). Athena is excluded
     * from its own VPN, so its active network is the system default: that one wins when it is a candidate.
     * Otherwise (no default yet, or not in the list yet) this approximates Android's ranking: validated beats not
     * validated, then Ethernet beats Wi-Fi beats mobile data beats the rest.
     */
    fun pickUnderlying(networks: Map<Network, NetworkCapabilities>): Network? {
        val candidates = networks.filterValues { caps ->
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val active = try {
            connectivityManager?.activeNetwork
        } catch (e: RuntimeException) {
            null
        }
        if (active != null && active in candidates) return active
        return candidates.entries.maxByOrNull { (_, caps) -> rank(caps) }?.key
    }

    private fun rank(caps: NetworkCapabilities): Int {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
        val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0
        return validated + transport
    }

    /** For debug logs: "[WIFI validated]", the transports and validation of a network. */
    fun describe(caps: NetworkCapabilities?): String {
        if (caps == null) return "[gone]"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "WIFI"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "ETHERNET"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts += "BLUETOOTH"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts += "validated"
        return parts.joinToString(" ", "[", "]")
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

    /**
     * The type the per-app rules use. Mobile data is MOBILE. Wi-Fi and every other real network (Ethernet, USB or
     * Bluetooth tethering, ...) is WIFI, so the Wi-Fi rules apply to it. NONE is only "no network at all".
     */
    fun getConnectionType(capabilities: NetworkCapabilities?): ConnectionType {
        return when {
            capabilities == null -> ConnectionType.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            // Satellite (API 35+) is metered like mobile data and is covered by the mobile rules
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                (Build.VERSION.SDK_INT >= 35 && capabilities.hasTransport(TRANSPORT_SATELLITE)) -> ConnectionType.MOBILE
            else -> ConnectionType.WIFI
        }
    }

    private companion object {
        // NetworkCapabilities.TRANSPORT_SATELLITE, API 35
        const val TRANSPORT_SATELLITE = 10
    }

    enum class ConnectionType {
        WIFI, MOBILE, NONE
    }
}
