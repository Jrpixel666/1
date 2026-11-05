package com.llamaaistudio.imagegen

/**
 * Thin JNI surface over the vendored stable-diffusion.cpp (see /third_party/stable-diffusion.cpp
 * and imagegen/src/main/cpp/sd_bridge.cpp). CLIP-based diffusion models only (SD1.x/2.x/SDXL/
 * SDXL-Turbo and similar) — see vocab.cpp's patch notes for why.
 */
object SdNative {

    init {
        System.loadLibrary("sd_bridge")
    }

    interface ProgressCallback {
        fun onProgress(step: Int, steps: Int, stepTimeMs: Float)
    }

    external fun getSystemInfo(): String

    /** Returns 0 on failure. */
    external fun loadModel(modelPath: String, nThreads: Int, enableMmap: Boolean): Long

    external fun freeModel(handle: Long)

    external fun supportsImageGeneration(handle: Long): Boolean

    /**
     * Generates one image and writes it as a PNG to [outputPath]. Returns JSON:
     * {"widthOut":,"heightOut":,"genMs":,"seedUsed":,"error":"..."?}
     */
    external fun generateImage(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        outputPath: String,
        callback: ProgressCallback,
        /** img2img: RGB888 pixels of the starting image (width x height), or null for txt2img. */
        initRgb: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        /** img2img: 0..1, how much the image may change. */
        strength: Float,
    ): String

    external fun cancelGeneration(handle: Long)

    external fun recommendedThreadCount(): Int
}
