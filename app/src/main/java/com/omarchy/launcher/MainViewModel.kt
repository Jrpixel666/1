package com.omarchy.launcher

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omarchy.launcher.agent.CommandResult
import com.omarchy.launcher.data.AgentLogEntry
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.AutomationRule
import com.omarchy.launcher.agent.AppRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

data class LauncherUiState(
    val allApps: List<AppInfo> = emptyList(),
    val favoriteApps: List<AppInfo> = emptyList(),
    val rankedApps: List<AppInfo> = emptyList(),
    val automationsEnabled: Boolean = true,
    val rules: List<AutomationRule> = emptyList(),
    val agentLog: List<AgentLogEntry> = emptyList(),
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _lastCommandFeedback = MutableStateFlow<String?>(null)
    val lastCommandFeedback: StateFlow<String?> = _lastCommandFeedback.asStateFlow()

    init {
        refreshApps()
        viewModelScope.launch {
            combine(
                appsFlow,
                container.agentPreferences.usageEvents,
                container.agentPreferences.automationsEnabled,
                container.agentPreferences.rules,
                container.agentPreferences.log,
            ) { apps, usage, automationsEnabled, rules, log ->
                val hour = currentHour()
                container.syncTimeOfDayAlarms(rules, automationsEnabled)
                LauncherUiState(
                    allApps = apps,
                    favoriteApps = AppRanker.favorites(apps, usage, hour),
                    rankedApps = AppRanker.rank(apps, usage, hour),
                    automationsEnabled = automationsEnabled,
                    rules = rules,
                    agentLog = log.asReversed(),
                )
            }.collect { _uiState.value = it }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            appsFlow.value = container.appRepository.loadLaunchableApps()
        }
    }

    fun onSearchQueryChanged(query: String, context: Context) {
        _searchQuery.value = query
        val result = container.commandParser.parse(context, query, _uiState.value.allApps)
        when (result) {
            is CommandResult.LaunchedApp -> onAppLaunched(result.app, context)
            is CommandResult.PerformedAction -> _lastCommandFeedback.value = result.description
            is CommandResult.FilterApps -> _lastCommandFeedback.value = null
            CommandResult.NoMatch -> _lastCommandFeedback.value = null
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _lastCommandFeedback.value = null
    }

    fun onAppLaunched(app: AppInfo, context: Context) {
        container.appRepository.launch(context, app)
        viewModelScope.launch {
            container.agentPreferences.recordAppOpened(app.packageName, currentHour())
            container.automationEngine.onAppOpened(app.packageName)
        }
    }

    fun setAutomationsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.agentPreferences.setAutomationsEnabled(enabled) }
    }

    fun addRule(rule: AutomationRule) {
        viewModelScope.launch {
            container.agentPreferences.saveRules(_uiState.value.rules + rule)
            container.agentPreferences.appendLog("rule \"${rule.name}\" created")
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            container.agentPreferences.saveRules(_uiState.value.rules.filterNot { it.id == ruleId })
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = _uiState.value.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
            container.agentPreferences.saveRules(updated)
        }
    }

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
