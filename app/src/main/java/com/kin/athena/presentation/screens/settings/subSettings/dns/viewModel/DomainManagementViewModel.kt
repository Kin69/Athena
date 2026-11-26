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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.CustomDomain
import com.kin.athena.domain.repository.CustomDomainRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DomainManagementViewModel @Inject constructor(
    private val customDomainRepository: CustomDomainRepository
) : ViewModel() {

    private val _allowlistDomains = MutableStateFlow<List<CustomDomain>>(emptyList())
    val allowlistDomains: StateFlow<List<CustomDomain>> = _allowlistDomains.asStateFlow()

    private val _blocklistDomains = MutableStateFlow<List<CustomDomain>>(emptyList())
    val blocklistDomains: StateFlow<List<CustomDomain>> = _blocklistDomains.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _allowlistCount = MutableStateFlow(0)
    val allowlistCount: StateFlow<Int> = _allowlistCount.asStateFlow()

    private val _blocklistCount = MutableStateFlow(0)
    val blocklistCount: StateFlow<Int> = _blocklistCount.asStateFlow()

    init {
        loadDomains()
        loadCounts()
    }

    private fun loadDomains() {
        _isLoading.value = true
        
        // Load allowlist domains
        viewModelScope.launch {
            customDomainRepository.getAllowlistDomains()
                .catch { e ->
                    Logger.error("Failed to load allowlist domains: ${e.message}", e)
                    _errorMessage.value = "Failed to load allowlist domains"
                }
                .collect { domains ->
                    _allowlistDomains.value = domains
                    _isLoading.value = false
                }
        }

        // Load blocklist domains
        viewModelScope.launch {
            customDomainRepository.getBlocklistDomains()
                .catch { e ->
                    Logger.error("Failed to load blocklist domains: ${e.message}", e)
                    _errorMessage.value = "Failed to load blocklist domains"
                }
                .collect { domains ->
                    _blocklistDomains.value = domains
                }
        }
    }

    private fun loadCounts() {
        viewModelScope.launch {
            try {
                _allowlistCount.value = customDomainRepository.getAllowlistCount()
                _blocklistCount.value = customDomainRepository.getBlocklistCount()
            } catch (e: Exception) {
                Logger.error("Failed to load domain counts: ${e.message}", e)
            }
        }
    }

    fun addDomain(domain: String, description: String, isRegex: Boolean, isAllowlist: Boolean) {
        viewModelScope.launch {
            try {
                Logger.info("=== addDomain called ===")
                Logger.info("Original input: '$domain'")
                Logger.info("isRegex: $isRegex")
                Logger.info("isAllowlist: $isAllowlist")
                
                // Extract clean domain from URL if needed (for non-regex entries)
                val cleanDomain = if (isRegex) domain else extractDomainFromUrl(domain)
                Logger.info("Clean domain after extraction: '$cleanDomain'")
                
                // Validate the clean domain (not the original input)
                val validationResult = validateDomain(cleanDomain, isRegex)
                Logger.info("Validation result: $validationResult")
                if (validationResult is ValidationResult.Error) {
                    Logger.error("Validation failed with message: ${validationResult.message}")
                    _errorMessage.value = validationResult.message
                    return@launch
                }

                // Check for duplicates using the clean domain
                val isDuplicate = customDomainRepository.isDomainExists(cleanDomain, isAllowlist)
                if (isDuplicate) {
                    Logger.debug { "[UI] Custom domain $cleanDomain already in ${if (isAllowlist) "allowlist" else "blocklist"}, not added" }
                    _errorMessage.value = "Domain already exists in ${if (isAllowlist) "allowlist" else "blocklist"}"
                    return@launch
                }

                val customDomain = CustomDomain(
                    domain = cleanDomain,
                    description = description,
                    isRegex = isRegex,
                    isAllowlist = isAllowlist
                )

                customDomainRepository.insertDomain(customDomain)
                loadCounts() // Update counts after adding
                Logger.info("Added domain: $cleanDomain to ${if (isAllowlist) "allowlist" else "blocklist"}")
                
            } catch (e: Exception) {
                Logger.error("Failed to add domain: ${e.message}", e)
                _errorMessage.value = "Failed to add domain: ${e.message}"
            }
        }
    }

    fun removeDomain(domain: CustomDomain) {
        viewModelScope.launch {
            try {
                customDomainRepository.deleteDomain(domain)
                loadCounts() // Update counts after removing
                Logger.info("Removed domain: ${domain.domain} from ${if (domain.isAllowlist) "allowlist" else "blocklist"}")
                
            } catch (e: Exception) {
                Logger.error("Failed to remove domain: ${e.message}", e)
                _errorMessage.value = "Failed to remove domain: ${e.message}"
            }
        }
    }

    fun toggleDomainEnabled(domain: CustomDomain) {
        viewModelScope.launch {
            try {
                customDomainRepository.updateDomainEnabled(domain.id, !domain.isEnabled)
                Logger.debug { "[UI] Custom domain ${domain.domain} id=${domain.id} enabled=${domain.isEnabled}->${!domain.isEnabled}" }
                Logger.info("Toggled domain enabled state: ${domain.domain}")
                
            } catch (e: Exception) {
                Logger.error("Failed to toggle domain enabled state: ${e.message}", e)
                _errorMessage.value = "Failed to update domain: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun validateDomain(domain: String, isRegex: Boolean): ValidationResult {
        Logger.info("=== validateDomain called ===")
        Logger.info("Domain: '$domain'")
        Logger.info("Domain length: ${domain.length}")
        Logger.info("isRegex: $isRegex")
        
        return when {
            domain.isBlank() -> {
                Logger.warn("VALIDATION FAILED: blank domain")
                ValidationResult.Error("Please enter a domain")
            }
            isRegex -> {
                try {
                    Regex(domain)
                    Logger.info("VALIDATION SUCCESS: Regex validation successful for: '$domain'")
                    ValidationResult.Success
                } catch (e: Exception) {
                    Logger.warn("VALIDATION FAILED: Regex validation failed for: '$domain' - ${e.message}")
                    ValidationResult.Error("Invalid regex pattern: ${e.message}")
                }
            }
            else -> {
                Logger.info("Checking domain validation rules...")
                when {
                    domain.contains(" ") -> {
                        Logger.warn("VALIDATION FAILED: contains spaces - '$domain'")
                        ValidationResult.Error("Domain cannot contain spaces")
                    }
                    domain.length < 2 -> {
                        Logger.warn("VALIDATION FAILED: too short (${domain.length}) - '$domain'")
                        ValidationResult.Error("Domain too short (minimum 2 characters)")
                    }
                    domain.length > 253 -> {
                        Logger.warn("VALIDATION FAILED: too long (${domain.length}) - '$domain'")
                        ValidationResult.Error("Domain too long (maximum 253 characters)")
                    }
                    domain.startsWith(".") -> {
                        Logger.warn("VALIDATION FAILED: starts with dot - '$domain'")
                        ValidationResult.Error("Domain cannot start with a dot")
                    }
                    !domain.contains(".") && domain.length > 2 -> {
                        // Allow simple hostnames like "localhost" but warn for very short ones
                        Logger.info("VALIDATION SUCCESS: allowing hostname without dots: '$domain'")
                        ValidationResult.Success
                    }
                    !domain.matches(Regex("^[a-zA-Z0-9.-]+$")) -> {
                        Logger.warn("VALIDATION FAILED: invalid characters - '$domain'")
                        Logger.warn("Characters check: ${domain.toCharArray().joinToString { "'$it'(${it.code})" }}")
                        ValidationResult.Error("Domain contains invalid characters")
                    }
                    else -> {
                        Logger.info("VALIDATION SUCCESS: Domain validation successful for: '$domain'")
                        ValidationResult.Success
                    }
                }
            }
        }
    }

    private fun extractDomainFromUrl(input: String): String {
        return try {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return trimmed
            
            var domain = trimmed
            
            // Remove protocol if present (http://, https://, ftp://, etc.)
            domain = domain.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
            
            // Remove www. if present
            domain = domain.replace(Regex("^www\\."), "")
            
            // Remove everything after first slash (path)
            domain = domain.split("/")[0]
            
            // Remove everything after ? (query parameters)
            domain = domain.split("?")[0]
            
            // Remove everything after # (fragment)
            domain = domain.split("#")[0]
            
            // Remove port number if present
            domain = domain.split(":")[0]
            
            // Remove any trailing dots
            domain = domain.trimEnd('.')
            
            // Convert to lowercase for consistency
            domain = domain.lowercase()
            
            Logger.info("Domain extraction: '$input' -> '$domain'")
            domain
            
        } catch (e: Exception) {
            Logger.error("Error extracting domain from: '$input'", e)
            input.trim().lowercase() // Return original if extraction fails
        }
    }

    fun deleteAllDomainsByType(isAllowlist: Boolean) {
        viewModelScope.launch {
            try {
                customDomainRepository.deleteAllDomainsByType(isAllowlist)
                loadCounts()
                Logger.info("Deleted all ${if (isAllowlist) "allowlist" else "blocklist"} domains")
                
            } catch (e: Exception) {
                Logger.error("Failed to delete all domains: ${e.message}", e)
                _errorMessage.value = "Failed to delete domains: ${e.message}"
            }
        }
    }
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}