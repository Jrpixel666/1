package com.omarchy.launcher

import android.content.Context
import com.omarchy.launcher.agent.AutomationEngine
import com.omarchy.launcher.agent.AutomationExecutor
import com.omarchy.launcher.agent.AutomationTriggerType
import com.omarchy.launcher.agent.CommandParser
import com.omarchy.launcher.agent.TimeOfDayScheduler
import com.omarchy.launcher.data.AgentPreferences
import com.omarchy.launcher.data.AppRepository
import com.omarchy.launcher.data.AutomationRule
import com.omarchy.launcher.data.NativeWidgetPreferences
import com.omarchy.launcher.data.WidgetPreferences
import com.omarchy.launcher.data.WidgetRepository
import com.omarchy.launcher.nativewidgets.NativeWidgetHostManager
import com.omarchy.launcher.termux.TermuxController

/** Tiny manual DI container - no DI framework needed for an app this size. */
class AppContainer(private val context: Context) {
    val appRepository = AppRepository(context)
    val agentPreferences = AgentPreferences(context)
    val termuxController = TermuxController(context)
    val automationExecutor = AutomationExecutor(context, appRepository)
    val automationEngine = AutomationEngine(agentPreferences, automationExecutor)
    val commandParser = CommandParser(appRepository)
    val widgetRepository = WidgetRepository(context).apply { ensureDefaultsInstalled() }
    val widgetPreferences = WidgetPreferences(context)
    val nativeWidgetHostManager = NativeWidgetHostManager(context)
    val nativeWidgetPreferences = NativeWidgetPreferences(context)

    /** Keeps AlarmManager in sync with the current rule set / master automations switch. */
    fun syncTimeOfDayAlarms(rules: List<AutomationRule>, automationsEnabled: Boolean) {
        if (automationsEnabled) {
            TimeOfDayScheduler.syncAll(context, rules)
        } else {
            rules.filter { it.triggerType == AutomationTriggerType.TIME_OF_DAY.id }
                .forEach { TimeOfDayScheduler.cancel(context, it.id) }
        }
    }
}
