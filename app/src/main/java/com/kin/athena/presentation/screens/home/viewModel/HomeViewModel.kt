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

package com.kin.athena.presentation.screens.home.viewModel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.utils.Result
import com.kin.athena.core.utils.extensions.getApplicationName
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Shell
import com.kin.athena.core.utils.extensions.getApplicationIcon
import com.kin.athena.data.service.PackageLoader
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.service.utils.receiver.AppChangeCallback
import com.kin.athena.service.utils.receiver.AppChangeReceiver
import com.kin.athena.presentation.screens.settings.viewModel.SettingsViewModel
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallManager
import com.kin.athena.service.utils.manager.FirewallMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationUseCases: ApplicationUseCases,
    val firewallManager: FirewallManager
) : ViewModel() {
    
    // Professional state management
    private val _applicationState = mutableStateOf<ApplicationListState>(ApplicationListState.Loading)
    val applicationState: State<ApplicationListState> = _applicationState

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    // Debounce job for search
    private var searchJob: Job? = null
    
    private var currentSettingsViewModel: SettingsViewModel? = null
    
    // Removed pagination - show all applications

    // Saved scroll position for restoration
    private val _savedScrollIndex = mutableStateOf(0)
    val savedScrollIndex: State<Int> = _savedScrollIndex

    private val _savedScrollOffset = mutableStateOf(0)
    val savedScrollOffset: State<Int> = _savedScrollOffset

    // Icon cache
    val iconMap: MutableState<Map<String, Drawable?>> = mutableStateOf(emptyMap())
    
    // Track icon loading to prevent duplicates
    private var iconLoadingJob: Job? = null
    private var lastIconLoadSize = 0
    
    // Throttle refresh calls
    private var lastRefreshTime = 0L
    private val refreshThrottleMs = 1000L // 1 second minimum between refreshes

    private val _firewallClicked = mutableStateOf(false)
    val firewallClicked: State<Boolean> get() = _firewallClicked

    private val _rootUncleaned = mutableStateOf(false)
    val rootUncleaned: State<Boolean> get() = _rootUncleaned

    private val _magiskError = mutableStateOf(false)
    val magiskError: State<Boolean> get() = _magiskError

    private val _menuStatus: MutableState<Boolean> = mutableStateOf(false)
    val menuStatus: State<Boolean> = _menuStatus

    private val _notificationPermissionRequested = mutableStateOf(false)
    val notificationPermissionRequested: State<Boolean> = _notificationPermissionRequested

    private val _rootPermissionRequested = mutableStateOf(false)
    val rootPermissionRequested: State<Boolean> = _rootPermissionRequested

    private val _vpnPermissionRequested = mutableStateOf(false)
    val vpnPermissionRequested: State<Boolean> = _vpnPermissionRequested

    private val _isInitialLoad = mutableStateOf(true)
    val isInitialLoad: State<Boolean> = _isInitialLoad

    private val _showSplashScreen = mutableStateOf(true)
    val showSplashScreen: State<Boolean> = _showSplashScreen

    private var appChangeReceiver: AppChangeReceiver? = null

    fun setRootUncleaned(value: Boolean) {
        _rootUncleaned.value = value
    }

    fun updateMagiskError(value: Boolean) {
        _magiskError.value = value
    }


    fun setFirewallClicked(clicked: Boolean) {
        _firewallClicked.value = clicked
    }

    fun updateMenuStatus(value: Boolean) {
        _menuStatus.value = value
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        _savedScrollIndex.value = index
        _savedScrollOffset.value = offset
    }

    fun refreshVisibleApplications() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRefreshTime < refreshThrottleMs) {
            Logger.info("HomeViewModel: Refresh throttled - last refresh was ${currentTime - lastRefreshTime}ms ago")
            return // Throttle rapid refresh calls
        }
        
        val currentState = _applicationState.value
        if (currentState is ApplicationListState.Success) {
            lastRefreshTime = currentTime
            viewModelScope.launch(Dispatchers.IO) {
                // Refresh all applications
                val packageIds = currentState.applications.map { it.packageID }
                val freshApps = packageIds.mapNotNull { packageId ->
                    applicationUseCases.getApplication.execute(packageId).fold(
                        ifSuccess = { it },
                        ifFailure = { null }
                    )
                }

                // Update state with fresh data
                val updatedApplications = currentState.applications.map { oldApp ->
                    freshApps.find { it.packageID == oldApp.packageID } ?: oldApp
                }
                _applicationState.value = currentState.copy(applications = updatedApplications)
                Logger.info("HomeViewModel: Refreshed ${freshApps.size} applications")
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        // Cancel previous search job
        searchJob?.cancel()

        // Debounce search for professional performance
        searchJob = viewModelScope.launch {
            delay(300) // Professional debounce timing
            
            val showSystemPackages = currentSettingsViewModel?.settings?.value?.showSystemPackages ?: true
            val showOfflinePackages = currentSettingsViewModel?.settings?.value?.showOfflinePackages ?: true
            
            Logger.info("HomeViewModel: Search with query='$query', showSystemPackages=$showSystemPackages, showOfflinePackages=$showOfflinePackages")
            
            val result = applicationUseCases.getFilteredApplications.execute(
                showSystemPackages = showSystemPackages,
                showOfflinePackages = showOfflinePackages,
                searchQuery = query,
                offset = 0
            )
            
            when (result) {
                is Result.Success -> {
                    val data = result.data
                    // Smoothly replace the current list without showing loading state
                    _applicationState.value = ApplicationListState.Success(
                        applications = data.applications,
                        totalCount = data.totalCount
                    )
                }
                is Result.Failure -> {
                    _applicationState.value = ApplicationListState.Error(
                        result.error.message ?: "Failed to search applications"
                    )
                }
            }
        }
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
        searchJob?.cancel()
        
        // When clearing search, smoothly reload the full list without jumping
        viewModelScope.launch(Dispatchers.IO) {
            val showSystemPackages = currentSettingsViewModel?.settings?.value?.showSystemPackages ?: true
            val showOfflinePackages = currentSettingsViewModel?.settings?.value?.showOfflinePackages ?: true
            
            val result = applicationUseCases.getFilteredApplications.execute(
                showSystemPackages = showSystemPackages,
                showOfflinePackages = showOfflinePackages,
                searchQuery = "",
                offset = 0
            )
            
            when (result) {
                is Result.Success -> {
                    val data = result.data
                    // Smoothly replace the current list without showing loading state
                    _applicationState.value = ApplicationListState.Success(
                        applications = data.applications,
                        totalCount = data.totalCount
                    )
                }
                is Result.Failure -> {
                    _applicationState.value = ApplicationListState.Error(
                        result.error.message ?: "Failed to load applications"
                    )
                }
            }
        }
    }

    fun updateFirewallStatus(value: FirewallStatus, serviceType: FirewallMode) {
        Logger.debug { "[UI] Firewall ${value.name()} requested mode=$serviceType current=${firewallManager.rulesLoaded.value.name()} by=${Logger.caller()}" }
        firewallManager.setFirewallMode(serviceType)
        value?.let {
            if (it.name() == FirewallStatus.ONLINE.name()) firewallManager.startFirewall() else if (it.name() == FirewallStatus.OFFLINE.name()) firewallManager.stopFirewall()
        }
    }


    fun updateNotificationPermissionStatus(isRequested: Boolean) {
        _notificationPermissionRequested.value = isRequested
    }

    fun updateRootPermissionStatus(isRequested: Boolean) {
        _rootPermissionRequested.value = isRequested
    }

    fun updateVpnPermissionStatus(isRequested: Boolean) {
        _vpnPermissionRequested.value = isRequested
    }

    fun loadApplications(reset: Boolean = false, settingsViewModel: SettingsViewModel? = null) {
        Logger.info("HomeViewModel: loadApplications called - loading all applications")
        if (reset) {
            _applicationState.value = ApplicationListState.Loading
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get filter settings - use defaults if settingsViewModel is null
                val showSystemPackages = settingsViewModel?.settings?.value?.showSystemPackages ?: true
                val showOfflinePackages = settingsViewModel?.settings?.value?.showOfflinePackages ?: true
                
                // Debug logging for search
                Logger.info("HomeViewModel: Loading applications with search='${_searchQuery.value}', showSystemPackages=$showSystemPackages, showOfflinePackages=$showOfflinePackages")
                
                val result = applicationUseCases.getFilteredApplications.execute(
                    showSystemPackages = showSystemPackages,
                    showOfflinePackages = showOfflinePackages,
                    searchQuery = _searchQuery.value,
                    offset = 0
                )
                
                when (result) {
                    is Result.Success -> {
                        val data = result.data
                        Logger.info("HomeViewModel: Loaded ${data.applications.size} apps, totalCount=${data.totalCount}")
                        _applicationState.value = ApplicationListState.Success(
                            applications = data.applications,
                            totalCount = data.totalCount
                        )
                    }
                    is Result.Failure -> {
                        _applicationState.value = ApplicationListState.Error(
                            result.error.message ?: "Failed to load applications"
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.debug(e) { "[UI] Loading applications failed" }
                _applicationState.value = ApplicationListState.Error(
                    e.message ?: "Unknown error occurred"
                )
            }
        }
    }
    
    // Removed pagination - no longer needed
    fun loadMoreApplications(settingsViewModel: SettingsViewModel) {
        // No-op - pagination removed
    }

    fun updatePackage(packageEntity: Application, updateUI: Boolean = true) {
        viewModelScope.launch {
            Logger.info("HomeViewModel: updatePackage called for ${packageEntity.packageID}, wifi=${packageEntity.internetAccess}, cellular=${packageEntity.cellularAccess}")
            Logger.debug {
                val before = (_applicationState.value as? ApplicationListState.Success)
                    ?.applications?.find { it.packageID == packageEntity.packageID }
                "[UI] App rule change pkg=${packageEntity.packageID} uid=${packageEntity.uid} " +
                    "wifi=${before?.internetAccess}->${packageEntity.internetAccess} " +
                    "cell=${before?.cellularAccess}->${packageEntity.cellularAccess} " +
                    "bypassVpn=${before?.bypassVpn}->${packageEntity.bypassVpn} " +
                    "firewall=${firewallManager.rulesLoaded.value.name()}"
            }

            // Update UI state IMMEDIATELY before database update for instant feedback
            if (updateUI) {
                val currentState = _applicationState.value
                if (currentState is ApplicationListState.Success) {
                    val updatedApplications = currentState.applications.map { app ->
                        if (app.packageID == packageEntity.packageID) packageEntity else app
                    }
                    _applicationState.value = currentState.copy(applications = updatedApplications)
                    Logger.info("HomeViewModel: UI state updated immediately for ${packageEntity.packageID}")
                }
            }

            // Update database on IO thread and wait for completion
            withContext(Dispatchers.IO) {
                applicationUseCases.updateApplication.execute(packageEntity)
            }
            Logger.info("HomeViewModel: Database updated for ${packageEntity.packageID}")

            if (firewallManager.rulesLoaded.value == FirewallStatus.ONLINE) {
                withContext(Dispatchers.IO) {
                    firewallManager.updateFirewallRules(packageEntity)
                }
            } else {
                Logger.debug { "[UI] Firewall not online, rules for ${packageEntity.packageID} apply on next start" }
            }
        }
    }

    fun togglePinned(packageEntity: Application) {
        viewModelScope.launch {
            val newPinnedStatus = !packageEntity.isPinned
            Logger.info("HomeViewModel: togglePinned called for ${packageEntity.packageID}, newPinnedStatus=$newPinnedStatus")

            // Update UI state IMMEDIATELY for instant feedback
            val currentState = _applicationState.value
            if (currentState is ApplicationListState.Success) {
                val updatedApp = packageEntity.copy(isPinned = newPinnedStatus)
                // Update the app in the list
                val updatedApplications = currentState.applications.map { app ->
                    if (app.packageID == packageEntity.packageID) updatedApp else app
                }
                // Sort: pinned apps first
                val sortedApplications = updatedApplications.sortedWith(
                    compareByDescending<Application> { it.isPinned }
                        .thenBy { it.displayName.lowercase(Locale.getDefault()) }
                )
                _applicationState.value = currentState.copy(applications = sortedApplications)
                Logger.info("HomeViewModel: UI state updated immediately for ${packageEntity.packageID}")
            }

            // Update database on IO thread
            withContext(Dispatchers.IO) {
                applicationUseCases.updatePinnedStatus.execute(packageEntity.packageID, newPinnedStatus)
            }
            Logger.info("HomeViewModel: Database updated for ${packageEntity.packageID}")
        }
    }

    fun addPackageByName(name: String, settingsViewModel: SettingsViewModel) {
        Logger.debug { "[UI] Package added pkg=$name, adding to the app list" }
        viewModelScope.launch {
            try {
                val info = context.packageManager.getApplicationInfo(name, 0)
                applicationUseCases.addApplication.execute(
                    Application(name, info.uid, info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                )
                loadPackages(settingsViewModel)
            } catch (e: NameNotFoundException) {
               Logger.error("Package $name not found.")
               Logger.debug(e) { "[UI] Installed package $name is not visible to the package manager" }
            }
        }
    }

    fun initialize(settingsViewModel: SettingsViewModel) {
        // Store settings reference for search and filtering
        currentSettingsViewModel = settingsViewModel

        // Only load if we don't have data already
        val currentState = _applicationState.value
        if (currentState !is ApplicationListState.Success || currentState.applications.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                // Load packages from database with initial settings
                loadPackages(settingsViewModel)

                // Load applications from database on IO thread to prevent UI blocking
                withContext(Dispatchers.Main) {
                    loadApplications(reset = true, settingsViewModel = settingsViewModel)
                }
            }
        }

        // Register app change receiver once - do this immediately to avoid delays
        if (appChangeReceiver == null) {
            appChangeReceiver = AppChangeReceiver(createAppChangeCallback(settingsViewModel))
            appChangeReceiver?.register(context)
        }

        // Set up callback to reload when settings change
        settingsViewModel.onAppFilteringSettingsChanged = {
            Logger.info("HomeViewModel: onAppFilteringSettingsChanged triggered, reloading packages and applications")
            viewModelScope.launch(Dispatchers.IO) {
                // Reload packages from system with new settings on IO thread
                loadPackages(settingsViewModel)
                // Then reload the application list view on main thread
                withContext(Dispatchers.Main) {
                    loadApplications(reset = true, settingsViewModel = settingsViewModel)
                }
            }
        }
    }

    fun loadIcons(applications: List<Application>, settingsViewModel: SettingsViewModel, color: Color) {
        // Prevent duplicate icon loading
        if (applications.size == lastIconLoadSize) {
            return // Same size, likely a duplicate call
        }
        
        // Cancel previous icon loading job
        iconLoadingJob?.cancel()
        
        iconLoadingJob = viewModelScope.launch(Dispatchers.IO) {
            val existingIcons = iconMap.value
            
            // Only load icons for new applications
            val newApps = applications.filter { !existingIcons.containsKey(it.packageID) }
            if (newApps.isEmpty()) {
                lastIconLoadSize = applications.size
                return@launch
            }
            
            // Prioritize the first 15 applications for immediate loading
            val priorityApps = newApps.take(15)
            val remainingApps = newApps.drop(15)
            
            // Load priority apps first (first 15) with no delay
            if (priorityApps.isNotEmpty()) {
                val priorityIcons = priorityApps.mapNotNull { application ->
                    try {
                        val icon = application.getApplicationIcon(
                            context.packageManager,
                            tintColor = color.toArgb(),
                            useDynamicIcon = settingsViewModel.settings.value.useDynamicIcons,
                            context
                        )
                        application.packageID to icon
                    } catch (e: Exception) {
                        Logger.error("Failed to load icon for ${application.packageID}: ${e.message}", e)
                        application.packageID to null
                    }
                }.toMap()
                
                // Update priority icons immediately
                iconMap.value = iconMap.value + priorityIcons
                Logger.info("HomeViewModel: Loaded ${priorityIcons.size} priority icons (total apps: ${applications.size})")
            }
            
            // Load remaining icons in background with delays
            if (remainingApps.isNotEmpty()) {
                remainingApps.chunked(3).forEachIndexed { index, chunk ->
                    delay(100) // Increased delay to reduce background loading frequency
                    
                    val chunkIcons = chunk.mapNotNull { application ->
                        try {
                            val icon = application.getApplicationIcon(
                                context.packageManager,
                                tintColor = color.toArgb(),
                                useDynamicIcon = settingsViewModel.settings.value.useDynamicIcons,
                                context
                            )
                            application.packageID to icon
                        } catch (e: Exception) {
                            Logger.error("Failed to load icon for ${application.packageID}: ${e.message}", e)
                            application.packageID to null
                        }
                    }.toMap()
                    
                    // Update icons incrementally
                    iconMap.value = iconMap.value + chunkIcons
                }
            }
            
            lastIconLoadSize = applications.size
        }
    }

    private suspend fun loadPackages(settingsViewModel: SettingsViewModel) {
        PackageLoader(settingsViewModel, context.packageManager, applicationUseCases).loadPackages()
    }

    private fun createAppChangeCallback(settingsViewModel: SettingsViewModel) = object : AppChangeCallback {
        override fun onAppInstalled(packageName: String?) {
            packageName?.let { addPackageByName(it, settingsViewModel) }
        }

        override fun onAppUninstalled(packageName: String?) {
            packageName?.let {
                Logger.debug { "[UI] Package removed pkg=$it, deleting from the app list" }
                viewModelScope.launch {
                    try {
                        val application = applicationUseCases.getApplication.execute(it)
                        application.fold(
                            ifSuccess = { applicationModel ->
                                applicationUseCases.deleteApplication.execute(applicationModel)
                            },
                            ifFailure = { error ->
                                Logger.error("Failed to get application $it for uninstall cleanup: ${error.message}")
                            }
                        )
                    } catch (e: Exception) {
                        Logger.error("Error handling app uninstall for $it: ${e.message}")
                    }
                }
            }
        }
    }

    fun deleteApplication(application: Application) {
        Logger.debug { "[UI] Delete application pkg=${application.packageID}" }
        viewModelScope.launch {
            applicationUseCases.deleteApplication.execute(application)
        }
    }


    fun checkIfCleanedUp() {
        val shell = Shell("su")

        shell.addOnStderrLineListener( object : Shell.OnLineListener {
            override fun onLine(line: String) {
                val lines = line.toIntOrNull()

                lines?.let {
                    if (lines !in 0..2) {
                        Logger.debug { "[UI] Leftover root rules found, EasyApps chain lines=$lines" }
                        setRootUncleaned(true)
                    }
                }
            }
        })

        shell.addOnStdoutLineListener( object : Shell.OnLineListener {
            override fun onLine(line: String) {
                val lines = line.toIntOrNull()

                lines?.let {
                    if (lines !in 0..2) {
                        Logger.debug { "[UI] Leftover root rules found, EasyApps chain lines=$lines" }
                        setRootUncleaned(true)
                    }
                }
            }
        })

        shell.run("iptables -L EasyApps -w | wc -l")
    }

    fun cleanRoot() {
        Logger.debug { "[UI] Flushing leftover iptables rules (root clean-up)" }
        viewModelScope.launch {
            val shell = Shell("su")

            shell.run("iptables -F -w && ip6tables -F -w")
            shell.run("iptables -t nat -F OUTPUT")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }
}
