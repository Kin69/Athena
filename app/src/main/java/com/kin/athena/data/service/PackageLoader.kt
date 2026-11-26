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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.kin.athena.core.utils.Error
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Result
import com.kin.athena.core.utils.extensions.usesGooglePlayServices
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.presentation.screens.settings.viewModel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageLoader(
    val settingsViewModel: SettingsViewModel,
    private val packageManager: PackageManager,
    private val applicationUseCases: ApplicationUseCases
) {

    suspend fun loadPackages(): Result<Unit, Error> = withContext(Dispatchers.IO) {
        return@withContext try {
            Logger.info("PackageLoader: loadPackages() called")
            val allApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            val showSystemPackages = settingsViewModel.settings.value.showSystemPackages
            val showOfflinePackages = settingsViewModel.settings.value.showOfflinePackages
            val wifiDefault = settingsViewModel.settings.value.wiFiDefault
            val cellularDefault = settingsViewModel.settings.value.cellularDefault
            Logger.info("PackageLoader: Settings - showSystem=$showSystemPackages, showOffline=$showOfflinePackages, wifiDefault=$wifiDefault, cellularDefault=$cellularDefault")
            
            val filteredApps = allApps.filter { appInfo ->
                if (appInfo.packageName == "com.kin.athena") {
                    return@filter false
                }
                
                val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                
                if (isSystemApp && !showSystemPackages) {
                    return@filter false
                }
                
                if (!appInfo.enabled && !showOfflinePackages) {
                    return@filter false
                }
                
                true
            }
            
            Logger.info("PackageLoader: Found ${allApps.size} total apps, filtered to ${filteredApps.size} apps (removed ${allApps.size - filteredApps.size} apps)")
            
            // Get all existing package IDs in one batch query
            val allPackageIds = filteredApps.map { it.packageName }
            val existingPackageIds = applicationUseCases.getExistingPackageIds.execute(allPackageIds)
            val existingPackageSet = existingPackageIds.toSet()
            
            // Create list of new applications to insert with proper display names
            val newApplications = filteredApps.mapNotNull { appInfo ->
                if (appInfo.packageName !in existingPackageSet) {
                    val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    
                    // Get display name efficiently
                    val displayName = try {
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        Logger.debug { "[Pkg] No label for ${appInfo.packageName}: ${e.message}" }
                        appInfo.packageName
                    }
                    
                    // Check network permissions efficiently
                    val requiresNetwork = try {
                        packageManager.checkPermission(
                            android.Manifest.permission.INTERNET,
                            appInfo.packageName
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } catch (e: Exception) {
                        Logger.debug { "[Pkg] Cannot check INTERNET for ${appInfo.packageName}: ${e.message}" }
                        true // Default to true for safety
                    }
                    
                    val tempApp = Application(
                        packageID = appInfo.packageName,
                        uid = appInfo.uid,
                        systemApp = isSystemApp,
                        usesGooglePlayServices = false,
                        internetAccess = wifiDefault,
                        cellularAccess = cellularDefault,
                        displayName = displayName,
                        lastUpdated = System.currentTimeMillis(),
                        requiresNetwork = requiresNetwork
                    )
                    
                    tempApp.copy(usesGooglePlayServices = tempApp.usesGooglePlayServices())
                } else null
            }
            
            // Batch insert all new applications
            if (newApplications.isNotEmpty()) {
                Logger.info("PackageLoader: Inserting ${newApplications.size} new applications")
                Logger.debug { "[Pkg] New apps (wifi=$wifiDefault cell=$cellularDefault): ${newApplications.joinToString { "${it.packageID}/${it.uid}" }}" }
                applicationUseCases.addApplications.execute(newApplications)
            } else {
                Logger.info("PackageLoader: No new applications to insert")
            }
            
            // Update display names for existing apps that have empty display names
            updateEmptyDisplayNames()
            
            // Update GMS status for existing apps
            updateGmsStatus()
            
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.error("Error while loading packages: ${e.message}")
            Result.Failure(Error.ServerError(e.message ?: "Error while loading packages"))
        }
    }
    
    private suspend fun updateEmptyDisplayNames() {
        try {
            // Get all apps with empty display names
            val allAppsResult = applicationUseCases.getApplications.execute()
            allAppsResult.fold(
                ifSuccess = { allApps ->
                    val appsToUpdate = allApps.filter { it.displayName.isEmpty() || it.displayName == it.packageID }

                    if (appsToUpdate.isNotEmpty()) {
                        Logger.info("PackageLoader: Updating display names for ${appsToUpdate.size} applications")

                        appsToUpdate.forEach { app ->
                            try {
                                val appInfo = packageManager.getApplicationInfo(app.packageID, 0)
                                val displayName = packageManager.getApplicationLabel(appInfo).toString()

                                // Get fresh app data from database to avoid overwriting recent changes
                                val freshApp = applicationUseCases.getApplication.execute(app.packageID)
                                freshApp.fold(
                                    ifSuccess = { currentApp ->
                                        applicationUseCases.updateApplication.execute(
                                            currentApp.copy(displayName = displayName)
                                        )
                                    }
                                )
                            } catch (e: Exception) {
                                Logger.error("Failed to get display name for ${app.packageID}: ${e.message}")
                            }
                        }

                        Logger.info("PackageLoader: Updated ${appsToUpdate.size} display names")
                    }
                },
                ifFailure = { error ->
                    Logger.error("Error getting all applications: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Logger.error("Error updating display names: ${e.message}")
        }
    }
    
    private suspend fun updateGmsStatus() {
        try {
            val allAppsResult = applicationUseCases.getApplications.execute()
            allAppsResult.fold(
                ifSuccess = { allApps ->
                    val appsToUpdate = allApps.filter { app ->
                        val expectedGmsStatus = app.usesGooglePlayServices()
                        app.usesGooglePlayServices != expectedGmsStatus
                    }

                    if (appsToUpdate.isNotEmpty()) {
                        Logger.info("PackageLoader: Updating GMS status for ${appsToUpdate.size} applications")

                        appsToUpdate.forEach { app ->
                            try {
                                val correctedGmsStatus = app.usesGooglePlayServices()
                                applicationUseCases.updateApplication.execute(
                                    app.copy(usesGooglePlayServices = correctedGmsStatus)
                                )
                            } catch (e: Exception) {
                                Logger.error("Failed to update GMS status for ${app.packageID}: ${e.message}")
                            }
                        }

                        Logger.info("PackageLoader: Updated GMS status for ${appsToUpdate.size} applications")
                    }
                },
                ifFailure = { error ->
                    Logger.error("Error getting all applications for GMS update: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Logger.error("Error updating GMS status: ${e.message}")
        }
    }
}