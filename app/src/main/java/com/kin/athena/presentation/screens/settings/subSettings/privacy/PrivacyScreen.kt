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

package com.kin.athena.presentation.screens.settings.subSettings.privacy

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Carpenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DoDisturbAlt
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.RestorePage
import androidx.compose.material3.Icon
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.kin.athena.R
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.extensions.isFingerprintSupported
import com.kin.athena.core.utils.extensions.safeNavigate
import com.kin.athena.presentation.navigation.routes.HomeRoutes
import com.kin.athena.presentation.navigation.routes.SettingRoutes
import com.kin.athena.presentation.screens.settings.components.CustomListDialog
import com.kin.athena.presentation.screens.settings.components.IconType
import com.kin.athena.presentation.screens.settings.components.SettingType
import com.kin.athena.presentation.screens.settings.components.SettingsBox
import com.kin.athena.presentation.screens.settings.components.SettingsScaffold
import com.kin.athena.presentation.screens.settings.components.settingsContainer
import com.kin.athena.presentation.screens.settings.subSettings.lock.components.ActionType
import com.kin.athena.presentation.screens.settings.viewModel.SettingsViewModel
import com.kin.athena.domain.manager.BackupManager
import com.kin.athena.di.BackupManagerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacyScreen(
    navController: NavController,
    settings: SettingsViewModel,
) {
    val context = LocalContext.current
    val backupManager: BackupManager = EntryPointAccessors.fromApplication(
        context.applicationContext,
        BackupManagerEntryPoint::class.java
    ).backupManager()
    val scope = rememberCoroutineScope()

    // Dialogs state
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    // Store backup options
    var exportOptions by remember { mutableStateOf(BackupOptions()) }
    var importOptions by remember { mutableStateOf(BackupOptions()) }

    // Export backup launcher
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        backupManager.exportBackup(
                            outputStream = outputStream,
                            settings = settings.settings.value,
                            includeSettings = exportOptions.includeSettings,
                            includeDomains = exportOptions.includeDomains,
                            includeBlocklists = exportOptions.includeBlocklists,
                            includeApplications = exportOptions.includeApplications
                        ).onSuccess {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.backup_export_success), Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { error ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.backup_export_failed, error.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.debug(e) { "[Backup] Export to the chosen file failed" }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Import backup launcher
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        backupManager.importBackup(
                            inputStream = inputStream,
                            restoreSettings = importOptions.includeSettings,
                            restoreDomains = importOptions.includeDomains,
                            restoreBlocklists = importOptions.includeBlocklists,
                            restoreApplications = importOptions.includeApplications,
                            onSettingsRestored = { restoredSettings ->
                                settings.update(restoredSettings)
                            }
                        ).onSuccess {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.backup_import_success), Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { error ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.backup_import_failed, error.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.debug(e) { "[Backup] Import from the chosen file failed" }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.backup_import_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    SettingsScaffold(
        settings = settings,
        title = stringResource(id = R.string.settings_privacy),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        settingsContainer {
            SettingsBox(
                title = stringResource(id = R.string.privacy_screen_protection),
                description = stringResource(id = R.string.privacy_screen_protection_desc),
                icon = IconType.VectorIcon(Icons.Filled.RemoveRedEye),
                actionType = SettingType.SWITCH,
                variable = settings.settings.value.screenProtection,
                onSwitchEnabled = { settings.update(settings.settings.value.copy(screenProtection = it)) },
            )
            SettingsBox(
                title = stringResource(id = R.string.privacy_app_lock),
                description = stringResource(id = R.string.privacy_app_lock_desc),
                icon = IconType.VectorIcon(Icons.Filled.Lock),
                actionType = SettingType.CUSTOM,
                customAction = { onExit ->
                    OnLockClicked(onExit = { onExit() }, navController = navController, settings = settings)
                },
            )
            SettingsBox(
                isEnabled = settings.settings.value.passcode != null || settings.settings.value.pattern != null || settings.settings.value.fingerprint,
                title = stringResource(id = R.string.privacy_lock_on_resume),
                description = stringResource(id = R.string.privacy_lock_on_resume_desc),
                icon = IconType.VectorIcon(Icons.Rounded.LockReset),
                actionType = SettingType.SWITCH,
                variable = settings.settings.value.lockImmediately,
                onSwitchEnabled = { settings.update(settings.settings.value.copy(lockImmediately = it)) },
            )
        }
        settingsContainer {
            SettingsBox(
                title = stringResource(id = R.string.privacy_block_port_80),
                description = stringResource(id = R.string.privacy_block_port_80_desc),
                icon = IconType.VectorIcon(Icons.Filled.Report),
                actionType = SettingType.SWITCH,
                variable = settings.settings.value.blockPort80,
                onSwitchEnabled = { settings.update(settings.settings.value.copy(blockPort80 = it)) },
            )
            SettingsBox(
                title = stringResource(id = R.string.privacy_arp_spoofing),
                description = stringResource(id = R.string.privacy_in_development),
                icon = IconType.VectorIcon(Icons.Filled.Carpenter),
                actionType = SettingType.TEXT,
            )
            SettingsBox(
                title = stringResource(id = R.string.dns_rebind_protection),
                description = stringResource(id = R.string.privacy_in_development),
                icon = IconType.VectorIcon(Icons.Filled.Security),
                actionType = SettingType.TEXT,
            )
        }
        settingsContainer {
            SettingsBox(
                title = stringResource(id = R.string.backup_export),
                description = stringResource(id = R.string.backup_export_desc),
                icon = IconType.VectorIcon(Icons.Rounded.Backup),
                actionType = SettingType.CUSTOM,
                customAction = { onExit ->
                    showExportDialog = true
                    onExit()
                }
            )
            SettingsBox(
                title = stringResource(id = R.string.backup_import),
                description = stringResource(id = R.string.backup_import_desc),
                icon = IconType.VectorIcon(Icons.Rounded.RestorePage),
                actionType = SettingType.CUSTOM,
                customAction = { onExit ->
                    showImportDialog = true
                    onExit()
                }
            )
        }
    }

    // Show export selection dialog
    if (showExportDialog) {
        BackupSelectionDialog(
            title = stringResource(id = R.string.backup_export),
            onDismiss = { showExportDialog = false },
            onConfirm = { options ->
                exportOptions = options
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                val fileName = "athena_backup_${dateFormat.format(Date())}.json"
                exportBackupLauncher.launch(fileName)
            }
        )
    }

    // Show import selection dialog
    if (showImportDialog) {
        BackupSelectionDialog(
            title = stringResource(id = R.string.backup_import),
            onDismiss = { showImportDialog = false },
            onConfirm = { options ->
                importOptions = options
                importBackupLauncher.launch(arrayOf("application/json"))
            }
        )
    }
}

@Composable
private fun OnLockClicked(
    settings: SettingsViewModel,
    onExit: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current

    CustomListDialog(
        text = stringResource(id = R.string.privacy_app_lock),
        onExit = onExit
    ) {
        item {
            CreateSettingBox(
                title = stringResource(id = R.string.privacy_passcode),
                description = stringResource(id = R.string.privacy_authorize_passcode),
                isEnabled = settings.settings.value.passcode.isNullOrBlank(),
                onAction = {
                    onExit()
                    if (settings.settings.value.passcode.isNullOrBlank()) {
                        navController.safeNavigate(SettingRoutes.LockScreen.createRoute(ActionType.PASSCODE))
                    } else {
                        settings.update(
                            settings.settings.value.copy(
                                passcode = null,
                                defaultRoute = HomeRoutes.Home.route
                            )
                        )
                        settings.loadDefaultRoute()
                    }
                }
            )
            if (context.isFingerprintSupported()) {
                CreateSettingBox(
                    title = stringResource(id = R.string.lock_fingerprint),
                    description = stringResource(id = R.string.privacy_authorize_fingerprint),
                    isEnabled = !settings.settings.value.fingerprint,
                    onAction = {
                        onExit()
                        if (!settings.settings.value.fingerprint) {
                            navController.safeNavigate(SettingRoutes.LockScreen.createRoute(ActionType.FINGERPRINT))
                        } else {
                            settings.update(
                                settings.settings.value.copy(
                                    fingerprint = false,
                                    defaultRoute = HomeRoutes.Home.route
                                )
                            )
                            settings.loadDefaultRoute()
                        }
                    }
                )
            }
            CreateSettingBox(
                title = stringResource(id = R.string.privacy_pattern),
                description = stringResource(id = R.string.privacy_authorize_pattern),
                isEnabled = settings.settings.value.pattern.isNullOrBlank(),
                onAction = {
                    onExit()
                    if (settings.settings.value.pattern.isNullOrBlank()) {
                        navController.safeNavigate(SettingRoutes.LockScreen.createRoute(ActionType.PATTERN))
                    } else {
                        settings.update(
                            settings.settings.value.copy(
                                pattern = null,
                                defaultRoute = HomeRoutes.Home.route
                            )
                        )
                        settings.loadDefaultRoute()
                    }
                }
            )
        }
    }
}

@Composable
private fun CreateSettingBox(
    title: String,
    description: String,
    isEnabled: Boolean,
    onAction: () -> Unit
) {
    SettingsBox(
        title = title,
        description = description,
        actionType = SettingType.CUSTOM,
        customAction = {
            LaunchedEffect(Unit) {
                onAction()
            }
        },
        customButton = {
            Icon(
                imageVector = if (isEnabled) {
                    Icons.AutoMirrored.Rounded.ArrowForwardIos
                } else {
                    Icons.Rounded.DoDisturbAlt
                },
                contentDescription = "",
                modifier = Modifier.scale(0.75f)
            )
        }
    )
}
