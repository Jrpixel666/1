package com.llamaaistudio.app.data.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtFilesTest {
    @Test
    fun keepsGenericBuildsGpuFirstAndDropsChipSpecificOnes() {
        // Real file list of litert-community/gemma-4-E2B-it-litert-lm.
        val names = listOf(
            "gemma-4-E2B-it-gpu.litertlm" to 1_870_000_000L, "gemma-4-E2B-it-web.litertlm" to 1_870_000_000L,
            "gemma-4-E2B-it.litertlm" to 2_410_000_000L, "gemma-4-E2B-it_Google_Tensor_G5.litertlm" to 2_900_000_000L,
            "gemma-4-E2B-it_NPU_Uncompiled.litertlm" to 2_440_000_000L, "gemma-4-E2B-it_Qualcomm_QCS8275.litertlm" to 2_540_000_000L,
            "gemma-4-E2B-it_intel_LNL.litertlm" to 2_760_000_000L, "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm" to 690_000_000L,
            "Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm" to 1_030_000_000L, "README.md" to 10L,
        )
        val detail = HfModelDetail(
            id = "litert-community/gemma-4-E2B-it-litert-lm",
            siblings = names.map { (n, size) -> HfSibling(n, size, HfSiblingLfs(null, size)) },
            gated = false,
        )
        val files = detail.litertFiles()
        assertEquals(listOf("gemma-4-E2B-it-gpu.litertlm", "gemma-4-E2B-it.litertlm"), files.map { it.filename })
        assertTrue(files.first().quantization!!.contains("GPU build"))
        assertTrue(files.all { it.isLiteRt })
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm",
            files.first().downloadUrl,
        )
    }

    @Test
    fun marksGatedRepos() {
        val detail = HfModelDetail(
            id = "litert-community/Gemma3-1B-IT",
            siblings = listOf(HfSibling("gemma3-1b-it-int4.litertlm", 1, HfSiblingLfs(null, 584_417_280))),
            gated = "auto",
        )
        assertTrue(detail.litertFiles().single().quantization!!.contains("gated"))
    }
}
