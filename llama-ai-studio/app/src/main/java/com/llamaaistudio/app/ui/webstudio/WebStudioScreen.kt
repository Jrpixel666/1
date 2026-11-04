package com.llamaaistudio.app.ui.webstudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.WebProjectEntity
import com.llamaaistudio.app.devstudio.TermuxBridge
import com.llamaaistudio.app.devstudio.rememberTermuxActionRunner
import com.llamaaistudio.app.ui.chat.ConfirmDialog
import com.llamaaistudio.app.ui.chat.TextInputDialog
import com.llamaaistudio.app.ui.theme.StudioError
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.io.File

@Composable
fun WebStudioScreen(app: LlamaAiStudioApp) {
    val viewModel: WebStudioViewModel = viewModel(factory = WebStudioViewModel.Factory(app))
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.selectedProject == null -> ProjectListScreen(viewModel, projects)
        uiState.previewUrl != null -> PreviewScreen(viewModel, uiState.previewUrl!!)
        uiState.openFile != null -> FileEditorScreen(viewModel, uiState)
        else -> ProjectScreen(viewModel, uiState)
    }
}

// --- project list ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(viewModel: WebStudioViewModel, projects: List<WebProjectEntity>) {
    var showNew by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<WebProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Web Studio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Describe a site · AI builds it · test it instantly",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New web project")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            if (projects.isEmpty()) {
                Text("No web projects yet. Tap + to start one.", color = StudioTextMuted)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(
                        onClick = { viewModel.openProject(project) },
                        colors = CardDefaults.cardColors(containerColor = StudioPanel),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    templateLabel(project.template) + " · port ${project.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextMuted,
                                )
                            }
                            IconButton(onClick = { toDelete = project }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = StudioTextMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        NewWebProjectDialog(
            onCreate = { name, template -> viewModel.createProject(name, template); showNew = false },
            onDismiss = { showNew = false },
        )
    }
    toDelete?.let { project ->
        ConfirmDialog(
            title = "Delete ${project.name}?",
            message = "All of this project's files will be deleted from the app.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteProject(project); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

private fun templateLabel(template: String) = if (template == "node") "Node.js server" else "Static site"

@Composable
private fun NewWebProjectDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("static") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New web project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = template == "static", onClick = { template = "static" }, label = { Text("Static site") })
                    FilterChip(selected = template == "node", onClick = { template = "node" }, label = { Text("Node.js") })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (template == "node") "public/ is served instantly by the app; server.js (/api routes) can run under Node in Termux."
                    else "HTML, CSS and JavaScript — served instantly by the app's built-in server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name, template) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- project page ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    val project = uiState.selectedProject ?: return
    BackHandler { viewModel.closeProject() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeProject() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(templateLabel(project.template), style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    }
                },
                actions = {
                    ServerBadge(
                        when {
                            uiState.serverRunning || uiState.nodeServer == ServerStatus.RUNNING -> ServerStatus.RUNNING
                            uiState.nodeServer == ServerStatus.STARTING -> ServerStatus.STARTING
                            else -> ServerStatus.STOPPED
                        }
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.status?.let { StatusCard(it, onDismiss = viewModel::clearStatus) }
            BuildCard(viewModel, uiState)
            RunCard(viewModel, uiState)
            FilesCard(viewModel, uiState)
        }
    }
}

@Composable
private fun ServerBadge(status: ServerStatus) {
    val (label, color) = when (status) {
        ServerStatus.RUNNING -> "Live" to StudioSuccess
        ServerStatus.STARTING -> "Starting" to StudioOrange
        ServerStatus.STOPPED -> "Stopped" to StudioTextMuted
        ServerStatus.UNKNOWN -> "…" to StudioTextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun StatusCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = StudioOrange.copy(alpha = 0.12f))) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = StudioTextMuted) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = StudioPanel), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun BuildCard(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    var idea by remember { mutableStateOf("") }
    SectionCard("1 · Build with AI") {
        Text(
            "Describe the site or change you want. The AI writes the HTML/CSS/JS (several files at " +
                "once if needed) using the model loaded in Models.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = idea,
            onValueChange = { idea = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. \"A dark-themed to-do list that saves tasks in localStorage\"") },
            minLines = 3,
            enabled = !uiState.isGenerating,
        )
        Spacer(Modifier.height(8.dp))
        if (uiState.isGenerating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Building… ${uiState.genTokens} tokens" +
                        if (uiState.genTps > 0) " · %.1f tok/s".format(uiState.genTps) else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::stopGeneration) { Text("Stop") }
            }
        } else {
            Button(onClick = { viewModel.generateWithAi(idea) }, enabled = idea.isNotBlank()) { Text("Build it") }
            if (!viewModel.modelLoaded) {
                Text(
                    "No model loaded — load one in Models first. Coding models (e.g. Qwen2.5-Coder) do best.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioError,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunCard(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    val context = LocalContext.current
    val project = uiState.selectedProject ?: return
    val server = uiState.server
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SectionCard("2 · Run & test") {
        Text(
            if (server.running) "Live at ${server.localUrl} — pages reload automatically when files change."
            else "Built-in server — nothing to install. Starts on port ${project.port}" +
                (if (project.template == "node") " and serves public/." else "."),
            style = MaterialTheme.typography.bodySmall,
            color = if (server.running) StudioSuccess else StudioTextMuted,
        )
        server.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = StudioError)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!server.running) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // Only for the "server running · Stop" notification; serving works either way.
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.startServer()
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start server")
                }
            } else {
                OutlinedButton(onClick = viewModel::stopServer) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
            OutlinedButton(onClick = viewModel::openPreview) {
                Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Preview")
            }
            if (server.running) {
                OutlinedButton(onClick = { server.localUrl?.let { openInBrowser(context, it) } }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Browser")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Share on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    server.lanUrl?.let { "Open $it on your laptop or another phone" }
                        ?: "Let other devices on the same Wi-Fi open the site",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.lanUrl != null) StudioSuccess else StudioTextMuted,
                )
            }
            Switch(checked = uiState.shareOnLan, onCheckedChange = viewModel::setShareOnLan)
        }
        server.lanUrl?.let { lan ->
            TextButton(onClick = {
                copyToClipboard(context, "Site address", lan)
                viewModel.setStatus("Copied $lan")
            }) { Text("Copy Wi-Fi address") }
        }

        if (project.template == "node") {
            Spacer(Modifier.height(8.dp))
            NodeTermuxSection(viewModel, uiState)
        }
    }
}

