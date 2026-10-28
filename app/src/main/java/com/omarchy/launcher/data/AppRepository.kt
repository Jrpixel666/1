package com.omarchy.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

data class AppInfo(
    val packageName: String,
    val activityClassName: String,
    val label: String,
    val isSystemApp: Boolean,
) {
    val key: String get() = "$packageName/$activityClassName"
}

/** Thin wrapper around PackageManager - the only place in the app that talks to it. */
class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun loadLaunchableApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        return resolved
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val appInfo = ri.activityInfo.applicationInfo
                AppInfo(
                    packageName = ri.activityInfo.packageName,
                    activityClassName = ri.activityInfo.name,
                    label = ri.loadLabel(packageManager).toString(),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun loadIcon(app: AppInfo): Drawable? = try {
        packageManager.getActivityIcon(
            android.content.ComponentName(app.packageName, app.activityClassName)
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun launch(context: Context, app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityClassName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ContextCompat.startActivity(context, intent, null)
    }

    fun openAppInfo(context: Context, app: AppInfo) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", app.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ContextCompat.startActivity(context, intent, null)
    }

    fun isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
