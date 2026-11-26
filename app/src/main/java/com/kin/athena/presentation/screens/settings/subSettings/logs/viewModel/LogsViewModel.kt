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

package com.kin.athena.presentation.screens.settings.subSettings.logs.viewModel

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.utils.extensions.getAppNameFromPackage
import com.kin.athena.core.utils.extensions.toFormattedDateTime
import com.kin.athena.core.utils.extensions.uidToApplication
import com.kin.athena.domain.model.Log
import com.kin.athena.domain.model.NetworkStatsState
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.service.firewall.model.FirewallResult
import com.kin.athena.service.firewall.utils.FirewallStatus
import com.kin.athena.service.utils.manager.FirewallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.PerformanceMonitor
import kotlin.math.abs

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logsUseCases: LogUseCases,
    private val firewallManager: FirewallManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _logs: MutableState<List<List<Log>>> = mutableStateOf(emptyList())
    val logs: State<List<List<Log>>> = _logs

    private val _filteredLogs = MutableStateFlow<List<List<Log>>>(emptyList())
    val filteredLogs: StateFlow<List<List<Log>>> = _filteredLogs.asStateFlow()

    private val _query = mutableStateOf("")
    val query: State<String> = _query

    private val _networkStats = MutableStateFlow(NetworkStatsState())
    val networkStats: StateFlow<NetworkStatsState> = _networkStats.asStateFlow()

    private var sessionStartTime: Long? = null
    
    // Performance optimization: Cache for statistics calculations
    private val statsCache = ConcurrentHashMap<String, NetworkStatsState>()
    private var lastLogHash: Int = 0
    
    // Performance optimization: Batch update tracking
    private var pendingUpdates = 0
    private val maxBatchSize = 50
    
    // Performance monitoring
    private var cacheHits = 0
    private var cacheMisses = 0

    init {
        getLogs()
        observeFirewallStatus()
    }

    fun deleteLogs() {
        Logger.debug { "[UI] Delete all packet logs" }
        viewModelScope.launch {
            logsUseCases.deleteLogs.execute()
            // Reset session start time when logs are deleted
            // Only set new session time if firewall is currently active
            sessionStartTime = if (isFirewallActive()) {
                System.currentTimeMillis()
            } else {
                null
            }
            // Performance optimization: Clear cache when logs are deleted
            clearStatsCache()
            getLogs()
        }
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        applyFilter()
    }

    fun clearQuery() {
        _query.value = ""
        // Use applyFilter to ensure consistent filtering logic and trigger statistics update
        applyFilter()
    }

    private fun calculateNetworkStats(logs: List<List<Log>>): NetworkStatsState {
        return PerformanceMonitor.measureTime("calculateNetworkStats") {
            // Performance optimization: Check cache first to avoid recalculation on unchanged data
            val currentLogHash = logs.hashCode()
            val isFirewallActive = isFirewallActive()
            val cacheKey = "${currentLogHash}_${isFirewallActive}_${sessionStartTime}"
            
            // Return cached result if data hasn't changed
            if (currentLogHash == lastLogHash && statsCache.containsKey(cacheKey)) {
                cacheHits++
                PerformanceMonitor.logCacheStats("NetworkStats", cacheHits, cacheMisses)
                return@measureTime statsCache[cacheKey]!!
            }
            
            cacheMisses++
            
            // Performance optimization: Use optimized counting for large datasets
            val stats = if (logs.isEmpty()) {
                NetworkStatsState(
                    isFirewallActive = isFirewallActive,
                    sessionStartTime = sessionStartTime
                )
            } else {
                calculateStatsOptimized(logs, isFirewallActive)
            }
            
            // Cache the result
            lastLogHash = currentLogHash
            statsCache[cacheKey] = stats
            
            // Clean up old cache entries to prevent memory leaks
            if (statsCache.size > 10) {
                val oldestKey = statsCache.keys.first()
                statsCache.remove(oldestKey)
            }
            
            PerformanceMonitor.logMemoryUsage("After stats calculation")
            stats
        }
    }
    
    /**
     * Performance-optimized statistics calculation for large log datasets.
     * Uses efficient counting algorithms and avoids unnecessary iterations.
     */
    private fun calculateStatsOptimized(logs: List<List<Log>>, isFirewallActive: Boolean): NetworkStatsState {
        return PerformanceMonitor.measureTime("calculateStatsOptimized") {
            var allowedCount = 0L
            var blockedCount = 0L
            var totalCount = 0L
            
            // Single pass through all logs with optimized counting
            for (logGroup in logs) {
                val groupSize = logGroup.size
                totalCount += groupSize
                
                // Use more efficient counting for large groups
                if (groupSize > 100) {
                    // For large groups, use parallel counting if beneficial
                    val (allowed, blocked) = countPacketStatusesOptimized(logGroup)
                    allowedCount += allowed
                    blockedCount += blocked
                } else {
                    // For smaller groups, use simple iteration
                    for (log in logGroup) {
                        when (log.packetStatus) {
                            FirewallResult.ACCEPT -> allowedCount++
                            FirewallResult.DROP -> blockedCount++
                            FirewallResult.DNS_BLOCKED -> blockedCount++
                        }
                    }
                }
            }
            
            NetworkStatsState(
                allowedCount = allowedCount,
                blockedCount = blockedCount,
                totalCount = totalCount,
                isFirewallActive = isFirewallActive,
                sessionStartTime = sessionStartTime
            )
        }
    }
    
    /**
     * Optimized counting for large log groups using efficient algorithms.
     */
    private fun countPacketStatusesOptimized(logs: List<Log>): Pair<Long, Long> {
        var allowedCount = 0L
        var blockedCount = 0L
        
        // Use efficient iteration without unnecessary object creation
        val iterator = logs.iterator()
        while (iterator.hasNext()) {
            when (iterator.next().packetStatus) {
                FirewallResult.ACCEPT -> allowedCount++
                FirewallResult.DROP -> blockedCount++
                FirewallResult.DNS_BLOCKED -> blockedCount++
            }
        }
        
        return Pair(allowedCount, blockedCount)
    }

    @OptIn(FlowPreview::class)
    private fun observeFirewallStatus() {
        viewModelScope.launch {
            // Track firewall status changes for session start time management
            firewallManager.rulesLoaded.collect { status ->
                updateSessionStartTime(status)
                // Clear cache when firewall status changes
                clearStatsCache()
            }
        }
        
        viewModelScope.launch {
            // Performance optimization: Implement batched updates for multiple rapid log insertions
            combine(
                _filteredLogs,
                firewallManager.rulesLoaded
            ) { filteredLogs, _ ->
                // Track pending updates for batching
                pendingUpdates++
                
                // Calculate stats based on filtered logs, firewall status is used in calculateNetworkStats
                calculateNetworkStats(filteredLogs)
            }
            .distinctUntilChanged() // Only emit when stats actually change
            .debounce(150) // Increased debounce time for better batching of rapid updates
            .collect { stats ->
                // Performance optimization: Only update if we have significant changes or reached batch limit
                if (shouldUpdateStats(stats)) {
                    _networkStats.value = stats
                    pendingUpdates = 0 // Reset batch counter
                }
            }
        }
    }
    
    /**
     * Determines if statistics should be updated based on batching logic.
     * Helps prevent excessive UI updates during rapid log insertions.
     */
    private fun shouldUpdateStats(newStats: NetworkStatsState): Boolean {
        val currentStats = _networkStats.value
        
        // Always update if firewall status changed
        if (currentStats.isFirewallActive != newStats.isFirewallActive) {
            return true
        }
        
        // Update if we've reached the batch size limit
        if (pendingUpdates >= maxBatchSize) {
            return true
        }
        
        // Update if there's a significant change in counts (more than 10% or 100 items)
        val totalDiff = abs(newStats.totalCount - currentStats.totalCount)
        val significantChange = totalDiff >= 100 || 
            (currentStats.totalCount > 0 && totalDiff.toDouble() / currentStats.totalCount > 0.1)
        
        return significantChange
    }
    
    /**
     * Clears the statistics cache to ensure fresh calculations.
     * Called when firewall status changes or logs are deleted.
     */
    private fun clearStatsCache() {
        statsCache.clear()
        lastLogHash = 0
        // Reset performance counters
        cacheHits = 0
        cacheMisses = 0
    }

    private fun isFirewallActive(): Boolean {
        return when (firewallManager.rulesLoaded.value) {
            is FirewallStatus.ONLINE -> true
            is FirewallStatus.LOADING -> true
            is FirewallStatus.OFFLINE -> false
            is FirewallStatus.MAGISK_SYSTEMLESS_ERROR -> false
        }
    }

    /**
     * Updates session start time based on firewall lifecycle changes.
     * Session starts when firewall becomes active (ONLINE) and resets when it goes offline.
     */
    private fun updateSessionStartTime(status: FirewallStatus) {
        when (status) {
            is FirewallStatus.ONLINE -> {
                // Start new session when firewall comes online (if not already started)
                if (sessionStartTime == null) {
                    sessionStartTime = System.currentTimeMillis()
                }
            }
            is FirewallStatus.OFFLINE, 
            is FirewallStatus.MAGISK_SYSTEMLESS_ERROR -> {
                // Keep session time when firewall goes offline to preserve "since last session" info
                // Session time will be reset when firewall comes back online or logs are deleted
            }
            is FirewallStatus.LOADING -> {
                // Don't change session time during loading state
            }
        }
    }

    private fun applyFilter() {
        val currentQuery = query.value.trim()
        val filteredResult = if (currentQuery.isEmpty()) {
            _logs.value
        } else {
            // Performance optimization: Use more efficient filtering for large datasets
            applyFilterOptimized(currentQuery)
        }
        
        // Update filtered logs StateFlow to trigger statistics recalculation
        _filteredLogs.value = filteredResult
    }
    
    /**
     * Performance-optimized filtering for large log datasets.
     * Reduces string operations and improves search efficiency.
     */
    private fun applyFilterOptimized(query: String): List<List<Log>> {
        return PerformanceMonitor.measureTime("applyFilterOptimized") {
            val lowerQuery = query.lowercase() // Convert once instead of per comparison
            
            // Use sequence for lazy evaluation with large datasets
            _logs.value.asSequence()
                .mapNotNull { group ->
                    val filteredGroup = group.filter { log ->
                        // Performance optimization: Early exit on first match
                        matchesQuery(log, lowerQuery)
                    }
                    
                    // Only return non-empty groups
                    if (filteredGroup.isNotEmpty()) filteredGroup else null
                }
                .toList()
        }
    }
    
    /**
     * Optimized query matching with early exit strategy.
     */
    private fun matchesQuery(log: Log, lowerQuery: String): Boolean {
        // Check simple string fields first (fastest)
        if (log.destinationIP.contains(lowerQuery, ignoreCase = true) ||
            log.sourceIP.contains(lowerQuery, ignoreCase = true) ||
            log.sourcePort.contains(lowerQuery, ignoreCase = true) ||
            log.destinationPort.contains(lowerQuery, ignoreCase = true) ||
            log.protocol.lowercase().contains(lowerQuery) ||
            log.packetStatus.name.lowercase().contains(lowerQuery)) {
            return true
        }
        
        // Check destination address (may be null)
        log.destinationAddress?.lowercase()?.let { address ->
            if (address.contains(lowerQuery)) return true
        }
        
        // Check app name (most expensive operation, do last)
        val application = context.uidToApplication(log.packageID)?.packageID
        val appName = application?.let { context.getAppNameFromPackage(it) }?.lowercase()
        if (appName?.contains(lowerQuery) == true) return true
        
        // Check formatted time (expensive, do last)
        val formattedTime = log.time.toFormattedDateTime().lowercase()
        return formattedTime.contains(lowerQuery)
    }

    private fun groupConsecutiveLogs(logs: List<Log>): List<List<Log>> {
        return PerformanceMonitor.measureTime("groupConsecutiveLogs") {
            // Performance optimization: Pre-filter and use more efficient grouping for large datasets
            val validLogs = logs.filter { it.packageID != -1 }
            if (validLogs.isEmpty()) return@measureTime emptyList()
            
            val groupedLogs = mutableListOf<MutableList<Log>>()
            var currentPackageId = -1

            for (log in validLogs) {
                if (log.packageID != currentPackageId) {
                    // Start new group
                    currentPackageId = log.packageID
                    groupedLogs.add(mutableListOf(log))
                } else {
                    // Add to current group
                    groupedLogs.last().add(log)
                }
            }

            // Performance optimization: Use more efficient deduplication for large groups
            groupedLogs.map { group ->
                if (group.size > 50) {
                    // For large groups, use LinkedHashSet for better performance
                    deduplicateLogsOptimized(group)
                } else {
                    // For small groups, use simple distinctBy
                    group.distinctBy { log ->
                        "${log.packageID}_${log.destinationIP}_${log.destinationPort}_${log.packetStatus}"
                    }
                }
            }
        }
    }
    
    /**
     * Optimized deduplication for large log groups.
     */
    private fun deduplicateLogsOptimized(logs: List<Log>): List<Log> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Log>()
        
        for (log in logs) {
            val key = "${log.packageID}_${log.destinationIP}_${log.destinationPort}_${log.packetStatus}"
            if (seen.add(key)) {
                result.add(log)
            }
        }
        
        return result
    }

    private fun getLogs() {
        viewModelScope.launch {
            logsUseCases.getLogs.execute().fold(
                ifSuccess = { logsUpdated ->
                    logsUpdated?.collect { logs ->
                        val groupedLogs = groupConsecutiveLogs(logs).reversed()
                        _logs.value = groupedLogs
                        _filteredLogs.value = groupedLogs // Set initial filtered logs immediately
                        // Apply filter which will update _filteredLogs and trigger statistics recalculation
                        applyFilter()
                    }
                }
            )
        }
    }
}