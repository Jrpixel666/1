package com.omarchy.launcher.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omarchy.launcher.OmarchyLauncherApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Fires when a time-of-day automation's daily alarm goes off, then reschedules itself for tomorrow. */
class TimeOfDayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(TimeOfDayScheduler.EXTRA_RULE_ID) ?: return
        val container = (context.applicationContext as OmarchyLauncherApplication).container
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val automationsEnabled = container.agentPreferences.automationsEnabled.first()
                val rule = container.agentPreferences.rules.first().firstOrNull { it.id == ruleId }
                if (rule != null && rule.enabled) {
                    if (automationsEnabled) {
                        val type = AutomationActionType.fromId(rule.actionType)
                        if (type != null) {
                            val outcome = container.automationExecutor.execute(type, rule.actionArg)
                            container.agentPreferences.appendLog("rule \"${rule.name}\" fired -> $outcome")
                        }
                    }
                    // Reschedule for tomorrow regardless of the automations-enabled master switch,
                    // so flipping it back on later doesn't require re-saving every rule.
                    TimeOfDayScheduler.schedule(context, rule)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
