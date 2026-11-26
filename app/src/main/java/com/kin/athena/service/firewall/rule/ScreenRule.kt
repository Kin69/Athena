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
import com.kin.athena.data.service.NetworkManager
import com.kin.athena.data.service.ScreenManager
import com.kin.athena.data.service.ScreenStateManager
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.service.firewall.model.FireWallModel
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.vpn.network.transport.dns.DNSModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class ScreenRule @Inject constructor(
    private val screenStateManager: ScreenStateManager,
    private val preferencesUseCases: PreferencesUseCases
) : FirewallRule {
    var blockWhenScreenOffWifi: Boolean? = null
    var blockWhenScreenOffData: Boolean? = null

    init {
        updateRules()
    }

    fun updateRules() {
        runBlocking {
            preferencesUseCases.loadSettings.execute().fold(
                ifSuccess = { settings ->
                    blockWhenScreenOffData = settings.blockCellularWhenScreenOff
                    blockWhenScreenOffWifi = settings.blockWifiWhenScreenOff
                    Logger.debug { "[Rules] Screen-off rules: blockWifi=$blockWhenScreenOffWifi blockCell=$blockWhenScreenOffData screen=${screenStateManager.currentScreenStatus}" }
                }
            )
        }
    }

    override fun check(packet: FireWallModel, dnsModel: DNSModel?, logUseCases: LogUseCases, result: FirewallResult): FirewallResult {
        return when {
            blockWhenScreenOffData == true && packet.networkType == NetworkManager.ConnectionType.MOBILE -> {
                if (screenStateManager.currentScreenStatus == ScreenManager.SCREEN_OFF) {
                    FirewallResult.DROP
                } else FirewallResult.ACCEPT
            }
            blockWhenScreenOffWifi == true && packet.networkType == NetworkManager.ConnectionType.WIFI -> {
                if (screenStateManager.currentScreenStatus == ScreenManager.SCREEN_OFF) {
                    FirewallResult.DROP
                } else FirewallResult.ACCEPT
            }
            else -> FirewallResult.ACCEPT
        }
    }
}