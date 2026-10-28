package com.omarchy.launcher.ui.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.data.AgentLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transparency log: every automation the agent fired, newest first. The point of keeping all
 * "agentic" behavior local is defeated if the user can't see what it actually did - this screen
 * is that audit trail.
 */
@Composable
fun AgentLogScreen(entries: List<AgentLogEntry>, onBack: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("agent log", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }

            if (entries.isEmpty()) {
                Text(
                    "Nothing yet. Once an automation rule fires, it'll show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(
                            timeFormat.format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}
