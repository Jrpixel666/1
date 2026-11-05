package com.llamaaistudio.app.ui.image

import android.graphics.BitmapFactory
import com.llamaaistudio.app.ui.cloud.openCodeModelSection
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.GeneratedImageEntity
import com.llamaaistudio.app.data.db.LocalImageModelEntity
import com.llamaaistudio.app.data.hf.DownloadStatus
import com.llamaaistudio.app.data.hf.GgufFileOption
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import com.llamaaistudio.imagegen.ImageEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ImageGenScreen(app: LlamaAiStudioApp, onOpenSettings: () -> Unit = {}) {
    val viewModel: ImageGenViewModel = viewModel(factory = ImageGenViewModel.Factory(app))
    var tab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Generate") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Gallery") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Models") })
            }
            when (tab) {
                0 -> GenerateTab(viewModel)
                1 -> GalleryTab(viewModel, onEditStarted = { tab = 0 })
                else -> ImageModelsTab(viewModel, onOpenSettings)
            }
        }
    }
}

@Composable
private fun GenerateTab(viewModel: ImageGenViewModel) {
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val params by viewModel.genParams.flow.collectAsStateWithLifecycle()

    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (engineState) {
            is ImageEngineState.Idle -> Text(
                "No diffusion model loaded. Download or select one in the Models tab.",
                color = StudioTextMuted,
            )
            is ImageEngineState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading model…")
            }
            is ImageEngineState.Error -> Text(
                (engineState as ImageEngineState.Error).message,
                color = MaterialTheme.colorScheme.error,
            )
            is ImageEngineState.Ready -> Text(
                "Model ready: ${(engineState as ImageEngineState.Ready).modelPath.substringAfterLast('/')}",
                color = StudioSuccess,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = negativePrompt,
            onValueChange = { negativePrompt = it },
            label = { Text("Negative prompt (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (viewModel.assistantAvailable) {
            TextButton(
                onClick = {
                    viewModel.improvePrompt(prompt) { suggestion ->
                        prompt = suggestion.prompt
                        if (suggestion.negative.isNotBlank()) negativePrompt = suggestion.negative
                    }
                },
                enabled = prompt.isNotBlank() && uiState.assistantStatus == null && !uiState.isGenerating,
            ) { Text("✨ Improve prompt with AI") }
        }
        uiState.assistantStatus?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
            }
        }

        LabeledSlider("Steps", params.steps.toFloat(), 1f, 50f) { viewModel.genParams.update { p -> p.copy(steps = it.toInt()) } }
        LabeledSlider("CFG scale", params.cfgScale, 1f, 20f) { viewModel.genParams.update { p -> p.copy(cfgScale = it) } }
        LabeledSlider("Width", params.width.toFloat(), 256f, 1024f) { viewModel.genParams.update { p -> p.copy(width = (it.toInt() / 64) * 64) } }
        LabeledSlider("Height", params.height.toFloat(), 256f, 1024f) { viewModel.genParams.update { p -> p.copy(height = (it.toInt() / 64) * 64) } }
        Text(
            "Turbo/distilled models (SDXL-Turbo, SDXS, LCM) want 1-4 steps and a low CFG (~1-2). " +
                "Standard SD1.x/SDXL checkpoints want ~20-30 steps and CFG ~7.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )

        if (uiState.isGenerating) {
            LinearProgressIndicator(
                progress = { if (uiState.totalSteps > 0) uiState.currentStep.toFloat() / uiState.totalSteps else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Text("Step ${uiState.currentStep}/${uiState.totalSteps}")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.stopGeneration() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Button(
                onClick = { viewModel.generate(prompt, negativePrompt) },
                enabled = engineState is ImageEngineState.Ready && prompt.isNotBlank(),
            ) {
                Text("Generate")
            }
        }

        uiState.lastImage?.let { image ->
            Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    LocalImage(image.filePath, modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${image.width}x${image.height} · seed ${image.seed} · %.1fs".format(image.genMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                }
            }
        }

        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun LocalImage(path: String, modifier: Modifier = Modifier, maxDimension: Int = 1024) {
    // Decoded off the main thread and downsampled: decoding every full-size PNG synchronously
    // during composition stalled the UI as soon as the gallery had more than a few images.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxDimension) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun GalleryTab(viewModel: ImageGenViewModel, onEditStarted: () -> Unit) {
    val images by viewModel.gallery.collectAsStateWithLifecycle()
    val vision by viewModel.visionModel.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GeneratedImageEntity?>(null) }

    editing?.let { image ->
        EditImageDialog(
            image = image,
            assistantName = vision?.name,
            onEdit = { instruction, strength ->
                editing = null
                viewModel.editImage(image, instruction, strength)
                onEditStarted()
            },
            onDismiss = { editing = null },
        )
    }

    if (images.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No images yet. Generate one from the Generate tab.", color = StudioTextMuted)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(images, key = { it.id }) { image ->
            GalleryItem(image = image, onEdit = { editing = image }, onDelete = { viewModel.deleteFromGallery(image) })
        }
    }
}

@Composable
private fun GalleryItem(image: GeneratedImageEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(6.dp)) {
            LocalImage(
                image.filePath,
                maxDimension = 512,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 1f)
                    .padding(bottom = 4.dp),
            )
            Text(image.prompt, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = StudioTextMuted)
                }
            }
        }
    }
}

