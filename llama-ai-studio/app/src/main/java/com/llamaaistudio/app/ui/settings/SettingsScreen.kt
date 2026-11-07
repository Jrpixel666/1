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
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val localState by app.localEngineState.collectAsStateWithLifecycle()
    var reloading by remember { mutableStateOf(false) }

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
            ModelStorageCard(app)
            settings?.let { s ->
                EnginesCard(
                    app = app,
                    settings = s,
                    localState = localState,
                    reloading = reloading,
                    onReload = {
                        val path = (localState as? com.llamaaistudio.app.engine.EngineState.Ready)?.modelPath
                        if (path != null) {
                            reloading = true
                            scope.launch { app.loadLocalModel(path); reloading = false }
                        }
                    },
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Cloud models (OpenRouter / OpenCode)", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                    Text(
                        "Cloud models for Chat and Web/Dev Studio. Paste an OpenRouter key " +
                            "(sk-or-…, from openrouter.ai/keys) to use OpenRouter's models (free and paid); without a key, " +
                            "OpenCode Zen's models are used — free ones need no key. The key is encrypted on this phone (Android Keystore) and only " +
                            "sent to that service. Now using: ${openCode.providerName}.",
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
                        label = { Text(if (hasKey) "Replace API key" else "API key (OpenRouter sk-or-… or OpenCode)") },
                        placeholder = { Text("Paste your OpenRouter or OpenCode key") },
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
                    Text("Chat: " + (chatModel?.let { "${openCode.providerName} · ${it.name}" } ?: "on-device model"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chatModel != null) TextButton(onClick = { openCode.selectChatModel(null) }) { Text("Chat: use local") }
                    }
                    Spacer(Modifier.width(1.dp))
                    Text(
                        "Pick models in the Models tab.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                    )
                }
            }
        }
    }
}

