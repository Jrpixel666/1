package com.omarchy.launcher.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

sealed class TermuxDispatchResult {
    data object Dispatched : TermuxDispatchResult()
    data object TermuxNotInstalled : TermuxDispatchResult()
    data object PermissionNotGranted : TermuxDispatchResult()
    data class Failed(val reason: String) : TermuxDispatchResult()
}

/**
 * Fires scripts into Termux via its RUN_COMMAND Intent API and streams the result back into
 * TermuxOutputBus. This app never ships or executes its own proot/busybox binaries - it hands
 * the work to Termux, which already solves Android's exec-from-storage restrictions properly.
 */
class TermuxController(private val context: Context) {

    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TermuxConstants.TERMUX_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, TermuxConstants.RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Runs [script] as `bash -c "<script>"` inside Termux's own environment, in the background. */
    fun runScript(script: String, label: String): TermuxDispatchResult {
        if (!isTermuxInstalled()) return TermuxDispatchResult.TermuxNotInstalled
        if (!hasRunCommandPermission()) return TermuxDispatchResult.PermissionNotGranted

        return try {
            TermuxOutputBus.append("\$ $label")

            val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                label.hashCode(),
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val intent = Intent(TermuxConstants.ACTION_RUN_COMMAND).apply {
                setClassName(TermuxConstants.TERMUX_PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE)
                putExtra(TermuxConstants.EXTRA_COMMAND_PATH, TermuxConstants.DEFAULT_BASH)
                putExtra(TermuxConstants.EXTRA_ARGUMENTS, arrayOf("-c", script))
                putExtra(TermuxConstants.EXTRA_WORKDIR, TermuxConstants.DEFAULT_HOME)
                putExtra(TermuxConstants.EXTRA_BACKGROUND, true)
                putExtra(TermuxConstants.EXTRA_LABEL, label)
                putExtra(TermuxConstants.EXTRA_PENDING_INTENT, pendingIntent)
            }
            ContextCompat.startForegroundService(context, intent)
            TermuxDispatchResult.Dispatched
        } catch (e: Exception) {
            TermuxOutputBus.append("[error] ${e.message}")
            TermuxDispatchResult.Failed(e.message ?: "unknown error")
        }
    }

    fun openTermux() {
        val intent = context.packageManager.getLaunchIntentForPackage(TermuxConstants.TERMUX_PACKAGE)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { context.startActivity(it) }
    }
}
