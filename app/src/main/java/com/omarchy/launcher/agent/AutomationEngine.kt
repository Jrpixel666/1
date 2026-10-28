package com.omarchy.launcher.agent

import com.omarchy.launcher.data.AgentPreferences
import com.omarchy.launcher.data.AutomationRule
import kotlinx.coroutines.flow.first

/**
 * Local "agent" #2: a small if-this-then-that engine. Trigger is always "app X was opened";
 * action is one of AutomationActionType. Every firing is written to the agent log so the
 * automations screen can show exactly what the agent did and why - nothing happens silently.
 */
class AutomationEngine(
    private val preferences: AgentPreferences,
    private val executor: AutomationExecutor,
) {
    suspend fun onAppOpened(packageName: String) {
        val enabled = try {
            preferences.automationsEnabled.first()
        } catch (e: Exception) {
            true
        }
        if (!enabled) return

        val rules = try {
            preferences.rules.first()
        } catch (e: Exception) {
            emptyList<AutomationRule>()
        }
        rules.filter { it.enabled && it.triggerPackage == packageName }
            .forEach { rule -> fire(rule) }
    }

    private suspend fun fire(rule: AutomationRule) {
        val type = AutomationActionType.fromId(rule.actionType) ?: return
        val outcome = executor.execute(type, rule.actionArg)
        preferences.appendLog("rule \"${rule.name}\" fired -> $outcome")
    }
}
