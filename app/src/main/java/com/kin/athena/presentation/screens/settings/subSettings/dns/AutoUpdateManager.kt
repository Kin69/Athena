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

package com.kin.athena.presentation.screens.settings.subSettings.dns

import android.content.Context
import androidx.work.Data
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kin.athena.core.logging.Logger
import com.kin.athena.presentation.screens.settings.subSettings.dns.hosts.RuleDatabaseUpdateWorker
import java.util.concurrent.TimeUnit

object AutoUpdateManager {

    fun initializeAutoUpdate(context: Context, enabled: Boolean, intervalMs: Long) {
        Logger.debug { "[Blocklist] Auto-update init enabled=$enabled intervalMs=$intervalMs" }
        if (!enabled) {
            cancelAutoUpdate(context)
            return
        }

        val workManager = WorkManager.getInstance(context)

        // Check if auto-update worker is already scheduled
        val existingWork = workManager.getWorkInfosByTag("auto_update_blocklists")
        try {
            if (existingWork.get().any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Logger.debug { "[Blocklist] Auto-update already scheduled" }
                return
            }
        } catch (e: Exception) {
            // Proceed with scheduling if check fails
            Logger.debug(e) { "[Blocklist] Cannot check the scheduled auto-update, scheduling it again" }
        }

        scheduleAutoUpdateWorker(context, intervalMs)
    }

    fun scheduleAutoUpdateWorker(context: Context, intervalMs: Long, enabled: Boolean = true) {
        val workManager = WorkManager.getInstance(context)

        // Cancel existing auto-update work
        workManager.cancelAllWorkByTag("auto_update_blocklists")

        if (!enabled) {
            return
        }

        // Convert milliseconds to minutes (minimum interval for PeriodicWorkRequest is 15 minutes)
        val intervalMinutes = maxOf(15L, intervalMs / (60 * 1000L))
        Logger.debug { "[Blocklist] Auto-update scheduled every $intervalMinutes min" }

        val autoUpdateRequest = PeriodicWorkRequestBuilder<RuleDatabaseUpdateWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .addTag("auto_update_blocklists")
            .setInputData(
                Data.Builder()
                    .putBoolean("auto_update", true)
                    .build()
            )
            .build()

        workManager.enqueue(autoUpdateRequest)
    }

    fun cancelAutoUpdate(context: Context) {
        Logger.debug { "[Blocklist] Auto-update cancelled" }
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag("auto_update_blocklists")
    }
}