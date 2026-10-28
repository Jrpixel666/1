package com.omarchy.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.AppRepository
import com.omarchy.launcher.ui.common.AppIcon
import com.omarchy.launcher.ui.common.OmarchyTopBar

@Composable
fun HomeScreen(
    favoriteApps: List<AppInfo>,
    appRepository: AppRepository,
    onLaunchApp: (AppInfo) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OmarchyTopBar()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clickable { onOpenDrawer() },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "search apps, or type a command...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            Text(
                text = "favorites",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(favoriteApps, key = { it.key }) { app ->
                    FavoriteRow(app = app, appRepository = appRepository, onClick = { onLaunchApp(app) })
                }
            }

            Column {
                androidx.compose.material3.Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Apps, contentDescription = "All apps", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Terminal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(app: AppInfo, appRepository: AppRepository, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            app = app,
            appRepository = appRepository,
            modifier = Modifier.size(20.dp).padding(end = 12.dp),
        )
        Text(
            text = "->",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = app.label,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
