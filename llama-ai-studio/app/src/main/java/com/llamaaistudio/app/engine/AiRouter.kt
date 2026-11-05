package com.llamaaistudio.app.engine

import com.llamaaistudio.app.data.cloud.OpenCodeRepository
import kotlinx.coroutines.flow.Flow

/**
 * One entry point for "ask the AI": the OpenCode cloud model when one is selected (and a key is
 * set), otherwise the on-device llama.cpp model. Used by Chat, Web Studio and Dev Studio.
 */
class AiRouter(
    private val local: LlamaEngine,
    private val cloud: OpenCodeRepository,
) {
    /** The cloud model in use, or null when the local model is used. */
    val cloudModelName: String?
        get() = cloud.chatModel.value?.takeIf { cloud.hasKey.value }?.name

    val isAvailable: Boolean
        get() = cloudModelName != null || local.isLoaded

    /** Label for where the answer comes from, e.g. "OpenCode · Big Pickle" or "on-device". */
    val sourceLabel: String
        get() = cloudModelName?.let { "OpenCode · $it" } ?: "on-device model"

    fun generate(messages: List<ChatTurn>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent> {
        val sel = cloud.chatModel.value
        return if (sel != null && cloud.hasKey.value) cloud.chat(sel.id, messages, params)
        else local.generate(messages, params)
    }

    /** Where a chat session's reply came from, stored on the session ("opencode:<id>" or a GGUF path). */
    fun currentModelTag(): String? =
        cloud.chatModel.value?.takeIf { cloud.hasKey.value }?.let { "opencode:${it.id}" } ?: local.currentModelPath()
}
