package com.llamaaistudio.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.CpuTopology
import com.llamaaistudio.app.engine.ModelLoadParams
import com.llamaaistudio.imagegen.ImageGenParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "studio_settings")

enum class PerformanceProfile { BATTERY_SAVER, BALANCED, MAX_PERFORMANCE }

/** Where the chat model runs. GPU_AND_CPU offloads [StudioSettings.gpuLayers] layers via Vulkan. */
enum class ComputeMode { CPU_ONLY, GPU_AND_CPU }

/** Sentinel for "offload every layer". */
const val ALL_GPU_LAYERS = -1

/** Sentinel for "as many layers as fit the GPU's free memory; the rest on the CPU". */
const val AUTO_GPU_LAYERS = -2

data class StudioSettings(
    val threads: Int,
    /** Threads for prompt processing (compute-bound, so it can use the efficiency cores too). */
    val threadsBatch: Int,
    val computeMode: ComputeMode,
    /** Layers on the GPU in GPU_AND_CPU mode; [ALL_GPU_LAYERS] = all, [AUTO_GPU_LAYERS] = fit. */
    val gpuLayers: Int,
    /** Hardware for LiteRT-LM (.litertlm) models. */
    val liteRtBackend: com.llamaaistudio.app.engine.LiteRtBackend,
    val contextLength: Int,
    val profile: PerformanceProfile,
    val useMlock: Boolean,
    val lastChatSessionId: Long?,
    val chatParams: GenerationParams,
    val imageParams: ImageGenParams,
) {
    /** Exactly what a model load should use, derived from these settings. */
    fun loadParams(): ModelLoadParams = ModelLoadParams(
        contextLength = contextLength,
        threads = threads,
        threadsBatch = threadsBatch,
        gpuLayers = if (computeMode == ComputeMode.GPU_AND_CPU) gpuLayers else 0,
        useMlock = useMlock,
    )
}

