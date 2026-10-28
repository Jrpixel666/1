package com.omarchy.launcher.ui.nativewidgets

import android.appwidget.AppWidgetProviderInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omarchy.launcher.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NativeWidgetsUiState(
    val activeIds: List<Int> = emptyList(),
    val providers: List<AppWidgetProviderInfo> = emptyList(),
)

class NativeWidgetsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(NativeWidgetsUiState())
    val uiState: StateFlow<NativeWidgetsUiState> = _uiState.asStateFlow()

    init {
        refreshProviders()
        viewModelScope.launch {
            container.nativeWidgetPreferences.activeWidgetIds.collect { ids ->
                _uiState.value = _uiState.value.copy(activeIds = ids)
            }
        }
    }

    fun refreshProviders() {
        _uiState.value = _uiState.value.copy(providers = container.nativeWidgetHostManager.installedHomeScreenProviders())
    }

    fun getAppWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? =
        container.nativeWidgetHostManager.getAppWidgetInfo(appWidgetId)

    fun remove(appWidgetId: Int) {
        viewModelScope.launch {
            container.nativeWidgetHostManager.deleteAppWidgetId(appWidgetId)
            val updated = _uiState.value.activeIds.filterNot { it == appWidgetId }
            container.nativeWidgetPreferences.setActiveWidgetIds(updated)
        }
    }

    fun moveUp(appWidgetId: Int) = reorder(appWidgetId, -1)
    fun moveDown(appWidgetId: Int) = reorder(appWidgetId, 1)

    private fun reorder(appWidgetId: Int, delta: Int) {
        viewModelScope.launch {
            val current = _uiState.value.activeIds.toMutableList()
            val index = current.indexOf(appWidgetId)
            val target = index + delta
            if (index < 0 || target < 0 || target >= current.size) return@launch
            val item = current.removeAt(index)
            current.add(target, item)
            container.nativeWidgetPreferences.setActiveWidgetIds(current)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeWidgetsViewModel(container) as T
        }
    }
}
