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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.service.utils.manager.FirewallManager
import com.kin.athena.service.utils.manager.FirewallMode
import com.kin.athena.service.utils.manager.VpnManager
import com.kin.athena.service.utils.manager.NetworkSpeedManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var firewallManager: FirewallManager

    @Inject
    lateinit var preferencesUseCases: PreferencesUseCases

    @Inject
    @ApplicationContext
    lateinit var context: Context

    override fun onReceive(context: Context, intent: Intent) {
        Logger.debug { "[Boot] Received ${intent.action} (SDK ${Build.VERSION.SDK_INT})" }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // On Android 15+, use WorkManager to avoid foreground service restrictions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val workRequest = OneTimeWorkRequestBuilder<BootStartWorker>()
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "boot_start_work",
                        ExistingWorkPolicy.REPLACE,
                        workRequest
                    )

                Logger.info("Scheduled boot services via WorkManager for Android 15+")
            } else {
                // On older Android versions, start services directly
                CoroutineScope(Dispatchers.IO).launch {
                    preferencesUseCases.loadSettings.execute().fold(
                        ifSuccess = { settings ->
                            Logger.debug { "[Boot] startOnBoot=${settings.startOnBoot} root=${settings.useRootMode} shizuku=${settings.useShizukuMode} speedMonitor=${settings.networkSpeedMonitor}" }
                            if (settings.startOnBoot) {
                                if (settings.useRootMode == true) {
                                    firewallManager.setFirewallMode(FirewallMode.ROOT)
                                    firewallManager.startFirewall()
                                } else {
                                    Logger.debug { "[Boot] Starting the VPN, permission=${VpnManager.permissionChecker(context)}" }
                                    if (VpnManager.permissionChecker(context)) {
                                        firewallManager.startFirewall()
                                    } else {
                                        Logger.error("VPN permission not granted")
                                    }
                                }
                            }

                            // Start network speed monitor if enabled
                            if (settings.networkSpeedMonitor) {
                                NetworkSpeedManager.start(context)
                                Logger.info("Network speed monitor started on boot")
                            }
                        },
                        ifFailure = { error ->
                            Logger.error("Failed to load settings on boot: ${error.message}")
                        }
                    )
                }
            }
        }
    }
}
