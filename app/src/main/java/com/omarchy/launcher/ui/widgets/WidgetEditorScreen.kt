package com.omarchy.launcher.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.widgets.OwlParseException
import com.omarchy.launcher.widgets.OwlParser

/**
 * The on-device widget editor - OWL's README calls this out as the point: "a small declarative
 * language you can edit on the phone." Parse errors show inline (like `owl-check`'s error
 * output, minus the generated preview image) but never block saving - a widget mid-edit is
 * allowed to be momentarily invalid.
 */
@Composable
fun WidgetEditorScreen(
    fileName: String,
    initialContent: String,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var text by remember(fileName) { mutableStateOf(initialContent) }
    val parseError = remember(text) {
        try {
            OwlParser.parse(text)
            null
        } catch (e: OwlParseException) {
            e.message
        } catch (e: Exception) {
            "unexpected error: ${e.message}"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(fileName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onSave(fileName, text) }) { Text("Save") }
            }

            if (parseError != null) {
                Text(
                    "parse error: $parseError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize().padding(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
