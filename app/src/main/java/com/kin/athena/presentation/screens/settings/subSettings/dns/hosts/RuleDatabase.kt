/* Copyright (C) 2024 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016 - 2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */


package com.kin.athena.presentation.screens.settings.subSettings.dns.hosts

import androidx.collection.MutableIntSet
import androidx.collection.intSetOf
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Shell
import com.kin.athena.presentation.config
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader
import javax.inject.Singleton

@Singleton
class RuleDatabase {

    companion object {
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val IPV6_LOOPBACK = "::1"
        private const val NO_ROUTE = "0.0.0.0"

        @Deprecated("Use BlocklistParser.parseLine() instead")
        fun parseLine(line: String): String? {
            if (line.isEmpty() || line.isBlank()) {
                return null
            }

            var endOfLine = line.indexOf('#')
            if (endOfLine == -1) {
                endOfLine = line.length
            }

            if (endOfLine <= 0) {
                return null
            }

            var startOfHost = 0
            val ipv4LoopbackIndex = line.lastIndexOf(IPV4_LOOPBACK)
            if (ipv4LoopbackIndex != -1) {
                startOfHost += (ipv4LoopbackIndex + IPV4_LOOPBACK.length)
            }
            if (startOfHost == 0) {
                val ipv6LoopbackIndex = line.lastIndexOf(IPV6_LOOPBACK)
                if (ipv6LoopbackIndex != -1) {
                    startOfHost += (ipv6LoopbackIndex + IPV6_LOOPBACK.length)
                }
            }
            if (startOfHost == 0) {
                val noRouteIndex = line.lastIndexOf(NO_ROUTE)
                if (noRouteIndex != -1) {
                    startOfHost += (noRouteIndex + NO_ROUTE.length)
                }
            }

            if (startOfHost >= endOfLine) {
                return null
            }

            while (startOfHost < endOfLine && Character.isWhitespace(line[startOfHost])) {
                startOfHost++
            }
            while (startOfHost < endOfLine && Character.isWhitespace(line[endOfLine - 1])) {
                endOfLine--
            }

            val host = line.substring(startOfHost, endOfLine).lowercase()
            if (host.isEmpty() || host.any { Character.isWhitespace(it) }) {
                return null
            }
            return host
        }
    }

    var blockedHosts = MutableStateFlow(atomic(intSetOf()))
    private val wildcardRules = mutableListOf<BlocklistRule.WildcardDomain>()
    private val regexRules = mutableListOf<BlocklistRule.RegexPattern>()
    private val whitelistedHosts = mutableSetOf<String>()

    fun isBlocked(host: String): Boolean {
        val lowerHost = host.lowercase()

        // Check if whitelisted first
        if (whitelistedHosts.contains(lowerHost)) {
            return false
        }

        // Check exact match in blocked hosts
        if (blockedHosts.value.value.contains(lowerHost.hashCode())) {
            return true
        }

        // Check wildcard rules
        for (rule in wildcardRules) {
            if (rule.regex.matches(lowerHost)) {
                return true
            }
        }

        // Check regex rules
        for (rule in regexRules) {
            if (rule.regex.matches(lowerHost)) {
                return true
            }
        }

        return false
    }


    @Throws(InterruptedException::class)
    suspend fun initialize(rootMode: Boolean = false, progressCallback: (suspend (Int) -> Unit)? = null): List<String>? {
        val startedAt = System.currentTimeMillis()

        val sortedHostItems = config.hosts.items
            .mapNotNull {
                if (it.state != HostState.IGNORE) it else null
            }
            .sortedBy { it.state.ordinal }

        val newHosts = MutableIntSet(sortedHostItems.size + config.hosts.exceptions.size)
        val allHosts = mutableListOf<String>()

        // Clear previous wildcard and regex rules
        wildcardRules.clear()
        regexRules.clear()
        whitelistedHosts.clear()

        val totalItems = sortedHostItems.size
        Logger.debug {
            "[Blocklist] Loading domain rules rootMode=$rootMode lists=$totalItems of ${config.hosts.items.size} " +
                "exceptions=${config.hosts.exceptions.size}"
        }
        for ((index, item) in sortedHostItems.withIndex()) {
            if (Thread.interrupted()) {
                throw InterruptedException("Interrupted")
            }
            val hostsFromItem = loadItem(newHosts, item, rootMode)
            if (rootMode && hostsFromItem != null) {
                allHosts.addAll(hostsFromItem)
            }

            // Report progress: 75-95% for parsing
            progressCallback?.invoke(75 + ((index + 1) * 20) / totalItems.coerceAtLeast(1))
        }

        if (rootMode) {
            Logger.debug { "[Blocklist] Root mode: ${allHosts.size} hosts loaded in ${System.currentTimeMillis() - startedAt} ms" }
            return allHosts
        }

        for (exception in config.hosts.exceptions) {
            if (Thread.interrupted()) {
                throw InterruptedException("Interrupted")
            }
            addHostException(newHosts, exception)
        }

        blockedHosts.value = atomic(newHosts)
        val totalRules = newHosts.size + wildcardRules.size + regexRules.size
        Logger.info("Domain rules loaded: $totalRules total (${newHosts.size} domains, ${wildcardRules.size} wildcards, ${regexRules.size} regex, ${whitelistedHosts.size} whitelisted)")
        Logger.debug { "[Blocklist] Domain rules loaded in ${System.currentTimeMillis() - startedAt} ms" }
        Runtime.getRuntime().gc()
        return null
    }

