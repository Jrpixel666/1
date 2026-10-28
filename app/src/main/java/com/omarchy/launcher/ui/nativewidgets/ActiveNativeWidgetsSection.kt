package com.omarchy.launcher.ui.nativewidgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchy.launcher.data.NativeWidgetPreferences
import com.omarchy.launcher.nativewidgets.NativeWidgetHostManager

/** Renders every active real Android widget, stacked, on the home screen. */
@Composable
fun ActiveNativeWidgetsSection(
    nativeWidgetPreferences: NativeWidgetPreferences,
    hostManager: NativeWidgetHostManager,
) {
    val activeIds by nativeWidgetPreferences.activeWidgetIds.collectAsState(initial = emptyList())
    if (activeIds.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        activeIds.forEach { id ->
            NativeWidgetHostView(appWidgetId = id, hostManager = hostManager, modifier = Modifier.fillMaxWidth())
        }
    }
}
