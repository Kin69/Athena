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

package com.kin.athena.service.firewall.rule

import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Ip
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import com.kin.athena.service.firewall.model.FireWallModel
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.vpn.network.transport.dns.DNSModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

class FilterRule @Inject constructor(
    private val networkFilterUseCases: NetworkFilterUseCases
) : FirewallRule {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ips: List<Ip>? = null

    init {
        // One collector for the life of the rule: it follows every change of the IP list by itself
        scope.launch {
            networkFilterUseCases.getIps.execute().fold(
                ifFailure = { Logger.error("Cannot observe the IP filter list: ${it.message}") },
                ifSuccess = {
                    it.collect { ip ->
                        ips = ip
                        Logger.debug { "[Rules] IP filter list loaded: ${ip.size} entries" }
                    }
                }
            )
        }
    }

    /**
     * Reads the IP list once more, for a rule change that resets connections only after the new rules are loaded
     * (RuleHandler.updateAppRules). Completes when the list is loaded. Before, every rule change started another
     * collector that never ended, and the reset did not wait for the IP list.
     */
    fun reloadIps(): Job = scope.launch {
        networkFilterUseCases.getIps.execute().fold(
            ifFailure = { Logger.error("Cannot load the IP filter list: ${it.message}") },
            ifSuccess = {
                val ip = it.first()
                ips = ip
                Logger.debug { "[Rules] IP filter list reloaded: ${ip.size} entries" }
            }
        )
    }

    override fun check(
        packet: FireWallModel,
        dnsModel: DNSModel?,
        logUseCases: LogUseCases,
        result: FirewallResult
    ): FirewallResult {
        val isBlocked = ips?.firstOrNull { it.ip == packet.destinationIP } == null

        return if (!isBlocked) FirewallResult.DROP else FirewallResult.ACCEPT

    }
}
