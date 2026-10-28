package com.omarchy.launcher.agent

/** The fixed set of triggers an automation rule can fire on. */
enum class AutomationTriggerType(val id: String, val label: String) {
    APP_OPENED("APP_OPENED", "App opens"),
    TIME_OF_DAY("TIME_OF_DAY", "Time of day");

    companion object {
        fun fromId(id: String): AutomationTriggerType = entries.firstOrNull { it.id == id } ?: APP_OPENED
    }
}
