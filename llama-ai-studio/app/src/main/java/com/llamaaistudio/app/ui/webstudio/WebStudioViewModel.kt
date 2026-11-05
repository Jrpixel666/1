package com.llamaaistudio.app.ui.webstudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.WebProjectEntity
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.webserver.LocalWebServer
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
    /** Built-in server, when it is serving *this* project. */
    val server: LocalWebServer.State = LocalWebServer.State(),
    val shareOnLan: Boolean = false,
    /** Optional: the project's own server.js running under Node in the built-in Linux. */
    val nodeServer: ServerStatus = ServerStatus.UNKNOWN,
    /** URL shown in the in-app preview, or null when the preview is closed. */
    val previewUrl: String? = null,
) {
    val serverRunning: Boolean get() = server.running
    val nodeUrl: String get() = "http://127.0.0.1:$NODE_PORT/"
}

/** Port server.js listens on inside the built-in Linux (the static server keeps the project port). */
const val NODE_PORT = 3000

private const val WEB_GEN_MAX_TOKENS = 2048
private const val WEB_GEN_CONTEXT_CHARS = 5000

class WebStudioViewModel(private val app: LlamaAiStudioApp) : ViewModel() {
    private val repo = app.webProjectRepository

    val projects: StateFlow<List<WebProjectEntity>> =
        repo.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(WebStudioUiState())
    val uiState: StateFlow<WebStudioUiState> = _uiState

    /** A model to build with: the selected OpenCode model, or a loaded on-device model. */
    val modelLoaded: Boolean get() = app.ai.isAvailable
    val aiSourceLabel: String get() = app.ai.sourceLabel

    private val probeClient = app.httpClient.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var pollJob: Job? = null
    private var serverWatchJob: Job? = null
    private var genJob: Job? = null
    private var startRequestedAt = 0L

    // --- projects ---------------------------------------------------------------------------

    fun createProject(name: String, template: String) {
        viewModelScope.launch {
            openProject(repo.createProject(name, template))
        }
    }

    fun openProject(project: WebProjectEntity) {
        _uiState.value = WebStudioUiState(selectedProject = project, shareOnLan = _uiState.value.shareOnLan)
        viewModelScope.launch {
            repo.touchOpened(project)
            refreshFiles()
        }
        serverWatchJob?.cancel()
        serverWatchJob = viewModelScope.launch {
            app.webServer.state.collect { s ->
                val mine = s.running && s.config?.projectId == project.id
                _uiState.update {
                    it.copy(
                        server = if (mine) s else LocalWebServer.State(error = s.error.takeIf { s.config?.projectId == project.id }),
                        previewUrl = if (!mine && it.previewUrl != null && it.previewUrl != it.nodeUrl) null else it.previewUrl,
                    )
                }
            }
        }
        if (project.template == "node") startNodePolling() else pollJob?.cancel()
    }

