package com.llamaaistudio.app.ui.huggingface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.cloud.CloudModel
import com.llamaaistudio.app.data.hf.DeviceFit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import com.llamaaistudio.app.data.db.LocalModelEntity
import com.llamaaistudio.app.data.hf.GgufFileOption
import com.llamaaistudio.app.data.hf.HfModelDetail
import com.llamaaistudio.app.data.hf.HfModelSummary
import com.llamaaistudio.app.data.hf.ggufFiles
import com.llamaaistudio.app.data.hf.litertFiles
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

/** "Runs on your CPU + GPU" search results. */
data class DeviceFitUiState(
    val searching: Boolean = false,
    val progress: String? = null,
    val budget: DeviceFit.Budget? = null,
    val gpuName: String? = null,
    val results: List<DeviceFitResult> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

data class DeviceFitResult(val modelId: String, val downloads: Long, val pick: DeviceFit.Pick)

data class LiteRtSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
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
    val engineState = app.localEngineState

    // --- "Find models for CPU + GPU" ---------------------------------------------------------

    private val _fit = MutableStateFlow(DeviceFitUiState())
    val fit: StateFlow<DeviceFitUiState> = _fit
    private var fitJob: kotlinx.coroutines.Job? = null

    /**
     * Searches the most-downloaded GGUF chat models (matching the search box, if filled) and
     * keeps the ones that fit this phone, choosing for each the best file for GPU + CPU.
     */
    fun findModelsForCpuGpu() {
        fitJob?.cancel()
        fitJob = viewModelScope.launch {
            _fit.value = DeviceFitUiState(searching = true, progress = "Checking your phone…")
            val settings = app.settingsRepository.settings.first()
            val gpu = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.llamaaistudio.app.engine.LlamaEngine.devices().firstOrNull { it.isGpu }
            }
            val total = app.modelRepository.totalMemoryBytes()
            val avail = app.modelRepository.availableMemoryBytes()
            // Leave room for Android and other apps; the GPU shares the same RAM.
            val ram = minOf((total * 0.6).toLong(), (avail * 0.85).toLong())
            val gpuBytes = when {
                gpu == null -> 0L
                gpu.memoryFree > 0 -> minOf(gpu.memoryFree - 768L * 1024 * 1024, ram).coerceAtLeast(0)
                else -> (ram * 0.75).toLong()
            }
            val budget = DeviceFit.Budget(ram, gpuBytes, settings.contextLength.coerceAtMost(8192))
            _fit.value = _fit.value.copy(budget = budget, gpuName = gpu?.description?.ifBlank { gpu.name }, progress = "Searching Hugging Face…")

            val query = _uiState.value.query.trim().ifEmpty { null }
            val listed = runCatching { app.hfApi.listPopularGguf(search = query) }.getOrElse {
                _fit.value = _fit.value.copy(searching = false, progress = null, error = "Search failed: ${it.message}")
                return@launch
            }
            val semaphore = kotlinx.coroutines.sync.Semaphore(6)
            var checked = 0
            val results = kotlinx.coroutines.coroutineScope {
                listed.map { summary ->
                    async {
                        semaphore.withPermit {
                            val detail = runCatching { app.hfApi.getModelDetail(summary.id) }.getOrNull()
                            checked++
                            _fit.value = _fit.value.copy(progress = "Checking models $checked/${listed.size}…")
                            detail?.let { d -> DeviceFit.pick(d.ggufFiles(), budget)?.let { DeviceFitResult(summary.id, summary.downloads ?: 0, it) } }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val sorted = results.sortedWith(
                compareBy<DeviceFitResult> { it.pick.placement.ordinal }.thenByDescending { it.downloads }
            ).take(25)
            _fit.value = _fit.value.copy(searching = false, progress = null, results = sorted, searched = true)
        }
    }

    /** Downloads a recommended file and sets Chat to run on GPU + CPU (auto-fit) when a GPU exists. */
    fun downloadForCpuGpu(result: DeviceFitResult) {
        download(result.pick.file)
        if (result.pick.placement != DeviceFit.Placement.CPU_ONLY) {
            viewModelScope.launch {
                app.settingsRepository.setComputeMode(com.llamaaistudio.app.data.datastore.ComputeMode.GPU_AND_CPU)
                app.settingsRepository.setGpuLayers(com.llamaaistudio.app.data.datastore.AUTO_GPU_LAYERS)
            }
        }
    }

    fun clearFitResults() {
        fitJob?.cancel()
        _fit.value = DeviceFitUiState()
    }

    // --- OpenCode (free cloud models) ------------------------------------------------------

    val openCodeHasKey = app.openCode.ready
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
            val result = runCatching { app.openCode.models(force = force) }
            _cloud.value = _cloud.value.copy(
                loading = false,
                models = result.getOrDefault(_cloud.value.models),
                loaded = result.isSuccess || _cloud.value.loaded,
                error = result.exceptionOrNull()?.let { "Couldn't load ${app.openCode.providerName} models: ${it.message}" },
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

    fun filesFor(detail: HfModelDetail): List<GgufFileOption> = detail.ggufFiles() + detail.litertFiles()

    // --- LiteRT-LM model search ---------------------------------------------------------------

    private val _liteRt = MutableStateFlow(LiteRtSearchState())
    val liteRt: StateFlow<LiteRtSearchState> = _liteRt

    fun onLiteRtQueryChange(q: String) {
        _liteRt.value = _liteRt.value.copy(query = q)
    }

    fun searchLiteRt() {
        viewModelScope.launch {
            _liteRt.value = _liteRt.value.copy(searching = true, error = null)
            val r = runCatching { app.hfApi.searchLiteRt(search = _liteRt.value.query.trim().ifEmpty { null }) }
            _liteRt.value = _liteRt.value.copy(
                searching = false,
                searched = true,
                results = r.getOrDefault(emptyList()),
                error = r.exceptionOrNull()?.let { "LiteRT search failed: ${it.message}" },
            )
        }
    }

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
        viewModelScope.launch { app.loadLocalModel(model.filePath) }
    }

    fun deleteModel(model: LocalModelEntity) {
        viewModelScope.launch {
            if (app.llamaEngine.currentModelPath() == model.filePath) app.llamaEngine.unloadModel()
            if (app.liteRtEngine.loaded?.path == model.filePath) app.liteRtEngine.unload()
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
