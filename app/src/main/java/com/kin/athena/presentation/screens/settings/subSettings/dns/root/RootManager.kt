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

package com.kin.athena.presentation.screens.settings.subSettings.dns.root

import android.content.Context
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.Shell
import java.io.File

class HostsManager(private val context: Context, private val domains: List<String>) {

    companion object {
        private const val HOSTS_PATH = "/system/etc/hosts"
        private const val TEMP_HOSTS_FILENAME = "hosts_temp"
        private const val BACKUP_HOSTS_FILENAME = "hosts_backup"
        private const val LOCALHOST_IPV4 = "127.0.0.1"
        private const val LOCALHOST_IPV6 = "::1"
        private const val LOCALHOST_HOSTNAME = "localhost"
        private const val BLOCK_IP = "0.0.0.0" // IP to redirect blocked domains to
    }

    private val shell: Shell = Shell.SU

    @Throws(HostsException::class)
    fun apply() {
        Logger.info("HostsManager: apply() called with ${domains.size} domains")
        val (isWritable, isMagiskSystemless) = isHostsWritable()
        Logger.info("HostsManager: Hosts writable: $isWritable, Magisk systemless: $isMagiskSystemless")
        
        if (isWritable) {
            try {
                Logger.info("HostsManager: Starting hosts file modification process")
                backupHostsFile() // Backup before applying changes
                Logger.info("HostsManager: Backup completed, creating temp hosts file")
                val tempHostsPath = createTempHostsFile()
                Logger.info("HostsManager: Temp file created at: $tempHostsPath")
                remountPartitionReadWrite()
                Logger.info("HostsManager: Partition remounted as read-write")
                copyAndSetPermissions(tempHostsPath)
                Logger.info("HostsManager: Hosts file copied and permissions set")
                remountPartitionReadOnly()
                Logger.info("HostsManager: Partition remounted as read-only")
                verifyUpdate()
                Logger.info("HostsManager: Hosts file update verified successfully")
            } catch (e: Exception) {
                Logger.error("HostsManager: Exception during apply: ${e.message}", e)
                throw HostsException("Failed to apply hosts file: ${e.message}", e)
            }
        } else {
            Logger.warn("HostsManager: Hosts file is not writable; skipping apply")
        }
    }

    @Throws(HostsException::class)
    fun revertToDefault() {
        if (isHostsWritable().first) {
            try {
                val backupFile = File(context.filesDir, BACKUP_HOSTS_FILENAME)
                val tempHostsPath = if (backupFile.exists()) {
                    backupFile.absolutePath // Use backup if it exists
                } else {
                    createDefaultHostsFile() // Otherwise, create a default version
                }
                remountPartitionReadWrite()
                copyAndSetPermissions(tempHostsPath)
                remountPartitionReadOnly()
                verifyRevert()
                Logger.info("Hosts file reverted to default version")
            } catch (e: Exception) {
                Logger.debug(e) { "[Blocklist] Reverting the hosts file failed" }
                throw HostsException("Failed to revert hosts file: ${e.message}", e)
            }
        } else {
            Logger.warn("Hosts file is not writable; cannot revert")
            throw HostsException("Hosts file is not writable; cannot revert")
        }
    }

    fun isHostsWritable(): Pair<Boolean, Boolean> {
        val initialWritableResult = shell.run("test -w $HOSTS_PATH && echo 'yes' || echo 'no'")
        var isWritable = initialWritableResult.stdout() == "yes"

        if (!isWritable) {
            shell.run("mount -o rw,remount $HOSTS_PATH")
            val afterRemountResult = shell.run("test -w $HOSTS_PATH && echo 'yes' || echo 'no'")
            isWritable = afterRemountResult.stdout() == "yes"
            if (isWritable) {
                shell.run("mount -o ro,remount $HOSTS_PATH")
            }
        }

        val mountResult = shell.run("mount | grep $HOSTS_PATH")
        val mounts = mountResult.stdout()
        val isMagiskSystemless = mounts.isNotEmpty() && mounts.contains("magisk") && mounts.contains("bind")

        Logger.info("Hosts can be made writable: $isWritable, Magisk systemless detected: $isMagiskSystemless")
        return Pair(isWritable, isMagiskSystemless)
    }

