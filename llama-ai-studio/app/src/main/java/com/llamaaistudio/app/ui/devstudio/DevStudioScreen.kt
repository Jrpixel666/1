package com.llamaaistudio.app.ui.devstudio

import androidx.compose.foundation.layout.Arrangement
import com.llamaaistudio.app.linux.LinuxEnvironment
import com.llamaaistudio.app.linux.LinuxJob
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.DevProjectEntity
import com.llamaaistudio.app.ui.linux.LinuxEntryCard
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevStudioScreen(app: LlamaAiStudioApp, onOpenLinux: () -> Unit = {}) {
    val viewModel: DevStudioViewModel = viewModel(factory = DevStudioViewModel.Factory(app))
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.selectedProject == null) {
        ProjectListScreen(app, viewModel, projects, onOpenLinux)
    } else if (uiState.openFile != null) {
        FileEditorScreen(viewModel, uiState)
    } else {
        ProjectDetailScreen(viewModel, uiState, onOpenLinux)
    }
}

@Composable
private fun ProjectListScreen(
    app: LlamaAiStudioApp,
    viewModel: DevStudioViewModel,
    projects: List<DevProjectEntity>,
    onOpenLinux: () -> Unit,
) {
    var showNewDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            LinuxEntryCard(app, onOpenLinux)
            Spacer(Modifier.height(16.dp))
            Text("Projects", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (projects.isEmpty()) {
                Text("No projects yet. Tap + to scaffold one.", color = StudioTextMuted)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(
                        onClick = { viewModel.openProject(project) },
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(project.name, style = MaterialTheme.typography.bodyLarge)
                                Text(project.template, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                            }
                            TextButton(onClick = { viewModel.deleteProject(project) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onCreate = { name, template -> viewModel.createProject(name, template); showNewDialog = false },
            onDismiss = { showNewDialog = false },
        )
    }
}

@Composable
private fun NewProjectDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("empty-compose") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = template == "empty-compose", onClick = { template = "empty-compose" }, label = { Text("Android (Views)") })
                    FilterChip(selected = template == "library", onClick = { template = "library" }, label = { Text("Kotlin console") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name, template) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectDetailScreen(
    viewModel: DevStudioViewModel,
    uiState: DevStudioUiState,
    onOpenLinux: () -> Unit,
) {
    val project = uiState.selectedProject ?: return
    val root = File(project.rootPath)

    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.closeProject() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(project.name, style = MaterialTheme.typography.titleLarge)
            }

            Text("Files", style = MaterialTheme.typography.titleMedium, color = StudioOrange, modifier = Modifier.padding(top = 12.dp))
            uiState.files.forEach { file ->
                TextButton(onClick = { viewModel.openFile(file) }) {
                    Text(file.relativeTo(root).path)
                }
            }

            Spacer(Modifier.height(16.dp))
            AiBuilderCard(viewModel, uiState)

            Spacer(Modifier.height(16.dp))
            BuildCard(viewModel, project.id, onOpenLinux)

            uiState.status?.let {
                Spacer(Modifier.height(8.dp))
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
                    Text(it, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AiBuilderCard(viewModel: DevStudioViewModel, uiState: DevStudioUiState) {
    var idea by remember { mutableStateOf("") }

    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Build with AI", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Spacer(Modifier.height(4.dp))
            Text(
                if (uiState.openFile != null) "Describe a change — the AI rewrites ${uiState.openFile!!.name} to match."
                else "Describe what you want — the AI writes it into the project's main source file. Open a different file first to target that one instead.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = idea,
                onValueChange = { idea = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. \"Add a button that shows the current time\"") },
                minLines = 2,
                enabled = !uiState.isGeneratingCode,
            )
            Spacer(Modifier.height(8.dp))
            if (uiState.isGeneratingCode) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(onClick = {}, enabled = false) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Generating…")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.stopCodeGeneration() }) { Text("Stop") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${uiState.codeGenTokens} tokens")
                        if (uiState.codeGenTps > 0) append(" · %.1f tok/s".format(uiState.codeGenTps))
                        uiState.codeGenEtaSeconds?.let { eta ->
                            append(" · up to ~${formatEta(eta)} left")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
                Text(
                    "That's a ceiling, not a prediction — the model decides when the file is " +
                        "done and usually stops well before the cap.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            } else {
                Button(
                    onClick = { viewModel.generateWithAi(idea) },
                    enabled = idea.isNotBlank(),
                ) {
                    Text("Generate")
                }
            }
            Text(
                "Quality depends entirely on the model loaded in Chat/Models — a coding-focused " +
                    "model (e.g. a Qwen2.5-Coder GGUF) will do much better than a general chat model.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuildCard(viewModel: DevStudioViewModel, projectId: Long, onOpenLinux: () -> Unit) {
    val linux by viewModel.linuxState.collectAsStateWithLifecycle()
    val toolchainReady by viewModel.toolchainReady.collectAsStateWithLifecycle()
    val buildAny by viewModel.buildStatus.collectAsStateWithLifecycle()
    val logAny by viewModel.buildLog.collectAsStateWithLifecycle()
    // The build job is app-wide: show it here only for this project (or the shared SDK setup).
    val mine = buildAny.owner == null || buildAny.owner == projectId
    val build = if (mine) buildAny else LinuxJob.Status()
    val log = if (mine) logAny else emptyList()
    val busyElsewhere = buildAny.running && !mine
    val android = viewModel.isAndroidProject()
    var showLog by remember { mutableStateOf(true) }
    var confirmLicense by remember { mutableStateOf(false) }

    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Build & run", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    linux !is LinuxEnvironment.State.Ready -> "Builds run on this phone inside the built-in Linux. Set it up first."
                    !toolchainReady -> "One-time setup: Java 17 and the Android SDK (with ARM64 aapt2) inside Linux — about 250 MB."
                    android -> "Gradle builds a debug APK on this phone; then install it with one tap. The first build downloads Gradle and the Android plugins."
                    else -> "Gradle compiles and runs this Kotlin program on this phone."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    linux !is LinuxEnvironment.State.Ready -> Button(onClick = onOpenLinux) { Text("Set up Linux") }
                    busyElsewhere -> Text(
                        "Another build is running: ${buildAny.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                    build.running -> {
                        Button(onClick = {}, enabled = false) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (toolchainReady) "Building…" else "Installing…")
                        }
                        TextButton(onClick = viewModel::stopBuild) { Text("Stop") }
                    }
                    !toolchainReady -> Button(onClick = { confirmLicense = true }) { Text("Install build tools") }
                    else -> {
                        Button(onClick = viewModel::build) { Text(if (android) "Build APK" else "Run") }
                        if (android && viewModel.builtApk() != null) {
                            OutlinedButton(onClick = viewModel::installApk) { Text("Install APK") }
                        }
                    }
                }
                TextButton(onClick = { viewModel.exportToDownloads() }) { Text("Export to Downloads") }
            }

            if (build.running && toolchainReady && build.owner == projectId) {
                BuildEta(build.startedAt, log, viewModel.lastBuild(projectId, android))
            }
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (build.label ?: "Log") + when {
                            build.running -> " · running"
                            build.lastExitCode == 0 -> " · ok"
                            build.lastExitCode != null -> " · failed (${build.lastExitCode})"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            build.running -> StudioTextMuted
                            build.lastExitCode == 0 -> StudioSuccess
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
                }
                if (showLog) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.LaunchedEffect(log.size) {
                        if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .background(Color(0xFF0E0F11), RoundedCornerShape(6.dp))
                                .padding(6.dp),
                        ) {
                            items(log.size) { i ->
                                Text(log[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFE6E6E6))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmLicense) {
        AlertDialog(
            onDismissRequest = { confirmLicense = false },
            title = { Text("Install build tools?") },
            text = {
                Text(
                    "Downloads OpenJDK 17 (Alpine), the Android 35 platform and build-tools 34 from Google, and " +
                        "ARM64 builds of aapt2/aidl/zipalign (github.com/lzhiyong/android-sdk-tools). Every download is " +
                        "checksum-verified. About 250 MB.\n\nBy continuing you accept the Android SDK License " +
                        "Agreement (developer.android.com/studio/terms)."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLicense = false; viewModel.installToolchain() }) { Text("Accept & install") }
            },
            dismissButton = { TextButton(onClick = { confirmLicense = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileEditorScreen(viewModel: DevStudioViewModel, uiState: DevStudioUiState) {
    val file = uiState.openFile ?: return

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.saveOpenFile() }) {
                Icon(Icons.Filled.Save, contentDescription = "Save")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.closeFile() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(file.name + if (uiState.dirty) " *" else "", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = uiState.openFileContent,
                onValueChange = viewModel::updateOpenFileContent,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun formatEta(seconds: Double): String =
    if (seconds >= 60) "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s" else "${seconds.toInt()}s"

/** Elapsed time, and — from the last successful build — progress and time remaining. */
@Composable
private fun BuildEta(startedAt: Long?, log: List<String>, last: Pair<Long, Int>?) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val elapsed = (now - (startedAt ?: now)).coerceAtLeast(0)
    val tasksDone = log.count { it.startsWith("> Task ") }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (last == null) {
            Text(
                "Elapsed ${fmtDuration(elapsed)} · first build of this project usually takes 5–15 min " +
                    "(downloads Gradle and the Android plugins). Later builds show an ETA.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
        } else {
            val (lastMs, lastTasks) = last
            // Task progress when we have it; otherwise fall back to time.
            val fraction = when {
                lastTasks > 0 && tasksDone > 0 -> (tasksDone.toFloat() / lastTasks).coerceIn(0f, 0.97f)
                else -> (elapsed.toFloat() / lastMs).coerceIn(0f, 0.97f)
            }
            val remaining = when {
                fraction > 0.1f -> (elapsed / fraction - elapsed).toLong()
                else -> (lastMs - elapsed)
            }.coerceAtLeast(0)
            androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "${(fraction * 100).toInt()}% · elapsed ${fmtDuration(elapsed)} · " +
                    (if (remaining > 0) "about ${fmtDuration(remaining)} left" else "finishing…") +
                    " (last build: ${fmtDuration(lastMs)})",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
        }
    }
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}
