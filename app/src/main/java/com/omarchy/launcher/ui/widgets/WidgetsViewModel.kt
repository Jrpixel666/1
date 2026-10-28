package com.omarchy.launcher.ui.widgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omarchy.launcher.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class WidgetsUiState(
    val allFiles: List<String> = emptyList(),
    val activeFiles: List<String> = emptyList(),
)

class WidgetsViewModel(private val container: AppContainer) : ViewModel() {

    private val allFilesFlow = MutableStateFlow<List<String>>(emptyList())
    private val _uiState = MutableStateFlow(WidgetsUiState())
    val uiState: StateFlow<WidgetsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            combine(allFilesFlow, container.widgetPreferences.activeWidgetFiles) { all, active ->
                WidgetsUiState(allFiles = all, activeFiles = active)
            }.collect { _uiState.value = it }
        }
    }

    fun refresh() {
        allFilesFlow.value = container.widgetRepository.listWidgetFiles()
    }

    fun setActive(fileName: String, active: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.activeFiles
            val updated = if (active) current + fileName else current.filterNot { it == fileName }
            container.widgetPreferences.setActiveWidgetFiles(updated)
        }
    }

    fun moveUp(fileName: String) = reorder(fileName, -1)
    fun moveDown(fileName: String) = reorder(fileName, 1)

    private fun reorder(fileName: String, delta: Int) {
        viewModelScope.launch {
            val current = _uiState.value.activeFiles.toMutableList()
            val index = current.indexOf(fileName)
            val target = index + delta
            if (index < 0 || target < 0 || target >= current.size) return@launch
            val item = current.removeAt(index)
            current.add(target, item)
            container.widgetPreferences.setActiveWidgetFiles(current)
        }
    }

    fun createNew(name: String): String {
        val fileName = container.widgetRepository.createBlank(name)
        refresh()
        return fileName
    }

    fun delete(fileName: String) {
        container.widgetRepository.delete(fileName)
        setActive(fileName, active = false)
        refresh()
    }

    fun readContent(fileName: String): String = container.widgetRepository.read(fileName)

    fun saveContent(fileName: String, content: String) {
        container.widgetRepository.write(fileName, content)
        refresh()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WidgetsViewModel(container) as T
        }
    }
}
