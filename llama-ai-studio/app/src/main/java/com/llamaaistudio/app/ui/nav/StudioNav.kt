package com.llamaaistudio.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notes
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
import com.llamaaistudio.app.ui.image.ImageGenScreen
import com.llamaaistudio.app.ui.notes.NotesScreen
import com.llamaaistudio.app.ui.perf.PerformanceScreen

enum class StudioDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Chat("chat", "Chat", Icons.Filled.Chat, Icons.Outlined.Chat),
    Notes("notes", "Notes", Icons.Filled.Notes, Icons.Outlined.Notes),
    Image("image", "Image", Icons.Filled.Image, Icons.Outlined.Image),
    Models("models", "Models", Icons.Filled.Download, Icons.Outlined.Download),
    Dev("dev", "Dev Studio", Icons.Filled.Code, Icons.Outlined.Code),
    Perf("perf", "Performance", Icons.Filled.Speed, Icons.Outlined.Speed),
}

@Composable
fun StudioBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    NavigationBar {
        StudioDestination.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                    )
                },
                label = { Text(dest.label) },
            )
        }
    }
}

@Composable
fun StudioNavHost(navController: NavHostController, app: LlamaAiStudioApp) {
    NavHost(navController = navController, startDestination = StudioDestination.Chat.route) {
        composable(StudioDestination.Chat.route) {
            ChatScreen(app = app)
        }
        composable(StudioDestination.Notes.route) {
            NotesScreen(app = app)
        }
        composable(StudioDestination.Image.route) {
            ImageGenScreen(app = app)
        }
        composable(StudioDestination.Models.route) {
            ModelsScreen(app = app)
        }
        composable(StudioDestination.Dev.route) {
            DevStudioScreen(app = app)
        }
        composable(StudioDestination.Perf.route) {
            PerformanceScreen(app = app)
        }
    }
}
