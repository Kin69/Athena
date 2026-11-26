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

package com.kin.athena.core.logging

import android.os.SystemClock
import android.util.Log
import com.kin.athena.BuildConfig

/**
 * Logs with the tag "Athena". Info, warnings and errors are logged in every build, debug messages in debug builds
 * only.
 *
 * Debug messages use the lambda overload, `Logger.debug { "[Vpn] established fd=$fd" }`: a release build never calls
 * the lambda, so it does not build the message either. They start with the component in brackets ([Vpn], [Conn],
 * [Rules], [Pkt], ...) so logcat can be filtered by area, and give values as key=value.
 * Never log license keys, purchase tokens, passwords or packet payloads.
 */
object Logger {
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    const val TAG = "Athena"

    /** True in debug builds only. Guards debug-only work such as counting or bookkeeping for a log. */
    val isDebug: Boolean
        get() = BuildConfig.DEBUG

    private var currentLogLevel: LogLevel = LogLevel.DEBUG

    /** Debug builds only. Prefer the lambda overload, which does not build the message in release builds. */
    fun debug(message: String) {
        if (BuildConfig.DEBUG) log(LogLevel.DEBUG, message)
    }

    /** Debug builds only. [message] is not called in release builds. */
    inline fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) debugNow(message(), null)
    }

    /** Debug builds only, with [throwable]'s stack trace. [message] is not called in release builds. */
    inline fun debug(throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) debugNow(message(), throwable)
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun warning(message: String) = log(LogLevel.WARN, message)
    fun error(message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, message, throwable)

    /**
     * For debug logs: "Class.method:line" of the code that called the function calling this, e.g. who asked to
     * start the firewall. [depth] 2 is that caller, 3 its caller, and so on.
     */
    fun caller(depth: Int = 2): String {
        val frame = Throwable().stackTrace.getOrNull(depth) ?: return "?"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    @PublishedApi
    internal fun debugNow(message: String, throwable: Throwable?) = log(LogLevel.DEBUG, message, throwable)

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        if (currentLogLevel <= level) {
            when (level) {
                LogLevel.DEBUG -> Log.d(TAG, "[ + ] $message", throwable)
                LogLevel.INFO -> Log.i(TAG, "[ + ] $message", throwable)
                LogLevel.WARN -> Log.w(TAG, "[ ! ] $message", throwable)
                LogLevel.ERROR -> Log.e(TAG, "[ ! ] $message", throwable)
            }
        }
    }

    /**
     * Limits debug logs on hot paths (every packet) to the first event of a key, such as a flow, and then to one
     * per [intervalMs]. Remembers the last [MAX_KEYS] keys. Always false in release builds, which keep nothing.
     */
    class Throttle(private val intervalMs: Long = 60_000L) {
        private val seen = object : LinkedHashMap<Any, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Long>?) = size > MAX_KEYS
        }

        fun shouldLog(key: Any): Boolean {
            if (!BuildConfig.DEBUG) return false
            val now = SystemClock.uptimeMillis()
            synchronized(seen) {
                val last = seen[key]
                if (last != null && now - last < intervalMs) return false
                seen[key] = now
                return true
            }
        }

        private companion object {
            const val MAX_KEYS = 512
        }
    }
}
