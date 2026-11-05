package com.llamaaistudio.app.data.hf

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HfModelSummary(
    val id: String,
    val modelId: String? = null,
    val author: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    val tags: List<String>? = null,
    @Json(name = "pipeline_tag") val pipelineTag: String? = null,
)

@JsonClass(generateAdapter = true)
data class HfSiblingLfs(
    val sha256: String? = null,
    val size: Long? = null,
)

@JsonClass(generateAdapter = true)
data class HfSibling(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HfSiblingLfs? = null,
)

@JsonClass(generateAdapter = true)
data class HfGgufMeta(
    val total: Long? = null,
    val architecture: String? = null,
    @Json(name = "context_length") val contextLength: Int? = null,
    @Json(name = "chat_template") val chatTemplate: String? = null,
)

@JsonClass(generateAdapter = true)
data class HfModelDetail(
    val id: String,
    val author: String? = null,
    val tags: List<String>? = null,
    val siblings: List<HfSibling>? = null,
    val gguf: HfGgufMeta? = null,
    /** false, or "auto"/"manual" when the repo needs its licence accepted on huggingface.co. */
    val gated: Any? = null,
)

/** One downloadable GGUF file, flattened out of [HfModelDetail.siblings] for the UI. */
data class GgufFileOption(
    val modelId: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String?,
    val quantization: String?,
    val paramCount: Long?,
    val contextLengthTrained: Int?,
    /** Path inside the repo when it differs from [filename] (files in subfolders). */
    val path: String = filename,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$modelId/resolve/main/$path"

    val isLiteRt: Boolean get() = filename.endsWith(".litertlm", ignoreCase = true)

    fun estimatedRamBytes(): Long = (sizeBytes * 1.15).toLong() + 256L * 1024 * 1024
}

private val QUANT_IN_FILENAME = Regex("(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z]+|F16|F32|BF16)", RegexOption.IGNORE_CASE)

fun HfModelDetail.ggufFiles(): List<GgufFileOption> =
    siblings.orEmpty()
        .filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
        .map { sib ->
            GgufFileOption(
                modelId = id,
                filename = sib.rfilename,
                sizeBytes = sib.lfs?.size ?: sib.size ?: 0L,
                sha256 = sib.lfs?.sha256,
                quantization = QUANT_IN_FILENAME.find(sib.rfilename)?.value?.uppercase(),
                paramCount = gguf?.total,
                contextLengthTrained = gguf?.contextLength,
            )
        }
        .sortedBy { it.sizeBytes }

/**
 * Builds compiled for one specific NPU (Tensor G5/G6, Qualcomm, MediaTek, Intel…) or for the
 * web don't run on this phone's GPU/CPU — and there are none for Tensor G4 (Pixel 9).
 */
private val LITERT_TARGET_SPECIFIC = Regex(
    "(Tensor_G\\d|Qualcomm|_QCS\\d|_sm\\d{4}|_mt\\d{4}|intel_|NPU_Uncompiled|-web\\.)",
    RegexOption.IGNORE_CASE,
)

/** Generic `.litertlm` files for LiteRT-LM, GPU builds first (same download flow as GGUF). */
fun HfModelDetail.litertFiles(): List<GgufFileOption> {
    val isGated = gated != null && gated != false && gated.toString() != "false"
    return siblings.orEmpty()
        .filter { it.rfilename.endsWith(".litertlm", ignoreCase = true) && !LITERT_TARGET_SPECIFIC.containsMatchIn(it.rfilename) }
        .map { sib ->
            val name = sib.rfilename.substringAfterLast('/')
            val gpu = name.contains("-gpu", ignoreCase = true)
            GgufFileOption(
                modelId = id,
                filename = name,
                sizeBytes = sib.lfs?.size ?: sib.size ?: 0L,
                sha256 = sib.lfs?.sha256,
                quantization = buildList {
                    add(if (gpu) "LiteRT · GPU build" else "LiteRT")
                    Regex("(int4|int8|q4|q8|f16|f32)", RegexOption.IGNORE_CASE).find(name)?.value?.lowercase()?.let(::add)
                    if (isGated) add("gated: accept licence on huggingface.co first")
                }.joinToString(" · "),
                paramCount = null,
                contextLengthTrained = null,
                path = sib.rfilename,
            )
        }
        .sortedWith(compareBy<GgufFileOption>({ !it.filename.contains("-gpu", true) }, { it.sizeBytes }))
}
