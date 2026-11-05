package com.llamaaistudio.app.ui.perf

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.datastore.ALL_GPU_LAYERS
import com.llamaaistudio.app.data.datastore.ComputeMode
import com.llamaaistudio.app.data.datastore.PerformanceProfile
import com.llamaaistudio.app.data.datastore.StudioSettings
import com.llamaaistudio.app.engine.BackendDevice
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.CpuTopology
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.GenerationStats
import com.llamaaistudio.app.engine.LlamaEngine
import com.llamaaistudio.app.engine.ModelLoadParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BenchmarkResult(
    val promptTps: Double,
    val genTps: Double,
    val promptTokens: Int,
    val genTokens: Int,
    val backend: String,
)

/** One configuration tried by Auto-tune. */
data class TuneTrial(val label: String, val promptTps: Double, val genTps: Double, val ok: Boolean, val note: String? = null)

data class PerfUiState(
    val availableMemoryBytes: Long = 0,
    val totalMemoryBytes: Long = 0,
    val thermalHeadroom: Float? = null,
    val devices: List<BackendDevice> = emptyList(),
    val isBenchmarking: Boolean = false,
    val lastResult: BenchmarkResult? = null,
    val isApplying: Boolean = false,
    val isTuning: Boolean = false,
    val tuneStep: String? = null,
    val tuneTrials: List<TuneTrial> = emptyList(),
    val tuneSummary: String? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val gpu: BackendDevice? get() = devices.firstOrNull { it.isGpu }
}

private const val BENCH_PROMPT = "Explain, in detail, how a transformer neural network's " +
    "self-attention mechanism works, including the role of queries, keys, and values."

class PerformanceViewModel(private val app: LlamaAiStudioApp) : ViewModel() {

    private val engine: LlamaEngine = app.llamaEngine

    private val _uiState = MutableStateFlow(PerfUiState(notice = app.gpuCrashNotice))
    val uiState: StateFlow<PerfUiState> = _uiState

    val engineState = engine.state

    val settings: StateFlow<StudioSettings?> = app.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cpuDescription: String = CpuTopology.description
    val performanceCores: Int = CpuTopology.performanceCores
    val totalCores: Int = CpuTopology.totalCores

    private var tuneJob: Job? = null

    init {
        viewModelScope.launch {
            val devices = withContext(Dispatchers.Default) { LlamaEngine.devices() }
            _uiState.update { it.copy(devices = devices) }
        }
        viewModelScope.launch {
            while (isActive) {
                refreshSystemStats()
                delay(3000)
            }
        }
    }

