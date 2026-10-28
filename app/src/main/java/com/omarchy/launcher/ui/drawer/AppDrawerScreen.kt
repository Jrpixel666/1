package com.omarchy.launcher.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.util.FuzzySearch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerScreen(
    apps: List<AppInfo>,
    feedback: String?,
    onLaunchApp: (AppInfo) -> Unit,
    onQueryChanged: (String) -> Unit,
    query: String,
    onBack: () -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onAutomateApp: (AppInfo) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val filtered = remember(query, apps) { FuzzySearch.filter(query, apps) { it.label } }
    var menuForApp by remember { mutableStateOf<AppInfo?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text("> search or type a command", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }

            if (feedback != null) {
                Text(
                    text = "agent: $feedback",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.key }) { app ->
                    androidx.compose.foundation.layout.Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onLaunchApp(app) },
                                    onLongClick = { menuForApp = app },
                                )
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = app.label,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (menuForApp?.key == app.key) {
                            DropdownMenu(expanded = true, onDismissRequest = { menuForApp = null }) {
                                DropdownMenuItem(text = { Text("Open") }, onClick = { menuForApp = null; onLaunchApp(app) })
                                DropdownMenuItem(text = { Text("App info") }, onClick = { menuForApp = null; onAppInfo(app) })
                                DropdownMenuItem(text = { Text("Automate this app...") }, onClick = { menuForApp = null; onAutomateApp(app) })
                            }
                        }
                    }
                }
            }
        }
    }
}
