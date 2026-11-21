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

package com.kin.athena.presentation.screens.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.kin.athena.R
import com.kin.athena.core.utils.extensions.*
import com.kin.athena.core.utils.isDeviceRooted
import com.kin.athena.core.utils.isRootGranted
import com.kin.athena.core.utils.ShizukuUtils
import com.kin.athena.domain.model.Application
import com.kin.athena.presentation.components.PermissionModal
import com.kin.athena.presentation.components.ComprehensivePermissionModal
import com.kin.athena.presentation.components.OnboardingOverlay
import com.kin.athena.presentation.components.material.MaterialButton
import com.kin.athena.presentation.components.material.MaterialScaffold
import com.kin.athena.presentation.config
import com.kin.athena.presentation.screens.home.components.BottomMenu
import com.kin.athena.presentation.screens.settings.viewModel.SettingsViewModel
import com.kin.athena.presentation.screens.home.components.SearchBar
import com.kin.athena.presentation.screens.home.viewModel.HomeViewModel
import com.kin.athena.presentation.screens.home.viewModel.ApplicationListState
import android.graphics.drawable.Drawable
import com.kin.athena.presentation.screens.settings.components.IconType
import com.kin.athena.presentation.screens.settings.components.SettingDialog
import com.kin.athena.presentation.screens.settings.components.SettingType
import com.kin.athena.presentation.screens.settings.components.SettingsBox
import com.kin.athena.presentation.components.material.MaterialText
import com.kin.athena.presentation.components.CircleWrapper
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.dimensionResource
import com.kin.athena.core.utils.extensions.toBitmap
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import com.kin.athena.core.logging.Logger
import com.kin.athena.presentation.screens.settings.subSettings.dns.components.MagiskSystemlessHostsDialog
import com.kin.athena.presentation.screens.settings.subSettings.dns.root.HostsManager
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallMode
import com.kin.athena.service.utils.manager.VpnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CoroutineCreationDuringComposition")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogsClicked: () -> Unit,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    onSettingsClicked: () -> Unit,
    onApplicationClicked: (String) -> Unit,
) {
    val rootState = rememberModalBottomSheetState()
    val sheetState = rememberModalBottomSheetState()
    val disableState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val isFirewallManager = homeViewModel.firewallManager.rulesLoaded.collectAsState()
    val context = LocalContext.current
    
    // Track security icon position for onboarding
    var securityIconPosition by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        homeViewModel.initialize(settingsViewModel)
    }

    // Refresh visible applications when returning to HomeScreen and close menu when navigating away
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // When navigating away, close the menu and mark that we should refresh on return
            homeViewModel.updateMenuStatus(false)
        }
    }

    androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.let { lifecycle ->
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    homeViewModel.refreshVisibleApplications()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
            }
        }
    }

    val firewallColor = getFirewallColor(isFirewallManager.value)

    fun onMenuClosed() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                homeViewModel.updateMenuStatus(false)
            }
        }
    }

    settingsViewModel.settings.value.logs.let {
        if (homeViewModel.menuStatus.value) {
            BottomMenu(
                sheetState = sheetState,
                onSettingsClicked = onSettingsClicked,
                onLogsClicked = onLogsClicked,
                onExit = { onMenuClosed() }
            )
        }
    }

    if (homeViewModel.magiskError.value) {
        MagiskSystemlessHostsDialog {
            homeViewModel.updateMagiskError(false)
            // Disable root mode when user dismisses the Magisk dialog
            // since they cannot use root mode without Magisk systemless hosts
            settingsViewModel.update(settingsViewModel.settings.value.copy(useRootMode = false))
        }
    }

    MaterialScaffold(
        topBar = {
            SearchBar(
                showStartInfo = false,
                query = homeViewModel.searchQuery.value,
                onQueryChange = { homeViewModel.updateSearchQuery(it) },
                onClearClick = { homeViewModel.clearSearchQuery() },
                button = {
                    if (settingsViewModel.settings.value.logs) {
                        MaterialButton(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "",
                        ) {
                            if (homeViewModel.menuStatus.value) {
                                onMenuClosed()
                            } else {
                                homeViewModel.updateMenuStatus(true)
                            }
                        }
                    } else {
                        MaterialButton(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "",
                        ) {
                            onSettingsClicked()
                        }
                    }
                },
                onFirewallClicked = {
                    settingsViewModel.update(settingsViewModel.settings.value.copy(dontShowHelp = true))
                    if (isFirewallManager.value == FirewallStatus.ONLINE) {
                        homeViewModel.setFirewallClicked(true)
                    } else {
                        handleFirewallClick(homeViewModel, context, settingsViewModel, isFirewallManager.value)
                    }
                },
                firewallColor = firewallColor,
                onFirewallIconPositioned = { position ->
                    securityIconPosition = position
                }
            )
        },
        content = {
            if (isFirewallManager.value.name() == FirewallStatus.OFFLINE.name()) {
                if (settingsViewModel.settings.value.useRootMode == true) {
                    homeViewModel.checkIfCleanedUp()
                    if (homeViewModel.rootUncleaned.value) {
                        fun onClick() {
                            scope.launch {
                                rootState.hide()
                            }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    homeViewModel.setRootUncleaned(false)
                                }
                            }
                        }
                        PermissionModal(
                            permissionName = stringResource(id = R.string.home_firewall_terminated),
                            permissionDescription = stringResource(id = R.string.firewall_terminated_description),
                            permissionRequest = stringResource(id = R.string.common_clear),
                            onDismiss = {
                                onClick()
                            },
                            onPermissionRequest = {
                                homeViewModel.cleanRoot()
                                onClick()
                            },
                            sheetState = rootState,
                            permissionAlternative = stringResource(id = R.string.common_cancel),
                            onPermissionAlternativeRequest = {
                                onClick()
                            }
                        )
                    }
                }
            }

            if (homeViewModel.firewallClicked.value) {
                fun onClick() {
                    scope.launch {
                        disableState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            homeViewModel.setRootUncleaned(false)
                        }
                        homeViewModel.setFirewallClicked(false)
                    }
                }
                if (settingsViewModel.settings.value.showDialog) {
                    PermissionModal(
                        permissionName = stringResource(id = R.string.home_firewall_disable),
                        permissionDescription = stringResource(id = R.string.home_firewall_permission_desc),
                        permissionRequest = stringResource(id = R.string.common_disable),
                        onDismiss = {
                            onClick()
                        },
                        onPermissionRequest =  {
                            handleFirewallClick(homeViewModel, context, settingsViewModel, isFirewallManager.value)
                            onClick()
                        },
                        sheetState = disableState,
                        permissionAlternative = stringResource(id = R.string.common_cancel),
                        onPermissionAlternativeRequest = {
                            onClick()
                        }
                    )
                } else {
                    handleFirewallClick(homeViewModel, context, settingsViewModel, isFirewallManager.value)
                    homeViewModel.setFirewallClicked(false)
                }
            }

            HomeScreenContent(settingsViewModel, homeViewModel, context, onApplicationClicked)
        }
    )

    // Progress dialog overlay - appears on top of everything (but not in Shizuku mode)
    val isShizukuMode = settingsViewModel.settings.value.useShizukuMode == true
    if (isFirewallManager.value.name() == FirewallStatus.LOADING().name() && !isShizukuMode) {
        MaterialYouProgressDialog(
            progress = isFirewallManager.value.getRulesLoaded(),
            onDismiss = { /* Cannot dismiss during loading */ }
        )
    }

    // Onboarding overlay for first-time users - show regardless of firewall status
    if (settingsViewModel.settings.value.isFirstTimeRunning) {
        OnboardingOverlay(
            targetIconPosition = securityIconPosition,
            onDismiss = {
                settingsViewModel.update(
                    settingsViewModel.settings.value.copy(isFirstTimeRunning = false)
                )
            },
            onFirewallClick = {
                settingsViewModel.update(settingsViewModel.settings.value.copy(dontShowHelp = true))
                handleFirewallClick(homeViewModel, context, settingsViewModel, isFirewallManager.value)
            }
        )
    }

    HandleComprehensivePermissions(homeViewModel, context, settingsViewModel, isFirewallManager.value)
}

