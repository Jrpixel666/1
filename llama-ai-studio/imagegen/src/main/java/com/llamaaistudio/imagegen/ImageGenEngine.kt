package com.llamaaistudio.imagegen

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class ImageModelInfo(val path: String, val supportsImageGeneration: Boolean)

data class ImageGenParams(
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
)

data class ImageGenStats(
    val widthOut: Int,
    val heightOut: Int,
    val genMs: Double,
    val seedUsed: Long,
)

sealed interface ImageGenEvent {
    data class Progress(val step: Int, val steps: Int, val stepTimeMs: Float) : ImageGenEvent
    data class Done(val outputFile: File, val stats: ImageGenStats) : ImageGenEvent
    data class Failed(val reason: String) : ImageGenEvent
}

sealed interface ImageEngineState {
    data object Idle : ImageEngineState
    data object Loading : ImageEngineState
    data class Ready(val modelPath: String) : ImageEngineState
    data class Error(val message: String) : ImageEngineState
}

/**
 * Coroutine-friendly wrapper around [SdNative], mirroring the shape of the chat engine's
 * LlamaEngine. Owns at most one loaded diffusion model at a time.
 */
class ImageGenEngine(
    private val backgroundDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sd-inference") }.asCoroutineDispatcher(),
) {
    private val seedCounter = AtomicLong(System.nanoTime())

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow<ImageEngineState>(ImageEngineState.Idle)
    val state: StateFlow<ImageEngineState> = _state

    val isLoaded: Boolean get() = handle != 0L

    suspend fun loadModel(path: String, threads: Int = SdNative.recommendedThreadCount(), enableMmap: Boolean = true): Result<ImageModelInfo> =
        withContext(backgroundDispatcher) {
            unloadInternal()
            _state.value = ImageEngineState.Loading
            val newHandle = SdNative.loadModel(path, threads, enableMmap)
            if (newHandle == 0L) {
                val msg = "Failed to load diffusion model (unsupported format, out of memory, or needs a text encoder this build doesn't include)"
                _state.value = ImageEngineState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            handle = newHandle
            val supports = SdNative.supportsImageGeneration(newHandle)
            _state.value = ImageEngineState.Ready(path)
            Result.success(ImageModelInfo(path, supports))
        }

    suspend fun unloadModel() = withContext(backgroundDispatcher) {
        unloadInternal()
        _state.value = ImageEngineState.Idle
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            SdNative.freeModel(handle)
            handle = 0L
        }
    }

    fun generate(
        prompt: String,
        negativePrompt: String,
        outputFile: File,
        params: ImageGenParams = ImageGenParams(),
    ): Flow<ImageGenEvent> = callbackFlow {
        val h = handle
        if (h == 0L) {
            trySend(ImageGenEvent.Failed("No diffusion model loaded"))
            close()
            return@callbackFlow
        }

        val callback = object : SdNative.ProgressCallback {
            override fun onProgress(step: Int, steps: Int, stepTimeMs: Float) {
                trySend(ImageGenEvent.Progress(step, steps, stepTimeMs))
            }
        }

        withContext(backgroundDispatcher) {
            val seed = if (params.seed >= 0) params.seed else seedCounter.incrementAndGet()
            val resultJson = SdNative.generateImage(
                h, prompt, negativePrompt, params.width, params.height, params.steps,
                params.cfgScale, seed, outputFile.absolutePath, callback,
            )
            val obj = JSONObject(resultJson)
            if (obj.has("error")) {
                trySend(ImageGenEvent.Failed(obj.getString("error")))
            } else {
                trySend(
                    ImageGenEvent.Done(
                        outputFile,
                        ImageGenStats(
                            widthOut = obj.getInt("widthOut"),
                            heightOut = obj.getInt("heightOut"),
                            genMs = obj.getDouble("genMs"),
                            seedUsed = obj.getLong("seedUsed"),
                        ),
                    )
                )
            }
        }
        close()

        awaitClose {
            if (h != 0L) SdNative.cancelGeneration(h)
        }
    }
}
