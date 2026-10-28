package com.omarchy.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.agentDataStore by preferencesDataStore(name = "agent_state")

@Serializable
data class UsageEvent(val packageName: String, val timestampMillis: Long, val hourOfDay: Int)

@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val triggerPackage: String,
    val actionType: String, // see AutomationAction.kt for the fixed set of values
    val actionArg: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class AgentLogEntry(val timestampMillis: Long, val message: String)

/**
 * Every piece of "agent" state the launcher keeps - usage history, automation rules, the
 * transparency log - lives in this one on-device DataStore. Nothing here is ever sent anywhere.
 */
class AgentPreferences(private val context: Context) {

    private object Keys {
        val USAGE_EVENTS = stringPreferencesKey("usage_events_json")
        val RULES = stringPreferencesKey("automation_rules_json")
        val LOG = stringPreferencesKey("agent_log_json")
        val AUTOMATIONS_ENABLED = booleanPreferencesKey("automations_enabled")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val maxUsageEvents = 500
    private val maxLogEntries = 200

    val automationsEnabled: Flow<Boolean> =
        context.agentDataStore.data.map { prefs: Preferences -> prefs[Keys.AUTOMATIONS_ENABLED] ?: true }

    suspend fun setAutomationsEnabled(enabled: Boolean) {
        context.agentDataStore.edit { prefs: MutablePreferences -> prefs[Keys.AUTOMATIONS_ENABLED] = enabled }
    }

    val usageEvents: Flow<List<UsageEvent>> = context.agentDataStore.data.map { prefs: Preferences ->
        val raw: String? = prefs[Keys.USAGE_EVENTS]
        if (raw == null) emptyList() else decodeList<UsageEvent>(raw)
    }

    suspend fun recordAppOpened(packageName: String, hourOfDay: Int) {
        context.agentDataStore.edit { prefs: MutablePreferences ->
            val raw: String? = prefs[Keys.USAGE_EVENTS]
            val current: List<UsageEvent> = if (raw == null) emptyList() else decodeList(raw)
            val updated = (current + UsageEvent(packageName, System.currentTimeMillis(), hourOfDay))
                .takeLast(maxUsageEvents)
            prefs[Keys.USAGE_EVENTS] = json.encodeToString(updated)
        }
    }

    val rules: Flow<List<AutomationRule>> = context.agentDataStore.data.map { prefs: Preferences ->
        val raw: String? = prefs[Keys.RULES]
        if (raw == null) emptyList() else decodeList<AutomationRule>(raw)
    }

    suspend fun saveRules(rules: List<AutomationRule>) {
        context.agentDataStore.edit { prefs: MutablePreferences -> prefs[Keys.RULES] = json.encodeToString(rules) }
    }

    val log: Flow<List<AgentLogEntry>> = context.agentDataStore.data.map { prefs: Preferences ->
        val raw: String? = prefs[Keys.LOG]
        if (raw == null) emptyList() else decodeList<AgentLogEntry>(raw)
    }

    suspend fun appendLog(message: String) {
        context.agentDataStore.edit { prefs: MutablePreferences ->
            val raw: String? = prefs[Keys.LOG]
            val current: List<AgentLogEntry> = if (raw == null) emptyList() else decodeList(raw)
            val updated = (current + AgentLogEntry(System.currentTimeMillis(), message)).takeLast(maxLogEntries)
            prefs[Keys.LOG] = json.encodeToString(updated)
        }
    }

    private inline fun <reified T> decodeList(raw: String): List<T> = try {
        json.decodeFromString<List<T>>(raw)
    } catch (e: Exception) {
        emptyList()
    }
}
