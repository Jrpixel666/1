package com.omarchy.launcher.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.agent.AutomationActionType
import com.omarchy.launcher.agent.AutomationTriggerType
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.AutomationRule
import java.util.UUID

@Composable
fun SettingsScreen(
    apps: List<AppInfo>,
    rules: List<AutomationRule>,
    automationsEnabled: Boolean,
    onBack: () -> Unit,
    onAutomationsEnabledChanged: (Boolean) -> Unit,
    onAddRule: (AutomationRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenAgentLog: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var showAddRule by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Automations", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Local if-this-then-that rules. Fully on-device, nothing is sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = automationsEnabled, onCheckedChange = onAutomationsEnabledChanged)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAddRule = true }) { Text("+ add rule") }
                OutlinedButton(onClick = onOpenAgentLog) { Text("agent log") }
                OutlinedButton(onClick = onOpenTerminal) { Text("terminal") }
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(rules, key = { it.id }) { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                            Text(
                                "${triggerDescription(apps, rule)} -> ${AutomationActionType.fromId(rule.actionType)?.label ?: rule.actionType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rule.enabled, onCheckedChange = { onToggleRule(rule.id, it) })
                            IconButton(onClick = { onDeleteRule(rule.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete rule", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRule) {
        AddRuleDialog(
            apps = apps,
            onDismiss = { showAddRule = false },
            onConfirm = { rule -> onAddRule(rule); showAddRule = false },
        )
    }
}

private fun appLabel(apps: List<AppInfo>, packageName: String): String =
    apps.firstOrNull { it.packageName == packageName }?.label ?: packageName

private fun triggerDescription(apps: List<AppInfo>, rule: AutomationRule): String =
    when (AutomationTriggerType.fromId(rule.triggerType)) {
        AutomationTriggerType.APP_OPENED -> "when ${appLabel(apps, rule.triggerPackage)} opens"
        AutomationTriggerType.TIME_OF_DAY -> "at %02d:%02d".format(rule.triggerHour, rule.triggerMinute)
    }

@Composable
private fun AddRuleDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (AutomationRule) -> Unit,
) {
    var triggerType by remember { mutableStateOf(AutomationTriggerType.APP_OPENED) }
    var triggerTypeMenuExpanded by remember { mutableStateOf(false) }
    var triggerApp by remember { mutableStateOf<AppInfo?>(apps.firstOrNull()) }
    var triggerMenuExpanded by remember { mutableStateOf(false) }
    var triggerHourText by remember { mutableStateOf("8") }
    var triggerMinuteText by remember { mutableStateOf("0") }
    var actionType by remember { mutableStateOf(AutomationActionType.TOAST) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var actionArg by remember { mutableStateOf("") }
    var openAppTarget by remember { mutableStateOf<AppInfo?>(null) }
    var openAppMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New automation") },
        text = {
            Column {
                Text("Trigger:", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { triggerTypeMenuExpanded = true }) {
                        Text(triggerType.label)
                    }
                    DropdownMenu(expanded = triggerTypeMenuExpanded, onDismissRequest = { triggerTypeMenuExpanded = false }) {
                        AutomationTriggerType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(type.label) }, onClick = { triggerType = type; triggerTypeMenuExpanded = false })
                        }
                    }
                }

                if (triggerType == AutomationTriggerType.APP_OPENED) {
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { triggerMenuExpanded = true }) {
                            Text(triggerApp?.label ?: "choose app")
                        }
                        DropdownMenu(expanded = triggerMenuExpanded, onDismissRequest = { triggerMenuExpanded = false }) {
                            apps.take(100).forEach { app ->
                                DropdownMenuItem(text = { Text(app.label) }, onClick = { triggerApp = app; triggerMenuExpanded = false })
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = triggerHourText,
                            onValueChange = { triggerHourText = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("hour (0-23)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = triggerMinuteText,
                            onValueChange = { triggerMinuteText = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("min (0-59)") },
                            singleLine = true,
                        )
                    }
                }

                Text("Do this:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                Box {
                    OutlinedButton(onClick = { actionMenuExpanded = true }) {
                        Text(actionType.label)
                    }
                    DropdownMenu(expanded = actionMenuExpanded, onDismissRequest = { actionMenuExpanded = false }) {
                        AutomationActionType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(type.label) }, onClick = { actionType = type; actionMenuExpanded = false })
                        }
                    }
                }

                if (actionType == AutomationActionType.OPEN_APP) {
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { openAppMenuExpanded = true }) {
                            Text(openAppTarget?.label ?: "choose app to open")
                        }
                        DropdownMenu(expanded = openAppMenuExpanded, onDismissRequest = { openAppMenuExpanded = false }) {
                            apps.take(100).forEach { app ->
                                DropdownMenuItem(text = { Text(app.label) }, onClick = { openAppTarget = app; openAppMenuExpanded = false })
                            }
                        }
                    }
                } else if (actionType.needsArg) {
                    OutlinedTextField(
                        value = actionArg,
                        onValueChange = { actionArg = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text(if (actionType == AutomationActionType.SET_TIMER) "minutes" else "message") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val arg = if (actionType == AutomationActionType.OPEN_APP) (openAppTarget?.packageName ?: "") else actionArg
                if (actionType.needsArg && arg.isBlank()) return@Button

                val rule = if (triggerType == AutomationTriggerType.APP_OPENED) {
                    val trigger = triggerApp ?: return@Button
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "${trigger.label} -> ${actionType.label}",
                        triggerType = AutomationTriggerType.APP_OPENED.id,
                        triggerPackage = trigger.packageName,
                        actionType = actionType.id,
                        actionArg = arg,
                    )
                } else {
                    val hour = (triggerHourText.toIntOrNull() ?: 8).coerceIn(0, 23)
                    val minute = (triggerMinuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "%02d:%02d -> %s".format(hour, minute, actionType.label),
                        triggerType = AutomationTriggerType.TIME_OF_DAY.id,
                        triggerHour = hour,
                        triggerMinute = minute,
                        actionType = actionType.id,
                        actionArg = arg,
                    )
                }
                onConfirm(rule)
            }) { Text("Create") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