    fun closeProject() {
        pollJob?.cancel()
        serverWatchJob?.cancel()
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
            syncNodeIfRunning(changed = listOf(file))
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
        if (!app.ai.isAvailable) {
            _uiState.update { it.copy(status = "Pick a model in the Models tab first — a local GGUF or a free OpenCode model") }
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
            app.ai.generate(turns, GenerationParams(maxTokens = WEB_GEN_MAX_TOKENS, temperature = 0.2f))
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
            // Serve it straight away so testing is one tap; live reload refreshes open pages.
            val wasRunning = _uiState.value.serverRunning
            if (!wasRunning) startServerNow(project)
            val url = _uiState.value.server.localUrl
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    status = "Built ${written.size} file(s): $names ($tokens tokens). " +
                        when {
                            url == null -> "Tap Start server to test it."
                            wasRunning -> "Open previews refresh automatically."
                            else -> "Live at $url — tap Preview."
                        },
                )
            }
            syncNodeIfRunning(changed = written)
        }
    }

    // --- built-in server -------------------------------------------------------------------

    fun startServer() {
        val project = _uiState.value.selectedProject ?: return
        if (startServerNow(project)) {
            val st = _uiState.value.server
            _uiState.update { it.copy(status = "Serving at ${st.localUrl}" + (st.lanUrl?.let { lan -> " · on Wi-Fi: $lan" } ?: "")) }
        }
    }

    /** Starts (or restarts) the built-in server on this project; returns false on failure. */
    private fun startServerNow(project: WebProjectEntity): Boolean {
        val result = app.webServer.start(
            LocalWebServer.Config(
                root = repo.previewRoot(project),
                port = project.port,
                shareOnLan = _uiState.value.shareOnLan,
                label = project.name,
                projectId = project.id,
            )
        )
        result.onSuccess { state ->
            _uiState.update { it.copy(server = state) }
        }.onFailure { e ->
            _uiState.update { it.copy(status = "Couldn't start the server: ${e.message}") }
        }
        return result.isSuccess
    }

    fun stopServer() {
        app.webServer.stop()
        _uiState.update { it.copy(status = "Server stopped") }
    }

    fun setShareOnLan(enabled: Boolean) {
        _uiState.update { it.copy(shareOnLan = enabled) }
        val project = _uiState.value.selectedProject ?: return
        if (_uiState.value.serverRunning) {
            startServerNow(project)
            val st = _uiState.value.server
            _uiState.update {
                it.copy(
                    status = if (!enabled) "Wi-Fi sharing off — only this phone can open the site."
                    else st.lanUrl?.let { lan -> "Other devices on your Wi-Fi can open $lan" }
                        ?: "Couldn't find a Wi-Fi address — are you connected to Wi-Fi?",
                )
            }
        }
    }

    /** Opens the in-app preview, starting the built-in server first if needed. */
    fun openPreview() {
        val project = _uiState.value.selectedProject ?: return
        if (!_uiState.value.serverRunning && !startServerNow(project)) return
        _uiState.update { it.copy(previewUrl = it.server.localUrl) }
    }

    fun openNodePreview() {
        _uiState.update { it.copy(previewUrl = it.nodeUrl) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewUrl = null) }
    }

    // --- optional: Node.js server.js inside the built-in Linux ----------------------------

    val linuxState get() = app.linux.state
    val nodeLog get() = app.nodeJob.log

    /**
     * Runs the project's server.js with Node inside the built-in Linux (installing Node.js on
     * first use). The project folder is mounted at /projects/web/<name>, so Node always sees
     * the current files — no export or copying.
     */
    fun startNode() {
        val project = _uiState.value.selectedProject ?: return
        if (!app.linux.isReady) {
            _uiState.update { it.copy(status = "Set up the built-in Linux first (Linux terminal → Install Linux).") }
            return
        }
        startRequestedAt = System.currentTimeMillis()
        _uiState.update { it.copy(nodeServer = ServerStatus.STARTING, status = "Starting server.js…") }
        app.nodeJob.start(
            label = "node server.js (${project.name})",
            command = nodeCommand(File(project.rootPath).name),
            owner = project.id,
        )
    }

    fun stopNode() {
        app.nodeJob.stop()
        _uiState.update {
            it.copy(
                nodeServer = ServerStatus.STOPPED,
                status = "Node server stopped",
                previewUrl = if (it.previewUrl == it.nodeUrl) null else it.previewUrl,
            )
        }
    }

    private fun nodeCommand(dir: String): String = """
        cd '/projects/web/$dir' || exit 1
        if ! command -v node >/dev/null 2>&1; then
          echo '==> Installing Node.js (first run only)…'
          apk add --no-progress nodejs npm || exit 1
        fi
        if [ -f package.json ] && grep -q '"dependencies"' package.json; then
          echo '==> npm install'
          npm install --no-audit --no-fund || exit 1
        fi
        ENTRY=server.js
        [ -f "${'$'}ENTRY" ] || ENTRY=index.js
        echo "==> node ${'$'}ENTRY on http://127.0.0.1:$NODE_PORT"
        PORT=$NODE_PORT exec node "${'$'}ENTRY"
    """.trimIndent()

    private val nodeRunningForThis: Boolean
        get() = app.nodeJob.status.value.let { it.running && it.owner == _uiState.value.selectedProject?.id }

    /** Static files are read per request by server.js; only server code needs a restart. */
    private fun syncNodeIfRunning(changed: List<File>) {
        val project = _uiState.value.selectedProject ?: return
        if (project.template != "node" || !nodeRunningForThis) return
        val root = File(project.rootPath).canonicalPath
        val serverCodeChanged = changed.any {
            (it.parentFile?.canonicalPath == root && it.extension in setOf("js", "mjs", "cjs")) || it.name == "package.json"
        }
        if (serverCodeChanged) {
            startNode()
            _uiState.update { it.copy(status = (it.status ?: "") + " Restarting Node for the new server code.") }
        }
    }

    private fun startNodePolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val jobRunning = nodeRunningForThis
                val up = jobRunning && probe(NODE_PORT)
                _uiState.update { state ->
                    val next = when {
                        up -> ServerStatus.RUNNING
                        jobRunning -> ServerStatus.STARTING
                        else -> ServerStatus.STOPPED
                    }
                    val justStarted = next == ServerStatus.RUNNING && state.nodeServer == ServerStatus.STARTING
                    val exited = state.nodeServer != ServerStatus.STOPPED && next == ServerStatus.STOPPED &&
                        System.currentTimeMillis() - startRequestedAt > 1500
                    state.copy(
                        nodeServer = next,
                        previewUrl = when {
                            justStarted -> state.nodeUrl
                            next != ServerStatus.RUNNING && state.previewUrl == state.nodeUrl -> null
                            else -> state.previewUrl
                        },
                        status = when {
                            justStarted -> "Node server running at ${state.nodeUrl}"
                            exited && state.nodeServer == ServerStatus.STARTING -> "server.js exited — see the Node log."
                            else -> state.status
                        },
                    )
                }
                delay(if (_uiState.value.nodeServer == ServerStatus.STARTING) 700 else 2500)
            }
        }
    }

    private suspend fun probe(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url("http://127.0.0.1:$port/").head().build())
                .execute().use { true }
        }.getOrDefault(false)
    }

    fun clearStatus() {
        _uiState.update { it.copy(status = null) }
    }

    fun setStatus(message: String) {
        _uiState.update { it.copy(status = message) }
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WebStudioViewModel(app) as T
    }
}
