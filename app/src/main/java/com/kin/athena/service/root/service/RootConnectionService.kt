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

package com.kin.athena.service.root.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Shell
import com.kin.athena.core.utils.constants.AppConstants
import com.kin.athena.core.utils.extensions.requiresNetworkPermissions
import com.kin.athena.core.utils.registerShell
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.model.Ip
import com.kin.athena.domain.model.Settings
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.presentation.screens.settings.subSettings.dns.hosts.RuleDatabase
import com.kin.athena.presentation.screens.settings.subSettings.dns.root.HostsManager
import com.kin.athena.service.firewall.handler.RuleHandler
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.root.nflog.NflogManager
import com.kin.athena.service.utils.manager.FirewallManager
import com.kin.athena.service.utils.notifications.showInstallNotification
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.service.utils.manager.FirewallService
import com.kin.athena.service.utils.notifications.showStartNotification
import com.kin.athena.service.utils.receiver.AppChangeCallback
import com.kin.athena.service.utils.receiver.AppChangeReceiver
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class RootConnectionService : Service(), CoroutineScope by CoroutineScope(Dispatchers.IO), FirewallService, AppChangeCallback {

    @Inject lateinit var networkLogger: NflogManager
    @Inject lateinit var preferencesUseCases: PreferencesUseCases
    @Inject lateinit var firewallRulesHandler: RuleHandler
    @Inject lateinit var applicationUseCases: ApplicationUseCases
    @Inject lateinit var packageManager: ApplicationUseCases
    @Inject lateinit var firewallManager: FirewallManager
    @Inject lateinit var networkFilterManager: NetworkFilterUseCases
    @Inject @ApplicationContext lateinit var appContext: Context
    var ruleManager = RuleDatabase()
    var domains: List<String>? = null



    private val appChangeReceiver: AppChangeReceiver = AppChangeReceiver(this)
    private val shellExecutor: Shell by lazy { Shell.SU }

    private var installedApplications: List<Application>? = null
    private var ipAddresses: List<Ip>? = null

    private val serviceContext: Context = this

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.debug { "[Root] onStartCommand action=${intent?.action} flags=$flags startId=$startId package=${intent?.getStringExtra("packageName")}" }
        intent?.action?.let { action ->
            when (action) {
                NetworkConstants.ACTION_TOGGLE_CELLURAL -> toggleAccess(intent.getStringExtra("packageName"), AccessType.CELLULAR)
                NetworkConstants.ACTION_TOGGLE_WIFI -> toggleAccess(intent.getStringExtra("packageName"), AccessType.WIFI)
                NetworkConstants.ACTION_STOP_ROOT -> onStopIntent()
                NetworkConstants.ACTION_START_ROOT -> onStartIntent()
            }
        }
        return START_STICKY
    }

    private fun toggleAccess(packageName: String?, accessType: AccessType) {
        packageName?.let { pkgName ->
            launch {
                packageManager.getApplication.execute(pkgName).fold(
                    ifSuccess = { application ->
                        val updatedApplication = application.toggleAccess(accessType)
                        Logger.debug {
                            "[Rules] Notification toggle $accessType $pkgName uid=${application.uid}: wifi ${application.internetAccess}->${updatedApplication.internetAccess} " +
                                "cell ${application.cellularAccess}->${updatedApplication.cellularAccess}"
                        }
                        packageManager.updateApplication.execute(updatedApplication).fold(
                            ifSuccess = {
                                firewallRulesHandler.updateAppRules(updatedApplication)
                                showInstallNotification(pkgName, updatedApplication)
                            }
                        )
                    },
                    ifFailure = {
                        Logger.debug { "[Rules] Toggle for unknown $pkgName, treating it as a new install" }
                        onAppInstalled(packageName)
                    }
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("MissingPermission")
    private fun showInstallNotification(packageName: String, application: Application) {
        launch {
            showInstallNotification(
                packageName = packageName,
                applicationUseCases = applicationUseCases,
                preferencesUseCases = preferencesUseCases,
                useRootMode = true,
                firewallManager = firewallManager,
                application = application
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = RootBinder()

    override fun updateRules(application: Application?) {
        application?.let { app ->
            launch {
                updateFirewallRules(app)
            }
        }
    }

    private fun updateFirewallRules(application: Application) {
        val wifiRule = if (application.internetAccess) "ACCEPT" else "REJECT"
        val cellularRule = if (application.cellularAccess) "ACCEPT" else "REJECT"

        val shell = Shell.registerShell("su")
        Logger.debug { "[Root] Rules for ${application.packageID} uid=${application.uid}: wifi=$wifiRule cell=$cellularRule" }

        shell.run("iptables -I EasyApps-wifi -m owner --uid-owner ${application.uid} -j $wifiRule -w")
        shell.run("iptables -I EasyApps-data -m owner --uid-owner ${application.uid} -j $cellularRule -w")

        runBlocking {
            packageManager.getApplications.execute().fold(
                ifSuccess = {
                    installedApplications = it
                    showStartNotification(installedApplications!!, preferencesUseCases, context = appContext)
                }
            )
        }
    }

    override fun startService(context: Context) {
        context.startService(Intent(context, RootConnectionService::class.java).apply {
            action = NetworkConstants.ACTION_START_ROOT
        })
    }

    override fun stopService(context: Context) {
        context.startService(Intent(context, RootConnectionService::class.java).apply {
            action = NetworkConstants.ACTION_STOP_ROOT
        })
    }

    override fun updateLogs(enabled: Boolean) {
        Logger.debug { "[Root] updateLogs enabled=$enabled" }
        launch {
            packageManager.getApplications.execute().fold(
                ifSuccess = {
                    if (enabled) {
                        networkLogger.start(it, bypassSettings = true)
                    } else {
                        networkLogger.stop()
                    }
                }
            )
        }
    }

    override fun updateScreen(value: Boolean) {
        Logger.debug { "[Root] updateScreen value=$value: not supported in root mode" }
    }

    override suspend fun updateDomains(progressCallback: (suspend (Int) -> Unit)?) {
        Logger.info("RootConnectionService: updateDomains() called - reloading domains")
        loadAndApplyDomains()
    }

    override fun updateHttpSettings() {
        Logger.debug { "[Root] updateHttpSettings: applied on the next start in root mode" }
    }
    
    override fun setDnsBlocking(enabled: Boolean) {
        firewallRulesHandler.setDnsBlocking(enabled)
    }
    
    override fun isDnsBlockingEnabled(): Boolean {
        return firewallRulesHandler.isDnsBlockingEnabled()
    }

    private suspend fun loadRules() {
        firewallManager.update(FirewallStatus.LOADING(0f))

        val applicationsJob = async {
            val allDeviceApps = getAllInstalledAppsFromDevice()
            installedApplications = allDeviceApps
        }
        val ipsJob = async { networkFilterManager.getIps.execute().fold(ifSuccess = { ipAddresses = it.first() }) }

        runBlocking {
            applicationsJob.await()
            ipsJob.await()
        }
        Logger.debug {
            val apps = installedApplications
            "[Root] Rules input: apps=${apps?.size} wifiBlocked=${apps?.count { !it.internetAccess }} cellBlocked=${apps?.count { !it.cellularAccess }} ips=${ipAddresses?.size}"
        }

        installedApplications?.let { apps ->
            ipAddresses?.let { ips ->
                showStartNotification(apps, preferencesUseCases)
                rootAppRules(apps, ips)
            }
        }
    }

    private suspend fun getAllInstalledAppsFromDevice(): List<Application> = withContext(Dispatchers.IO) {
        // Get all apps from database
        val dbApps = packageManager.getApplications.execute().fold(
            ifSuccess = { it },
            ifFailure = { emptyList() }
        )

        // Get all installed apps from device
        val allPackages = appContext.packageManager.getInstalledApplications(0)

        // Create a map of database apps by package ID for quick lookup
        val dbAppMap = dbApps.associateBy { it.packageID }

        // Merge: use database settings if available, otherwise default system apps to enabled
        val mergedApps = allPackages.mapNotNull { packageInfo ->
            val packageName = packageInfo.packageName
            val uid = packageInfo.uid
            val isSystem = (packageInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            // If app is in database, use its settings
            dbAppMap[packageName] ?: run {
                // Not in database - create default entry with system apps enabled
                if (isSystem) {
                    Application(
                        packageID = packageName,
                        uid = uid,
                        systemApp = true,
                        internetAccess = true,  // Default: allow wifi for system apps
                        cellularAccess = true,  // Default: allow cellular for system apps
                        bypassVpn = false,
                        isPinned = false
                    )
                } else {
                    // Non-system apps not in database shouldn't happen, but if they do, allow them
                    Application(
                        packageID = packageName,
                        uid = uid,
                        systemApp = false,
                        internetAccess = true,
                        cellularAccess = true,
                        bypassVpn = false,
                        isPinned = false
                    )
                }
            }
        }

        Logger.info("RootConnectionService: Loaded ${mergedApps.size} total apps (${dbApps.size} from DB, ${mergedApps.size - dbApps.size} system apps not in DB)")
        mergedApps
    }

    private fun onStartIntent() {
        Logger.info("RootConnectionService: onStartIntent() called")
        firewallManager.update(FirewallStatus.LOADING(0f))
        appChangeReceiver.register(this)
        
        launch { 
            loadRules()
            loadAndApplyDomains()
        }
    }
    
    private suspend fun loadAndApplyDomains() {
        Logger.info("RootConnectionService: Loading domains from RuleDatabase")
        domains = ruleManager.initialize(true)
        Logger.info("RootConnectionService: Loaded ${domains?.size ?: 0} domains from RuleDatabase")
        domains?.take(5)?.forEach { domain ->
            Logger.info("RootConnectionService: Sample domain: $domain")
        }
        
        // Apply domains to system hosts file if domains are available
        if (!domains.isNullOrEmpty()) {
            try {
                Logger.info("RootConnectionService: Creating HostsManager with ${domains!!.size} domains")
                val hostsManager = HostsManager(appContext, domains!!)
                Logger.info("RootConnectionService: Calling hostsManager.apply()")
                hostsManager.apply()
                Logger.info("RootConnectionService: Successfully applied ${domains!!.size} domains to system hosts file")
            } catch (e: Exception) {
                Logger.error("RootConnectionService: Failed to apply domains to hosts file: ${e.message}", e)
            }
        } else {
            Logger.warn("RootConnectionService: No domains to apply to hosts file")
        }
    }

    private fun onStopIntent() {
        Logger.debug { "[Root] onStopIntent domains=${domains?.size} by ${Logger.caller()}" }
        val hostsManager = domains?.let { HostsManager(appContext, it) }

        if (!domains.isNullOrEmpty()) {
            hostsManager?.revertToDefault()
        }
        appChangeReceiver.unregister(this)
        firewallManager.update(FirewallStatus.OFFLINE)
        launch {
            flushAppRules()
            networkLogger.stop()
        }
        stopForeground(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.debug { "[Root] Service destroyed" }
        onStopIntent()
    }

    inner class RootBinder : Binder() {
        val service: RootConnectionService
            get() = this@RootConnectionService
    }

    suspend fun rootAppRules(allPackages: List<Application>, ips: List<Ip>) = withContext(Dispatchers.IO) {
        setupEasyAppsChain(allPackages, ips)
        launch { networkLogger.start(allPackages) }
    }

    suspend fun flushAppRules() = withContext(Dispatchers.IO) {
        executeShellCommand("iptables -F -w && ip6tables -F -w")
        executeShellCommand("iptables -t nat -F OUTPUT")
    }

    suspend fun setupEasyAppsChain(applications: List<Application>, ips: List<Ip>) = withContext(Dispatchers.IO) {
        val hostsManager = domains?.let { HostsManager(appContext, it) }

        if (!domains.isNullOrEmpty()) {
            hostsManager?.apply()
        }

        val internetApps = applications.filter { it.requiresNetworkPermissions(serviceContext.packageManager) }
        val commands = mutableListOf<String>()
        val iptablesList = listOf("iptables", "ip6tables")

        iptablesList.forEach { iptables ->
            preferencesUseCases.loadSettings.execute().fold(
                ifSuccess = { settings ->
                    createChains(iptables, commands)
                    connectChains(iptables, commands)
                    createRejectChain(iptables, commands)
                    createAppRules(iptables, internetApps, commands)
                    closeChains(iptables, commands)
                    if (settings.blockPort80) {
                        block80(iptables, commands)
                    }
                    applyRules(iptables, commands)
                    createDNSRules(iptables, commands, settings)
                }
            )
        }
        applyIpRules("iptables", ips, commands)
        Logger.debug { "[Root] EasyApps chain: ${commands.size} commands for ${internetApps.size} network apps, ${ips.size} IPs" }

        // Execute commands individually with progress updates
        executeCommandsWithProgress(commands)
    }

    private fun block80(iptables: String, commands: MutableList<String>) {
        commands.add("$iptables -I EasyApps-ips -p tcp --dport 80 -j DROP -w")
    }

    private fun createChains(iptables: String, commands: MutableList<String>) {
        commands.addAll(listOf(
            "$iptables -I OUTPUT -j NFLOG --nflog-group ${AppConstants.NetworkConstants.NFLOG_GROUP_ID}",
            "$iptables -L EasyApps -w > /dev/null 2>&1 || $iptables -N EasyApps -w",
            "$iptables -L EasyApps-ips -w > /dev/null 2>&1 || $iptables -N EasyApps-ips -w",
            "$iptables -L EasyApps-wifi -w > /dev/null 2>&1 || $iptables -N EasyApps-wifi -w",
            "$iptables -L EasyApps-data -w > /dev/null 2>&1 || $iptables -N EasyApps-data -w",
            "$iptables -L EasyApps-reject -w > /dev/null 2>&1 || $iptables -N EasyApps-reject -w"
        ))
    }

    private fun connectChains(iptables: String, commands: MutableList<String>) {
        commands.addAll(listOf(
            "$iptables -A EasyApps -j EasyApps-ips",
            "$iptables -A EasyApps -o eth+ -j EasyApps-wifi",
            "$iptables -A EasyApps -o wlan+ -j EasyApps-wifi",
            "$iptables -A EasyApps -o tiwlan+ -j EasyApps-wifi",
            "$iptables -A EasyApps -o ra+ -j EasyApps-wifi",
            "$iptables -A EasyApps -o bnep+ -j EasyApps-wifi",
            "$iptables -A EasyApps -o rmnet+ -j EasyApps-data",
            "$iptables -A EasyApps -o pdp+ -j EasyApps-data",
            "$iptables -A EasyApps -o uwbr+ -j EasyApps-data",
            "$iptables -A EasyApps -o wimax+ -j EasyApps-data",
            "$iptables -A EasyApps -o vsnet+ -j EasyApps-data",
            "$iptables -A EasyApps -o rmnet_sdio+ -j EasyApps-data",
            "$iptables -A EasyApps -o ccmni+ -j EasyApps-data",
            "$iptables -A EasyApps -o qmi+ -j EasyApps-data",
            "$iptables -A EasyApps -o svnet0+ -j EasyApps-data",
            "$iptables -A EasyApps -o ccemni+ -j EasyApps-data",
            "$iptables -A EasyApps -o wwan+ -j EasyApps-data",
            "$iptables -A EasyApps -o cdma_rmnet+ -j EasyApps-data",
            "$iptables -A EasyApps -o clat4+ -j EasyApps-data",
            "$iptables -A EasyApps -o cc2mni+ -j EasyApps-data",
            "$iptables -A EasyApps -o bond1+ -j EasyApps-data",
            "$iptables -A EasyApps -o rmnet_smux+ -j EasyApps-data",
            "$iptables -A EasyApps -o ccinet+ -j EasyApps-data",
            "$iptables -A EasyApps -o v4-rmnet+ -j EasyApps-data",
            "$iptables -A EasyApps -o seth_w+ -j EasyApps-data",
            "$iptables -A EasyApps -o v4-rmnet_data+ -j EasyApps-data",
            "$iptables -A EasyApps -o rmnet_ipa+ -j EasyApps-data",
            "$iptables -A EasyApps -o rmnet_data+ -j EasyApps-data",
            "$iptables -A EasyApps -o r_rmnet_data+ -j EasyApps-data"
        ))
    }

    private fun createRejectChain(iptables: String, commands: MutableList<String>) {
        commands.addAll(
            listOf(
                "$iptables -A EasyApps-reject -p udp --dport 53 -m owner --uid-owner root -j ACCEPT -w",
                "$iptables -A EasyApps-reject -p tcp --dport 53 -j ACCEPT -w",
                "$iptables -A EasyApps-reject -p udp --dport 53 -j ACCEPT -w",
                "$iptables -A EasyApps-reject -j REJECT --reject-with ${if (iptables == "iptables") "icmp-port-unreachable" else "icmp6-adm-prohibited"} -w"
            )
        )
    }

    private fun createDNSRules(iptables: String, commands: MutableList<String>, settings: Settings) {
        if (iptables == "iptables") {
            // IPv4 DNS redirection
            commands.addAll(listOf(
                "$iptables -t nat -I OUTPUT -p udp --dport 53 -j DNAT --to ${settings.dnsServer1}:53",
                "$iptables -t nat -I OUTPUT -p tcp --dport 53 -j DNAT --to ${settings.dnsServer1}:53"
            ))
        } else if (iptables == "ip6tables") {
            // IPv6 DNS redirection
            commands.addAll(listOf(
                "$iptables -t nat -I OUTPUT -p udp --dport 53 -j DNAT --to [${settings.dnsServer1v6}]:53",
                "$iptables -t nat -I OUTPUT -p tcp --dport 53 -j DNAT --to [${settings.dnsServer1v6}]:53"
            ))
        }
    }

    private fun createAppRules(iptables: String, internetApps: List<Application>, commands: MutableList<String>) {
        internetApps.forEach { application ->
            if (application.internetAccess) {
                commands.add("$iptables -A EasyApps-wifi -m owner --uid-owner ${application.uid} -j ACCEPT -w")
            }
            if (application.cellularAccess) {
                commands.add("$iptables -A EasyApps-data -m owner --uid-owner ${application.uid} -j ACCEPT -w")
            }
        }
    }

    private fun applyIpRules(iptables: String, ips: List<Ip>, commands: MutableList<String>) {
        ips.forEach { ip ->
            commands.add("$iptables -I EasyApps-ips -d ${ip.ip} -j DROP -w")
        }
    }

    private fun closeChains(iptables: String, commands: MutableList<String>) {
        commands.addAll(listOf(
            "$iptables -A EasyApps-wifi -j EasyApps-reject -w",
            "$iptables -A EasyApps-data -j EasyApps-reject -w",
        ))
    }

    private fun applyRules(iptables: String, commands: MutableList<String>) {
        commands.add("$iptables -A OUTPUT -j EasyApps -w")
    }

    private fun updateLoading(completedSteps: Int, totalSteps: Int) {
        if (::firewallManager.isInitialized) {
            val progress = completedSteps.toFloat() / totalSteps.toFloat()
            firewallManager.update(FirewallStatus.LOADING(progress))

            if (progress == 1f) {
                firewallManager.update(FirewallStatus.ONLINE)
            }
        }
    }

    fun isNumber(input: String): Boolean {
        return input.toDoubleOrNull() != null
    }

    private fun executeCommandsWithProgress(commands: List<String>) {
        val totalCommands = commands.size
        commands.forEachIndexed { index, command ->
            try {
                Logger.info("RootConnectionService: Executing command ${index + 1}/$totalCommands: $command")
                shellExecutor.run(command)
                
                // Update progress based purely on command execution
                val progress = (index + 1).toFloat() / totalCommands.toFloat()
                firewallManager.update(FirewallStatus.LOADING(progress))
                
                // Small delay to make progress visible
                Thread.sleep(10)
                
            } catch (e: Exception) {
                Logger.error("RootConnectionService: Failed to execute command: $command", e)
            }
        }
        
        // Mark as complete
        firewallManager.update(FirewallStatus.ONLINE)
        Logger.info("RootConnectionService: All firewall rules applied successfully")
    }

    private fun executeShellCommand(command: String, totalCmd: Int? = null) {
        shellExecutor.addOnStdoutLineListener(object : Shell.OnLineListener {
                override fun onLine(line: String) {
                    if (line.isNotBlank()) {
                        totalCmd?.let {
                            if (isNumber(line)) {
                                updateLoading(line.toInt(), it)
                            }
                        }
                    }
                }
            }
        )

        shellExecutor.run(command)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onAppInstalled(packageName: String?) {
        Logger.debug { "[Pkg] Installed $packageName, showing the install notification (root mode)" }
        launch {
            packageName?.let {
                showInstallNotification(
                    it,
                    applicationUseCases,
                    preferencesUseCases,
                    true,
                    firewallManager,
                )
            }
        }
    }

    override fun onAppUninstalled(packageName: String?) {
        Logger.debug { "[Pkg] Uninstalled $packageName" }
        // Handle app uninstallation logic here
    }

    private fun Application.toggleAccess(accessType: AccessType): Application {
        return when (accessType) {
            AccessType.WIFI -> copy(internetAccess = !internetAccess)
            AccessType.CELLULAR -> copy(cellularAccess = !cellularAccess)
        }
    }

    enum class AccessType {
        WIFI, CELLULAR
    }
}