    @Throws(InterruptedException::class)
    private fun loadItem(set: MutableIntSet, item: HostFile, rootMode: Boolean): List<String>? {
        if (item.state == HostState.IGNORE) {
            return null
        }

        val reader = try {
            FileHelper.openItemFile(item)
        } catch (e: FileNotFoundException) {
            Logger.warn("${item.title}: File not found, skipping")
            return null
        }

        val hosts = mutableListOf<String>()

        if (reader == null) {
            val host = addHost(set, item, item.data, rootMode)
            if (rootMode && host != null) {
                hosts.add(host)
                return hosts
            }
        } else {
            val readerHosts = loadReader(set, item, reader, rootMode)
            if (rootMode && readerHosts != null) {
                hosts.addAll(readerHosts)
                return hosts
            }
        }
        return if (rootMode) hosts else null
    }

    private fun addHost(set: MutableIntSet, item: Host, host: String, rootMode: Boolean): String? {
        when (item.state) {
            HostState.ALLOW -> {
                set.remove(host.hashCode())
                return if (rootMode) host else null
            }
            HostState.DENY -> {
                if (rootMode) {
                    return host
                } else {
                    set.add(host.hashCode())
                    return null
                }
            }
            else -> return null
        }
    }

    private fun addHostException(set: MutableIntSet, exception: HostException) {
        when (exception.state) {
            HostState.ALLOW -> set.remove(exception.data.hashCode())
            HostState.DENY -> set.add(exception.data.hashCode())
            else -> return
        }
    }

    @Throws(InterruptedException::class)
    private fun loadReader(set: MutableIntSet, item: Host, reader: Reader, rootMode: Boolean): List<String>? {
        val hosts = if (rootMode) ArrayList<String>(10000) else null
        var count = 0
        var wildcardCount = 0
        var regexCount = 0
        var whitelistCount = 0
        val isDeny = item.state == HostState.DENY

        try {
            Logger.debug("Loading rules from ${item.title}")
            BufferedReader(reader, 65536).use { bufferedReader ->
                var line = bufferedReader.readLine()
                while (line != null) {
                    // Check interruption every 1000 lines for better performance
                    if (count % 1000 == 0 && Thread.interrupted()) {
                        throw InterruptedException("Interrupted")
                    }

                    // Use new parser
                    val rule = BlocklistParser.parseLine(line)
                    if (rule != null) {
                        when (rule) {
                            is BlocklistRule.PlainDomain -> {
                                count++
                                val result = addHost(set, item, rule.domain, rootMode)
                                if (rootMode && result != null) {
                                    hosts!!.add(result)
                                }
                            }
                            is BlocklistRule.WildcardDomain -> {
                                if (!rootMode) {
                                    if (isDeny) {
                                        wildcardRules.add(rule)
                                        wildcardCount++
                                    }
                                } else {
                                    hosts!!.add(rule.pattern)
                                }
                            }
                            is BlocklistRule.RegexPattern -> {
                                if (!rootMode) {
                                    if (isDeny) {
                                        regexRules.add(rule)
                                        regexCount++
                                    }
                                } else {
                                    hosts!!.add(rule.pattern)
                                }
                            }
                            is BlocklistRule.WhitelistDomain -> {
                                if (!rootMode) {
                                    whitelistedHosts.add(rule.domain)
                                    whitelistCount++
                                } else {
                                    hosts!!.add("!" + rule.domain)
                                }
                            }
                        }
                    }
                    line = bufferedReader.readLine()
                }
            }
            Logger.info("${item.title}: Loaded $count domains, $wildcardCount wildcards, $regexCount regex, $whitelistCount whitelisted")
            return hosts
        } catch (e: IOException) {
            Logger.error("${item.title}: Failed to read after $count items", e)
            return hosts
        }
    }
}