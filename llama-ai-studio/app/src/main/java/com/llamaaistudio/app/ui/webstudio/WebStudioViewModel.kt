package com.llamaaistudio.app.ui.webstudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.WebProjectEntity
import com.llamaaistudio.app.devstudio.TermuxBridge
import com.llamaaistudio.app.devstudio.TermuxWebServer
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class ServerStatus { UNKNOWN, STOPPED, STARTING, RUNNING }

sealed interface PreviewMode {
    /** Opens the project's index.html straight from app storage — no server, no /api routes. */
    data object Offline : PreviewMode
    /** Loads the Termux-served site, exactly as a browser on the phone would see it. */
    data object Live : PreviewMode
}

data class WebStudioUiState(
    val selectedProject: WebProjectEntity? = null,
    val files: List<File> = emptyList(),
    val openFile: File? = null,
    val openFileContent: String = "",
    val dirty: Boolean = false,
    val status: String? = null,
    val isGenerating: Boolean = false,
    val genTokens: Int = 0,
    val genTps: Double = 0.0,
    val server: ServerStatus = ServerStatus.UNKNOWN,
    val preview: PreviewMode? = null,
) {
    val serverUrl: String? get() = selectedProject?.let { "http://127.0.0.1:${it.port}/" }
}

private const val WEB_GEN_MAX_TOKENS = 2048
private const val WEB_GEN_CONTEXT_CHARS = 5000
private const val STARTING_TIMEOUT_MS = 120_000L // first run installs Python/Node

class WebStudioViewModel(private val app: LlamaAiStudioApp) : ViewModel() {
    private val repo = app.webProjectRepository

    val projects: StateFlow<List<WebProjectEntity>> =
        repo.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(WebStudioUiState())
    val uiState: StateFlow<WebStudioUiState> = _uiState

    val termuxInstalled: Boolean get() = TermuxBridge.isTermuxInstalled(app)
    val modelLoaded: Boolean get() = app.llamaEngine.isLoaded

    private val probeClient = app.httpClient.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var pollJob: Job? = null
    private var genJob: Job? = null
    private var startRequestedAt = 0L

    // --- projects ---------------------------------------------------------------------------

    fun createProject(name: String, template: String) {
        viewModelScope.launch {
            openProject(repo.createProject(name, template))
        }
    }

    fun openProject(project: WebProjectEntity) {
        _uiState.value = WebStudioUiState(selectedProject = project)
        viewModelScope.launch {
            repo.touchOpened(project)
            refreshFiles()
        }
        startPolling(project)
    }

    fun closeProject() {
        pollJob?.cancel()
        genJob?.cancel()
        _uiState.value = WebStudioUiState()
    }

    fun deleteProject(project: WebProjectEntity) {
        viewModelScope.launch {
            if (_uiState.value.selectedProject?.id == project.id) closeProject()
            repo.deleteProject(project)
        }
    }

    private suspend fun refreshFiles() {
        val project = _uiState.value.selectedProject ?: return
        val files = repo.listFiles(File(project.rootPath))
        _uiState.update { it.copy(files = files) }
    }

    // --- files ------------------------------------------------------------------------------

    fun openFile(file: File) {
        viewModelScope.launch {
            val content = runCatching { repo.readFile(file) }.getOrElse { "" }
            _uiState.update { it.copy(openFile = file, openFileContent = content, dirty = false) }
        }
    }

    fun closeFile() {
        _uiState.update { it.copy(openFile = null, openFileContent = "", dirty = false) }
    }

    fun updateOpenFileContent(content: String) {
        _uiState.update { it.copy(openFileContent = content, dirty = true) }
    }

    fun saveOpenFile() {
        val state = _uiState.value
        val file = state.openFile ?: return
        viewModelScope.launch {
            repo.writeFile(file, state.openFileContent)
            _uiState.update { it.copy(dirty = false, status = "Saved ${file.name}") }
            syncIfServing(changed = listOf(file))
        }
    }

