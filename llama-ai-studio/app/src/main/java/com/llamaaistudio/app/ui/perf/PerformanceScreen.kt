package com.llamaaistudio.app.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.datastore.ALL_GPU_LAYERS
import com.llamaaistudio.app.data.datastore.AUTO_GPU_LAYERS
import com.llamaaistudio.app.data.datastore.ComputeMode
import com.llamaaistudio.app.data.datastore.PerformanceProfile
import com.llamaaistudio.app.data.datastore.StudioSettings
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
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
            uiState.notice?.let { NoticeCard(it, viewModel::clearNotice) }
            SystemStatsCard(uiState)
            settings?.let { s ->
                BackendCard(viewModel, uiState, s, engineState)
                AutoTuneCard(viewModel, uiState, modelLoaded = engineState is EngineState.Ready)
                ProfileCard(s.profile, viewModel::setProfile)
                TuningCard(
                    threads = s.threads,
                    threadsBatch = s.threadsBatch,
                    contextLength = s.contextLength,
                    maxThreads = viewModel.totalCores,
                    onThreadsChange = viewModel::setThreads,
                    onThreadsBatchChange = viewModel::setThreadsBatch,
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
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioOrange.copy(alpha = 0.15f))) {
        Row(modifier = Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackendCard(viewModel: PerformanceViewModel, uiState: PerfUiState, settings: StudioSettings, engineState: EngineState) {
    val gpu = uiState.gpu
    val info = (engineState as? EngineState.Ready)?.info
    SectionCard("Inference backend") {
        Text("CPU: ${viewModel.cpuDescription}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "ARM64 NEON + KleidiAI kernels · ${viewModel.performanceCores} fast cores for generation",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )
        Spacer(Modifier.height(6.dp))
        if (gpu != null) {
            Text("GPU: ${gpu.description.ifBlank { gpu.name }} (Vulkan)", style = MaterialTheme.typography.bodyMedium)
            if (gpu.memoryTotal > 0) {
                Text(
                    "Shared memory available to the GPU: ${formatGb(gpu.memoryTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
        } else {
            Text(
                if (uiState.devices.isEmpty()) "Detecting GPU…" else "GPU: no Vulkan GPU usable on this device — CPU only",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextMuted,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Run the model on", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.computeMode == ComputeMode.CPU_ONLY,
                onClick = { viewModel.setComputeMode(ComputeMode.CPU_ONLY) },
                label = { Text("CPU only") },
            )
            FilterChip(
                selected = settings.computeMode == ComputeMode.GPU_AND_CPU,
                onClick = { viewModel.setComputeMode(ComputeMode.GPU_AND_CPU) },
                enabled = gpu != null,
                label = { Text("GPU + CPU") },
            )
        }
        if (settings.computeMode == ComputeMode.GPU_AND_CPU && gpu != null) {
            Spacer(Modifier.height(6.dp))
            Text("Layers on the GPU", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.gpuLayers == AUTO_GPU_LAYERS,
                    onClick = { viewModel.setGpuLayers(AUTO_GPU_LAYERS) },
                    label = { Text("Auto (fit to memory)") },
                )
                FilterChip(
                    selected = settings.gpuLayers != AUTO_GPU_LAYERS,
                    onClick = { if (settings.gpuLayers == AUTO_GPU_LAYERS) viewModel.setGpuLayers(ALL_GPU_LAYERS) },
                    label = { Text("Manual") },
                )
            }
            if (settings.gpuLayers == AUTO_GPU_LAYERS) {
                Text(
                    "As many layers as fit the GPU's free memory; the rest run on the CPU at the same time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
        }
        if (settings.computeMode == ComputeMode.GPU_AND_CPU && gpu != null && settings.gpuLayers != AUTO_GPU_LAYERS) {
            val maxLayers = (info?.nLayer?.plus(1) ?: 33).coerceAtLeast(1)
            val current = if (settings.gpuLayers == ALL_GPU_LAYERS) maxLayers else settings.gpuLayers.coerceIn(1, maxLayers)
            var slider by remember(current, maxLayers) { mutableFloatStateOf(current.toFloat()) }
            Spacer(Modifier.height(6.dp))
            Text(
                "Layers on the GPU: " + (if (slider.toInt() >= maxLayers) "all ($maxLayers)" else "${slider.toInt()} of $maxLayers") +
                    " — the rest run on the CPU",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = {
                    val v = slider.toInt()
                    viewModel.setGpuLayers(if (v >= maxLayers) ALL_GPU_LAYERS else v)
                },
                valueRange = 1f..maxLayers.toFloat(),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            when {
                info == null -> "No model loaded — these settings apply when you load one."
                info.onGpu -> "Loaded now: ${info.gpuLayers} of ${info.nLayer + 1} layers on the GPU, the rest on the CPU."
                else -> "Loaded now: CPU only."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (info?.onGpu == true) StudioSuccess else StudioTextMuted,
        )
        if (viewModel.needsReload(settings)) {
            Spacer(Modifier.height(6.dp))
            Button(onClick = viewModel::applyNow, enabled = !uiState.isApplying && !uiState.isTuning) {
                if (uiState.isApplying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reloading…")
                } else {
                    Text("Apply — reload model")
                }
            }
        }
    }
}

@Composable
private fun AutoTuneCard(viewModel: PerformanceViewModel, uiState: PerfUiState, modelLoaded: Boolean) {
    SectionCard("Auto-tune for max speed") {
        Text(
            "Measures this model on this phone: CPU generation with different thread counts" +
                (if (uiState.gpu != null) ", then GPU + CPU with every layer offloaded" else "") +
                ". Saves and applies whichever is fastest. Takes about a minute.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (uiState.isTuning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(uiState.tuneStep ?: "Tuning…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::stopTune) { Text("Stop") }
            }
        } else {
            Button(onClick = viewModel::autoTune, enabled = modelLoaded && !uiState.isBenchmarking && !uiState.isApplying) {
                Text(if (modelLoaded) "Auto-tune" else "Load a model first")
            }
        }
        if (uiState.tuneTrials.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val best = uiState.tuneTrials.filter { it.ok }.maxByOrNull { it.genTps }
            uiState.tuneTrials.forEach { t ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        color = if (t == best) StudioSuccess else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (t.ok) "%.1f tok/s · prompt %.0f".format(t.genTps, t.promptTps) else (t.note ?: "failed"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.ok) StudioTextMuted else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        uiState.tuneSummary?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = StudioSuccess)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(current: PerformanceProfile, onSelect: (PerformanceProfile) -> Unit) {
    SectionCard("Performance profile") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = current == PerformanceProfile.BATTERY_SAVER, onClick = { onSelect(PerformanceProfile.BATTERY_SAVER) }, label = { Text("Battery saver") })
            FilterChip(selected = current == PerformanceProfile.BALANCED, onClick = { onSelect(PerformanceProfile.BALANCED) }, label = { Text("Balanced") })
            FilterChip(selected = current == PerformanceProfile.MAX_PERFORMANCE, onClick = { onSelect(PerformanceProfile.MAX_PERFORMANCE) }, label = { Text("Max performance") })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Max performance uses the fast cores for generation, every core for prompts, and the GPU when available. Use Apply above to reload the model.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )
    }
}

private const val MIN_CONTEXT_SLIDER = 1024f
private const val MAX_CONTEXT_SLIDER = 16384f

@Composable
private fun TuningCard(
    threads: Int,
    threadsBatch: Int,
    contextLength: Int,
    maxThreads: Int,
    onThreadsChange: (Int) -> Unit,
    onThreadsBatchChange: (Int) -> Unit,
    onContextChange: (Int) -> Unit,
) {
    val maxT = maxOf(maxThreads, threads, threadsBatch, 2).toFloat()
    var threadsSlider by remember(threads) { mutableFloatStateOf(threads.toFloat().coerceIn(1f, maxT)) }
    var batchSlider by remember(threadsBatch) { mutableFloatStateOf(threadsBatch.toFloat().coerceIn(1f, maxT)) }
    var contextSlider by remember(contextLength) {
        mutableFloatStateOf(contextLength.toFloat().coerceIn(MIN_CONTEXT_SLIDER, MAX_CONTEXT_SLIDER))
    }

    SectionCard("Manual tuning") {
        Text("Generation threads: ${threadsSlider.toInt()}")
        Slider(
            value = threadsSlider,
            onValueChange = { threadsSlider = it },
            onValueChangeFinished = { onThreadsChange(threadsSlider.toInt()) },
            valueRange = 1f..maxT,
            steps = (maxT - 2).toInt().coerceAtLeast(0),
        )
        Text("Prompt-processing threads: ${batchSlider.toInt()}")
        Slider(
            value = batchSlider,
            onValueChange = { batchSlider = it },
            onValueChangeFinished = { onThreadsBatchChange(batchSlider.toInt()) },
            valueRange = 1f..maxT,
            steps = (maxT - 2).toInt().coerceAtLeast(0),
        )
        Text(
            "Thread changes apply instantly to the loaded model.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
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
