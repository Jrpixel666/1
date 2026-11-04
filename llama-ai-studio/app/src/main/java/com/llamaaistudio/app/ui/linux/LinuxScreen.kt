package com.llamaaistudio.app.ui.linux

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.linux.LinuxEnvironment
import com.llamaaistudio.app.ui.chat.ConfirmDialog
import com.llamaaistudio.app.ui.theme.StudioError
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import kotlinx.coroutines.launch

private data class QuickAction(val label: String, val command: String)

private val QUICK_ACTIONS = listOf(
    QuickAction("Install Node.js", "apk update && apk add --no-progress nodejs npm && node -v && npm -v"),
    QuickAction(
        "Install Java + Gradle",
        "apk update && apk add --no-progress openjdk17-jdk gradle && java -version && gradle --version --no-daemon",
    ),
    QuickAction("Install git", "apk add --no-progress git && git --version"),
    QuickAction("Update packages", "apk update && apk upgrade --no-progress"),
    QuickAction("What's installed", "for t in node npm java gradle git python3; do printf '%-8s ' \$t; command -v \$t >/dev/null && echo yes || echo no; done"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinuxScreen(app: LlamaAiStudioApp, onBack: () -> Unit) {
    val env = app.linux
    val terminal = app.linuxTerminal
    val state by env.state.collectAsStateWithLifecycle()
    val lines by terminal.lines.collectAsStateWithLifecycle()
    val running by terminal.running.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text("Linux", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (val s = state) {
                                is LinuxEnvironment.State.Ready -> "Alpine ${s.alpineVersion} · proot · projects in /projects"
                                is LinuxEnvironment.State.Installing -> "Installing…"
                                is LinuxEnvironment.State.Failed -> "Install failed"
                                LinuxEnvironment.State.NotInstalled -> "Not installed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = terminal::clear) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear output") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Run self-test") },
                                enabled = running == null,
                                onClick = { menuOpen = false; terminal.selfTest() },
                            )
                            DropdownMenuItem(
                                text = { Text("Reinstall Alpine") },
                                enabled = running == null,
                                onClick = { menuOpen = false; terminal.install() },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove Linux", color = StudioError) },
                                enabled = running == null && state is LinuxEnvironment.State.Ready,
                                onClick = { menuOpen = false; confirmRemove = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            StatusCard(state, running != null, onInstall = terminal::install)

            if (state is LinuxEnvironment.State.Ready) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_ACTIONS.forEach { action ->
                        AssistChip(
                            onClick = { terminal.run(action.command, action.label) },
                            enabled = running == null,
                            label = { Text(action.label) },
                        )
                    }
                }
            }

            // Terminal output
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(Color(0xFF0E0F11), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        "Output appears here. Commands run as root inside Alpine Linux; your Web and Dev " +
                            "Studio projects are at /projects/web and /projects/apps.",
                        color = StudioTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
                                color = if (line.startsWith("$ ")) StudioOrange else Color(0xFFE6E6E6),
                            )
                        }
                    }
                }
            }

            // Command line
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = state is LinuxEnvironment.State.Ready,
                    placeholder = { Text("e.g. apk add python3", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (running == null && command.isNotBlank()) { terminal.run(command); command = "" }
                    }),
                )
                Spacer(Modifier.width(4.dp))
                if (running != null) {
                    IconButton(onClick = terminal::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = StudioError)
                    }
                } else {
                    IconButton(
                        onClick = { terminal.run(command); command = "" },
                        enabled = state is LinuxEnvironment.State.Ready && command.isNotBlank(),
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run", tint = StudioOrange) }
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove Linux?",
            message = "Deletes Alpine and every package installed in it. Your Web/Dev Studio projects are not affected.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                scope.launch { env.uninstall(); terminal.print("Linux removed.\n") }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun StatusCard(state: LinuxEnvironment.State, busy: Boolean, onInstall: () -> Unit) {
    when (state) {
        is LinuxEnvironment.State.Ready -> return
        else -> {}
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioPanel),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                LinuxEnvironment.State.NotInstalled -> {
                    Text("Built-in Linux (Alpine + proot)", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A real Linux userland inside the app — install Node.js, Java, Gradle, git and more with " +
                            "one tap. No Termux, no root. First step: download Alpine Linux " +
                            "${LinuxEnvironment.ALPINE_VERSION} (about 4 MB, checksum-verified).\n\n" +
                            "Uses proot (GPL-2.0, source: github.com/termux/proot).",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstall, enabled = !busy) { Text("Install Linux") }
                }
                is LinuxEnvironment.State.Installing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state.step, style = MaterialTheme.typography.bodySmall)
                    }
                    state.progress?.let {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is LinuxEnvironment.State.Failed -> {
                    Text("Install failed", style = MaterialTheme.typography.titleMedium, color = StudioError)
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall, enabled = !busy) { Text("Try again") }
                    }
                }
                is LinuxEnvironment.State.Ready -> {}
            }
        }
    }
}

/** Compact entry point shown at the top of Dev Studio. */
@Composable
fun LinuxEntryCard(app: LlamaAiStudioApp, onOpen: () -> Unit) {
    val state by app.linux.state.collectAsStateWithLifecycle()
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = StudioPanel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Linux terminal", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                Text(
                    when (state) {
                        is LinuxEnvironment.State.Ready -> "Alpine Linux ready — Node.js, Java, Gradle via apk"
                        is LinuxEnvironment.State.Installing -> "Installing…"
                        else -> "Built-in Alpine Linux (proot) — tap to set up"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is LinuxEnvironment.State.Ready) StudioSuccess else StudioTextMuted,
                )
            }
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioTextMuted)
        }
    }
}
