package com.llamaaistudio.app.ui.devstudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.DevProjectEntity
import com.llamaaistudio.app.devstudio.TermuxBridge
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DevStudioUiState(
    val selectedProject: DevProjectEntity? = null,
    val files: List<File> = emptyList(),
    val openFile: File? = null,
    val openFileContent: String = "",
    val dirty: Boolean = false,
    val status: String? = null,
    val isGeneratingCode: Boolean = false,
    val codeGenTokens: Int = 0,
    val codeGenTps: Double = 0.0,
    val codeGenEtaSeconds: Double? = null,
)

private const val CODE_GEN_MAX_TOKENS = 1536

class DevStudioViewModel(private val app: LlamaAiStudioApp) : ViewModel() {
    private val repo = app.devProjectRepository

    val projects: StateFlow<List<DevProjectEntity>> =
        repo.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(DevStudioUiState())
    val uiState: StateFlow<DevStudioUiState> = _uiState

    val termuxInstalled: Boolean get() = TermuxBridge.isTermuxInstalled(app)
    val toolchainComponents = TermuxBridge.components

    fun createProject(name: String, template: String) {
        viewModelScope.launch {
            val project = repo.createProject(name, template)
            openProject(project)
        }
    }

    fun openProject(project: DevProjectEntity) {
        viewModelScope.launch { repo.touchOpened(project) }
        val files = repo.listFiles(File(project.rootPath))
        _uiState.value = DevStudioUiState(selectedProject = project, files = files)
    }

    fun closeProject() {
        _uiState.value = DevStudioUiState()
    }

    fun closeFile() {
        _uiState.value = _uiState.value.copy(openFile = null, openFileContent = "", dirty = false)
    }

    fun deleteProject(project: DevProjectEntity) {
        viewModelScope.launch {
            repo.deleteProject(project)
            if (_uiState.value.selectedProject?.id == project.id) closeProject()
        }
    }

    fun openFile(file: File) {
        viewModelScope.launch {
            val content = repo.readFile(file)
            _uiState.value = _uiState.value.copy(openFile = file, openFileContent = content, dirty = false)
        }
    }

    fun updateOpenFileContent(content: String) {
        _uiState.value = _uiState.value.copy(openFileContent = content, dirty = true)
    }

    fun saveOpenFile() {
        val file = _uiState.value.openFile ?: return
        viewModelScope.launch {
            repo.writeFile(file, _uiState.value.openFileContent)
            _uiState.value = _uiState.value.copy(dirty = false, status = "Saved ${file.name}")
        }
    }

