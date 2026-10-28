package com.omarchy.launcher.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.data.WidgetPreferences
import com.omarchy.launcher.data.WidgetRepository
import com.omarchy.launcher.widgets.OwlParser
import com.omarchy.launcher.widgets.RenderOwlWidget
import com.omarchy.launcher.widgets.WidgetAction
import com.omarchy.launcher.widgets.rememberLiveValues

/** Renders every active widget, stacked, above the home screen's favorites list. */
@Composable
fun ActiveWidgetsSection(
    widgetRepository: WidgetRepository,
    widgetPreferences: WidgetPreferences,
    onAction: (WidgetAction) -> Unit,
) {
    val context = LocalContext.current
    val activeFiles by widgetPreferences.activeWidgetFiles.collectAsState(initial = emptyList())
    if (activeFiles.isEmpty()) return

    val liveValues = rememberLiveValues(context)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        activeFiles.forEach { fileName ->
            val widget = remember(fileName) {
                runCatching { OwlParser.parse(widgetRepository.read(fileName)) }.getOrNull()
            }
            if (widget != null) {
                RenderOwlWidget(widget = widget, values = liveValues.asMap(), onAction = onAction)
            }
        }
    }
}
