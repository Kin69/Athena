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

package com.kin.athena

import android.os.Build
import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kin.athena.core.logging.Logger
import com.kin.athena.data.cache.DomainCacheService
import com.kin.athena.presentation.screens.settings.subSettings.dns.AutoUpdateManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var domainCacheService: DomainCacheService

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        App.applicationContext = this
        instance = this
        Logger.debug {
            val process = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName() else "?"
            "[App] onCreate ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE} " +
                "SDK ${Build.VERSION.SDK_INT} process=$process"
        }
        
        // Initialize auto-update on app startup
        initializeAutoUpdate()
        
        // Don't initialize domains on app startup - they'll be loaded when needed
        // This significantly improves app startup time
        // Domains will be loaded when:
        // 1. VPN service starts
        // 2. User navigates to DNS settings
        // 3. User manually triggers a refresh
        // 4. Auto-update worker runs
    }
    
    private fun initializeAutoUpdate() {
        try {
            // Use default interval of 15 minutes if settings aren't available yet
            // The actual interval and enabled state will be updated when settings are loaded
            val defaultInterval = 15 * 60 * 1000L // 15 minutes
            val defaultEnabled = true // Default to enabled
            AutoUpdateManager.initializeAutoUpdate(this, defaultEnabled, defaultInterval)
        } catch (e: Exception) {
            // Silently handle initialization errors
            Logger.debug(e) { "[App] Auto-update initialization failed" }
        }
    }

    companion object {
        lateinit var applicationContext: Context
        private lateinit var instance: App
    }
}

