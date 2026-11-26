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

import com.kin.athena.core.logging.Logger

/**
 * Utility object for monitoring performance of statistics calculations and other operations.
 * Used in debug builds to track optimization effectiveness.
 */
object PerformanceMonitor {
    
    const val TAG = "PerformanceMonitor"
    const val DEBUG = false // Set to true for performance logging
    
    /**
     * Measures the execution time of a block of code and logs the result.
     * 
     * @param operationName Name of the operation being measured
     * @param block The code block to measure
     * @return The result of the block execution
     */
    inline fun <T> measureTime(operationName: String, block: () -> T): T {
        if (!DEBUG) return block()
        
        val startTime = System.nanoTime()
        val result = block()
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000.0
        
        Logger.debug { "[Perf] $operationName took ${String.format("%.2f", durationMs)}ms" }
        
        return result
    }
    
    /**
     * Logs memory usage information for debugging performance issues.
     * 
     * @param context Description of when this measurement was taken
     */
    fun logMemoryUsage(context: String) {
        if (!DEBUG) return
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMB = usedMemory / (1024 * 1024)
        val maxMB = maxMemory / (1024 * 1024)
        
        Logger.debug { "[Perf] $context - Memory usage: ${usedMB}MB / ${maxMB}MB" }
    }
    
    /**
     * Logs cache hit/miss statistics for performance analysis.
     * 
     * @param cacheName Name of the cache being monitored
     * @param hits Number of cache hits
     * @param misses Number of cache misses
     */
    fun logCacheStats(cacheName: String, hits: Int, misses: Int) {
        if (!DEBUG) return
        
        val total = hits + misses
        val hitRate = if (total > 0) (hits.toDouble() / total * 100) else 0.0
        
        Logger.debug { "[Perf] $cacheName cache - Hits: $hits, Misses: $misses, Hit rate: ${String.format("%.1f", hitRate)}%" }
    }
}