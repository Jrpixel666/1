package com.llamaaistudio.app

import android.app.Application
import com.llamaaistudio.app.data.cloud.OpenCodeRepository
import com.llamaaistudio.app.data.datastore.ComputeMode
import com.llamaaistudio.app.data.datastore.SettingsRepository
import com.llamaaistudio.app.data.db.AppDatabase
import com.llamaaistudio.app.data.hf.HfApi
import com.llamaaistudio.app.data.hf.ModelDownloadManager
import com.llamaaistudio.app.data.repo.ChatRepository
import com.llamaaistudio.app.data.repo.DevProjectRepository
import com.llamaaistudio.app.data.repo.ImageGalleryRepository
import com.llamaaistudio.app.data.repo.ImageModelRepository
import com.llamaaistudio.app.data.repo.ModelRepository
import com.llamaaistudio.app.data.repo.WebProjectRepository
import com.llamaaistudio.app.engine.AiRouter
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.engine.LiteRtEngine
import com.llamaaistudio.app.engine.LlamaEngine
import com.llamaaistudio.app.engine.ModelLoadParams
import com.llamaaistudio.app.linux.LinuxEnvironment
import com.llamaaistudio.app.linux.LinuxJob
import com.llamaaistudio.app.linux.LinuxTerminal
import com.llamaaistudio.app.webserver.BackgroundWorkService
import com.llamaaistudio.app.webserver.LocalWebServer
import com.llamaaistudio.imagegen.ImageGenEngine
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class LlamaAiStudioApp : Application() {

    /**
     * Outlives any single screen/ViewModel — used only to restore last-used state on launch.
     * This is best-effort background work; a failure in it (e.g. a model load erroring out)
     * must never take the whole app down, hence the exception handler instead of letting it
     * propagate to the default (crashing) handler.
     */
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("LlamaAiStudioApp", "Background restore failed (non-fatal)", e)
        },
    )

    lateinit var database: AppDatabase
        private set

    lateinit var llamaEngine: LlamaEngine
        private set

    lateinit var chatRepository: ChatRepository
        private set

    /** Optional OpenCode Zen cloud models (user's own API key). */
    lateinit var openCode: OpenCodeRepository
        private set

    /** Google's LiteRT-LM runtime for .litertlm models (GPU / CPU / NPU). */
    lateinit var liteRtEngine: LiteRtEngine
        private set

    /** The on-device engine that has a model (LiteRT-LM if loaded, else llama.cpp). */
    val localEngineState: StateFlow<EngineState> by lazy {
        combine(llamaEngine.state, liteRtEngine.state) { llama, lite -> if (lite !is EngineState.Idle) lite else llama }
            .stateIn(applicationScope, SharingStarted.Eagerly, EngineState.Idle)
    }

    /**
     * Loads a local model with the engine its file needs (.litertlm → LiteRT-LM, .gguf →
     * llama.cpp) and unloads the other engine so two models never compete for RAM.
     */
    suspend fun loadLocalModel(path: String): Boolean {
        val settings = settingsRepository.settings.first()
        val ok = if (path.endsWith(".litertlm", ignoreCase = true)) {
            llamaEngine.unloadModel()
            liteRtEngine.loadModel(path, settings.liteRtBackend, settings.contextLength, settings.threads).isSuccess
        } else {
            liteRtEngine.unload()
            llamaEngine.loadModel(path, settings.loadParams()).isSuccess
        }
        if (ok) modelRepository.markUsed(path)
        return ok
    }

    /** Routes "ask the AI" to the selected OpenCode model or the on-device model. */
    lateinit var ai: AiRouter
        private set

    lateinit var webProjectRepository: WebProjectRepository
        private set

    /** Built-in Alpine Linux (proot) and its app-wide terminal. */
    lateinit var linux: LinuxEnvironment
        private set

    lateinit var linuxTerminal: LinuxTerminal
        private set

    /** Web Studio's Node.js server (server.js) running inside the built-in Linux. */
    lateinit var nodeJob: LinuxJob
        private set

    /** Dev Studio's Gradle builds / Android SDK setup inside the built-in Linux. */
    lateinit var buildJob: LinuxJob
        private set

    /** Dev Studio's Project Builder (plan → write files → build → auto-fix); app-scoped. */
    val projectBuilder by lazy { com.llamaaistudio.app.ui.devstudio.ProjectBuilder() }

    /** Web Studio's built-in server; app-scoped so it keeps running across screens. */
    val webServer = LocalWebServer()

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var httpClient: OkHttpClient
        private set

    lateinit var hfApi: HfApi
        private set

    lateinit var downloadManager: ModelDownloadManager
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var devProjectRepository: DevProjectRepository
        private set

    lateinit var imageGenEngine: ImageGenEngine
        private set

    lateinit var imageModelRepository: ImageModelRepository
        private set

    lateinit var imageDownloadManager: ModelDownloadManager
        private set

    lateinit var imageGalleryRepository: ImageGalleryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.build(this)
        llamaEngine = LlamaEngine(gpuCrashMarker = File(filesDir, "gpu_call_in_progress"))
        httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        openCode = OpenCodeRepository(this, httpClient)
        liteRtEngine = LiteRtEngine(java.io.File(cacheDir, "litertlm"), applicationInfo.nativeLibraryDir)
        ai = AiRouter(llamaEngine, liteRtEngine, openCode)
        chatRepository = ChatRepository(database.chatDao(), ai)
        modelRepository = ModelRepository(this, database.modelDao())
        hfApi = HfApi.create(httpClient)
        linux = LinuxEnvironment(this, httpClient)
        linuxTerminal = LinuxTerminal(linux)
        nodeJob = LinuxJob(linux, "node")
        buildJob = LinuxJob(linux, "build")
        downloadManager = ModelDownloadManager(modelRepository.modelsDir, { file, meta, sha, verified ->
            modelRepository.registerModel(
                file = file,
                displayName = meta.filename.removeSuffix(".gguf"),
                hfModelId = meta.modelId,
                hfFilename = meta.filename,
                sha256 = sha,
                sha256Verified = verified,
                quantization = meta.quantization,
                paramCount = meta.paramCount,
            )
        }, httpClient)
        settingsRepository = SettingsRepository(this)
        devProjectRepository = DevProjectRepository(this, database.devProjectDao())
        webProjectRepository = WebProjectRepository(this, database.webProjectDao())

        imageGenEngine = ImageGenEngine()
        imageModelRepository = ImageModelRepository(this, database.imageModelDao())
        imageDownloadManager = ModelDownloadManager(imageModelRepository.modelsDir, { file, meta, sha, verified ->
            imageModelRepository.registerModel(
                file = file,
                displayName = meta.filename.removeSuffix(".gguf"),
                hfModelId = meta.modelId,
                hfFilename = meta.filename,
                sha256 = sha,
                sha256Verified = verified,
                quantization = meta.quantization,
            )
        }, httpClient)
        imageGalleryRepository = ImageGalleryRepository(database.generatedImageDao())

        restoreLastUsedModels()
        watchBackgroundWork()
    }

    /**
     * Human-readable list of long-running work (servers, builds, installs). While non-empty,
     * [BackgroundWorkService] keeps the process in the foreground so it isn't frozen when the
     * user switches to a browser or another app.
     */
    val backgroundWork: StateFlow<List<String>> by lazy {
        combine(webServer.state, nodeJob.status, buildJob.status, linuxTerminal.running) { web, node, build, term ->
            buildList {
                if (web.running) add("Web server · ${web.localUrl}")
                if (node.running) add("Node.js · ${node.label}")
                if (build.running) add("Build · ${build.label}")
                if (term != null) add("Linux · $term")
            }
        }.stateIn(applicationScope, SharingStarted.Eagerly, emptyList())
    }

    private fun watchBackgroundWork() {
        applicationScope.launch(Dispatchers.Main) {
            var wasActive = false
            backgroundWork.collect { items ->
                val active = items.isNotEmpty()
                // Started on the transition to "something is running" — that's always right
                // after the user tapped Start/Build/Install, i.e. while the app is in the
                // foreground, which Android requires for starting a foreground service.
                if (active && !wasActive) runCatching { BackgroundWorkService.start(this@LlamaAiStudioApp) }
                wasActive = active
            }
        }
    }

    fun stopAllBackgroundWork() {
        webServer.stop()
        nodeJob.stop()
        buildJob.stop()
        linuxTerminal.stop()
    }

    /**
     * Closing and reopening the app otherwise means reselecting a model every time, since the
     * loaded-model handle is purely in-memory and process death throws it away. Reload whatever
     * was most recently used (if it's still on disk) so the app comes back ready instead of
     * resetting to a blank slate.
     *
     * This runs sequentially and checks available memory before each load — a phone's RAM
     * budget can't necessarily hold a multi-GB chat model AND a multi-GB diffusion model at
     * once, and loading both unconditionally in parallel (as an earlier version of this did)
     * left the process memory-starved, with no obvious cause: the crash this was producing
     * showed up as soon as almost anything else tried to allocate — a settings change, a
     * Compose recomposition from dragging a slider — not at the load itself.
     */
    /** Set when the previous run crashed inside a GPU call; Performance tab explains it. */
    @Volatile
    var gpuCrashNotice: String? = null

    private fun restoreLastUsedModels() {
        applicationScope.launch {
            llamaEngine.consumeGpuCrashMarker()?.let { phase ->
                // The GPU driver crashed the app last time — don't walk into it again.
                settingsRepository.setComputeMode(ComputeMode.CPU_ONLY)
                gpuCrashNotice = "The app closed unexpectedly while the GPU was " +
                    (if (phase == "load") "loading the model" else "generating") +
                    ", so it switched back to CPU only. You can try GPU + CPU again with fewer GPU layers."
                Log.w("LlamaAiStudioApp", "GPU call crashed last run ($phase); fell back to CPU")
            }
            val settings = settingsRepository.settings.first()
            modelRepository.getMostRecentlyUsed()?.let { model ->
                val fits = modelRepository.estimateRamRequirementBytes(model.sizeBytes, settings.contextLength) <
                    modelRepository.availableMemoryBytes() * 0.7
                if (fits && File(model.filePath).exists()) loadLocalModel(model.filePath)
            }

            // Checked again (not just once up front): loading the chat model above, if it
            // happened, already spent part of the available budget.
            imageModelRepository.getMostRecentlyUsed()?.let { model ->
                val fits = imageModelRepository.estimateRamRequirementBytes(model.sizeBytes) <
                    modelRepository.availableMemoryBytes() * 0.7
                if (fits && File(model.filePath).exists()) {
                    imageGenEngine.loadModel(model.filePath)
                }
            }
        }
    }
}
