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

package com.kin.athena.presentation.screens.settings.subSettings.network.ips.viewModel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Ip
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IpViewModel @Inject constructor(
    private val networkFilterUseCases: NetworkFilterUseCases
) : ViewModel() {
    init {
        loadBlockedIps()
    }

    private val _dialogTextField: MutableState<TextFieldValue> = mutableStateOf(TextFieldValue(""))
    val dialogTextField: State<TextFieldValue> = _dialogTextField

    private val _addDialogEnabled: MutableState<Boolean> = mutableStateOf(false)
    val addDialogEnabled: State<Boolean> = _addDialogEnabled


    private val _query: MutableState<String> = mutableStateOf("")
    val query: State<String> = _query


    private val _ips: MutableState<List<Ip>> = mutableStateOf(emptyList())
    val ips: State<List<Ip>> = derivedStateOf { applyFilter(_ips.value) }

    fun updateDialogText(text: TextFieldValue) {
        _dialogTextField.value = text
    }

    fun setAddDialog(enabled: Boolean) {
        _addDialogEnabled.value = enabled
    }

    fun clearQuery() {
        _query.value = ""
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    private fun applyFilter(ips: List<Ip>): List<Ip> {
        val currentQuery = _query.value.trim()
        val ret = if (currentQuery.isBlank()) {
            ips
        } else {
            ips.filter { it.ip.contains(currentQuery, ignoreCase = true) }
        }
        return ret
    }

    fun loadBlockedIps() {
        viewModelScope.launch {
            try {
                networkFilterUseCases.getIps.execute().fold(
                    ifSuccess = { flow ->
                        flow?.collect { newIps ->
                            if (newIps != null) {
                                _ips.value = newIps
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.error("Failed to load blocked IPs: ${e.message}", e)
            }
        }
    }

    fun addIP(value: TextFieldValue) {
        Logger.debug { "[UI] IP filter add ip=${value.text}" }
        viewModelScope.launch {
            networkFilterUseCases.addIp.execute(Ip(value.text))
            loadBlockedIps()
            updateDialogText(TextFieldValue(""))
            clearQuery()
            setAddDialog(false)
        }
    }

    fun deleteIP(ip: String) {
        Logger.debug { "[UI] IP filter remove ip=$ip" }
        viewModelScope.launch {
            networkFilterUseCases.deleteIp.execute(ip)
            loadBlockedIps()
        }
    }
}