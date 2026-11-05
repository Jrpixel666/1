package com.llamaaistudio.app.ui.huggingface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.cloud.CloudModel
import com.llamaaistudio.app.data.db.LocalModelEntity
import com.llamaaistudio.app.data.hf.GgufFileOption
import com.llamaaistudio.app.data.hf.HfModelDetail
import com.llamaaistudio.app.data.hf.HfModelSummary
import com.llamaaistudio.app.data.hf.ggufFiles
import com.llamaaistudio.app.engine.ModelLoadParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val expandedDetail: HfModelDetail? = null,
    val isLoadingDetail: Boolean = false,
    val error: String? = null,
)

data class CloudUiState(
    val query: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val models: List<CloudModel> = emptyList(),
    val error: String? = null,
) {
    val filtered: List<CloudModel>
        get() = if (query.isBlank()) models else models.filter {
            it.name.contains(query, true) || it.id.contains(query, true) || it.description.contains(query, true)
        }
}

class ModelsViewModel(private val app: LlamaAiStudioApp) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState

    val downloads = app.downloadManager.downloads
    val installedModels: StateFlow<List<LocalModelEntity>> =
        app.modelRepository.installedModels.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    val engineState = app.llamaEngine.state

    // --- OpenCode (free cloud models) ------------------------------------------------------

    val openCodeHasKey = app.openCode.hasKey
    val cloudChatModel = app.openCode.chatModel

    private val _cloud = MutableStateFlow(CloudUiState())
    val cloud: StateFlow<CloudUiState> = _cloud

    fun onCloudQueryChange(q: String) {
        _cloud.value = _cloud.value.copy(query = q)
    }

    /** Loads OpenCode's catalogue and keeps only the free, chat-capable models. */
    fun refreshCloudModels(force: Boolean = false) {
        if (_cloud.value.loading) return
        viewModelScope.launch {
            _cloud.value = _cloud.value.copy(loading = true, error = null)
            val result = runCatching { app.openCode.freeModels(force = force) }
            _cloud.value = _cloud.value.copy(
                loading = false,
                models = result.getOrDefault(_cloud.value.models),
                loaded = result.isSuccess || _cloud.value.loaded,
                error = result.exceptionOrNull()?.let { "Couldn't load OpenCode models: ${it.message}" },
            )
        }
    }

    /** Chat (and Web/Dev Studio's AI builders) will use this OpenCode model. */
    fun useCloudModel(model: CloudModel) = app.openCode.selectChatModel(model)

    /** Back to the on-device model for Chat. */
    fun useLocalModel() = app.openCode.selectChatModel(null)

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun search() {
        val q = _uiState.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            try {
                val results = app.hfApi.searchModels(search = q)
                _uiState.value = _uiState.value.copy(isSearching = false, results = results)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearching = false, error = e.message ?: "Search failed")
            }
        }
    }

    fun openDetail(modelId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDetail = true, expandedDetail = null)
            try {
                val detail = app.hfApi.getModelDetail(modelId)
                _uiState.value = _uiState.value.copy(isLoadingDetail = false, expandedDetail = detail)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingDetail = false, error = e.message ?: "Failed to load model details")
            }
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(expandedDetail = null)
    }

    fun filesFor(detail: HfModelDetail): List<GgufFileOption> = detail.ggufFiles()

    fun download(file: GgufFileOption) = app.downloadManager.start(file)
    fun pauseDownload(file: GgufFileOption) = app.downloadManager.pause(file)
    fun resumeDownload(file: GgufFileOption) = app.downloadManager.resume(file)
    fun cancelDownload(file: GgufFileOption) = app.downloadManager.cancel(file)

    fun recommendedContextLength(contextLengthTrained: Int?): Int =
        (contextLengthTrained ?: 4096).coerceAtMost(8192)

    fun fitsInMemory(sizeBytes: Long): Boolean {
        val estimate = app.modelRepository.estimateRamRequirementBytes(sizeBytes)
        return estimate < app.modelRepository.totalMemoryBytes() * 0.7
    }

    fun loadModel(model: LocalModelEntity) {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            val result = app.llamaEngine.loadModel(
                model.filePath,
                settings.loadParams(),
            )
            // Only a successful load becomes "last used" — otherwise a broken file would be
            // retried automatically on every app launch.
            if (result.isSuccess) app.modelRepository.markUsed(model.filePath)
        }
    }

    fun deleteModel(model: LocalModelEntity) {
        viewModelScope.launch {
            if (app.llamaEngine.currentModelPath() == model.filePath) app.llamaEngine.unloadModel()
            if (!app.modelRepository.deleteModel(model)) {
                _uiState.value = _uiState.value.copy(error = "Could not delete ${model.displayName}")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelsViewModel(app) as T
    }
}
