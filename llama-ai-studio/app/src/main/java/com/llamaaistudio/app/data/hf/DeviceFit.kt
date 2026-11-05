package com.llamaaistudio.app.data.hf

/**
 * Picks, for one Hugging Face model, the GGUF file that runs best on this phone with the CPU and
 * GPU working together. Pure so it can be unit-tested.
 *
 * Memory on a phone is shared: the Vulkan GPU (Mali on Tensor G4) draws from the same RAM as the
 * CPU. A file that fits the GPU's budget runs with every layer on the GPU; one that only fits
 * overall memory runs split — as many layers on the GPU as fit, the rest on the CPU (llama.cpp's
 * auto-fit does the split at load time).
 */
object DeviceFit {

    enum class Placement { ALL_ON_GPU, SPLIT_GPU_CPU, CPU_ONLY }

    data class Budget(
        /** Memory the model may use overall (weights + KV cache + runtime). */
        val ramBytes: Long,
        /** Memory the GPU can take; 0 when there is no usable GPU. */
        val gpuBytes: Long,
        val contextLength: Int = 4096,
    )

    data class Pick(
        val file: GgufFileOption,
        val placement: Placement,
        val neededBytes: Long,
        /** Rough share of the model that lands on the GPU (0..1). */
        val gpuShare: Float,
    )

    /** Quality/speed order for phone GPUs + CPUs (k-quants run well on Vulkan and NEON). */
    val PREFERRED_QUANTS = listOf(
        "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q4_0", "Q5_K_S", "Q6_K", "IQ4_XS", "IQ4_NL", "Q8_0", "Q3_K_L", "Q3_K_M",
    )

    private val MULTIPART = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** Helper files that aren't the chat model itself (vision projectors, draft/MTP heads). */
    private val AUXILIARY = Regex("(mmproj|draft|mtp|dflash|eagle)", RegexOption.IGNORE_CASE)

    /**
     * Rejects files far too small to be the repo's main model: some repos ship a tiny draft
     * model next to a 27B one, which would otherwise look like "27B in 1.6 GB". Even 2-bit
     * quants need more than 2.5 bits per weight.
     */
    private fun plausibleSize(f: GgufFileOption): Boolean {
        val params = f.paramCount ?: return true
        if (params <= 0) return true
        return f.sizeBytes * 8.0 / params >= 2.5
    }

    /** Weights + KV cache for [contextLength] + runtime overhead (same rule the loader uses). */
    fun neededBytes(fileBytes: Long, contextLength: Int): Long {
        val kvCache = contextLength.toLong() * 2 * 128 * 1024
        return (fileBytes * 1.15).toLong() + kvCache + 256L * 1024 * 1024
    }

    fun pick(files: List<GgufFileOption>, budget: Budget): Pick? {
        val candidates = files.filter { f ->
            f.sizeBytes > 0 &&
                !MULTIPART.containsMatchIn(f.filename) &&
                !AUXILIARY.containsMatchIn(f.filename) &&
                plausibleSize(f) &&
                f.quantization?.uppercase() in PREFERRED_QUANTS
        }.sortedBy { PREFERRED_QUANTS.indexOf(it.quantization!!.uppercase()) }

        if (budget.gpuBytes > 0) {
            candidates.firstOrNull { neededBytes(it.sizeBytes, budget.contextLength) <= minOf(budget.gpuBytes, budget.ramBytes) }
                ?.let { return Pick(it, Placement.ALL_ON_GPU, neededBytes(it.sizeBytes, budget.contextLength), 1f) }
        }
        val fits = candidates.firstOrNull { neededBytes(it.sizeBytes, budget.contextLength) <= budget.ramBytes } ?: return null
        val need = neededBytes(fits.sizeBytes, budget.contextLength)
        return if (budget.gpuBytes > 0) {
            Pick(fits, Placement.SPLIT_GPU_CPU, need, (budget.gpuBytes.toFloat() / need).coerceIn(0.05f, 0.95f))
        } else {
            Pick(fits, Placement.CPU_ONLY, need, 0f)
        }
    }
}
