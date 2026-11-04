package com.llamaaistudio.app

import android.app.Application
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
import com.llamaaistudio.app.engine.LlamaEngine
import com.llamaaistudio.app.engine.ModelLoadParams
import com.llamaaistudio.imagegen.ImageGenEngine
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    lateinit var webProjectRepository: WebProjectRepository
        private set

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
        llamaEngine = LlamaEngine()
        chatRepository = ChatRepository(database.chatDao(), llamaEngine)
        modelRepository = ModelRepository(this, database.modelDao())
        httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        hfApi = HfApi.create(httpClient)
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
    private fun restoreLastUsedModels() {
        applicationScope.launch {
            val settings = settingsRepository.settings.first()
            modelRepository.getMostRecentlyUsed()?.let { model ->
                val fits = modelRepository.estimateRamRequirementBytes(model.sizeBytes, settings.contextLength) <
                    modelRepository.availableMemoryBytes() * 0.7
                if (fits && File(model.filePath).exists()) {
                    llamaEngine.loadModel(
                        model.filePath,
                        ModelLoadParams(
                            contextLength = settings.contextLength,
                            threads = settings.threads,
                            threadsBatch = settings.threads,
                            useMlock = settings.useMlock,
                        ),
                    )
                }
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
