package com.omarchy.launcher

import android.content.Context
import com.omarchy.launcher.agent.AutomationEngine
import com.omarchy.launcher.agent.AutomationExecutor
import com.omarchy.launcher.agent.CommandParser
import com.omarchy.launcher.data.AgentPreferences
import com.omarchy.launcher.data.AppRepository
import com.omarchy.launcher.termux.TermuxController

/** Tiny manual DI container - no DI framework needed for an app this size. */
class AppContainer(context: Context) {
    val appRepository = AppRepository(context)
    val agentPreferences = AgentPreferences(context)
    val termuxController = TermuxController(context)
    val automationExecutor = AutomationExecutor(context, appRepository)
    val automationEngine = AutomationEngine(agentPreferences, automationExecutor)
    val commandParser = CommandParser(appRepository)
}
