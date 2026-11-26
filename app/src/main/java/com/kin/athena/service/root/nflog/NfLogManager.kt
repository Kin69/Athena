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

package com.kin.athena.service.root.nflog

import android.content.Context
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Shell
import com.kin.athena.core.utils.extensions.resolveIpToHostname
import com.kin.athena.core.utils.registerShell
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.model.Log
import com.kin.athena.domain.model.Settings
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.service.firewall.model.FirewallResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

class NflogManager @Inject constructor(
    @ApplicationContext context: Context,
    private val preferencesUseCases: PreferencesUseCases,
    private val logUseCases: LogUseCases
) {
    var PID = 0
    val nflogPath = context.applicationInfo.nativeLibraryDir + "/libnflog.so"

    suspend fun start(packages: List<Application>, bypassSettings: Boolean = false) {
        preferencesUseCases.loadSettings.execute().fold(
            ifSuccess = { settings ->
                Logger.debug { "[Root] NFLOG start: logs=${settings.logs} bypassSettings=$bypassSettings apps=${packages.size}" }
                if (settings.logs || bypassSettings) {
                    startLog(settings, packages)
                }
            }
        )
    }

    suspend fun startLog(settings: Settings, packages: List<Application>) {
        val shell = Shell.registerShell("su")

        shell.addOnStderrLineListener(object : Shell.OnLineListener {
            override fun onLine(line: String) {
                Logger.error("stderr: $line")
            }
        })

        shell.addOnStdoutLineListener(object : Shell.OnLineListener {
            override fun onLine(line: String) {
                when {
                    line.startsWith("ERR Failed to register hook function") -> {
                        Logger.error("Trying to kill nflog service")
                        killService(settings.PID)
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(1000)
                            startLog(settings, packages)
                        }
                    }
                    line.startsWith("Process ID") -> {
                        val pid = extractPidFromOutput(line)
                        pid?.let {
                            PID = pid
                            CoroutineScope(Dispatchers.IO).launch {
                                preferencesUseCases.saveSettings.execute(settings = settings.copy(PID = pid))
                            }
                        }
                    }
                    line.startsWith("PKT") -> {
                        extractPacketInfo(line, packages)
                    }
                    else -> {
                        Logger.error(line)
                    }
                }
            }
        })

        shell.run("$nflogPath -group 58")
    }

    fun killService(pid: Int) {
        var uids: MutableList<Int>
        var commands = mutableListOf<String>()
        uids = getUids()
        if (pid != 0) {
            uids.add(pid)
        }

        Logger.error("Killing uids: $uids")

        for (uid in uids) {
            commands.add("toolbox kill -9 $uid || true")
            commands.add("toybox kill -9 $uid || true")
            commands.add("kill -9 $uid || true")
        }


        val shell = Shell.registerShell("su")

        commands.forEach { commnad ->
            shell.run(commnad)
        }
    }

    fun getUids(): MutableList<Int> {
        val shell = Shell("su")
        val uids = mutableListOf<Int>()

        shell.addOnStdoutLineListener(object : Shell.OnLineListener {
           override fun onLine(line: String) {
                val uid = line.split(" ").filter { it.isNotEmpty() }.getOrNull(1)?.toIntOrNull()
                if (uid != null && uid !in uids) {
                    uids.add(uid)
                }
            }
        })

        shell.run("lsof | grep nflog")

        return uids
    }



    suspend fun stop() {
        Logger.debug { "[Root] NFLOG stop pid=$PID" }
        killService(PID)
    }

    fun extractPidFromOutput(outputLine: String): Int? {
        return try {
            val regex = "Process ID \\(PID\\): (\\d+)".toRegex()
            val matchResult = regex.find(outputLine)

            matchResult?.groups?.get(1)?.value?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun extractPacketInfo(outputLine: String, packages: List<Application>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val line = outputLine.split(" ")

                val wordsToRemove = listOf("TIME:", "UID:", "SIP:", "SPT:", "DIP:", "DPT:")

                val filteredLine = line.map { word ->
                    wordsToRemove.fold(word) { acc, prefix ->
                        if (acc.startsWith(prefix)) acc.removePrefix(prefix) else acc
                    }
                }
                val finalLine = filteredLine.drop(2)

                val uid = finalLine[0].toInt()
                val application = packages.firstOrNull { it.uid == uid }
                val log = Log(
                    packageID = uid,
                    destinationIP = finalLine[4],
                    destinationPort = finalLine[5],
                    packetStatus = if (application?.cellularAccess != false && application?.internetAccess != false) FirewallResult.ACCEPT else FirewallResult.DROP,
                    sourceIP = finalLine[2],
                    sourcePort = finalLine[3],
                    protocol = finalLine[1],
                    destinationAddress = finalLine[4].resolveIpToHostname()
                )

                saveLog(log)

            } catch (e: Exception) {
                Logger.error("Error extracting packet $outputLine ${e.stackTraceToString()}")
            }
        }
    }

    fun saveLog(log: Log) {
        CoroutineScope(Dispatchers.IO).launch {
            logUseCases.addLog.execute(log)
        }
    }
}
