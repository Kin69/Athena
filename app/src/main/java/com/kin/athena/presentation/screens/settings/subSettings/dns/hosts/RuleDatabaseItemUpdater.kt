/* Copyright (C) 2024 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kin.athena.presentation.screens.settings.subSettings.dns.hosts

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kin.athena.R
import com.kin.athena.core.logging.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Date
import javax.net.ssl.HttpsURLConnection

class RuleDatabaseItemUpdate(
    private val context: Context,
    private val worker: RuleDatabaseUpdateWorker,
    private val item: HostFile,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 3000
        private const val READ_TIMEOUT_MILLIS = 10000
    }

    private lateinit var url: URL
    private var file: File? = null

    fun shouldDownload(): Boolean {
        if (item.data.startsWith("content://")) {
            return true
        }

        file = FileHelper.getItemFile(item)
        if (file == null || !item.isDownloadable()) {
            Logger.debug { "[Blocklist] ${item.title}: not downloadable, file=${file != null}" }
            return false
        }

        try {
            url = URL(item.data)
        } catch (e: MalformedURLException) {
            Logger.debug(e) { "[Blocklist] ${item.title}: invalid URL ${item.data}" }
            worker.addError(item, context.getString(R.string.rule_invalid_url) + item.data)
            return false
        }

        return true
    }

    fun run() {
        if (item.data.startsWith("content://")) {
            try {
                val uri = parseUri(item.data)
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                context.contentResolver.openInputStream(uri)?.close()
                Logger.info("Permission granted for content URI: ${item.title}")
            } catch (e: SecurityException) {
                Logger.error("Permission denied for ${item.title}", e)
                worker.addError(item, context.getString(R.string.rule_permission_denied))
            } catch (e: FileNotFoundException) {
                Logger.error("File not found: ${item.title}", e)
                worker.addError(item, context.getString(R.string.rule_file_not_found))
            } catch (e: IOException) {
                Logger.error("IO error for ${item.title}: ${e.localizedMessage}", e)
                worker.addError(
                    item,
                    context.getString(R.string.rule_unknown_error) + e.localizedMessage
                )
            }
            return
        }

        val singleWriterMultipleReaderFile = SingleWriterMultipleReaderFile(file!!)
        var connection: HttpURLConnection? = null
        worker.addBegin(item)
        val startedAt = System.currentTimeMillis()
        try {
            connection = getHttpURLConnection(file!!, singleWriterMultipleReaderFile, url)

            if (!validateResponse(connection)) {
                // Handle 304 Not Modified as success (file already up to date)
                if (connection.responseCode == 304) {
                    Logger.info("${item.title}: Already up to date (304)")
                    worker.addDone(item)
                }
                return
            }
            downloadFile(file!!, singleWriterMultipleReaderFile, connection)
            Logger.info("${item.title}: Download completed successfully")
            Logger.debug { "[Blocklist] ${item.title}: ${file?.length()} bytes in ${System.currentTimeMillis() - startedAt} ms" }
            worker.addDone(item)
        } catch (e: SocketTimeoutException) {
            Logger.warn("${item.title}: Connection timeout")
            worker.addError(item, context.getString(R.string.rule_timeout))
        } catch (e: IOException) {
            Logger.error("${item.title}: Download failed - ${e.message}", e)
            worker.addError(item, context.getString(R.string.rule_unknown_error) + e.toString())
        } catch (e: NullPointerException) {
            Logger.error("${item.title}: Unexpected null pointer", e)
            worker.addError(item, context.getString(R.string.rule_unknown_error) + e.toString())
        } finally {
            connection?.disconnect()
        }
    }

    fun parseUri(uri: String): Uri = Uri.parse(uri)

    @Throws(IOException::class)
    fun getHttpURLConnection(
        file: File,
        singleWriterMultipleReaderFile: SingleWriterMultipleReaderFile,
        url: URL
    ): HttpURLConnection {
        val connection = internalOpenHttpConnection(url)
        connection.apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
        }

        try {
            singleWriterMultipleReaderFile.openRead().close()
            connection.ifModifiedSince = file.lastModified()
        } catch (e: IOException) {
            Logger.debug { "[Blocklist] ${item.title}: no local copy yet, full download (${e.message})" }
        }

        connection.connect()
        return connection
    }

    @Throws(IOException::class)
    fun internalOpenHttpConnection(url: URL): HttpURLConnection =
        url.openConnection() as HttpsURLConnection

    @Throws(IOException::class)
    fun validateResponse(connection: HttpURLConnection): Boolean {
        val responseCode = connection.responseCode

        when (responseCode) {
            200 -> {
                Logger.debug("${item.title}: Server returned 200 OK")
                return true
            }
            304 -> {
                Logger.debug("${item.title}: Not modified (304)")
                return false
            }
            404 -> {
                Logger.warn("${item.title}: File not found (404) at ${item.data}")
                worker.addError(item, context.getString(R.string.rule_file_not_found))
                return false
            }
            else -> {
                Logger.warn("${item.title}: Server returned ${responseCode} ${connection.responseMessage}")
                worker.addError(
                    item,
                    context.resources.getString(R.string.rule_update_error) + responseCode + " " + connection.responseMessage
                )
                return false
            }
        }
    }

    @Throws(IOException::class)
    fun downloadFile(
        file: File,
        singleWriterMultipleReaderFile: SingleWriterMultipleReaderFile,
        connection: HttpURLConnection
    ) {
        val inStream = connection.inputStream
        var outStream: FileOutputStream? = singleWriterMultipleReaderFile.startWrite()
        try {
            Logger.debug("${item.title}: Starting file download")
            copyStream(inStream, outStream!!)

            singleWriterMultipleReaderFile.finishWrite(outStream)
            outStream = null

            if (connection.lastModified == 0L || !file.setLastModified(connection.lastModified)) {
                Logger.debug("${item.title}: Could not set last modified timestamp")
            }
        } finally {
            if (outStream != null) {
                singleWriterMultipleReaderFile.failWrite(outStream)
            }
        }
    }

    @Throws(IOException::class)
    fun copyStream(inStream: InputStream, outStream: OutputStream) {
        val buffer = ByteArray(65536) // 64KB buffer for faster downloads
        var rc = inStream.read(buffer)
        while (rc != -1) {
            outStream.write(buffer, 0, rc)
            rc = inStream.read(buffer)
        }
    }
}