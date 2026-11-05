package com.llamaaistudio.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.cloud.OpenCodeRepository
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: LlamaAiStudioApp, onBack: () -> Unit) {
    val openCode = app.openCode
    val hasKey by openCode.hasKey.collectAsStateWithLifecycle()
    val chatModel by openCode.chatModel.collectAsStateWithLifecycle()
    val visionModel by openCode.visionModel.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun test() {
        testing = true
        result = null
        scope.launch {
            val r = openCode.testKey()
            testing = false
            result = r.fold({ true to it }, { false to (it.message ?: "Failed") })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("OpenCode", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                    Text(
                        "Use OpenCode Zen's free cloud models for Chat, Web/Dev Studio and the Image assistant. " +
                            "Only free models are offered. Your key is encrypted on this phone (Android Keystore) " +
                            "and only sent to opencode.ai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (hasKey) {
                        Text("API key: ${openCode.maskedKey() ?: "saved"}", style = MaterialTheme.typography.bodyMedium, color = StudioSuccess)
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (hasKey) "Replace API key" else "API key") },
                        placeholder = { Text("Paste your OpenCode API key") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide key" else "Show key",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                openCode.setApiKey(keyInput)
                                keyInput = ""
                                showKey = false
                                test()
                            },
                            enabled = keyInput.isNotBlank(),
                        ) { Text("Save") }
                        if (hasKey) {
                            OutlinedButton(onClick = ::test, enabled = !testing) { Text("Test") }
                            TextButton(onClick = {
                                openCode.clearApiKey()
                                result = null
                            }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    result?.let { (ok, msg) ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            (if (ok) "✓ " else "✗ ") + msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) StudioSuccess else MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(OpenCodeRepository.KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Get a key at opencode.ai") }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("In use", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                    Spacer(Modifier.height(4.dp))
                    Text("Chat: " + (chatModel?.let { "OpenCode · ${it.name}" } ?: "on-device model"), style = MaterialTheme.typography.bodySmall)
                    Text("Image assistant: " + (visionModel?.let { "OpenCode · ${it.name}" } ?: "none"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chatModel != null) TextButton(onClick = { openCode.selectChatModel(null) }) { Text("Chat: use local") }
                        if (visionModel != null) TextButton(onClick = { openCode.selectVisionModel(null) }) { Text("Clear image assistant") }
                    }
                    Spacer(Modifier.width(1.dp))
                    Text(
                        "Pick models in the Models tab (chat) and Image → Models (image assistant).",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                }
            }
        }
    }
}
