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

package com.kin.athena.presentation.screens.settings.subSettings.dns.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class BlocklistValidationState(
    val isValidating: Boolean = false,
    val isValid: Boolean? = null,
    val errorMessage: String? = null,
    val entryCount: Int? = null,
    val contentSize: Long? = null,
    val lastModified: String? = null,
    val contentType: String? = null
)

data class BlocklistEntry(
    val url: String,
    val name: String = "",
    val description: String = "",
    val category: String = "Custom",
    val isEnabled: Boolean = true,
    val lastValidated: Long? = null,
    val entryCount: Int? = null,
    val validationState: BlocklistValidationState = BlocklistValidationState()
)

sealed class DialogState {
    object Hidden : DialogState()
    object AddingNew : DialogState()
    data class Editing(val entry: BlocklistEntry) : DialogState()
    object ImportingFromFile : DialogState()
    object ImportingFromUrl : DialogState()
}

sealed class ImportSource {
    object File : ImportSource()
    object Clipboard : ImportSource()
    data class Url(val url: String) : ImportSource()
}

@HiltViewModel
class CustomBlocklistViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _dialogState = MutableStateFlow<DialogState>(DialogState.Hidden)
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _currentEntry = MutableStateFlow<BlocklistEntry?>(null)
    val currentEntry: StateFlow<BlocklistEntry?> = _currentEntry.asStateFlow()

    private val _validationState = MutableStateFlow(BlocklistValidationState())
    val validationState: StateFlow<BlocklistValidationState> = _validationState.asStateFlow()

    private val _presetBlocklists = MutableStateFlow<List<BlocklistEntry>>(emptyList())
    val presetBlocklists: StateFlow<List<BlocklistEntry>> = _presetBlocklists.asStateFlow()

    private val _importResults = MutableStateFlow<List<String>>(emptyList())
    val importResults: StateFlow<List<String>> = _importResults.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _deletingItems = MutableStateFlow<Set<String>>(emptySet())
    val deletingItems: StateFlow<Set<String>> = _deletingItems.asStateFlow()
    
    // No caching - always fetch fresh validation data

    private val _categories = MutableStateFlow<Map<String, List<BlocklistEntry>>>(emptyMap())
    val categories: StateFlow<Map<String, List<BlocklistEntry>>> = _categories.asStateFlow()

    init {
        loadPresetBlocklists()
    }

    fun showAddDialog() {
        _dialogState.value = DialogState.AddingNew
        _currentEntry.value = BlocklistEntry(url = "")
        _validationState.value = BlocklistValidationState()
    }

    fun showEditDialog(entry: BlocklistEntry) {
        _dialogState.value = DialogState.Editing(entry)
        _currentEntry.value = entry
        _validationState.value = entry.validationState
    }

    fun showImportDialog(source: ImportSource) {
        _dialogState.value = when (source) {
            is ImportSource.File -> DialogState.ImportingFromFile
            is ImportSource.Clipboard -> DialogState.ImportingFromUrl
            is ImportSource.Url -> DialogState.ImportingFromUrl
        }
    }

    fun hideDialog() {
        _dialogState.value = DialogState.Hidden
        _currentEntry.value = null
        _validationState.value = BlocklistValidationState()
        _importResults.value = emptyList()
    }

    fun updateCurrentEntry(entry: BlocklistEntry) {
        _currentEntry.value = entry
        if (entry.url.isNotBlank() && isValidUrl(entry.url)) {
            validateBlocklist(entry.url)
        } else {
            _validationState.value = BlocklistValidationState()
        }
    }

    fun validateBlocklist(url: String) {
        if (!isValidUrl(url)) {
            _validationState.value = BlocklistValidationState(
                isValid = false,
                errorMessage = "Invalid URL format"
            )
            return
        }

        // Always show validating state for fresh data
        _validationState.value = BlocklistValidationState(isValidating = true)

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    validateBlocklistUrl(url)
                }
                _validationState.value = result
            } catch (e: Exception) {
                Logger.debug(e) { "[Blocklist] Validation of $url failed" }
                _validationState.value = BlocklistValidationState(
                    isValid = false,
                    errorMessage = "Validation failed: ${e.message}"
                )
            }
        }
    }

    private suspend fun validateBlocklistUrl(url: String): BlocklistValidationState {
        Logger.debug { "[Blocklist] Validating url=$url" }
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                val lastModified = response.header("Last-Modified")
                val contentType = response.header("Content-Type")

                if (contentLength != null && contentLength > 100_000_000) {
                    return BlocklistValidationState(
                        isValid = false,
                        errorMessage = "Blocklist is too large (>100MB)"
                    )
                }

                val getRequest = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1023")
                    .build()

                val contentResponse = httpClient.newCall(getRequest).execute()
                val sampleContent = contentResponse.body?.string() ?: ""
                val entryCount = estimateEntryCount(sampleContent, contentLength)
                Logger.debug { "[Blocklist] Valid url=$url size=$contentLength estimatedEntries=$entryCount type=$contentType" }

                BlocklistValidationState(
                    isValid = true,
                    entryCount = entryCount,
                    contentSize = contentLength,
                    lastModified = lastModified,
                    contentType = contentType
                )
            } else {
                Logger.debug { "[Blocklist] Invalid url=$url http=${response.code}" }
                BlocklistValidationState(
                    isValid = false,
                    errorMessage = "HTTP ${response.code}: ${response.message}"
                )
            }
        } catch (e: IOException) {
            Logger.debug(e) { "[Blocklist] Validation network error url=$url" }
            BlocklistValidationState(
                isValid = false,
                errorMessage = "Network error: ${e.message}"
            )
        } catch (e: Exception) {
            Logger.debug(e) { "[Blocklist] Validation error url=$url" }
            BlocklistValidationState(
                isValid = false,
                errorMessage = "Validation error: ${e.message}"
            )
        }
    }

    private fun estimateEntryCount(sampleContent: String, totalSize: Long?): Int? {
        if (totalSize == null) return null
        
        val lines = sampleContent.lines()
        val validLines = lines.count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && 
            (trimmed.contains(".") || trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1"))
        }
        
        if (validLines == 0) return 0
        
        val avgLineLength = sampleContent.length / lines.size
        val estimatedTotalLines = totalSize / avgLineLength
        val estimatedValidLines = (estimatedTotalLines * validLines) / lines.size
        
        return estimatedValidLines.toInt()
    }

    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        
        return try {
            val parsedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            
            URL(parsedUrl)
            val urlRegex = Regex(
                "^https?://([\\w-]+\\.)+[\\w-]+(/[\\w-./?%&=]*)?$",
                RegexOption.IGNORE_CASE
            )
            urlRegex.matches(parsedUrl)
        } catch (e: MalformedURLException) {
            false
        }
    }

    fun normalizeUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
    }

    private fun loadPresetBlocklists() {
        val presets = listOf(
            BlocklistEntry(
                url = "https://someonewhocares.org/hosts/zero/hosts",
                name = "Dan Pollock's Hosts",
                description = "Comprehensive ad and malware blocking list",
                category = "Popular",
                entryCount = 15000
            ),
            BlocklistEntry(
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                name = "StevenBlack's Hosts",
                description = "Unified hosts file with base adware + malware",
                category = "Popular",
                entryCount = 120000
            ),
            BlocklistEntry(
                url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
                name = "Peter Lowe's List",
                description = "Ad server blocking list",
                category = "Popular",
                entryCount = 8000
            ),
            BlocklistEntry(
                url = "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/BaseFilter/sections/adservers.txt",
                name = "AdGuard Base Filter",
                description = "AdGuard base filtering rules",
                category = "AdGuard",
                entryCount = 25000
            ),
            BlocklistEntry(
                url = "https://easylist.to/easylist/easylist.txt",
                name = "EasyList",
                description = "The primary filter list that removes most adverts",
                category = "EasyList",
                entryCount = 45000
            )
        )
        _presetBlocklists.value = presets
    }

    fun importFromText(text: String) {
        _isImporting.value = true
        
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    parseBlocklistUrls(text)
                }
                _importResults.value = results
                Logger.debug { "[Blocklist] Import from text found ${results.size} URLs" }
            } finally {
                _isImporting.value = false
            }
        }
    }

    private fun parseBlocklistUrls(text: String): List<String> {
        val urlRegex = Regex(
            "https?://[\\w.-]+(?:\\.[\\w\\.-]+)+[\\w\\-\\._~:/?#\\[\\]@!\\$&'\\(\\)\\*\\+,;=.]+",
            RegexOption.IGNORE_CASE
        )
        
        return urlRegex.findAll(text)
            .map { it.value }
            .filter { isValidUrl(it) }
            .distinct()
            .toList()
    }

    fun getFormattedFileSize(bytes: Long?): String {
        if (bytes == null) return "Unknown size"
        
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    fun getFormattedEntryCount(count: Int?): String {
        if (count == null) return "Unknown entries"
        
        return when {
            count < 1000 -> "$count entries"
            count < 1000000 -> "${count / 1000}K entries"
            else -> "${count / 1000000}M entries"
        }
    }

    fun exportBlocklistUrls(blocklists: List<String>): String {
        return buildString {
            appendLine("# Custom Blocklists - Exported on ${java.util.Date()}")
            appendLine("# Generated by Athena DNS Blocker")
            appendLine()
            
            blocklists.forEachIndexed { index, url ->
                appendLine("# Blocklist ${index + 1}")
                appendLine(url)
                appendLine()
            }
        }
    }

    fun validateMultipleBlocklists(urls: List<String>) {
        viewModelScope.launch {
            urls.forEach { url ->
                try {
                    validateBlocklistUrl(url)
                } catch (e: Exception) {
                    // Log error but continue with other URLs
                    Logger.debug(e) { "[Blocklist] Validation of $url failed" }
                }
            }
        }
    }

    fun preloadBlocklistPreview(url: String, callback: (BlocklistValidationState) -> Unit) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    validateBlocklistUrl(url)
                }
                callback(result)
            } catch (e: Exception) {
                Logger.debug(e) { "[Blocklist] Preview of $url failed" }
                callback(
                    BlocklistValidationState(
                        isValid = false,
                        errorMessage = "Preview failed: ${e.message}"
                    )
                )
            }
        }
    }

    fun startDeletion(url: String) {
        Logger.debug { "[Blocklist] Deleting url=$url" }
        val current = _deletingItems.value.toMutableSet()
        current.add(url)
        _deletingItems.value = current
    }

    fun finishDeletion(url: String) {
        Logger.debug { "[Blocklist] Deleted url=$url" }
        val current = _deletingItems.value.toMutableSet()
        current.remove(url)
        _deletingItems.value = current
    }

    fun updateCategories(blocklists: List<BlocklistEntry>) {
        val categorized = blocklists.groupBy { it.category }
        _categories.value = categorized
    }

    fun getBlocklistByUrl(url: String, blocklists: List<BlocklistEntry>): BlocklistEntry? {
        return blocklists.find { it.url == url }
    }

    fun createBlocklistEntry(url: String, name: String, category: String = "Custom"): BlocklistEntry {
        // Always return entry with pending validation state - fresh validation will update it
        return BlocklistEntry(
            url = url,
            name = name.ifBlank { extractNameFromUrl(url) },
            category = category,
            isEnabled = true,
            lastValidated = System.currentTimeMillis(),
            validationState = BlocklistValidationState(isValidating = true)
        )
    }

    private fun extractNameFromUrl(url: String): String {
        return try {
            val parsedUrl = URL(url)
            val path = parsedUrl.path
            when {
                path.contains("hosts") -> "Hosts File"
                path.contains("adblock") || path.contains("easylist") -> "AdBlock List"
                path.contains("privacy") -> "Privacy List"
                path.contains("malware") -> "Malware Protection"
                path.contains("social") -> "Social Media Blocking"
                else -> "Custom Blocklist"
            }
        } catch (e: Exception) {
            "Custom Blocklist"
        }
    }

    companion object {
        const val CATEGORY_POPULAR = "Popular"
        const val CATEGORY_PRIVACY = "Privacy"
        const val CATEGORY_SECURITY = "Security"
        const val CATEGORY_ADBLOCK = "Ad Blocking"
        const val CATEGORY_SOCIAL = "Social Media"
        const val CATEGORY_CUSTOM = "Custom"
    }
}