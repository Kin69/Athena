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
package com.kin.athena.presentation.screens.settings.viewModel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kin.athena.BuildConfig
import com.kin.athena.R
import com.kin.athena.core.logging.Logger
import com.kin.athena.data.remote.VerifyLicenseUseCase
import com.kin.athena.data.service.billing.BillingProvider
import com.kin.athena.domain.model.Settings
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import com.kin.athena.service.utils.manager.FirewallManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import java.security.NoSuchAlgorithmException

data class FeatureChoice(
    val featureName: String,
    val featureDescription: String,
    val productId: String,
    val onSuccess: () -> Unit
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferenceUseCases: PreferencesUseCases,
    private val verifyLicenseUseCase: VerifyLicenseUseCase,
    private val billingProvider: BillingProvider,
    private val firewallManager: FirewallManager
) : ViewModel() {

    private val _settings = mutableStateOf(Settings())
    val settings: State<Settings> = _settings

    var defaultRoute: String? = null
    
    // Premium feature choice dialog state
    private val _showFeatureChoiceDialog = mutableStateOf(false)
    val showFeatureChoiceDialog: State<Boolean> = _showFeatureChoiceDialog
    
    private val _currentFeatureChoice = mutableStateOf<FeatureChoice?>(null)
    val currentFeatureChoice: State<FeatureChoice?> = _currentFeatureChoice
    val version: String = BuildConfig.VERSION_NAME
    val build: String = BuildConfig.BUILD_TYPE
    
    // Callback for when app filtering settings change
    var onAppFilteringSettingsChanged: (() -> Unit)? = null

    init {
        runBlocking {
            loadSettings()
        }
    }

    fun verifyLicense(key: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // Never log the key itself
        Logger.debug { "[Settings] License verification requested" }
        viewModelScope.launch {
            verifyLicenseUseCase.invoke(key).fold(
                ifSuccess = { licenseResponse ->
                    Logger.debug { "[Settings] License verification result valid=${licenseResponse.valid}" }
                    if (licenseResponse.valid == true) {
                        update(settings.value.copy(premiumUnlocked = true))
                        Toast.makeText(context, context.getString(R.string.premium_activation_success), Toast.LENGTH_LONG).show()
                        onResult(true, "✅ ${context.getString(R.string.premium_activation_success)}")
                    } else {
                        val errorMessage = licenseResponse.error ?: context.getString(R.string.premium_invalid_license)
                        Toast.makeText(context, context.getString(R.string.premium_activation_error, errorMessage), Toast.LENGTH_LONG).show()
                        onResult(false, context.getString(R.string.premium_activation_error, errorMessage))
                    }
                },
                ifFailure = { error ->
                    Logger.debug { "[Settings] License verification failed: ${error.message}" }
                    val errorMessage = "❌ Failed to verify license: ${error.message}"
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    onResult(false, errorMessage)
                }
            )
        }
    }

    fun startBilling(item: String, onSuccess: () -> Unit) {
        if (settings.value.premiumUnlocked) {
            Logger.info("Premium already unlocked, calling onSuccess")
            onSuccess()
        } else {
            billingProvider.getBillingInterface()?.showPurchaseDialog(item, onSuccess)
                ?: Logger.error("BillingInterface not available - activity not set")
        }
    }

    fun getProductPrice(productId: String): String? {
        return billingProvider.getBillingInterface()?.getProductPrice(productId)
    }

    fun calculateOriginalPrice(currentPrice: String?): String? {
        if (currentPrice == null) return null
        // Extract numeric value from price string (e.g., "$4.99" -> 4.99)
        val numericPrice = currentPrice.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
        return if (numericPrice != null) {
            val originalPrice = numericPrice * 1.2 // 20% higher
            val currencySymbol = currentPrice.replace(Regex("[\\d.]"), "")
            String.format("%.2f", originalPrice).let { 
                currencySymbol + it
            }
        } else currentPrice
    }

    fun showFeatureChoiceDialog(
        featureName: String,
        featureDescription: String,
        productId: String,
        onSuccess: () -> Unit
    ) {
        // Check if already unlocked before showing dialog
        if (settings.value.premiumUnlocked) {
            Logger.info("Premium already unlocked, executing onSuccess without showing dialog")
            onSuccess()
            return
        }

        // Check if this specific product is already owned
        val isOwned = billingProvider.getBillingInterface()?.isProductOwned(productId) ?: false
        if (isOwned) {
            Logger.info("Product $productId already owned, executing onSuccess without showing dialog")
            onSuccess()
            return
        }

        Logger.debug { "[Settings] SettingsViewModel(${this.hashCode()}) - Setting dialog state to true" }
        _currentFeatureChoice.value = FeatureChoice(
            featureName = featureName,
            featureDescription = featureDescription,
            productId = productId,
            onSuccess = onSuccess
        )
        _showFeatureChoiceDialog.value = true
        Logger.debug { "[Settings] SettingsViewModel(${this.hashCode()}) - Dialog state is now: ${_showFeatureChoiceDialog.value}" }
    }

    fun dismissFeatureChoiceDialog() {
        _showFeatureChoiceDialog.value = false
        _currentFeatureChoice.value = null
    }

    fun purchaseSingleFeature() {
        currentFeatureChoice.value?.let { choice ->
            startBilling(choice.productId, choice.onSuccess)
            dismissFeatureChoiceDialog()
        }
    }

    fun purchaseFullPremium() {
        currentFeatureChoice.value?.let { choice ->
            startBilling("all_features") {
                choice.onSuccess()
                // Also unlock premium globally
                update(settings.value.copy(premiumUnlocked = true))
            }
            dismissFeatureChoiceDialog()
        }
    }

    fun loadDefaultRoute() {
        defaultRoute = _settings.value.defaultRoute
    }

    private suspend fun loadSettings() {
        val result = preferenceUseCases.loadSettings.execute()
        result.fold(
            ifSuccess = { loadedSettings ->
                _settings.value = loadedSettings
                defaultRoute = loadedSettings.defaultRoute
            }
        )
    }

    fun updateDefaultRoute(route: String) {
        Logger.debug { "[Settings] defaultRoute=$route" }
        _settings.value = _settings.value.copy(defaultRoute = route)
        viewModelScope.launch {
            preferenceUseCases.saveSettings.execute(_settings.value)
        }
    }

    fun update(newSettings: Settings, onSuccess: (() -> Unit)? = null) {
        val oldSettings = _settings.value
        Logger.debug { "[Settings] Changed ${settingsDiff(oldSettings, newSettings)}" }
        _settings.value = newSettings.copy()

        // Check if app filtering settings changed and trigger reload IMMEDIATELY
        if (oldSettings.showSystemPackages != newSettings.showSystemPackages ||
            oldSettings.showOfflinePackages != newSettings.showOfflinePackages) {
            Logger.info("SettingsViewModel: App filtering settings changed, triggering immediate app reload")
            onAppFilteringSettingsChanged?.invoke()
        }

        viewModelScope.launch {
            try {
                preferenceUseCases.saveSettings.execute(newSettings).fold(
                    ifSuccess = {
                        // Check if blockPort80 setting changed
                        if (oldSettings.blockPort80 != newSettings.blockPort80) {
                            Logger.debug { "[Settings] blockPort80 changed, updating the HTTP rule" }
                            firewallManager.updateHttpSettings()
                        }

                        onSuccess?.invoke()
                    },
                    ifFailure = { error ->
                        Logger.error("Failed to save settings: ${error.message}", error)
                    }
                )
            } catch (e: CancellationException) {
                // Don't log cancellation as error - it's expected behavior
                Logger.debug("Settings update cancelled")
                throw e // Re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                Logger.error("Error updating settings: ${e.message}", e)
            }
        }
    }


    fun getAppSignature(): String? {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val signatures = packageInfo.signatures

            if (signatures != null) {
                for (signature in signatures) {
                    val cert = signature.toByteArray()
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(cert)
                    val hexString = digest.joinToString("") { "%02x".format(it) }
                    return hexString
                }
            }
        null
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.error("Failed to read the app signature", e)
            null
        } catch (e: NoSuchAlgorithmException) {
            Logger.error("Failed to read the app signature", e)
            null
        }
    }

    // Debug builds only: the settings that differ, as name=old->new. Lock secrets only say that they changed.
    private fun settingsDiff(old: Settings, new: Settings): String {
        val changes = Settings::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            val before = field.get(old)
            val after = field.get(new)
            when {
                before == after -> null
                field.name in SECRET_SETTINGS -> "${field.name}=<changed, set=${after != null}>"
                else -> "${field.name}=$before->$after"
            }
        }
        return if (changes.isEmpty()) "nothing" else changes.joinToString(" ")
    }

    fun getSupportedLanguages(context: Context): Map<String, String> {
        val localeList = getLocaleListFromXml(context)
        val map = mutableMapOf<String, String>()

        for (a in 0 until localeList.size()) {
            localeList[a].let {
                it?.let { it1 -> map.put(it1.getDisplayName(it), it.toLanguageTag()) }
            }
        }
        return map
    }

    // Taken from: https://stackoverflow.com/questions/74114067/get-list-of-locales-from-locale-config-in-android-13
    private fun getLocaleListFromXml(context: Context): LocaleListCompat {
        val tagsList = mutableListOf<CharSequence>()
        try {
            val xpp: XmlPullParser = context.resources.getXml(R.xml.locales_config)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG) {
                    if (xpp.name == "locale") {
                        tagsList.add(xpp.getAttributeValue(0))
                    }
                }
                xpp.next()
            }
        } catch (e: XmlPullParserException) {
            Logger.error("Failed to read locales_config", e)
        } catch (e: IOException) {
            Logger.error("Failed to read locales_config", e)
        }

        return LocaleListCompat.forLanguageTags(tagsList.joinToString(","))
    }
    
    suspend fun isPremiumFeatureEnabled(featureKey: String): Boolean {
        return settings.value.premiumUnlocked
    }

    suspend fun validatePremiumStatus(): Boolean {
        return settings.value.premiumUnlocked
    }

    fun isProductOwned(productId: String): Boolean {
        return billingProvider.getBillingInterface()?.isProductOwned(productId) ?: false
    }

    private companion object {
        val SECRET_SETTINGS = setOf("passcode", "pattern")
    }
}