/** Which on-device engine runs where: LiteRT-LM backend + llama.cpp placement. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EnginesCard(
    app: LlamaAiStudioApp,
    settings: com.llamaaistudio.app.data.datastore.StudioSettings,
    localState: com.llamaaistudio.app.engine.EngineState,
    reloading: Boolean,
    onReload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val loadedLite = app.liteRtEngine.loaded
    val llamaParams = app.llamaEngine.currentLoadParams
    val ready = localState as? com.llamaaistudio.app.engine.EngineState.Ready
    val needsReload = when {
        loadedLite != null -> loadedLite.backend != settings.liteRtBackend
        llamaParams != null -> llamaParams.gpuLayers != settings.loadParams().gpuLayers
        else -> false
    }
    Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("On-device engines", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Text(
                "Models load with the engine their file needs: .litertlm → LiteRT-LM, .gguf → llama.cpp. " +
                    "Only one model is loaded at a time.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text("LiteRT-LM (Google) runs on", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    com.llamaaistudio.app.engine.LiteRtBackend.GPU to "GPU",
                    com.llamaaistudio.app.engine.LiteRtBackend.NPU to "NPU (Tensor)",
                    com.llamaaistudio.app.engine.LiteRtBackend.CPU to "CPU",
                ).forEach { (b, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.liteRtBackend == b,
                        onClick = { scope.launch { app.settingsRepository.setLiteRtBackend(b) } },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "GPU is the usual choice on Pixel. NPU only works with models built for it (often named " +
                    "for the chip); if loading fails, switch to GPU or CPU.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text("llama.cpp (GGUF) runs on", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = settings.computeMode == com.llamaaistudio.app.data.datastore.ComputeMode.CPU_ONLY,
                    onClick = { scope.launch { app.settingsRepository.setComputeMode(com.llamaaistudio.app.data.datastore.ComputeMode.CPU_ONLY) } },
                    label = { Text("CPU only") },
                )
                androidx.compose.material3.FilterChip(
                    selected = settings.computeMode == com.llamaaistudio.app.data.datastore.ComputeMode.GPU_AND_CPU,
                    onClick = {
                        scope.launch {
                            app.settingsRepository.setComputeMode(com.llamaaistudio.app.data.datastore.ComputeMode.GPU_AND_CPU)
                        }
                    },
                    label = { Text("GPU + CPU (auto-fit)") },
                )
            }
            Text(
                "Threads, context length, GPU layers, benchmark and Auto-tune are in the Perf tab.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Loaded now: " + when {
                    loadedLite != null -> "${loadedLite.name} · LiteRT-LM on ${loadedLite.backend}"
                    ready != null -> ready.info.desc + if (ready.info.onGpu) " · llama.cpp GPU + CPU" else " · llama.cpp CPU"
                    else -> "nothing"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (ready != null) StudioSuccess else StudioTextMuted,
            )
            if (needsReload) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = onReload, enabled = !reloading) {
                    Text(if (reloading) "Reloading…" else "Apply — reload model")
                }
            }
        }
    }
}

@Composable
private fun ModelStorageCard(app: LlamaAiStudioApp) {
    val repo = app.modelRepository
    val storage = repo.storage
    val location by storage.location.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Re-check "All files access" when coming back from the system settings page.
    var access by remember { mutableStateOf(storage.hasAllFilesAccess()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) access = storage.hasAllFilesAccess()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val sdCard = remember { storage.sdCardRoot() }
    fun gb(b: Long?) = b?.let { "%.1f GB free".format(it / 1e9) } ?: ""

    Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Model storage", style = MaterialTheme.typography.titleMedium, color = StudioOrange)
            Text("Where downloaded and imported models are saved.", style = MaterialTheme.typography.bodySmall, color = StudioTextMuted)
            Spacer(Modifier.height(6.dp))
            com.llamaaistudio.app.data.repo.ModelStorage.Location.entries.forEach { loc ->
                val available = when (loc) {
                    com.llamaaistudio.app.data.repo.ModelStorage.Location.SD_CARD -> sdCard != null
                    else -> true
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = location == loc, enabled = available && !busy,
                        onClick = {
                            storage.set(loc)
                            status = null
                            if (loc != com.llamaaistudio.app.data.repo.ModelStorage.Location.PHONE || access) scope.launch {
                                val n = repo.scanFolder()
                                if (n > 0) status = "Found $n model${if (n == 1) "" else "s"} already in that folder."
                            }
                        },
                    )
                    Column {
                        Text(loc.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (loc) {
                                com.llamaaistudio.app.data.repo.ModelStorage.Location.APP -> "Private to the app; removed if you uninstall. ${gb(storage.freeBytes(loc))}"
                                com.llamaaistudio.app.data.repo.ModelStorage.Location.PHONE ->
                                    "/sdcard/LlamaAIStudio/models — visible in Files, kept after uninstall. " +
                                        if (access) gb(storage.freeBytes(loc)) else "Needs All files access."
                                com.llamaaistudio.app.data.repo.ModelStorage.Location.SD_CARD ->
                                    if (sdCard == null) "No SD card inserted." else "Removable card. ${gb(storage.freeBytes(loc))}"
                            },
                            style = MaterialTheme.typography.labelSmall, color = StudioTextMuted,
                        )
                    }
                }
            }
            if (location == com.llamaaistudio.app.data.repo.ModelStorage.Location.PHONE && !access) {
                Text("Allow \"All files access\" so models can be saved and loaded from /sdcard. Until then, app storage is used.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }) { Text("Allow All files access") }
            }
            Spacer(Modifier.height(4.dp))
            Text("Saving to: ${repo.modelsDir.absolutePath}", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        status = runCatching {
                            val n = repo.moveAllTo { status = "Moving $it…" }
                            if (n == 0) "All models are already here." else "Moved $n model${if (n == 1) "" else "s"}."
                        }.getOrElse { "Move stopped: ${it.message}" }
                        busy = false
                    }
                },
            ) { Text("Move existing models here") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
