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

package com.kin.athena.service.utils.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kin.athena.R
import com.kin.athena.core.logging.Logger
import com.kin.athena.core.utils.NotificationUtils
import com.kin.athena.core.utils.extensions.requiresNetworkPermissions
import com.kin.athena.domain.model.Application
import com.kin.athena.domain.usecase.preferences.PreferencesUseCases
import com.kin.athena.presentation.MainActivity

@SuppressLint("MissingPermission")
suspend fun Service.showStartNotification(applications: List<Application>, preferencesUseCases: PreferencesUseCases, context: Context? = null) {
    val contextNot = context ?: this

    preferencesUseCases.loadSettings.execute().fold(
        ifSuccess = { settings ->
            var enabled = applications.filter { it.internetAccess || it.cellularAccess }
            var disabled = applications.filter { !it.internetAccess || !it.cellularAccess }

            if (!settings.showSystemPackages) {
                enabled = enabled.filter { !it.systemApp }
                disabled = disabled.filter { !it.systemApp }
            }
            if (!settings.showOfflinePackages) {
                enabled = enabled.filter { it.requiresNetworkPermissions(contextNot.packageManager) }
                disabled = disabled.filter { it.requiresNetworkPermissions(contextNot.packageManager) }
            }

            val networkStats = Pair(enabled.count(), disabled.count())
            Logger.debug { "[Fw] Service notification: allowed=${networkStats.first} blocked=${networkStats.second} foreground=${context == null} permanent=${settings.permanentNotification}" }

            val channelID = NotificationUtils.createNotificationChannel(
                context = contextNot,
                channelId = "service_running",
                channelName = R.string.vpn_service_channel_name,
                channelDescription = R.string.vpn_service_channel_description,
                importance = if (settings.permanentNotification) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
            )


            val notificationPendingIntent = PendingIntent.getActivity(
                contextNot,
                0,
                Intent(contextNot, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationNotColorized =
                NotificationCompat.Builder(contextNot, channelID).apply {
                    setContentTitle(contextNot.getString(R.string.vpn_service_running))
                    setContentText(contextNot.getString(R.string.notification_allowed_blocked, networkStats.first, networkStats.second))
                    if (settings.permanentNotification) {
                        setColorized(true)
                        setOngoing(true)
                        setPriority(NotificationCompat.PRIORITY_HIGH)
                        setColor(Color.argb(100, 255,100,0))
                    } else {
                        setSilent(true)
                        setPriority(NotificationCompat.PRIORITY_LOW)
                    }
                    setSmallIcon(R.drawable.logo)
                    setContentIntent(notificationPendingIntent)
                }.build()
            if (context == null) {
                startForeground(2, notificationNotColorized)
            } else {
                NotificationManagerCompat.from(contextNot).notify(2, notificationNotColorized)
            }
        },
        ifFailure = { Logger.error("Failed to load settings: ${it.message}") }
    )
}