/** Optional for Node projects: run the real server.js (API routes) under Node in Termux. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeTermuxSection(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    val context = LocalContext.current
    val runTermux = rememberTermuxActionRunner(
        onDenied = { viewModel.setStatus("Termux permission denied — server.js can't be run without it.") },
    )
    var showSetup by remember { mutableStateOf(false) }

    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text("Run server.js with Node (optional)", style = MaterialTheme.typography.labelLarge)
    Text(
        when (uiState.nodeServer) {
            ServerStatus.RUNNING -> "Node server running at ${uiState.nodeUrl}"
            ServerStatus.STARTING -> "Starting in Termux…"
            else -> "Needed only for /api routes. Uses Termux and installs Node.js the first time (port $NODE_PORT)."
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (uiState.nodeServer == ServerStatus.RUNNING) StudioSuccess else StudioTextMuted,
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!viewModel.termuxInstalled) {
            OutlinedButton(onClick = { context.startActivity(TermuxBridge.installTermuxIntent()) }) { Text("Install Termux") }
        } else {
            OutlinedButton(
                onClick = { runTermux { viewModel.startNodeInTermux() } },
                enabled = uiState.nodeServer != ServerStatus.STARTING,
            ) { Text(if (uiState.nodeServer == ServerStatus.RUNNING) "Restart Node" else "Start Node") }
            if (uiState.nodeServer == ServerStatus.RUNNING || uiState.nodeServer == ServerStatus.STARTING) {
                OutlinedButton(onClick = { runTermux { viewModel.stopNodeInTermux() } }) { Text("Stop Node") }
            }
            if (uiState.nodeServer == ServerStatus.RUNNING) {
                OutlinedButton(onClick = viewModel::openNodePreview) { Text("Preview Node") }
            }
            TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide setup" else "Termux setup") }
        }
    }
    if (showSetup) {
        Text(
            "One time: open Termux and paste the setup command. It lets this app send Termux " +
                "commands and gives Termux access to the exported project files.",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                copyToClipboard(context, "Termux setup", TermuxBridge.ALLOW_EXTERNAL_APPS_COMMAND)
                viewModel.setStatus("Setup command copied — paste it in Termux and press Enter.")
            }) { Text("Copy setup command") }
            TextButton(onClick = {
                context.packageManager.getLaunchIntentForPackage("com.termux")?.let(context::startActivity)
            }) { Text("Open Termux") }
        }
    }
}

@Composable
private fun FilesCard(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    val project = uiState.selectedProject ?: return
    val root = File(project.rootPath)
    var showNewFile by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<File?>(null) }

    SectionCard("3 · Files") {
        if (uiState.files.isEmpty()) Text("No files yet.", color = StudioTextMuted)
        uiState.files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.openFile(file) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = StudioTextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        file.relativeTo(root).path,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${(file.length() + 1023) / 1024} KB", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                }
                IconButton(onClick = { toDelete = file }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete file", tint = StudioTextMuted)
                }
            }
        }
        TextButton(onClick = { showNewFile = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("New file")
        }
    }

    if (showNewFile) {
        TextInputDialog(
            title = "New file",
            initial = if (project.template == "node") "public/" else "",
            singleLine = true,
            supportingText = "Path inside the project, e.g. about.html or js/app.js",
            confirmLabel = "Create",
            onConfirm = { viewModel.createFile(it); showNewFile = false },
            onDismiss = { showNewFile = false },
        )
    }
    toDelete?.let { file ->
        ConfirmDialog(
            title = "Delete ${file.name}?",
            message = "This file will be removed from the project.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteFile(file); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

// --- editor & preview -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(viewModel: WebStudioViewModel, uiState: WebStudioUiState) {
    val file = uiState.openFile ?: return
    BackHandler { viewModel.closeFile() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeFile() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(file.name + if (uiState.dirty) " •" else "", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = viewModel::saveOpenFile, enabled = uiState.dirty) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        OutlinedTextField(
            value = uiState.openFileContent,
            onValueChange = viewModel::updateOpenFileContent,
            modifier = Modifier.padding(padding).fillMaxSize().padding(8.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewScreen(viewModel: WebStudioViewModel, url: String) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else viewModel.closePreview()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::closePreview) {
                        Icon(Icons.Filled.Close, contentDescription = "Close preview")
                    }
                },
                title = {
                    Column {
                        Text("Preview", style = MaterialTheme.typography.titleMedium)
                        Text(
                            url + " · live reload",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = { openInBrowser(context, url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize().background(Color.White),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView = this
                }
            },
            update = { wv -> if (wv.url != null && !wv.url!!.startsWith(url)) wv.loadUrl(url) },
        )
        DisposableEffect(Unit) {
            onDispose {
                webView?.destroy()
                webView = null
            }
        }
    }
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
