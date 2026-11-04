package com.llamaaistudio.app.ui.huggingface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.LocalModelEntity
import com.llamaaistudio.app.data.hf.DownloadStatus
import com.llamaaistudio.app.data.hf.GgufFileOption
import com.llamaaistudio.app.data.hf.HfModelSummary
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(app: LlamaAiStudioApp) {
    val viewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory(app))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installed by viewModel.installedModels.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Discover") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("My Library (${installed.size})") })
            }

            if (tab == 0) {
                DiscoverTab(viewModel, uiState, downloads)
            } else {
                LibraryTab(viewModel, installed, engineState)
            }
        }
    }
}

@Composable
private fun DiscoverTab(
    viewModel: ModelsViewModel,
    uiState: ModelsUiState,
    downloads: Map<String, com.llamaaistudio.app.data.hf.DownloadState>,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search GGUF models on Hugging Face…") },
                singleLine = true,
            )
            IconButton(onClick = { viewModel.search() }) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = StudioOrange)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (uiState.isSearching) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        uiState.expandedDetail?.let { detail ->
            ModelDetailPanel(
                viewModel = viewModel,
                modelId = detail.id,
                files = viewModel.filesFor(detail),
                downloads = downloads,
                onClose = viewModel::closeDetail,
            )
        } ?: run {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.results, key = { it.id }) { summary ->
                    ModelSummaryCard(summary) { viewModel.openDetail(summary.id) }
                }
            }
        }

        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ModelSummaryCard(summary: HfModelSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(summary.id, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "${formatCount(summary.downloads ?: 0)} downloads · ${formatCount(summary.likes ?: 0)} likes",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
        }
    }
}

@Composable
private fun ModelDetailPanel(
    viewModel: ModelsViewModel,
    modelId: String,
    files: List<GgufFileOption>,
    downloads: Map<String, com.llamaaistudio.app.data.hf.DownloadState>,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(modelId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Back") }
        }
        Divider()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(files, key = { it.filename }) { file ->
                GgufFileCard(
                    file = file,
                    download = downloads["${file.modelId}/${file.filename}"],
                    recommended = viewModel.fitsInMemory(file.sizeBytes),
                    onDownload = { viewModel.download(file) },
                    onPause = { viewModel.pauseDownload(file) },
                    onResume = { viewModel.resumeDownload(file) },
                    onCancel = { viewModel.cancelDownload(file) },
                )
            }
        }
    }
}

@Composable
private fun GgufFileCard(
    file: GgufFileOption,
    download: com.llamaaistudio.app.data.hf.DownloadState?,
    recommended: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(file.filename, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (recommended) {
                    AssistChip(onClick = {}, label = { Text("Fits your device") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${file.quantization ?: "?"} · ${formatBytes(file.sizeBytes)} on disk · " +
                    "~${formatBytes(file.estimatedRamBytes())} RAM" +
                    (file.paramCount?.let { " · %.1fB params".format(it / 1e9) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(8.dp))

            when (download?.status) {
                null, DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                    if (download?.status == DownloadStatus.FAILED) {
                        Text(download.error ?: "Download failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = onDownload) { Text("Download") }
                }
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> {
                    LinearProgressIndicator(
                        progress = { download.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (download.status == DownloadStatus.VERIFYING) "Verifying SHA-256…"
                            else "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        if (download.status == DownloadStatus.DOWNLOADING) {
                            IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, contentDescription = "Pause") }
                        }
                        IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "Cancel") }
                    }
                }
                DownloadStatus.PAUSED -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Paused · ${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}")
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume") }
                        IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "Cancel") }
                    }
                }
                DownloadStatus.COMPLETED -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StudioSuccess)
                        Spacer(Modifier.width(6.dp))
                        Text("Installed", color = StudioSuccess)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(
    viewModel: ModelsViewModel,
    installed: List<LocalModelEntity>,
    engineState: EngineState,
) {
    if (installed.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text("No models installed yet. Use Discover to search Hugging Face for GGUF models.")
        }
        return
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(installed, key = { it.filePath }) { model ->
            val isActive = (engineState as? EngineState.Ready)?.modelPath == model.filePath
            Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${model.quantization ?: "?"} · ${formatBytes(model.sizeBytes)}" +
                            if (model.sha256Verified) " · SHA-256 verified" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.loadModel(model) }, enabled = !isActive) {
                            Text(if (isActive) "Loaded" else "Load")
                        }
                        TextButton(onClick = { viewModel.deleteModel(model) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
