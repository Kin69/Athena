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

package com.kin.athena.service.firewall.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Process.INVALID_UID
import androidx.core.content.ContextCompat
import com.kin.athena.service.vpn.network.util.NetworkConstants
import com.kin.athena.core.logging.Logger
import com.kin.athena.service.firewall.model.FireWallModel
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.nio.ByteOrder
import javax.inject.Inject

fun isLocalIPv4(ip: String): Boolean {
    try {
        val octets = ip.split(".").map { it.toInt() }

        if (octets.size != 4 || octets.any { it !in 0..255 }) {
            return false
        }

        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 127 -> true
            else -> false
        }
    } catch (e: Exception) {
        return false
    }
}


class ConnectivityUtils @Inject constructor(
    private val context: Context
) {
    private val connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
    // Debug builds only. Called for every checked packet, so each kind of failure is logged once a minute.
    private val failureLog = Logger.Throttle()

    fun getConnectionOwnerUid(packet: FireWallModel): Int {
        if (connectivityManager == null) {
            Logger.error("ConnectivityManager is not available")
            return INVALID_UID
        }

        return try {
            val localInetSocketAddress = InetSocketAddress(packet.sourceIP, packet.sourcePort)
            val remoteInetSocketAddress = InetSocketAddress(packet.destinationIP, packet.destinationPort)

            val method = ConnectivityManager::class.java.getMethod(
                "getConnectionOwnerUid",
                Int::class.javaPrimitiveType,
                InetSocketAddress::class.java,
                InetSocketAddress::class.java
            )

            val uid = method.invoke(connectivityManager, packet.protocol, localInetSocketAddress, remoteInetSocketAddress) as? Int ?: INVALID_UID
            uid
        } catch (e: NoSuchMethodException) {
            if (failureLog.shouldLog("no-method")) Logger.debug { "[Rules] No getConnectionOwnerUid, reading /proc/net instead" }
            try {
                val uid = getUidForConnectionOldMethod(packet)
                return uid
            } catch (e: Exception) {
                if (failureLog.shouldLog(e.javaClass)) Logger.debug(e) { "[Rules] Cannot read the connection owner from /proc/net" }
                INVALID_UID
            }
        } catch (e: IllegalAccessException) {
            Logger.error("Illegal access to method: ${e.message}")
            INVALID_UID
        } catch (e: InvocationTargetException) {
            if (failureLog.shouldLog(e.targetException?.javaClass ?: e.javaClass)) {
                Logger.debug(e.targetException) { "[Rules] getConnectionOwnerUid failed for ${packet.destinationIP}:${packet.destinationPort}" }
            }
            INVALID_UID
        } catch (e: Exception) {
            Logger.error("Unexpected error: ${e.message}")
            INVALID_UID
        }
    }

    /**
     * Get the UID of the connection based on old method reading from /proc/net/tcp or /proc/net/udp
     */
    private fun getUidForConnectionOldMethod(fireWallModel: FireWallModel): Int {
        val filePath = when (fireWallModel.protocol) {
            NetworkConstants.TCP_PROTOCOL -> "/proc/net/tcp"
            NetworkConstants.UDP_PROTOCOL -> "/proc/net/udp"
            else -> return -1
        }

        val connectionList = File(filePath).readLines()

        for (line in connectionList.drop(1)) {
            val columns = line.trim().split(Regex("\\s+"))

            if (columns.size >= 8) {
                val localAddr = columns[1].split(":")
                val localIp = parseHexToIp(localAddr[0])
                val localPort = parseHexToPort(localAddr[1])

                val remoteAddr = columns[2].split(":")
                val remoteIp = parseHexToIp(remoteAddr[0])
                val remotePort = parseHexToPort(remoteAddr[1])

                val uid = columns[7].toInt()

                if (localIp == fireWallModel.sourceIP && localPort == fireWallModel.sourcePort &&
                    remoteIp == fireWallModel.destinationIP && remotePort == fireWallModel.destinationPort) {
                    return uid
                }
            }
        }

        return -1
    }
    private fun parseHexToIp(hexIp: String): String {
        val ipBytes = hexIp.chunked(2).map { it.toInt(16) }
        return ipBytes.reversed().joinToString(".")
    }

    private fun parseHexToPort(hexPort: String): Int {
        return hexPort.toInt(16)
    }
}