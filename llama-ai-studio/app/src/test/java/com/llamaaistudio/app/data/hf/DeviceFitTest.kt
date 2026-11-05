package com.llamaaistudio.app.data.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFitTest {
    private val gb = 1024L * 1024 * 1024

    // Real file list of unsloth/Qwen3-4B-Instruct-2507-GGUF (sizes from the HF API).
    private val qwen4b = listOf(
        "F16" to 8051285344, "IQ4_NL" to 2381343840, "IQ4_XS" to 2270751840, "Q2_K" to 1669500000,
        "Q3_K_M" to 2075618400, "Q3_K_S" to 1886997600, "Q4_0" to 2375773280, "Q4_1" to 2596629600,
        "Q4_K_M" to 2497281120, "Q4_K_S" to 2383309920, "Q5_K_M" to 2889514080, "Q5_K_S" to 2823711840,
        "Q6_K" to 3306261600, "Q8_0" to 4280405600, "UD-IQ1_M" to 1143484000, "UD-Q4_K_XL" to 2546340960,
    ).map { (q, size) ->
        GgufFileOption(
            modelId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
            filename = "Qwen3-4B-Instruct-2507-$q.gguf",
            sizeBytes = size.toLong(),
            sha256 = null,
            quantization = Regex("(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z]+|F16|F32|BF16)").find(q)?.value,
            paramCount = 4_022_468_096,
            contextLengthTrained = 262144,
        )
    }

    @Test
    fun pixel9BudgetPutsA4bQ4KMEntirelyOnTheGpu() {
        // Pixel 9: 12 GB RAM → ~6.5 GB for the model, ~5.5 GB of it usable by the GPU.
        val pick = DeviceFit.pick(qwen4b, DeviceFit.Budget(ramBytes = (6.5 * gb).toLong(), gpuBytes = (5.5 * gb).toLong()))!!
        assertEquals("Q4_K_M", pick.file.quantization)
        assertEquals(DeviceFit.Placement.ALL_ON_GPU, pick.placement)
        assertEquals(1f, pick.gpuShare)
    }

    @Test
    fun smallGpuBudgetSplitsBetweenGpuAndCpu() {
        val pick = DeviceFit.pick(qwen4b, DeviceFit.Budget(ramBytes = 6 * gb, gpuBytes = (1.5 * gb).toLong()))!!
        assertEquals("Q4_K_M", pick.file.quantization)
        assertEquals(DeviceFit.Placement.SPLIT_GPU_CPU, pick.placement)
        assertTrue(pick.gpuShare in 0.3f..0.7f)
    }

    @Test
    fun tightMemoryFallsBackToSmallerQuantNeverAnUnlistedOne() {
        // 3.6 GiB budget (weights x1.15 + ~1 GiB KV cache at 4K context + runtime): the Q4
        // variants (~3.9 GiB) and IQ4_XS (~3.68 GiB) don't fit; Q3_K_M (~3.47 GiB) does.
        val pick = DeviceFit.pick(qwen4b, DeviceFit.Budget(ramBytes = (3.6 * gb).toLong(), gpuBytes = (3.6 * gb).toLong()))!!
        assertEquals("Q3_K_M", pick.file.quantization)
        // Too little memory for any preferred quant: no recommendation (IQ1/Q2 aren't offered).
        assertNull(DeviceFit.pick(qwen4b, DeviceFit.Budget(ramBytes = (1.5 * gb).toLong(), gpuBytes = gb)))
    }

    @Test
    fun noGpuMeansCpuOnlyAndSkipsSplitFilesAndProjectors() {
        val files = qwen4b + listOf(
            qwen4b[8].copy(filename = "big-Q4_K_M-00001-of-00002.gguf", sizeBytes = 100),
            qwen4b[8].copy(filename = "mmproj-Q4_K_M.gguf", sizeBytes = 100),
        )
        val pick = DeviceFit.pick(files, DeviceFit.Budget(ramBytes = 6 * gb, gpuBytes = 0))!!
        assertEquals(DeviceFit.Placement.CPU_ONLY, pick.placement)
        assertEquals("Qwen3-4B-Instruct-2507-Q4_K_M.gguf", pick.file.filename)
    }

    @Test
    fun ignoresTinyDraftFilesInBigModelRepos() {
        // A 27B repo that also ships a small draft model: the 1.6 GB "Q4_0" is not the 27B model.
        val big = 27_000_000_000L
        val files = listOf(
            GgufFileOption("x/Big-27B-GGUF", "Big-27B-Q4_0.gguf", 1_600_000_000, null, "Q4_0", big, 32768),
            GgufFileOption("x/Big-27B-GGUF", "Big-27B-Q4_K_M.gguf", 16_500_000_000, null, "Q4_K_M", big, 32768),
            GgufFileOption("x/Big-27B-GGUF", "Big-27B-draft-Q4_K_M.gguf", 500_000_000, null, "Q4_K_M", big, 32768),
        )
        assertNull(DeviceFit.pick(files, DeviceFit.Budget(ramBytes = 6 * gb, gpuBytes = 5 * gb)))
    }
}
