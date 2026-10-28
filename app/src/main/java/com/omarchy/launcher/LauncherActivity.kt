package com.omarchy.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.omarchy.launcher.termux.TermuxConstants
import com.omarchy.launcher.termux.TermuxOutputBus
import com.omarchy.launcher.ui.Routes
import com.omarchy.launcher.ui.OmarchyNavHost
import com.omarchy.launcher.ui.theme.OmarchyLauncherTheme

class LauncherActivity : ComponentActivity() {

    private var navControllerRef: NavHostController? = null

    private val requestTermuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        TermuxOutputBus.append(
            if (granted) "[ok] Termux RUN_COMMAND permission granted."
            else "[error] Termux RUN_COMMAND permission denied - Arch install buttons won't work until it's granted."
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as OmarchyLauncherApplication).container

        setContent {
            val navController = rememberNavController()
            navControllerRef = navController
            var atHome by remember { mutableStateOf(true) }

            DisposableEffect(navController) {
                val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                    atHome = destination.route == Routes.HOME
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose { navController.removeOnDestinationChangedListener(listener) }
            }

            // A home-screen launcher is the root of its task: back should never exit it.
            BackHandler(enabled = atHome) { /* swallow */ }

            OmarchyLauncherTheme {
                OmarchyNavHost(
                    container = container,
                    onRequestTermuxPermission = { requestTermuxPermission.launch(TermuxConstants.RUN_COMMAND_PERMISSION) },
                    navController = navController,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // User tapped the Home button again while already inside the launcher's own UI -
        // standard launcher behavior is to pop back to the home screen.
        setIntent(intent)
        navControllerRef?.popBackStack(Routes.HOME, inclusive = false)
    }
}
