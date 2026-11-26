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


import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep
import com.kin.athena.core.logging.Logger
import com.kin.athena.presentation.screens.settings.subSettings.dns.hosts.HostState.Companion.toHostState
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class Configuration(var hosts: Hosts = Hosts(), ) {

    companion object {
        private const val DEFAULT_CONFIG_FILENAME = "hosts.json"
        private const val CONFIG_BACKUP_EXTENSION = ".bak"

        private val json by lazy {
            Json {
                ignoreUnknownKeys = true
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun load(inputStream: InputStream): Configuration {
            val config = try {
                json.decodeFromStream<Configuration>(inputStream)
            } catch (e: Exception) {
                Logger.error("Failed to decode config!", e)
                loadBackup()
            }

            return config
        }

        fun load(name: String = DEFAULT_CONFIG_FILENAME): Configuration {
            val inputStream = FileHelper.openRead(name) ?: return Configuration()

            return load(inputStream)
        }

        private fun loadBackup(name: String = "$DEFAULT_CONFIG_FILENAME$CONFIG_BACKUP_EXTENSION"): Configuration = load(name)
    }

    fun addURL(title: String, location: String, state: HostState) =
        hosts.items.add(
            element = HostFile(
                title = title,
                data = location,
                state = state,
            ),
        ).also { Logger.debug { "[Blocklist] Config add title=$title url=$location state=$state" } }

    fun removeURL(oldURL: String) =
        hosts.items.removeAll { it.data == oldURL }
            .also { removed -> Logger.debug { "[Blocklist] Config remove url=$oldURL removed=$removed" } }

    fun disableURL(oldURL: String) {
        Logger.error("disableURL: Disabling $oldURL")
        hosts.items.forEach {
            if (it.data == oldURL) {
                it.state = HostState.IGNORE
            }
        }
    }

    fun save(name: String = DEFAULT_CONFIG_FILENAME) {
        val outputStream = FileHelper.openWrite(name)
        save(outputStream)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(writer: OutputStream) {
        try {
            json.encodeToStream(this, writer)
        } catch (e: Exception) {
            Logger.error("Failed to write config to disk!", e)
        }
    }
}

interface Host : Parcelable {
    var title: String
    var data: String
    var state: HostState
}

@Parcelize
@Serializable
data class HostFile(
    override var title: String = "",
    @SerialName("location") override var data: String = "",
    override var state: HostState = HostState.IGNORE,
) : Host {
    fun isDownloadable(): Boolean =
        data.startsWith("https://") || data.startsWith("http://")

    companion object : Parceler<HostFile> {
        override fun HostFile.write(parcel: Parcel, flags: Int) {
            parcel.apply {
                writeString(title)
                writeString(data)
                writeInt(state.ordinal)
            }
        }

        override fun create(parcel: Parcel): HostFile =
            HostFile(
                parcel.readString() ?: "",
                parcel.readString() ?: "",
                parcel.readInt().toHostState(),
            )
    }
}

@Parcelize
@Serializable
data class HostException(
    override var title: String = "",
    @SerialName("hostname") override var data: String = "",
    override var state: HostState = HostState.IGNORE,
) : Host {
    companion object : Parceler<HostException> {
        override fun HostException.write(parcel: Parcel, flags: Int) {
            parcel.apply {
                writeString(title)
                writeString(data)
                writeInt(state.ordinal)
            }
        }

        override fun create(parcel: Parcel): HostException =
            HostException(
                parcel.readString() ?: "",
                parcel.readString() ?: "",
                parcel.readInt().toHostState(),
            )
    }
}

@Serializable
data class Hosts(
    var enabled: Boolean = true,
    var automaticRefresh: Boolean = false,
    var items: MutableList<HostFile> = mutableListOf(),
    var exceptions: MutableList<HostException> = mutableListOf(),
)


@Keep
enum class HostState {
    IGNORE, DENY, ALLOW;

    companion object {
        fun Int.toHostState(): HostState = entries.firstOrNull { it.ordinal == this } ?: IGNORE
    }
}
