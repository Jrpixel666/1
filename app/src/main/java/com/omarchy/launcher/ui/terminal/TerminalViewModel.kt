package com.omarchy.launcher.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omarchy.launcher.AppContainer
import com.omarchy.launcher.termux.TermuxDispatchResult
import com.omarchy.launcher.termux.TermuxOutputBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalViewModel(private val container: AppContainer, private val appContext: Context) : ViewModel() {

    val outputLines: StateFlow<List<String>> = TermuxOutputBus.lines

    fun isTermuxInstalled() = container.termuxController.isTermuxInstalled()
    fun hasRunCommandPermission() = container.termuxController.hasRunCommandPermission()

    fun openTermux() = container.termuxController.openTermux()

    fun installArchLinuxArm() = dispatchAsset("termux/install_archlinux_arm.sh", "install Arch Linux ARM")

    fun applyOmarchyTheme() = dispatchAsset("termux/omarchy_theme.sh", "apply Omarchy theme")

    fun runLocalCommand(command: String) {
        TermuxOutputBus.append("\$ $command")
        viewModelScope.launch {
            val output = withContext(Dispatchers.IO) { runLocalShell(command) }
            TermuxOutputBus.appendBlock(output)
        }
    }

    private fun dispatchAsset(assetPath: String, label: String) {
        viewModelScope.launch {
            val script = withContext(Dispatchers.IO) {
                appContext.assets.open(assetPath).bufferedReader().use { it.readText() }
            }
            when (val result = container.termuxController.runScript(script, label)) {
                TermuxDispatchResult.Dispatched -> Unit
                TermuxDispatchResult.TermuxNotInstalled ->
                    TermuxOutputBus.append("[error] Termux is not installed. Install it from F-Droid first.")
                TermuxDispatchResult.PermissionNotGranted ->
                    TermuxOutputBus.append("[error] RUN_COMMAND permission not granted yet. Grant it in the permission prompt, or in Android Settings > Apps > Omarchy Launcher > Permissions, then retry. Termux also needs 'allow-external-apps=true' in ~/.termux/termux.properties.")
                is TermuxDispatchResult.Failed ->
                    TermuxOutputBus.append("[error] ${result.reason}")
            }
        }
    }

    /** Runs a tiny, safe local command directly in our own app sandbox - no proot/Termux involved. */
    private fun runLocalShell(command: String): String {
        val safeCommands = setOf("date", "pwd", "whoami", "echo omarchy-launcher")
        if (command !in safeCommands && !command.startsWith("echo ")) {
            return "[local shell only supports: ${safeCommands.joinToString()} - use the Arch buttons above for anything else]"
        }
        return try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    companion object {
        fun factory(container: AppContainer, appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TerminalViewModel(container, appContext) as T
            }
    }
}
