package com.llamaaistudio.app.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.datastore.PerformanceProfile
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.util.Locale

@Composable
fun PerformanceScreen(app: LlamaAiStudioApp) {
    val viewModel: PerformanceViewModel = viewModel(factory = PerformanceViewModel.Factory(app))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SystemStatsCard(uiState)
            BackendCard(uiState)
            if (settings != null) {
                ProfileCard(settings!!.profile, viewModel::setProfile)
                TuningCard(
                    threads = settings!!.threads,
                    contextLength = settings!!.contextLength,
                    onThreadsChange = viewModel::setThreads,
                    onContextChange = viewModel::setContextLength,
                )
            }
            BenchmarkCard(
                uiState = uiState,
                modelLoaded = engineState is EngineState.Ready,
                onRun = viewModel::runBenchmark,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SystemStatsCard(uiState: PerfUiState) {
    SectionCard("Device") {
        val usedFraction = if (uiState.totalMemoryBytes > 0) {
            1f - (uiState.availableMemoryBytes.toFloat() / uiState.totalMemoryBytes.toFloat())
        } else 0f
        Text("Memory: ${formatGb(uiState.totalMemoryBytes - uiState.availableMemoryBytes)} / ${formatGb(uiState.totalMemoryBytes)} used")
        LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        uiState.thermalHeadroom?.let { headroom ->
            val status = when {
                headroom < 0.3f -> "Cool"
                headroom < 0.7f -> "Warm"
                else -> "Throttling likely"
            }
            Text("Thermal headroom: %.0f%% — %s".format(headroom * 100, status))
        } ?: Text("Thermal headroom: unavailable on this device", color = StudioTextMuted)
    }
}

@Composable
private fun BackendCard(uiState: PerfUiState) {
    SectionCard("Inference backend") {
        Text("CPU: ARM64 NEON-optimised ggml kernels, KleidiAI micro-kernels on supported Tensor cores")
        Spacer(Modifier.height(4.dp))
        Text(
            if (uiState.vulkanAvailable) "Vulkan GPU backend: available"
            else "Vulkan GPU backend: not compiled into this build (CPU-only build — cross-compiling " +
                "ggml-vulkan's shader generator for Android is still experimental; see README)",
            color = StudioTextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text("Recommended threads for this device: ${uiState.recommendedThreads}", color = StudioTextMuted)
    }
}

@Composable
private fun ProfileCard(current: PerformanceProfile, onSelect: (PerformanceProfile) -> Unit) {
    SectionCard("Performance profile") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = current == PerformanceProfile.BATTERY_SAVER, onClick = { onSelect(PerformanceProfile.BATTERY_SAVER) }, label = { Text("Battery saver") })
            FilterChip(selected = current == PerformanceProfile.BALANCED, onClick = { onSelect(PerformanceProfile.BALANCED) }, label = { Text("Balanced") })
            FilterChip(selected = current == PerformanceProfile.MAX_PERFORMANCE, onClick = { onSelect(PerformanceProfile.MAX_PERFORMANCE) }, label = { Text("Max performance") })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Changes thread count and context length used the next time you load a model.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )
    }
}

// "Max performance" sets threads = recommendedThreads + 1, which on an 8-recommended-thread
// device (plausible on the Pixel 9's 8/9-core Tensor G4) is 9 — a value a 1f..8f slider range
// can't represent. Material3's Slider is not guaranteed to just clamp a value outside its
// declared valueRange, so this range must always be wide enough to hold whatever the profiles
// can actually produce, and the seed value is coerced into it defensively regardless.
private const val MAX_THREADS_SLIDER = 16f
private const val MIN_CONTEXT_SLIDER = 1024f
private const val MAX_CONTEXT_SLIDER = 16384f

@Composable
private fun TuningCard(
    threads: Int,
    contextLength: Int,
    onThreadsChange: (Int) -> Unit,
    onContextChange: (Int) -> Unit,
) {
    var threadsSlider by remember(threads) {
        mutableFloatStateOf(threads.toFloat().coerceIn(1f, MAX_THREADS_SLIDER))
    }
    var contextSlider by remember(contextLength) {
        mutableFloatStateOf(contextLength.toFloat().coerceIn(MIN_CONTEXT_SLIDER, MAX_CONTEXT_SLIDER))
    }

    SectionCard("Manual tuning") {
        Text("Threads: ${threadsSlider.toInt()}")
        Slider(
            value = threadsSlider.coerceIn(1f, MAX_THREADS_SLIDER),
            onValueChange = { threadsSlider = it },
            onValueChangeFinished = { onThreadsChange(threadsSlider.toInt()) },
            valueRange = 1f..MAX_THREADS_SLIDER,
            steps = 14,
        )
        Spacer(Modifier.height(8.dp))
        Text("Context length: ${contextSlider.toInt()}")
        Slider(
            value = contextSlider.coerceIn(MIN_CONTEXT_SLIDER, MAX_CONTEXT_SLIDER),
            onValueChange = { contextSlider = it },
            onValueChangeFinished = { onContextChange(contextSlider.toInt()) },
            valueRange = MIN_CONTEXT_SLIDER..MAX_CONTEXT_SLIDER,
            steps = 14,
        )
    }
}

@Composable
private fun BenchmarkCard(uiState: PerfUiState, modelLoaded: Boolean, onRun: () -> Unit) {
    SectionCard("Benchmark") {
        Text(
            "Runs a fixed prompt against the currently loaded model and measures real prefill " +
                "and generation throughput — not an estimate.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRun, enabled = modelLoaded && !uiState.isBenchmarking) {
            if (uiState.isBenchmarking) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Run benchmark")
            }
        }
        uiState.lastResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text("Backend: ${result.backend}")
            Text("Prefill: %.1f tok/s (%d tokens)".format(result.promptTps, result.promptTokens))
            Text("Generation: %.1f tok/s (%d tokens)".format(result.genTps, result.genTokens))
        }
        uiState.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatGb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
