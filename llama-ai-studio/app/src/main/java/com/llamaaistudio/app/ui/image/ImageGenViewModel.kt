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
    /** e.g. "Asking Nemotron how to edit…" while the assistant works. */
    val assistantStatus: String? = null,
)

/** A prompt pair written by the AI assistant. */
data class PromptSuggestion(val prompt: String, val negative: String)

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

    // --- AI assistant (OpenCode vision model) + image editing --------------------------------

    val openCodeHasKey = app.openCode.ready
    val visionModel = app.openCode.visionModel

    private val _cloud = MutableStateFlow(com.llamaaistudio.app.ui.huggingface.CloudUiState())
    val cloud: StateFlow<com.llamaaistudio.app.ui.huggingface.CloudUiState> = _cloud

    fun onCloudQueryChange(q: String) {
        _cloud.value = _cloud.value.copy(query = q)
    }

    /** Free OpenCode models that accept images (none of them generate images). */
    fun refreshVisionModels(force: Boolean = false) {
        if (_cloud.value.loading) return
        viewModelScope.launch {
            _cloud.value = _cloud.value.copy(loading = true, error = null)
            val result = runCatching { app.openCode.freeModels(force = force, imagesOnly = true) }
            _cloud.value = _cloud.value.copy(
                loading = false,
                loaded = result.isSuccess || _cloud.value.loaded,
                models = result.getOrDefault(_cloud.value.models),
                error = result.exceptionOrNull()?.let { "Couldn't load ${app.openCode.providerName} models: ${it.message}" },
            )
        }
    }

    fun useVisionModel(model: com.llamaaistudio.app.data.cloud.CloudModel?) = app.openCode.selectVisionModel(model)

    /** Can the AI help write prompts? (vision assistant, or any chat model) */
    val assistantAvailable: Boolean
        get() = (app.openCode.ready.value && app.openCode.visionModel.value != null) || app.ai.isAvailable

    /** Rewrites a rough idea into a detailed Stable Diffusion prompt + negative prompt. */
    fun improvePrompt(idea: String, onResult: (PromptSuggestion) -> Unit) {
        if (idea.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(assistantStatus = "Improving the prompt…", error = null)
            val messages = listOf(
                com.llamaaistudio.app.engine.ChatTurn("system", PROMPT_SYSTEM),
                com.llamaaistudio.app.engine.ChatTurn("user", "Idea: ${idea.trim()}"),
            )
            val reply = askAssistant(messages, image = null)
            _uiState.value = _uiState.value.copy(assistantStatus = null)
            reply.onSuccess { onResult(parseSuggestion(it, fallbackPrompt = idea)) }
                .onFailure { _uiState.value = _uiState.value.copy(error = "AI couldn't improve the prompt: ${it.message}") }
        }
    }

    /**
     * Edits [image]: the vision assistant (if picked) looks at it and turns [instruction] into a
     * new prompt; then the on-device diffusion model redraws it from the original picture
     * (img2img), changing it by [strength] (0 = keep, 1 = redraw freely).
     */
    fun editImage(image: GeneratedImageEntity, instruction: String, strength: Float) {
        if (_uiState.value.isGenerating || instruction.isBlank()) return
        if (engineState.value !is ImageEngineState.Ready) {
            _uiState.value = _uiState.value.copy(error = "Load a diffusion model first (Models tab)")
            return
        }
        val modelPath = (engineState.value as? ImageEngineState.Ready)?.modelPath
        generationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, currentStep = 0, totalSteps = 0, error = null)
            val w = ((image.width / 64) * 64).coerceIn(256, 1024)
            val h = ((image.height / 64) * 64).coerceIn(256, 1024)
            val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImageCodec.prepare(File(image.filePath), w, h) }
            if (prepared == null) {
                _uiState.value = _uiState.value.copy(isGenerating = false, error = "Couldn't read that image")
                return@launch
            }

            val vision = app.openCode.visionModel.value?.takeIf { app.openCode.ready.value }
            val suggestion = if (vision != null) {
                _uiState.value = _uiState.value.copy(assistantStatus = "Asking ${vision.name} how to edit it…")
                val messages = listOf(
                    com.llamaaistudio.app.engine.ChatTurn("system", EDIT_SYSTEM),
                    com.llamaaistudio.app.engine.ChatTurn(
                        "user",
                        "Original prompt: ${image.prompt}\nRequested edit: ${instruction.trim()}",
                    ),
                )
                app.openCode.complete(vision.id, messages, imageJpeg = prepared.jpegForAi)
                    .map { parseSuggestion(it, fallbackPrompt = "${image.prompt}, ${instruction.trim()}") }
                    .getOrElse {
                        _uiState.value = _uiState.value.copy(error = "Assistant failed (${it.message}) — editing with your words instead")
                        PromptSuggestion("${image.prompt}, ${instruction.trim()}", image.negativePrompt)
                    }
            } else {
                PromptSuggestion("${image.prompt}, ${instruction.trim()}", image.negativePrompt)
            }
            _uiState.value = _uiState.value.copy(assistantStatus = "Redrawing on-device: ${suggestion.prompt.take(80)}")

            val params = genParams.value.copy(width = w, height = h, seed = image.seed)
            val outputFile = File(imagesDir, "img_${System.currentTimeMillis()}_edit.png")
            engine.generate(suggestion.prompt, suggestion.negative, outputFile, params, prepared.rgb, strength)
                .collect { event ->
                    when (event) {
                        is ImageGenEvent.Progress ->
                            _uiState.value = _uiState.value.copy(currentStep = event.step, totalSteps = event.steps)
                        is ImageGenEvent.Done -> {
                            val entity = GeneratedImageEntity(
                                filePath = event.outputFile.absolutePath,
                                prompt = suggestion.prompt,
                                negativePrompt = suggestion.negative,
                                width = event.stats.widthOut,
                                height = event.stats.heightOut,
                                steps = params.steps,
                                cfgScale = params.cfgScale,
                                seed = event.stats.seedUsed,
                                modelFilePath = modelPath,
                                genMs = event.stats.genMs,
                            )
                            val id = app.imageGalleryRepository.record(entity)
                            _uiState.value = _uiState.value.copy(isGenerating = false, assistantStatus = null, lastImage = entity.copy(id = id))
                        }
                        is ImageGenEvent.Failed ->
                            _uiState.value = _uiState.value.copy(isGenerating = false, assistantStatus = null, error = event.reason)
                    }
                }
        }
    }

    private suspend fun askAssistant(messages: List<com.llamaaistudio.app.engine.ChatTurn>, image: ByteArray?): Result<String> {
        val vision = app.openCode.visionModel.value?.takeIf { app.openCode.ready.value }
        if (vision != null) return app.openCode.complete(vision.id, messages, imageJpeg = image)
        if (!app.ai.isAvailable) return Result.failure(IllegalStateException("pick an Image assistant or a chat model first"))
        val sb = StringBuilder()
        var failure: String? = null
        app.ai.generate(messages, com.llamaaistudio.app.engine.GenerationParams(maxTokens = 400, temperature = 0.4f)).collect { e ->
            when (e) {
                is com.llamaaistudio.app.engine.GenerationEvent.Token -> sb.append(e.piece)
                is com.llamaaistudio.app.engine.GenerationEvent.Failed -> failure = e.reason
                is com.llamaaistudio.app.engine.GenerationEvent.Done -> {}
            }
        }
        return failure?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(sb.toString())
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

    companion object {
        private const val PROMPT_SYSTEM =
            "You write prompts for Stable Diffusion image models. Turn the user's idea into one " +
                "detailed, comma-separated prompt (subject, style, lighting, composition, quality tags) " +
                "and a short negative prompt. Reply with JSON only: {\"prompt\": \"...\", \"negative\": \"...\"}"

        private const val EDIT_SYSTEM =
            "You help edit images made with Stable Diffusion. You see the current image, the prompt " +
                "that produced it, and the user's requested edit. Write the new prompt that describes the " +
                "edited image (keep what should stay, change what was asked) and a short negative prompt. " +
                "Reply with JSON only: {\"prompt\": \"...\", \"negative\": \"...\"}"

        /** Reads {"prompt","negative"} out of a model reply (tolerates code fences / chatter). */
        fun parseSuggestion(reply: String, fallbackPrompt: String): PromptSuggestion {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching {
                    val o = org.json.JSONObject(reply.substring(start, end + 1))
                    val p = o.optString("prompt").trim()
                    if (p.isNotEmpty()) return PromptSuggestion(p, o.optString("negative").trim())
                }
            }
            val text = reply.trim().removeSurrounding("```").trim()
            return PromptSuggestion(text.ifBlank { fallbackPrompt }.take(1000), "")
        }
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImageGenViewModel(app) as T
    }
}
