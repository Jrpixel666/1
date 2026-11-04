package com.llamaaistudio.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.ui.chat.ChatScreen
import com.llamaaistudio.app.ui.devstudio.DevStudioScreen
import com.llamaaistudio.app.ui.huggingface.ModelsScreen
import com.llamaaistudio.app.ui.linux.LinuxScreen
import com.llamaaistudio.app.ui.image.ImageGenScreen
import com.llamaaistudio.app.ui.perf.PerformanceScreen
import com.llamaaistudio.app.ui.sessions.SessionsScreen
import com.llamaaistudio.app.ui.webstudio.WebStudioScreen

/** Sessions is reached from Chat's ☰ button, not the bottom bar. */
const val SESSIONS_ROUTE = "sessions"

/** The Linux terminal is reached from Dev Studio. */
const val LINUX_ROUTE = "linux"

enum class StudioDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    Web("web", "Web", Icons.Filled.Language, Icons.Outlined.Language),
    Image("image", "Image", Icons.Filled.Image, Icons.Outlined.Image),
    Models("models", "Models", Icons.Filled.Download, Icons.Outlined.Download),
    Dev("dev", "Dev", Icons.Filled.Code, Icons.Outlined.Code),
    Perf("perf", "Perf", Icons.Filled.Speed, Icons.Outlined.Speed),
}

@Composable
fun StudioBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    NavigationBar {
        StudioDestination.entries.forEach { dest ->
            val selected = currentRoute == dest.route ||
                (dest == StudioDestination.Chat && currentRoute == SESSIONS_ROUTE) ||
                (dest == StudioDestination.Dev && currentRoute == LINUX_ROUTE)
            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToTab(dest) },
                icon = {
                    Icon(
                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                    )
                },
                label = { Text(dest.label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
fun StudioNavHost(navController: NavHostController, app: LlamaAiStudioApp) {
    NavHost(navController = navController, startDestination = StudioDestination.Chat.route) {
        composable(StudioDestination.Chat.route) {
            ChatScreen(app = app, onOpenSessions = {
                navController.navigate(SESSIONS_ROUTE) { launchSingleTop = true }
            })
        }
        composable(SESSIONS_ROUTE) {
            SessionsScreen(
                app = app,
                onOpenChat = { navController.navigateToTab(StudioDestination.Chat) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(StudioDestination.Web.route) {
            WebStudioScreen(app = app)
        }
        composable(StudioDestination.Image.route) {
            ImageGenScreen(app = app)
        }
        composable(StudioDestination.Models.route) {
            ModelsScreen(app = app)
        }
        composable(StudioDestination.Dev.route) {
            DevStudioScreen(app = app, onOpenLinux = {
                navController.navigate(LINUX_ROUTE) { launchSingleTop = true }
            })
        }
        composable(LINUX_ROUTE) {
            LinuxScreen(app = app, onBack = { navController.popBackStack() })
        }
        composable(StudioDestination.Perf.route) {
            PerformanceScreen(app = app)
        }
    }
}

/** Tab-style navigation: one copy of each top-level destination, state saved/restored. */
fun NavHostController.navigateToTab(dest: StudioDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
