package com.llamaaistudio.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Hands an APK built on-device to Android's package installer. */
object ApkInstaller {

    sealed interface Result {
        data object Launched : Result
        /** "Install unknown apps" isn't allowed for this app yet; settings were opened. */
        data object NeedsPermission : Result
        data class Failed(val message: String) : Result
    }

    fun install(context: Context, apk: File): Result {
        if (!apk.isFile) return Result.Failed("APK not found: ${apk.name} — build the project first")
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return Result.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Launched
        } catch (e: ActivityNotFoundException) {
            Result.Failed("No package installer available")
        }
    }
}
