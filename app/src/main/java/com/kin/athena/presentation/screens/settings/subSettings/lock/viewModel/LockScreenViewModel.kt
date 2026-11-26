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

package com.kin.athena.presentation.screens.settings.subSettings.lock.viewModel

import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.presentation.navigation.routes.SettingRoutes
import com.kin.athena.presentation.screens.settings.viewModel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LockScreenViewModel : ViewModel() {
    val selectedCellsIndexList = mutableStateListOf<Int?>()
    val selectedCellCenterList = mutableStateListOf<Offset>()
    var canvasSize by mutableStateOf(Size.Zero)
    var currentTouchOffset by mutableStateOf<Offset?>(null)
    var lastCellCenter by mutableStateOf<Offset?>(null)
    var path by mutableStateOf(Path())

    private val _pinCode : MutableState<List<Int>> = mutableStateOf(emptyList())
    val pinCode: State<List<Int>> = _pinCode

    private val _isPinIncorrect = mutableStateOf(false)
    val isPinIncorrect: State<Boolean> = _isPinIncorrect

    private val _animateError = mutableStateOf(false)
    val animateError: State<Boolean> = _animateError

    fun updatePath(cellCenter: Offset) {
        lastCellCenter?.let {
            path.lineTo(it.x, it.y)
        }
        lastCellCenter = cellCenter
        path.lineTo(cellCenter.x, cellCenter.y)
    }


    fun clearPattern() {
        selectedCellsIndexList.clear()
        selectedCellCenterList.clear()
        path = Path()
        currentTouchOffset = null
        lastCellCenter = null
    }

    fun addNumber(settingsViewModel: SettingsViewModel, number: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (_pinCode.value.size < 6) {
                _pinCode.value += number
                if (pinCode.value.size == 6) {
                    onResult(checkPinCode(settingsViewModel))
                }
            }
        }
    }

    fun removeNumber() {
        viewModelScope.launch {
            if (_pinCode.value.isNotEmpty()) {
                _pinCode.value = _pinCode.value.dropLast(1)
            }
        }
    }

    private fun checkPinCode(settingsViewModel: SettingsViewModel): Boolean {
        if (settingsViewModel.settings.value.passcode == null) {
            // Never log the PIN itself
            Logger.debug { "[UI] Lock PIN set, pattern and fingerprint cleared" }
            settingsViewModel.update(
                settingsViewModel.settings.value.copy(
                    passcode = _pinCode.value.joinToString(""),
                    fingerprint = false,
                    pattern = null,
                )
            )
            settingsViewModel.updateDefaultRoute(SettingRoutes.LockScreen.createRoute(null),)

            return true
        } else {
            if (_pinCode.value.joinToString("") == settingsViewModel.settings.value.passcode) {
                return true
            } else {
                Logger.debug { "[UI] Lock PIN incorrect" }
                _isPinIncorrect.value = true
                _animateError.value = true
                viewModelScope.launch {
                    delay(500)
                    _animateError.value = false
                    onReset()
                }
                return false
            }
        }
    }

    fun onReset() {
        _pinCode.value = emptyList()
        _isPinIncorrect.value = false
        _animateError.value = false
    }
}