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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.workDataOf
import com.kin.athena.core.logging.Logger
import com.kin.athena.presentation.config
import com.kin.athena.service.utils.manager.FirewallManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@HiltWorker
class RuleDatabaseUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val firewallManager: FirewallManager,
) : CoroutineWorker(context, params) {

    companion object {
        var lastErrors by atomic<MutableList<String>?>(null)

        private const val DATABASE_UPDATE_TIMEOUT = 3600000L

        @SuppressLint("MutableCollectionMutableState")
        var doneNames = MutableStateFlow(mutableListOf<String>())

        private val _isRefreshing = MutableStateFlow(false)
    }

    private val errors = ArrayList<String>()
    private val pending = ArrayList<String>()
    private val done = ArrayList<String>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        _isRefreshing.value = true

        // Check if this is an auto-update
        val isAutoUpdate = inputData.getBoolean("auto_update", false)
        
        // Check if we should update specific lists only
        val targetLists = inputData.getStringArray("target_lists")
        val hostsToProcess = if (targetLists != null && targetLists.isNotEmpty()) {
            config.hosts.items.filter { targetLists.contains(it.data) }
        } else {
            // For auto-updates, only update enabled lists
            if (isAutoUpdate) {
                config.hosts.items.filter { it.state == HostState.DENY }
            } else {
                config.hosts.items
            }
        }

        val updateType = if (isAutoUpdate) "auto-update" else "manual update"
        Logger.info("DNS blocklist $updateType started: ${hostsToProcess.size} lists to process")
        Logger.debug {
            "[Blocklist] Worker id=$id attempt=$runAttemptCount targets=${targetLists?.size ?: "all"} " +
                "lists=${hostsToProcess.joinToString { "${it.title}(${it.state})" }}"
        }

        // Set initial progress
        setProgress(workDataOf("progress" to 0, "stage" to "downloading"))

        // Process downloads in parallel
        val jobs = mutableListOf<Deferred<Unit>>()
        var totalJobs = 0
        hostsToProcess.forEach {
            val update = RuleDatabaseItemUpdate(context, this@RuleDatabaseUpdateWorker, it)
            if (update.shouldDownload()) {
                totalJobs++
                val job = async { update.run() }
                job.start()
                jobs.add(job)
            } else {
                Logger.debug { "[Blocklist] Not downloading ${it.title}: state=${it.state}" }
            }
        }
        Logger.debug { "[Blocklist] Downloading $totalJobs of ${hostsToProcess.size} lists" }

        releaseGarbagePermissions()

        // Wait for all downloads with timeout and update progress
        try {
            withTimeout(DATABASE_UPDATE_TIMEOUT) {
                jobs.forEachIndexed { index, job ->
                    job.await()
                    // Update progress: 0-70% for downloading
                    val downloadProgress = ((index + 1) * 70) / totalJobs.coerceAtLeast(1)
                    setProgress(workDataOf("progress" to downloadProgress, "stage" to "downloading"))
                }
            }
        } catch (_: TimeoutCancellationException) {
            Logger.warn("DNS update timeout: Some downloads did not complete within ${DATABASE_UPDATE_TIMEOUT}ms")
        }

        // Update progress for parsing stage
        setProgress(workDataOf("progress" to 75, "stage" to "parsing"))

        // Update domains once after all downloads complete with progress callback
        Logger.info("Reloading domain rules from downloaded files")
        val reloadStart = System.currentTimeMillis()
        firewallManager.updateDomains { progress ->
            setProgress(workDataOf("progress" to progress, "stage" to "parsing"))
        }
        Logger.debug { "[Blocklist] Domain rules reloaded in ${System.currentTimeMillis() - reloadStart} ms" }

        val duration = System.currentTimeMillis() - start
        val successCount = done.size
        val errorCount = errors.size

        Logger.info("DNS blocklist $updateType completed: ${successCount} succeeded, ${errorCount} failed (${duration}ms total)")

        postExecute()

        // Consider it a success if:
        // 1. There were no errors at all, OR
        // 2. There were some errors but at least some succeeded (partial success is still success)
        // This handles both fresh downloads and cases where download fails but cached file was loaded
        if (errors.isNotEmpty() && successCount == 0) {
            // Only fail if ALL operations failed with no successes
            Logger.warn("All downloads failed with ${errorCount} errors")
            Result.failure()
        } else {
            // Set final progress to 100% on success
            setProgress(workDataOf("progress" to 100, "stage" to "complete"))
            if (errors.isNotEmpty()) {
                Logger.info("Completed with ${successCount} successes and ${errorCount} errors (partial success)")
            }
            Result.success()
        }
    }

    private fun releaseGarbagePermissions() {
        val contentResolver = context.contentResolver
        var releasedCount = 0
        for (permission in contentResolver.persistedUriPermissions) {
            if (isGarbage(permission.uri)) {
                contentResolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                releasedCount++
            }
        }
        if (releasedCount > 0) {
            Logger.debug("Released $releasedCount unused URI permissions")
        }
    }

    private fun isGarbage(uri: Uri): Boolean {
        for (item in config.hosts.items) {
            if (Uri.parse(item.data) == uri) {
                return false
            }
        }
        return true
    }

    @Synchronized
    private fun updateProgressNotification() {
        val builder = StringBuilder()
        for (p in pending) {
            if (builder.isNotEmpty()) {
                builder.append("\n")
            }
            builder.append(p)
        }
    }

    @Synchronized
    private fun postExecute() {
        if (!errors.isEmpty()) {
            lastErrors = errors
        }
        _isRefreshing.value = false
    }

    @Synchronized
    fun addError(item: Host, message: String) {
        Logger.warn("${item.title}: $message")
        errors.add("${item.title}\n$message")
    }

    @Synchronized
    fun addDone(item: Host) {
        Logger.debug { "[Blocklist] Done ${item.title}" }
        // Don't update domains here - wait until all downloads complete
        pending.remove(item.title)
        done.add(item.title)
        doneNames.value = doneNames.value.toMutableList().apply { add(item.title) }
        updateProgressNotification()
    }
    @Synchronized
    fun addBegin(item: Host) {
        Logger.debug { "[Blocklist] Begin ${item.title}" }
        pending.add(item.title)
        updateProgressNotification()
    }
}