    private fun createTempHostsFile(): String {
        Logger.info("HostsManager: Creating temp hosts file with ${domains.size} domains")
        val tempFile = File(context.filesDir, TEMP_HOSTS_FILENAME)
        tempFile.bufferedWriter().use { writer ->
            writer.write("# This hosts file has been generated by Athena\n")
            writer.write("# Please do not modify it directly, it will be overwritten when Athena is applied again.\n\n")
            writer.write("$LOCALHOST_IPV4 $LOCALHOST_HOSTNAME\n")
            writer.write("$LOCALHOST_IPV6 $LOCALHOST_HOSTNAME\n")

            domains.forEachIndexed { index, domain ->
                writer.write("$BLOCK_IP $domain\n")
                if (index < 5) {
                    Logger.info("HostsManager: Writing domain: $BLOCK_IP $domain")
                }
            }
        }
        Logger.info("HostsManager: Temp hosts file created at ${tempFile.absolutePath} with ${domains.size} blocked domains")

        if (!tempFile.exists()) {
            throw HostsException("Failed to create temporary hosts file at ${tempFile.absolutePath}")
        }
        return tempFile.absolutePath
    }

    private fun createDefaultHostsFile(): String {
        val tempFile = File(context.filesDir, TEMP_HOSTS_FILENAME)
        tempFile.bufferedWriter().use { writer ->
            writer.write("# Default Android hosts file\n")
            writer.write("$LOCALHOST_IPV4 $LOCALHOST_HOSTNAME\n")
            writer.write("$LOCALHOST_IPV6 $LOCALHOST_HOSTNAME\n")
        }
        Logger.info("DEFAULT FILE CREATED at ${tempFile.absolutePath}")

        if (!tempFile.exists()) {
            throw HostsException("Failed to create default hosts file at ${tempFile.absolutePath}")
        }
        return tempFile.absolutePath
    }

    private fun backupHostsFile() {
        val backupFile = File(context.filesDir, BACKUP_HOSTS_FILENAME)
        val backupCommand = "dd if=$HOSTS_PATH of=${backupFile.absolutePath}"
        val result = shell.run(backupCommand)
        if (!result.isSuccess) {
            Logger.warn("Failed to backup hosts file: ${result.stderr()}")
        } else {
            Logger.info("Hosts file backed up to ${backupFile.absolutePath}")
        }
    }

    private fun remountPartitionReadWrite() {
        shell.run("test -w $HOSTS_PATH && echo 'yes' || echo 'no'")
        shell.run("mount -o rw,remount $HOSTS_PATH")

        val writableResult = shell.run("test -w $HOSTS_PATH && echo 'yes' || echo 'no'")
        if (writableResult.stdout() != "yes") {
            Logger.error(writableResult.stdout())
            throw HostsException("Failed to remount partition as read-write")
        }
    }

    private fun copyAndSetPermissions(tempHostsPath: String) {
        val commands = arrayOf(
            "dd if=$tempHostsPath of=$HOSTS_PATH",
            "chown 0:0 $HOSTS_PATH",
            "chmod 644 $HOSTS_PATH"
        )

        commands.forEach { cmd ->
            val result = shell.run(cmd)
            if (!result.isSuccess) {
                throw HostsException("Failed to execute '$cmd': ${result.stderr()}")
            }
        }

        File(tempHostsPath).delete()
    }

    private fun remountPartitionReadOnly() {
        shell.run("mount -o ro,remount $HOSTS_PATH")
    }

    private fun verifyUpdate() {
        val result = shell.run("cat $HOSTS_PATH")
        if (!result.isSuccess) {
            throw HostsException("Failed to read $HOSTS_PATH: ${result.stderr()}")
        }

        val content = result.stdout()
        if (!content.contains("$BLOCK_IP ${domains.first()}")) {
            throw HostsException("Hosts file update verification failed; domains not found")
        }
    }

    private fun verifyRevert() {
        val result = shell.run("cat $HOSTS_PATH")
        if (!result.isSuccess) {
            throw HostsException("Failed to read $HOSTS_PATH after revert: ${result.stderr()}")
        }

        val content = result.stdout()
        if (content.contains("$BLOCK_IP") && domains.isNotEmpty()) {
            throw HostsException("Hosts file revert failed; blocked domains still present")
        }
    }

    class HostsException(message: String, cause: Throwable? = null) : Exception(message, cause)
}