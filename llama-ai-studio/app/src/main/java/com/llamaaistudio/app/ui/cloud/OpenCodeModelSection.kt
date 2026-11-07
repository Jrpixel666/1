package com.llamaaistudio.app.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llamaaistudio.app.data.cloud.CloudModel
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioSuccess
import com.llamaaistudio.app.ui.theme.StudioTextMuted

/**
 * "OpenCode · free models" search section, for embedding in a LazyColumn. Shows the key prompt
 * when no API key is set, otherwise a search box over the free models with Use buttons.
 */
fun LazyListScope.openCodeModelSection(
    title: String,
    subtitle: String,
    hasKey: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    models: List<CloudModel>,
    selectedId: String?,
    selectLabel: String,
    onSelect: (CloudModel) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    emptyText: String = "No models match.",
) {
    item(key = "oc_header") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Cloud, contentDescription = null, tint = StudioOrange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = StudioOrange)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
            }
            if (hasKey) {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh OpenCode models") }
            }
        }
    }
    if (!hasKey) {
        item(key = "oc_nokey") {
            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Add your OpenCode API key to search its free models.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSettings) { Text("Add API key") }
                }
            }
        }
        return
    }
    item(key = "oc_search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = { Text("Search cloud models…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
    }
    if (loading) {
        item(key = "oc_loading") { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
    }
    error?.let { e -> item(key = "oc_error") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
    if (!loading && error == null && models.isEmpty()) {
        item(key = "oc_empty") { Text(emptyText, color = StudioTextMuted, modifier = Modifier.padding(8.dp)) }
    }
    items(models, key = { "oc_" + it.id }) { m ->
        val selected = m.id == selectedId
        Card(
            colors = CardDefaults.cardColors(containerColor = if (selected) StudioOrange.copy(alpha = 0.15f) else StudioPanel),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append(if (m.free) "Free" else "Paid")
                            if (m.contextLength > 0) append(" · ${m.contextLength / 1024}K context")
                            if (m.acceptsImages) append(" · sees images")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.free) StudioSuccess else StudioOrange,
                    )
                    if (m.description.isNotBlank()) {
                        Text(
                            m.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (selected) {
                    Text("In use", style = MaterialTheme.typography.labelMedium, color = StudioOrange)
                } else {
                    OutlinedButton(onClick = { onSelect(m) }) { Text(selectLabel) }
                }
            }
        }
    }
    item(key = "oc_footer") { Spacer(Modifier.height(8.dp)) }
}