@Composable
private fun EditImageDialog(
    image: GeneratedImageEntity,
    assistantName: String?,
    onEdit: (String, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var instruction by remember { mutableStateOf("") }
    var strength by remember { mutableFloatStateOf(0.55f) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit image") },
        text = {
            Column {
                LocalImage(
                    image.filePath,
                    maxDimension = 384,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("What should change?") },
                    placeholder = { Text("e.g. make it night time with neon lights") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                Text("Change amount: ${(strength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.2f..0.9f)
                Text(
                    if (assistantName != null) "$assistantName looks at the picture and writes the new prompt; " +
                        "the on-device model redraws it from the original."
                    else "The on-device model redraws it from the original using your words. Pick an Image " +
                        "assistant in Models for smarter edits.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEdit(instruction, strength) }, enabled = instruction.isNotBlank()) { Text("Edit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImageModelsTab(viewModel: ImageGenViewModel, onOpenSettings: () -> Unit) {
    val installed by viewModel.installedModels.collectAsStateWithLifecycle()
    val uiState by viewModel.modelsUiState.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val cloud by viewModel.cloud.collectAsStateWithLifecycle()
    val hasKey by viewModel.openCodeHasKey.collectAsStateWithLifecycle()
    val vision by viewModel.visionModel.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(hasKey) {
        if (hasKey && !cloud.loaded) viewModel.refreshVisionModels()
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 1) On-device diffusion models (Hugging Face)
        item(key = "local_header") {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("On-device image models", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                Text("Stable Diffusion GGUF checkpoints — generate and edit offline", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
            }
        }
        item(key = "local_search") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search e.g. \"sdxl-turbo gguf\"…") },
                    singleLine = true,
                )
                IconButton(onClick = { viewModel.search() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = StudioOrange)
                }
            }
        }
        if (installed.isNotEmpty()) {
            item(key = "installed_header") {
                Text("Installed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
            }
            items(installed, key = { "inst_" + it.filePath }) { model ->
                val isActive = (engineState as? ImageEngineState.Ready)?.modelPath == model.filePath
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.displayName)
                        Text(
                            "${model.quantization ?: "?"} · ${formatBytes(model.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                        )
                    }
                    Button(onClick = { viewModel.loadModel(model) }, enabled = !isActive) {
                        Text(if (isActive) "Loaded" else "Load")
                    }
                    TextButton(onClick = { viewModel.deleteModel(model) }) { Text("Delete") }
                }
            }
        }
        val detail = uiState.expandedDetail
        if (detail != null) {
            item(key = "detail_header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(detail.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.closeDetail() }) { Text("Back") }
                }
            }
            items(viewModel.filesFor(detail), key = { "file_" + it.filename }) { file ->
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    ImageGgufFileCard(
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
        } else {
            items(uiState.results, key = { "hf_" + it.id }) { summary ->
                Card(
                    onClick = { viewModel.openDetail(summary.id) },
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(summary.id, modifier = Modifier.padding(10.dp))
                }
            }
        }
        uiState.error?.let { e -> item(key = "local_error") { Text(e, color = MaterialTheme.colorScheme.error) } }

        // 2) OpenCode: free models that can *see* images (Zen has no image-generation models)
        openCodeModelSection(
            title = "OpenCode · free image assistants",
            subtitle = "Free models that understand images — they write prompts and plan edits; " +
                "the on-device model draws. (OpenCode has no image-generating models.)",
            hasKey = hasKey,
            query = cloud.query,
            onQueryChange = viewModel::onCloudQueryChange,
            loading = cloud.loading,
            error = cloud.error,
            models = cloud.filtered,
            selectedId = vision?.id,
            selectLabel = "Use",
            onSelect = viewModel::useVisionModel,
            onRefresh = { viewModel.refreshVisionModels(force = true) },
            onOpenSettings = onOpenSettings,
            emptyText = "No free image-capable models right now.",
        )
    }
}

@Composable
private fun ImageGgufFileCard(
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.filename, modifier = Modifier.weight(1f))
                if (recommended) AssistChip(onClick = {}, label = { Text("Fits your device") })
            }
            Text(
                "${file.quantization ?: "?"} · ${formatBytes(file.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(8.dp))
            when (download?.status) {
                null, DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                    if (download?.status == DownloadStatus.FAILED) {
                        Text(download.error ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = onDownload) { Text("Download") }
                }
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> {
                    LinearProgressIndicator(progress = { download.progressFraction }, modifier = Modifier.fillMaxWidth())
                    Row {
                        Text(
                            if (download.status == DownloadStatus.VERIFYING) "Verifying…"
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
                DownloadStatus.PAUSED -> Row {
                    Text("Paused")
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume") }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "Cancel") }
                }
                DownloadStatus.COMPLETED -> Row {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StudioSuccess)
                    Spacer(Modifier.width(6.dp))
                    Text("Installed", color = StudioSuccess)
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
        value /= 1024; unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
