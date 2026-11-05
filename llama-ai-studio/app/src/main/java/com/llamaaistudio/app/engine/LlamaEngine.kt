package com.llamaaistudio.app.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ChatTurn(val role: String, val content: String)

data class ModelLoadParams(
    val contextLength: Int = 4096,
    val batchSize: Int = 512,
    val threads: Int = CpuTopology.performanceCores,
    val threadsBatch: Int = threads,
    /** 0 = CPU only, N = N layers on the GPU, -1 = all layers, -2 = auto-fit to GPU memory. */
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Long = -1L,
)

data class ModelInfo(
    val desc: String,
    val paramCount: Long,
    val sizeBytes: Long,
    val contextLengthTrained: Int,
    val vocabSize: Int,
    val hasChatTemplate: Boolean,
    val nLayer: Int = 0,
    /** Layers actually offloaded to the GPU (0 = CPU only). */
    val gpuLayers: Int = 0,
) {
    val onGpu: Boolean get() = gpuLayers > 0
}

/** A compute device ggml found (the CPU, the Vulkan GPU, …). */
data class BackendDevice(
    val name: String,
    val description: String,
    val type: String, // cpu | gpu | igpu | accel
    val memoryFree: Long,
    val memoryTotal: Long,
) {
    val isGpu: Boolean get() = type == "gpu" || type == "igpu"
}

data class GenerationStats(
    val promptTokens: Int,
    val genTokens: Int,
    val prefillMs: Double,
    val genMs: Double,
    val promptTps: Double,
    val genTps: Double,
    val stopReason: String,
)

sealed interface GenerationEvent {
    data class Token(val piece: String) : GenerationEvent
    data class Done(val stats: GenerationStats) : GenerationEvent
    data class Failed(val reason: String) : GenerationEvent
}

sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data class Ready(val modelPath: String, val info: ModelInfo) : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Coroutine-friendly wrapper around [LlamaNative]. Owns at most one loaded model at a time;
 * callers swap models by calling [loadModel] again (it unloads the previous one first).
 */
