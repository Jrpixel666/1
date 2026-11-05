package com.llamaaistudio.app.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.ChatSessionSummary
import com.llamaaistudio.app.ui.chat.ConfirmDialog
import com.llamaaistudio.app.ui.chat.TextInputDialog
import com.llamaaistudio.app.ui.chat.shareText
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import java.io.File

private sealed interface SessionDialog {
    data class Rename(val summary: ChatSessionSummary) : SessionDialog
    data class SystemPrompt(val summary: ChatSessionSummary) : SessionDialog
    data class Clear(val summary: ChatSessionSummary) : SessionDialog
    data class Delete(val summary: ChatSessionSummary) : SessionDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(app: LlamaAiStudioApp, onOpenChat: () -> Unit, onBack: () -> Unit = onOpenChat) {
    val viewModel: SessionsViewModel = viewModel(factory = SessionsViewModel.Factory(app))
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<SessionDialog?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chat")
                    }
                },
                title = {
                    Column {
                        Text("Sessions", style = MaterialTheme.typography.titleMedium)
                        val count = sessions?.size
                        if (count != null) {
                            Text(
                                if (query.isBlank()) "$count saved" else "$count matching",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextMuted,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.newSession(onOpenChat) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search titles and messages…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            val list = sessions
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> EmptyState(searching = query.isNotBlank())
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val pinned = list.filter { it.session.pinned }
                    val others = list.filterNot { it.session.pinned }
                    if (pinned.isNotEmpty()) {
                        item(key = "h_pinned") { SectionHeader("Pinned") }
                        items(pinned, key = { it.session.id }) { summary ->
                            SessionCard(
                                summary = summary,
                                active = summary.session.id == activeId,
                                onOpen = { viewModel.open(summary.session.id); onOpenChat() },
                                onTogglePin = { viewModel.togglePinned(summary) },
                                onAction = { dialog = it },
                                onDuplicate = { viewModel.duplicate(summary.session.id) },
                                onExport = { viewModel.export(summary.session.id) { t, md -> shareText(context, t, md) } },
                            )
                        }
                    }
                    if (others.isNotEmpty()) {
                        if (pinned.isNotEmpty()) item(key = "h_recent") { SectionHeader("Recent") }
                        items(others, key = { it.session.id }) { summary ->
                            SessionCard(
                                summary = summary,
                                active = summary.session.id == activeId,
                                onOpen = { viewModel.open(summary.session.id); onOpenChat() },
                                onTogglePin = { viewModel.togglePinned(summary) },
                                onAction = { dialog = it },
                                onDuplicate = { viewModel.duplicate(summary.session.id) },
                                onExport = { viewModel.export(summary.session.id) { t, md -> shareText(context, t, md) } },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is SessionDialog.Rename -> TextInputDialog(
            title = "Rename session",
            initial = d.summary.session.title,
            singleLine = true,
            onConfirm = { viewModel.rename(d.summary.session.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is SessionDialog.SystemPrompt -> TextInputDialog(
            title = "System prompt",
            initial = d.summary.session.systemPrompt,
            singleLine = false,
            supportingText = "Applies to this session only. Leave empty for none.",
            onConfirm = { viewModel.updateSystemPrompt(d.summary.session.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is SessionDialog.Clear -> ConfirmDialog(
            title = "Clear messages?",
            message = "All ${d.summary.messageCount} messages in \"${d.summary.session.title}\" will be removed. The session itself is kept.",
            confirmLabel = "Clear",
            onConfirm = { viewModel.clearMessages(d.summary.session.id); dialog = null },
            onDismiss = { dialog = null },
        )
        is SessionDialog.Delete -> ConfirmDialog(
            title = "Delete session?",
            message = "\"${d.summary.session.title}\" and all of its messages will be deleted.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(d.summary.session.id); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = StudioTextMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun EmptyState(searching: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Forum, contentDescription = null, tint = StudioTextMuted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (searching) "No sessions match your search" else "No sessions yet",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "Search looks at session titles and every message." else "Start one with New session.",
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextMuted,
        )
    }
}

@Composable
private fun SessionCard(
    summary: ChatSessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onAction: (SessionDialog) -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
) {
    val session = summary.session
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (active) StudioOrange.copy(alpha = 0.15f) else StudioPanel,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = StudioOrange,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        session.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) StudioOrange else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                summary.lastMessage?.takeIf { it.isNotBlank() }?.let { last ->
                    Text(
                        last.replace(Regex("\\s+"), " ").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (summary.messageCount == 1) "1 message" else "${summary.messageCount} messages")
                        append(" · ")
                        append(
                            DateUtils.getRelativeTimeSpanString(
                                session.updatedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE,
                            )
                        )
                        session.modelFilePath?.let { append(" · ").append(File(it).nameWithoutExtension) }
                        if (active) append(" · open")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    if (session.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (session.pinned) "Unpin" else "Pin",
                    tint = if (session.pinned) StudioOrange else StudioTextMuted,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Session options", tint = StudioTextMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onAction(SessionDialog.Rename(summary)) },
                    )
                    DropdownMenuItem(
                        text = { Text("System prompt") },
                        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        onClick = { menuOpen = false; onAction(SessionDialog.SystemPrompt(summary)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share transcript") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear messages") },
                        leadingIcon = { Icon(Icons.Filled.ClearAll, contentDescription = null) },
                        enabled = summary.messageCount > 0,
                        onClick = { menuOpen = false; onAction(SessionDialog.Clear(summary)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onAction(SessionDialog.Delete(summary)) },
                    )
                }
            }
        }
    }
}
