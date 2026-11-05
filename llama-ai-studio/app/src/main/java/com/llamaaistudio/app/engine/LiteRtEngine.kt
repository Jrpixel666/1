@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.llamaaistudio.app.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Hardware LiteRT-LM runs the model on. */
enum class LiteRtBackend { GPU, CPU, NPU }

/**
 * Google's LiteRT-LM runtime for `.litertlm` models (Gemma, Qwen, Phi… from the LiteRT
 * community on Hugging Face), with GPU (OpenCL), CPU or NPU acceleration. A second on-device
 * engine next to llama.cpp; [AiRouter] uses whichever has a model loaded.
 */
class LiteRtEngine(
    private val cacheDir: File,
    private val nativeLibraryDir: String,
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "litertlm") }.asCoroutineDispatcher(),
) {
    data class Loaded(val path: String, val backend: LiteRtBackend, val name: String)

    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state

    @Volatile
    var loaded: Loaded? = null
        private set

    val isLoaded: Boolean get() = loaded != null

    suspend fun loadModel(path: String, backend: LiteRtBackend, maxTokens: Int, threads: Int): Result<Loaded> =
        withContext(dispatcher) {
            unloadInternal()
            _state.value = EngineState.Loading
            runCatching {
                val be = when (backend) {
                    LiteRtBackend.GPU -> Backend.GPU()
                    LiteRtBackend.CPU -> Backend.CPU(threads, null)
                    LiteRtBackend.NPU -> Backend.NPU(nativeLibraryDir)
                }
                cacheDir.mkdirs()
                val e = Engine(EngineConfig(path, be, null, null, maxTokens, null, cacheDir.absolutePath))
                e.initialize()
                engine = e
                val name = File(path).nameWithoutExtension
                Loaded(path, backend, name).also {
                    loaded = it
                    _state.value = EngineState.Ready(
                        path,
                        ModelInfo(
                            desc = "$name · LiteRT ${backend.name}",
                            paramCount = 0, sizeBytes = File(path).length(),
                            contextLengthTrained = maxTokens, vocabSize = 0, hasChatTemplate = true,
                        ),
                    )
                }
            }.onFailure { e ->
                unloadInternal()
                _state.value = EngineState.Error(
                    "LiteRT-LM couldn't load this model on the ${backend.name}: ${e.message ?: e}. " +
                        if (backend != LiteRtBackend.CPU) "Try the CPU backend in Settings." else ""
                )
            }
        }

    suspend fun unload() = withContext(dispatcher) {
        unloadInternal()
        _state.value = EngineState.Idle
    }

    private fun unloadInternal() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loaded = null
    }

    /**
     * One reply. The history is replayed as the conversation's initial messages so every app
     * session (and Web/Dev Studio prompt) is independent, exactly like the llama.cpp path.
     */
    fun generate(messages: List<ChatTurn>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent> = flow {
        val e = engine
        if (e == null) {
            emit(GenerationEvent.Failed("No LiteRT-LM model loaded"))
            return@flow
        }
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val chat = messages.filter { it.role != "system" }
        val last = chat.lastOrNull { it.role == "user" }
        if (last == null) {
            emit(GenerationEvent.Failed("Nothing to answer"))
            return@flow
        }
        val history = chat.subList(0, chat.lastIndexOf(last)).map { t ->
            if (t.role == "assistant") Message.Companion.model(t.content) else Message.Companion.user(t.content)
        }
        val config = ConversationConfig(
            if (system.isBlank()) null else Contents.Companion.of(system),
            history,
            emptyList(),
            SamplerConfig(params.topK.coerceAtLeast(1), params.topP.toDouble(), params.temperature.toDouble().coerceAtLeast(0.0), 0),
        )
        val conv = e.createConversation(config)
        conversation = conv
        val start = System.currentTimeMillis()
        var firstAt = 0L
        var pieces = 0
        try {
            conv.sendMessageAsync(last.content, emptyMap(), null, null, null, params.maxTokens, null, null).collect { msg ->
                val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    pieces++
                    emit(GenerationEvent.Token(text))
                }
            }
            val end = System.currentTimeMillis()
            val genMs = (end - (if (firstAt > 0) firstAt else end)).toDouble()
            emit(
                GenerationEvent.Done(
                    GenerationStats(
                        promptTokens = 0,
                        genTokens = pieces,
                        prefillMs = ((if (firstAt > 0) firstAt else end) - start).toDouble(),
                        genMs = genMs,
                        promptTps = 0.0,
                        genTps = if (genMs > 0) pieces / (genMs / 1000) else 0.0,
                        stopReason = "eos",
                    )
                )
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                try { conv.cancelProcess() } catch (_: Throwable) {}
                throw t
            }
            emit(GenerationEvent.Failed("LiteRT-LM: ${t.message ?: t}"))
        } finally {
            try { conv.close() } catch (_: Throwable) {}
            if (conversation === conv) conversation = null
        }
    }.flowOn(dispatcher)
}