class LlamaEngine(
    /**
     * Written right before a GPU load/generation and deleted right after. If the process dies
     * in between (a GPU driver crash in native code), the file survives and the next launch
     * falls back to CPU instead of crashing again on the automatic model restore.
     */
    private val gpuCrashMarker: File? = null,
    private val backgroundDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-inference") }
            .asCoroutineDispatcher(),
) {
    private val seedCounter = AtomicLong(System.nanoTime())

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state

    init {
        LlamaNative.backendInit()
    }

    val isLoaded: Boolean get() = handle != 0L

    /** Params the current model was loaded with (null when nothing is loaded). */
    @Volatile
    var currentLoadParams: ModelLoadParams? = null
        private set

    /** Changes CPU threads on the loaded model immediately, no reload needed. */
    suspend fun setThreads(threads: Int, threadsBatch: Int) = withContext(backgroundDispatcher) {
        val h = handle
        if (h != 0L) {
            LlamaNative.setThreads(h, threads, threadsBatch)
            currentLoadParams = currentLoadParams?.copy(threads = threads, threadsBatch = threadsBatch)
        }
    }

    fun currentModelPath(): String? = (_state.value as? EngineState.Ready)?.modelPath

    suspend fun loadModel(path: String, params: ModelLoadParams): Result<ModelInfo> =
        withContext(backgroundDispatcher) {
            unloadInternal()
            _state.value = EngineState.Loading
            val gpu = params.gpuLayers != 0
            if (gpu) markGpu("load")
            val newHandle = LlamaNative.loadModel(
                path,
                params.contextLength,
                params.batchSize,
                params.threads,
                params.threadsBatch,
                params.gpuLayers,
                params.useMmap,
                params.useMlock,
            )
            if (gpu) clearGpuMark()
            if (newHandle == 0L) {
                val msg = "Failed to load model (out of memory, unsupported format, or corrupt file)"
                _state.value = EngineState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            handle = newHandle
            currentLoadParams = params
            val info = parseModelInfo(LlamaNative.getModelInfo(newHandle))
            _state.value = EngineState.Ready(path, info)
            Result.success(info)
        }

    suspend fun unloadModel() = withContext(backgroundDispatcher) {
        unloadInternal()
        _state.value = EngineState.Idle
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
            currentLoadParams = null
        }
    }

    fun generate(
        messages: List<ChatTurn>,
        params: GenerationParams = GenerationParams(),
    ): Flow<GenerationEvent> = callbackFlow {
        val h = handle
        if (h == 0L) {
            trySend(GenerationEvent.Failed("No model loaded"))
            close()
            return@callbackFlow
        }

        val callback = object : LlamaNative.TokenCallback {
            override fun onToken(piece: String): Boolean {
                // The channel is unbounded (see .buffer below), so this only fails once the
                // collector has gone away — i.e. the user pressed Stop / left the screen.
                return trySend(GenerationEvent.Token(piece)).isSuccess
            }
        }

        // The native call below blocks its thread until generation ends, so awaitClose{} only
        // runs afterwards and can't be what stops it. This sibling coroutine is cancelled the
        // moment the collector goes away (Stop button, screen left) and flips the native
        // cancel flag right then.
        val finished = AtomicBoolean(false)
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) LlamaNative.cancelGeneration(h)
            }
        }

        try {
            withContext(backgroundDispatcher) {
                ensureActive() // stopped while queued behind another job on the inference thread
                // The model may have been swapped/unloaded while this call was queued behind
                // another generation on the single inference thread.
                if (handle != h) {
                    trySend(GenerationEvent.Failed("Model was unloaded"))
                    return@withContext
                }
                val seed = if (params.seed >= 0) params.seed else seedCounter.incrementAndGet()
                val gpu = (currentLoadParams?.gpuLayers ?: 0) != 0
                if (gpu) markGpu("generate")
                val messagesJson = JSONArray().apply {
                    messages.forEach { turn ->
                        put(JSONObject().put("role", turn.role).put("content", turn.content))
                    }
                }.toString()
                val resultJson = LlamaNative.generate(
                    h,
                    messagesJson,
                    params.maxTokens,
                    params.temperature,
                    params.topK,
                    params.topP,
                    params.minP,
                    params.repeatPenalty,
                    params.repeatLastN,
                    seed,
                    callback,
                )
                if (gpu) clearGpuMark()
                val obj = JSONObject(resultJson)
                if (obj.has("error")) {
                    trySend(GenerationEvent.Failed(obj.getString("error")))
                } else {
                    trySend(
                        GenerationEvent.Done(
                            GenerationStats(
                                promptTokens = obj.getInt("promptTokens"),
                                genTokens = obj.getInt("genTokens"),
                                prefillMs = obj.getDouble("prefillMs"),
                                genMs = obj.getDouble("genMs"),
                                promptTps = obj.getDouble("promptTps"),
                                genTps = obj.getDouble("genTps"),
                                stopReason = obj.getString("stopReason"),
                            )
                        )
                    )
                }
            }
        } finally {
            finished.set(true)
            cancelWatcher.cancel()
        }
        close()
        awaitClose { }
    }
        // Default callbackFlow buffer is 64 elements and trySend() fails when it's full, which
        // made long replies stop dead mid-sentence whenever the UI fell briefly behind.
        .buffer(Channel.UNLIMITED)

    private fun markGpu(phase: String) {
        runCatching { gpuCrashMarker?.writeText(phase) }
    }

    private fun clearGpuMark() {
        runCatching { gpuCrashMarker?.delete() }
    }

    /** "load"/"generate" if the previous run died during a GPU call (and resets the marker). */
    fun consumeGpuCrashMarker(): String? {
        val f = gpuCrashMarker ?: return null
        if (!f.exists()) return null
        val phase = runCatching { f.readText() }.getOrDefault("unknown")
        f.delete()
        return phase
    }

    fun tokenCount(text: String): Int {
        val h = handle
        return if (h == 0L) 0 else LlamaNative.tokenCount(h, text)
    }

    companion object {
        fun parseModelInfo(json: String): ModelInfo {
            val obj = JSONObject(json)
            return ModelInfo(
                desc = obj.optString("desc", "unknown"),
                paramCount = obj.optLong("nParams", 0L),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                contextLengthTrained = obj.optInt("nCtxTrain", 0),
                vocabSize = obj.optInt("nVocab", 0),
                hasChatTemplate = obj.optBoolean("hasChatTemplate", false),
                nLayer = obj.optInt("nLayer", 0),
                gpuLayers = obj.optInt("gpuLayers", 0),
            )
        }

        /** Compute devices ggml registered on this phone (CPU + Vulkan GPU if usable). */
        fun devices(): List<BackendDevice> = runCatching {
            val arr = JSONArray(LlamaNative.listDevices())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BackendDevice(
                    name = o.optString("name"),
                    description = o.optString("description"),
                    type = o.optString("type"),
                    memoryFree = o.optLong("memoryFree"),
                    memoryTotal = o.optLong("memoryTotal"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
