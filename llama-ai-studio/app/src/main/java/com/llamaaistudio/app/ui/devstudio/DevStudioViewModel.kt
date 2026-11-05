package com.llamaaistudio.app.ui.devstudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.DevProjectEntity
import com.llamaaistudio.app.linux.AndroidBuildToolchain
import com.llamaaistudio.app.linux.LinuxEnvironment
import com.llamaaistudio.app.linux.LinuxJob
import com.llamaaistudio.app.util.ApkInstaller
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

    /** Built-in Linux state + the app-wide build job (SDK setup, Gradle builds). */
    val linuxState: StateFlow<LinuxEnvironment.State> = app.linux.state
    val buildStatus: StateFlow<LinuxJob.Status> = app.buildJob.status
    val buildLog: StateFlow<List<String>> = app.buildJob.log

    private val _toolchainReady = MutableStateFlow(AndroidBuildToolchain.isInstalled(app.linux.rootfs))
    /** Java 17 + Android SDK (with aarch64 aapt2) installed inside Linux. */
    val toolchainReady: StateFlow<Boolean> = _toolchainReady

    fun createProject(name: String, template: String) {
        viewModelScope.launch {
            val project = repo.createProject(name, template)
            openProject(project)
        }
    }

    fun openProject(project: DevProjectEntity) {
        _toolchainReady.value = AndroidBuildToolchain.isInstalled(app.linux.rootfs)
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

    // --- on-device builds (built-in Linux) ----------------------------------------------

    /** Installs Java 17 + the Android SDK inside Linux (one-time, roughly 250 MB download). */
    fun installToolchain() {
        if (!app.linux.isReady) {
            setStatus("Set up Linux first (Linux terminal → Install Linux).")
            return
        }
        app.buildJob.start(
            label = "Install Java 17 + Android SDK",
            command = AndroidBuildToolchain.setupScript(),
            onFinished = { code ->
                _toolchainReady.value = AndroidBuildToolchain.isInstalled(app.linux.rootfs)
                setStatus(if (code == 0) "Build tools installed — you can build now." else "Build tools setup failed — see the log, then try again.")
            },
        )
    }

    /** Builds the open project with Gradle inside Linux (APK for Android apps, `run` for console). */
    fun build() {
        val project = _uiState.value.selectedProject ?: return
        if (!_toolchainReady.value) {
            setStatus("Install the build tools first.")
            return
        }
        val android = repo.isAndroidApp(project)
        viewModelScope.launch {
            repo.ensureBuildFiles(project)
            app.buildJob.start(
                label = if (android) "Build ${project.name} (assembleDebug)" else "Run ${project.name}",
                command = AndroidBuildToolchain.buildCommand(if (android) "assembleDebug" else "run -q"),
                owner = project.id,
                workdir = repo.guestPath(project),
                env = AndroidBuildToolchain.buildEnv,
                onFinished = { code ->
                    setStatus(
                        when {
                            code != 0 -> "Build failed (exit $code) — the log shows why. The first build also downloads Gradle and dependencies, so check your connection."
                            android && repo.builtApk(project).isFile -> "Built ${repo.builtApk(project).name} — tap Install APK."
                            android -> "Build finished but no APK was found."
                            else -> "Program finished — output is in the log."
                        }
                    )
                },
            )
        }
    }

    fun stopBuild() = app.buildJob.stop()

    fun builtApk(): File? = _uiState.value.selectedProject?.let { repo.builtApk(it) }?.takeIf { it.isFile }

    fun isAndroidProject(): Boolean = _uiState.value.selectedProject?.let(repo::isAndroidApp) ?: true

    fun installApk() {
        val apk = builtApk() ?: return setStatus("Build the project first.")
        when (val r = ApkInstaller.install(app, apk)) {
            ApkInstaller.Result.Launched -> {}
            ApkInstaller.Result.NeedsPermission ->
                setStatus("Allow \"Install unknown apps\" for Llama AI Studio, then tap Install APK again.")
            is ApkInstaller.Result.Failed -> setStatus(r.message)
        }
    }

    fun setStatus(message: String) {
        _uiState.value = _uiState.value.copy(status = message)
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
                status = "AI updated ${targetFile.name} ($tokenCount tokens) — review it, then tap Build",
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
