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
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$modelId/resolve/main/$filename"

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
