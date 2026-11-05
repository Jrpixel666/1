package com.llamaaistudio.app.data.cloud

import android.content.Context
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.GenerationStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One OpenCode Zen model, as listed by https://opencode.ai/zen/v1/models. */
data class CloudModel(
    val id: String,
    val name: String,
    val description: String,
    val contextLength: Int,
    val free: Boolean,
    /** Accepts images as input (vision). No Zen model *generates* images. */
    val acceptsImages: Boolean,
    val chatBaseUrl: String?,
) {
    val usable: Boolean get() = chatBaseUrl != null
}

/** Which cloud model a workspace uses (persisted). */
data class CloudSelection(val id: String, val name: String, val acceptsImages: Boolean)

/**
 * OpenCode Zen (opencode.ai) as an optional cloud backend: the user's API key, the catalogue
 * of *free* models, streamed chat completions (OpenAI-compatible) and vision requests.
 *
 * The key is kept encrypted by [SecureKeyStore] and only ever sent to opencode.ai.
 */
class OpenCodeRepository(context: Context, httpClient: OkHttpClient) {

    private val keys = SecureKeyStore(context)
    private val prefs = context.getSharedPreferences("opencode", Context.MODE_PRIVATE)

    private val client = httpClient.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS) // reasoning models can think a while before tokens flow
        .build()

    private val _hasKey = MutableStateFlow(keys.get(KEY_NAME) != null)
    val hasKey: StateFlow<Boolean> = _hasKey

    private val _chatModel = MutableStateFlow(loadSelection("chat"))
    /** Cloud model used by Chat, or null = use the local (on-device) model. */
    val chatModel: StateFlow<CloudSelection?> = _chatModel

    private val _codeModel = MutableStateFlow(loadSelection("code"))
    /** Model Dev Studio's Project Builder / Build with AI uses (null = same as Chat). */
    val codeModel: StateFlow<CloudSelection?> = _codeModel

    fun selectCodeModel(model: CloudModel?) {
        val sel = model?.let { CloudSelection(it.id, it.name, it.acceptsImages) }
        saveSelection("code", sel)
        _codeModel.value = sel
    }

    /** Every chat-completions model, including paid ones (billed to the user's key). */
    suspend fun allModels(force: Boolean = false): List<CloudModel> {
        val all = cachedModels?.takeUnless { force } ?: fetchModels().also { cachedModels = it }
        return all.filter { it.usable }
    }

    private val _visionModel = MutableStateFlow(loadSelection("vision"))
    /** Free vision model the Image workspace uses to understand pictures / write prompts. */
    val visionModel: StateFlow<CloudSelection?> = _visionModel

    @Volatile
    private var cachedModels: List<CloudModel>? = null

    // --- key ---------------------------------------------------------------------------------

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return clearApiKey()
        keys.put(KEY_NAME, trimmed)
        _hasKey.value = true
    }

    fun clearApiKey() {
        keys.remove(KEY_NAME)
        _hasKey.value = false
    }

    /** e.g. "sk-…a1b2" for display; never the full key. */
    fun maskedKey(): String? = keys.get(KEY_NAME)?.let { k ->
        if (k.length <= 8) "••••" else k.take(3) + "…" + k.takeLast(4)
    }

    // --- selection ---------------------------------------------------------------------------

    fun selectChatModel(model: CloudModel?) {
        val sel = model?.let { CloudSelection(it.id, it.name, it.acceptsImages) }
        saveSelection("chat", sel)
        _chatModel.value = sel
    }

    fun selectVisionModel(model: CloudModel?) {
        val sel = model?.let { CloudSelection(it.id, it.name, it.acceptsImages) }
        saveSelection("vision", sel)
        _visionModel.value = sel
    }

    private fun loadSelection(slot: String): CloudSelection? {
        val id = prefs.getString("${slot}_id", null) ?: return null
        return CloudSelection(id, prefs.getString("${slot}_name", id) ?: id, prefs.getBoolean("${slot}_images", false))
    }

    private fun saveSelection(slot: String, sel: CloudSelection?) {
        prefs.edit().apply {
            if (sel == null) {
                remove("${slot}_id"); remove("${slot}_name"); remove("${slot}_images")
            } else {
                putString("${slot}_id", sel.id); putString("${slot}_name", sel.name); putBoolean("${slot}_images", sel.acceptsImages)
            }
        }.apply()
    }

    // --- catalogue ---------------------------------------------------------------------------

    /** Free, chat-completions-capable models (optionally only those that accept images). */
    suspend fun freeModels(force: Boolean = false, imagesOnly: Boolean = false): List<CloudModel> {
        val all = cachedModels?.takeUnless { force } ?: fetchModels().also { cachedModels = it }
        return all.filter { it.free && it.usable && (!imagesOnly || it.acceptsImages) }
    }

    private suspend fun fetchModels(): List<CloudModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(MODELS_URL).apply { authHeader()?.let { header("Authorization", it) } }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(errorMessage(resp))
            parseModels(resp.body?.string().orEmpty())
        }
    }

    // --- chat --------------------------------------------------------------------------------

    /** Streams a chat completion from [modelId]; [imageJpeg] attaches a picture to the last user turn. */
    fun chat(
        modelId: String,
        messages: List<ChatTurn>,
        params: GenerationParams = GenerationParams(),
        imageJpeg: ByteArray? = null,
    ): Flow<GenerationEvent> = callbackFlow {
        val key = keys.get(KEY_NAME)
        if (key == null) {
            trySend(GenerationEvent.Failed("Add your OpenCode API key in Settings first"))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val baseUrl = cachedModels?.firstOrNull { it.id == modelId }?.chatBaseUrl ?: DEFAULT_CHAT_BASE
        val body = buildChatBody(modelId, messages, params, imageJpeg)
        val call = client.newCall(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        )
        launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            var firstTokenAt = 0L
            var tokens = 0
            var promptTokens = 0
            var completionTokens = 0
            var finish = "eos"
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        trySend(GenerationEvent.Failed(errorMessage(resp)))
                        return@use
                    }
                    val source = resp.body?.source() ?: throw IOException("Empty response")
                    val parsed = parseChatStream(generateSequence { source.readUtf8Line() }.iterator()) { piece ->
                        if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                        tokens++
                        trySend(GenerationEvent.Token(piece))
                    }
                    parsed.error?.let { err ->
                        trySend(GenerationEvent.Failed(err))
                        return@use
                    }
                    promptTokens = parsed.promptTokens
                    completionTokens = parsed.completionTokens
                    finish = parsed.finish
                    val end = System.currentTimeMillis()
                    val genMs = (end - (if (firstTokenAt > 0) firstTokenAt else end)).toDouble()
                    val genTokens = if (completionTokens > 0) completionTokens else tokens
                    trySend(
                        GenerationEvent.Done(
                            GenerationStats(
                                promptTokens = promptTokens,
                                genTokens = genTokens,
                                prefillMs = ((if (firstTokenAt > 0) firstTokenAt else end) - start).toDouble(),
                                genMs = genMs,
                                promptTps = 0.0,
                                genTps = if (genMs > 0) genTokens / (genMs / 1000.0) else 0.0,
                                stopReason = finish,
                            )
                        )
                    )
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) trySend(GenerationEvent.Failed(e.message ?: "Network error"))
            } finally {
                close()
            }
        }
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Non-streaming convenience: the full reply text, or a failure. */
    suspend fun complete(
        modelId: String,
        messages: List<ChatTurn>,
        params: GenerationParams = GenerationParams(maxTokens = 600, temperature = 0.4f),
        imageJpeg: ByteArray? = null,
    ): Result<String> {
        val sb = StringBuilder()
        var failure: String? = null
        chat(modelId, messages, params, imageJpeg).collect { e ->
            when (e) {
                is GenerationEvent.Token -> sb.append(e.piece)
                is GenerationEvent.Failed -> failure = e.reason
                is GenerationEvent.Done -> {}
            }
        }
        return failure?.let { Result.failure(IOException(it)) } ?: Result.success(sb.toString().trim())
    }

    /** Quick check that the key works with a free model. */
    suspend fun testKey(): Result<String> {
        val model = runCatching { freeModels(force = true).firstOrNull() }.getOrNull()
            ?: return Result.failure(IOException("Couldn't load OpenCode's model list"))
        return complete(model.id, listOf(ChatTurn("user", "Reply with exactly: OK")), GenerationParams(maxTokens = 400, temperature = 0f))
            .map { reply -> "${model.name} replied: ${reply.take(60).ifBlank { "(empty)" }}" }
    }

    private fun authHeader(): String? = keys.get(KEY_NAME)?.let { "Bearer $it" }

    companion object {
        private const val KEY_NAME = "opencode_api_key"
        const val MODELS_URL = "https://opencode.ai/zen/v1/models"
        const val DEFAULT_CHAT_BASE = "https://opencode.ai/zen/v1"
        const val KEY_URL = "https://opencode.ai/auth"

        /** Parses the Zen catalogue. Pure (org.json only), so it's unit-testable. */
        fun parseModels(json: String): List<CloudModel> {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val m = data.optJSONObject(i) ?: return@mapNotNull null
                if (m.optString("type", "chat") != "chat") return@mapNotNull null
                val inputs = m.optJSONObject("modalities")?.optJSONArray("input")
                val acceptsImages = inputs != null && (0 until inputs.length()).any { inputs.optString(it).startsWith("image") }
                CloudModel(
                    id = m.getString("id"),
                    name = m.optString("name", m.getString("id")),
                    description = m.optString("description"),
                    contextLength = m.optJSONObject("limit")?.optInt("context", 0) ?: 0,
                    free = isFree(m.optJSONObject("cost")),
                    acceptsImages = acceptsImages,
                    chatBaseUrl = m.optJSONObject("api")?.optJSONObject("chat-completions")?.optString("base_url")
                        ?.takeIf { it.startsWith("https://") },
                )
            }
        }

        /** Free = the catalogue lists prices and every one of them is 0. */
        internal fun isFree(cost: JSONObject?): Boolean {
            cost ?: return false
            val prices = mutableListOf<Double>()
            fun walk(o: Any?) {
                when (o) {
                    is JSONObject -> o.keys().forEach { k -> if (k == "price") prices += o.optDouble(k, 1.0) else walk(o.opt(k)) }
                    is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
                }
            }
            walk(cost)
            return prices.isNotEmpty() && prices.all { it == 0.0 }
        }

        internal fun buildChatBody(
            modelId: String,
            messages: List<ChatTurn>,
            params: GenerationParams,
            imageJpeg: ByteArray?,
        ): JSONObject {
            val lastUser = messages.indexOfLast { it.role == "user" }
            val arr = JSONArray()
            messages.forEachIndexed { i, turn ->
                val msg = JSONObject().put("role", turn.role)
                if (i == lastUser && imageJpeg != null) {
                    val url = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(imageJpeg)
                    msg.put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", turn.content))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))),
                    )
                } else {
                    msg.put("content", turn.content)
                }
                arr.put(msg)
            }
            return JSONObject()
                .put("model", modelId)
                .put("messages", arr)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
                .put("max_tokens", params.maxTokens)
                .put("temperature", params.temperature.toDouble())
                .put("top_p", params.topP.toDouble())
        }

        data class StreamResult(
            val error: String? = null,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val finish: String = "eos",
        )

        /**
         * Parses an OpenAI-style chat completion response: SSE `data:` chunks (content deltas,
         * reasoning deltas — skipped, a final usage chunk, `[DONE]`), or — if the server didn't
         * stream — a single JSON completion or error. Content is handed to [onContent] as it comes.
         */
        fun parseChatStream(lines: Iterator<String>, onContent: (String) -> Unit): StreamResult {
            var sawData = false
            val other = StringBuilder()
            var promptTokens = 0
            var completionTokens = 0
            var finish = "eos"
            while (lines.hasNext()) {
                val line = lines.next()
                if (!line.startsWith("data:")) {
                    if (!sawData && other.length < 64_000) other.append(line).append('\n')
                    continue
                }
                sawData = true
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                obj.optJSONObject("error")?.let { err ->
                    return StreamResult(error = err.optString("message", "OpenCode error"))
                }
                obj.optJSONObject("usage")?.let { u ->
                    promptTokens = u.optInt("prompt_tokens", promptTokens)
                    completionTokens = u.optInt("completion_tokens", completionTokens)
                }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val fr = choice.optString("finish_reason")
                if (fr.isNotEmpty() && fr != "null") finish = if (fr == "length") "length" else "eos"
                val delta = choice.optJSONObject("delta") ?: continue
                if (delta.isNull("content")) continue
                val piece = delta.optString("content")
                if (piece.isNotEmpty()) onContent(piece)
            }
            if (!sawData) {
                val obj = runCatching { JSONObject(other.toString()) }.getOrNull()
                val err = obj?.optJSONObject("error")?.optString("message")
                if (!err.isNullOrBlank()) return StreamResult(error = err)
                val content = obj?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (content.isNullOrEmpty()) return StreamResult(error = "OpenCode returned no reply")
                onContent(content)
                obj.optJSONObject("usage")?.let { u ->
                    promptTokens = u.optInt("prompt_tokens", 0)
                    completionTokens = u.optInt("completion_tokens", 0)
                }
            }
            return StreamResult(null, promptTokens, completionTokens, finish)
        }

        internal fun errorMessage(resp: Response): String {
            val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            return when {
                !msg.isNullOrBlank() -> msg
                resp.code == 401 -> "OpenCode rejected the API key (401) — check it in Settings"
                else -> "OpenCode error ${resp.code}"
            }
        }
    }
}
