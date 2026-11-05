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
                    if (code == 0) recordBuild(project.id, android)
                    setStatus(
                        when {
                            code != 0 -> gradleFailureReason(app.buildJob.log.value)
                                ?.let { "Build failed: $it" }
                                ?: "Build failed (exit $code) — the log shows why. The first build also downloads Gradle and dependencies, so check your connection."
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

    // --- Project Builder ---------------------------------------------------------------------

    val builder: StateFlow<ProjectBuilder.State> get() = app.projectBuilder.state

    private fun pkgOf(project: DevProjectEntity) =
        com.llamaaistudio.app.data.repo.ProjectTemplates.packageSegment(project.name)

    private fun codeAi(): CodeAi = object : CodeAi {
        override val label = app.ai.sourceLabel
        override val largeContext = app.ai.cloudModelName != null
        override suspend fun complete(system: String, user: String, maxTokens: Int): Result<String> {
            val sb = StringBuilder()
            var failure: String? = null
            app.ai.generate(listOf(ChatTurn("system", system), ChatTurn("user", user)), GenerationParams(maxTokens = maxTokens, temperature = 0.2f))
                .collect { e ->
                    when (e) {
                        is GenerationEvent.Token -> sb.append(e.piece)
                        is GenerationEvent.Failed -> failure = e.reason
                        is GenerationEvent.Done -> Unit
                    }
                }
            return failure?.let { Result.failure(Exception(it)) } ?: Result.success(sb.toString())
        }
    }

    private fun gradleRunner(project: DevProjectEntity): ProjectBuildRunner = object : ProjectBuildRunner {
        override suspend fun build(onLog: (String) -> Unit): ProjectBuildRunner.Outcome {
            repo.ensureBuildFiles(project)
            val done = kotlinx.coroutines.CompletableDeferred<Int>()
            app.buildJob.start(
                label = "Project Builder: ${project.name} (assembleDebug)",
                command = AndroidBuildToolchain.buildCommand("assembleDebug"),
                owner = project.id,
                workdir = repo.guestPath(project),
                env = AndroidBuildToolchain.buildEnv,
                onFinished = { done.complete(it) },
            )
            val code = try { done.await() } catch (e: kotlinx.coroutines.CancellationException) { app.buildJob.stop(); throw e }
            if (code == 0) recordBuild(project.id, true)
            val apk = repo.builtApk(project)
            return ProjectBuildRunner.Outcome(code == 0, app.buildJob.log.value, apk.takeIf { it.isFile })
        }
    }

    // --- GitHub builds -------------------------------------------------------------------------

    private val ghPrefs = app.getSharedPreferences("github_builds", android.content.Context.MODE_PRIVATE)
    private val _useGitHub = MutableStateFlow(ghPrefs.getBoolean("use", false))
    /** Compile on GitHub Actions instead of the phone (Build button and Project Builder). */
    val useGitHub: StateFlow<Boolean> = _useGitHub
    val gitHubToken: StateFlow<String?> get() { app.gitHubBuilds; return app.gitHubToken }
    private val _gitHub = MutableStateFlow<String?>(null)
    /** Progress / result of the last GitHub build. */
    val gitHubStatus: StateFlow<String?> = _gitHub
    private val _gitHubBusy = MutableStateFlow(false)
    val gitHubBusy: StateFlow<Boolean> = _gitHubBusy
    private var gitHubJob: kotlinx.coroutines.Job? = null

    fun setUseGitHub(on: Boolean) { _useGitHub.value = on; ghPrefs.edit().putBoolean("use", on).apply() }

    fun saveGitHubToken(token: String) {
        val t = token.trim()
        app.gitHubBuilds
        if (t.isEmpty()) { app.gitHubKeys.remove("github_token"); app.gitHubToken.value = null; return }
        app.gitHubKeys.put("github_token", t)
        app.gitHubToken.value = t
        viewModelScope.launch {
            _gitHub.value = runCatching { "Connected as ${app.gitHubBuilds.login()}" }.getOrElse { "Token check failed: ${it.message}" }
        }
    }

    private fun gitHubRunner(project: DevProjectEntity): ProjectBuildRunner = object : ProjectBuildRunner {
        override suspend fun build(onLog: (String) -> Unit): ProjectBuildRunner.Outcome {
            repo.ensureBuildFiles(project)
            var last: String? = null
            val r = try {
                app.gitHubBuilds.build(File(project.rootPath), com.llamaaistudio.app.data.cloud.GitHubBuilds.repoName(project.name), repo.builtApk(project)) {
                    _gitHub.value = it
                    if (it != last) { last = it; onLog(it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                _gitHub.value = msg
                return ProjectBuildRunner.Outcome(false, listOf("GitHub: $msg"), null)
            }
            r.runUrl?.let { onLog("Run: $it") }
            if (r.success) recordBuild(project.id, true)
            _gitHub.value = if (r.success) "Built on GitHub — tap Install APK." else "GitHub build failed" + (r.runUrl?.let { " — $it" } ?: "")
            return ProjectBuildRunner.Outcome(r.success, r.log, r.apk)
        }
    }

    /** One-off GitHub build (no AI fixing) from the Build card. */
    fun buildOnGitHub() {
        val project = _uiState.value.selectedProject ?: return
        if (_gitHubBusy.value) return
        if (app.gitHubToken.value == null) { _gitHub.value = "Add a GitHub token first"; return }
        gitHubJob = viewModelScope.launch {
            _gitHubBusy.value = true
            try {
                val o = gitHubRunner(project).build { }
                if (!o.success) {
                    val errs = ProjectBuilder.parseErrors(o.log)
                    if (errs.isNotEmpty()) _gitHub.value = (_gitHub.value ?: "") + "\n" +
                        errs.flatMap { (f, e) -> e.map { "${f.substringAfterLast('/')}: $it" } }.take(8).joinToString("\n")
                }
            } finally { _gitHubBusy.value = false }
        }
    }

    fun stopGitHubBuild() { gitHubJob?.cancel(); _gitHubBusy.value = false; _gitHub.value = "Stopped (GitHub may finish the run anyway)" }

    private fun runnerFor(project: DevProjectEntity) = if (_useGitHub.value) gitHubRunner(project) else gradleRunner(project)

    private fun builderReady(project: DevProjectEntity): Boolean = when {
        !repo.isAndroidApp(project) -> { setStatus("Project Builder makes Android apps — create an Android project."); false }
        !app.ai.isAvailable -> { setStatus("Pick a model first — OpenCode is best for big projects."); false }
        else -> true
    }

    fun planProject(idea: String) {
        val project = _uiState.value.selectedProject ?: return
        if (!builderReady(project)) return
        app.projectBuilder.plan(project.id, File(project.rootPath), pkgOf(project), idea, codeAi())
    }

    fun removePlannedFile(path: String) = app.projectBuilder.removeFromPlan(path)

    fun buildPlannedProject() {
        val project = _uiState.value.selectedProject ?: return
        if (!builderReady(project)) return
        if (_useGitHub.value) {
            if (app.gitHubToken.value == null) { setStatus("Add a GitHub token in the GitHub card first."); return }
        } else if (!_toolchainReady.value) { setStatus("Install the build tools first — or turn on Build on GitHub."); return }
        app.projectBuilder.buildAll(File(project.rootPath), pkgOf(project), codeAi(), runnerFor(project))
    }

    fun stopProjectBuilder() { app.projectBuilder.stop(); app.buildJob.stop() }
    fun resetProjectBuilder() = app.projectBuilder.reset()

    /**
     * Why the build failed: the compiler's own "e: file:line message" errors first (that's the
     * actual problem), then Gradle's "* What went wrong:" summary, plus out-of-memory hints.
     */
    private fun gradleFailureReason(log: List<String>): String? {
        val compilerErrors = log.filter { it.startsWith("e: ") }
            .map { it.removePrefix("e: ").replace(Regex("file://\\S*/projects/apps/[^/]+/"), "") }
            .distinct().take(8)
        val start = log.indexOfFirst { it.trim() == "* What went wrong:" }
        val section = if (start >= 0) log.drop(start + 1).takeWhile { !it.trim().startsWith("* Try:") }
            .map { it.trim() }.filter { it.isNotEmpty() && !it.contains("compilerRunner") } else emptyList()
        val oom = log.firstOrNull { it.contains("OutOfMemoryError") || it.contains("Java heap space") || it.contains("GC overhead") }
            ?.let { listOf("Gradle ran out of memory — close other apps, or lower org.gradle.jvmargs in gradle.properties.") }
            .orEmpty()
        val text = (compilerErrors + oom + section).joinToString("\n")
        return text.takeIf { it.isNotBlank() }?.take(2500)
    }

    /** Last failure text, for the Copy error button. */
    fun lastFailureText(): String = gradleFailureReason(app.buildJob.log.value)
        ?: app.buildJob.log.value.takeLast(80).joinToString("\n")

    // --- build ETA: learned from this project's last successful build ------------------------

    private val buildPrefs = app.getSharedPreferences("build_history", android.content.Context.MODE_PRIVATE)

    /** (duration ms, Gradle task count) of the last successful build, or null. */
    fun lastBuild(projectId: Long, android: Boolean): Pair<Long, Int>? {
        val key = "p${projectId}_${if (android) "apk" else "run"}"
        val ms = buildPrefs.getLong("${key}_ms", 0)
        return if (ms > 0) ms to buildPrefs.getInt("${key}_tasks", 0) else null
    }

    private fun recordBuild(projectId: Long, android: Boolean) {
        val st = app.buildJob.status.value
        val started = st.startedAt ?: return
        val key = "p${projectId}_${if (android) "apk" else "run"}"
        buildPrefs.edit()
            .putLong("${key}_ms", System.currentTimeMillis() - started)
            .putInt("${key}_tasks", app.buildJob.log.value.count { it.startsWith("> Task ") })
            .apply()
    }

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
        if (!app.ai.isAvailable) {
            _uiState.value = _uiState.value.copy(status = "Pick a model in the Models tab first — a local GGUF or a free OpenCode model")
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
                        "compilable. Project facts you must respect: it is a single-file app — build the " +
                        "whole UI in Kotlin code (e.g. LinearLayout, TextView, Button created in onCreate) and " +
                        "NEVER use R.layout, findViewById or XML resources, because no layout files exist. " +
                        "Available libraries: androidx.appcompat (AppCompatActivity), androidx.core, " +
                        "com.google.android.material, androidx.constraintlayout — nothing else (no Compose). " +
                        "Keep the same package name. Output ONLY the complete raw source code for the file — no " +
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
            app.ai.generate(turns, GenerationParams(maxTokens = CODE_GEN_MAX_TOKENS, temperature = 0.2f))
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
