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
 * You should hinave received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.kin.athena.service.vpn.service

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kin.athena.core.logging.Logger
import com.kin.athena.service.firewall.handler.RuleHandler
import com.kin.athena.service.firewall.handler.filterPacket
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.vpn.network.transport.tcp.TCPHeader
import com.kin.athena.service.vpn.network.transport.udp.UDPModel
import com.kin.athena.service.vpn.network.transport.udp.toUDPHeader
import com.kin.athena.service.vpn.network.transport.udp.extractUDPData
import com.kin.athena.service.vpn.network.transport.udp.handleDnsResponse
import com.kin.athena.service.vpn.network.transport.icmp.ICMPModel
import com.kin.athena.service.vpn.network.transport.icmp.toICMPPacket
import com.kin.athena.service.vpn.network.transport.ipv4.IPv4
import com.kin.athena.service.vpn.network.transport.ipv4.toIPv4Header
import com.kin.athena.service.vpn.network.transport.dns.DNSModel
import com.kin.athena.service.vpn.network.transport.dns.toDNSModel
import com.kin.athena.service.vpn.network.transport.udp.soarResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TunnelManager(
    private val ruleHandler: RuleHandler? = null,
    private val dnsServerV4: String = "9.9.9.9",
    private val dnsServerV6: String = "2620:fe::fe"
) {

    private var contextPtr: Long = 0
    private val lock = Any()
    private var isReleased = false



    fun initialize(): Boolean {
        contextPtr = jni_init(Build.VERSION.SDK_INT)
        Logger.debug { "[Engine] jni_init sdk=${Build.VERSION.SDK_INT} -> ${if (contextPtr != 0L) "ok" else "failed"}" }
        if (contextPtr != 0L) {
            // Set DNS servers in native code
            jni_set_dns_servers(contextPtr, dnsServerV4, dnsServerV6)
        }
        return contextPtr != 0L
    }
    
    fun start(logLevel: Int) {
        Logger.debug { "[Engine] start logLevel=$logLevel initialized=${contextPtr != 0L}" }
        if (contextPtr != 0L) {
            jni_start(contextPtr, logLevel)
        }
    }
    
    fun run(tunFd: Int, forwardDns: Boolean = true, rcode: Int = 3) {
        if (contextPtr != 0L) {
            val startedAt = SystemClock.uptimeMillis()
            Logger.debug { "[Engine] jni_run fd=$tunFd forwardDns=$forwardDns rcode=$rcode on ${Thread.currentThread().name}" }
            jni_run(contextPtr, tunFd, forwardDns, rcode)
            Logger.debug { "[Engine] jni_run fd=$tunFd exited after ${SystemClock.uptimeMillis() - startedAt} ms" }
        } else {
            Logger.debug { "[Engine] run fd=$tunFd skipped: not initialized" }
        }
    }
    
    fun stop() {
        Logger.debug { "[Engine] stop initialized=${contextPtr != 0L}" }
        if (contextPtr != 0L) {
            jni_stop(contextPtr)
        }
    }
    
    fun done() {
        Logger.debug { "[Engine] done initialized=${contextPtr != 0L}" }
        if (contextPtr != 0L) {
            jni_done(contextPtr)
            contextPtr = 0L
        }
    }
    
    fun getMtu(): Int {
        return jni_get_mtu()
    }
    
    fun getProperty(name: String): String {
        return jni_getprop(name)
    }
    
    fun setDnsServers(dnsV4: String, dnsV6: String) {
        if (contextPtr != 0L) {
            jni_set_dns_servers(contextPtr, dnsV4, dnsV6)
        }
    }
    
    fun clearSessions() {
        // Debug logs only: a long wait for the lock means the clear was queued behind release()
        val calledAt = SystemClock.uptimeMillis()
        synchronized(lock) {
            try {
                if (isReleased) {
                    Logger.warn("Cannot clear sessions: TunnelManager has been released")
                    return
                }
                val contextPtrSnapshot = contextPtr
                if (contextPtrSnapshot != 0L) {
                    val lockedAt = SystemClock.uptimeMillis()
                    Logger.debug { "[Engine] clearSessions by ${Logger.caller()} on ${Thread.currentThread().name}, waited ${lockedAt - calledAt} ms for the lock" }
                    jni_clear_sessions(contextPtrSnapshot)
                    Logger.debug { "[Engine] jni_clear_sessions blocked for ${SystemClock.uptimeMillis() - lockedAt} ms" }
                } else {
                    Logger.warn("Cannot clear sessions: TunnelManager not properly initialized")
                }
            } catch (e: UnsatisfiedLinkError) {
                Logger.error("Native library unavailable for clearSessions: ${e.message}")
            } catch (e: Exception) {
                Logger.error("Error in clearSessions: ${e.message}", e)
            }
        }
    }

    private fun onTcpPacketReceived(data: ByteArray, length: Int, direction: String): Boolean {
        return try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            // Check IP version - first 4 bits
            val firstByte = buffer.get(0).toInt() and 0xFF
            val ipVersion = (firstByte shr 4) and 0xF
            
            if (ipVersion == 6) {
                // IPv6 packets - currently not supported for TCP filtering
                Log.d("PacketFilter", "[$direction] IPv6 TCP packet detected, allowing (not implemented)")
                return true
            } else if (ipVersion != 4) {
                Log.w("PacketFilter", "[$direction] Unknown IP version $ipVersion, blocking")
                return false
            }
            
            val ipHeader = buffer.toIPv4Header()
            val tcpHeader = TCPHeader.fromByteBuffer(buffer.slice())

            // Use existing firewall filtering logic
            ruleHandler?.let { ruleHandler ->
                val isSynPacket = tcpHeader.flags.contains(com.kin.athena.service.vpn.network.transport.tcp.TCPFlag.SYN) && 
                                 !tcpHeader.flags.contains(com.kin.athena.service.vpn.network.transport.tcp.TCPFlag.ACK)
                val filterResult = filterPacket(tcpHeader, ipHeader, ruleHandler, bypassCheck = !isSynPacket)
                
                val allowed = filterResult.first
                
                allowed
            } ?: run {
                Log.d("PacketFilter", "[$direction] TCP: No RuleHandler, allowing packet")
                true // Allow if no rule handler
            }
        } catch (e: Exception) {
            // For malformed packets (like Data Offset 0), block them for security
            if (e.message?.contains("Malformed TCP packet") == true) {
                // Log full packet data for debugging
                val packetHex = data.take(minOf(length, 100)).joinToString(" ") { "%02x".format(it) }
                Log.d("PacketFilter", "[$direction] TCP: Blocking malformed packet: ${e.message}")
                Log.d("PacketFilter", "[$direction] TCP: Full packet hex (first 100 bytes): $packetHex")
                false // Block malformed packets
            } else {
                Log.e("PacketFilter", "Error filtering TCP packet: ${e.message}")
                true // Allow packet if other parsing errors occur
            }
        }
    }

    private fun onUdpPacketReceived(data: ByteArray, length: Int, direction: String): Boolean {
        return try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            val ipHeader = buffer.toIPv4Header()
            val udpStartPosition = buffer.position()
            val udpHeaderBuffer = buffer.slice()
            val udpHeader = udpHeaderBuffer.toUDPHeader()
            
            // Use existing firewall filtering logic
            ruleHandler?.let { ruleHandler ->
                val isDnsPacket = udpHeader.destinationPort == 53
                val dnsModel: DNSModel? = if (isDnsPacket) {
                    try {
                        val udpData = udpHeader.extractUDPData(udpHeaderBuffer)
                        ByteBuffer.wrap(udpData).toDNSModel()

                    } catch (e: Exception) {
                        Log.w("PacketFilter", "Failed to parse DNS model: ${e.message}")
                        null
                    }
                } else null

                val filterResult = filterPacket(udpHeader, ipHeader, ruleHandler, dnsModel)
                val allowed = filterResult.first
                val firewallResult = filterResult.third
                
                // Handle DNS blocking with SOAR response
                if (isDnsPacket && firewallResult == FirewallResult.DNS_BLOCKED) {
                    try {
                        // Create a buffer with UDP header and data for soarResponse
                        val udpPacketData = ByteBuffer.wrap(data, udpStartPosition, length - udpStartPosition)
                        udpPacketData.order(ByteOrder.BIG_ENDIAN)
                        
                        Log.d("PacketFilter", "[$direction] DNS: Creating SOAR response for blocked domain, UDP data size: ${udpPacketData.remaining()}")
                        val soarResponsePayload = soarResponse(udpPacketData, ipHeader)
                        Log.d("PacketFilter", "[$direction] DNS: SOAR response payload created, size: ${soarResponsePayload.size}")
                        
                        // Create complete response packet using Kotlin packet creation system
                        val completeResponsePacket = handleDnsResponse(udpHeader, ipHeader, soarResponsePayload)
                        Log.d("PacketFilter", "[$direction] DNS: Complete response packet created, size: ${completeResponsePacket.size}")
                        
                        // Send complete packet through VPN interface
                        if (contextPtr != 0L) {
                            jni_send_complete_packet(contextPtr, completeResponsePacket)
                        }
                        
                        Log.d("PacketFilter", "[$direction] DNS: Sent SOAR response for blocked domain")
                        false // Block original packet
                    } catch (e: Exception) {
                        Log.e("PacketFilter", "Error creating/sending SOAR response: ${e.message}", e)
                        false // Block packet on error
                    }
                } else {
                    allowed
                }
            } ?: run {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun onIcmpPacketReceived(data: ByteArray, length: Int, direction: String): Boolean {
        return try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            val ipHeader = buffer.toIPv4Header()
            val icmpPacket = buffer.slice().toICMPPacket()

            ruleHandler?.let { ruleHandler ->
                val filterResult = filterPacket(icmpPacket, ipHeader, ruleHandler)
                
                val allowed = filterResult.first

                allowed
            } ?: run {
                Log.d("PacketFilter", "[$direction] ICMP: No RuleHandler, allowing packet")
                true
            }
        } catch (e: Exception) {
            Log.e("PacketFilter", "Error filtering ICMP packet: ${e.message}")
            true
        }
    }

    fun release() {
        Logger.debug { "[Engine] release released=$isReleased initialized=${contextPtr != 0L}" }
        synchronized(lock) {
            if (!isReleased) {
                isReleased = true
                val contextPtrSnapshot = contextPtr
                if (contextPtrSnapshot != 0L) {
                    try {
                        stop()
                        done()
                    } catch (e: UnsatisfiedLinkError) {
                        Logger.error("Native library unavailable during release: ${e.message}")
                    } catch (e: Exception) {
                        Logger.error("Error during TunnelManager release: ${e.message}", e)
                    } finally {
                        contextPtr = 0L
                    }
                    Logger.info("TunnelManager resources released")
                } else {
                    Logger.info("TunnelManager was already released or not initialized")
                }
            }
        }
    }


    private external fun jni_init(sdk: Int): Long
    private external fun jni_start(context: Long, loglevel: Int)
    private external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jni_stop(context: Long)
    private external fun jni_done(context: Long)
    private external fun jni_getprop(name: String): String
    private external fun jni_get_mtu(): Int
    private external fun jni_clear_sessions(context: Long)
    private external fun jni_set_dns_servers(context: Long, dnsV4: String, dnsV6: String)
    private external fun jni_send_complete_packet(context: Long, packetData: ByteArray)

    companion object {
        init {
            System.loadLibrary("athena")
        }
    }
}