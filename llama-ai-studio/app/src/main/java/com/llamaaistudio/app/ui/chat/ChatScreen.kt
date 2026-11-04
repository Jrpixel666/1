package com.llamaaistudio.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.ChatMessageEntity
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioPanelSecondary
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import com.llamaaistudio.app.util.WorkspaceBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: LlamaAiStudioApp, onOpenSessions: () -> Unit = {}) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(app))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val genParams by viewModel.genParams.flow.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        WorkspaceBridge.consumePendingChatContext()?.let { inputText = it }
    }

    LaunchedEffect(uiState.info) {
        uiState.info?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    val currentSession = uiState.currentSession
    val streamingHere = uiState.isGenerating && uiState.generatingSessionId == uiState.currentSessionId

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable(enabled = currentSession != null) { showRename = true }) {
                        Text(
                            currentSession?.title ?: "Llama AI Studio",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        EngineStatusLabel(engineState, uiState.liveGenTps, uiState.isGenerating)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Filled.Menu, contentDescription = "Sessions")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.newSession() }) {
                        Icon(Icons.Filled.Add, contentDescription = "New session")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Generation settings")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }, enabled = currentSession != null) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Session options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename session") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { showMenu = false; showRename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("System prompt") },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                onClick = { showMenu = false; showSystemPrompt = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Export to note") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                                onClick = { showMenu = false; viewModel.exportCurrentSessionToNote() },
                            )
                            DropdownMenuItem(
                                text = { Text("All sessions") },
                                leadingIcon = { Icon(Icons.Filled.Forum, contentDescription = null) },
                                onClick = { showMenu = false; onOpenSessions() },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear messages") },
                                leadingIcon = { Icon(Icons.Filled.ClearAll, contentDescription = null) },
                                onClick = { showMenu = false; confirmClear = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete session", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showMenu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                isGenerating = uiState.isGenerating,
                enabled = engineState is EngineState.Ready && currentSession != null,
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                onStop = { viewModel.stopGeneration() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (engineState) {
                is EngineState.Idle -> NoModelBanner()
                is EngineState.Loading -> LoadingModelBanner()
                is EngineState.Error -> ErrorBanner((engineState as EngineState.Error).message)
                is EngineState.Ready -> {}
            }
            if (uiState.isGenerating && !streamingHere) {
                Surface(color = StudioPanelSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Another session is still generating a reply…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                }
            }
            if (uiState.messages.isEmpty() && !streamingHere) {
                EmptySessionHint(
                    systemPrompt = currentSession?.systemPrompt.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessageList(
                    messages = uiState.messages,
                    streamingText = uiState.streamingText,
                    isGenerating = streamingHere,
                    modifier = Modifier.weight(1f),
                    onSaveAsNote = viewModel::saveMessageAsNote,
                )
            }
        }
    }

    if (showRename && currentSession != null) {
        TextInputDialog(
            title = "Rename session",
            initial = currentSession.title,
            singleLine = true,
            onConfirm = { viewModel.renameCurrentSession(it); showRename = false },
            onDismiss = { showRename = false },
        )
    }

    if (showSystemPrompt && currentSession != null) {
        TextInputDialog(
            title = "System prompt",
            initial = currentSession.systemPrompt,
            singleLine = false,
            supportingText = "Applies to this session only. Leave empty for none.",
            onConfirm = { viewModel.updateCurrentSystemPrompt(it); showSystemPrompt = false },
            onDismiss = { showSystemPrompt = false },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear messages?",
            message = "All messages in \"${currentSession?.title.orEmpty()}\" will be removed. The session itself is kept.",
            confirmLabel = "Clear",
            onConfirm = { viewModel.clearCurrentSession(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete session?",
            message = "\"${currentSession?.title.orEmpty()}\" and all of its messages will be deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                uiState.currentSessionId?.let(viewModel::deleteSession)
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showSettings) {
        GenerationSettingsDialog(
            params = genParams,
            onUpdate = { viewModel.updateGenerationParams { _ -> it } },
            onDismiss = { showSettings = false },
        )
    }

    uiState.error?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } },
        )
    }
}

