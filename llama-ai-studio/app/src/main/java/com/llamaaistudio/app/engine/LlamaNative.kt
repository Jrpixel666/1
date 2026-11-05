package com.llamaaistudio.app.engine

/**
 * Thin JNI surface over the vendored llama.cpp (see /third_party/llama.cpp and
 * app/src/main/cpp/llama_bridge.cpp). One native "handle" == one loaded model + context;
 * callers (LlamaEngine) own handle lifetime.
 */
object LlamaNative {

    init {
        System.loadLibrary("llama_bridge")
    }

    interface TokenCallback {
        /** Called on the generation thread for every produced token piece. Return false to stop. */
        fun onToken(piece: String): Boolean
    }

    external fun backendInit()
    external fun backendFree()

    /** Returns 0 on failure. */
    external fun loadModel(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): Long

    external fun freeModel(handle: Long)

    /** JSON: {"nParams","sizeBytes","nCtxTrain","desc","nVocab","hasChatTemplate","nLayer","gpuLayers"} */
    external fun getModelInfo(handle: Long): String

    external fun tokenCount(handle: Long, text: String): Int

    /**
     * Runs prefill + generation synchronously on the calling thread, streaming token pieces to
     * [callback]. Returns JSON stats: {"promptTokens":,"genTokens":,"prefillMs":,"genMs":,
     * "promptTps":,"genTps":,"stopReason":"eos"|"length"|"cancelled"}
     */
    /** [messagesJson] is a JSON array of {"role":"system|user|assistant","content":".."}, oldest first. */
    external fun generate(
        handle: Long,
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        callback: TokenCallback,
    ): String

    external fun cancelGeneration(handle: Long)

    /** True when the Vulkan backend found a usable GPU on this device (runtime check). */
    external fun supportsVulkan(): Boolean

    /** JSON array of {"name","description","type":"cpu|gpu|igpu|accel","memoryFree","memoryTotal"}. */
    external fun listDevices(): String

    /** Changes CPU thread counts on a loaded model without reloading it. */
    external fun setThreads(handle: Long, nThreads: Int, nThreadsBatch: Int)

    external fun nativeThreadCountRecommendation(): Int
}
