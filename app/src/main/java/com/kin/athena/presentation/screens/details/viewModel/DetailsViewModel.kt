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

package com.kin.athena.presentation.screens.details.viewModel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.model.Log
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.service.firewall.model.FireWallModel
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DetailsUiState {
    data object Loading : DetailsUiState()
    data class Success(val application: Application) : DetailsUiState()
    data class Error(val message: String) : DetailsUiState()
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val firewallManager: FirewallManager,
    savedStateHandle: SavedStateHandle,
    private val applicationUseCases: ApplicationUseCases,
    private val logUseCases: LogUseCases
) : ViewModel() {
    private val applicationID = savedStateHandle.get<String>("applicationID")
    var uiState: MutableState<DetailsUiState> = mutableStateOf(DetailsUiState.Loading)

    private val _topDomainsAndIps: MutableState<List<Triple<String, Int, String>>> = mutableStateOf(emptyList())
    val topDomainsAndIps: State<List<Triple<String, Int, String>>> = _topDomainsAndIps

    init {
        loadPackage()
    }

    private fun loadLogs(application: Application) {
        viewModelScope.launch {
            logUseCases.getLogs.execute().fold(
                ifSuccess = { logs ->
                    logs?.collectLatest { logs ->
                        val appLogs = logs.filter { it.packageID == application.uid }

                        val domainAndIpCounts = appLogs.groupingBy {
                            it.destinationAddress ?: it.destinationIP ?: "Unknown"
                        }.eachCount()

                        _topDomainsAndIps.value = domainAndIpCounts.entries
                            .sortedByDescending { it.value }
                            .take(5)
                            .map { (key, value) ->
                                val logId = appLogs.firstOrNull { it.destinationAddress == key || it.destinationIP == key }?.id.toString()
                                Triple(key, value, logId)
                            }
                    }
                }
            )
        }
    }

    private fun loadPackage() {
        applicationID?.let {
            viewModelScope.launch {
                uiState.value = DetailsUiState.Loading
                applicationUseCases.observeApplication.execute(applicationID).collectLatest { application ->
                    application?.let {
                        uiState.value = DetailsUiState.Success(application)
                        loadLogs(application)
                    }
                }
            }
        }
    }

    fun updatePackage(packageEntity: Application) {
        viewModelScope.launch {
            Logger.info("DetailsViewModel: Updating package ${packageEntity.packageID}, wifi=${packageEntity.internetAccess}, cellular=${packageEntity.cellularAccess}, bypassVpn=${packageEntity.bypassVpn}")
            Logger.debug {
                val before = (uiState.value as? DetailsUiState.Success)?.application
                "[UI] App rule change (details) pkg=${packageEntity.packageID} uid=${packageEntity.uid} " +
                    "wifi=${before?.internetAccess}->${packageEntity.internetAccess} " +
                    "cell=${before?.cellularAccess}->${packageEntity.cellularAccess} " +
                    "bypassVpn=${before?.bypassVpn}->${packageEntity.bypassVpn} " +
                    "firewall=${firewallManager.rulesLoaded.value.name()}"
            }
            applicationUseCases.updateApplication.execute(packageEntity)
            Logger.info("DetailsViewModel: Package updated in database")
            // The observeApplication flow will automatically update the UI
            if (firewallManager.rulesLoaded.value == FirewallStatus.ONLINE) {
                firewallManager.updateFirewallRules(packageEntity)
            }
        }
    }

    fun isVpnMode(): Boolean {
        return firewallManager.rulesLoaded.value == FirewallStatus.ONLINE
    }
}