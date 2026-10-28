package com.omarchy.launcher.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omarchy.launcher.data.AppRepository

/**
 * The fixed, auditable set of things a widget's `onTap` can do - same philosophy as the
 * automation engine's action set. Parsed from call-like prop values such as `open("pkg.name")`
 * or `url("https://...")`.
 */
sealed class WidgetAction {
    data class OpenApp(val packageName: String) : WidgetAction()
    data class OpenUrl(val url: String) : WidgetAction()

    companion object {
        private val callPattern = Regex("""^(\w+)\((.*)\)$""")

        fun parse(raw: String?): WidgetAction? {
            if (raw == null) return null
            val match = callPattern.matchEntire(raw.trim()) ?: return null
            val (fn, arg) = match.destructured
            return when (fn) {
                "open" -> OpenApp(arg.trim())
                "url" -> OpenUrl(arg.trim())
                else -> null
            }
        }
    }

    fun perform(context: Context, appRepository: AppRepository) {
        when (this) {
            is OpenApp -> {
                val app = appRepository.loadLaunchableApps().firstOrNull { it.packageName == packageName }
                if (app != null) {
                    appRepository.launch(context, app)
                } else {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launchIntent?.let { context.startActivity(it) }
                }
            }
            is OpenUrl -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
