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

package com.kin.athena.presentation.screens.settings.subSettings.behavior.viewModel

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.service.utils.manager.FirewallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BehaviorViewModel @Inject constructor(
    val firewallManager: FirewallManager,
    private val applicationUseCases: ApplicationUseCases,
    @ApplicationContext private val context: Context
) : ViewModel() {
    fun updateLogs(enabled: Boolean) {
        Logger.debug { "[UI] Packet logs enabled=$enabled" }
        viewModelScope.launch {
            firewallManager.updateLogs(enabled)
        }
    }


    fun updateApp(wifi: Boolean, cellular: Boolean, updateSystemApps: Boolean) {
        Logger.debug { "[UI] Bulk app rule change wifi=$wifi cell=$cellular includeSystemApps=$updateSystemApps" }
        viewModelScope.launch {
            applicationUseCases.getApplications.execute().fold(
                ifSuccess = { applications ->
                    Logger.debug {
                        val count = if (updateSystemApps) applications.size else applications.count { !it.systemApp }
                        "[UI] Bulk app rule change applies to $count of ${applications.size} apps"
                    }
                    applications.forEach { application ->
                        if (updateSystemApps) {
                            applicationUseCases.updateApplication.execute(application.copy(internetAccess = wifi, cellularAccess = cellular))
                        } else {
                            if (!application.systemApp) {
                                applicationUseCases.updateApplication.execute(application.copy(internetAccess = wifi, cellularAccess = cellular))
                            }
                        }
                    }
                }
            )
        }
    }
    
    fun showRootDisabledMessage() {
        Toast.makeText(
            context, 
            context.getString(com.kin.athena.R.string.behavior_root_disabled_message), 
            Toast.LENGTH_LONG
        ).show()
    }
    
    fun showFirewallDisableMessage() {
        Toast.makeText(
            context, 
            context.getString(com.kin.athena.R.string.home_firewall_disable), 
            Toast.LENGTH_LONG
        ).show()
    }
}