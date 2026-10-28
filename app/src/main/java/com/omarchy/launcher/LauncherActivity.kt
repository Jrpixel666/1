package com.omarchy.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.omarchy.launcher.termux.TermuxConstants
import com.omarchy.launcher.termux.TermuxOutputBus
import com.omarchy.launcher.ui.Routes
import com.omarchy.launcher.ui.OmarchyNavHost
import com.omarchy.launcher.ui.theme.OmarchyLauncherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {

    private var navControllerRef: NavHostController? = null

    private val requestTermuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        TermuxOutputBus.append(
            if (granted) "[ok] Termux RUN_COMMAND permission granted."
            else "[error] Termux RUN_COMMAND permission denied - Arch install buttons won't work until it's granted."
        )
    }

    // --- Native (AppWidgetHost) widget add flow: allocate -> bind (maybe via system dialog) ->
    // configure (if the widget has one) -> persist. Only an Activity can launch these system
    // flows, so this coordination lives here rather than in a ViewModel.
    private var pendingWidgetId: Int = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val bindWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val provider = pendingProvider
        if (result.resultCode == RESULT_OK && provider != null) {
            proceedToConfigureOrFinish(pendingWidgetId, provider)
        } else {
            cancelPendingWidget()
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            finishAddingWidget(pendingWidgetId)
        } else {
            cancelPendingWidget()
        }
    }

    private fun requestAddNativeWidget(provider: AppWidgetProviderInfo) {
        val container = (application as OmarchyLauncherApplication).container
        val id = container.nativeWidgetHostManager.allocateAppWidgetId()
        pendingWidgetId = id
        pendingProvider = provider

        val boundDirectly = container.nativeWidgetHostManager.bindAppWidgetIdIfAllowed(id, provider.provider)
        if (boundDirectly) {
            proceedToConfigureOrFinish(id, provider)
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
            }
            bindWidgetLauncher.launch(bindIntent)
        }
    }

    private fun proceedToConfigureOrFinish(widgetId: Int, provider: AppWidgetProviderInfo) {
        val configureComponent = provider.configure
        if (configureComponent == null) {
            finishAddingWidget(widgetId)
        } else {
            val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configureComponent
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            configureWidgetLauncher.launch(configureIntent)
        }
    }

    private fun finishAddingWidget(widgetId: Int) {
        val container = (application as OmarchyLauncherApplication).container
        lifecycleScope.launch {
            val current = container.nativeWidgetPreferences.activeWidgetIds.first()
            container.nativeWidgetPreferences.setActiveWidgetIds(current + widgetId)
        }
        pendingWidgetId = -1
        pendingProvider = null
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            (application as OmarchyLauncherApplication).container.nativeWidgetHostManager.deleteAppWidgetId(pendingWidgetId)
        }
        pendingWidgetId = -1
        pendingProvider = null
    }

    override fun onResume() {
        super.onResume()
        (application as OmarchyLauncherApplication).container.nativeWidgetHostManager.startListening()
    }

    override fun onPause() {
        (application as OmarchyLauncherApplication).container.nativeWidgetHostManager.stopListening()
        super.onPause()
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
                    onRequestAddNativeWidget = { provider -> requestAddNativeWidget(provider) },
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
