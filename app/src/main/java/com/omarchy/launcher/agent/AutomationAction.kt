package com.omarchy.launcher.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import com.omarchy.launcher.data.AppRepository

/**
 * The fixed, local-only set of actions an automation rule can trigger. Kept as a flat enum
 * (rather than arbitrary code/scripts) so every possible agent action is auditable up front.
 */
enum class AutomationActionType(val id: String, val label: String, val needsArg: Boolean) {
    OPEN_APP("OPEN_APP", "Open another app", needsArg = true),
    WIFI_PANEL("WIFI_PANEL", "Show Wi-Fi quick panel", needsArg = false),
    VOLUME_PANEL("VOLUME_PANEL", "Show volume quick panel", needsArg = false),
    SET_TIMER("SET_TIMER", "Start a timer (minutes)", needsArg = true),
    TOAST("TOAST", "Show a reminder toast", needsArg = true);

    companion object {
        fun fromId(id: String): AutomationActionType? = entries.firstOrNull { it.id == id }
    }
}

/** Executes one AutomationActionType. Every branch stays local - no network calls. */
class AutomationExecutor(private val context: Context, private val appRepository: AppRepository) {

    fun execute(type: AutomationActionType, arg: String): String = try {
        when (type) {
            AutomationActionType.OPEN_APP -> {
                val target = appRepository.loadLaunchableApps().firstOrNull { it.packageName == arg }
                if (target != null) {
                    appRepository.launch(context, target)
                    "opened ${target.label}"
                } else "could not find app '$arg'"
            }

            AutomationActionType.WIFI_PANEL -> {
                startPanel(Settings.Panel.ACTION_WIFI)
                "opened Wi-Fi panel"
            }

            AutomationActionType.VOLUME_PANEL -> {
                startPanel(Settings.Panel.ACTION_VOLUME)
                "opened volume panel"
            }

            AutomationActionType.SET_TIMER -> {
                val minutes = arg.toIntOrNull() ?: 5
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "started a $minutes minute timer"
            }

            AutomationActionType.TOAST -> {
                Toast.makeText(context, arg, Toast.LENGTH_LONG).show()
                "showed reminder: \"$arg\""
            }
        }
    } catch (e: Exception) {
        "failed: ${e.message}"
    }

    private fun startPanel(action: String) {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
