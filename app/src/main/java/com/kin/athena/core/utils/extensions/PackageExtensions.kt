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

package com.kin.athena.core.utils.extensions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import com.kin.athena.core.logging.Logger
import com.kin.athena.domain.model.Application
import java.util.Locale

fun Application.toApplicationInfo(packageManager: PackageManager): ApplicationInfo? {
    return try {
        packageManager.getApplicationInfo(packageID, PackageManager.GET_PERMISSIONS)
    } catch (e: NameNotFoundException) {
        null
    }
}

fun Application.getApplicationName(packageManager: PackageManager): String? {
    return try {
        val appInfo = toApplicationInfo(packageManager)
        appInfo?.let {
            packageManager.getApplicationLabel(appInfo).toString()
        }
    } catch (e: NameNotFoundException) {
        null
    }
}

fun Application.requiresNetworkPermissions(packageManager: PackageManager): Boolean {
    return try {
        val packageInfo = packageManager.getPackageInfo(this.packageID, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions ?: emptyArray()

        permissions.any { permission ->
            permission == "android.permission.INTERNET" || permission == "android.permission.ACCESS_NETWORK_STATE"
        }
    } catch (e: NameNotFoundException) {
        false
    }
}

fun Application.usesGooglePlayServices(): Boolean {
    // Only show GMS indicator for these specific messaging apps
    val messagingApps = listOf(
        "com.discord",
        "com.facebook.mlite", 
        "com.facebook.orca",
        "com.instagram.android",
        "com.Slack",
        "com.skype.raider",
        "com.snapchat.android", 
        "com.whatsapp",
        "com.whatsapp.w4b"
    )
    
    // Return true only if this is one of the specified messaging apps
    return messagingApps.contains(packageID)
}



fun Application.getApplicationIcon(
    packageManager: PackageManager,
    tintColor: Int,
    useDynamicIcon: Boolean = false,
    context: Context
): Drawable? {
    return try {
        val appInfo = toApplicationInfo(packageManager) ?: return null
        val icon = packageManager.getApplicationIcon(appInfo)

        // Process the icon based on type and preferences
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    icon is AdaptiveIconDrawable &&
                    useDynamicIcon -> {
                icon.monochrome?.let { monochrome ->
                    applyTint(monochrome, tintColor).processIcon(context)
                } ?: icon.foreground?.processIcon(context) ?: icon
            }
            else -> icon.processIcon(context)
        }
    } catch (e: NameNotFoundException) {
        Logger.error("Package not found for icon loading: ${packageID}")
        null
    } catch (e: OutOfMemoryError) {
        Logger.error("Out of memory loading icon for: ${packageID}")
        null
    } catch (e: Exception) {
        Logger.error("Failed to load icon for ${packageID}: ${e.message}", e)
        null
    }
}

/**
 * Combines makeCircle() and removeWhite() into a single pass for efficiency.
 */
private fun Drawable.processIcon(context: Context): Drawable {
    if (isAlreadyCircular()) {
        return this
    }

    val bitmap = this.toBitmap()
    val size = minOf(bitmap.width, bitmap.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint().apply { isAntiAlias = true }
    val radius = size / 2f

    // Draw the circle mask
    canvas.drawCircle(radius, radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)


    canvas.drawBitmap(bitmap, (size - bitmap.width) / 2f, (size - bitmap.height) / 2f, paint)
    return BitmapDrawable(context.resources, output)
}

private fun Drawable.isAlreadyCircular(): Boolean {
    return when (this) {
        is ShapeDrawable -> this.shape is OvalShape
        is BitmapDrawable -> {
            val bitmap = this.bitmap
            if (bitmap.width != bitmap.height) return false
            val radius = bitmap.width / 2
            val centerPixel = bitmap.getPixel(radius, radius)
            val topLeftPixel = bitmap.getPixel(0, 0)
            val bottomRightPixel = bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
            (centerPixel ushr 24 != 0) && (topLeftPixel ushr 24 == 0) && (bottomRightPixel ushr 24 == 0)
        }
        else -> false
    }
}

private fun applyTint(drawable: Drawable, color: Int): Drawable {
    val wrappedDrawable = DrawableCompat.wrap(drawable)
    DrawableCompat.setTint(wrappedDrawable, color)
    return wrappedDrawable
}

fun Application.toPackageInfo(packageManager: PackageManager): PackageInfo? {
    return try {
        packageManager.getPackageInfo(packageID, PackageManager.GET_PERMISSIONS)
    } catch (e: NameNotFoundException) {
        null
    }
}

fun Application.getPermissions(packageManager: PackageManager): List<Pair<String, String>>? {
    return try {
        val packageInfo = packageManager.getPackageInfo(this.packageID, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions ?: emptyArray()

        permissions.mapNotNull { permission ->
            try {
                val permissionInfo = packageManager.getPermissionInfo(permission, 0)
                val cleanPermission = permission.removePrefix("android.permission.").replace("_", " ")
                val description = permissionInfo.loadLabel(packageManager).toString().replaceFirstChar { it.uppercase() }
                Pair(cleanPermission, description)
            } catch (e: NameNotFoundException) {
                null
            }
        }
    } catch (e: NameNotFoundException) {
        null
    }
}

fun Context.uidToApplication(uid: Int): Application? {
    return try {
        val packageManager = packageManager
        val packages = packageManager.getPackagesForUid(uid)
        Application(
            packageID = packages?.toList()?.first().toString(),
            uid = uid,
            systemApp = false
        )
    } catch (e: Exception) {
        Logger.debug(e) { "[Apps] No application for uid=$uid" }
        null
    }
}

fun Context.getAppNameFromPackage(packageId: String): String? {
    return try {
        val packageManager = packageManager
        val applicationInfo = packageManager.getApplicationInfo(packageId, 0)
        packageManager.getApplicationLabel(applicationInfo).toString()
    } catch (e: NameNotFoundException) {
        Logger.error("Package not found: $packageId")
        null
    }
}