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

package com.kin.athena.presentation.components

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kin.athena.R
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.extensions.requestDisableBatteryOptimization
import com.kin.athena.core.utils.isDeviceRooted
import com.kin.athena.core.utils.grantRootAccess
import com.kin.athena.core.utils.ShizukuUtils
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComprehensivePermissionModal(
    context: Context,
    sheetState: SheetState,
    onPermissionsComplete: (useVpn: Boolean?, useShizuku: Boolean?) -> Unit,
    onDismiss: () -> Unit
) {
    var currentStep by remember { mutableStateOf(PermissionStep.VPN_METHOD) }
    var selectedMethod by remember { mutableStateOf<String?>(null) } // "vpn", "root", "shizuku"
    
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Proceed to battery optimization regardless of notification permission result
        context.requestDisableBatteryOptimization()
        // Complete the flow
        when (selectedMethod) {
            "vpn" -> onPermissionsComplete(true, false)
            "root" -> onPermissionsComplete(false, false)
            "shizuku" -> onPermissionsComplete(false, true)
        }
    }
    
    fun requestNotificationsAfterVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Skip notification request on older versions
            context.requestDisableBatteryOptimization()
            onPermissionsComplete(true, false) // VPN method
        }
    }

    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestNotificationsAfterVpn() }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // VPN mode forwards every app's traffic through this app's own sockets, so on
        // Android 17 LAN connections fail for all of them until this is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            requestNotificationsAfterVpn()
        }
    }
    
    // Shizuku permission listener
    val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            Logger.debug("Shizuku permission result: $grantResult")
            if (grantResult == 0) {
                // Permission granted, proceed to notifications
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.requestDisableBatteryOptimization()
                    onPermissionsComplete(false, true) // Shizuku method
                }
            } else {
                // Permission denied, show error or fallback
                Logger.warn("Shizuku permission denied")
                onDismiss()
            }
        }
    }
    
    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    if (sheetState.isVisible) {
        ModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp)
                        )
                )
            },
            content = {
                when (currentStep) {
                    PermissionStep.VPN_METHOD -> {
                        VpnMethodSelection(
                            context = context,
                            onVpnSelected = { 
                                selectedMethod = "vpn"
                                currentStep = PermissionStep.VPN_PERMISSION
                            },
                            onRootSelected = {
                                selectedMethod = "root"
                                currentStep = PermissionStep.ROOT_PERMISSION
                            },
                            onShizukuSelected = {
                                selectedMethod = "shizuku"
                                currentStep = PermissionStep.SHIZUKU_PERMISSION
                            }
                        )
                    }
                    PermissionStep.VPN_PERMISSION -> {
                        VpnPermissionRequest(
                            context = context,
                            vpnLauncher = vpnLauncher
                        )
                    }
                    PermissionStep.ROOT_PERMISSION -> {
                        RootPermissionRequest(
                            onPermissionGranted = {
                                // Root permission granted, proceed to notifications
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.requestDisableBatteryOptimization()
                                    onPermissionsComplete(false, false) // Root method
                                }
                            },
                            onPermissionDenied = {
                                // Root permission denied, go back to method selection
                                currentStep = PermissionStep.VPN_METHOD
                            }
                        )
                    }
                    PermissionStep.SHIZUKU_PERMISSION -> {
                        ShizukuPermissionRequest(
                            onPermissionGranted = {
                                // Permission granted, proceed to notifications
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.requestDisableBatteryOptimization()
                                    onPermissionsComplete(false, true) // Shizuku method
                                }
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun VpnMethodSelection(
    context: Context,
    onVpnSelected: () -> Unit,
    onRootSelected: () -> Unit,
    onShizukuSelected: () -> Unit
) {
    val isRooted = isDeviceRooted(context)
    val isShizukuAvailable = ShizukuUtils.isShizukuAvailable()
    
    Column(
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.vpn_permission_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        val hasLocalNetworkPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        val isVpnTheOnlyMethod = !isRooted && !isShizukuAvailable
        val description = stringResource(R.string.vpn_permission_description)
        Text(
            text = when {
                isVpnTheOnlyMethod && hasLocalNetworkPrompt -> stringResource(R.string.vpn_only_description_local_network)
                isVpnTheOnlyMethod -> stringResource(R.string.vpn_only_description)
                hasLocalNetworkPrompt -> "${description.trimEnd('.')}. ${stringResource(R.string.vpn_local_network_note)}"
                else -> description
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onVpnSelected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.method_use_vpn),
                fontWeight = FontWeight.Bold
            )
        }

        if (isRooted) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onRootSelected,
                colors = ButtonDefaults.buttonColors().copy(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.permissions_use_root_method),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        if (isShizukuAvailable) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onShizukuSelected,
                colors = ButtonDefaults.buttonColors().copy(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.permissions_use_shizuku_method),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun VpnPermissionRequest(
    context: Context,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
) {
    LaunchedEffect(Unit) {
        val intent = android.net.VpnService.prepare(context)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            // VPN already permitted, proceed to next step - will be handled by launcher callback
        }
    }
    
    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.permissions_setup),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.permissions_grant_required),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RootPermissionRequest(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val granted = grantRootAccess()
                if (granted) {
                    onPermissionGranted()
                } else {
                    onPermissionDenied()
                }
            } catch (e: Exception) {
                Logger.error("Root permission request failed: ${e.message}")
                onPermissionDenied()
            }
        }
    }
    
    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.permissions_setup),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.permissions_grant_required),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ShizukuPermissionRequest(onPermissionGranted: () -> Unit) {
    LaunchedEffect(Unit) {
        ShizukuUtils.requestShizukuPermission(onPermissionGranted)
    }
    
    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.permissions_setup),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.permissions_grant_shizuku),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

private enum class PermissionStep {
    VPN_METHOD,
    VPN_PERMISSION,
    ROOT_PERMISSION,
    SHIZUKU_PERMISSION
}