    fun createFile(relativePath: String) {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            val file = repo.createFile(project, relativePath)
            if (file == null) {
                _uiState.update { it.copy(status = "\"$relativePath\" isn't a valid path inside the project") }
                return@launch
            }
            refreshFiles()
            openFile(file)
        }
    }

    fun deleteFile(file: File) {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            repo.deleteFile(project, file)
            if (_uiState.value.openFile == file) closeFile()
            refreshFiles()
        }
    }

    // --- AI build ---------------------------------------------------------------------------

    fun stopGeneration() {
        genJob?.cancel()
        genJob = null
        _uiState.update { it.copy(isGenerating = false, status = "Generation stopped — no files were changed") }
    }

    fun generateWithAi(idea: String) {
        val project = _uiState.value.selectedProject ?: return
        if (idea.isBlank() || _uiState.value.isGenerating) return
        if (!app.llamaEngine.isLoaded) {
            _uiState.update { it.copy(status = "Load a model in the Models tab first (a coding model works best)") }
            return
        }
        genJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, genTokens = 0, genTps = 0.0, status = null) }
            val target = _uiState.value.openFile ?: repo.mainFile(project)
            val contextFiles = repo.contextFiles(project, target, WEB_GEN_CONTEXT_CHARS)
            val turns = listOf(
                ChatTurn("system", WebCodeGen.systemPrompt(project.template)),
                ChatTurn("user", WebCodeGen.userPrompt(idea, contextFiles)),
            )

            val sb = StringBuilder()
            var failure: String? = null
            var tokens = 0
            val start = System.currentTimeMillis()
            app.llamaEngine.generate(turns, GenerationParams(maxTokens = WEB_GEN_MAX_TOKENS, temperature = 0.2f))
                .collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            sb.append(event.piece)
                            tokens++
                            val secs = (System.currentTimeMillis() - start) / 1000.0
                            _uiState.update { it.copy(genTokens = tokens, genTps = if (secs > 0) tokens / secs else 0.0) }
                        }
                        is GenerationEvent.Failed -> failure = event.reason
                        is GenerationEvent.Done -> {}
                    }
                }

            if (failure != null) {
                _uiState.update { it.copy(isGenerating = false, status = "AI generation failed: $failure") }
                return@launch
            }

            val root = File(project.rootPath)
            val generated = WebCodeGen.parse(sb.toString(), target.relativeTo(root).path)
            if (generated.isEmpty()) {
                _uiState.update { it.copy(isGenerating = false, status = "The model returned no files — try rephrasing the idea") }
                return@launch
            }
            val written = repo.applyGenerated(project, generated)
            refreshFiles()

            // Keep the editor in sync if it shows a file the AI just rewrote.
            val open = _uiState.value.openFile
            if (open != null && written.any { it.canonicalPath == open.canonicalPath }) {
                val text = repo.readFile(open)
                _uiState.update { it.copy(openFileContent = text, dirty = false) }
            }

            val names = written.joinToString(", ") { it.relativeTo(root).path }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    status = "Built ${written.size} file(s): $names ($tokens tokens). " +
                        if (it.server == ServerStatus.RUNNING) "Updating the running server…" else "Tap Start server to test it.",
                )
            }
            syncIfServing(changed = written)
        }
    }

    // --- server -----------------------------------------------------------------------------

    /** Exports the latest files, then has Termux install what's missing and (re)start the server. */
    fun startServer() {
        val project = _uiState.value.selectedProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(status = "Preparing files for Termux…") }
            repo.exportToDownloads(project)
            val ok = TermuxBridge.runCommand(
                app,
                TermuxWebServer.startScript(project.name, project.template, project.port),
                TermuxBridge.TERMUX_HOME,
            )
            if (ok) {
                startRequestedAt = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        server = ServerStatus.STARTING,
                        status = "Starting in Termux. The first run installs " +
                            (if (project.template == "node") "Node.js" else "Python") +
                            " — that can take a minute. The preview opens as soon as it's up.",
                    )
                }
            } else {
                _uiState.update { it.copy(status = termuxUnreachableMessage()) }
            }
        }
    }

    fun stopServer() {
        val project = _uiState.value.selectedProject ?: return
        val ok = TermuxBridge.runCommand(
            app, TermuxWebServer.stopScript(project.name, project.port), TermuxBridge.TERMUX_HOME, background = true,
        )
        _uiState.update {
            it.copy(
                status = if (ok) "Stopping server…" else termuxUnreachableMessage(),
                preview = if (it.preview == PreviewMode.Live) null else it.preview,
            )
        }
    }

    private suspend fun syncIfServing(changed: List<File>) {
        val project = _uiState.value.selectedProject ?: return
        if (_uiState.value.server != ServerStatus.RUNNING) return
        repo.exportToDownloads(project)
        val serverCodeChanged = project.template == "node" &&
            changed.any { it.parentFile?.canonicalPath == File(project.rootPath).canonicalPath && it.extension == "js" }
        if (serverCodeChanged || changed.any { it.name == "package.json" }) {
            startServer() // server code changed: restart so Node picks it up
        } else {
            TermuxBridge.runCommand(
                app, TermuxWebServer.syncScript(project.name, project.port), TermuxBridge.TERMUX_HOME, background = true,
            )
            _uiState.update { it.copy(status = (it.status ?: "") + " Server updated — reload the preview.") }
        }
    }

    private fun termuxUnreachableMessage(): String = when {
        !TermuxBridge.isTermuxInstalled(app) -> "Termux isn't installed — install it from F-Droid first."
        !TermuxBridge.hasRunCommandPermission(app) -> "Allow Llama AI Studio to run commands in Termux, then try again."
        else -> "Termux didn't accept the command. Run the one-time setup command in Termux (Copy setup command), then try again."
    }

    private fun startPolling(project: WebProjectEntity) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val up = probe(project.port)
                _uiState.update { state ->
                    val starting = state.server == ServerStatus.STARTING &&
                        System.currentTimeMillis() - startRequestedAt < STARTING_TIMEOUT_MS
                    val next = when {
                        up -> ServerStatus.RUNNING
                        starting -> ServerStatus.STARTING
                        else -> ServerStatus.STOPPED
                    }
                    // Jump straight into the live preview once a server we just started comes up.
                    val openPreview = next == ServerStatus.RUNNING && state.server == ServerStatus.STARTING
                    val preview = when {
                        openPreview -> PreviewMode.Live
                        next != ServerStatus.RUNNING && state.preview == PreviewMode.Live -> null
                        else -> state.preview
                    }
                    state.copy(
                        server = next,
                        preview = preview,
                        status = if (openPreview) "Server running at ${state.serverUrl}" else state.status,
                    )
                }
                delay(if (_uiState.value.server == ServerStatus.STARTING) 1000 else 2500)
            }
        }
    }

    private suspend fun probe(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url("http://127.0.0.1:$port/").head().build())
                .execute().use { true }
        }.getOrDefault(false)
    }

    fun showPreview(mode: PreviewMode?) {
        _uiState.update { it.copy(preview = mode) }
    }

    fun clearStatus() {
        _uiState.update { it.copy(status = null) }
    }

    fun setStatus(message: String) {
        _uiState.update { it.copy(status = message) }
    }

    fun offlinePreviewUrl(): String? {
        val project = _uiState.value.selectedProject ?: return null
        val index = File(repo.previewRoot(project), "index.html")
        return if (index.exists()) "file://${index.absolutePath}" else null
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WebStudioViewModel(app) as T
    }
}