    fun exportToDownloads() {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            val dest = repo.exportToDownloads(project)
            _uiState.value = _uiState.value.copy(status = "Exported to $dest")
        }
    }

    fun buildViaTermux() {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            repo.exportToDownloads(project)
            // RUN_COMMAND_WORKDIR is handed to the process as-is — "~" is not expanded, so it
            // must be absolute. ~/storage/downloads is what `termux-setup-storage` creates.
            val termuxPath =
                "/data/data/com.termux/files/home/storage/downloads/LlamaAiStudioProjects/${project.name}"
            val ok = TermuxBridge.runCommand(
                app,
                // Scaffolded projects ship no Gradle wrapper; fall back to Termux's `gradle`.
                "if [ -f ./gradlew ]; then sh ./gradlew assembleDebug; else gradle assembleDebug; fi " +
                    "2>&1 | tee build_output.log",
                termuxPath,
            )
            _uiState.value = _uiState.value.copy(
                status = if (ok) {
                    "Sent to Termux. Run `termux-setup-storage` once if you haven't, then check " +
                        "~/storage/downloads/LlamaAiStudioProjects/${project.name}/build_output.log for progress."
                } else {
                    "Could not reach Termux. Install it and enable allow-external-apps in " +
                        "~/.termux/termux.properties, then try again."
                },
            )
        }
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(status = null)
    }

    /**
     * Rewrites the open file (or the project's main source file, if none is open) to implement
     * [idea], using whatever model is currently loaded in the Chat/Models workspace. Feeds the
     * file's current content back in so repeated ideas refine the same file instead of starting
     * over each time. The result lands in the editor for review before building — this never
     * builds or runs anything on its own.
     */
    private var codeGenJob: kotlinx.coroutines.Job? = null

    fun stopCodeGeneration() {
        codeGenJob?.cancel()
        _uiState.value = _uiState.value.copy(isGeneratingCode = false, status = "Cancelled")
    }

    fun generateWithAi(idea: String) {
        val project = _uiState.value.selectedProject ?: return
        if (idea.isBlank() || _uiState.value.isGeneratingCode) return
        if (!app.llamaEngine.isLoaded) {
            _uiState.value = _uiState.value.copy(status = "Load a model in the Chat or Models tab first")
            return
        }
        codeGenJob = viewModelScope.launch {
            val targetFile = _uiState.value.openFile ?: findMainSourceFile(project)
            if (targetFile == null) {
                _uiState.value = _uiState.value.copy(status = "No source file found in this project to write into")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isGeneratingCode = true,
                status = null,
                codeGenTokens = 0,
                codeGenTps = 0.0,
                codeGenEtaSeconds = null,
            )

            val existingContent = runCatching { repo.readFile(targetFile) }.getOrDefault("")
            val turns = listOf(
                ChatTurn(
                    "system",
                    "You are an expert Kotlin/Android developer. The user describes a feature or " +
                        "change. Rewrite the ENTIRE file below to implement it, keeping it " +
                        "compilable. Output ONLY the complete raw source code for the file — no " +
                        "explanations, no markdown code fences, no commentary before or after.",
                ),
                ChatTurn(
                    "user",
                    "Current contents of ${targetFile.name}:\n\n$existingContent\n\n" +
                        "Requested change:\n$idea",
                ),
            )

            val sb = StringBuilder()
            var failure: String? = null
            var tokenCount = 0
            val startTime = System.currentTimeMillis()
            app.llamaEngine.generate(turns, GenerationParams(maxTokens = CODE_GEN_MAX_TOKENS, temperature = 0.2f))
                .collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            sb.append(event.piece)
                            tokenCount++
                            val elapsedS = (System.currentTimeMillis() - startTime) / 1000.0
                            val tps = if (elapsedS > 0) tokenCount / elapsedS else 0.0
                            // Worst-case ETA assuming it runs to the token cap — the model will
                            // very often stop earlier (it decides when the file is complete), so
                            // this is a ceiling, not a prediction, and the UI labels it as such.
                            val remaining = (CODE_GEN_MAX_TOKENS - tokenCount).coerceAtLeast(0)
                            val eta = if (tps > 0) remaining / tps else null
                            _uiState.value = _uiState.value.copy(
                                codeGenTokens = tokenCount,
                                codeGenTps = tps,
                                codeGenEtaSeconds = eta,
                            )
                        }
                        is GenerationEvent.Failed -> failure = event.reason
                        is GenerationEvent.Done -> {}
                    }
                }

            if (failure != null) {
                _uiState.value = _uiState.value.copy(isGeneratingCode = false, status = "AI generation failed: $failure")
                return@launch
            }

            val cleaned = stripMarkdownFences(sb.toString())
            repo.writeFile(targetFile, cleaned)
            _uiState.value = _uiState.value.copy(
                isGeneratingCode = false,
                openFile = targetFile,
                openFileContent = cleaned,
                dirty = false,
                status = "AI updated ${targetFile.name} ($tokenCount tokens) — review it, then build via Termux when ready",
            )
        }
    }

    private fun findMainSourceFile(project: DevProjectEntity): File? =
        repo.listFiles(File(project.rootPath)).firstOrNull { it.name == "MainActivity.kt" || it.name == "Main.kt" }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        val fenced = Regex("^```[a-zA-Z0-9]*\\n([\\s\\S]*?)\\n?```$").find(trimmed)
        return fenced?.groupValues?.get(1) ?: trimmed
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DevStudioViewModel(app) as T
    }
}
