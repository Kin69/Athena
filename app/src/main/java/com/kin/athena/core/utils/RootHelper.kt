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

package com.kin.athena.core.utils

import android.content.Context
import android.content.pm.PackageManager
import com.kin.athena.core.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeoutException

fun checkForSuBinary(): Boolean {
    val paths = arrayOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su"
    )
    for (path in paths) {
        if (File(path).exists()) {
            return true
        }
    }
    return false
}


fun checkForRootManagementApps(packageManager: PackageManager): Boolean {
    val rootApps = listOf(
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "eu.chainfire.supersu",
        "com.thirdparty.superuser",
        "com.topjohnwu.magisk"
    )
    for (packageName in rootApps) {
        try {
            packageManager.getPackageInfo(packageName, 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            /* Ignore */
        }
    }
    return false
}

fun canAccessRestrictedAreas(): Boolean {
    return try {
        val file = File("/system/su_test.txt")
        file.createNewFile()
        file.delete()
        true
    } catch (e: IOException) {
        false
    }
}

fun checkForDangerousProps(): Boolean {
    val props = arrayOf(
        "ro.debuggable", "ro.secure"
    )
    for (prop in props) {
        val propValue = getSystemProperty(prop)
        if (prop == "ro.debuggable" && propValue == "1") return true
        if (prop == "ro.secure" && propValue == "0") return true
    }
    return false
}

fun getSystemProperty(propName: String): String? {
    try {
        val process = Runtime.getRuntime().exec("getprop $propName")
        val bufferedReader = process.inputStream.bufferedReader()
        return bufferedReader.readLine()
    } catch (e: Exception) {
        Logger.error("Failed to read system property $propName", e)
    }
    return null
}


fun isDeviceRooted(context: Context): Boolean {
    return checkForSuBinary() || checkForRootManagementApps(context.packageManager) || canAccessRestrictedAreas() || checkForDangerousProps()
}

suspend fun grantRootAccess(): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            writer.write("exit\n")
            writer.flush()
            writer.close()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: IOException) {
            Logger.error("Failed to request root access", e)
            false
        } catch (e: Exception) {
            Logger.error("Failed to request root access", e)
            false
        }
    }
}

suspend fun isRootGranted(): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "which su"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}

fun runRootCommand(commands: String){
    try {
        val process = Runtime.getRuntime().exec("su")
        val outputStream = DataOutputStream(process.outputStream)

        outputStream.writeBytes(commands)
        outputStream.writeBytes("exit\n")
        outputStream.flush()

    } catch (e: Exception) {
        Logger.error("Failed to run root command", e)
    }
}