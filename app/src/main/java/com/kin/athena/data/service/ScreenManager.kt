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

import com.kin.athena.core.logging.Logger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton

@AndroidEntryPoint
class ScreenStateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var screenStateManager: ScreenStateManager

    override fun onReceive(context: Context, intent: Intent) {
        Logger.debug { "[Screen] ${intent.action}" }
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                screenStateManager.updateScreenStatus(ScreenManager.SCREEN_OFF)
            }
            Intent.ACTION_SCREEN_ON -> {
                screenStateManager.updateScreenStatus(ScreenManager.SCREEN_ON)
            }
        }
    }
}

@Singleton
class ScreenStateManager
 {
    var currentScreenStatus: ScreenManager = ScreenManager.SCREEN_ON

    fun updateScreenStatus(screenStatus: ScreenManager) {
        currentScreenStatus = screenStatus
    }
}

enum class ScreenManager {
    SCREEN_OFF,
    SCREEN_ON
}