/**
 * Persists user-tunable inference/performance settings, the last-open chat session, and the
 * last-used generation parameters across app restarts (Jetpack DataStore) — so closing and
 * reopening the app returns you to where you left off instead of resetting to defaults. The
 * last-*used model* is tracked separately (LocalModelEntity/LocalImageModelEntity.lastUsedAt
 * in Room) since that's already there for the model library's own "recently used" sorting.
 */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val THREADS = intPreferencesKey("threads")
        val THREADS_BATCH = intPreferencesKey("threads_batch")
        val COMPUTE_MODE = stringPreferencesKey("compute_mode")
        val GPU_LAYERS = intPreferencesKey("gpu_layers")
        val LITERT_BACKEND = stringPreferencesKey("litert_backend")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val PROFILE = stringPreferencesKey("perf_profile")
        val USE_MLOCK = booleanPreferencesKey("use_mlock")
        val LAST_CHAT_SESSION_ID = longPreferencesKey("last_chat_session_id")

        val CHAT_TEMPERATURE = floatPreferencesKey("chat_temperature")
        val CHAT_TOP_K = intPreferencesKey("chat_top_k")
        val CHAT_TOP_P = floatPreferencesKey("chat_top_p")
        val CHAT_MIN_P = floatPreferencesKey("chat_min_p")
        val CHAT_REPEAT_PENALTY = floatPreferencesKey("chat_repeat_penalty")
        val CHAT_MAX_TOKENS = intPreferencesKey("chat_max_tokens")

        val IMAGE_STEPS = intPreferencesKey("image_steps")
        val IMAGE_CFG_SCALE = floatPreferencesKey("image_cfg_scale")
        val IMAGE_WIDTH = intPreferencesKey("image_width")
        val IMAGE_HEIGHT = intPreferencesKey("image_height")
    }

    val settings: Flow<StudioSettings> = context.settingsDataStore.data.map { prefs ->
        val defaultChat = GenerationParams()
        val defaultImage = ImageGenParams()
        StudioSettings(
            threads = prefs[Keys.THREADS] ?: CpuTopology.performanceCores,
            threadsBatch = prefs[Keys.THREADS_BATCH] ?: CpuTopology.totalCores,
            computeMode = prefs[Keys.COMPUTE_MODE]?.let { runCatching { ComputeMode.valueOf(it) }.getOrNull() }
                ?: ComputeMode.CPU_ONLY,
            gpuLayers = prefs[Keys.GPU_LAYERS] ?: AUTO_GPU_LAYERS,
            liteRtBackend = prefs[Keys.LITERT_BACKEND]?.let {
                runCatching { com.llamaaistudio.app.engine.LiteRtBackend.valueOf(it) }.getOrNull()
            } ?: com.llamaaistudio.app.engine.LiteRtBackend.GPU,
            contextLength = prefs[Keys.CONTEXT_LENGTH] ?: 4096,
            profile = prefs[Keys.PROFILE]?.let { runCatching { PerformanceProfile.valueOf(it) }.getOrNull() }
                ?: PerformanceProfile.BALANCED,
            useMlock = prefs[Keys.USE_MLOCK] ?: false,
            lastChatSessionId = prefs[Keys.LAST_CHAT_SESSION_ID],
            chatParams = GenerationParams(
                maxTokens = prefs[Keys.CHAT_MAX_TOKENS] ?: defaultChat.maxTokens,
                temperature = prefs[Keys.CHAT_TEMPERATURE] ?: defaultChat.temperature,
                topK = prefs[Keys.CHAT_TOP_K] ?: defaultChat.topK,
                topP = prefs[Keys.CHAT_TOP_P] ?: defaultChat.topP,
                minP = prefs[Keys.CHAT_MIN_P] ?: defaultChat.minP,
                repeatPenalty = prefs[Keys.CHAT_REPEAT_PENALTY] ?: defaultChat.repeatPenalty,
            ),
            imageParams = ImageGenParams(
                width = prefs[Keys.IMAGE_WIDTH] ?: defaultImage.width,
                height = prefs[Keys.IMAGE_HEIGHT] ?: defaultImage.height,
                steps = prefs[Keys.IMAGE_STEPS] ?: defaultImage.steps,
                cfgScale = prefs[Keys.IMAGE_CFG_SCALE] ?: defaultImage.cfgScale,
            ),
        )
    }

    suspend fun setThreads(threads: Int) {
        context.settingsDataStore.edit { it[Keys.THREADS] = threads }
    }

    suspend fun setContextLength(length: Int) {
        context.settingsDataStore.edit { it[Keys.CONTEXT_LENGTH] = length }
    }

    /**
     * Profiles, tuned for big.LITTLE phones like Tensor G4 (1 + 3 fast cores, 4 efficiency):
     * - Battery saver: half the fast cores, CPU only, 2K context.
     * - Balanced: the fast cores for both phases, CPU only, 4K context.
     * - Max performance: fast cores for generation, every core for prompt processing, and the
     *   GPU (Vulkan) on top when this device has one — all layers offloaded, 8K context.
     */
    suspend fun setProfile(profile: PerformanceProfile, gpuAvailable: Boolean = false) {
        val perf = CpuTopology.performanceCores
        val all = CpuTopology.totalCores
        context.settingsDataStore.edit {
            it[Keys.PROFILE] = profile.name
            when (profile) {
                PerformanceProfile.BATTERY_SAVER -> {
                    it[Keys.THREADS] = (perf / 2).coerceAtLeast(2)
                    it[Keys.THREADS_BATCH] = (perf / 2).coerceAtLeast(2)
                    it[Keys.CONTEXT_LENGTH] = 2048
                    it[Keys.COMPUTE_MODE] = ComputeMode.CPU_ONLY.name
                }
                PerformanceProfile.BALANCED -> {
                    it[Keys.THREADS] = perf
                    it[Keys.THREADS_BATCH] = perf
                    it[Keys.CONTEXT_LENGTH] = 4096
                    it[Keys.COMPUTE_MODE] = ComputeMode.CPU_ONLY.name
                }
                PerformanceProfile.MAX_PERFORMANCE -> {
                    it[Keys.THREADS] = perf
                    it[Keys.THREADS_BATCH] = all
                    it[Keys.CONTEXT_LENGTH] = 8192
                    it[Keys.COMPUTE_MODE] = (if (gpuAvailable) ComputeMode.GPU_AND_CPU else ComputeMode.CPU_ONLY).name
                    it[Keys.GPU_LAYERS] = AUTO_GPU_LAYERS
                }
            }
        }
    }

    suspend fun setThreadsBatch(threads: Int) {
        context.settingsDataStore.edit { it[Keys.THREADS_BATCH] = threads }
    }

    suspend fun setComputeMode(mode: ComputeMode) {
        context.settingsDataStore.edit { it[Keys.COMPUTE_MODE] = mode.name }
    }

    suspend fun setLiteRtBackend(backend: com.llamaaistudio.app.engine.LiteRtBackend) {
        context.settingsDataStore.edit { it[Keys.LITERT_BACKEND] = backend.name }
    }

    suspend fun setGpuLayers(layers: Int) {
        context.settingsDataStore.edit { it[Keys.GPU_LAYERS] = layers }
    }

    suspend fun setUseMlock(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_MLOCK] = enabled }
    }

    suspend fun setLastChatSessionId(id: Long) {
        context.settingsDataStore.edit { it[Keys.LAST_CHAT_SESSION_ID] = id }
    }

    suspend fun setChatParams(params: GenerationParams) {
        context.settingsDataStore.edit {
            it[Keys.CHAT_MAX_TOKENS] = params.maxTokens
            it[Keys.CHAT_TEMPERATURE] = params.temperature
            it[Keys.CHAT_TOP_K] = params.topK
            it[Keys.CHAT_TOP_P] = params.topP
            it[Keys.CHAT_MIN_P] = params.minP
            it[Keys.CHAT_REPEAT_PENALTY] = params.repeatPenalty
        }
    }

    suspend fun setImageParams(params: ImageGenParams) {
        context.settingsDataStore.edit {
            it[Keys.IMAGE_WIDTH] = params.width
            it[Keys.IMAGE_HEIGHT] = params.height
            it[Keys.IMAGE_STEPS] = params.steps
            it[Keys.IMAGE_CFG_SCALE] = params.cfgScale
        }
    }
}
