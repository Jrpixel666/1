package com.omarchy.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omarchy.launcher.AppContainer
import com.omarchy.launcher.MainViewModel
import com.omarchy.launcher.ui.agent.AgentLogScreen
import com.omarchy.launcher.ui.drawer.AppDrawerScreen
import com.omarchy.launcher.ui.home.HomeScreen
import com.omarchy.launcher.ui.settings.SettingsScreen
import com.omarchy.launcher.ui.terminal.TerminalScreen
import com.omarchy.launcher.ui.widgets.WidgetEditorScreen
import com.omarchy.launcher.ui.widgets.WidgetsScreen
import com.omarchy.launcher.ui.widgets.WidgetsViewModel

object Routes {
    const val HOME = "home"
    const val DRAWER = "drawer"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
    const val AGENT_LOG = "agent_log"
    const val WIDGETS = "widgets"
    const val WIDGET_EDITOR = "widget_editor/{fileName}"

    fun widgetEditor(fileName: String) = "widget_editor/$fileName"
}

@Composable
fun OmarchyNavHost(
    container: AppContainer,
    onRequestTermuxPermission: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val feedback by viewModel.lastCommandFeedback.collectAsState()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                favoriteApps = uiState.favoriteApps,
                appRepository = container.appRepository,
                widgetRepository = container.widgetRepository,
                widgetPreferences = container.widgetPreferences,
                onWidgetAction = { action -> action.perform(context, container.appRepository) },
                onLaunchApp = { app -> viewModel.onAppLaunched(app, context) },
                onOpenDrawer = { navController.navigate(Routes.DRAWER) },
                onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DRAWER) {
            AppDrawerScreen(
                apps = uiState.rankedApps,
                appRepository = container.appRepository,
                feedback = feedback,
                query = query,
                onQueryChanged = { viewModel.onSearchQueryChanged(it, context) },
                onLaunchApp = { app -> viewModel.onAppLaunched(app, context); viewModel.clearSearch(); navController.popBackStack(Routes.HOME, false) },
                onBack = { viewModel.clearSearch(); navController.popBackStack() },
                onAppInfo = { app -> container.appRepository.openAppInfo(context, app) },
                onAutomateApp = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onRequestTermuxPermission = onRequestTermuxPermission,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                apps = uiState.allApps,
                rules = uiState.rules,
                automationsEnabled = uiState.automationsEnabled,
                onBack = { navController.popBackStack() },
                onAutomationsEnabledChanged = viewModel::setAutomationsEnabled,
                onAddRule = viewModel::addRule,
                onDeleteRule = viewModel::deleteRule,
                onToggleRule = viewModel::toggleRule,
                onOpenAgentLog = { navController.navigate(Routes.AGENT_LOG) },
                onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                onOpenWidgets = { navController.navigate(Routes.WIDGETS) },
            )
        }
        composable(Routes.AGENT_LOG) {
            AgentLogScreen(entries = uiState.agentLog, onBack = { navController.popBackStack() })
        }
        composable(Routes.WIDGETS) {
            val widgetsViewModel: WidgetsViewModel = viewModel(factory = WidgetsViewModel.factory(container))
            val widgetsState by widgetsViewModel.uiState.collectAsState()
            WidgetsScreen(
                allFiles = widgetsState.allFiles,
                activeFiles = widgetsState.activeFiles,
                onBack = { navController.popBackStack() },
                onToggleActive = widgetsViewModel::setActive,
                onMoveUp = widgetsViewModel::moveUp,
                onMoveDown = widgetsViewModel::moveDown,
                onEdit = { fileName -> navController.navigate(Routes.widgetEditor(fileName)) },
                onDelete = widgetsViewModel::delete,
                onCreateNew = widgetsViewModel::createNew,
            )
        }
        composable(Routes.WIDGET_EDITOR) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
            val widgetsViewModel: WidgetsViewModel = viewModel(factory = WidgetsViewModel.factory(container))
            WidgetEditorScreen(
                fileName = fileName,
                initialContent = remember(fileName) { widgetsViewModel.readContent(fileName) },
                onBack = { navController.popBackStack() },
                onSave = { name, content -> widgetsViewModel.saveContent(name, content) },
            )
        }
    }
}
