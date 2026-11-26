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

package com.kin.athena.service.firewall.handler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import com.kin.athena.core.logging.Logger
import com.kin.athena.data.service.NetworkManager
import com.kin.athena.data.service.ScreenStateManager
import com.kin.athena.data.service.ScreenStateReceiver
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.service.firewall.model.FireWallModel
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.firewall.rule.AppRule
import com.kin.athena.service.firewall.rule.CustomDomainRule
import com.kin.athena.service.firewall.rule.DNSRule
import com.kin.athena.service.firewall.rule.FilterRule
import com.kin.athena.service.firewall.rule.FirewallRule
import com.kin.athena.service.firewall.rule.HTTPRule
import com.kin.athena.service.firewall.rule.LogRule
import com.kin.athena.service.firewall.rule.ScreenRule
import com.kin.athena.service.firewall.utils.isLocalIPv4
import com.kin.athena.service.vpn.network.transport.dns.DNSModel
import com.kin.athena.service.vpn.service.VpnConnectionServer
import com.kin.athena.service.vpn.network.util.NetworkConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable
import javax.inject.Inject

class RuleHandler @Inject constructor(
    private val rules: List<FirewallRule>,
    private val logUseCases: LogUseCases,
    private val preferencesUseCases: PreferencesUseCases,
    @ApplicationContext private val context: Context
) {
    var allowLocal = false
    // Debug builds only: one decision log per flow and result, see logDecision
    private val decisionLog = Logger.Throttle()

    init {
        // Network changes are followed by ConnectionStateManager while the tunnel runs
        val screenStateReceiver = ScreenStateReceiver()
        val intentFilter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }

        registerReceiver(context, screenStateReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        CoroutineScope(Dispatchers.IO).launch {
            preferencesUseCases.loadSettings.execute().fold(
                ifSuccess = { settings ->
                   allowLocal = settings.allowLocal
                   Logger.debug { "[Rules] allowLocal=${settings.allowLocal}, rules=${rules.map { it.javaClass.simpleName }}" }
                }
            )
        }
        
        rules.filterIsInstance<CustomDomainRule>().forEach { customDomainRule ->
            customDomainRule.setRuleHandler(this)
        }
    }

    suspend fun updateBlocklist(progressCallback: (suspend (Int) -> Unit)? = null) {
        Logger.debug { "[Rules] Updating the DNS blocklist" }
        rules.filterIsInstance<DNSRule>().forEach {
            it.updateBlocklist(progressCallback)
        }
    }

    fun updateLogs(enabled: Boolean) {
        Logger.debug { "[Rules] Packet logs enabled=$enabled" }
        rules.filterIsInstance<LogRule>().forEach {
            it.updateLogStatus(enabled)
        }
    }
    
    fun setDnsBlocking(enabled: Boolean) {
        Logger.debug { "[Rules] DNS blocking enabled=$enabled" }
        rules.filterIsInstance<DNSRule>().forEach { dnsRule ->
            if (enabled) {
                dnsRule.enableDnsBlocking()
            } else {
                dnsRule.disableDnsBlocking()
            }
        }
    }
    
    fun isDnsBlockingEnabled(): Boolean {
        return rules.filterIsInstance<DNSRule>().firstOrNull()?.isDnsBlockingEnabled() ?: false
    }

    fun setCustomDomainRules(enabled: Boolean) {
        Logger.debug { "[Rules] Custom domain rules enabled=$enabled" }
        rules.filterIsInstance<CustomDomainRule>().forEach { customDomainRule ->
            if (enabled) {
                customDomainRule.enableCustomDomainRules()
            } else {
                customDomainRule.disableCustomDomainRules()
            }
        }
    }
    
    fun isCustomDomainRulesEnabled(): Boolean {
        return rules.filterIsInstance<CustomDomainRule>().firstOrNull()?.isCustomDomainRulesEnabled() ?: false
    }

    fun updateScreenSetting() {
        Logger.debug { "[Rules] Reloading the screen-off rules" }
        rules.filterIsInstance<ScreenRule>().forEach {
            it.updateRules()
        }
    }

    fun updateHttpSettings() {
        Logger.debug { "[Rules] Reloading the HTTP rules" }
        rules.filterIsInstance<HTTPRule>().forEach {
            it.updateHTTPStatus()
        }
    }

    fun updateAppRules(updatedApplication: Application? = null) {
        Logger.debug { "[Rules] Reloading app and IP rules${updatedApplication?.let { " after a change of ${it.packageID}: wifi=${it.internetAccess} cell=${it.cellularAccess}" } ?: ""}" }
        rules.filterIsInstance<AppRule>().forEach {
            it.observePackages(updatedApplication)
        }
        rules.filterIsInstance<FilterRule>().forEach {
            it.observeIps()
        }
    }

    /** The uids that the per-app rules block on [type], see [AppRule.blockedUids]. */
    fun blockedUids(type: NetworkManager.ConnectionType): IntArray {
        return rules.filterIsInstance<AppRule>().firstOrNull()?.blockedUids(type) ?: IntArray(0)
    }

    fun handle(packet: FireWallModel, dnsModel: DNSModel? = null, bypassCheck: Boolean): Triple<Boolean, Int, FirewallResult> {
        if (bypassCheck) {
            return Triple(true, packet.uid, FirewallResult.ACCEPT)
        }

        if (allowLocal && dnsModel == null) {
            if (isLocalIPv4(packet.destinationIP)) {
                if (Logger.isDebug) logDecision(packet, null, FirewallResult.ACCEPT, "local")
                return Triple(true, packet.uid, FirewallResult.ACCEPT)
            }
        }

        var result = FirewallResult.ACCEPT
        var decidedBy: FirewallRule? = null
        packet.apply {
            for (rule in rules.dropLast(1)) {
                val ruleResult = rule.check(packet, dnsModel, logUseCases, result)
                if (result == FirewallResult.ACCEPT && ruleResult != FirewallResult.ACCEPT) {
                    result = ruleResult
                    decidedBy = rule
                }
            }
        }

        rules.last().check(packet, dnsModel, logUseCases, result)
        if (Logger.isDebug) logDecision(packet, dnsModel, result, decidedBy?.javaClass?.simpleName ?: "none")
        return Triple(result == FirewallResult.ACCEPT, packet.uid, result)
    }

    /**
     * Debug builds only. Called for every checked packet: a TCP SYN, but every UDP packet, so each flow (protocol,
     * source port, destination) is logged once per result and then at most once a minute.
     */
    private fun logDecision(packet: FireWallModel, dnsModel: DNSModel?, result: FirewallResult, decidedBy: String) {
        val protocol = when (packet.protocol) {
            NetworkConstants.TCP_PROTOCOL -> "TCP"
            NetworkConstants.UDP_PROTOCOL -> "UDP"
            NetworkConstants.ICMP_PROTOCOL -> "ICMP"
            else -> packet.protocol.toString()
        }
        val key = "$protocol:${packet.sourcePort}>${packet.destinationIP}:${packet.destinationPort}:$result"
        if (!decisionLog.shouldLog(key)) return
        Logger.debug {
            "[Pkt] $protocol uid=${packet.uid} :${packet.sourcePort} -> ${packet.destinationIP}:${packet.destinationPort}" +
                "${dnsModel?.domainName?.let { " dns=$it" } ?: ""} net=${packet.networkType} -> $result by=$decidedBy"
        }
    }
}