@Composable
private fun HomeScreenContent(
    settingsViewModel: SettingsViewModel,
    homeViewModel: HomeViewModel,
    context: Context,
    onApplicationClicked: (String) -> Unit,
) {
    val applicationState = homeViewModel.applicationState.value
    val searchQuery = homeViewModel.searchQuery.value

    // Track animation state for search changes only
    var animationKey by remember { mutableStateOf(0) }
    var previousSearchQuery by remember { mutableStateOf(searchQuery) }
    var previousAppList by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFirstLoad by remember { mutableStateOf(true) }

    // Animate on search changes and initial load only
    LaunchedEffect(applicationState) {
        if (applicationState is ApplicationListState.Success) {
            val currentAppList = applicationState.applications.map { it.packageID }

            if (isFirstLoad) {
                isFirstLoad = false
                animationKey++
                previousAppList = currentAppList
            } else if (searchQuery != previousSearchQuery) {
                // Search query changed - animate
                if (currentAppList != previousAppList) {
                    previousSearchQuery = searchQuery
                    previousAppList = currentAppList
                    animationKey++
                }
            } else if (currentAppList != previousAppList) {
                // List structure changed but not search - don't animate to prevent scroll interference
                previousAppList = currentAppList
            }
        }
    }

    when (applicationState) {
        is ApplicationListState.Loading -> {
            // Don't show loading indicator - let the list appear instantly
        }

        is ApplicationListState.Error -> {
            // Don't show error - silently retry or show empty state
        }

        is ApplicationListState.Success -> {
            ProfessionalApplicationList(
                applications = applicationState.applications,
                viewModel = homeViewModel,
                settingsViewModel = settingsViewModel,
                onApplicationClicked = onApplicationClicked,
                animationKey = animationKey
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfessionalApplicationList(
    applications: List<Application>,
    viewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    onApplicationClicked: (String) -> Unit,
    animationKey: Int = 0
) {
    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.savedScrollIndex.value,
        initialFirstVisibleItemScrollOffset = viewModel.savedScrollOffset.value
    )

    // Save scroll position when scrolling
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.saveScrollPosition(index, offset)
        }
    }

    // Track visible items for controlled animations
    val visibleItems = remember(animationKey) { mutableStateMapOf<String, Boolean>() }
    val applicationIds = remember(applications) { applications.map { it.packageID } }

    // Control animations based on animation key
    LaunchedEffect(animationKey) {
        if (animationKey > 0) {
            // Clear visibility and stagger for animation (only on search/initial load)
            visibleItems.clear()
            applicationIds.forEachIndexed { index, packageId ->
                delay(25L) // Faster stagger to reduce scroll interference
                visibleItems[packageId] = true
            }
        } else {
            // No animation, make everything visible immediately
            applicationIds.forEach { packageId ->
                visibleItems[packageId] = true
            }
        }
    }

    // Ensure new items become visible immediately during normal updates
    LaunchedEffect(applicationIds) {
        applicationIds.forEach { packageId ->
            if (!visibleItems.containsKey(packageId)) {
                visibleItems[packageId] = true
            }
        }
    }

    // Load icons for visible applications - debounced to prevent excessive calls
    val primaryColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(applications.size) {
        Logger.info("HomeScreen: Applications list size changed to ${applications.size}")
        if (applications.isNotEmpty()) {
            // Small delay to debounce rapid size changes during pagination
            kotlinx.coroutines.delay(50)
            viewModel.loadIcons(
                applications = applications,
                settingsViewModel = settingsViewModel,
                color = primaryColor
            )
        }
    }

    // Pagination removed - all applications are loaded at once
    
    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp, 18.dp, 0.dp, 0.dp)),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        itemsIndexed(
            items = applications,
            key = { _, application -> application.packageID },
            contentType = { _, _ -> "application_item" }
        ) { index, application ->
            val icon = viewModel.iconMap.value[application.packageID]

            val shape = when {
                applications.size == 1 -> RoundedCornerShape(32.dp)
                index == 0 -> RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                index == applications.lastIndex -> RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
                else -> RoundedCornerShape(0.dp)
            }

            if (icon != null) {
                val description = application.packageID

                val isVisible = visibleItems[application.packageID] == true

                AnimatedVisibility(
                    visible = isVisible,
                    enter = if (animationKey > 0) {
                        fadeIn(
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + slideInVertically(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 6 }
                        )
                    } else {
                        EnterTransition.None
                    },
                    exit = ExitTransition.None,
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.clip(shape)
                    ) {
                        ProfessionalApplicationItem(
                            application = application,
                            displayName = application.displayName.ifEmpty { application.packageID },
                            description = description,
                            icon = icon,
                            settingsViewModel = settingsViewModel,
                            viewModel = viewModel,
                            onApplicationClicked = onApplicationClicked
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun ProfessionalApplicationItem(
    application: Application,
    displayName: String,
    description: String,
    icon: Drawable,
    settingsViewModel: SettingsViewModel,
    viewModel: HomeViewModel,
    onApplicationClicked: (String) -> Unit
) {
    Box(
        modifier = Modifier.alpha(if (application.bypassVpn) 0.5f else 1f)
    ) {
        CustomSettingsBox(
            title = displayName,
            description = description,
            icon = IconType.DrawableIcon(icon),
            actionType = SettingType.CUSTOM,
            circleWrapperColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            circleWrapperSize = if (settingsViewModel.settings.value.useDynamicIcons) 6.dp else 0.dp,
            customButton = {
                AccessControlButtons(
                    packageEntity = application,
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel
                )
            },
            customAction = { onApplicationClicked(application.packageID) },
            usesGMS = application.usesGooglePlayServices,
            onLongClick = { viewModel.togglePinned(application) },
            isPinned = application.isPinned
        )
    }
}

@Composable
private fun AccessControlButtons(
    packageEntity: Application,
    viewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel
) {
    var wifiAccess by remember(packageEntity.packageID, packageEntity.internetAccess) {
        mutableStateOf(packageEntity.internetAccess)
    }
    var cellularAccess by remember(packageEntity.packageID, packageEntity.cellularAccess) {
        mutableStateOf(packageEntity.cellularAccess)
    }

    // Check if app is bypassing VPN
    val isBypassed = packageEntity.bypassVpn
    val isShizukuMode = settingsViewModel.settings.value.useShizukuMode == true

    if (isBypassed) {
        // Show only VPN bypass icon for bypassed apps
        Icon(
            imageVector = Icons.Rounded.VpnLock,
            contentDescription = "VPN Bypassed",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(24.dp)
        )
    } else if (isShizukuMode) {
        // Show WiFi icon for Shizuku mode (can't control cellular and wifi separately)
        Icon(
            imageVector = Icons.Rounded.Wifi,
            contentDescription = null,
            tint = getAccessControlTint(wifiAccess),
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(24.dp)
                .clickable {
                    wifiAccess = !wifiAccess
                    viewModel.updatePackage(packageEntity.copy(internetAccess = wifiAccess, cellularAccess = wifiAccess), updateUI = true)
                }
        )
    } else {
        // Show normal wifi/cellular controls
        Row {
            IconButton(onClick = {
                wifiAccess = !wifiAccess
                viewModel.updatePackage(packageEntity.copy(internetAccess = wifiAccess), updateUI = true)
            }) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = getAccessControlTint(wifiAccess),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            IconButton(onClick = {
                cellularAccess = !cellularAccess
                viewModel.updatePackage(packageEntity.copy(cellularAccess = cellularAccess), updateUI = true)
            }) {
                Icon(
                    imageVector = Icons.Rounded.SignalCellularAlt,
                    contentDescription = null,
                    tint = getAccessControlTint(cellularAccess)
                )
            }
        }
    }
}

@Composable
private fun getFirewallColor(isFirewallActive: FirewallStatus?): Color {
    return when (isFirewallActive) {
        null -> MaterialTheme.colorScheme.outline
        FirewallStatus.ONLINE -> MaterialTheme.colorScheme.primary
        FirewallStatus.OFFLINE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
}

private fun handleFirewallClick(homeViewModel: HomeViewModel, context: Context, settingsViewModel: SettingsViewModel, isFirewallManager: FirewallStatus) {
    val isEnabling = isFirewallManager != FirewallStatus.ONLINE
    val settings = settingsViewModel.settings.value
    
    when {
        settings.useShizukuMode == true -> {
            // Shizuku mode - start firewall instantly
            if (isEnabling) {
                homeViewModel.updateFirewallStatus(FirewallStatus.ONLINE, FirewallMode.SHIZUKU)
            } else {
                homeViewModel.updateFirewallStatus(FirewallStatus.OFFLINE, FirewallMode.SHIZUKU)
            }
        }
        settings.useRootMode == true -> {
            CoroutineScope(Dispatchers.IO).launch {
                if (isRootGranted()) {
                    val hostManager = HostsManager(context, emptyList()).isHostsWritable()
                    if (config.hosts.items.isNotEmpty() && !hostManager.first)  {
                        homeViewModel.updateMagiskError(true)
                    } else {
                        homeViewModel.updateFirewallStatus(isFirewallManager.not(), FirewallMode.ROOT)
                    }
                } else {
                    settingsViewModel.update(settingsViewModel.settings.value.copy(useRootMode = false))
                }
            }
        }
        settings.useRootMode == false -> {
            if (isEnabling) {
                homeViewModel.updateVpnPermissionStatus(true)
            } else {
                homeViewModel.updateFirewallStatus(FirewallStatus.OFFLINE, FirewallMode.VPN)
            }
        }
        settings.useRootMode == null -> {
            if (VpnManager.permissionChecker(context)) {
                homeViewModel.updateFirewallStatus(isFirewallManager.not(), FirewallMode.VPN)
            } else {
                homeViewModel.updateVpnPermissionStatus(true)
            }
        }
    }
}

@Composable
private fun getAccessControlTint(isAccessEnabled: Boolean): Color {
    return if (isAccessEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandleComprehensivePermissions(homeViewModel: HomeViewModel, context: Context, settingsViewModel: SettingsViewModel, isFirewallManager: FirewallStatus) {
    val comprehensiveSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val settings = settingsViewModel.settings.value

    var askedByPermissionModal by remember { mutableStateOf(false) }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(isFirewallManager, settings.useRootMode, settings.useShizukuMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            !askedByPermissionModal &&
            isFirewallManager == FirewallStatus.ONLINE &&
            settings.useRootMode != true &&
            settings.useShizukuMode != true &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    if (homeViewModel.vpnPermissionRequested.value && isFirewallManager != FirewallStatus.ONLINE) {
        ComprehensivePermissionModal(
            context = context,
            sheetState = comprehensiveSheetState,
            onPermissionsComplete = { useVpn, useShizuku ->
                askedByPermissionModal = true
                // Update settings based on selected method
                settingsViewModel.update(
                    settingsViewModel.settings.value.copy(
                        useRootMode = if (useVpn == true) null else if (useShizuku == true) null else true,
                        useShizukuMode = useShizuku,
                        isFirstTimeRunning = false
                    )
                )
                
                // Start the firewall with the appropriate mode
                val mode = when {
                    useVpn == true -> FirewallMode.VPN
                    useShizuku == true -> FirewallMode.SHIZUKU
                    else -> FirewallMode.ROOT
                }
                homeViewModel.updateFirewallStatus(FirewallStatus.ONLINE, mode)
                
                // Reset permission state
                homeViewModel.updateVpnPermissionStatus(false)
                
                // Hide the modal
                scope.launch { comprehensiveSheetState.hide() }
            },
            onDismiss = {
                homeViewModel.updateVpnPermissionStatus(false)
                scope.launch { comprehensiveSheetState.hide() }
            }
        )
        
        // Auto-show the modal when permission is requested
        LaunchedEffect(homeViewModel.vpnPermissionRequested.value) {
            if (homeViewModel.vpnPermissionRequested.value) {
                comprehensiveSheetState.show()
            }
        }
    }
}

@Composable
private fun MaterialYouProgressDialog(
    progress: Float,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(24.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Header with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_applying_rules),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Progress information
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Circular progress with percentage
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(80.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 6.dp,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Progress stages text
                        Text(
                            text = getProgressStageText(progress),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Warning section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.home_warning_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.home_setup_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getProgressStageText(progress: Float): String {
    val currentCommand = (progress * 685).toInt() // Approximate total commands based on your logs
    val totalCommands = 685
    
    return when {
        progress < 0.01f -> stringResource(R.string.home_preparing_commands)
        progress < 1.0f -> stringResource(R.string.home_executing_commands, currentCommand, totalCommands)
        else -> stringResource(R.string.home_rules_applied)
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomSettingsBox(
    title: String,
    description: String,
    icon: IconType,
    actionType: SettingType,
    circleWrapperColor: Color,
    circleWrapperSize: androidx.compose.ui.unit.Dp,
    customButton: @Composable () -> Unit,
    customAction: () -> Unit,
    usesGMS: Boolean,
    onLongClick: (() -> Unit)? = null,
    isPinned: Boolean = false
) {
    val context = LocalContext.current
    var showCustomAction by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(bottom = dimensionResource(id = R.dimen.card_padding_bottom))
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { customAction() },
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.clickable { customAction() }
                }
            )
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(
                    horizontal = dimensionResource(id = R.dimen.card_padding_horizontal),
                    vertical = 12.dp
                )
                .fillMaxWidth()
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box {
                    when (icon) {
                        is IconType.DrawableIcon -> {
                            CircleWrapper(
                                size = circleWrapperSize,
                                color = circleWrapperColor
                            ) {
                                Image(
                                    bitmap = icon.drawable.toBitmap().asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(38.dp - circleWrapperSize)
                                        .width(38.dp - circleWrapperSize)
                                )
                            }
                        }
                        is IconType.VectorIcon -> {
                            CircleWrapper(
                                size = 12.dp,
                                color = circleWrapperColor
                            ) {
                                Icon(
                                    imageVector = icon.imageVector,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Pinned indicator - Material 3 filled pin icon at bottom right
                    if (isPinned) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(y = 4.dp) // Move down for better positioning
                                .size(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                
                if (usesGMS) {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                        Text(
                            text = description.substringBefore('\n'),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_gms_warning),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                } else {
                    MaterialText(
                        title = title,
                        description = description
                    )
                }
            }
            customButton()
        }
    }
}

