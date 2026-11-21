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

package com.kin.athena.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.kin.athena.core.utils.constants.AppConstants
import kotlinx.coroutines.flow.first
import com.kin.athena.domain.model.Settings
import com.kin.athena.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.map
import java.lang.reflect.Modifier

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(AppConstants.DatabaseConstants.SETTINGS_DATABASE_NAME)

// Skips the compiler's static final fields, such as Compose's $stable. Writing one
// by reflection throws IllegalAccessException when targeting Android 17.
private val settingsFields = Settings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    override suspend fun saveSettings(settings: Settings) {
        context.dataStore.edit { preferences ->
            settingsFields.forEach { field ->
                field.isAccessible = true
                val key = field.name
                when (val value = field.get(settings)) {
                    is Boolean? -> value?.let { preferences[booleanPreferencesKey(key)] = it } ?: preferences.remove(booleanPreferencesKey(key))
                    is String? -> value?.let { preferences[stringPreferencesKey(key)] = it } ?: preferences.remove(stringPreferencesKey(key))
                    is Int? -> value?.let { preferences[intPreferencesKey(key)] = it } ?: preferences.remove(intPreferencesKey(key))
                    is Float? -> value?.let { preferences[floatPreferencesKey(key)] = it } ?: preferences.remove(floatPreferencesKey(key))
                    is Long? -> value?.let { preferences[longPreferencesKey(key)] = it } ?: preferences.remove(longPreferencesKey(key))
                    null -> preferences.remove(stringPreferencesKey(key))
                    else -> throw IllegalArgumentException("Unsupported type: ${value.javaClass}")
                }
            }
        }
    }

    override suspend fun loadSettings(): Settings {
        val preferences = context.dataStore.data.first()
        val settings = Settings()

        settingsFields.forEach { field ->
            field.isAccessible = true
            val key = field.name
            val value = when {
                Boolean::class.java == field.type || java.lang.Boolean::class.java == field.type -> preferences[booleanPreferencesKey(key)] ?: field.get(settings) as? Boolean
                String::class.java == field.type || java.lang.String::class.java == field.type  -> preferences[stringPreferencesKey(key)] ?: field.get(settings) as? String
                Int::class.java == field.type  || Integer::class.java == field.type  -> preferences[intPreferencesKey(key)] ?: field.get(settings) as? Int
                Float::class.java == field.type  || java.lang.Float::class.java == field.type -> preferences[floatPreferencesKey(key)] ?: field.get(settings) as? Float
                Long::class.java == field.type  || java.lang.Long::class.java == field.type -> preferences[longPreferencesKey(key)] ?: field.get(settings) as? Long
                else -> null
            }
            field.set(settings, value)
        }

        return settings
    }
}
