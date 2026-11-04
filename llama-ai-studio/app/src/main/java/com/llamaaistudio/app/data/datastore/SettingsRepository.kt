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
import com.llamaaistudio.app.engine.LlamaNative
import com.llamaaistudio.imagegen.ImageGenParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "studio_settings")

enum class PerformanceProfile { BATTERY_SAVER, BALANCED, MAX_PERFORMANCE }

data class StudioSettings(
    val threads: Int,
    val contextLength: Int,
    val profile: PerformanceProfile,
    val useMlock: Boolean,
    val lastChatSessionId: Long?,
    val chatParams: GenerationParams,
    val imageParams: ImageGenParams,
)

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
            threads = prefs[Keys.THREADS] ?: LlamaNative.nativeThreadCountRecommendation(),
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

    suspend fun setProfile(profile: PerformanceProfile) {
        context.settingsDataStore.edit { it[Keys.PROFILE] = profile.name }
        when (profile) {
            PerformanceProfile.BATTERY_SAVER -> {
                setThreads((LlamaNative.nativeThreadCountRecommendation() / 2).coerceAtLeast(2))
                setContextLength(2048)
            }
            PerformanceProfile.BALANCED -> {
                setThreads(LlamaNative.nativeThreadCountRecommendation())
                setContextLength(4096)
            }
            PerformanceProfile.MAX_PERFORMANCE -> {
                setThreads(LlamaNative.nativeThreadCountRecommendation() + 1)
                setContextLength(8192)
            }
        }
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
