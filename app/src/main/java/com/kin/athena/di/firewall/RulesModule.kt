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

package com.kin.athena.di.firewall

import android.content.Context
import com.kin.athena.data.service.ConnectionStateManager
import com.kin.athena.data.service.NetworkManager
import com.kin.athena.data.service.ScreenStateManager
import com.kin.athena.domain.usecase.application.ApplicationUseCases
import com.kin.athena.domain.usecase.log.LogUseCases
import com.kin.athena.domain.usecase.networkFilter.NetworkFilterUseCases
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.domain.repository.CustomDomainRepository
import com.kin.athena.data.cache.DomainCacheService
import com.kin.athena.presentation.screens.settings.subSettings.dns.hosts.RuleDatabase
import com.kin.athena.service.firewall.rule.AppRule
import com.kin.athena.service.firewall.handler.RuleHandler
import com.kin.athena.service.firewall.rule.CustomDomainRule
import com.kin.athena.service.firewall.rule.DNSRule
import com.kin.athena.service.firewall.rule.FilterRule
import com.kin.athena.service.firewall.rule.HTTPRule
import com.kin.athena.service.firewall.rule.LogRule
import com.kin.athena.service.firewall.rule.ScreenRule
import com.kin.athena.service.firewall.utils.ConnectivityUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RulesModule {
    @Provides
    @Singleton
    fun provideRuleHDatabase(): RuleDatabase {
        return RuleDatabase()
    }


    @Provides
    @Singleton
    fun provideScreenStateManager(): ScreenStateManager {
        return ScreenStateManager()
    }

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class ApplicationScope


    @Provides
    @Singleton
    fun provideConnectivityUtils(
        @ApplicationContext context: Context
    ): ConnectivityUtils {
        return ConnectivityUtils(context)
    }


    @Provides
    @Singleton
    fun provideNetworkManager(@ApplicationContext context: Context): NetworkManager {
        return NetworkManager(context)
    }

    @Provides
    @Singleton
    fun provideConnectionStateManager(
        networkManager: NetworkManager,
        @ApplicationContext context: Context
    ): ConnectionStateManager {
        return ConnectionStateManager(context, networkManager)
    }

    @Provides
    @Singleton
    fun provideLogRule(
        preferencesUseCases: PreferencesUseCases,
        @ApplicationScope externalScope: CoroutineScope
    ): LogRule {
        return LogRule(preferencesUseCases, externalScope)
    }

    @Provides
    @Singleton
    fun provideHTTPRule(
        preferencesUseCases: PreferencesUseCases,
        @ApplicationScope externalScope: CoroutineScope
    ): HTTPRule {
        return HTTPRule(preferencesUseCases, externalScope)
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideAppRule(
        applicationUseCases: ApplicationUseCases,
        connectionStateManager: ConnectionStateManager,
        connectivityUtils: ConnectivityUtils
    ): AppRule {
        return AppRule(applicationUseCases, connectionStateManager, connectivityUtils)
    }

    @Provides
    @Singleton
    fun provideFilterRule(
        networkFilterUseCases: NetworkFilterUseCases
    ): FilterRule {
        return FilterRule(networkFilterUseCases)
    }

    @Provides
    @Singleton
    fun provideScreenRule(
        screenStateManager: ScreenStateManager,
        preferencesUseCases: PreferencesUseCases,
    ): ScreenRule {
        return ScreenRule(screenStateManager, preferencesUseCases)
    }

    @Provides
    @Singleton
    fun provideDomainCacheService(ruleDatabase: RuleDatabase): DomainCacheService {
        return DomainCacheService(ruleDatabase)
    }

    @Provides
    @Singleton
    fun provideDNSRule(ruleDatabase: RuleDatabase): DNSRule {
        return DNSRule(ruleDatabase)
    }

    @Provides
    @Singleton
    fun provideCustomDomainRule(
        customDomainRepository: CustomDomainRepository
    ): CustomDomainRule {
        return CustomDomainRule(customDomainRepository)
    }

    @Provides
    @Singleton
    fun provideRuleManager(
        appRule: AppRule,
        filterRule: FilterRule,
        logRule: LogRule,
        httpRule: HTTPRule,
        screenRule: ScreenRule,
        DNSRule: DNSRule,
        customDomainRule: CustomDomainRule,
        logUseCases: LogUseCases,
        preferencesUseCases: PreferencesUseCases,
        @ApplicationContext context: Context
    ): RuleHandler {
        return RuleHandler(listOf(appRule, DNSRule, customDomainRule, filterRule, screenRule, httpRule, logRule), logUseCases, preferencesUseCases, context)
    }
}