@Composable
private fun EngineStatusLabel(state: EngineState, liveTps: Double, isGenerating: Boolean) {
    val text = when (state) {
        is EngineState.Idle -> "No model loaded"
        is EngineState.Loading -> "Loading model…"
        is EngineState.Error -> "Model error"
        is EngineState.Ready -> if (isGenerating) {
            "Generating · %.1f tok/s".format(liveTps)
        } else {
            state.info.desc
        }
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
}

@Composable
private fun NoModelBanner() {
    Surface(color = StudioPanelSecondary, modifier = Modifier.fillMaxWidth()) {
        Text(
            "No model loaded. Open the Models tab to download or import a GGUF model, then load it from there.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoadingModelBanner() {
    Surface(color = StudioPanelSecondary, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading model…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onError)
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessageEntity>,
    streamingText: String,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    onSaveAsNote: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (isGenerating) 1 else 0
    LaunchedEffect(itemCount, streamingText.length / 64) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { msg -> MessageBubble(msg, onSaveAsNote) }
        if (isGenerating) {
            item(key = "streaming") {
                StreamingBubble(streamingText)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessageEntity, onSaveAsNote: (String) -> Unit) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isUser) StudioOrange.copy(alpha = 0.18f) else StudioPanel,
            ),
            modifier = Modifier.padding(start = if (isUser) 48.dp else 0.dp, end = if (isUser) 0.dp else 48.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                if (!isUser && message.genTps == null && message.stopReason != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (message.stopReason == "error") "Interrupted by an error" else "Stopped early",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onSaveAsNote(message.content) }) {
                            Text("Save as note", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (!isUser && message.genTps != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%.1f tok/s · %d tokens · %s".format(
                                message.genTps, message.genTokens ?: 0, message.stopReason ?: ""
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onSaveAsNote(message.content) }) {
                            Text("Save as note", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel),
            modifier = Modifier.padding(end = 48.dp),
        ) {
            Text(
                text.ifEmpty { "…" },
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = StudioPanel) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Message…" else "Load a model first") },
                enabled = enabled && !isGenerating,
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = onSend, enabled = enabled && text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = StudioOrange)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerationSettingsDialog(
    params: GenerationParams,
    onUpdate: (GenerationParams) -> Unit,
    onDismiss: () -> Unit,
) {
    var temperature by remember(params) { mutableStateOf(params.temperature) }
    var topP by remember(params) { mutableStateOf(params.topP) }
    var topK by remember(params) { mutableStateOf(params.topK.toFloat()) }
    var repeatPenalty by remember(params) { mutableStateOf(params.repeatPenalty) }
    var maxTokens by remember(params) { mutableStateOf(params.maxTokens.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generation settings") },
        text = {
            Column {
                LabeledSlider("Temperature", temperature, 0f, 2f) { temperature = it }
                LabeledSlider("Top-P", topP, 0f, 1f) { topP = it }
                LabeledSlider("Top-K", topK, 0f, 100f) { topK = it }
                LabeledSlider("Repeat penalty", repeatPenalty, 1f, 1.5f) { repeatPenalty = it }
                LabeledSlider("Max tokens", maxTokens, 32f, 2048f) { maxTokens = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpdate(
                    params.copy(
                        temperature = temperature,
                        topP = topP,
                        topK = topK.toInt(),
                        repeatPenalty = repeatPenalty,
                        maxTokens = maxTokens.toInt(),
                    )
                )
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: %.2f".format(value), style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun EmptySessionHint(systemPrompt: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Start the conversation", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "The session is titled from your first message. Rename it, change its system " +
                "prompt or export it from the ⋮ menu.",
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextMuted,
        )
        if (systemPrompt.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "System: $systemPrompt",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    singleLine: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    supportingText: String? = null,
    confirmLabel: String = "Save",
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                maxLines = if (singleLine) 1 else 8,
                supportingText = supportingText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
