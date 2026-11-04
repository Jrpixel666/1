package com.llamaaistudio.app.ui.devstudio

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
import com.llamaaistudio.app.devstudio.TermuxBridge
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevStudioScreen(app: LlamaAiStudioApp) {
    val viewModel: DevStudioViewModel = viewModel(factory = DevStudioViewModel.Factory(app))
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.selectedProject == null) {
        ProjectListScreen(viewModel, projects)
    } else if (uiState.openFile != null) {
        FileEditorScreen(viewModel, uiState)
    } else {
        ProjectDetailScreen(app, viewModel, uiState)
    }
}

@Composable
private fun ProjectListScreen(viewModel: DevStudioViewModel, projects: List<DevProjectEntity>) {
    var showNewDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
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
    app: LlamaAiStudioApp,
    viewModel: DevStudioViewModel,
    uiState: DevStudioUiState,
) {
    val project = uiState.selectedProject ?: return
    val root = File(project.rootPath)

    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.closeProject() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            ToolchainCard(app, viewModel)

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

@Composable
private fun ToolchainCard(app: LlamaAiStudioApp, viewModel: DevStudioViewModel) {
    var showToolchainDetails by remember { mutableStateOf(false) }

    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Build toolchain", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Spacer(Modifier.height(4.dp))
            Text(
                if (viewModel.termuxInstalled) "Termux detected" else "Termux not installed",
                color = if (viewModel.termuxInstalled) StudioSuccess else StudioTextMuted,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showToolchainDetails = !showToolchainDetails }) {
                Text(if (showToolchainDetails) "Hide ARM64 compatibility notes" else "Show ARM64 compatibility notes")
            }
            if (showToolchainDetails) {
                TermuxBridge.components.forEach { c ->
                    Text(
                        "${c.name}: ${if (c.arm64Compatible) "✓" else "✗"} ${c.note}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.arm64Compatible) StudioTextMuted else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!viewModel.termuxInstalled) {
                    Button(onClick = {
                        app.startActivity(TermuxBridge.installTermuxIntent())
                    }) { Text("Install Termux") }
                } else {
                    Button(onClick = { viewModel.buildViaTermux() }) { Text("Build via Termux") }
                }
                TextButton(onClick = { viewModel.exportToDownloads() }) { Text("Export to Downloads") }
            }
        }
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
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