    private fun refreshSystemStats() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val headroom = runCatching { pm?.getThermalHeadroom(60) }.getOrNull()
        _uiState.update {
            it.copy(
                availableMemoryBytes = app.modelRepository.availableMemoryBytes(),
                totalMemoryBytes = app.modelRepository.totalMemoryBytes(),
                thermalHeadroom = headroom?.takeIf { h -> !h.isNaN() },
            )
        }
    }

    fun setProfile(profile: PerformanceProfile) {
        viewModelScope.launch { app.settingsRepository.setProfile(profile, gpuAvailable = _uiState.value.gpu != null) }
    }

    /** Thread changes apply to the loaded model immediately (no reload). */
    fun setThreads(threads: Int) {
        viewModelScope.launch {
            app.settingsRepository.setThreads(threads)
            val s = app.settingsRepository.settings.first()
            engine.setThreads(s.threads, s.threadsBatch)
        }
    }

    fun setThreadsBatch(threads: Int) {
        viewModelScope.launch {
            app.settingsRepository.setThreadsBatch(threads)
            val s = app.settingsRepository.settings.first()
            engine.setThreads(s.threads, s.threadsBatch)
        }
    }

    fun setContextLength(length: Int) {
        viewModelScope.launch { app.settingsRepository.setContextLength(length) }
    }

    fun setComputeMode(mode: ComputeMode) {
        viewModelScope.launch { app.settingsRepository.setComputeMode(mode) }
    }

    fun setGpuLayers(layers: Int) {
        viewModelScope.launch { app.settingsRepository.setGpuLayers(layers) }
    }

    /** True when the loaded model's placement/context differs from the saved settings. */
    fun needsReload(s: StudioSettings?): Boolean {
        val current = engine.currentLoadParams ?: return false
        s ?: return false
        val wanted = s.loadParams()
        return current.gpuLayers != wanted.gpuLayers || current.contextLength != wanted.contextLength
    }

    /** Reloads the current model with the saved settings (CPU/GPU placement, context). */
    fun applyNow() {
        val path = engine.currentModelPath() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true, error = null) }
            val params = app.settingsRepository.settings.first().loadParams()
            val result = engine.loadModel(path, params)
            _uiState.update {
                it.copy(
                    isApplying = false,
                    error = result.exceptionOrNull()?.message,
                    notice = result.getOrNull()?.let { info ->
                        if (info.onGpu) "Loaded with ${info.gpuLayers} of ${info.nLayer + 1} layers on the GPU"
                        else "Loaded on the CPU"
                    },
                )
            }
        }
    }

    fun runBenchmark() {
        if (engine.state.value !is EngineState.Ready) {
            _uiState.update { it.copy(error = "Load a model first (Models tab)") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBenchmarking = true, error = null) }
            val stats = bench(maxTokens = 128)
            _uiState.update {
                it.copy(
                    isBenchmarking = false,
                    lastResult = stats?.let { st ->
                        BenchmarkResult(st.promptTps, st.genTps, st.promptTokens, st.genTokens, backendLabel())
                    },
                    error = if (stats == null) "Benchmark failed" else null,
                )
            }
        }
    }

    private fun backendLabel(): String {
        val info = (engine.state.value as? EngineState.Ready)?.info
        val p = engine.currentLoadParams
        val threads = p?.let { " · ${it.threads} threads (${it.threadsBatch} for prompts)" }.orEmpty()
        return if (info?.onGpu == true) {
            "GPU (${_uiState.value.gpu?.description ?: "Vulkan"}) ${info.gpuLayers}/${info.nLayer + 1} layers + CPU$threads"
        } else {
            "CPU (NEON + KleidiAI)$threads"
        }
    }

    private suspend fun bench(maxTokens: Int): GenerationStats? {
        var stats: GenerationStats? = null
        engine.generate(
            listOf(ChatTurn("user", BENCH_PROMPT)),
            GenerationParams(maxTokens = maxTokens, temperature = 0.0f, seed = 42),
        ).collect { event ->
            if (event is GenerationEvent.Done) stats = event.stats
        }
        return stats?.takeIf { it.genTokens > 0 }
    }

    /**
     * Finds the fastest configuration on this phone by measuring it:
     * 1. CPU: generation speed with different thread counts (changed live, no reloads).
     * 2. GPU + CPU (if a Vulkan GPU exists): all layers offloaded, with the best CPU threads.
     * Saves the winner and leaves the model loaded that way.
     */
    fun autoTune() {
        val path = engine.currentModelPath()
        if (path == null) {
            _uiState.update { it.copy(error = "Load a model first (Models tab)") }
            return
        }
        tuneJob = viewModelScope.launch {
            val original = app.settingsRepository.settings.first()
            val trials = mutableListOf<TuneTrial>()
            fun publish(step: String?) = _uiState.update {
                it.copy(isTuning = step != null, tuneStep = step, tuneTrials = trials.toList(), error = null)
            }
            publish("Loading on the CPU…")

            val base = original.loadParams()
            val cpuParams = base.copy(gpuLayers = 0, threadsBatch = totalCores)
            if (engine.currentLoadParams?.gpuLayers != 0 || engine.currentLoadParams?.contextLength != base.contextLength) {
                if (engine.loadModel(path, cpuParams).isFailure) {
                    _uiState.update { it.copy(isTuning = false, tuneStep = null, error = "Couldn't load the model on the CPU") }
                    return@launch
                }
            }

            // Warm-up so the first trial isn't penalised by cold caches / CPU ramp-up.
            publish("Warming up…")
            bench(maxTokens = 8)

            val candidates = (listOf(2, performanceCores - 1, performanceCores, performanceCores + 1, performanceCores + 2, totalCores)
                .filter { it in 1..totalCores }).distinct().sorted()
            var bestCpu: Pair<Int, GenerationStats>? = null
            for (t in candidates) {
                publish("CPU · $t threads…")
                engine.setThreads(t, totalCores)
                val st = bench(maxTokens = 48)
                trials += TuneTrial("CPU · $t threads", st?.promptTps ?: 0.0, st?.genTps ?: 0.0, st != null)
                if (st != null && (bestCpu == null || st.genTps > bestCpu.second.genTps)) bestCpu = t to st
                publish("CPU · $t threads")
            }
            val bestThreads = bestCpu?.first ?: performanceCores

            var gpuStats: GenerationStats? = null
            if (_uiState.value.gpu != null) {
                publish("GPU + CPU · all layers…")
                val gpuParams = cpuParams.copy(gpuLayers = ALL_GPU_LAYERS, threads = bestThreads)
                val loaded = engine.loadModel(path, gpuParams)
                if (loaded.isSuccess && loaded.getOrNull()?.onGpu == true) {
                    bench(maxTokens = 8) // warm-up: first GPU run compiles pipelines
                    gpuStats = bench(maxTokens = 48)
                    trials += TuneTrial(
                        "GPU + CPU · ${loaded.getOrNull()?.gpuLayers} layers · $bestThreads threads",
                        gpuStats?.promptTps ?: 0.0, gpuStats?.genTps ?: 0.0, gpuStats != null,
                    )
                } else {
                    trials += TuneTrial("GPU + CPU", 0.0, 0.0, false, loaded.exceptionOrNull()?.message ?: "GPU not used")
                }
            }

            // Pick the winner on generation speed (what you feel in chat); prefill breaks ties.
            val cpuGen = bestCpu?.second?.genTps ?: 0.0
            val useGpu = gpuStats != null && gpuStats.genTps > cpuGen * 1.05
            val repo = app.settingsRepository
            repo.setThreads(bestThreads)
            repo.setThreadsBatch(totalCores)
            repo.setComputeMode(if (useGpu) ComputeMode.GPU_AND_CPU else ComputeMode.CPU_ONLY)
            if (useGpu) repo.setGpuLayers(ALL_GPU_LAYERS)

            publish("Applying the fastest setup…")
            val final = repo.settings.first().loadParams()
            if (engine.currentLoadParams?.gpuLayers != final.gpuLayers) engine.loadModel(path, final)
            else engine.setThreads(final.threads, final.threadsBatch)

            val winner = if (useGpu) "GPU + CPU" else "CPU with $bestThreads threads"
            val speed = if (useGpu) gpuStats!!.genTps else cpuGen
            _uiState.update {
                it.copy(
                    isTuning = false,
                    tuneStep = null,
                    tuneTrials = trials.toList(),
                    tuneSummary = "Fastest: $winner — %.1f tok/s generation. Saved.".format(speed) +
                        if (gpuStats != null && !useGpu) " (The GPU was slower on this model, so CPU stays.)" else "",
                )
            }
        }
    }

    fun stopTune() {
        tuneJob?.cancel()
        _uiState.update { it.copy(isTuning = false, tuneStep = null) }
    }

    fun clearNotice() {
        app.gpuCrashNotice = null
        _uiState.update { it.copy(notice = null) }
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PerformanceViewModel(app) as T
    }
}
