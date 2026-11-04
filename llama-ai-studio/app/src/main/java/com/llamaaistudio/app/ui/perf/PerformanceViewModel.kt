package com.llamaaistudio.app.ui.perf

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.datastore.PerformanceProfile
import com.llamaaistudio.app.data.datastore.StudioSettings
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.LlamaNative
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BenchmarkResult(
    val promptTps: Double,
    val genTps: Double,
    val promptTokens: Int,
    val genTokens: Int,
    val backend: String,
)

data class PerfUiState(
    val availableMemoryBytes: Long = 0,
    val totalMemoryBytes: Long = 0,
    val thermalHeadroom: Float? = null,
    val recommendedThreads: Int = LlamaNative.nativeThreadCountRecommendation(),
    val vulkanAvailable: Boolean = LlamaNative.supportsVulkan(),
    val isBenchmarking: Boolean = false,
    val lastResult: BenchmarkResult? = null,
    val error: String? = null,
)

private const val BENCH_PROMPT = "Explain, in detail, how a transformer neural network's " +
    "self-attention mechanism works, including the role of queries, keys, and values."

class PerformanceViewModel(private val app: LlamaAiStudioApp) : ViewModel() {

    private val _uiState = MutableStateFlow(PerfUiState())
    val uiState: StateFlow<PerfUiState> = _uiState

    val engineState = app.llamaEngine.state

    val settings: StateFlow<StudioSettings?> = app.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refreshSystemStats()
        viewModelScope.launch {
            while (isActive) {
                refreshSystemStats()
                delay(3000)
            }
        }
    }

    private fun refreshSystemStats() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val headroom = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) pm?.getThermalHeadroom(60) else null
        } catch (_: Exception) {
            null
        }
        _uiState.value = _uiState.value.copy(
            availableMemoryBytes = app.modelRepository.availableMemoryBytes(),
            totalMemoryBytes = app.modelRepository.totalMemoryBytes(),
            thermalHeadroom = headroom?.takeIf { !it.isNaN() },
        )
    }

    fun setProfile(profile: PerformanceProfile) {
        viewModelScope.launch { app.settingsRepository.setProfile(profile) }
    }

    fun setThreads(threads: Int) {
        viewModelScope.launch { app.settingsRepository.setThreads(threads) }
    }

    fun setContextLength(length: Int) {
        viewModelScope.launch { app.settingsRepository.setContextLength(length) }
    }

    fun runBenchmark() {
        if (app.llamaEngine.state.value !is EngineState.Ready) {
            _uiState.value = _uiState.value.copy(error = "Load a model first (Chat or Models tab)")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBenchmarking = true, error = null)
            var promptTps = 0.0
            var genTps = 0.0
            var promptTokens = 0
            var genTokens = 0
            app.llamaEngine.generate(
                listOf(ChatTurn("user", BENCH_PROMPT)),
                GenerationParams(maxTokens = 128, temperature = 0.0f),
            ).collect { event ->
                if (event is GenerationEvent.Done) {
                    promptTps = event.stats.promptTps
                    genTps = event.stats.genTps
                    promptTokens = event.stats.promptTokens
                    genTokens = event.stats.genTokens
                } else if (event is GenerationEvent.Failed) {
                    _uiState.value = _uiState.value.copy(error = event.reason)
                }
            }
            _uiState.value = _uiState.value.copy(
                isBenchmarking = false,
                lastResult = BenchmarkResult(promptTps, genTps, promptTokens, genTokens, "CPU (NEON" +
                    (if (LlamaNative.supportsVulkan()) " + Vulkan available)" else ", KleidiAI where applicable)")),
            )
        }
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PerformanceViewModel(app) as T
    }
}
