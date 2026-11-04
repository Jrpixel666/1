package com.llamaaistudio.app.devstudio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns a runner that executes an action needing Termux's RUN_COMMAND permission, asking
 * for the permission first when it hasn't been granted. (It is a runtime permission: merely
 * declaring it in the manifest — all this app did before — leaves every Termux call failing.)
 */
@Composable
fun rememberTermuxActionRunner(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending.value
        pending.value = null
        if (granted) action?.invoke() else onDenied()
    }
    return remember(launcher) {
        { action: () -> Unit ->
            if (TermuxBridge.hasRunCommandPermission(context)) {
                action()
            } else {
                pending.value = action
                launcher.launch(TermuxBridge.RUN_COMMAND_PERMISSION)
            }
        }
    }
}
