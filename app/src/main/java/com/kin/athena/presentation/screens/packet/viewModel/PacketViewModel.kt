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

package com.kin.athena.presentation.screens.packet.viewModel

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Ip
import com.kin.athena.domain.model.Log
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import com.kin.athena.service.utils.manager.FirewallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PacketViewModel @Inject constructor(
    private val logsUseCases: LogUseCases,
    private val networkFilterUseCases: NetworkFilterUseCases,
    private val firewallManager: FirewallManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val logId = savedStateHandle.get<String>("logID")?.toInt()

    private val _log: MutableState<Log?> = mutableStateOf(null)
    val log: State<Log?> = _log

    private val _blockedIps: MutableState<List<Ip>> = mutableStateOf(emptyList())
    val blockedIps: State<List<Ip>> = _blockedIps

    init {
        loadLog()
        loadBlockedIps()
    }


    fun addIp(ip: Ip) {
        Logger.debug { "[UI] IP filter add ip=${ip.ip} (from packet log)" }
        viewModelScope.launch {
            networkFilterUseCases.addIp.execute(ip)
            firewallManager.updateFirewallRules(null)
        }
    }

    fun deleteIp(ip: String) {
        Logger.debug { "[UI] IP filter remove ip=$ip (from packet log)" }
        viewModelScope.launch {
            networkFilterUseCases.deleteIp.execute(ip)
            firewallManager.updateFirewallRules(null)
        }
    }

    private fun loadBlockedIps() {
        viewModelScope.launch {
            networkFilterUseCases.getIps.execute().fold(
                ifSuccess = {
                    it.collect { ips ->
                        _blockedIps.value = ips
                    }
                }
            )
        }
    }

    private fun loadLog() {
        viewModelScope.launch {
            logId?.let {
                logsUseCases.getLog.execute(logId).fold(
                    ifSuccess = { log ->
                        _log.value = log
                    }
                )
            }
        }
    }
}