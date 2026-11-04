package com.llamaaistudio.app.ui.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.GeneratedImageEntity
import com.llamaaistudio.app.data.db.LocalImageModelEntity
import com.llamaaistudio.app.data.hf.GgufFileOption
import com.llamaaistudio.app.data.hf.HfModelDetail
import com.llamaaistudio.app.data.hf.HfModelSummary
import com.llamaaistudio.app.data.hf.ggufFiles
import com.llamaaistudio.app.ui.chat.MutableStateFlowHolder
import com.llamaaistudio.imagegen.ImageEngineState
import com.llamaaistudio.imagegen.ImageGenEvent
import com.llamaaistudio.imagegen.ImageGenParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ImageGenUiState(
    val isGenerating: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val lastImage: GeneratedImageEntity? = null,
    val error: String? = null,
)

data class ImageModelsUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val expandedDetail: HfModelDetail? = null,
    val error: String? = null,
)

class ImageGenViewModel(private val app: LlamaAiStudioApp) : ViewModel() {

    private val engine = app.imageGenEngine

    val engineState: StateFlow<ImageEngineState> = engine.state

    val installedModels: StateFlow<List<LocalImageModelEntity>> =
        app.imageModelRepository.installedModels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gallery: StateFlow<List<GeneratedImageEntity>> =
        app.imageGalleryRepository.images.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads = app.imageDownloadManager.downloads

    val genParams = MutableStateFlowHolder(ImageGenParams())

    init {
        viewModelScope.launch {
            val restored = app.settingsRepository.settings.first().imageParams
            genParams.update { restored }
            // Persist every later change so slider positions survive the app being closed and
            // reopened. Started only after the restore: collecting in a separate coroutine (as
            // before) wrote the *default* params to DataStore before the saved ones were read
            // back, so the user's settings were frequently reset on launch.
            genParams.flow.drop(1).collect { params -> app.settingsRepository.setImageParams(params) }
        }
    }

    private val _uiState = MutableStateFlow(ImageGenUiState())
    val uiState: StateFlow<ImageGenUiState> = _uiState

    private val _modelsUiState = MutableStateFlow(ImageModelsUiState())
    val modelsUiState: StateFlow<ImageModelsUiState> = _modelsUiState

    private val imagesDir: File = File(app.filesDir, "generated_images").apply { mkdirs() }

    fun loadModel(model: LocalImageModelEntity) {
        viewModelScope.launch {
            val result = engine.loadModel(model.filePath)
            if (result.isSuccess) {
                app.imageModelRepository.markUsed(model.filePath)
            } else {
                _uiState.value = _uiState.value.copy(error = result.exceptionOrNull()?.message)
            }
        }
    }

    fun deleteModel(model: LocalImageModelEntity) {
        viewModelScope.launch {
            if ((engineState.value as? ImageEngineState.Ready)?.modelPath == model.filePath) {
                if (_uiState.value.isGenerating) stopGeneration()
                engine.unloadModel()
            }
            if (!app.imageModelRepository.deleteModel(model)) {
                _uiState.value = _uiState.value.copy(error = "Could not delete ${model.displayName}")
            }
        }
    }

    private var generationJob: kotlinx.coroutines.Job? = null

    fun stopGeneration() {
        generationJob?.cancel()
        _uiState.value = _uiState.value.copy(isGenerating = false)
    }

    fun generate(prompt: String, negativePrompt: String) {
        if (_uiState.value.isGenerating || prompt.isBlank()) return
        if (engineState.value !is ImageEngineState.Ready) {
            _uiState.value = _uiState.value.copy(error = "Load a diffusion model first")
            return
        }
        val modelPath = (engineState.value as? ImageEngineState.Ready)?.modelPath
        generationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, currentStep = 0, totalSteps = 0, error = null)
            val outputFile = File(imagesDir, "img_${System.currentTimeMillis()}.png")
            val params = genParams.value
            engine.generate(prompt, negativePrompt, outputFile, params).collect { event ->
                when (event) {
                    is ImageGenEvent.Progress -> {
                        _uiState.value = _uiState.value.copy(currentStep = event.step, totalSteps = event.steps)
                    }
                    is ImageGenEvent.Done -> {
                        val entity = GeneratedImageEntity(
                            filePath = event.outputFile.absolutePath,
                            prompt = prompt,
                            negativePrompt = negativePrompt,
                            width = event.stats.widthOut,
                            height = event.stats.heightOut,
                            steps = params.steps,
                            cfgScale = params.cfgScale,
                            seed = event.stats.seedUsed,
                            modelFilePath = modelPath,
                            genMs = event.stats.genMs,
                        )
                        val id = app.imageGalleryRepository.record(entity)
                        _uiState.value = _uiState.value.copy(isGenerating = false, lastImage = entity.copy(id = id))
                    }
                    is ImageGenEvent.Failed -> {
                        _uiState.value = _uiState.value.copy(isGenerating = false, error = event.reason)
                    }
                }
            }
        }
    }

    fun deleteFromGallery(image: GeneratedImageEntity) {
        viewModelScope.launch { app.imageGalleryRepository.delete(image) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // --- model discovery (mirrors ModelsViewModel, scoped to this workspace) ---

    fun onQueryChange(query: String) {
        _modelsUiState.value = _modelsUiState.value.copy(query = query)
    }

    fun search() {
        val q = _modelsUiState.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _modelsUiState.value = _modelsUiState.value.copy(isSearching = true, error = null)
            try {
                val results = app.hfApi.searchModels(search = q)
                _modelsUiState.value = _modelsUiState.value.copy(isSearching = false, results = results)
            } catch (e: Exception) {
                _modelsUiState.value = _modelsUiState.value.copy(isSearching = false, error = e.message ?: "Search failed")
            }
        }
    }

    fun openDetail(modelId: String) {
        viewModelScope.launch {
            try {
                val detail = app.hfApi.getModelDetail(modelId)
                _modelsUiState.value = _modelsUiState.value.copy(expandedDetail = detail)
            } catch (e: Exception) {
                _modelsUiState.value = _modelsUiState.value.copy(error = e.message ?: "Failed to load model details")
            }
        }
    }

    fun closeDetail() {
        _modelsUiState.value = _modelsUiState.value.copy(expandedDetail = null)
    }

    fun filesFor(detail: HfModelDetail): List<GgufFileOption> = detail.ggufFiles()

    fun download(file: GgufFileOption) = app.imageDownloadManager.start(file)
    fun pauseDownload(file: GgufFileOption) = app.imageDownloadManager.pause(file)
    fun resumeDownload(file: GgufFileOption) = app.imageDownloadManager.resume(file)
    fun cancelDownload(file: GgufFileOption) = app.imageDownloadManager.cancel(file)

    fun fitsInMemory(sizeBytes: Long): Boolean {
        val estimate = app.imageModelRepository.estimateRamRequirementBytes(sizeBytes)
        return estimate < app.modelRepository.totalMemoryBytes() * 0.7
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImageGenViewModel(app) as T
    }
}
