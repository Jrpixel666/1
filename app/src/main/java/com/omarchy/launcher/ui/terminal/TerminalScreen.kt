package com.omarchy.launcher.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omarchy.launcher.AppContainer

@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val viewModel: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(container, context))
    val lines by viewModel.outputLines.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("terminal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }

            if (!viewModel.isTermuxInstalled()) {
                Text(
                    text = "Termux is not installed. Get it from F-Droid (not the unmaintained Play Store build), then come back here.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else if (!viewModel.hasRunCommandPermission()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    OutlinedButton(onClick = onRequestTermuxPermission) {
                        Text("Grant Termux RUN_COMMAND permission")
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.installArchLinuxArm() }) { Text("Install Arch ARM") }
                Button(onClick = { viewModel.applyOmarchyTheme() }) { Text("Apply Omarchy theme") }
                OutlinedButton(onClick = { viewModel.openTermux() }) { Text("Open Termux") }
            }

            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("$ local command") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (input.isNotBlank()) { viewModel.runLocalCommand(input); input = "" }
                    }),
                )
            }
        }
    }
}
